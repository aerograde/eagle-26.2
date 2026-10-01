/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Phase 3.3b: the fake "native" heap backing the org.lwjgl.system.MemoryUtil
 * linkage stub in this module. Vanilla 26.2 stores NativeImage pixels and vertex
 * data behind raw long addresses (MemoryUtil.nmemAlloc + memPutInt/memCopy/...);
 * the browser has no native memory, so addresses are SLOT-ENCODED:
 *
 *   address = ((slot + 1) << 32) | byteOffsetWithinAllocation
 *
 * Resolution is O(1) (no range search): slot indexes an ArrayList of
 * little-endian heap ByteBuffers. Slot -1 (address 0) is NULL. Address
 * arithmetic used by the game ({@code base + offset}) stays inside one
 * allocation, so it never carries into the slot bits.
 */
public class EaglerFakeHeap {

	private static final List<ByteBuffer> slots = new ArrayList<>();
	private static final List<Integer> freeSlots = new ArrayList<>();
	private static int cachedSlot = -1;
	private static ByteBuffer cachedBuffer;
	private static long liveBytes;
	private static long peakBytes;
	private static long allocationCount;
	private static long freeCount;
	private static int liveAllocations;

	private EaglerFakeHeap() {
	}

	public static synchronized long alloc(long size) {
		if (size < 0L || size > 0x7FFFFFF0L) {
			return 0L;
		}
		// HOLISTIC BUFFER FIX (#18/#19/#20): fake-heap slots back the vanilla MemoryUtil/NativeImage/
		// vertex path. Always a HEAP ByteBuffer.allocate() on BOTH targets (wasm-gc = TByteBufferImpl,
		// plain byte[]). A slice reaching the WebGL upload (Int8Array.fromJavaBuffer) is copied into a
		// fresh Int8Array by the patched TJSBufferHelper.getArrayBufferView, so no linear-memory direct
		// buffer is needed; the font channel.read path also needs the heap form. The slot-encoded
		// addressing is unchanged. JS build byte-identical (it already used allocate()).
		ByteBuffer buf = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
		int slot;
		if (!freeSlots.isEmpty()) {
			slot = freeSlots.remove(freeSlots.size() - 1);
			slots.set(slot, buf);
		} else {
			slot = slots.size();
			slots.add(buf);
		}
		if (cachedSlot == slot) {
			cachedBuffer = buf;
		}
		liveBytes += size;
		peakBytes = Math.max(peakBytes, liveBytes);
		allocationCount++;
		liveAllocations++;
		return ((long) (slot + 1)) << 32;
	}

	public static synchronized long realloc(long address, long newSize) {
		if (address == 0L) {
			return alloc(newSize);
		}
		ByteBuffer old = resolveBase(address);
		long addr = alloc(newSize);
		if (addr == 0L) {
			return 0L;
		}
		ByteBuffer neu = resolveBase(addr);
		int copy = Math.min(old.capacity(), neu.capacity());
		// Every fake-heap allocation is a heap ByteBuffer. A byte-at-a-time Java loop
		// made multi-megabyte vertex/image growth catastrophically slow under Wasm-GC.
		System.arraycopy(old.array(), old.arrayOffset(), neu.array(), neu.arrayOffset(), copy);
		free(address);
		return addr;
	}

	public static synchronized void free(long address) {
		if (address == 0L) {
			return;
		}
		int slot = slotOf(address);
		if (slot >= 0 && slot < slots.size() && slots.get(slot) != null) {
			ByteBuffer old = slots.get(slot);
			slots.set(slot, null);
			if (cachedSlot == slot) {
				cachedSlot = -1;
				cachedBuffer = null;
			}
			freeSlots.add(slot);
			liveBytes -= old.capacity();
			freeCount++;
			liveAllocations--;
		}
	}

	public static synchronized long getLiveBytes() {
		return liveBytes;
	}

	public static synchronized long getPeakBytes() {
		return peakBytes;
	}

	public static synchronized long getAllocationCount() {
		return allocationCount;
	}

	public static synchronized long getFreeCount() {
		return freeCount;
	}

	public static synchronized int getLiveAllocations() {
		return liveAllocations;
	}

	private static int slotOf(long address) {
		return ((int) (address >>> 32)) - 1;
	}

	public static int offsetOf(long address) {
		return (int) address;
	}

	/** The whole allocation containing the address (position/limit untouched). */
	public static ByteBuffer resolveBase(long address) {
		int slot = slotOf(address);
		if (slot == cachedSlot && cachedBuffer != null) {
			return cachedBuffer;
		}
		ByteBuffer b;
		if (slot < 0 || slot >= slots.size() || (b = slots.get(slot)) == null) {
			throw new IllegalStateException("EaglerFakeHeap: bad native address 0x" + Long.toHexString(address));
		}
		cachedSlot = slot;
		cachedBuffer = b;
		return b;
	}

	/** A little-endian slice view [address, address+length). */
	public static ByteBuffer slice(long address, int length) {
		ByteBuffer base = resolveBase(address);
		int off = offsetOf(address);
		ByteBuffer dup = base.duplicate();
		dup.position(off).limit(off + length);
		return dup.slice().order(ByteOrder.LITTLE_ENDIAN);
	}

	/** Registers an externally-created buffer's CONTENT into a new allocation. */
	public static long wrapCopy(ByteBuffer src) {
		ByteBuffer dup = src.duplicate();
		int len = dup.remaining();
		long addr = alloc(len);
		ByteBuffer dst = resolveBase(addr);
		if (dup.hasArray()) {
			System.arraycopy(dup.array(), dup.arrayOffset() + dup.position(),
					dst.array(), dst.arrayOffset(), len);
			dup.position(dup.position() + len);
		}else {
			dst.put(dup);
		}
		return addr;
	}

	public static void copy(long src, long dst, long bytes) {
		int len = (int)bytes;
		ByteBuffer s = resolveBase(src);
		ByteBuffer d = resolveBase(dst);
		System.arraycopy(s.array(), s.arrayOffset() + offsetOf(src),
				d.array(), d.arrayOffset() + offsetOf(dst), len);
	}

	public static void set(long address, int value, long bytes) {
		ByteBuffer b = resolveBase(address);
		int from = b.arrayOffset() + offsetOf(address);
		Arrays.fill(b.array(), from, from + (int)bytes, (byte)value);
	}
}

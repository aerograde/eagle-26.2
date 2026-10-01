package org.lwjgl.system;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap;

/**
 * Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees
 * this module). Backed by {@link EaglerFakeHeap}: "native" addresses are
 * slot-encoded handles onto little-endian heap ByteBuffers, so the vanilla
 * NativeImage / ByteBufferBuilder / vertex-writer address arithmetic works
 * unchanged in the browser.
 *
 * Allocations and their owning typed views are tracked bidirectionally so
 * memAddress() resolves ByteBuffers and every free path (memFree / nmemFree /
 * stbi_image_free) purges the tracking maps.
 */
public final class MemoryUtil {

	public static final long NULL = 0L;

	private static final Map<ByteBuffer, Long> trackedAddresses = new IdentityHashMap<>();
	private static final Map<Long, ByteBuffer> trackedBuffers = new HashMap<>();
	private static final Map<Buffer, ByteBuffer> trackedViewBackings = new IdentityHashMap<>();
	private static final Map<ByteBuffer, Buffer> trackedBackingViews = new IdentityHashMap<>();

	private MemoryUtil() {
	}

	// ==== raw allocation ====

	public static long nmemAlloc(final long size) {
		return EaglerFakeHeap.alloc(size);
	}

	public static long nmemCalloc(final long num, final long size) {
		return EaglerFakeHeap.alloc(num * size); // heap buffers are zero-filled
	}

	public static long nmemRealloc(final long address, final long size) {
		return EaglerFakeHeap.realloc(address, size);
	}

	public static void nmemFree(final long address) {
		untrackAddress(address);
		EaglerFakeHeap.free(address);
	}

	/** Frees a heap address and purges buffer tracking (also used by the STB stub). */
	public static void eaglerFreeAddress(final long address) {
		nmemFree(address);
	}

	private static synchronized void untrackAddress(final long address) {
		ByteBuffer tracked = trackedBuffers.remove(address);
		if (tracked != null) {
			trackedAddresses.remove(tracked);
			untrackViews(tracked);
		}
	}

	private static void untrackViews(final ByteBuffer backing) {
		Buffer view = trackedBackingViews.remove(backing);
		if (view != null) {
			trackedViewBackings.remove(view);
		}
	}

	// ==== tracked ByteBuffer allocation ====

	public static synchronized ByteBuffer memAlloc(final int size) {
		long addr = EaglerFakeHeap.alloc(size);
		if (addr == 0L) {
			throw new OutOfMemoryError("eagler fake heap: cannot allocate " + size + " bytes");
		}
		ByteBuffer buf = EaglerFakeHeap.slice(addr, size);
		trackedAddresses.put(buf, addr);
		trackedBuffers.put(addr, buf);
		return buf;
	}

	public static ByteBuffer memCalloc(final int size) {
		return memAlloc(size);
	}

	public static synchronized ByteBuffer memRealloc(final ByteBuffer old, final int size) {
		ByteBuffer neu = memAlloc(size);
		if (old != null) {
			ByteBuffer src = old.duplicate();
			src.position(0).limit(Math.min(old.capacity(), size));
			ByteBuffer dst = neu.duplicate();
			dst.put(src);
			neu.position(Math.min(old.position(), size));
			memFree(old);
		}
		return neu;
	}

	public static synchronized void memFree(final Buffer buffer) {
		ByteBuffer backing = buffer instanceof ByteBuffer bb ? bb : trackedViewBackings.get(buffer);
		if (backing != null) {
			Long addr = trackedAddresses.remove(backing);
			untrackViews(backing);
			if (addr != null) {
				trackedBuffers.remove(addr);
				EaglerFakeHeap.free(addr);
			}
		}
	}

	// LWJGL exposes typed memFree overloads; game bytecode binds to the exact
	// ByteBuffer/IntBuffer descriptors, so both must exist (delegate to the base).
	public static void memFree(final ByteBuffer buffer) {
		memFree((Buffer) buffer);
	}

	public static void memFree(final IntBuffer buffer) {
		memFree((Buffer) buffer);
	}

	public static synchronized long memAddress(final ByteBuffer buffer) {
		if (buffer == null) {
			return 0L;
		}
		Long addr = trackedAddresses.get(buffer);
		return addr == null ? 0L : addr.longValue() + buffer.position();
	}

	/** Registers an externally-built buffer at a fresh heap address (stack/STB helper). */
	static synchronized ByteBuffer eaglerTrack(final long address, final ByteBuffer buffer) {
		trackedAddresses.put(buffer, address);
		trackedBuffers.put(address, buffer);
		return buffer;
	}

	// ==== typed views over raw addresses ====

	public static ByteBuffer memByteBuffer(final long address, final int capacity) {
		return EaglerFakeHeap.slice(address, capacity);
	}

	public static ByteBuffer memByteBuffer(final IntBuffer buffer) {
		// linkage-only in practice (UnihexProvider; no unihex fonts ship)
		ByteBuffer out = ByteBuffer.allocate(buffer.remaining() * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		out.asIntBuffer().put(buffer.duplicate());
		return out;
	}

	public static IntBuffer memIntBuffer(final long address, final int capacity) {
		return EaglerFakeHeap.slice(address, capacity * 4).asIntBuffer();
	}

	public static ByteBuffer memSlice(final ByteBuffer buffer, final int offset, final int capacity) {
		ByteBuffer dup = buffer.duplicate();
		dup.position(buffer.position() + offset).limit(buffer.position() + offset + capacity);
		return dup.slice().order(java.nio.ByteOrder.LITTLE_ENDIAN);
	}

	public static synchronized IntBuffer memAllocInt(final int count) {
		ByteBuffer backing = memAlloc(count * 4);
		IntBuffer view = backing.asIntBuffer();
		trackedViewBackings.put(view, backing);
		trackedBackingViews.put(backing, view);
		return view;
	}

	// ==== scalar access ====

	public static int memGetInt(final long address) {
		return EaglerFakeHeap.resolveBase(address).getInt(EaglerFakeHeap.offsetOf(address));
	}

	public static void memPutInt(final long address, final int value) {
		EaglerFakeHeap.resolveBase(address).putInt(EaglerFakeHeap.offsetOf(address), value);
	}

	public static byte memGetByte(final long address) {
		return EaglerFakeHeap.resolveBase(address).get(EaglerFakeHeap.offsetOf(address));
	}

	public static void memPutByte(final long address, final byte value) {
		EaglerFakeHeap.resolveBase(address).put(EaglerFakeHeap.offsetOf(address), value);
	}

	public static short memGetShort(final long address) {
		return EaglerFakeHeap.resolveBase(address).getShort(EaglerFakeHeap.offsetOf(address));
	}

	public static void memPutShort(final long address, final short value) {
		EaglerFakeHeap.resolveBase(address).putShort(EaglerFakeHeap.offsetOf(address), value);
	}

	public static float memGetFloat(final long address) {
		return EaglerFakeHeap.resolveBase(address).getFloat(EaglerFakeHeap.offsetOf(address));
	}

	public static void memPutFloat(final long address, final float value) {
		EaglerFakeHeap.resolveBase(address).putFloat(EaglerFakeHeap.offsetOf(address), value);
	}

	public static long memGetLong(final long address) {
		return EaglerFakeHeap.resolveBase(address).getLong(EaglerFakeHeap.offsetOf(address));
	}

	public static void memPutLong(final long address, final long value) {
		EaglerFakeHeap.resolveBase(address).putLong(EaglerFakeHeap.offsetOf(address), value);
	}

	// ==== bulk ops ====

	public static void memCopy(final long src, final long dst, final long bytes) {
		EaglerFakeHeap.copy(src, dst, bytes);
	}

	public static void memCopy(final ByteBuffer src, final ByteBuffer dst) {
		ByteBuffer s = src.duplicate();
		ByteBuffer d = dst.duplicate();
		d.put(s);
	}

	public static void memSet(final long address, final int value, final long bytes) {
		if (address == 0L) {
			// Blaze3D.youJustLostTheGame() writes to NULL on purpose — crash equivalently
			throw new IllegalStateException("you just lost the game (memSet to NULL)");
		}
		EaglerFakeHeap.set(address, value, bytes);
	}

	// ==== strings ====

	public static String memUTF8(final long address) {
		if (address == 0L) {
			return "";
		}
		ByteBuffer base = EaglerFakeHeap.resolveBase(address);
		int off = EaglerFakeHeap.offsetOf(address);
		StringBuilder sb = new StringBuilder();
		byte b;
		while (off < base.capacity() && (b = base.get(off)) != 0) {
			sb.append((char) (b & 0xFF));
			++off;
		}
		return sb.toString();
	}

	public static String memASCII(final long address, final int length) {
		if (address == 0L) {
			return "";
		}
		ByteBuffer base = EaglerFakeHeap.resolveBase(address);
		int off = EaglerFakeHeap.offsetOf(address);
		StringBuilder sb = new StringBuilder(length);
		for (int i = 0; i < length; ++i) {
			sb.append((char) (base.get(off + i) & 0xFF));
		}
		return sb.toString();
	}

	// ==== allocator (ByteBufferBuilder) ====

	public interface MemoryAllocator {
		long malloc(long size);

		long calloc(long num, long size);

		long realloc(long address, long size);

		void free(long address);
	}

	private static final MemoryAllocator ALLOCATOR_INSTANCE = new MemoryAllocator() {
		@Override
		public long malloc(final long size) {
			return EaglerFakeHeap.alloc(size);
		}

		@Override
		public long calloc(final long num, final long size) {
			return EaglerFakeHeap.alloc(num * size);
		}

		@Override
		public long realloc(final long address, final long size) {
			return EaglerFakeHeap.realloc(address, size);
		}

		@Override
		public void free(final long address) {
			EaglerFakeHeap.free(address);
		}
	};

	public static MemoryAllocator getAllocator(final boolean tracked) {
		return ALLOCATOR_INSTANCE;
	}

	public static MemoryAllocator getAllocator() {
		return ALLOCATOR_INSTANCE;
	}
}

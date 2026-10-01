/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.internal.webgpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Uint8Array;

/**
 * WebGPU backend (increment 1) buffer. Unlike WebGL2 (no mapping at all), WebGPU
 * DOES have real mapping — but only asynchronously (mapAsync). MC's blaze3d
 * {@code map()} is synchronous, so:
 *  - initial data uses mappedAtCreation (the fast path from the workers teardown);
 *  - write-flush uses queue.writeBuffer (also a fast path — no staging round-trip);
 *  - read-back of an arbitrary GPU buffer needs a staging copy + mapAsync await and
 *    is only implemented for buffers created with USAGE_MAP_READ (the copyTexture-
 *    ToBuffer readback path); other reads are increment-2 (webgpu-todo).
 * Buffer sizes are rounded up to 4 bytes (WebGPU requirement for mappable/uniform).
 */
public class WebGPUBuffer extends GpuBuffer {

	private final WebGPUDevice device;
	private final JSObject handle;
	private final int gpuSize;
	private boolean closed;
	int mappingRefCount;

	public WebGPUBuffer(final WebGPUDevice device, final @GpuBuffer.Usage int usage, final long size, final ByteBuffer initialData) {
		super(usage, initialData != null ? initialData.remaining() : size);
		this.device = device;
		final int logical = (int) Math.min(initialData != null ? initialData.remaining() : size, Integer.MAX_VALUE);
		this.gpuSize = (logical + 3) & ~3;
		int wgpuUsage = WebGPUConst.bufferUsage(usage);
		if (initialData != null) {
			this.handle = WebGPU.deviceCreateBuffer(device.deviceHandle(), this.gpuSize, wgpuUsage, true);
			ByteBuffer dup = initialData.duplicate();
			Uint8Array src = uint8(dup);
			WebGPU.bufferWriteMappedAndUnmap(this.handle, src, logical);
		} else {
			this.handle = WebGPU.deviceCreateBuffer(device.deviceHandle(), this.gpuSize, wgpuUsage, false);
		}
	}

	private static Uint8Array uint8(final ByteBuffer data) {
		Int8Array i8 = Int8Array.fromJavaBuffer(data);
		return Uint8Array.create(i8.getBuffer(), i8.getByteOffset(), i8.getLength());
	}

	public JSObject handle() {
		return this.handle;
	}

	public int gpuSize() {
		return this.gpuSize;
	}

	void checkCanBeUsed() {
		if (this.mappingRefCount != 0) {
			throw new IllegalStateException("Attempt to use buffer while mapped");
		}
	}

	/** Direct queue upload of a slice (used by the command encoder writeToBuffer). */
	void queueWrite(final int offset, final ByteBuffer data) {
		this.checkCanBeUsed();
		ByteBuffer dup = data.duplicate();
		int len = dup.remaining();
		WebGPU.queueWriteBuffer(this.device.queueHandle(), this.handle, offset, Int8Array.fromJavaBuffer(dup), 0, len);
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			if (this.mappingRefCount != 0) {
				throw new IllegalStateException("Attempt to close a mapped buffer");
			}
			WebGPU.bufferDestroy(this.handle);
		}
	}

	@Override
	public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
		if (this.closed) {
			throw new IllegalStateException("Buffer already closed");
		}
		if (!read && !write) {
			throw new IllegalArgumentException("At least read or write must be true");
		}
		if (offset + length > this.size()) {
			throw new IllegalArgumentException("Cannot map more data than this buffer can hold");
		}
		if (offset > Integer.MAX_VALUE || length > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Mapping buffers larger than 2GB is not supported");
		}

		this.mappingRefCount++;
		final int off = (int) offset;
		final int len = (int) length;
		final ByteBuffer shadow = ByteBuffer.allocateDirect(len).order(ByteOrder.LITTLE_ENDIAN);

		if (read) {
			if ((this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
				// Reading an arbitrary non-MAP_READ GPU buffer needs a staging copy;
				// that path is increment 2. The readback flow (copyTextureToBuffer ->
				// a USAGE_MAP_READ buffer) is what actually maps for read here.
				throw new UnsupportedOperationException("webgpu-todo: read-map of a non-MAP_READ buffer (increment 2 staging path)");
			}
			// mapAsync READ then copy the mapped range into the shadow (await on the green thread)
			JSObject p = WebGPU.bufferMapAsync(this.handle, WebGPUConst.MAP_READ, off, len);
			WebGPU.await(p);
			ArrayBuffer ab = WebGPU.bufferGetMappedRangeCopy(this.handle, off, len);
			Uint8Array srcU = Uint8Array.create(ab);
			Uint8Array dstU = uint8(shadow);
			for (int i = 0; i < len; i++) {
				dstU.set(i, srcU.get(i));
			}
			WebGPU.bufferUnmap(this.handle);
			shadow.position(0);
			shadow.limit(len);
		}

		final boolean doFlush = write;
		return new GpuBufferSlice.MappedView(this.slice(offset, length), shadow, new Runnable() {
			private boolean done;

			@Override
			public void run() {
				if (!this.done) {
					this.done = true;
					if (doFlush) {
						shadow.position(0);
						shadow.limit(len);
						WebGPU.queueWriteBuffer(WebGPUBuffer.this.device.queueHandle(), WebGPUBuffer.this.handle, off,
								Int8Array.fromJavaBuffer(shadow), 0, len);
					}
					WebGPUBuffer.this.mappingRefCount--;
				}
			}
		});
	}
}

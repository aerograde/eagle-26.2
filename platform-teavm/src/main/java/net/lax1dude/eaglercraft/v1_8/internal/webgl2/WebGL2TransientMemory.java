/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.internal.webgl2;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;

/**
 * Ported from com.mojang.blaze3d.opengl.GlTransientMemory.Fallback (recon §3/§6):
 * the host-buffer + bufferSubData path, which is the one already flagged COPYABLE
 * for platforms without persistent mapping — exactly WebGL2's situation
 * (persistentMapping = false). The upstream Fallback rides a
 * TransientBlockAllocator over native MemoryUtil blocks; that native ring is
 * replaced here by per-allocation transient WebGL2Buffers plus direct
 * ByteBuffer shadows, freed on rotate()/close() (documented simplification —
 * ring-buffer packing is a 3.3b throughput task, not needed for correctness).
 *
 * This class is only reached once the full CommandEncoder/RenderPass machinery is
 * wired (Phase 3.3b); it is present here for a compile-green, spec-faithful
 * backend surface.
 */
public class WebGL2TransientMemory implements TransientMemory, AutoCloseable {

	private final WebGL2RenderingContextExt gl;
	private final List<WebGL2Buffer> liveBuffers = new ArrayList<>();

	public WebGL2TransientMemory(final WebGL2RenderingContextExt gl) {
		this.gl = gl;
	}

	@Override
	public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
		// LITTLE_ENDIAN: GL buffer memory is little-endian platform-wide; MC's writers use
		// the buffer's own order, so a default BIG_ENDIAN buffer byte-swaps every value.
		return ByteBuffer.allocateDirect((int) size).order(java.nio.ByteOrder.LITTLE_ENDIAN);
	}

	private WebGL2Buffer newBuffer(final long size, final @GpuBuffer.Usage int usage, final ByteBuffer data) {
		WebGL2Buffer buffer = new WebGL2Buffer(this.gl, usage, size, data);
		this.liveBuffers.add(buffer);
		return buffer;
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(final long size, final long alignment, final @GpuBuffer.Usage int usage,
			final long minimumAllocation, final long elementSize) {
		final WebGL2Buffer buffer = this.newBuffer(size, usage, null);
		final ByteBuffer shadow = ByteBuffer.allocateDirect((int) size).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		final GpuBufferSlice slice = new GpuBufferSlice(buffer, 0L, size);
		return new GpuBufferSlice.MappedView(slice, shadow, () -> {
			shadow.position(0);
			shadow.limit((int) size);
			this.gl.bindBuffer(buffer.target(), buffer.handle());
			this.gl.bufferSubData(buffer.target(), 0, org.teavm.jso.typedarrays.Int8Array.fromJavaBuffer(shadow));
			this.gl.bindBuffer(buffer.target(), null);
		});
	}

	@Override
	public GpuBufferSlice allocateGpu(final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation,
			final long elementSize) {
		return new GpuBufferSlice(this.newBuffer(size, usage, null), 0L, size);
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(final long size, final long alignment, final @GpuBuffer.Usage int usage,
			final long minimumAllocation, final long elementSize) {
		return this.allocateStaging(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage,
			final long minimumAllocation, final long elementSize) {
		return this.uploadGpu(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage,
			final long minimumAllocation, final long elementSize) {
		long totalSize = 0L;
		for (ByteBuffer buf : data) {
			totalSize += buf.remaining();
			totalSize = roundToward(totalSize, alignment);
		}
		WebGL2Buffer buffer = this.newBuffer(totalSize, usage, null);
		this.gl.bindBuffer(buffer.target(), buffer.handle());
		long offset = 0L;
		for (ByteBuffer buf : data) {
			this.gl.bufferSubData(buffer.target(), (int) offset, org.teavm.jso.typedarrays.Int8Array.fromJavaBuffer(buf));
			offset += buf.remaining();
			offset = roundToward(offset, alignment);
		}
		this.gl.bindBuffer(buffer.target(), null);
		return new GpuBufferSlice(buffer, 0L, totalSize);
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		return this.multiUploadGpu(data, alignment, usage);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		final List<GpuBufferSlice> out = new ArrayList<>(data.size());
		if (data.isEmpty()) {
			return out;
		}

		long totalSize = 0L;
		for (ByteBuffer buf : data) {
			totalSize = roundToward(totalSize, alignment);
			totalSize += buf.remaining();
		}
		totalSize = roundToward(totalSize, alignment);

		final WebGL2Buffer buffer = this.newBuffer(totalSize, usage, null);
		this.gl.bindBuffer(buffer.target(), buffer.handle());
		long offset = 0L;
		for (ByteBuffer buf : data) {
			offset = roundToward(offset, alignment);
			final int length = buf.remaining();
			this.gl.bufferSubData(buffer.target(), (int) offset,
					org.teavm.jso.typedarrays.Int8Array.fromJavaBuffer(buf));
			out.add(new GpuBufferSlice(buffer, offset, length));
			offset += length;
		}
		this.gl.bindBuffer(buffer.target(), null);
		return out;
	}

	private static long roundToward(final long value, final long divisor) {
		return (value + divisor - 1L) / divisor * divisor;
	}

	/** Called by the encoder at frame boundary (submit) to recycle transient buffers. */
	public void rotate() {
		for (WebGL2Buffer buffer : this.liveBuffers) {
			if (!buffer.isClosed()) {
				buffer.close();
			}
		}
		this.liveBuffers.clear();
	}

	@Override
	public void close() {
		this.rotate();
	}
}

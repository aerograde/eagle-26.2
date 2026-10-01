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
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;

/**
 * WebGPU backend (increment 1) transient memory. Mirrors WebGL2TransientMemory:
 * per-allocation buffers freed on rotate()/close() (a proper ring-packing
 * allocator with mappedAtCreation sub-suballocation is an increment-2 throughput
 * task). Uploads use WebGPUBuffer's mappedAtCreation fast path.
 */
public class WebGPUTransientMemory implements TransientMemory, AutoCloseable {

	private final WebGPUDevice device;
	private final List<WebGPUBuffer> liveBuffers = new ArrayList<>();

	public WebGPUTransientMemory(final WebGPUDevice device) {
		this.device = device;
	}

	@Override
	public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
		return ByteBuffer.allocateDirect((int) size).order(ByteOrder.LITTLE_ENDIAN);
	}

	private WebGPUBuffer newBuffer(final long size, final @GpuBuffer.Usage int usage, final ByteBuffer data) {
		WebGPUBuffer buffer = new WebGPUBuffer(this.device, usage, size, data);
		this.liveBuffers.add(buffer);
		return buffer;
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(final long size, final long alignment, final @GpuBuffer.Usage int usage,
			final long minimumAllocation, final long elementSize) {
		final WebGPUBuffer buffer = this.newBuffer(size, usage, null);
		final ByteBuffer shadow = ByteBuffer.allocateDirect((int) size).order(ByteOrder.LITTLE_ENDIAN);
		final GpuBufferSlice slice = new GpuBufferSlice(buffer, 0L, size);
		return new GpuBufferSlice.MappedView(slice, shadow, () -> {
			shadow.position(0);
			shadow.limit((int) size);
			buffer.queueWrite(0, shadow);
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
		int totalSize = 0;
		for (ByteBuffer buf : data) {
			totalSize += buf.remaining();
			totalSize = (int) roundToward(totalSize, alignment);
		}
		ByteBuffer combined = ByteBuffer.allocateDirect(totalSize).order(ByteOrder.LITTLE_ENDIAN);
		int offset = 0;
		for (ByteBuffer buf : data) {
			ByteBuffer dup = buf.duplicate();
			combined.position(offset);
			combined.put(dup);
			offset += buf.remaining();
			offset = (int) roundToward(offset, alignment);
		}
		combined.position(0);
		combined.limit(totalSize);
		WebGPUBuffer buffer = this.newBuffer(totalSize, usage, combined);
		return new GpuBufferSlice(buffer, 0L, totalSize);
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		return this.multiUploadGpu(data, alignment, usage);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		List<GpuBufferSlice> out = new ArrayList<>(data.size());
		for (ByteBuffer buf : data) {
			out.add(this.uploadGpu(List.of(buf), alignment, usage, buf.remaining(), 1L));
		}
		return out;
	}

	private static long roundToward(final long value, final long divisor) {
		return (value + divisor - 1L) / divisor * divisor;
	}

	/** Called by the encoder at frame boundary (submit) to recycle transient buffers. */
	public void rotate() {
		for (WebGPUBuffer buffer : this.liveBuffers) {
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

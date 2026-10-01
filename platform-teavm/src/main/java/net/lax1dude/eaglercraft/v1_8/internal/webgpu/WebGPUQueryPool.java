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

import java.util.OptionalLong;

import com.mojang.blaze3d.systems.GpuQueryPool;

/**
 * WebGPU backend (increment 1) timestamp-query pool STUB. WebGPU timestamp queries
 * require the 'timestamp-query' feature (not requested in increment 1), so every
 * value is empty and writeTimestamp is a no-op — the profiler treats timing as
 * unavailable, exactly like WebGL2QueryPool.
 */
public class WebGPUQueryPool implements GpuQueryPool {

	private final int size;
	private boolean closed;

	public WebGPUQueryPool(final int size) {
		this.size = size;
	}

	@Override
	public int size() {
		return this.size;
	}

	@Override
	public OptionalLong getValue(final int index) {
		return OptionalLong.empty();
	}

	@Override
	public OptionalLong[] getValues(final int index, final int count) {
		if (index + count > this.size) {
			throw new IndexOutOfBoundsException("getValues out of range");
		}
		OptionalLong[] result = new OptionalLong[count];
		for (int i = 0; i < count; i++) {
			result[i] = OptionalLong.empty();
		}
		return result;
	}

	public void writeTimestamp(final int index) {
		// unsupported in increment 1 — no-op
	}

	@Override
	public void close() {
		this.closed = true;
	}
}

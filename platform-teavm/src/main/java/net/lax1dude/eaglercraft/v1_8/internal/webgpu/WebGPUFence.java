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

import org.teavm.jso.JSObject;

/**
 * WebGPU backend (increment 1) fence, backed by queue.onSubmittedWorkDone(). The
 * promise resolves when all work submitted before this fence completes; a .then
 * flips {@code done}. awaitCompletion(0) is a non-blocking poll of that flag (the
 * browser-safe contract, like WebGL2Fence); a positive timeout suspends the green
 * thread on the promise.
 */
public class WebGPUFence implements com.mojang.blaze3d.buffers.GpuFence {

	private final JSObject promise;
	private final boolean[] done = new boolean[] { false };
	private boolean closed;

	public WebGPUFence(final WebGPUDevice device) {
		this.promise = WebGPU.queueOnSubmittedWorkDone(device.queueHandle());
		markDoneWhenResolved(this.promise, (final JSObject value) -> this.done[0] = true);
	}

	@org.teavm.jso.JSBody(params = { "p", "cb" }, script = "try { p.then(function(){ cb(null); }); } catch(e) {}")
	private static native void markDoneWhenResolved(JSObject p, WebGPU.PromiseFn cb);

	@Override
	public void close() {
		this.closed = true;
	}

	@Override
	public boolean awaitCompletion(final long timeoutNS) {
		if (this.closed || this.done[0]) {
			return true;
		}
		if (timeoutNS <= 0L) {
			return this.done[0];
		}
		// suspend the green thread until the GPU work-done promise settles
		WebGPU.await(this.promise);
		return this.done[0];
	}
}

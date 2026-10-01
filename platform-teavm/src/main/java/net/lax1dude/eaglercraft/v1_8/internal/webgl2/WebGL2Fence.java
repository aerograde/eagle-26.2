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

import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;

import org.teavm.jso.webgl.WebGLSync;

/**
 * Ported concept from com.mojang.blaze3d.opengl.GlFence, but WebGL2's
 * clientWaitSync is POLL-ONLY (recon §3 "TRICKIEST AREA"): a blocking wait would
 * freeze the browser main thread. So awaitCompletion polls clientWaitSync with
 * timeout 0 and, when a positive timeout is requested, yields to the event loop
 * (EagRuntime-style, via PlatformRuntime.sleep(0) which suspends the green thread
 * and reschedules) between polls — it NEVER busy-blocks the GPU.
 *
 * NOTE (TODO 3.3b): true frame pacing wants this poll deferred across
 * requestAnimationFrame ticks rather than a green-thread yield loop; the RAF
 * present hook + RenderSystem.queueFencedTask drain integrate here.
 */
public class WebGL2Fence implements com.mojang.blaze3d.buffers.GpuFence {

	private final WebGL2RenderingContextExt gl;
	private WebGLSync sync;
	private boolean closedOrCompleted;

	public WebGL2Fence(final WebGL2RenderingContextExt gl) {
		this.gl = gl;
		this.sync = gl.fenceSync(WebGL2Const.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
	}

	@Override
	public void close() {
		if (!this.closedOrCompleted) {
			this.closedOrCompleted = true;
			if (this.sync != null) {
				this.gl.deleteSync(this.sync);
				this.sync = null;
			}
		}
	}

	private boolean poll() {
		// poll-only: clientWaitSync with a zero timeout never blocks the main thread
		int result = this.gl.clientWaitSync(this.sync, 0, 0);
		// SIGNALED is not a clientWaitSync return, but harmless to accept; ALREADY_
		// SIGNALED / CONDITION_SATISFIED mean the fence completed; WAIT_FAILED is
		// treated as done so callers never spin forever. TIMEOUT_EXPIRED = not done.
		return result == WebGL2Const.GL_ALREADY_SIGNALED || result == WebGL2Const.GL_CONDITION_SATISFIED
				|| result == WebGL2Const.GL_SIGNALED || result == WebGL2Const.GL_WAIT_FAILED;
	}

	@Override
	public boolean awaitCompletion(final long timeoutNS) {
		if (this.closedOrCompleted) {
			return true;
		}
		if (this.poll()) {
			this.close();
			this.closedOrCompleted = true;
			return true;
		}
		if (timeoutNS <= 0L) {
			return false;
		}
		// bounded yield-poll loop; never a blocking clientWaitSync
		long deadline = PlatformRuntime.nanoTime() + timeoutNS;
		while (PlatformRuntime.nanoTime() < deadline) {
			PlatformRuntime.sleep(0); // suspend green thread, let GPU/event loop run
			if (this.poll()) {
				this.close();
				this.closedOrCompleted = true;
				return true;
			}
		}
		return false;
	}
}

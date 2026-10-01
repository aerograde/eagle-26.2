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

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;

import org.teavm.jso.JSObject;

/**
 * WebGPU backend (increment 1) GpuBackend factory. Selected by ClientMain in place
 * of the WebGL2 backend when navigator.gpu exists AND the webgpu flag is set
 * (default OFF). createDevice performs the async adapter/device acquisition by
 * suspending the TeaVM green thread on the requestAdapter/requestDevice promises
 * ({@link WebGPU#await}), then wraps a {@link WebGPUDevice}.
 */
public class WebGPUBackend implements GpuBackend {

	@Override
	public String getName() {
		return "WebGPU";
	}

	@Override
	public void setWindowHints() {
		// No GLFW window hints in the browser.
	}

	@Override
	public void handleWindowCreationErrors(final GLFWErrorCapture.Error error) throws BackendCreationException {
		throw new BackendCreationException("Failed to create WebGPU context", BackendCreationException.Reason.OTHER);
	}

	@Override
	public GpuDevice createDevice(final long window, final ShaderSource defaultShaderSource, final GpuDebugOptions debugOptions,
			final Runnable criticalShaderLoader) throws BackendCreationException {
		if (!WebGPU.isSupported()) {
			throw new BackendCreationException("WebGPU is not supported by this browser", BackendCreationException.Reason.OPENGL_MISSING);
		}
		JSObject adapter = WebGPU.await(WebGPU.requestAdapterPromise());
		if (adapter == null) {
			throw new BackendCreationException("No WebGPU adapter available" + (WebGPU.lastError != null ? " (" + WebGPU.lastError + ")" : ""),
					BackendCreationException.Reason.OTHER);
		}
		JSObject device = WebGPU.await(WebGPU.requestDevicePromise(adapter));
		if (device == null) {
			throw new BackendCreationException("Could not acquire a WebGPU device" + (WebGPU.lastError != null ? " (" + WebGPU.lastError + ")" : ""),
					BackendCreationException.Reason.OTHER);
		}
		JSObject queue = WebGPU.deviceQueue(device);
		return new GpuDevice(new WebGPUDevice(device, queue, adapter, defaultShaderSource), criticalShaderLoader);
	}
}

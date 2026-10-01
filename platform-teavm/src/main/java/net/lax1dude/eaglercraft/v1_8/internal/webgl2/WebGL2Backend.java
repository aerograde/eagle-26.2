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

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;

import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;

/**
 * Phase 3.3a WebGL2 GpuBackend factory, ported from
 * com.mojang.blaze3d.opengl.GlBackend. This is the entry point the game-side
 * selection seam (PreferredGraphicsApi.getBackendsToTry, under the web flag) will
 * choose in Phase 3.3b. setWindowHints / handleWindowCreationErrors are the GLFW
 * coupling points that the browser build stubs (TODO 3.3b). createDevice acquires
 * the WebGL2 context from PlatformRuntime and wraps a WebGL2Device.
 */
public class WebGL2Backend implements GpuBackend {

	@Override
	public String getName() {
		return "WebGL2";
	}

	@Override
	public void setWindowHints() {
		// No GLFW window hints in the browser. TODO(3.3b): GLFW window/hint stubs.
	}

	@Override
	public void handleWindowCreationErrors(final GLFWErrorCapture.Error error) throws BackendCreationException {
		throw new BackendCreationException("Failed to create WebGL2 context", BackendCreationException.Reason.OTHER);
	}

	@Override
	public GpuDevice createDevice(final long window, final ShaderSource defaultShaderSource, final GpuDebugOptions debugOptions,
			final Runnable criticalShaderLoader) throws BackendCreationException {
		WebGL2RenderingContextExt gl = PlatformRuntime.getWebGL2Context();
		if (gl == null) {
			throw new BackendCreationException("WebGL 2.0 is not supported by this browser", BackendCreationException.Reason.OPENGL_MISSING);
		}
		return new GpuDevice(new WebGL2Device(gl, defaultShaderSource, debugOptions), criticalShaderLoader);
	}
}

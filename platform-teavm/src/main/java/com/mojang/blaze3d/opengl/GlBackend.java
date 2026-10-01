package com.mojang.blaze3d.opengl;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;

/**
 * Eagler 26.2 Phase 3.3b same-FQN SHADOW of the desktop GL backend factory.
 *
 * platform-teavm precedes :game on the TeaVM classpath, so this class replaces
 * com.mojang.blaze3d.opengl.GlBackend in the web build and severs the ENTIRE
 * com.mojang.blaze3d.opengl.* tree (GlDevice/GlCommandEncoder/GlStateManager/
 * org.lwjgl.opengl.*) from TeaVM's reachability graph — reach analysis does not
 * prune dead branches, so PreferredGraphicsApi's desktop fallback path would
 * otherwise drag all of LWJGL-GL in. At runtime the web boot never tries this
 * backend (EaglerHosted.webGpuBackendFactory short-circuits selection); if it is
 * ever constructed anyway, createDevice fails into Minecraft's normal
 * BackendCreationException handling. Desktop never sees this module.
 */
public class GlBackend implements GpuBackend {

	@Override
	public String getName() {
		return "OpenGL";
	}

	@Override
	public void setWindowHints() {
	}

	@Override
	public void handleWindowCreationErrors(final GLFWErrorCapture.Error error) throws BackendCreationException {
		throw new BackendCreationException("OpenGL backend is not supported in the browser", BackendCreationException.Reason.OPENGL_MISSING);
	}

	@Override
	public GpuDevice createDevice(final long window, final ShaderSource defaultShaderSource, final GpuDebugOptions debugOptions,
			final Runnable criticalShaderLoader) throws BackendCreationException {
		throw new BackendCreationException("OpenGL backend is not supported in the browser", BackendCreationException.Reason.OPENGL_MISSING);
	}
}

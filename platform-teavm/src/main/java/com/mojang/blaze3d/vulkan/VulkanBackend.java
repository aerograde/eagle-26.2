package com.mojang.blaze3d.vulkan;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;

/**
 * Eagler 26.2 Phase 3.3b same-FQN SHADOW of the desktop Vulkan backend factory
 * (see the GlBackend shadow for the mechanism). Severs com.mojang.blaze3d.vulkan.*
 * and org.lwjgl.vulkan.* from the web reachability graph. Outside referencers are
 * only PreferredGraphicsApi (ctor) and Minecraft.checkBackendAvailable().
 */
public class VulkanBackend implements GpuBackend {

	/** Mirrors the desktop signature; the web answer is always "unavailable". */
	public static BackendCreationException checkBackendAvailable() {
		return new BackendCreationException("Vulkan is not supported in the browser", BackendCreationException.Reason.OTHER);
	}

	@Override
	public String getName() {
		return "Vulkan";
	}

	@Override
	public void setWindowHints() {
	}

	@Override
	public void handleWindowCreationErrors(final GLFWErrorCapture.Error error) throws BackendCreationException {
		throw new BackendCreationException("Vulkan backend is not supported in the browser", BackendCreationException.Reason.OTHER);
	}

	@Override
	public GpuDevice createDevice(final long window, final ShaderSource defaultShaderSource, final GpuDebugOptions debugOptions,
			final Runnable criticalShaderLoader) throws BackendCreationException {
		throw new BackendCreationException("Vulkan backend is not supported in the browser", BackendCreationException.Reason.OTHER);
	}
}

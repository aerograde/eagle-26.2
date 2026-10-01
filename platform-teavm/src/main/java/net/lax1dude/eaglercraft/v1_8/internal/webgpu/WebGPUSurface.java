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

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

import net.lax1dude.eaglercraft.v1_8.internal.PlatformInput;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;

/**
 * WebGPU backend (increment 1) surface. Configures the canvas WebGPU context with
 * alphaMode:'opaque' (the workers-teardown fast path) and presents by blitting the
 * game's offscreen color texture onto the swapchain's current texture with a
 * fullscreen-triangle pass (WebGPUCommandEncoder.presentTexture).
 *
 * present() replicates the WebGL2Surface contract exactly: it flushes the recorded
 * blit, fires the one-shot __eaglerGameReady boot-splash signal, then yields to
 * requestAnimationFrame via PlatformInput.update() (canvas DPI resize + vsync
 * pacing), plus a bounded GPU-backlog throttle.
 */
public class WebGPUSurface implements GpuSurfaceBackend {

	private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES =
			EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);

	private static final int MAX_BACKLOG_YIELDS = 8;

	private final WebGPUDevice device;
	private final JSObject context;
	private final String canvasFormat;

	private int swapchainWidth;
	private int swapchainHeight;
	private boolean vsync = true;
	private JSObject currentSwapchainView;
	private WebGPUCommandEncoder lastEncoder;
	private boolean gameReadySignaled;

	public WebGPUSurface(final WebGPUDevice device) {
		this.device = device;
		this.context = WebGPU.canvasGetWebGpuContext(PlatformRuntime.canvas);
		this.canvasFormat = device.preferredCanvasFormat();
	}

	@Override
	public void configure(final GpuSurface.Configuration config) throws SurfaceException {
		this.swapchainWidth = config.width();
		this.swapchainHeight = config.height();
		this.vsync = config.presentMode() != GpuSurface.PresentMode.IMMEDIATE;
		PlatformInput.setVSync(this.vsync);
		if (this.context == null) {
			throw new SurfaceException("Could not acquire a WebGPU canvas context");
		}
		// alphaMode:'opaque' — no compositor blending, straight-through canvas.
		WebGPU.contextConfigure(this.context, this.device.deviceHandle(), this.canvasFormat, "opaque");
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
		if (this.context != null) {
			JSObject tex = WebGPU.contextGetCurrentTexture(this.context);
			this.currentSwapchainView = WebGPU.textureCreateView(tex, null);
		}
	}

	@Override
	public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
		this.lastEncoder = (WebGPUCommandEncoder) commandEncoder;
		if (this.currentSwapchainView != null) {
			this.lastEncoder.presentTexture(textureView, this.currentSwapchainView, this.canvasFormat);
		}
	}

	@Override
	public void present() {
		// submit the recorded present blit (idempotent with the frame's own submit()).
		if (this.lastEncoder != null) {
			this.lastEncoder.flush();
		}
		if (!this.gameReadySignaled) {
			this.gameReadySignaled = true;
			signalGameReadyJS();
		}
		this.currentSwapchainView = null;
		// rAF yield: suspends the render green thread; the compositor presents the
		// canvas in between (also resizes canvas to client size * dpr when changed).
		PlatformInput.update(net.minecraft.client.FramerateLimiter.getFramerateLimit());
		org.lwjgl.glfw.GLFW.eaglerPumpSizeCallbacks();
		if (this.lastEncoder != null) {
			int yields = 0;
			while (this.lastEncoder.isGpuBacklogged() && ++yields <= MAX_BACKLOG_YIELDS) {
				PlatformInput.update();
			}
		}
	}

	@JSBody(params = {}, script = "globalThis.__eaglerGameReady = true;")
	private static native void signalGameReadyJS();

	@Override
	public void close() {
		if (this.context != null) {
			WebGPU.contextUnconfigure(this.context);
		}
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return SUPPORTED_PRESENT_MODES;
	}
}

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

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Uint8Array;

import net.lax1dude.eaglercraft.v1_8.internal.PlatformInput;

/**
 * Ported from com.mojang.blaze3d.opengl.GlSurface. blitFromTexture blits the
 * offscreen color texture to the default framebuffer (the canvas backbuffer) via
 * blitFramebuffer, exactly like GlCommandEncoder.presentTexture.
 *
 * Phase 3.3b present(): the browser frame boundary. Upstream calls
 * glfwSwapBuffers; here the canvas is composited when the render green thread
 * yields to requestAnimationFrame — PlatformInput.update() carries the rAF await
 * (plus canvas DPI resize + the vsync-off swapDelay path).
 * GLFW.eaglerPumpSizeCallbacks() re-fires Window's framebuffer-size callbacks
 * after the canvas resize so Minecraft reconfigures the surface exactly like a
 * desktop resize. The browser/compositor owns GPU queue pacing; waiting through
 * additional rAF callbacks here would turn a temporary GPU backlog into
 * 30/20/15 FPS frame quantization.
 */
public class WebGL2Surface implements GpuSurfaceBackend {

	private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES =
			EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);

	/** Phase 3.3b title probe (eaglercraftXOpts.titleProbe): set by ClientMain. */
	public static boolean titleProbeEnabled = false;
	public static int titleProbeFrame = 120;

	private final WebGL2RenderingContextExt gl;
	private int swapchainWidth;
	private int swapchainHeight;
	private boolean vsync = true;
	private WebGL2CommandEncoder lastEncoder;
	private long frameCounter;
	private boolean titleProbeDone;
	/** Phase 3.3d: one-shot "first frame presented" flag for the boot-splash handoff. */
	private boolean gameReadySignaled;

	public WebGL2Surface(final WebGL2RenderingContextExt gl) {
		this.gl = gl;
	}

	@Override
	public void configure(final GpuSurface.Configuration config) throws SurfaceException {
		this.swapchainWidth = config.width();
		this.swapchainHeight = config.height();
		// FIFO(=vsync) -> rAF pacing; IMMEDIATE -> swapDelay/immediate continue
		this.vsync = config.presentMode() != GpuSurface.PresentMode.IMMEDIATE;
		PlatformInput.setVSync(this.vsync);
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
		this.lastEncoder = (WebGL2CommandEncoder) commandEncoder;
		this.lastEncoder.presentTexture(textureView, this.swapchainWidth, this.swapchainHeight);
	}

	@Override
	public void present() {
		++this.frameCounter;
		// Eagler boot splash handoff (Phase 3.3d): the index.html "Click to Play"
		// overlay stays up through the black parse/EPK-load/model-bake window and
		// removes itself only when the game presents its first real frame (the
		// Mojang loading screen, then the menu). Signal that here, once.
		if (!this.gameReadySignaled) {
			this.gameReadySignaled = true;
			signalGameReadyJS();
		}
		if (titleProbeEnabled && !this.titleProbeDone && this.frameCounter >= titleProbeFrame) {
			this.titleProbeDone = true;
			this.runTitleProbe();
		}
		// rAF yield: suspends the render green thread until the browser's next
		// animation frame; the compositor presents the canvas in between. Also
		// resizes the canvas to client size * dpr when it changed.
		PlatformInput.update(net.minecraft.client.FramerateLimiter.getFramerateLimit());
		// resize propagation: fire Window's GLFW size callbacks if the canvas
		// dimensions changed (arms windowSurfaceNeedsReconfiguring upstream)
		org.lwjgl.glfw.GLFW.eaglerPumpSizeCallbacks();
	}

	// Phase 3.3d: tell the index.html boot splash the first frame is on screen, so
	// it can fade out (see removeSplashWhenRendering). Guarded globalThis write —
	// TeaVM 0.13 emits strict-mode JS where a bare global assignment would throw.
	@JSBody(params = {}, script = "globalThis.__eaglerGameReady = true;")
	private static native void signalGameReadyJS();

	private void runTitleProbe() {
		try {
			int cx = Math.max(0, this.swapchainWidth / 2);
			int cy = Math.max(0, this.swapchainHeight / 2);
			Uint8Array center = Uint8Array.create(4);
			Uint8Array corner = Uint8Array.create(4);
			this.gl.bindFramebuffer(WebGL2Const.GL_READ_FRAMEBUFFER, null);
			this.gl.readPixels(cx, cy, 1, 1, 6408 /* RGBA */, 5121 /* UNSIGNED_BYTE */, center);
			this.gl.readPixels(2, 2, 1, 1, 6408, 5121, corner);
			long draws = this.lastEncoder != null ? this.lastEncoder.getDrawCallsTotal() : -1L;
			String line = "eagler-titleprobe: frames=" + this.frameCounter
					+ " px=" + center.get(0) + "," + center.get(1) + "," + center.get(2)
					+ " corner=" + corner.get(0) + "," + corner.get(1) + "," + corner.get(2)
					+ " drawcalls=" + draws;
			System.out.println(line);
			System.err.println(line);
		} catch (Throwable t) {
			System.err.println("eagler-titleprobe: EXCEPTION " + t);
		}
	}

	@Override
	public void close() {
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return SUPPORTED_PRESENT_MODES;
	}
}

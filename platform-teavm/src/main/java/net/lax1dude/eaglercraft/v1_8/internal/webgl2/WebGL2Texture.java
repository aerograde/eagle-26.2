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

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

import org.teavm.jso.webgl.WebGLTexture;

/**
 * Ported from com.mojang.blaze3d.opengl.GlTexture, simplified: WebGL2 uses only
 * TEXTURE_2D + TEXTURE_CUBE_MAP (recon §3; array/3D textures are rejected
 * upstream). The elaborate FrameBufferCache back-reference bookkeeping is dropped
 * — WebGL2CommandEncoder manages a small transient FBO set directly rather than
 * caching per-attachment FBOs (documented deviation). A view refcount keeps the
 * GL object alive while any GpuTextureView references it.
 */
public class WebGL2Texture extends GpuTexture {

	private final WebGL2RenderingContextExt gl;
	final WebGLTexture id;
	final boolean cubemap;
	private boolean closed;
	private int views;

	public WebGL2Texture(final WebGL2RenderingContextExt gl, final @GpuTexture.Usage int usage, final String label, final GpuFormat format,
			final int width, final int height, final int depthOrLayers, final int mipLevels, final WebGLTexture id) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.gl = gl;
		this.id = id;
		this.cubemap = (usage & 16) != 0;
	}

	public WebGLTexture glId() {
		return this.id;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			if (this.views == 0) {
				this.gl.deleteTexture(this.id);
			}
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	void addViews() {
		this.views++;
	}

	void removeViews() {
		this.views--;
		if (this.closed && this.views == 0) {
			this.gl.deleteTexture(this.id);
		}
	}
}

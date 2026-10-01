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

import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * Ported from com.mojang.blaze3d.opengl.GlTextureView. WebGL2 has no separate
 * texture-view object (no glTextureView); a view is a base-mip / mip-count
 * window over its texture, applied via TEXTURE_BASE_LEVEL/MAX_LEVEL at bind time
 * in the render pass. It just holds a refcount on the underlying texture.
 */
public class WebGL2TextureView extends GpuTextureView {

	private boolean closed;

	public WebGL2TextureView(final WebGL2Texture texture, final int baseMipLevel, final int mipLevels) {
		super(texture, baseMipLevel, mipLevels);
		texture.addViews();
	}

	@Override
	public WebGL2Texture texture() {
		return (WebGL2Texture) super.texture();
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.texture().removeViews();
		}
	}
}

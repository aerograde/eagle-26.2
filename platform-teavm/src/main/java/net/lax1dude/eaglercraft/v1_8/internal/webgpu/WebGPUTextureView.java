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

import com.mojang.blaze3d.textures.GpuTextureView;

import org.teavm.jso.JSObject;

/**
 * WebGPU backend (increment 1) texture view. Unlike WebGL2 (which has no real
 * view object and fakes it with TEXTURE_BASE/MAX_LEVEL at bind time), WebGPU has
 * a genuine GPUTextureView created here over the base-mip / mip-count window.
 */
public class WebGPUTextureView extends GpuTextureView {

	private final JSObject viewHandle;
	private boolean closed;

	public WebGPUTextureView(final WebGPUTexture texture, final int baseMipLevel, final int mipLevels) {
		super(texture, baseMipLevel, mipLevels);
		texture.addViews();
		JSObject desc = WebGPU.newObj();
		WebGPU.putInt(desc, "baseMipLevel", baseMipLevel);
		WebGPU.putInt(desc, "mipLevelCount", mipLevels);
		WebGPU.putStr(desc, "dimension", texture.cubemap ? "cube" : "2d");
		this.viewHandle = WebGPU.textureCreateView(texture.handle(), desc);
	}

	public JSObject viewHandle() {
		return this.viewHandle;
	}

	@Override
	public WebGPUTexture texture() {
		return (WebGPUTexture) super.texture();
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

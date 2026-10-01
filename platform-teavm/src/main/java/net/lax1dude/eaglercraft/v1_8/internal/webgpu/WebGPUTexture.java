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

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

import org.teavm.jso.JSObject;

/**
 * WebGPU backend (increment 1) texture. Holds the opaque GPUTexture handle. A
 * surface-owned texture (from context.getCurrentTexture()) sets {@code external}
 * so close() never destroys the swapchain image. A view refcount keeps the handle
 * alive while any GpuTextureView references it (mirrors WebGL2Texture).
 */
public class WebGPUTexture extends GpuTexture {

	final JSObject handle;
	final boolean cubemap;
	final boolean external;
	private boolean closed;
	private int views;

	public WebGPUTexture(final @GpuTexture.Usage int usage, final String label, final GpuFormat format, final int width,
			final int height, final int depthOrLayers, final int mipLevels, final JSObject handle, final boolean external) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.handle = handle;
		this.cubemap = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		this.external = external;
	}

	public JSObject handle() {
		return this.handle;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			if (this.views == 0 && !this.external) {
				WebGPU.textureDestroy(this.handle);
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
		if (this.closed && this.views == 0 && !this.external) {
			WebGPU.textureDestroy(this.handle);
		}
	}
}

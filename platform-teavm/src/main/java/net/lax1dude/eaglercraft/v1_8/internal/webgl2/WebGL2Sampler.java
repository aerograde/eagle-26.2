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

import java.util.OptionalDouble;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

import org.teavm.jso.webgl.WebGLSampler;

/**
 * Ported 1:1 from com.mojang.blaze3d.opengl.GlSampler — WebGL2 HAS sampler
 * objects (createSampler/samplerParameteri/f/bindSampler), so this is a direct
 * port. Anisotropy is applied via EXT_texture_filter_anisotropic when the
 * extension is present (device enables it and clamps maxAnisotropy).
 */
public class WebGL2Sampler extends GpuSampler {

	private final WebGL2RenderingContextExt gl;
	private final WebGLSampler id;
	private final AddressMode addressModeU;
	private final AddressMode addressModeV;
	private final FilterMode minFilter;
	private final FilterMode magFilter;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	private boolean closed;

	public WebGL2Sampler(final WebGL2RenderingContextExt gl, final AddressMode addressModeU, final AddressMode addressModeV,
			final FilterMode minFilter, final FilterMode magFilter, final int maxAnisotropy, final OptionalDouble maxLod, final boolean anisotropySupported) {
		this.gl = gl;
		this.addressModeU = addressModeU;
		this.addressModeV = addressModeV;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod;
		this.id = gl.createSampler();
		gl.samplerParameteri(this.id, WebGL2Const.GL_TEXTURE_WRAP_S, WebGL2Const.toGl(addressModeU));
		gl.samplerParameteri(this.id, WebGL2Const.GL_TEXTURE_WRAP_T, WebGL2Const.toGl(addressModeV));
		if (maxAnisotropy > 1 && anisotropySupported) {
			gl.samplerParameterf(this.id, WebGL2Const.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAnisotropy);
		}
		// Eagler 26.2 web fix (mobs "low-res / muddy skin + black square patch"): a MIPMAP min filter
		// on a 1-mip texture samples BLACK at minification on ANGLE/D3D11 (it fetches the non-existent
		// mip 1 as (0,0,0,0), and *_MIPMAP_LINEAR blends toward black), even with TEXTURE_MAX_LEVEL=0.
		// Native desktop GL clamps the sampled level to 0 so GlSampler always uses the mipmap filter;
		// WebGL2 must NOT when mipmaps are disabled. MC signals "no mipmaps" via maxLod=0.0 (SamplerCache),
		// which also literally means "never sample past mip 0", so a non-mipmap min filter is exact.
		// Entity textures (mipLevels=1, useMipmaps=false) are heavily minified on small on-screen mobs ->
		// LOD>0 -> the black-mip blend; the block atlas (useMipmaps=true, maxLod empty) keeps its real
		// mip chain + mipmap filter, unchanged. Web-only backend; desktop untouched.
		final boolean noMipmaps = maxLod.isPresent() && maxLod.getAsDouble() <= 0.0;
		switch (minFilter) {
			case NEAREST -> gl.samplerParameteri(this.id, WebGL2Const.GL_TEXTURE_MIN_FILTER,
					noMipmaps ? WebGL2Const.GL_NEAREST : WebGL2Const.GL_NEAREST_MIPMAP_LINEAR);
			case LINEAR -> gl.samplerParameteri(this.id, WebGL2Const.GL_TEXTURE_MIN_FILTER,
					noMipmaps ? WebGL2Const.GL_LINEAR : WebGL2Const.GL_LINEAR_MIPMAP_LINEAR);
		}
			switch (magFilter) {
				case NEAREST -> gl.samplerParameteri(this.id, WebGL2Const.GL_TEXTURE_MAG_FILTER, WebGL2Const.GL_NEAREST);
				// Minecraft's non-atlas entity/skin textures have no mip chain and contain
				// one-pixel facial details. Linear magnification blends those pixels away in
				// WebGL; use the native pixel-art sampling expected for mip-0-only textures.
				case LINEAR -> gl.samplerParameteri(this.id, WebGL2Const.GL_TEXTURE_MAG_FILTER,
						noMipmaps ? WebGL2Const.GL_NEAREST : WebGL2Const.GL_LINEAR);
			}
		if (maxLod.isPresent()) {
			gl.samplerParameterf(this.id, WebGL2Const.GL_TEXTURE_MAX_LOD, (float) maxLod.getAsDouble());
		}
	}

	public WebGLSampler getId() {
		return this.id;
	}

	@Override
	public AddressMode getAddressModeU() {
		return this.addressModeU;
	}

	@Override
	public AddressMode getAddressModeV() {
		return this.addressModeV;
	}

	@Override
	public FilterMode getMinFilter() {
		return this.minFilter;
	}

	@Override
	public FilterMode getMagFilter() {
		return this.magFilter;
	}

	@Override
	public int getMaxAnisotropy() {
		return this.maxAnisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return this.maxLod;
	}

	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.gl.deleteSampler(this.id);
		}
	}
}

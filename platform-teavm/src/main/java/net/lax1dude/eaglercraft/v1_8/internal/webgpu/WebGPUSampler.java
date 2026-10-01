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

import java.util.OptionalDouble;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

import org.teavm.jso.JSObject;

/**
 * WebGPU backend (increment 1) sampler. WebGPU samplers carry the filter/address
 * modes AND the mip filter (there is no separate mipmap-filter enum in blaze3d, so
 * we mirror the minFilter into mipmapFilter). maxAnisotropy is only accepted by
 * WebGPU when min/mag/mipmap are all "linear", so it is clamped accordingly.
 */
public class WebGPUSampler extends GpuSampler {

	private final JSObject handle;
	private final AddressMode addressModeU;
	private final AddressMode addressModeV;
	private final FilterMode minFilter;
	private final FilterMode magFilter;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	private boolean closed;

	public WebGPUSampler(final WebGPUDevice device, final AddressMode addressModeU, final AddressMode addressModeV,
			final FilterMode minFilter, final FilterMode magFilter, final int maxAnisotropy, final OptionalDouble maxLod) {
		this.addressModeU = addressModeU;
		this.addressModeV = addressModeV;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod;

		JSObject desc = WebGPU.newObj();
		WebGPU.putStr(desc, "addressModeU", WebGPUConst.addressMode(addressModeU));
		WebGPU.putStr(desc, "addressModeV", WebGPUConst.addressMode(addressModeV));
		WebGPU.putStr(desc, "addressModeW", "clamp-to-edge");
		WebGPU.putStr(desc, "minFilter", WebGPUConst.filterMode(minFilter));
		WebGPU.putStr(desc, "magFilter", WebGPUConst.filterMode(magFilter));
		// WebGPU requires an explicit mipmap filter; mirror the minification filter.
		boolean allLinear = minFilter == FilterMode.LINEAR && magFilter == FilterMode.LINEAR;
		WebGPU.putStr(desc, "mipmapFilter", WebGPUConst.filterMode(minFilter));
		WebGPU.putNum(desc, "lodMinClamp", 0.0);
		WebGPU.putNum(desc, "lodMaxClamp", maxLod.isPresent() ? maxLod.getAsDouble() : 32.0);
		if (maxAnisotropy > 1 && allLinear) {
			WebGPU.putInt(desc, "maxAnisotropy", maxAnisotropy);
		}
		this.handle = WebGPU.deviceCreateSampler(device.deviceHandle(), desc);
	}

	public JSObject handle() {
		return this.handle;
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
		this.closed = true; // GPUSampler has no destroy(); GC-collected
	}
}

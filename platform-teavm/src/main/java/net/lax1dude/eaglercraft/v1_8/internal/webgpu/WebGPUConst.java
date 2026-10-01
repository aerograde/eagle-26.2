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
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * WebGPU backend (increment 1): blaze3d enum -> WebGPU JS string / bitmask maps.
 * WebGPU's descriptor enums are strings ("triangle-list", "back", "less-equal")
 * and its usage flags are distinct bitmasks from blaze3d's GpuBuffer/GpuTexture
 * USAGE_* constants — both are translated here.
 */
public final class WebGPUConst {

	private WebGPUConst() {
	}

	// ==== GPUBufferUsage bitmask ====
	public static final int BUF_MAP_READ = 0x0001;
	public static final int BUF_MAP_WRITE = 0x0002;
	public static final int BUF_COPY_SRC = 0x0004;
	public static final int BUF_COPY_DST = 0x0008;
	public static final int BUF_INDEX = 0x0010;
	public static final int BUF_VERTEX = 0x0020;
	public static final int BUF_UNIFORM = 0x0040;
	public static final int BUF_STORAGE = 0x0080;
	public static final int BUF_INDIRECT = 0x0100;
	public static final int BUF_QUERY_RESOLVE = 0x0200;

	// ==== GPUTextureUsage bitmask ====
	public static final int TEX_COPY_SRC = 0x01;
	public static final int TEX_COPY_DST = 0x02;
	public static final int TEX_TEXTURE_BINDING = 0x04;
	public static final int TEX_STORAGE_BINDING = 0x08;
	public static final int TEX_RENDER_ATTACHMENT = 0x10;

	// ==== GPUMapMode ====
	public static final int MAP_READ = 0x01;
	public static final int MAP_WRITE = 0x02;

	/**
	 * blaze3d {@link GpuBuffer} usage -> GPUBufferUsage. blaze3d's map bits mean
	 * host visibility (READ/WRITE); WebGPU only maps for readback of COPY_DST-able
	 * buffers, so USAGE_MAP_READ implies COPY_DST + MAP_READ. All buffers get
	 * COPY_DST (queue.writeBuffer) and COPY_SRC so copies always work.
	 */
	public static int bufferUsage(final @GpuBuffer.Usage int usage) {
		int out = BUF_COPY_DST | BUF_COPY_SRC;
		if ((usage & GpuBuffer.USAGE_MAP_READ) != 0) {
			out |= BUF_MAP_READ;
		}
		if ((usage & GpuBuffer.USAGE_VERTEX) != 0) {
			out |= BUF_VERTEX;
		}
		if ((usage & GpuBuffer.USAGE_INDEX) != 0) {
			out |= BUF_INDEX;
		}
		if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) {
			out |= BUF_UNIFORM;
		}
		if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
			out |= BUF_STORAGE; // texel buffers become storage buffers (clouds SSBO path)
		}
		if ((usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0) {
			out |= BUF_INDIRECT;
		}
		return out;
	}

	public static int textureUsage(final @GpuTexture.Usage int usage) {
		int out = 0;
		if ((usage & GpuTexture.USAGE_COPY_DST) != 0) {
			out |= TEX_COPY_DST;
		}
		if ((usage & GpuTexture.USAGE_COPY_SRC) != 0) {
			out |= TEX_COPY_SRC;
		}
		if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
			out |= TEX_TEXTURE_BINDING;
		}
		if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
			out |= TEX_RENDER_ATTACHMENT;
		}
		return out;
	}

	/** GpuFormat -> WebGPU texture format string. Returns null if unmapped. */
	public static String textureFormat(final GpuFormat format) {
		switch (format) {
			case R8_UNORM:
				return "r8unorm";
			case R8_SNORM:
				return "r8snorm";
			case RG8_UNORM:
				return "rg8unorm";
			case RG8_SNORM:
				return "rg8snorm";
			case RGBA8_UNORM:
				return "rgba8unorm";
			case RGBA8_SNORM:
				return "rgba8snorm";
			case R8_UINT:
				return "r8uint";
			case R8_SINT:
				return "r8sint";
			case RG8_UINT:
				return "rg8uint";
			case RG8_SINT:
				return "rg8sint";
			case RGBA8_UINT:
				return "rgba8uint";
			case RGBA8_SINT:
				return "rgba8sint";
			case R16_UINT:
				return "r16uint";
			case R16_SINT:
				return "r16sint";
			case RG16_UINT:
				return "rg16uint";
			case RG16_SINT:
				return "rg16sint";
			case RGBA16_UINT:
				return "rgba16uint";
			case RGBA16_SINT:
				return "rgba16sint";
			case R16_FLOAT:
				return "r16float";
			case RG16_FLOAT:
				return "rg16float";
			case RGBA16_FLOAT:
				return "rgba16float";
			case R32_UINT:
				return "r32uint";
			case R32_SINT:
				return "r32sint";
			case RG32_UINT:
				return "rg32uint";
			case RG32_SINT:
				return "rg32sint";
			case RGBA32_UINT:
				return "rgba32uint";
			case RGBA32_SINT:
				return "rgba32sint";
			case R32_FLOAT:
				return "r32float";
			case RG32_FLOAT:
				return "rg32float";
			case RGBA32_FLOAT:
				return "rgba32float";
			case RGB10A2_UNORM:
				return "rgb10a2unorm";
			case RGB10A2_UINT:
				return "rgb10a2uint";
			case RG11B10_FLOAT:
				return "rg11b10ufloat";
			case D32_FLOAT:
				return "depth32float";
			case D32_FLOAT_S8_UINT:
				return "depth32float-stencil8";
			case D24_UNORM_S8_UINT:
				return "depth24plus-stencil8";
			case D16_UNORM:
				return "depth16unorm";
			case S8_UINT:
				return "stencil8";
			default:
				return null; // RGB* three-channel formats have no WebGPU equivalent
		}
	}

	/** GpuFormat vertex-attribute -> WebGPU vertex format string. Returns null if unmapped. */
	public static String vertexFormat(final GpuFormat format) {
		switch (format) {
			case R32_FLOAT:
				return "float32";
			case RG32_FLOAT:
				return "float32x2";
			case RGB32_FLOAT:
				return "float32x3";
			case RGBA32_FLOAT:
				return "float32x4";
			case RGBA8_UNORM:
				return "unorm8x4";
			case RGBA8_SNORM:
				return "snorm8x4";
			case RGBA8_UINT:
				return "uint8x4";
			case RGBA8_SINT:
				return "sint8x4";
			case RG8_UNORM:
				return "unorm8x2";
			case RG8_SNORM:
				return "snorm8x2";
			case RG16_SINT:
				return "sint16x2";
			case RG16_UINT:
				return "uint16x2";
			case RGBA16_SINT:
				return "sint16x4";
			case RGBA16_UINT:
				return "uint16x4";
			case RG16_FLOAT:
				return "float16x2";
			case RGBA16_FLOAT:
				return "float16x4";
			case R32_SINT:
				return "sint32";
			case R32_UINT:
				return "uint32";
			case RG32_SINT:
				return "sint32x2";
			case RG32_UINT:
				return "uint32x2";
			case RGB32_SINT:
				return "sint32x3";
			case RGB32_UINT:
				return "uint32x3";
			case RGBA32_SINT:
				return "sint32x4";
			case RGBA32_UINT:
				return "uint32x4";
			default:
				return null;
		}
	}

	public static String topology(final PrimitiveTopology t) {
		switch (t) {
			case TRIANGLES:
			case QUADS: // quads are expanded to indexed triangles upstream
				return "triangle-list";
			case TRIANGLE_STRIP:
			case TRIANGLE_FAN: // WebGPU has no triangle-fan; caller must handle if used
				return "triangle-strip";
			case LINES:
			case DEBUG_LINES:
				return "line-list";
			case DEBUG_LINE_STRIP:
				return "line-strip";
			case POINTS:
				return "point-list";
			default:
				return "triangle-list";
		}
	}

	public static String compare(final CompareOp op) {
		switch (op) {
			case ALWAYS_PASS:
				return "always";
			case LESS_THAN:
				return "less";
			case LESS_THAN_OR_EQUAL:
				return "less-equal";
			case EQUAL:
				return "equal";
			case NOT_EQUAL:
				return "not-equal";
			case GREATER_THAN_OR_EQUAL:
				return "greater-equal";
			case GREATER_THAN:
				return "greater";
			case NEVER_PASS:
				return "never";
			default:
				return "always";
		}
	}

	public static String blendFactor(final BlendFactor f) {
		switch (f) {
			case ZERO:
				return "zero";
			case ONE:
				return "one";
			case SRC_COLOR:
				return "src";
			case ONE_MINUS_SRC_COLOR:
				return "one-minus-src";
			case DST_COLOR:
				return "dst";
			case ONE_MINUS_DST_COLOR:
				return "one-minus-dst";
			case SRC_ALPHA:
				return "src-alpha";
			case ONE_MINUS_SRC_ALPHA:
				return "one-minus-src-alpha";
			case DST_ALPHA:
				return "dst-alpha";
			case ONE_MINUS_DST_ALPHA:
				return "one-minus-dst-alpha";
			case CONSTANT_COLOR:
				return "constant";
			case ONE_MINUS_CONSTANT_COLOR:
				return "one-minus-constant";
			case CONSTANT_ALPHA:
				return "constant";
			case ONE_MINUS_CONSTANT_ALPHA:
				return "one-minus-constant";
			case SRC_ALPHA_SATURATE:
				return "src-alpha-saturated";
			default:
				return "one";
		}
	}

	public static String blendOp(final BlendOp op) {
		switch (op) {
			case ADD:
				return "add";
			case SUBTRACT:
				return "subtract";
			case REVERSE_SUBTRACT:
				return "reverse-subtract";
			case MIN:
				return "min";
			case MAX:
				return "max";
			default:
				return "add";
		}
	}

	public static String addressMode(final AddressMode m) {
		switch (m) {
			case REPEAT:
				return "repeat";
			case CLAMP_TO_EDGE:
				return "clamp-to-edge";
			default:
				return "clamp-to-edge";
		}
	}

	public static String filterMode(final FilterMode m) {
		return m == FilterMode.LINEAR ? "linear" : "nearest";
	}

	public static String indexFormat(final IndexType t) {
		return t == IndexType.INT ? "uint32" : "uint16";
	}
}

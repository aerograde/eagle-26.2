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
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.textures.AddressMode;

/**
 * Phase 3.3a: enum→GL-enum tables, ported from
 * com.mojang.blaze3d.opengl.GlConst but PRUNED per the recon (§3, §6):
 *  - no glPolygonMode (WebGL2 lacks it; wireframe is a no-op)
 *  - no logicOp table (no live caller)
 *  - no texel-buffer (TBO) internal-format usage
 * The GL token numbers are identical between GL3.3-core and WebGL2 for the
 * shared subset, so the int constants are copied verbatim. WebGL2-only buffer
 * usage uses the STATIC/DYNAMIC/STREAM DRAW/READ hints (no ARB_buffer_storage
 * flags, since WebGL2 has no immutable storage / persistent mapping).
 */
public final class WebGL2Const {

	private WebGL2Const() {
	}

	// texture cubemap face targets (GL_TEXTURE_CUBE_MAP_POSITIVE_X ..)
	public static final int GL_TEXTURE_CUBE_MAP = 34067;
	public static final int[] CUBEMAP_TARGETS = new int[] { 34069, 34070, 34071, 34072, 34073, 34074 };
	public static final int GL_TEXTURE_2D = 3553;

	// framebuffer bind targets
	public static final int GL_FRAMEBUFFER = 36160;
	public static final int GL_READ_FRAMEBUFFER = 36008;
	public static final int GL_DRAW_FRAMEBUFFER = 36009;
	public static final int GL_COLOR_ATTACHMENT0 = 36064;
	public static final int GL_DEPTH_ATTACHMENT = 36096;

	// buffer bind targets
	public static final int GL_ARRAY_BUFFER = 34962;
	public static final int GL_ELEMENT_ARRAY_BUFFER = 34963;
	public static final int GL_UNIFORM_BUFFER = 35345;
	public static final int GL_COPY_READ_BUFFER = 36662;
	public static final int GL_COPY_WRITE_BUFFER = 36663;
	public static final int GL_PIXEL_PACK_BUFFER = 35051;
	public static final int GL_PIXEL_UNPACK_BUFFER = 35052;

	// pixelStore
	public static final int GL_UNPACK_ROW_LENGTH = 3314;
	public static final int GL_UNPACK_SKIP_ROWS = 3315;
	public static final int GL_UNPACK_SKIP_PIXELS = 3316;
	public static final int GL_UNPACK_ALIGNMENT = 3317;
	public static final int GL_UNPACK_IMAGE_HEIGHT = 32878;
	public static final int GL_PACK_ROW_LENGTH = 3330;
	public static final int GL_PACK_ALIGNMENT = 3333;

	// clears
	public static final int GL_COLOR_BUFFER_BIT = 16384;
	public static final int GL_DEPTH_BUFFER_BIT = 256;

	// buffer/blit filter
	public static final int GL_NEAREST = 9728;
	public static final int GL_LINEAR = 9729;

	// texture params
	public static final int GL_TEXTURE_MAG_FILTER = 10240;
	public static final int GL_TEXTURE_MIN_FILTER = 10241;
	public static final int GL_TEXTURE_WRAP_S = 10242;
	public static final int GL_TEXTURE_WRAP_T = 10243;
	public static final int GL_CLAMP_TO_EDGE = 33071;
	public static final int GL_TEXTURE0 = 33984;
	public static final int GL_TEXTURE_BASE_LEVEL = 33084;
	public static final int GL_TEXTURE_MAX_LEVEL = 33085;
	public static final int GL_TEXTURE_MIN_LOD = 33082;
	public static final int GL_TEXTURE_MAX_LOD = 33083;
	public static final int GL_TEXTURE_COMPARE_MODE = 34892;
	public static final int GL_NEAREST_MIPMAP_LINEAR = 9986;
	public static final int GL_LINEAR_MIPMAP_LINEAR = 9987;
	public static final int GL_TEXTURE_MAX_ANISOTROPY_EXT = 34046;

	// shader / program
	public static final int GL_COMPILE_STATUS = 35713;
	public static final int GL_LINK_STATUS = 35714;
	public static final int GL_VERTEX_SHADER = 35633;
	public static final int GL_FRAGMENT_SHADER = 35632;
	public static final int GL_ACTIVE_UNIFORM_BLOCKS = 35382;
	/** getActiveUniformBlockParameter pname: the std140 byte size the driver requires
	 *  for a uniform block. A bindBufferRange smaller than this makes drawArrays/
	 *  ElementsInstanced raise GL_INVALID_OPERATION on real ANGLE (SwiftShader ignores it). */
	public static final int GL_UNIFORM_BLOCK_DATA_SIZE = 35392;

	// caps
	public static final int GL_DEPTH_TEST = 2929;
	public static final int GL_CULL_FACE = 2884;
	public static final int GL_BLEND = 3042;
	public static final int GL_SCISSOR_TEST = 3089;
	public static final int GL_STENCIL_TEST = 2960;
	public static final int GL_POLYGON_OFFSET_FILL = 32823;

	// draw modes
	public static final int GL_TRIANGLES = 4;

	// getParameter names
	public static final int GL_MAX_TEXTURE_SIZE = 3379;
	public static final int GL_MAX_COLOR_ATTACHMENTS = 36063;
	public static final int GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT = 35380;
	public static final int GL_VENDOR = 7936;
	public static final int GL_RENDERER = 7937;
	public static final int GL_VERSION = 7938;
	public static final int GL_MAX_SAMPLES = 36183;

	// sync
	public static final int GL_SYNC_GPU_COMMANDS_COMPLETE = 37143;
	public static final int GL_SYNC_STATUS = 37140;
	public static final int GL_SIGNALED = 37145;
	public static final int GL_ALREADY_SIGNALED = 37146;
	public static final int GL_TIMEOUT_EXPIRED = 37147;
	public static final int GL_CONDITION_SATISFIED = 37148;
	public static final int GL_WAIT_FAILED = 37149;
	public static final int GL_SYNC_FLUSH_COMMANDS_BIT = 1;

	// errors
	public static final int GL_NO_ERROR = 0;
	public static final int GL_OUT_OF_MEMORY = 1285;

	public static int toGl(final CompareOp compareOp) {
		return switch (compareOp) {
			case ALWAYS_PASS -> 519;
			case LESS_THAN -> 513;
			case LESS_THAN_OR_EQUAL -> 515;
			case EQUAL -> 514;
			case NOT_EQUAL -> 517;
			case GREATER_THAN_OR_EQUAL -> 518;
			case GREATER_THAN -> 516;
			case NEVER_PASS -> 512;
		};
	}

	public static int toGl(final BlendFactor blendFactor) {
		return switch (blendFactor) {
			case CONSTANT_ALPHA -> 32771;
			case CONSTANT_COLOR -> 32769;
			case DST_ALPHA -> 772;
			case DST_COLOR -> 774;
			case ONE -> 1;
			case ONE_MINUS_CONSTANT_ALPHA -> 32772;
			case ONE_MINUS_CONSTANT_COLOR -> 32770;
			case ONE_MINUS_DST_ALPHA -> 773;
			case ONE_MINUS_DST_COLOR -> 775;
			case ONE_MINUS_SRC_ALPHA -> 771;
			case ONE_MINUS_SRC_COLOR -> 769;
			case SRC_ALPHA -> 770;
			case SRC_ALPHA_SATURATE -> 776;
			case SRC_COLOR -> 768;
			case ZERO -> 0;
		};
	}

	public static int toGl(final BlendOp blendOp) {
		return switch (blendOp) {
			case ADD -> 32774;
			case SUBTRACT -> 32778;
			case REVERSE_SUBTRACT -> 32779;
			case MIN -> 32775;
			case MAX -> 32776;
		};
	}

	public static int toGl(final PrimitiveTopology primitiveTopology) {
		return switch (primitiveTopology) {
			case LINES -> 4;
			case DEBUG_LINES -> 1;
			case DEBUG_LINE_STRIP -> 3;
			case POINTS -> 0;
			case TRIANGLES -> 4;
			case TRIANGLE_STRIP -> 5;
			case TRIANGLE_FAN -> 6;
			case QUADS -> 4;
		};
	}

	public static int toGl(final IndexType indexType) {
		return switch (indexType) {
			case SHORT -> 5123;
			case INT -> 5125;
		};
	}

	public static int toGl(final AddressMode addressMode) {
		return switch (addressMode) {
			case REPEAT -> 10497;
			case CLAMP_TO_EDGE -> 33071;
		};
	}

	public static int toGl(final ShaderType type) {
		return switch (type) {
			case VERTEX -> 35633;
			case FRAGMENT -> 35632;
		};
	}

	public static int glFormatChannelCount(final int glExternalID) {
		if (glExternalID == 36249 || glExternalID == 6408) {
			return 4;
		} else if (glExternalID == 36248 || glExternalID == 6407) {
			return 3;
		} else if (glExternalID == 33320 || glExternalID == 33319) {
			return 2;
		} else {
			return glExternalID != 36244 && glExternalID != 6403 ? 0 : 1;
		}
	}

	public static boolean isGlFormatInteger(final int glExternalID) {
		return glExternalID == 36249 || glExternalID == 36248 || glExternalID == 33320 || glExternalID == 36244;
	}

	public static boolean isFormatNormalized(final GpuFormat gpuFormat) {
		return switch (gpuFormat) {
			case R8_UNORM, R8_SNORM, R16_UNORM, R16_SNORM, RG8_UNORM, RG8_SNORM, RG16_UNORM, RG16_SNORM, RGB8_UNORM,
					RGB8_SNORM, RGB16_UNORM, RGB16_SNORM, RGBA8_UNORM, RGBA8_SNORM, RGBA16_UNORM, RGB10A2_UNORM,
					D16_UNORM -> true;
			default -> false;
		};
	}

	public static int toGlInternalId(final GpuFormat gpuFormat) {
		return switch (gpuFormat) {
			case R8_UNORM -> 33321;
			case R8_SNORM -> 36756;
			case R16_UNORM -> 33322;
			case R16_SNORM -> 36760;
			case RG8_UNORM -> 33323;
			case RG8_SNORM -> 36757;
			case RG16_UNORM -> 33324;
			case RG16_SNORM -> 36761;
			default -> 0;
			case RGBA8_UNORM -> 32856;
			case RGBA8_SNORM -> 36759;
			case RGBA16_UNORM -> 32859;
			case RGB10A2_UNORM -> 32857;
			case D16_UNORM -> 33189;
			case RGBA16_SNORM -> 36763;
			case R8_UINT -> 33330;
			case R8_SINT -> 33329;
			case RG8_UINT -> 33336;
			case RG8_SINT -> 33335;
			case RGBA8_UINT -> 36220;
			case RGBA8_SINT -> 36238;
			case R16_UINT -> 33332;
			case R16_SINT -> 33331;
			case RG16_UINT -> 33338;
			case RG16_SINT -> 33337;
			case RGBA16_UINT -> 36214;
			case RGBA16_SINT -> 36232;
			case R32_UINT -> 33334;
			case R32_SINT -> 33333;
			case RG32_UINT -> 33340;
			case RG32_SINT -> 33339;
			case RGB32_UINT -> 36209;
			case RGB32_SINT -> 36227;
			case RGBA32_UINT -> 36208;
			case RGBA32_SINT -> 36226;
			case R16_FLOAT -> 33325;
			case RG16_FLOAT -> 33327;
			case RGBA16_FLOAT -> 34842;
			case R32_FLOAT -> 33326;
			case RG32_FLOAT -> 33328;
			case RGBA32_FLOAT -> 34836;
			case RGB10A2_UINT -> 36975;
			case RG11B10_FLOAT -> 35898;
			case D32_FLOAT -> 36012;
			case D32_FLOAT_S8_UINT -> 36013;
			case D24_UNORM_S8_UINT -> 35056;
			case S8_UINT -> 36168;
		};
	}

	public static int toGlExternalId(final GpuFormat gpuFormat) {
		return switch (gpuFormat) {
			case R8_UNORM, R8_SNORM, R16_UNORM, R16_SNORM, R16_FLOAT, R32_FLOAT -> 6403;
			case RG8_UNORM, RG8_SNORM, RG16_UNORM, RG16_SNORM, RG16_FLOAT, RG32_FLOAT -> 33319;
			case RGB8_UNORM, RGB8_SNORM, RGB16_UNORM, RGB16_SNORM, RG11B10_FLOAT, RGB16_FLOAT, RGB32_FLOAT -> 6407;
			case RGBA8_UNORM, RGBA8_SNORM, RGBA16_UNORM, RGB10A2_UNORM, RGBA16_SNORM, RGBA16_FLOAT, RGBA32_FLOAT -> 6408;
			case D16_UNORM, D32_FLOAT -> 6402;
			case R8_UINT, R8_SINT, R16_UINT, R16_SINT, R32_UINT, R32_SINT -> 36244;
			case RG8_UINT, RG8_SINT, RG16_UINT, RG16_SINT, RG32_UINT, RG32_SINT -> 33320;
			case RGBA8_UINT, RGBA8_SINT, RGBA16_UINT, RGBA16_SINT, RGBA32_UINT, RGBA32_SINT, RGB10A2_UINT -> 36249;
			case RGB32_UINT, RGB32_SINT, RGB8_UINT, RGB8_SINT, RGB16_UINT, RGB16_SINT -> 36248;
			case D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT -> 34041;
			case S8_UINT -> 6401;
			default -> 0;
		};
	}

	public static int toGlType(final GpuFormat gpuFormat) {
		return switch (gpuFormat) {
			case R8_UNORM, RG8_UNORM, RGB8_UNORM, RGBA8_UNORM, R8_UINT, RG8_UINT, RGBA8_UINT, S8_UINT, RGB8_UINT -> 5121;
			case R8_SNORM, RG8_SNORM, RGB8_SNORM, RGBA8_SNORM, R8_SINT, RG8_SINT, RGBA8_SINT, RGB8_SINT -> 5120;
			case R16_UNORM, RG16_UNORM, RGB16_UNORM, RGBA16_UNORM, D16_UNORM, R16_UINT, RG16_UINT, RGBA16_UINT,
					RGB16_UINT -> 5123;
			case R16_SNORM, RG16_SNORM, RGB16_SNORM, RGBA16_SNORM, R16_SINT, RG16_SINT, RGBA16_SINT, RGB16_SINT -> 5122;
			case RGB10A2_UNORM, RGB10A2_UINT -> 33640;
			case R32_UINT, RG32_UINT, RGB32_UINT, RGBA32_UINT -> 5125;
			case R32_SINT, RG32_SINT, RGB32_SINT, RGBA32_SINT -> 5124;
			case R16_FLOAT, RG16_FLOAT, RGBA16_FLOAT, RGB16_FLOAT -> 5131;
			case R32_FLOAT, RG32_FLOAT, RGBA32_FLOAT, D32_FLOAT, RGB32_FLOAT -> 5126;
			case RG11B10_FLOAT -> 35899;
			case D32_FLOAT_S8_UINT -> 36269;
			case D24_UNORM_S8_UINT -> 34042;
			default -> 0;
		};
	}

	/** WebGL2 has no immutable buffer storage; pick a usage hint from the flags. */
	public static int bufferUsageToGlEnum(final @GpuBuffer.Usage int usage) {
		boolean clientStorage = (usage & 4) != 0;
		if ((usage & 2) != 0) {
			return clientStorage ? 35040 : 35044; // STREAM_DRAW : STATIC_DRAW
		} else if ((usage & 1) != 0) {
			return clientStorage ? 35041 : 35045; // STREAM_READ : STATIC_READ
		} else {
			return 35044; // STATIC_DRAW
		}
	}

	public static int selectBufferBindTarget(final @GpuBuffer.Usage int usage) {
		if ((usage & 32) != 0) {
			return GL_ARRAY_BUFFER;
		} else if ((usage & 64) != 0) {
			return GL_ELEMENT_ARRAY_BUFFER;
		} else {
			return (usage & 128) != 0 ? GL_UNIFORM_BUFFER : GL_COPY_WRITE_BUFFER;
		}
	}
}

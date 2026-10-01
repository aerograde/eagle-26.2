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

import org.teavm.jso.webgl.WebGLUniformLocation;

/**
 * Ported from com.mojang.blaze3d.opengl.Uniform, PRUNED to drop the Utb (texel
 * buffer) variant: WebGL2 has no TBO, and the recon confirms the only TBO
 * consumer (clouds isamplerBuffer) is rewritten out of the web shader set. So a
 * WebGL2 uniform is either a std140 uniform-block binding (Ubo) or a texture
 * sampler unit (Sampler).
 */
public sealed interface WebGL2Uniform permits WebGL2Uniform.Ubo, WebGL2Uniform.Sampler {

	/**
	 * @param blockBinding the global UBO binding index this block was assigned
	 * @param dataSize     GL_UNIFORM_BLOCK_DATA_SIZE — the std140 byte size the driver
	 *                     requires; a bindBufferRange smaller than this raises
	 *                     GL_INVALID_OPERATION on real ANGLE (0 if the query failed).
	 */
	record Ubo(int blockBinding, int dataSize) implements WebGL2Uniform {
	}

	record Sampler(WebGLUniformLocation location, int samplerIndex) implements WebGL2Uniform {
	}
}

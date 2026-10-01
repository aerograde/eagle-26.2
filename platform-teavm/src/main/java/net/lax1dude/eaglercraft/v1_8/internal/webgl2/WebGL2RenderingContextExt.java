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

import org.teavm.jso.JSMethod;
import org.teavm.jso.webgl.WebGL2RenderingContext;

/**
 * Phase 3.3a JSO surface decision (docs/phase33-webgl2-recon.md work item 1):
 * org.teavm:teavm-jso-apis:0.13.0 already ships a fully-featured
 * {@link WebGL2RenderingContext} — sampler objects, sync/fence objects,
 * texStorage2D, vertexAttribIPointer, bindBufferRange/Base,
 * getUniformBlockIndex/uniformBlockBinding, drawBuffers, blitFramebuffer,
 * vertexAttribDivisor, drawArraysInstanced/drawElementsInstanced, VAOs,
 * getExtension/getParameter/getError — so upstream's copied
 * WebGL2RenderingContext.java is NOT needed.
 *
 * The ONE gap for the 26.2 backend is the PACK-buffer offset form of
 * {@code readPixels} (base JSO only exposes the ArrayBufferView form). This tiny
 * extension adds it (async PBO readback path, GlCommandEncoder.copyTextureToBuffer
 * → WebGL2CommandEncoder). TeaVM maps the Java method to the JS "readPixels" name.
 */
public interface WebGL2RenderingContextExt extends WebGL2RenderingContext {

	/** readPixels into the bound PIXEL_PACK_BUFFER at the given byte offset. */
	@JSMethod("readPixels")
	void readPixels(int x, int y, int width, int height, int format, int type, int packBufferOffset);

	/** texSubImage2D sourcing from the bound PIXEL_UNPACK_BUFFER at a byte offset. */
	@JSMethod("texSubImage2D")
	void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, int pboOffset);

	/** getActiveUniformBlockParameter as an int — used to read GL_UNIFORM_BLOCK_DATA_SIZE
	 *  (the std140 size a UBO bind must cover). The base JSO binding returns a JSObject
	 *  whose runtime type depends on pname; for DATA_SIZE it is a plain GLuint, so this
	 *  int-typed form coerces cleanly. */
	@JSMethod("getActiveUniformBlockParameter")
	int getActiveUniformBlockParameteri(org.teavm.jso.webgl.WebGLProgram program, int uniformBlockIndex, int pname);

}

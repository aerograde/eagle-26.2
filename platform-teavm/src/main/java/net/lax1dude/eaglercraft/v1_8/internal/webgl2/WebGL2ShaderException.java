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

/**
 * Local compile/link failure signal. Upstream GlProgram.link throws
 * net.minecraft.client.renderer.ShaderManager.CompilationException; we use a
 * package-local exception instead so the WebGL2 backend never drags ShaderManager
 * (and its dependency surface) into the TeaVM reachability graph. See report /
 * recon deviation notes.
 */
public class WebGL2ShaderException extends RuntimeException {
	public WebGL2ShaderException(final String message) {
		super(message);
	}
}

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

import com.mojang.blaze3d.shaders.ShaderType;

import org.teavm.jso.webgl.WebGLShader;

/**
 * Ported from com.mojang.blaze3d.opengl.GlShaderModule — a compiled WebGL shader
 * object plus its identity. Holds a JSO {@link WebGLShader} instead of an int
 * handle (WebGL uses opaque object handles, not integer names).
 */
public class WebGL2ShaderModule implements AutoCloseable {

	public static final WebGL2ShaderModule INVALID_SHADER = new WebGL2ShaderModule(null, null, "invalid", ShaderType.VERTEX);

	private final WebGL2RenderingContextExt gl;
	private WebGLShader shader;
	private final String id;
	private final ShaderType type;

	public WebGL2ShaderModule(final WebGL2RenderingContextExt gl, final WebGLShader shader, final String id, final ShaderType type) {
		this.gl = gl;
		this.shader = shader;
		this.id = id;
		this.type = type;
	}

	public WebGLShader getShader() {
		return this.shader;
	}

	public String getId() {
		return this.id;
	}

	public ShaderType getType() {
		return this.type;
	}

	@Override
	public void close() {
		if (this.shader != null && this.gl != null) {
			this.gl.deleteShader(this.shader);
			this.shader = null;
		}
	}
}

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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import org.teavm.jso.webgl.WebGLProgram;
import org.teavm.jso.webgl.WebGLShader;
import org.teavm.jso.webgl.WebGLUniformLocation;
import org.teavm.jso.JSBody;

/**
 * Ported from com.mojang.blaze3d.opengl.GlProgram — link + std140 UBO/sampler
 * binding setup. WebGL2 supports every entrypoint 1:1 (bindAttribLocation,
 * getUniformBlockIndex, uniformBlockBinding, getUniformLocation, uniform1i,
 * getActiveUniformBlockName), which the recon flags as the single biggest
 * viability factor. Differences vs GL:
 *  - opaque WebGLProgram/WebGLShader/WebGLUniformLocation object handles;
 *  - Utb (texel buffer) path removed (WebGL2 has no TBO — see WebGL2Uniform);
 *  - compile/link failure raises the package-local WebGL2ShaderException instead
 *    of ShaderManager.CompilationException (keeps the reach graph lean).
 */
public class WebGL2Program implements AutoCloseable {

	public static final Set<String> BUILT_IN_UNIFORMS = new HashSet<>(List.of("Projection", "Lighting", "Fog", "Globals"));
	public static final WebGL2Program INVALID_PROGRAM = new WebGL2Program(null, null, "invalid");

	private final WebGL2RenderingContextExt gl;
	private final WebGLProgram programId;
	private final String debugLabel;
	private final Map<String, WebGL2Uniform> uniformsByName = new HashMap<>();

	private WebGL2Program(final WebGL2RenderingContextExt gl, final WebGLProgram programId, final String debugLabel) {
		this.gl = gl;
		this.programId = programId;
		this.debugLabel = debugLabel;
	}

	public WebGLProgram getProgramId() {
		return this.programId;
	}

	public String getDebugLabel() {
		return this.debugLabel;
	}

	public Map<String, WebGL2Uniform> getUniforms() {
		return this.uniformsByName;
	}

	public WebGL2Uniform getUniform(final String name) {
		return this.uniformsByName.get(name);
	}

	@Override
	public String toString() {
		return this.debugLabel;
	}

	/**
	 * Compile one shader stage: GlslPreprocessor output is expected already; this
	 * routine runs the 330→300es translation, uploads and compiles. Returns
	 * INVALID_SHADER on failure (never null), mirroring GlDevice.compileShader.
	 */
	public static WebGL2ShaderModule compileShaderModule(final WebGL2RenderingContextExt gl, final String preprocessedSource, final String id,
			final ShaderType type) {
		String esSource;
		try {
			esSource = WebGL2ShaderTranslator.translate(preprocessedSource, type);
		} catch (IllegalArgumentException ex) {
			recordShaderCompile(id, false, ex.getMessage());
			System.err.println("eagler-webgl2: rejected incompatible " + type.getName() + " shader (" + id + "): "
					+ ex.getMessage());
			return WebGL2ShaderModule.INVALID_SHADER;
		}
		WebGLShader shaderId = gl.createShader(WebGL2Const.toGl(type));
		gl.shaderSource(shaderId, esSource);
		gl.compileShader(shaderId);
		if (!gl.getShaderParameterb(shaderId, WebGL2Const.GL_COMPILE_STATUS)) {
			String logInfo = gl.getShaderInfoLog(shaderId);
			gl.deleteShader(shaderId);
			recordShaderCompile(id, false, logInfo);
			System.err.println("eagler-webgl2: couldn't compile " + type.getName() + " shader (" + id + "): "
					+ (logInfo == null ? "" : logInfo.trim()));
			return WebGL2ShaderModule.INVALID_SHADER;
		}
		recordShaderCompile(id, true, null);
		return new WebGL2ShaderModule(gl, shaderId, id, type);
	}

	@JSBody(params = { "id", "ok", "message" }, script = "try {"
			+ " var s = globalThis.__eaglerShaderPack || (globalThis.__eaglerShaderPack = {"
			+ " backend:'WebGL2 GLSL ES 3.00', api:1, compiled:0, failed:0, lastError:null});"
			+ " if(ok) s.compiled++; else { s.failed++; s.lastError = { shader:id, message:message || '' }; }"
			+ "} catch(e) {}")
	private static native void recordShaderCompile(String id, boolean ok, String message);

	public static WebGL2Program link(final WebGL2RenderingContextExt gl, final WebGL2ShaderModule vertexShader, final WebGL2ShaderModule fragmentShader,
			final VertexFormat[] vertexBindings, final String debugLabel) {
		WebGLProgram programId = gl.createProgram();
		if (programId == null) {
			throw new WebGL2ShaderException("Could not create shader program");
		}

		int attributeLocation = 0;
		String previousName = null;
		if (vertexBindings != null) {
			for (VertexFormat vertexFormat : vertexBindings) {
				if (vertexFormat != null) {
					for (VertexFormatElement attribute : vertexFormat.getElements()) {
						String attributeName = attribute.name();
						if (!attributeName.equals(previousName)) {
							gl.bindAttribLocation(programId, attributeLocation, attributeName);
						}
						previousName = attributeName;
						attributeLocation++;
					}
				}
			}
		}

		gl.attachShader(programId, vertexShader.getShader());
		gl.attachShader(programId, fragmentShader.getShader());
		gl.linkProgram(programId);
		boolean linkStatus = gl.getProgramParameterb(programId, WebGL2Const.GL_LINK_STATUS);
		String linkMessage = gl.getProgramInfoLog(programId);
		if (linkStatus && (linkMessage == null || !linkMessage.contains("Failed for unknown reason"))) {
			return new WebGL2Program(gl, programId, debugLabel);
		} else {
			gl.deleteProgram(programId);
			throw new WebGL2ShaderException("Error linking program " + debugLabel + ": " + linkMessage);
		}
	}

	/** Ported from GlProgram.setupBindGroupLayouts, minus the TEXEL_BUFFER branch. */
	public void setupBindGroupLayouts(final List<BindGroupLayout> bindGroupLayouts) {
		BindGroupLayout.ensureCompatible(bindGroupLayouts);
		List<BindGroupLayout.UniformDescription> uniforms = BindGroupLayout.flattenUniforms(bindGroupLayouts);
		List<String> samplers = BindGroupLayout.flattenSamplers(bindGroupLayouts);
		int nextUboBinding = 0;
		int nextSamplerIndex = 0;

		for (BindGroupLayout.UniformDescription uniformDescription : uniforms) {
			String uniformName = uniformDescription.name();
			switch (uniformDescription.type()) {
				case UNIFORM_BUFFER -> {
					int index = this.gl.getUniformBlockIndex(this.programId, uniformName);
					// getUniformBlockIndex returns GL_INVALID_INDEX (0xFFFFFFFF) for a
					// block the linker dropped. On desktop LWJGL that's a signed int
					// (-1); the browser hands back the *unsigned* 4294967295, so the
					// upstream `index != -1` guard let an invalid index through to
					// uniformBlockBinding -> INVALID_VALUE (GL 1281), which then sat
					// sticky and got misattributed to the next getError() (a texture
					// upload). Mask to unsigned so both encodings are rejected.
					if ((index & 0xFFFFFFFFL) != 0xFFFFFFFFL) {
						int uboBinding = nextUboBinding++;
						this.gl.uniformBlockBinding(this.programId, index, uboBinding);
						int dataSize = this.gl.getActiveUniformBlockParameteri(this.programId, index,
								WebGL2Const.GL_UNIFORM_BLOCK_DATA_SIZE);
						this.uniformsByName.put(uniformName, new WebGL2Uniform.Ubo(uboBinding, dataSize));
					}
				}
				case TEXEL_BUFFER -> {
					// WebGL2 has no texel buffers; the recon rewrites the only consumer
					// (clouds isamplerBuffer) out of the web shader set.
					System.err.println("eagler-webgl2: TEXEL_BUFFER uniform '" + uniformName
							+ "' is unsupported on WebGL2 (TODO 3.3b: clouds rewrite)");
				}
			}
		}

		for (String sampler : samplers) {
			WebGLUniformLocation location = this.gl.getUniformLocation(this.programId, sampler);
			if (location != null) {
				int samplerIndex = nextSamplerIndex++;
				this.uniformsByName.put(sampler, new WebGL2Uniform.Sampler(location, samplerIndex));
			}
		}

		int totalDefinedBlocks = this.gl.getProgramParameteri(this.programId, WebGL2Const.GL_ACTIVE_UNIFORM_BLOCKS);
		for (int i = 0; i < totalDefinedBlocks; i++) {
			String name = this.gl.getActiveUniformBlockName(this.programId, i);
			if (!this.uniformsByName.containsKey(name) && !samplers.contains(name) && BUILT_IN_UNIFORMS.contains(name)) {
				int uboBinding = nextUboBinding++;
				this.gl.uniformBlockBinding(this.programId, i, uboBinding);
				int dataSize = this.gl.getActiveUniformBlockParameteri(this.programId, i,
						WebGL2Const.GL_UNIFORM_BLOCK_DATA_SIZE);
				this.uniformsByName.put(name, new WebGL2Uniform.Ubo(uboBinding, dataSize));
			}
		}
	}

	@Override
	public void close() {
		if (this.programId != null && this.gl != null) {
			this.gl.deleteProgram(this.programId);
		}
	}
}

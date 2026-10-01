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

/**
 * Phase 3.3a (recon §4, work item 3): the small "#version 330" → "#version 300
 * es" translation pass applied AFTER the game's CPU-side GlslPreprocessor output.
 *
 * MC 26.2 core shaders are all "#version 330" and already declare explicit
 * {@code out vec4} fragment outputs, std140 UBO blocks, texelFetch/textureLod,
 * gl_VertexID — all valid in GLSL ES 3.00. The only edits WebGL2 requires:
 *   1. rewrite the {@code #version 330 [core]} line to {@code #version 300 es}
 *   2. inject default precision qualifiers after the version line (mandatory in
 *      ES for fragment shaders; harmless in vertex shaders, so we match on both
 *      when the source lacks an explicit "precision" of its own).
 * Everything else is passed through untouched.
 */
public final class WebGL2ShaderTranslator {

	private WebGL2ShaderTranslator() {
	}

	private static final String ES_HEADER = "#version 300 es\n";
	private static final String EAGLER_PACK_HEADER =
			"#define EAGLER_WEBGL2 1\n#define EAGLER_SHADER_PACK_API 1\n";
	private static final int MAX_SHADER_SOURCE_CHARS = 2 * 1024 * 1024;
	private static final String FRAGMENT_PRECISION =
			"precision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp samplerCube;\n";
	private static final String VERTEX_PRECISION =
			"precision highp float;\nprecision highp int;\n";

	/**
	 * @param source the preprocessed GLSL 330 source (defines already injected).
	 * @param type   VERTEX or FRAGMENT (controls the precision block injected).
	 * @return GLSL ES 3.00 source ready for glShaderSource.
	 */
	public static String translate(final String source, final ShaderType type) {
		validateCompatible(source);
		String precision = type == ShaderType.FRAGMENT ? FRAGMENT_PRECISION : VERTEX_PRECISION;
		int versionIdx = source.indexOf("#version");
		if (versionIdx < 0) {
			// no version directive at all — prepend a full ES header
			return ES_HEADER + EAGLER_PACK_HEADER + precision + source;
		}

		// find the end of the #version line
		int lineEnd = source.indexOf('\n', versionIdx);
		if (lineEnd < 0) {
			lineEnd = source.length();
		}

		String before = source.substring(0, versionIdx);
		String after = lineEnd < source.length() ? source.substring(lineEnd + 1) : "";

		StringBuilder sb = new StringBuilder(source.length() + 128);
		sb.append(before);
		sb.append(ES_HEADER);
		sb.append(EAGLER_PACK_HEADER);
		// only inject default precision if the shader doesn't set its own first
		if (!after.contains("precision ")) {
			sb.append(precision);
		}
		sb.append(after);
		return sb.toString();
	}

	/**
	 * Validate the portable core/post subset before allocating driver objects. Iris-style shadow,
	 * compute, image and shader-storage passes require a later dedicated render graph.
	 */
	public static void validateCompatible(final String source) {
		if (source == null) {
			throw new IllegalArgumentException("shader source is null");
		}
		if (source.length() > MAX_SHADER_SOURCE_CHARS) {
			throw new IllegalArgumentException("shader source exceeds the 2 MiB WebGL2 safety limit");
		}

		String code = stripComments(source);
		int version = parseVersion(code);
		if (version > 330) {
			throw new IllegalArgumentException("GLSL " + version + " requires desktop OpenGL newer than the WebGL2 shader layer");
		}
		reject(code, "samplerBuffer", "texture-buffer samplers");
		reject(code, "image2D", "image load/store");
		reject(code, "image3D", "image load/store");
		reject(code, "atomic_uint", "atomic counters");
		reject(code, "gl_InvocationID", "geometry/tessellation stages");
		reject(code, "GL_ARB_", "desktop GL_ARB extensions");
		reject(code, "GL_NV_", "vendor-specific GL_NV extensions");
		reject(code, "GL_AMD_", "vendor-specific GL_AMD extensions");
		if (code.matches("(?s).*layout\\s*\\([^)]*\\bbinding\\s*=.*")) {
			throw new IllegalArgumentException("explicit layout(binding=...) is not available in GLSL ES 3.00");
		}
	}

	private static void reject(final String code, final String token, final String feature) {
		if (code.contains(token)) {
			throw new IllegalArgumentException(feature + " are not supported by WebGL2 shader packs");
		}
	}

	private static int parseVersion(final String source) {
		int at = source.indexOf("#version");
		if (at < 0) {
			return 0;
		}
		int i = at + 8;
		while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
			i++;
		}
		int value = 0;
		while (i < source.length()) {
			char c = source.charAt(i++);
			if (c < '0' || c > '9') {
				break;
			}
			value = value * 10 + c - '0';
		}
		return value;
	}

	private static String stripComments(final String source) {
		StringBuilder out = new StringBuilder(source.length());
		boolean line = false;
		boolean block = false;
		for (int i = 0; i < source.length(); ++i) {
			char c = source.charAt(i);
			char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
			if (line) {
				if (c == '\n') {
					line = false;
					out.append(c);
				}
			} else if (block) {
				if (c == '*' && n == '/') {
					block = false;
					i++;
				} else if (c == '\n') {
					out.append(c);
				}
			} else if (c == '/' && n == '/') {
				line = true;
				i++;
			} else if (c == '/' && n == '*') {
				block = true;
				i++;
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}
}

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

/**
 * WebGPU backend (increment 1): maps a vanilla {@link RenderPipeline} to a baked
 * manifest program name in {@link WGSLShaderPack}. The manifest (from MC 26.1.2)
 * names programs by core-shader basename plus a {@code #DEFINE} variant suffix,
 * e.g. {@code terrain}, {@code terrain#ALPHA_CUTOUT=0.5;}, {@code entity#ALPHA_CUTOUT=0.1;NO_OVERLAY;}.
 *
 * Resolution:
 *  1. base = vertex-shader identifier basename (strip namespace + "core/" dir);
 *  2. if the pipeline has shader defines, build the alphabetically-sorted
 *     "#KEY=VALUE;FLAG;" suffix and try base+suffix;
 *  3. else / on miss, fall back to the bare base program;
 *  4. on total miss, log ONCE and return null (caller skips the draw, never crashes).
 *
 * 26.2 pipelines absent from the 26.1.2 manifest are tracked as name-mapping gaps
 * in webgpu-backend-plan.md.
 */
public final class WebGPUProgramMapping {

	private WebGPUProgramMapping() {
	}

	private static final Set<String> loggedMisses = Collections.synchronizedSet(new java.util.HashSet<>());

	public static WGSLShaderPack.Program resolve(final RenderPipeline pipeline) {
		String base = basename(pipeline.getVertexShader());
		ShaderDefines defines = pipeline.getShaderDefines();
		String variant = base + variantSuffix(defines);

		WGSLShaderPack.Program p = WGSLShaderPack.get(variant);
		if (p != null) {
			return p;
		}
		p = WGSLShaderPack.get(base);
		if (p != null) {
			return p;
		}
		// total miss — log once with enough detail to add to the gap list
		if (loggedMisses.add(base)) {
			System.err.println("webgpu-todo: no manifest program for pipeline " + pipeline.getLocation()
					+ " (vsh=" + pipeline.getVertexShader() + " tried '" + variant + "' and '" + base + "') — skipping draws");
		}
		return null;
	}

	/** Basename of a shader identifier: "minecraft:core/terrain" -> "terrain". */
	public static String basename(final Identifier id) {
		String path = id.getPath();
		int slash = path.lastIndexOf('/');
		if (slash >= 0) {
			path = path.substring(slash + 1);
		}
		// strip a common ".vsh"/".fsh" / extension if present
		int dot = path.indexOf('.');
		if (dot >= 0) {
			path = path.substring(0, dot);
		}
		return path;
	}

	/** Build the "#KEY=VALUE;FLAG;" suffix, alphabetically sorted (manifest order). */
	public static String variantSuffix(final ShaderDefines defines) {
		if (defines == null) {
			return "";
		}
		Map<String, String> values = defines.values();
		Set<String> flags = defines.flags();
		if ((values == null || values.isEmpty()) && (flags == null || flags.isEmpty())) {
			return "";
		}
		List<String> tokens = new ArrayList<>();
		if (values != null) {
			for (Map.Entry<String, String> e : values.entrySet()) {
				tokens.add(e.getKey() + "=" + e.getValue());
			}
		}
		if (flags != null) {
			tokens.addAll(flags);
		}
		Collections.sort(tokens);
		StringBuilder sb = new StringBuilder("#");
		for (String t : tokens) {
			sb.append(t).append(';');
		}
		return sb.toString();
	}
}

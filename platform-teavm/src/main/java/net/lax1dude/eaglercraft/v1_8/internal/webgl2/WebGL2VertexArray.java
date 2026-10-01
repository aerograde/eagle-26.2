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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import org.teavm.jso.webgl.WebGLVertexArrayObject;

/**
 * Ported from com.mojang.blaze3d.opengl.VertexArrayCache — the LEGACY combined-
 * attributes path ONLY (recon §3): the ARB_vertex_attrib_binding "Separate" path
 * is ES3.1+, so WebGL2 must use glVertexAttribPointer + glVertexAttribDivisor
 * exactly like VertexArrayCache.Emulated. WebGL2 provides VAOs natively
 * (createVertexArray/bindVertexArray).
 *
 * baseVertex FOLDING (recon §3, work item 4): GL3.3 threads baseVertex through
 * glDrawElementsBaseVertex; WebGL2 has no baseVertex. FIX: fold it into the
 * vertex-attribute byte offsets — totalOffset += baseVertex * vertexStride — and
 * re-issue the attribute pointers before the draw. COST: when a draw carries a
 * non-zero baseVertex (or a different one than last time), the cached VAO's
 * attribute pointers are re-specified rather than reused; zero-baseVertex draws
 * keep the fast cached path.
 */
public class WebGL2VertexArray {

	private final WebGL2RenderingContextExt gl;
	private final Map<List<VertexFormat>, Entry> formatCache = new HashMap<>();
	private final Map<VertexFormat[], Entry> identityCache = new IdentityHashMap<>();

	public WebGL2VertexArray(final WebGL2RenderingContextExt gl) {
		this.gl = gl;
	}

	public static final class Entry {
		final WebGLVertexArrayObject id;
		long lastBaseVertex = -1L;
		VertexFormat[] lastVertexBindings;
		final int[] attributeDivisors = new int[VertexFormat.MAX_VERTEX_ELEMENTS];

		Entry(final WebGLVertexArrayObject id) {
			this.id = id;
			Arrays.fill(this.attributeDivisors, Integer.MIN_VALUE);
		}
	}

	/**
	 * @param baseVertex folded into attribute offsets (in vertices).
	 * @param lastBound  the VAO bound by the previous draw, or null to force a full
	 *                   attribute re-specify (e.g. the vertex buffers changed).
	 */
	public Entry bindVertexArray(final VertexFormat[] vertexBindings, final GpuBufferSlice[] vertexBuffers, final int baseVertex,
			final Entry lastBound) {
		// RenderPipeline owns one stable vertex-format array for its lifetime. Keep an
		// identity fast path so every draw does not allocate an ArrayList and compare
		// sixteen slots through AbstractList.equals(). The measured multiplayer trace
		// spent 6.6 seconds of sampled self time in those two operations alone.
		Entry entry = this.identityCache.get(vertexBindings);
		if (entry == null) {
			// Preserve the original format-sharing behavior for the first encounter of a
			// pipeline array. The copied key is retained only once, never on the hot path.
			List<VertexFormat> listBindings = new ArrayList<>(Arrays.asList(vertexBindings));
			entry = this.formatCache.get(listBindings);
			if (entry == null) {
				WebGLVertexArrayObject id = this.gl.createVertexArray();
				this.gl.bindVertexArray(id);
				entry = new Entry(id);
				this.setupCombinedAttributes(entry, vertexBindings, true, vertexBuffers, baseVertex);
				entry.lastBaseVertex = baseVertex;
				entry.lastVertexBindings = vertexBindings;
				this.formatCache.put(listBindings, entry);
				this.identityCache.put(vertexBindings, entry);
				return entry;
			}
			this.identityCache.put(vertexBindings, entry);
		}

		// The encoder passes the VAO it knows is currently bound. Terrain batches
		// change baseVertex for almost every draw, but they keep using this same VAO;
		// folding the new offset still requires the pointer updates below, while an
		// identical bindVertexArray call does not.
		if (entry != lastBound) {
			this.gl.bindVertexArray(entry.id);
		}
		// Re-specify attribute pointers if the buffers changed (lastBound == null),
		// the VAO differs from the last bound one, or baseVertex changed (folding).
		if (entry != lastBound || entry.lastBaseVertex != baseVertex || entry.lastVertexBindings != vertexBindings) {
			this.setupCombinedAttributes(entry, vertexBindings, false, vertexBuffers, baseVertex);
			entry.lastBaseVertex = baseVertex;
			entry.lastVertexBindings = vertexBindings;
		}
		return entry;
	}

	private void setupCombinedAttributes(final Entry entry, final VertexFormat[] vertexBindings, final boolean enable,
			final GpuBufferSlice[] vertexBuffers, final int baseVertex) {
		int attributeIndex = 0;
		for (int i = 0; i < vertexBindings.length; i++) {
			VertexFormat vertexBinding = vertexBindings[i];
			if (vertexBinding != null) {
				WebGL2Buffer buffer = (WebGL2Buffer) vertexBuffers[i].buffer();
				this.gl.bindBuffer(WebGL2Const.GL_ARRAY_BUFFER, buffer.handle());
				int vertexSize = vertexBinding.getVertexSize();
				for (VertexFormatElement element : vertexBinding.getElements()) {
					// baseVertex folded here: shift the attribute's start by whole vertices
					long totalOffset = vertexBuffers[i].offset() + element.offset() + (long) baseVertex * vertexSize;
					int glExternalId = WebGL2Const.toGlExternalId(element.format());
					int glType = WebGL2Const.toGlType(element.format());
					boolean isIntegerFormat = WebGL2Const.isGlFormatInteger(glExternalId);
					boolean isNormalizedFormat = WebGL2Const.isFormatNormalized(element.format());
					int channelCount = WebGL2Const.glFormatChannelCount(glExternalId);
					if (enable) {
						this.gl.enableVertexAttribArray(attributeIndex);
					}
					if (isIntegerFormat) {
						this.gl.vertexAttribIPointer(attributeIndex, channelCount, glType, vertexSize, (int) totalOffset);
					} else {
						this.gl.vertexAttribPointer(attributeIndex, channelCount, glType, isNormalizedFormat, vertexSize, (int) totalOffset);
					}
					// Divisors are VAO state and baseVertex does not affect them. The format
					// cache can share equal VertexFormats whose equals() omits stepRate, so
					// retain the last requested value per attribute instead of assuming it.
					int stepRate = vertexBinding.getStepRate();
					if (entry.attributeDivisors[attributeIndex] != stepRate) {
						this.gl.vertexAttribDivisor(attributeIndex, stepRate);
						entry.attributeDivisors[attributeIndex] = stepRate;
					}
					attributeIndex++;
				}
			}
		}
	}
}

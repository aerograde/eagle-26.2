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

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.lwjgl.PointerBuffer;

/**
 * Phase 3.3a WebGL2 RenderPassBackend, ported from
 * com.mojang.blaze3d.opengl.GlRenderPass. State tracking (pipeline / vertex+index
 * buffers / uniforms / samplers / scissor / dirty set) is identical; the actual
 * draw is handed to WebGL2CommandEncoder which folds baseVertex into the vertex
 * attribute offsets (recon work item 4).
 *
 * multiDraw* / drawIndirect / drawIndexedIndirect throw UnsupportedOperationException
 * (DeviceFeatures report those false, so the public RenderPass wrappers reject the
 * calls before they ever reach this backend). drawMultipleIndexed is NOT feature-
 * gated (the terrain renderer uses it), so it is implemented as a per-draw CPU
 * loop, exactly like GlCommandEncoder.executeDrawMultiple.
 */
public class WebGL2RenderPass implements RenderPassBackend {

	private final WebGL2CommandEncoder encoder;
	private final WebGL2Device device;
	private final boolean hasDepthTexture;
	private final ScissorState defaultScissorState;

	protected WebGL2RenderPipeline pipeline;
	protected final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[16];
	protected boolean vertexBufferDirty = true;
	protected GpuBuffer indexBuffer;
	protected IndexType indexType = IndexType.INT;
	private final ScissorState scissorState = new ScissorState();
	protected final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
	protected final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
	protected final Set<String> dirtyUniforms = new HashSet<>();
	protected final int colorAttachmentCount;

	public WebGL2RenderPass(final WebGL2CommandEncoder encoder, final WebGL2Device device, final boolean hasDepthTexture,
			final int colorAttachmentCount, final ScissorState defaultScissorState) {
		this.encoder = encoder;
		this.device = device;
		this.hasDepthTexture = hasDepthTexture;
		this.colorAttachmentCount = colorAttachmentCount;
		this.defaultScissorState = defaultScissorState;
		this.scissorState.setFrom(defaultScissorState);
	}

	public boolean hasDepthTexture() {
		return this.hasDepthTexture;
	}

	@Override
	public void pushDebugGroup(final Supplier<String> label) {
		// KHR_debug is absent on WebGL2 (recon §3) — no-op.
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(final RenderPipeline pipeline) {
		if (this.pipeline == null || this.pipeline.info() != pipeline) {
			this.dirtyUniforms.addAll(this.uniforms.keySet());
			this.dirtyUniforms.addAll(this.samplers.keySet());
		}
		this.pipeline = this.device.getOrCompilePipeline(pipeline);
	}

	@Override
	public void bindTexture(final String name, final GpuTextureView textureView, final GpuSampler sampler) {
		if (sampler == null) {
			this.samplers.remove(name);
		} else {
			this.samplers.put(name, new TextureViewAndSampler((WebGL2TextureView) textureView, (WebGL2Sampler) sampler));
		}
		this.dirtyUniforms.add(name);
	}

	@Override
	public void setUniform(final String name, final GpuBuffer value) {
		this.uniforms.put(name, value.slice());
		this.dirtyUniforms.add(name);
	}

	@Override
	public void setUniform(final String name, final GpuBufferSlice value) {
		this.uniforms.put(name, value);
		this.dirtyUniforms.add(name);
	}

	@Override
	public void enableScissor(final int x, final int y, final int width, final int height) {
		this.scissorState.enable(x, y, width, height);
	}

	@Override
	public void disableScissor() {
		this.scissorState.setFrom(this.defaultScissorState);
	}

	public boolean isScissorEnabled() {
		return this.scissorState.enabled();
	}

	public int getScissorX() {
		return this.scissorState.x();
	}

	public int getScissorY() {
		return this.scissorState.y();
	}

	public int getScissorWidth() {
		return this.scissorState.width();
	}

	public int getScissorHeight() {
		return this.scissorState.height();
	}

	@Override
	public void setVertexBuffer(final int slot, final GpuBufferSlice vertexBuffer) {
		GpuBuffer inputBuffer = vertexBuffer != null ? vertexBuffer.buffer() : null;
		GpuBuffer existingBuffer = this.vertexBuffers[slot] != null ? this.vertexBuffers[slot].buffer() : null;
		long inputOffset = vertexBuffer != null ? vertexBuffer.offset() : 0L;
		long existingOffset = this.vertexBuffers[slot] != null ? this.vertexBuffers[slot].offset() : 0L;
		this.vertexBufferDirty |= inputBuffer != existingBuffer || inputOffset != existingOffset;
		this.vertexBuffers[slot] = vertexBuffer;
	}

	@Override
	public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
		this.indexBuffer = indexBuffer;
		this.indexType = indexType;
	}

	@Override
	public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
		this.encoder.executeDraw(this, vertexOffset, firstIndex, indexCount, this.indexType, instanceCount, firstInstance);
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		this.encoder.executeDraw(this, firstVertex, 0, vertexCount, null, instanceCount, firstInstance);
	}

	@Override
	public <T> void drawMultipleIndexed(final Collection<RenderPass.Draw<T>> draws, final GpuBuffer defaultIndexBuffer,
			final IndexType defaultIndexType, final Collection<String> dynamicUniforms, final T uniformArgument) {
		this.encoder.executeDrawMultiple(this, draws, defaultIndexBuffer, defaultIndexType, uniformArgument);
	}

	// ==== unsupported GPU multi-draw / indirect paths (DeviceFeatures all false) ====

	@Override
	public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		throw new UnsupportedOperationException("eagler-webgl2: multiDrawDirectInterleaved is unsupported on WebGL2");
	}

	@Override
	public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
		throw new UnsupportedOperationException("eagler-webgl2: multiDrawDirectSeparate is unsupported on WebGL2");
	}

	@Override
	public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
		throw new UnsupportedOperationException("eagler-webgl2: drawIndexedIndirect is unsupported on WebGL2");
	}

	@Override
	public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		throw new UnsupportedOperationException("eagler-webgl2: multiDrawDirectInterleaved is unsupported on WebGL2");
	}

	@Override
	public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
		throw new UnsupportedOperationException("eagler-webgl2: multiDrawDirectSeparate is unsupported on WebGL2");
	}

	@Override
	public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
		throw new UnsupportedOperationException("eagler-webgl2: drawIndirect is unsupported on WebGL2");
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		((WebGL2QueryPool) pool).writeTimestamp(index);
	}

	protected record TextureViewAndSampler(WebGL2TextureView view, WebGL2Sampler sampler) {
	}
}

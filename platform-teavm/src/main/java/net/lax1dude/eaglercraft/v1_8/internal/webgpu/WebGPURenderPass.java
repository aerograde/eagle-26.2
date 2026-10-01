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

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.lwjgl.PointerBuffer;
import org.teavm.jso.JSObject;

/**
 * WebGPU backend (increment 1) RenderPassBackend. Records into a live
 * GPURenderPassEncoder. Uniforms/textures are captured by name and assembled into
 * fresh bind groups at draw time following the manifest bind-group convention
 * (group(0)=UBOs, group(1)=texture+sampler, group(2)=storage). UBO slices bind
 * their exact offset (no std140 bind-size fixup — the WebGPU win over WebGL2).
 *
 * multiDraw / indirect throw a webgpu-todo UnsupportedOperationException (device
 * features report them false, so the public RenderPass wrappers reject them
 * first). drawMultipleIndexed is a per-draw loop (terrain path), like WebGL2.
 */
public class WebGPURenderPass implements RenderPassBackend {

	private final WebGPUCommandEncoder encoder;
	private final WebGPUDevice device;
	private final JSObject pass;
	private final boolean hasDepth;
	private final int colorAttachmentCount;

	private WebGPUPipeline pipeline;
	private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[16];
	private GpuBuffer indexBuffer;
	private IndexType indexType = IndexType.INT;
	private final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
	private final Map<String, TexSamp> samplers = new HashMap<>();

	private static final java.util.Set<String> loggedSkips = new java.util.HashSet<>();

	public WebGPURenderPass(final WebGPUCommandEncoder encoder, final WebGPUDevice device, final JSObject pass, final boolean hasDepth,
			final int colorAttachmentCount) {
		this.encoder = encoder;
		this.device = device;
		this.pass = pass;
		this.hasDepth = hasDepth;
		this.colorAttachmentCount = colorAttachmentCount;
	}

	@Override
	public void pushDebugGroup(final Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(final RenderPipeline pipeline) {
		this.pipeline = this.device.getOrCompilePipeline(pipeline);
		if (this.pipeline.isValid()) {
			WebGPU.passSetPipeline(this.pass, this.pipeline.handle());
		}
	}

	@Override
	public void bindTexture(final String name, final GpuTextureView textureView, final GpuSampler sampler) {
		if (sampler == null || textureView == null) {
			this.samplers.remove(name);
		} else {
			this.samplers.put(name, new TexSamp((WebGPUTextureView) textureView, (WebGPUSampler) sampler));
		}
	}

	@Override
	public void setUniform(final String name, final GpuBuffer value) {
		this.uniforms.put(name, value.slice());
	}

	@Override
	public void setUniform(final String name, final GpuBufferSlice value) {
		this.uniforms.put(name, value);
	}

	@Override
	public void enableScissor(final int x, final int y, final int width, final int height) {
		WebGPU.passSetScissorRect(this.pass, x, y, width, height);
	}

	@Override
	public void disableScissor() {
		// increment 1: WebGPU has no "reset scissor"; the render-area scissor set at
		// beginRenderPass remains until re-enabled. Full restore is increment 2.
	}

	@Override
	public void setVertexBuffer(final int slot, final GpuBufferSlice vertexBuffer) {
		this.vertexBuffers[slot] = vertexBuffer;
		if (vertexBuffer != null) {
			WebGPUBuffer b = (WebGPUBuffer) vertexBuffer.buffer();
			WebGPU.passSetVertexBuffer(this.pass, slot, b.handle(), (int) vertexBuffer.offset(), (int) vertexBuffer.length());
		}
	}

	@Override
	public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
		this.indexBuffer = indexBuffer;
		this.indexType = indexType;
		if (indexBuffer != null) {
			WebGPUBuffer b = (WebGPUBuffer) indexBuffer;
			WebGPU.passSetIndexBuffer(this.pass, b.handle(), WebGPUConst.indexFormat(indexType), 0, b.gpuSize());
		}
	}

	/** Assemble + set bind groups for the current pipeline. False => skip the draw. */
	private boolean setupBindGroups() {
		if (this.pipeline == null || !this.pipeline.isValid()) {
			return false;
		}
		WGSLShaderPack.Program prog = this.pipeline.program();
		JSObject dev = this.device.deviceHandle();

		// group 0: UBOs
		if (prog.uboNames.length > 0) {
			JSObject entries = WebGPU.newArr();
			for (int i = 0; i < prog.uboNames.length; i++) {
				GpuBufferSlice slice = this.uniforms.get(prog.uboNames[i]);
				if (slice == null) {
					return logSkip(prog.name, "missing UBO " + prog.uboNames[i]);
				}
				WebGPUBuffer buf = (WebGPUBuffer) slice.buffer();
				JSObject res = WebGPU.newObj();
				WebGPU.putObj(res, "buffer", buf.handle());
				WebGPU.putInt(res, "offset", (int) slice.offset());
				WebGPU.putInt(res, "size", (int) slice.length());
				JSObject e = WebGPU.newObj();
				WebGPU.putInt(e, "binding", prog.uboBindings[i]);
				WebGPU.putObj(e, "resource", res);
				WebGPU.push(entries, e);
			}
			JSObject desc = WebGPU.newObj();
			WebGPU.putObj(desc, "layout", this.pipeline.groupLayout(0));
			WebGPU.putObj(desc, "entries", entries);
			WebGPU.passSetBindGroup(this.pass, 0, WebGPU.deviceCreateBindGroup(dev, desc));
		}

		// group 1: textures + samplers
		if (prog.texNames.length > 0) {
			JSObject entries = WebGPU.newArr();
			for (int i = 0; i < prog.texNames.length; i++) {
				TexSamp ts = this.samplers.get(prog.texNames[i]);
				if (ts == null) {
					return logSkip(prog.name, "missing texture " + prog.texNames[i]);
				}
				JSObject te = WebGPU.newObj();
				WebGPU.putInt(te, "binding", prog.texTex[i]);
				WebGPU.putObj(te, "resource", ts.view.viewHandle());
				WebGPU.push(entries, te);
				JSObject se = WebGPU.newObj();
				WebGPU.putInt(se, "binding", prog.texSmp[i]);
				WebGPU.putObj(se, "resource", ts.sampler.handle());
				WebGPU.push(entries, se);
			}
			JSObject desc = WebGPU.newObj();
			WebGPU.putObj(desc, "layout", this.pipeline.groupLayout(1));
			WebGPU.putObj(desc, "entries", entries);
			WebGPU.passSetBindGroup(this.pass, 1, WebGPU.deviceCreateBindGroup(dev, desc));
		}

		// group 2: storage buffers (clouds SSBO) — not wired in increment 1.
		if (prog.storNames.length > 0) {
			return logSkip(prog.name, "group(2) storage buffers are increment 2 (clouds SSBO)");
		}
		return true;
	}

	private static boolean logSkip(final String prog, final String why) {
		if (loggedSkips.add(prog + "|" + why)) {
			System.err.println("webgpu-todo: skipping draw for program '" + prog + "': " + why);
		}
		return false;
	}

	@Override
	public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
		if (!this.setupBindGroups()) {
			return;
		}
		this.encoder.countDraw();
		WebGPU.passDrawIndexed(this.pass, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		if (!this.setupBindGroups()) {
			return;
		}
		this.encoder.countDraw();
		WebGPU.passDraw(this.pass, vertexCount, instanceCount, firstVertex, firstInstance);
	}

	@Override
	public <T> void drawMultipleIndexed(final Collection<RenderPass.Draw<T>> draws, final GpuBuffer defaultIndexBuffer,
			IndexType defaultIndexType, final Collection<String> dynamicUniforms, final T uniformArgument) {
		if (this.pipeline == null || !this.pipeline.isValid()) {
			return;
		}
		if (defaultIndexType == null) {
			defaultIndexType = IndexType.SHORT;
		}
		for (RenderPass.Draw<T> draw : draws) {
			IndexType it = draw.indexType() == null ? defaultIndexType : draw.indexType();
			this.setIndexBuffer(draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(), it);
			this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
			BiConsumer<T, RenderPass.UniformUploader> uploader = draw.uniformUploaderConsumer();
			if (uploader != null) {
				uploader.accept(uniformArgument, (name, buffer) -> this.uniforms.put(name, buffer));
			}
			if (!this.setupBindGroups()) {
				continue;
			}
			this.encoder.countDraw();
			WebGPU.passDrawIndexed(this.pass, draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
		}
	}

	// ==== unsupported GPU multi-draw / indirect (features report false) ====

	@Override
	public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		throw new UnsupportedOperationException("webgpu-todo: multiDrawIndexed (increment 2)");
	}

	@Override
	public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
		throw new UnsupportedOperationException("webgpu-todo: multiDrawIndexed (separate) (increment 2)");
	}

	@Override
	public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
		throw new UnsupportedOperationException("webgpu-todo: drawIndexedIndirect (increment 2)");
	}

	@Override
	public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		throw new UnsupportedOperationException("webgpu-todo: multiDraw (increment 2)");
	}

	@Override
	public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
		throw new UnsupportedOperationException("webgpu-todo: multiDraw (separate) (increment 2)");
	}

	@Override
	public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
		throw new UnsupportedOperationException("webgpu-todo: drawIndirect (increment 2)");
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		((WebGPUQueryPool) pool).writeTimestamp(index);
	}

	private record TexSamp(WebGPUTextureView view, WebGPUSampler sampler) {
	}
}

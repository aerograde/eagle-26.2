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

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.joml.Vector4fc;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Uint8Array;

/**
 * WebGPU backend (increment 1) CommandEncoderBackend. WebGPU encoders are
 * one-shot (create -> record -> finish -> submit), so this keeps ONE lazily
 * (re)created GPUCommandEncoder per flush window:
 *  - queue-level ops (writeToBuffer/writeToTexture) run immediately via the queue
 *    (WebGPU orders queue.writeBuffer before any later-submitted command buffer);
 *  - encoder-level ops (copies, clears, render passes) record into the current
 *    encoder and flush on submit()/createFence();
 *  - clears are done as tiny render passes with clear load-ops (WebGPU has no
 *    stand-alone clear outside a render pass).
 */
public class WebGPUCommandEncoder implements CommandEncoderBackend, AutoCloseable {

	static final int MAX_SUBMITS_IN_FLIGHT = 2;

	private final WebGPUDevice device;
	private WebGPUTransientMemory transientMemory;

	private JSObject currentEncoder; // GPUCommandEncoder or null
	private JSObject currentRenderPass; // GPURenderPassEncoder or null
	private int pendingSubmits;
	private long drawCallsTotal;

	public WebGPUCommandEncoder(final WebGPUDevice device) {
		this.device = device;
	}

	WebGPUDevice device() {
		return this.device;
	}

	public long getDrawCallsTotal() {
		return this.drawCallsTotal;
	}

	void countDraw() {
		this.drawCallsTotal++;
	}

	JSObject ensureEncoder() {
		if (this.currentEncoder == null) {
			this.currentEncoder = WebGPU.deviceCreateCommandEncoder(this.device.deviceHandle());
		}
		return this.currentEncoder;
	}

	/** Finish + submit the current encoder if it has recorded work. */
	void flush() {
		if (this.currentRenderPass != null) {
			throw new IllegalStateException("Cannot flush while a render pass is open");
		}
		if (this.currentEncoder != null) {
			JSObject cmd = WebGPU.encoderFinish(this.currentEncoder);
			WebGPU.queueSubmit(this.device.queueHandle(), cmd);
			this.currentEncoder = null;
			this.pendingSubmits++;
			trackSubmitDone(WebGPU.queueOnSubmittedWorkDone(this.device.queueHandle()), (final JSObject value) -> {
				if (this.pendingSubmits > 0) {
					this.pendingSubmits--;
				}
			});
		}
	}

	@org.teavm.jso.JSBody(params = { "p", "cb" }, script = "try { p.then(function(){ cb(null); }); } catch(e) {}")
	private static native void trackSubmitDone(JSObject p, WebGPU.PromiseFn cb);

	@Override
	public void submit() {
		this.flush();
		if (this.transientMemory != null) {
			this.transientMemory.rotate();
		}
	}

	public boolean isGpuBacklogged() {
		return this.pendingSubmits > MAX_SUBMITS_IN_FLIGHT;
	}

	@Override
	public TransientMemory transientMemory() {
		if (this.transientMemory == null) {
			this.transientMemory = new WebGPUTransientMemory(this.device);
		}
		return this.transientMemory;
	}

	// ==== render passes ====

	@Override
	public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
		JSObject enc = this.ensureEncoder();

		JSObject colorAttachments = WebGPU.newArr();
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		int colorCount = 0;
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> a : colors) {
			if (a == null) {
				pushNull(colorAttachments);
				continue;
			}
			colorCount++;
			WebGPUTextureView view = (WebGPUTextureView) a.textureView();
			JSObject ca = WebGPU.newObj();
			WebGPU.putObj(ca, "view", view.viewHandle());
			if (a.clearValue().isPresent()) {
				Vector4fc cv = a.clearValue().get();
				JSObject clr = WebGPU.newObj();
				WebGPU.putNum(clr, "r", cv.x());
				WebGPU.putNum(clr, "g", cv.y());
				WebGPU.putNum(clr, "b", cv.z());
				WebGPU.putNum(clr, "a", cv.w());
				WebGPU.putObj(ca, "clearValue", clr);
				WebGPU.putStr(ca, "loadOp", "clear");
			} else {
				WebGPU.putStr(ca, "loadOp", "load");
			}
			WebGPU.putStr(ca, "storeOp", "store");
			WebGPU.push(colorAttachments, ca);
		}

		JSObject passDesc = WebGPU.newObj();
		WebGPU.putObj(passDesc, "colorAttachments", colorAttachments);

		RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		boolean hasDepth = depth != null;
		if (hasDepth) {
			WebGPUTextureView dview = (WebGPUTextureView) depth.textureView();
			JSObject da = WebGPU.newObj();
			WebGPU.putObj(da, "view", dview.viewHandle());
			if (depth.clearValue().isPresent()) {
				WebGPU.putNum(da, "depthClearValue", depth.clearValue().getAsDouble());
				WebGPU.putStr(da, "depthLoadOp", "clear");
			} else {
				WebGPU.putStr(da, "depthLoadOp", "load");
			}
			WebGPU.putStr(da, "depthStoreOp", "store");
			WebGPU.putObj(passDesc, "depthStencilAttachment", da);
		}

		JSObject pass = WebGPU.encoderBeginRenderPass(enc, passDesc);
		this.currentRenderPass = pass;
		if (descriptor.renderArea != null) {
			WebGPU.passSetScissorRect(pass, descriptor.renderArea.x(), descriptor.renderArea.y(),
					descriptor.renderArea.width(), descriptor.renderArea.height());
		}
		return new WebGPURenderPass(this, this.device, pass, hasDepth, colorCount);
	}

	@org.teavm.jso.JSBody(params = { "arr" }, script = "arr.push(null);")
	private static native void pushNull(JSObject arr);

	@Override
	public void submitRenderPass() {
		if (this.currentRenderPass != null) {
			WebGPU.passEnd(this.currentRenderPass);
			this.currentRenderPass = null;
		}
	}

	// ==== clears (as tiny clear-load render passes) ====

	@Override
	public void clearColorTexture(final GpuTexture colorTexture, final Vector4fc clearColor) {
		JSObject enc = this.ensureEncoder();
		WebGPUTexture tex = (WebGPUTexture) colorTexture;
		JSObject view = defaultView(tex);
		JSObject ca = WebGPU.newObj();
		WebGPU.putObj(ca, "view", view);
		JSObject clr = WebGPU.newObj();
		WebGPU.putNum(clr, "r", clearColor.x());
		WebGPU.putNum(clr, "g", clearColor.y());
		WebGPU.putNum(clr, "b", clearColor.z());
		WebGPU.putNum(clr, "a", clearColor.w());
		WebGPU.putObj(ca, "clearValue", clr);
		WebGPU.putStr(ca, "loadOp", "clear");
		WebGPU.putStr(ca, "storeOp", "store");
		JSObject arr = WebGPU.newArr();
		WebGPU.push(arr, ca);
		JSObject desc = WebGPU.newObj();
		WebGPU.putObj(desc, "colorAttachments", arr);
		JSObject pass = WebGPU.encoderBeginRenderPass(enc, desc);
		WebGPU.passEnd(pass);
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture,
			final double clearDepth) {
		this.clearColorAndDepth(colorTexture, clearColor, depthTexture, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture,
			final double clearDepth, final int regionX, final int regionY, final int regionWidth, final int regionHeight) {
		// increment 1: region ignored (full-attachment clear); scissored clear is increment 2.
		this.clearColorAndDepth(colorTexture, clearColor, depthTexture, clearDepth);
	}

	private void clearColorAndDepth(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture,
			final double clearDepth) {
		JSObject enc = this.ensureEncoder();
		JSObject ca = WebGPU.newObj();
		WebGPU.putObj(ca, "view", defaultView((WebGPUTexture) colorTexture));
		JSObject clr = WebGPU.newObj();
		WebGPU.putNum(clr, "r", clearColor.x());
		WebGPU.putNum(clr, "g", clearColor.y());
		WebGPU.putNum(clr, "b", clearColor.z());
		WebGPU.putNum(clr, "a", clearColor.w());
		WebGPU.putObj(ca, "clearValue", clr);
		WebGPU.putStr(ca, "loadOp", "clear");
		WebGPU.putStr(ca, "storeOp", "store");
		JSObject arr = WebGPU.newArr();
		WebGPU.push(arr, ca);
		JSObject desc = WebGPU.newObj();
		WebGPU.putObj(desc, "colorAttachments", arr);
		JSObject da = WebGPU.newObj();
		WebGPU.putObj(da, "view", defaultView((WebGPUTexture) depthTexture));
		WebGPU.putNum(da, "depthClearValue", clearDepth);
		WebGPU.putStr(da, "depthLoadOp", "clear");
		WebGPU.putStr(da, "depthStoreOp", "store");
		WebGPU.putObj(desc, "depthStencilAttachment", da);
		JSObject pass = WebGPU.encoderBeginRenderPass(enc, desc);
		WebGPU.passEnd(pass);
	}

	@Override
	public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
		JSObject enc = this.ensureEncoder();
		JSObject desc = WebGPU.newObj();
		WebGPU.putObj(desc, "colorAttachments", WebGPU.newArr());
		JSObject da = WebGPU.newObj();
		WebGPU.putObj(da, "view", defaultView((WebGPUTexture) depthTexture));
		WebGPU.putNum(da, "depthClearValue", clearDepth);
		WebGPU.putStr(da, "depthLoadOp", "clear");
		WebGPU.putStr(da, "depthStoreOp", "store");
		WebGPU.putObj(desc, "depthStencilAttachment", da);
		JSObject pass = WebGPU.encoderBeginRenderPass(enc, desc);
		WebGPU.passEnd(pass);
	}

	private static JSObject defaultView(final WebGPUTexture tex) {
		return WebGPU.textureCreateView(tex.handle(), null);
	}

	// ==== buffer / texture transfers ====

	@Override
	public void writeToBuffer(final GpuBufferSlice slice, final ByteBuffer data) {
		WebGPUBuffer buffer = (WebGPUBuffer) slice.buffer();
		buffer.queueWrite((int) slice.offset(), data);
	}

	@Override
	public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
		WebGPUBuffer src = (WebGPUBuffer) source.buffer();
		WebGPUBuffer dst = (WebGPUBuffer) target.buffer();
		src.checkCanBeUsed();
		dst.checkCanBeUsed();
		JSObject enc = this.ensureEncoder();
		WebGPU.encoderCopyBufferToBuffer(enc, src.handle(), (int) source.offset(), dst.handle(), (int) target.offset(), (int) source.length());
	}

	@Override
	public void writeToTexture(final GpuTexture destination, final ByteBuffer source, final int mipLevel, final int depthOrLayer, final int destX,
			final int destY, final int width, final int height) {
		WebGPUTexture tex = (WebGPUTexture) destination;
		int blockSize = destination.getFormat().blockSize();

		JSObject dest = WebGPU.newObj();
		WebGPU.putObj(dest, "texture", tex.handle());
		WebGPU.putInt(dest, "mipLevel", mipLevel);
		JSObject origin = WebGPU.newObj();
		WebGPU.putInt(origin, "x", destX);
		WebGPU.putInt(origin, "y", destY);
		WebGPU.putInt(origin, "z", depthOrLayer);
		WebGPU.putObj(dest, "origin", origin);

		JSObject layout = WebGPU.newObj();
		WebGPU.putInt(layout, "offset", 0);
		WebGPU.putInt(layout, "bytesPerRow", width * blockSize);
		WebGPU.putInt(layout, "rowsPerImage", height);

		JSObject size = WebGPU.newObj();
		WebGPU.putInt(size, "width", width);
		WebGPU.putInt(size, "height", height);
		WebGPU.putInt(size, "depthOrArrayLayers", 1);

		ByteBuffer dup = source.duplicate();
		Int8Array i8 = Int8Array.fromJavaBuffer(dup);
		Uint8Array u8 = Uint8Array.create(i8.getBuffer(), i8.getByteOffset(), i8.getLength());
		WebGPU.queueWriteTexture(this.device.queueHandle(), dest, u8, layout, size);
	}

	@Override
	public void copyBufferToTexture(final GpuBufferSlice source, final int sourceX, final int sourceY, final int sourceWidth, final int sourceHeight,
			final GpuTexture destination, final int destinationX, final int destinationY, final int copyWidth, final int copyHeight, final int mipLevel,
			final int arrayLayer) {
		WebGPUTexture tex = (WebGPUTexture) destination;
		WebGPUBuffer srcBuf = (WebGPUBuffer) source.buffer();
		int blockSize = destination.getFormat().blockSize();
		long skipBytes = (sourceX + (long) sourceY * sourceWidth) * blockSize;

		JSObject src = WebGPU.newObj();
		WebGPU.putObj(src, "buffer", srcBuf.handle());
		WebGPU.putInt(src, "offset", (int) (source.offset() + skipBytes));
		WebGPU.putInt(src, "bytesPerRow", sourceWidth * blockSize);
		WebGPU.putInt(src, "rowsPerImage", sourceHeight);

		JSObject dest = WebGPU.newObj();
		WebGPU.putObj(dest, "texture", tex.handle());
		WebGPU.putInt(dest, "mipLevel", mipLevel);
		JSObject origin = WebGPU.newObj();
		WebGPU.putInt(origin, "x", destinationX);
		WebGPU.putInt(origin, "y", destinationY);
		WebGPU.putInt(origin, "z", arrayLayer);
		WebGPU.putObj(dest, "origin", origin);

		JSObject size = WebGPU.newObj();
		WebGPU.putInt(size, "width", copyWidth);
		WebGPU.putInt(size, "height", copyHeight);
		WebGPU.putInt(size, "depthOrArrayLayers", 1);

		JSObject enc = this.ensureEncoder();
		WebGPU.encoderCopyBufferToTexture(enc, src, dest, size);
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel,
			final int x, final int y, final int width, final int height) {
		WebGPUTexture tex = (WebGPUTexture) source;
		WebGPUBuffer dst = (WebGPUBuffer) destination;
		dst.checkCanBeUsed();
		int blockSize = source.getFormat().blockSize();

		JSObject src = WebGPU.newObj();
		WebGPU.putObj(src, "texture", tex.handle());
		WebGPU.putInt(src, "mipLevel", mipLevel);
		JSObject origin = WebGPU.newObj();
		WebGPU.putInt(origin, "x", x);
		WebGPU.putInt(origin, "y", y);
		WebGPU.putInt(origin, "z", 0);
		WebGPU.putObj(src, "origin", origin);

		JSObject dest = WebGPU.newObj();
		WebGPU.putObj(dest, "buffer", dst.handle());
		WebGPU.putInt(dest, "offset", (int) offset);
		WebGPU.putInt(dest, "bytesPerRow", width * blockSize);
		WebGPU.putInt(dest, "rowsPerImage", height);

		JSObject size = WebGPU.newObj();
		WebGPU.putInt(size, "width", width);
		WebGPU.putInt(size, "height", height);
		WebGPU.putInt(size, "depthOrArrayLayers", 1);

		JSObject enc = this.ensureEncoder();
		WebGPU.encoderCopyTextureToBuffer(enc, src, dest, size);
		RenderSystem.queueFencedTask(callback);
	}

	@Override
	public void copyTextureToTexture(final GpuTexture source, final GpuTexture destination, final int mipLevel, final int destX, final int destY,
			final int sourceX, final int sourceY, final int width, final int height) {
		WebGPUTexture src = (WebGPUTexture) source;
		WebGPUTexture dst = (WebGPUTexture) destination;

		JSObject s = WebGPU.newObj();
		WebGPU.putObj(s, "texture", src.handle());
		WebGPU.putInt(s, "mipLevel", mipLevel);
		JSObject so = WebGPU.newObj();
		WebGPU.putInt(so, "x", sourceX);
		WebGPU.putInt(so, "y", sourceY);
		WebGPU.putInt(so, "z", 0);
		WebGPU.putObj(s, "origin", so);

		JSObject d = WebGPU.newObj();
		WebGPU.putObj(d, "texture", dst.handle());
		WebGPU.putInt(d, "mipLevel", mipLevel);
		JSObject dorg = WebGPU.newObj();
		WebGPU.putInt(dorg, "x", destX);
		WebGPU.putInt(dorg, "y", destY);
		WebGPU.putInt(dorg, "z", 0);
		WebGPU.putObj(d, "origin", dorg);

		JSObject size = WebGPU.newObj();
		WebGPU.putInt(size, "width", width);
		WebGPU.putInt(size, "height", height);
		WebGPU.putInt(size, "depthOrArrayLayers", 1);

		JSObject enc = this.ensureEncoder();
		WebGPU.encoderCopyTextureToTexture(enc, s, d, size);
	}

	@Override
	public GpuFence createFence() {
		// flush recorded work so the fence tracks it, then fence on queue completion.
		this.flush();
		return new WebGPUFence(this.device);
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		((WebGPUQueryPool) pool).writeTimestamp(index);
	}

	// ==== present (fullscreen-triangle blit of the offscreen color onto the swapchain) ====

	private JSObject presentPipeline;
	private JSObject presentSampler;
	private JSObject presentBindGroupLayout;

	private void ensurePresentPipeline(final String canvasFormat) {
		if (this.presentPipeline != null) {
			return;
		}
		final String wgsl = "@group(0) @binding(0) var srcTex: texture_2d<f32>;\n"
				+ "@group(0) @binding(1) var srcSmp: sampler;\n"
				+ "struct VOut { @builtin(position) pos: vec4<f32>, @location(0) uv: vec2<f32>, };\n"
				+ "@vertex fn vs(@builtin(vertex_index) vid: u32) -> VOut {\n"
				+ "  var p = vec2<f32>(f32((vid << 1u) & 2u), f32(vid & 2u));\n"
				+ "  var o: VOut; o.uv = vec2<f32>(p.x, 1.0 - p.y);\n"
				+ "  o.pos = vec4<f32>(p * 2.0 - 1.0, 0.0, 1.0); return o;\n"
				+ "}\n"
				+ "@fragment fn fs(i: VOut) -> @location(0) vec4<f32> {\n"
				+ "  return textureSample(srcTex, srcSmp, i.uv);\n"
				+ "}\n";
		JSObject module = WebGPU.deviceCreateShaderModule(this.device.deviceHandle(), wgsl, "present-blit");

		JSObject entries = WebGPU.newArr();
		JSObject te = WebGPU.newObj();
		WebGPU.putInt(te, "binding", 0);
		WebGPU.putInt(te, "visibility", 0x2);
		JSObject tex = WebGPU.newObj();
		WebGPU.putStr(tex, "sampleType", "float");
		WebGPU.putStr(tex, "viewDimension", "2d");
		WebGPU.putObj(te, "texture", tex);
		WebGPU.push(entries, te);
		JSObject se = WebGPU.newObj();
		WebGPU.putInt(se, "binding", 1);
		WebGPU.putInt(se, "visibility", 0x2);
		JSObject smp = WebGPU.newObj();
		WebGPU.putStr(smp, "type", "filtering");
		WebGPU.putObj(se, "sampler", smp);
		WebGPU.push(entries, se);
		JSObject bglDesc = WebGPU.newObj();
		WebGPU.putObj(bglDesc, "entries", entries);
		this.presentBindGroupLayout = WebGPU.deviceCreateBindGroupLayout(this.device.deviceHandle(), bglDesc);

		JSObject bgls = WebGPU.newArr();
		WebGPU.push(bgls, this.presentBindGroupLayout);
		JSObject plDesc = WebGPU.newObj();
		WebGPU.putObj(plDesc, "bindGroupLayouts", bgls);
		JSObject pipelineLayout = WebGPU.deviceCreatePipelineLayout(this.device.deviceHandle(), plDesc);

		JSObject vertex = WebGPU.newObj();
		WebGPU.putObj(vertex, "module", module);
		WebGPU.putStr(vertex, "entryPoint", "vs");
		WebGPU.putObj(vertex, "buffers", WebGPU.newArr());

		JSObject target = WebGPU.newObj();
		WebGPU.putStr(target, "format", canvasFormat);
		JSObject targets = WebGPU.newArr();
		WebGPU.push(targets, target);
		JSObject fragment = WebGPU.newObj();
		WebGPU.putObj(fragment, "module", module);
		WebGPU.putStr(fragment, "entryPoint", "fs");
		WebGPU.putObj(fragment, "targets", targets);

		JSObject primitive = WebGPU.newObj();
		WebGPU.putStr(primitive, "topology", "triangle-list");

		JSObject desc = WebGPU.newObj();
		WebGPU.putObj(desc, "layout", pipelineLayout);
		WebGPU.putObj(desc, "vertex", vertex);
		WebGPU.putObj(desc, "fragment", fragment);
		WebGPU.putObj(desc, "primitive", primitive);
		this.presentPipeline = WebGPU.deviceCreateRenderPipeline(this.device.deviceHandle(), desc);

		JSObject sampDesc = WebGPU.newObj();
		WebGPU.putStr(sampDesc, "minFilter", "nearest");
		WebGPU.putStr(sampDesc, "magFilter", "nearest");
		WebGPU.putStr(sampDesc, "mipmapFilter", "nearest");
		this.presentSampler = WebGPU.deviceCreateSampler(this.device.deviceHandle(), sampDesc);
	}

	/** Blit the offscreen color texture view onto the swapchain's current texture view. */
	public void presentTexture(final GpuTextureView srcView, final JSObject swapchainView, final String canvasFormat) {
		this.ensurePresentPipeline(canvasFormat);
		if (this.presentPipeline == null) {
			return;
		}
		JSObject enc = this.ensureEncoder();

		JSObject bgEntries = WebGPU.newArr();
		JSObject t = WebGPU.newObj();
		WebGPU.putInt(t, "binding", 0);
		WebGPU.putObj(t, "resource", ((WebGPUTextureView) srcView).viewHandle());
		WebGPU.push(bgEntries, t);
		JSObject s = WebGPU.newObj();
		WebGPU.putInt(s, "binding", 1);
		WebGPU.putObj(s, "resource", this.presentSampler);
		WebGPU.push(bgEntries, s);
		JSObject bgDesc = WebGPU.newObj();
		WebGPU.putObj(bgDesc, "layout", this.presentBindGroupLayout);
		WebGPU.putObj(bgDesc, "entries", bgEntries);
		JSObject bindGroup = WebGPU.deviceCreateBindGroup(this.device.deviceHandle(), bgDesc);

		JSObject ca = WebGPU.newObj();
		WebGPU.putObj(ca, "view", swapchainView);
		WebGPU.putStr(ca, "loadOp", "clear");
		JSObject clr = WebGPU.newObj();
		WebGPU.putNum(clr, "r", 0.0);
		WebGPU.putNum(clr, "g", 0.0);
		WebGPU.putNum(clr, "b", 0.0);
		WebGPU.putNum(clr, "a", 1.0);
		WebGPU.putObj(ca, "clearValue", clr);
		WebGPU.putStr(ca, "storeOp", "store");
		JSObject arr = WebGPU.newArr();
		WebGPU.push(arr, ca);
		JSObject passDesc = WebGPU.newObj();
		WebGPU.putObj(passDesc, "colorAttachments", arr);

		JSObject pass = WebGPU.encoderBeginRenderPass(enc, passDesc);
		WebGPU.passSetPipeline(pass, this.presentPipeline);
		WebGPU.passSetBindGroup(pass, 0, bindGroup);
		WebGPU.passDraw(pass, 3, 1, 0, 0);
		WebGPU.passEnd(pass);
	}

	@Override
	public void close() {
		if (this.transientMemory != null) {
			this.transientMemory.close();
		}
	}
}

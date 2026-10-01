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

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Uint8Array;

/**
 * WebGPU backend (increment 1): the raw JS interop facade. TeaVM 0.13's jso-apis
 * ship no WebGPU (GPUDevice/GPUBuffer/...) typings, so every handle is an opaque
 * {@link JSObject} and every call is a one-line {@link JSBody}. Descriptors are
 * assembled with the generic JS object/array builder ({@link #newObj()} etc.)
 * rather than modeled as Java types — this keeps the Java surface tiny and
 * compile-stable while WebGPU's descriptor shapes evolve.
 *
 * Async is bridged to the TeaVM green thread with the same {@code @Async} +
 * {@link AsyncCallback} pattern the IndexedDB filesystem uses: {@link #await}
 * suspends the green thread on a JS Promise and resumes with its resolved value
 * (or null on reject). Only adapter/device acquisition and mapAsync readback are
 * async; pipeline/buffer/texture creation and pass recording are synchronous.
 */
public final class WebGPU {

	private WebGPU() {
	}

	// ==== feature detection ====

	@JSBody(params = {}, script = "return !!(typeof navigator !== 'undefined' && navigator.gpu);")
	public static native boolean isSupported();

	// ==== async bridge (green-thread suspend on a JS Promise) ====

	@JSFunctor
	public interface PromiseFn extends JSObject {
		void call(JSObject value);
	}

	@JSBody(params = { "promise", "res", "rej" }, script = "try { promise.then(function(v){res(v);}, function(e){rej(e);}); }"
			+ " catch(err) { rej(err); }")
	private static native void promiseThen(JSObject promise, PromiseFn res, PromiseFn rej);

	/** Suspend the green thread until {@code promise} settles; returns the resolved
	 *  value, or null if it rejected (see {@link #lastError}). */
	@Async
	public static native JSObject await(JSObject promise);

	private static void await(final JSObject promise, final AsyncCallback<JSObject> cb) {
		if (promise == null) {
			cb.complete(null);
			return;
		}
		promiseThen(promise, (final JSObject value) -> cb.complete(value), (final JSObject error) -> {
			lastError = stringify(error);
			cb.complete(null);
		});
	}

	/** Last Promise rejection message (best-effort), for diagnostics. */
	public static volatile String lastError;

	@JSBody(params = { "e" }, script = "try { return '' + (e && (e.message || e)) ; } catch(x) { return 'unknown'; }")
	public static native String stringify(JSObject e);

	// ==== adapter / device ====

	@JSBody(params = {}, script = "return navigator.gpu.requestAdapter({ powerPreference: 'high-performance' });")
	public static native JSObject requestAdapterPromise();

	@JSBody(params = { "adapter" }, script = "return adapter.requestDevice();")
	public static native JSObject requestDevicePromise(JSObject adapter);

	@JSBody(params = { "device" }, script = "return device.queue;")
	public static native JSObject deviceQueue(JSObject device);

	@JSBody(params = { "adapter" }, script = "return (adapter && adapter.info && adapter.info.vendor) ? adapter.info.vendor : 'unknown';")
	public static native String adapterVendor(JSObject adapter);

	@JSBody(params = { "adapter" }, script = "return (adapter && adapter.info && adapter.info.architecture) ? adapter.info.architecture : 'webgpu';")
	public static native String adapterArchitecture(JSObject adapter);

	@JSBody(params = { "device" }, script = "return (device && device.limits && device.limits.maxTextureDimension2D) ? device.limits.maxTextureDimension2D : 8192;")
	public static native int deviceMaxTextureDimension2D(JSObject device);

	@JSBody(params = { "device" }, script = "return (device && device.limits && device.limits.minUniformBufferOffsetAlignment) ? device.limits.minUniformBufferOffsetAlignment : 256;")
	public static native int deviceMinUniformBufferOffsetAlignment(JSObject device);

	@JSBody(params = { "device" }, script = "return (device && device.limits && device.limits.maxColorAttachments) ? device.limits.maxColorAttachments : 8;")
	public static native int deviceMaxColorAttachments(JSObject device);

	// ==== generic JS object / array descriptor builder ====

	@JSBody(params = {}, script = "return {};")
	public static native JSObject newObj();

	@JSBody(params = {}, script = "return [];")
	public static native JSObject newArr();

	@JSBody(params = { "o", "k", "v" }, script = "o[k] = v; return o;")
	public static native JSObject putObj(JSObject o, String k, JSObject v);

	@JSBody(params = { "o", "k", "v" }, script = "o[k] = v; return o;")
	public static native JSObject putStr(JSObject o, String k, String v);

	@JSBody(params = { "o", "k", "v" }, script = "o[k] = v; return o;")
	public static native JSObject putInt(JSObject o, String k, int v);

	@JSBody(params = { "o", "k", "v" }, script = "o[k] = v; return o;")
	public static native JSObject putNum(JSObject o, String k, double v);

	@JSBody(params = { "o", "k", "v" }, script = "o[k] = !!v; return o;")
	public static native JSObject putBool(JSObject o, String k, boolean v);

	@JSBody(params = { "arr", "v" }, script = "arr.push(v); return arr;")
	public static native JSObject push(JSObject arr, JSObject v);

	// ==== buffers ====

	/** GPUBufferUsage bitmask create. */
	@JSBody(params = { "device", "size", "usage", "mapped" }, script = "return device.createBuffer({ size: size, usage: usage, mappedAtCreation: !!mapped });")
	public static native JSObject deviceCreateBuffer(JSObject device, int size, int usage, boolean mapped);

	/** Copy a Java byte view into a mappedAtCreation buffer's mapped range and unmap. */
	@JSBody(params = { "buffer", "data", "size" }, script = "var dst = new Uint8Array(buffer.getMappedRange(0, size));"
			+ " dst.set(data.subarray(0, size)); buffer.unmap();")
	public static native void bufferWriteMappedAndUnmap(JSObject buffer, Uint8Array data, int size);

	@JSBody(params = { "queue", "buffer", "offset", "data", "dataOffset", "size" }, script = "queue.writeBuffer(buffer, offset, data, dataOffset, size);")
	public static native void queueWriteBuffer(JSObject queue, JSObject buffer, int offset, Int8Array data, int dataOffset, int size);

	@JSBody(params = { "buffer" }, script = "buffer.destroy();")
	public static native void bufferDestroy(JSObject buffer);

	/** buffer.mapAsync(mode, offset, size) -> Promise. mode: READ=1, WRITE=2. */
	@JSBody(params = { "buffer", "mode", "offset", "size" }, script = "return buffer.mapAsync(mode, offset, size);")
	public static native JSObject bufferMapAsync(JSObject buffer, int mode, int offset, int size);

	@JSBody(params = { "buffer", "offset", "size" }, script = "return buffer.getMappedRange(offset, size).slice(0);")
	public static native ArrayBuffer bufferGetMappedRangeCopy(JSObject buffer, int offset, int size);

	@JSBody(params = { "buffer" }, script = "buffer.unmap();")
	public static native void bufferUnmap(JSObject buffer);

	// ==== textures / views / samplers ====

	@JSBody(params = { "device", "desc" }, script = "return device.createTexture(desc);")
	public static native JSObject deviceCreateTexture(JSObject device, JSObject desc);

	@JSBody(params = { "texture", "desc" }, script = "return desc ? texture.createView(desc) : texture.createView();")
	public static native JSObject textureCreateView(JSObject texture, JSObject desc);

	@JSBody(params = { "texture" }, script = "texture.destroy();")
	public static native void textureDestroy(JSObject texture);

	@JSBody(params = { "device", "desc" }, script = "return device.createSampler(desc);")
	public static native JSObject deviceCreateSampler(JSObject device, JSObject desc);

	// ==== shader modules / pipelines / layouts / bind groups ====

	@JSBody(params = { "device", "code", "label" }, script = "return device.createShaderModule({ code: code, label: label });")
	public static native JSObject deviceCreateShaderModule(JSObject device, String code, String label);

	@JSBody(params = { "module" }, script = "return module.getCompilationInfo ? module.getCompilationInfo() : null;")
	public static native JSObject shaderModuleGetCompilationInfo(JSObject module);

	@JSBody(params = { "info" }, script = "if(!info||!info.messages){return '';} var s=''; for(var i=0;i<info.messages.length;i++){"
			+ " var m=info.messages[i]; if(m.type==='error'){ s+= (m.lineNum+':'+m.linePos+': '+m.message+'\\n'); } } return s;")
	public static native String compilationInfoErrors(JSObject info);

	@JSBody(params = { "device", "desc" }, script = "return device.createBindGroupLayout(desc);")
	public static native JSObject deviceCreateBindGroupLayout(JSObject device, JSObject desc);

	@JSBody(params = { "device", "desc" }, script = "return device.createPipelineLayout(desc);")
	public static native JSObject deviceCreatePipelineLayout(JSObject device, JSObject desc);

	@JSBody(params = { "device", "desc" }, script = "return device.createBindGroup(desc);")
	public static native JSObject deviceCreateBindGroup(JSObject device, JSObject desc);

	/** Synchronous pipeline create. Returns null on failure (device error scope). */
	@JSBody(params = { "device", "desc" }, script = "try { return device.createRenderPipeline(desc); } catch(e) {"
			+ " if(typeof console!=='undefined'){ console.error('webgpu createRenderPipeline: '+e); } return null; }")
	public static native JSObject deviceCreateRenderPipeline(JSObject device, JSObject desc);

	// ==== command encoder / render pass ====

	@JSBody(params = { "device" }, script = "return device.createCommandEncoder();")
	public static native JSObject deviceCreateCommandEncoder(JSObject device);

	@JSBody(params = { "encoder", "desc" }, script = "return encoder.beginRenderPass(desc);")
	public static native JSObject encoderBeginRenderPass(JSObject encoder, JSObject desc);

	@JSBody(params = { "pass", "pipeline" }, script = "pass.setPipeline(pipeline);")
	public static native void passSetPipeline(JSObject pass, JSObject pipeline);

	@JSBody(params = { "pass", "index", "group" }, script = "pass.setBindGroup(index, group);")
	public static native void passSetBindGroup(JSObject pass, int index, JSObject group);

	@JSBody(params = { "pass", "slot", "buffer", "offset", "size" }, script = "pass.setVertexBuffer(slot, buffer, offset, size);")
	public static native void passSetVertexBuffer(JSObject pass, int slot, JSObject buffer, int offset, int size);

	@JSBody(params = { "pass", "buffer", "format", "offset", "size" }, script = "pass.setIndexBuffer(buffer, format, offset, size);")
	public static native void passSetIndexBuffer(JSObject pass, JSObject buffer, String format, int offset, int size);

	@JSBody(params = { "pass", "x", "y", "w", "h" }, script = "pass.setScissorRect(x, y, w, h);")
	public static native void passSetScissorRect(JSObject pass, int x, int y, int w, int h);

	@JSBody(params = { "pass", "vertexCount", "instanceCount", "firstVertex", "firstInstance" }, script = "pass.draw(vertexCount, instanceCount, firstVertex, firstInstance);")
	public static native void passDraw(JSObject pass, int vertexCount, int instanceCount, int firstVertex, int firstInstance);

	@JSBody(params = { "pass", "indexCount", "instanceCount", "firstIndex", "baseVertex", "firstInstance" }, script = "pass.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);")
	public static native void passDrawIndexed(JSObject pass, int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance);

	@JSBody(params = { "pass" }, script = "pass.end();")
	public static native void passEnd(JSObject pass);

	@JSBody(params = { "encoder" }, script = "return encoder.finish();")
	public static native JSObject encoderFinish(JSObject encoder);

	@JSBody(params = { "queue", "commandBuffer" }, script = "queue.submit([commandBuffer]);")
	public static native void queueSubmit(JSObject queue, JSObject commandBuffer);

	@JSBody(params = { "queue" }, script = "return queue.onSubmittedWorkDone();")
	public static native JSObject queueOnSubmittedWorkDone(JSObject queue);

	// ==== copies (encoder) ====

	@JSBody(params = { "encoder", "src", "srcOff", "dst", "dstOff", "size" }, script = "encoder.copyBufferToBuffer(src, srcOff, dst, dstOff, size);")
	public static native void encoderCopyBufferToBuffer(JSObject encoder, JSObject src, int srcOff, JSObject dst, int dstOff, int size);

	@JSBody(params = { "encoder", "srcDesc", "dstDesc", "sizeDesc" }, script = "encoder.copyTextureToBuffer(srcDesc, dstDesc, sizeDesc);")
	public static native void encoderCopyTextureToBuffer(JSObject encoder, JSObject srcDesc, JSObject dstDesc, JSObject sizeDesc);

	@JSBody(params = { "encoder", "srcDesc", "dstDesc", "sizeDesc" }, script = "encoder.copyBufferToTexture(srcDesc, dstDesc, sizeDesc);")
	public static native void encoderCopyBufferToTexture(JSObject encoder, JSObject srcDesc, JSObject dstDesc, JSObject sizeDesc);

	@JSBody(params = { "encoder", "srcDesc", "dstDesc", "sizeDesc" }, script = "encoder.copyTextureToTexture(srcDesc, dstDesc, sizeDesc);")
	public static native void encoderCopyTextureToTexture(JSObject encoder, JSObject srcDesc, JSObject dstDesc, JSObject sizeDesc);

	@JSBody(params = { "queue", "texDesc", "data", "layoutDesc", "sizeDesc" }, script = "queue.writeTexture(texDesc, data, layoutDesc, sizeDesc);")
	public static native void queueWriteTexture(JSObject queue, JSObject texDesc, Uint8Array data, JSObject layoutDesc, JSObject sizeDesc);

	// ==== canvas surface ====

	@JSBody(params = { "canvas" }, script = "try { return canvas.getContext('webgpu'); } catch(e) { return null; }")
	public static native JSObject canvasGetWebGpuContext(HTMLCanvasElement canvas);

	@JSBody(params = {}, script = "return navigator.gpu.getPreferredCanvasFormat();")
	public static native String getPreferredCanvasFormat();

	@JSBody(params = { "ctx", "device", "format", "alphaMode" }, script = "ctx.configure({ device: device, format: format, alphaMode: alphaMode,"
			+ " usage: (GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.COPY_DST) });")
	public static native void contextConfigure(JSObject ctx, JSObject device, String format, String alphaMode);

	@JSBody(params = { "ctx" }, script = "return ctx.getCurrentTexture();")
	public static native JSObject contextGetCurrentTexture(JSObject ctx);

	@JSBody(params = { "ctx" }, script = "try { ctx.unconfigure(); } catch(e) {}")
	public static native void contextUnconfigure(JSObject ctx);

	// ==== error scopes (best-effort pipeline validation capture) ====

	@JSBody(params = { "device", "type" }, script = "device.pushErrorScope(type);")
	public static native void devicePushErrorScope(JSObject device, String type);

	@JSBody(params = { "device" }, script = "return device.popErrorScope();")
	public static native JSObject devicePopErrorScope(JSObject device);
}

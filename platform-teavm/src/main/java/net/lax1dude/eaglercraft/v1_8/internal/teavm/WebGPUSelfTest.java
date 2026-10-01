/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import com.mojang.blaze3d.buffers.GpuBuffer;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.webgpu.WGSLShaderPack;
import net.lax1dude.eaglercraft.v1_8.internal.webgpu.WebGPU;
import net.lax1dude.eaglercraft.v1_8.internal.webgpu.WebGPUBuffer;
import net.lax1dude.eaglercraft.v1_8.internal.webgpu.WebGPUConst;
import net.lax1dude.eaglercraft.v1_8.internal.webgpu.WebGPUDevice;
import net.lax1dude.eaglercraft.v1_8.internal.webgpu.WebGPUTexture;
import net.lax1dude.eaglercraft.v1_8.internal.webgpu.WebGPUTextureView;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Uint8Array;

/**
 * WebGPU backend (increment 1) headless self-test. Opt-in only
 * (eaglercraftXOpts.webgpuSelfTest === true OR ?webgputest); ClientMain guards the
 * call so it is dead code otherwise (mirrors WebGL2SelfTest / MeshWorkerSelfTest).
 *
 * Proves the WebGPU core plumbing end to end WITHOUT the full RenderPipeline SPI:
 * request adapter/device, compile the manifest 'position_color' WGSL from
 * {@link WGSLShaderPack}, build a tiny pipeline (2 UBOs + a POSITION+COLOR vertex
 * layout), draw one red triangle into a 64x64 offscreen RGBA8 texture, copy it to
 * a mappable buffer, map it, and read back the centre pixel — expecting 255,0,0.
 */
public class WebGPUSelfTest {

	@JSBody(params = { "msg" }, script = "console.log(msg);")
	private static native void log(String msg);

	private static final int TEX = 64; // 64*4 = 256 == required 256-byte bytesPerRow alignment
	private static final int BYTES_PER_ROW = TEX * 4;
	private static final int READBACK_SIZE = BYTES_PER_ROW * TEX;

	public static void run() {
		log("[WebGPUSelfTest] begin");
		try {
			if (!WebGPU.isSupported()) {
				log("[WebGPUSelfTest] FAIL navigator.gpu is not available");
				return;
			}
			JSObject adapter = WebGPU.await(WebGPU.requestAdapterPromise());
			if (adapter == null) {
				log("[WebGPUSelfTest] FAIL no adapter (" + WebGPU.lastError + ")");
				return;
			}
			JSObject device = WebGPU.await(WebGPU.requestDevicePromise(adapter));
			if (device == null) {
				log("[WebGPUSelfTest] FAIL no device (" + WebGPU.lastError + ")");
				return;
			}
			JSObject queue = WebGPU.deviceQueue(device);
			WebGPUDevice wdev = new WebGPUDevice(device, queue, adapter, null);
			log("[WebGPUSelfTest] device ok backend=" + wdev.getDeviceInfo().backendName()
					+ " maxTex=" + wdev.getDeviceInfo().limits().maxTextureSize()
					+ " uboAlign=" + wdev.getDeviceInfo().limits().minUniformOffsetAlignment()
					+ " zZeroToOne=" + wdev.getDeviceInfo().isZZeroToOne());

			WGSLShaderPack.Program pc = WGSLShaderPack.get("position_color");
			if (pc == null) {
				log("[WebGPUSelfTest] FAIL manifest program 'position_color' missing from pack");
				return;
			}
			log("[WebGPUSelfTest] pack ok programs=" + WGSLShaderPack.all().size()
					+ " position_color wgsl=" + pc.wgsl.length() + " chars ubos=" + pc.uboNames.length);

			JSObject module = WebGPU.deviceCreateShaderModule(device, pc.wgsl, "selftest-position_color");

			// ---- bind group layout: 2 uniform buffers (Projection=0, DynamicTransforms=1) ----
			JSObject bglEntries = WebGPU.newArr();
			WebGPU.push(bglEntries, uniformEntry(0));
			WebGPU.push(bglEntries, uniformEntry(1));
			JSObject bglDesc = WebGPU.newObj();
			WebGPU.putObj(bglDesc, "entries", bglEntries);
			JSObject bgl = WebGPU.deviceCreateBindGroupLayout(device, bglDesc);

			JSObject bgls = WebGPU.newArr();
			WebGPU.push(bgls, bgl);
			JSObject plDesc = WebGPU.newObj();
			WebGPU.putObj(plDesc, "bindGroupLayouts", bgls);
			JSObject pipelineLayout = WebGPU.deviceCreatePipelineLayout(device, plDesc);

			// ---- vertex layout: Position float32x3 @0, Color float32x4 @12, stride 28 ----
			JSObject attrs = WebGPU.newArr();
			WebGPU.push(attrs, attr("float32x3", 0, 0));
			WebGPU.push(attrs, attr("float32x4", 12, 1));
			JSObject vbl = WebGPU.newObj();
			WebGPU.putInt(vbl, "arrayStride", 28);
			WebGPU.putStr(vbl, "stepMode", "vertex");
			WebGPU.putObj(vbl, "attributes", attrs);
			JSObject vBuffers = WebGPU.newArr();
			WebGPU.push(vBuffers, vbl);
			JSObject vertex = WebGPU.newObj();
			WebGPU.putObj(vertex, "module", module);
			WebGPU.putStr(vertex, "entryPoint", "vs");
			WebGPU.putObj(vertex, "buffers", vBuffers);

			JSObject target = WebGPU.newObj();
			WebGPU.putStr(target, "format", "rgba8unorm");
			JSObject targets = WebGPU.newArr();
			WebGPU.push(targets, target);
			JSObject fragment = WebGPU.newObj();
			WebGPU.putObj(fragment, "module", module);
			WebGPU.putStr(fragment, "entryPoint", "fs");
			WebGPU.putObj(fragment, "targets", targets);

			JSObject primitive = WebGPU.newObj();
			WebGPU.putStr(primitive, "topology", "triangle-list");

			JSObject pipeDesc = WebGPU.newObj();
			WebGPU.putObj(pipeDesc, "layout", pipelineLayout);
			WebGPU.putObj(pipeDesc, "vertex", vertex);
			WebGPU.putObj(pipeDesc, "fragment", fragment);
			WebGPU.putObj(pipeDesc, "primitive", primitive);
			JSObject pipeline = WebGPU.deviceCreateRenderPipeline(device, pipeDesc);
			if (pipeline == null) {
				log("[WebGPUSelfTest] FAIL pipeline creation returned null (WGSL/layout mismatch)");
				return;
			}
			log("[WebGPUSelfTest] pipeline built");

			// ---- UBOs (identity matrices, white ColorModulator) ----
			WebGPUBuffer projBuf = (WebGPUBuffer) wdev.createBuffer(() -> "proj", GpuBuffer.USAGE_UNIFORM, identityMat4Ubo());
			WebGPUBuffer dynBuf = (WebGPUBuffer) wdev.createBuffer(() -> "dyn", GpuBuffer.USAGE_UNIFORM, dynamicTransformsUbo());

			// ---- vertex buffer: oversized red triangle covering the centre ----
			WebGPUBuffer vertBuf = (WebGPUBuffer) wdev.createBuffer(() -> "tri", GpuBuffer.USAGE_VERTEX, triangle());

			// ---- bind group ----
			JSObject bgEntries = WebGPU.newArr();
			WebGPU.push(bgEntries, bufferBinding(0, projBuf.handle(), projBuf.gpuSize()));
			WebGPU.push(bgEntries, bufferBinding(1, dynBuf.handle(), dynBuf.gpuSize()));
			JSObject bgDesc = WebGPU.newObj();
			WebGPU.putObj(bgDesc, "layout", bgl);
			WebGPU.putObj(bgDesc, "entries", bgEntries);
			JSObject bindGroup = WebGPU.deviceCreateBindGroup(device, bgDesc);

			// ---- offscreen texture (RGBA8, RENDER_ATTACHMENT | COPY_SRC) + view ----
			WebGPUTexture offscreen = (WebGPUTexture) wdev.createTexture("selftest-rt",
					GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, TEX, TEX, 1, 1);
			WebGPUTextureView offView = (WebGPUTextureView) wdev.createTextureView(offscreen);

			// ---- readback buffer (MAP_READ | COPY_DST) ----
			JSObject readBuf = WebGPU.deviceCreateBuffer(device, READBACK_SIZE, WebGPUConst.BUF_MAP_READ | WebGPUConst.BUF_COPY_DST, false);

			// ---- record + submit ----
			JSObject enc = WebGPU.deviceCreateCommandEncoder(device);
			JSObject ca = WebGPU.newObj();
			WebGPU.putObj(ca, "view", offView.viewHandle());
			WebGPU.putStr(ca, "loadOp", "clear");
			WebGPU.putStr(ca, "storeOp", "store");
			JSObject clr = WebGPU.newObj();
			WebGPU.putNum(clr, "r", 0.0);
			WebGPU.putNum(clr, "g", 0.0);
			WebGPU.putNum(clr, "b", 0.0);
			WebGPU.putNum(clr, "a", 1.0);
			WebGPU.putObj(ca, "clearValue", clr);
			JSObject caArr = WebGPU.newArr();
			WebGPU.push(caArr, ca);
			JSObject passDesc = WebGPU.newObj();
			WebGPU.putObj(passDesc, "colorAttachments", caArr);
			JSObject pass = WebGPU.encoderBeginRenderPass(enc, passDesc);
			WebGPU.passSetPipeline(pass, pipeline);
			WebGPU.passSetBindGroup(pass, 0, bindGroup);
			WebGPU.passSetVertexBuffer(pass, 0, vertBuf.handle(), 0, vertBuf.gpuSize());
			WebGPU.passDraw(pass, 3, 1, 0, 0);
			WebGPU.passEnd(pass);

			// copyTextureToBuffer
			JSObject src = WebGPU.newObj();
			WebGPU.putObj(src, "texture", offscreen.handle());
			JSObject dst = WebGPU.newObj();
			WebGPU.putObj(dst, "buffer", readBuf);
			WebGPU.putInt(dst, "bytesPerRow", BYTES_PER_ROW);
			WebGPU.putInt(dst, "rowsPerImage", TEX);
			JSObject size = WebGPU.newObj();
			WebGPU.putInt(size, "width", TEX);
			WebGPU.putInt(size, "height", TEX);
			WebGPU.putInt(size, "depthOrArrayLayers", 1);
			WebGPU.encoderCopyTextureToBuffer(enc, src, dst, size);

			JSObject cmd = WebGPU.encoderFinish(enc);
			WebGPU.queueSubmit(queue, cmd);

			// ---- map + read centre pixel ----
			WebGPU.await(WebGPU.bufferMapAsync(readBuf, WebGPUConst.MAP_READ, 0, READBACK_SIZE));
			ArrayBuffer ab = WebGPU.bufferGetMappedRangeCopy(readBuf, 0, READBACK_SIZE);
			WebGPU.bufferUnmap(readBuf);
			Uint8Array px = Uint8Array.create(ab);
			int centre = (TEX / 2) * BYTES_PER_ROW + (TEX / 2) * 4;
			int r = px.get(centre) & 0xFF;
			int g = px.get(centre + 1) & 0xFF;
			int b = px.get(centre + 2) & 0xFF;

			if (r >= 250 && g <= 5 && b <= 5) {
				log("[WebGPUSelfTest] PASS pixel=" + r + "," + g + "," + b);
			} else {
				log("[WebGPUSelfTest] FAIL pixel=" + r + "," + g + "," + b + " (expected ~255,0,0)");
			}
		} catch (Throwable t) {
			log("[WebGPUSelfTest] EXCEPTION " + t.toString());
			EagRuntime.debugPrintStackTrace(t);
		}
	}

	private static JSObject uniformEntry(final int binding) {
		JSObject e = WebGPU.newObj();
		WebGPU.putInt(e, "binding", binding);
		WebGPU.putInt(e, "visibility", 0x3); // VERTEX | FRAGMENT
		JSObject buf = WebGPU.newObj();
		WebGPU.putStr(buf, "type", "uniform");
		WebGPU.putObj(e, "buffer", buf);
		return e;
	}

	private static JSObject attr(final String fmt, final int offset, final int loc) {
		JSObject a = WebGPU.newObj();
		WebGPU.putStr(a, "format", fmt);
		WebGPU.putInt(a, "offset", offset);
		WebGPU.putInt(a, "shaderLocation", loc);
		return a;
	}

	private static JSObject bufferBinding(final int binding, final JSObject handle, final int size) {
		JSObject res = WebGPU.newObj();
		WebGPU.putObj(res, "buffer", handle);
		WebGPU.putInt(res, "offset", 0);
		WebGPU.putInt(res, "size", size);
		JSObject e = WebGPU.newObj();
		WebGPU.putInt(e, "binding", binding);
		WebGPU.putObj(e, "resource", res);
		return e;
	}

	private static ByteBuffer identityMat4Ubo() {
		ByteBuffer bb = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
		FloatBuffer fb = bb.asFloatBuffer();
		fb.put(new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 });
		bb.position(0);
		bb.limit(64);
		return bb;
	}

	private static ByteBuffer dynamicTransformsUbo() {
		// std140: ModelViewMat(0..63) + ColorModulator(64..79) + ModelOffset(80..91,pad) + TextureMat(96..159)
		ByteBuffer bb = ByteBuffer.allocateDirect(160).order(ByteOrder.LITTLE_ENDIAN);
		FloatBuffer fb = bb.asFloatBuffer();
		float[] data = new float[40];
		// ModelViewMat identity
		data[0] = 1; data[5] = 1; data[10] = 1; data[15] = 1;
		// ColorModulator white (offset 64 bytes = float 16)
		data[16] = 1; data[17] = 1; data[18] = 1; data[19] = 1;
		// TextureMat identity (offset 96 bytes = float 24)
		data[24] = 1; data[29] = 1; data[34] = 1; data[39] = 1;
		fb.put(data);
		bb.position(0);
		bb.limit(160);
		return bb;
	}

	private static ByteBuffer triangle() {
		// 3 verts * (vec3 pos + vec4 color). Oversized triangle covering clip space.
		ByteBuffer bb = ByteBuffer.allocateDirect(3 * 28).order(ByteOrder.LITTLE_ENDIAN);
		FloatBuffer fb = bb.asFloatBuffer();
		fb.put(new float[] {
				-1f, -1f, 0f, 1f, 0f, 0f, 1f,
				3f, -1f, 0f, 1f, 0f, 0f, 1f,
				-1f, 3f, 0f, 1f, 0f, 0f, 1f,
		});
		bb.position(0);
		bb.limit(3 * 28);
		return bb;
	}
}

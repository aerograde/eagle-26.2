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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.teavm.jso.JSObject;

/**
 * WebGPU backend (increment 1) implementation of
 * {@link com.mojang.blaze3d.systems.GpuDeviceBackend}, implemented DIRECTLY on
 * WebGPU (no GL emulation). Constructed from an already-acquired GPUDevice/queue
 * (the async adapter/device request is done in {@link WebGPUBackend#createDevice}).
 *
 * Key differences from the WebGL2 device: real texture views, real buffer mapping
 * (async), dynamic-offset UBOs (no std140 bind-size fixup needed), and the depth
 * range is [0,1] so isZZeroToOne = true. WGSL shaders come pre-translated from the
 * {@link WGSLShaderPack}; the defaultShaderSource (GLSL) is unused.
 */
public class WebGPUDevice implements GpuDeviceBackend {

	private final JSObject device;
	private final JSObject queue;
	private final DeviceInfo deviceInfo;
	private final String preferredCanvasFormat;

	private WebGPUCommandEncoder encoder;

	private final Map<RenderPipeline, WebGPUPipeline> pipelineCache = new IdentityHashMap<>();

	public WebGPUDevice(final JSObject device, final JSObject queue, final JSObject adapter, final ShaderSource defaultShaderSource) {
		this.device = device;
		this.queue = queue;
		this.preferredCanvasFormat = WebGPU.getPreferredCanvasFormat();

		String vendor = WebGPU.adapterVendor(adapter);
		String arch = WebGPU.adapterArchitecture(adapter);
		int maxTextureSize = WebGPU.deviceMaxTextureDimension2D(device);
		int uboAlign = WebGPU.deviceMinUniformBufferOffsetAlignment(device);
		if (uboAlign <= 0) {
			uboAlign = 256;
		}
		int maxColorAttachments = WebGPU.deviceMaxColorAttachments(device);

		DeviceLimits limits = new DeviceLimits(16, uboAlign, maxTextureSize, Long.MAX_VALUE, 0, maxColorAttachments);
		// increment 1: no multiDraw*/indirect/baseInstance yet; no persistent mapping.
		DeviceFeatures features = new DeviceFeatures(false, false, false, false, false, false, false);
		// WebGPU clip space depth is [0,1] (unlike GL's [-1,1]).
		this.deviceInfo = new DeviceInfo("WebGPU (" + arch + ")", vendor, "WebGPU", true, "WebGPU", 0.0F, limits, features,
				Collections.emptySet(), new HintsAndWorkarounds(false, false), DeviceType.OTHER);
	}

	public JSObject deviceHandle() {
		return this.device;
	}

	public JSObject queueHandle() {
		return this.queue;
	}

	public String preferredCanvasFormat() {
		return this.preferredCanvasFormat;
	}

	@Override
	public GpuSurfaceBackend createSurface(final long windowHandle) {
		return new WebGPUSurface(this);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		if (this.encoder == null) {
			this.encoder = new WebGPUCommandEncoder(this);
		}
		return this.encoder;
	}

	@Override
	public GpuSampler createSampler(final AddressMode addressModeU, final AddressMode addressModeV, final FilterMode minFilter,
			final FilterMode magFilter, final int maxAnisotropy, final OptionalDouble maxLod) {
		return new WebGPUSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(final Supplier<String> label, final @GpuTexture.Usage int usage, final GpuFormat format, final int width,
			final int height, final int depthOrLayers, final int mipLevels) {
		return this.createTexture(label != null ? label.get() : null, usage, format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTexture createTexture(String label, final @GpuTexture.Usage int usage, final GpuFormat format, final int width, final int height,
			final int depthOrLayers, final int mipLevels) {
		String wgpuFormat = WebGPUConst.textureFormat(format);
		if (wgpuFormat == null) {
			throw new IllegalArgumentException(format + " format cannot be used to create WebGPU textures");
		}
		if (label == null) {
			label = "webgpu-tex";
		}
		boolean cubemap = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;

		JSObject size = WebGPU.newObj();
		WebGPU.putInt(size, "width", width);
		WebGPU.putInt(size, "height", height);
		WebGPU.putInt(size, "depthOrArrayLayers", depthOrLayers);

		JSObject desc = WebGPU.newObj();
		WebGPU.putStr(desc, "label", label);
		WebGPU.putObj(desc, "size", size);
		WebGPU.putStr(desc, "format", wgpuFormat);
		WebGPU.putInt(desc, "mipLevelCount", mipLevels);
		WebGPU.putInt(desc, "sampleCount", 1);
		WebGPU.putStr(desc, "dimension", "2d");
		WebGPU.putInt(desc, "usage", WebGPUConst.textureUsage(usage));
		if (cubemap) {
			// allow cube-dimension views
			JSObject viewFormats = WebGPU.newArr();
			WebGPU.putObj(desc, "viewFormats", viewFormats);
		}

		JSObject handle = WebGPU.deviceCreateTexture(this.device, desc);
		return new WebGPUTexture(usage, label, format, width, height, depthOrLayers, mipLevels, handle, false);
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture) {
		return this.createTextureView(texture, 0, texture.getMipLevels());
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		return new WebGPUTextureView((WebGPUTexture) texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(final Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
		return new WebGPUBuffer(this, usage, size, null);
	}

	@Override
	public GpuBuffer createBuffer(final Supplier<String> label, final @GpuBuffer.Usage int usage, final ByteBuffer data) {
		return new WebGPUBuffer(this, usage, data.remaining(), data);
	}

	@Override
	public List<String> getLastDebugMessages() {
		return Collections.emptyList();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public CompiledRenderPipeline precompilePipeline(final RenderPipeline pipeline, final ShaderSource shaderSource) {
		return this.getOrCompilePipeline(pipeline);
	}

	public WebGPUPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
		WebGPUPipeline cached = this.pipelineCache.get(pipeline);
		if (cached != null) {
			return cached;
		}
		WGSLShaderPack.Program program = WebGPUProgramMapping.resolve(pipeline);
		WebGPUPipeline built = WebGPUPipeline.build(this, pipeline, program);
		this.pipelineCache.put(pipeline, built);
		return built;
	}

	@Override
	public void clearPipelineCache() {
		this.pipelineCache.clear();
	}

	@Override
	public void close() {
		this.clearPipelineCache();
		if (this.encoder != null) {
			this.encoder.close();
		}
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(final int size) {
		return new WebGPUQueryPool(size);
	}

	@Override
	public long getTimestampNow() {
		return 0L;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.deviceInfo;
	}
}

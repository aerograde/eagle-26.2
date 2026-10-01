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

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.GpuOutOfMemoryException;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
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

import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

import org.teavm.jso.webgl.WebGLTexture;

/**
 * Phase 3.3a WebGL2 implementation of com.mojang.blaze3d.systems.GpuDeviceBackend
 * — ported from com.mojang.blaze3d.opengl.GlDevice. Constructed directly with a
 * live {@link WebGL2RenderingContextExt} (the game-side selection seam +
 * GpuBackend factory is Phase 3.3b). Key GL→WebGL2 changes per the recon:
 *  - DeviceFeatures are ALL false, isZZeroToOne=false (no glClipControl), and
 *    persistentMapping=false so the command encoder picks the Fallback transient
 *    path;
 *  - textures are per-mip glTexImage2D over TEXTURE_2D / TEXTURE_CUBE_MAP only;
 *  - the DirectStateAccess / FrameBufferCache / KHR_debug label layers collapse
 *    away (WebGL2 has no DSA; the encoder binds FBOs directly);
 *  - the command encoder is created lazily so a Device-only self-test does not
 *    drag the encoder/render-pass/transient graph (and its LWJGL/Vk signature
 *    references) into TeaVM reachability.
 */
public class WebGL2Device implements GpuDeviceBackend {

	private final WebGL2RenderingContextExt gl;
	private final ShaderSource defaultShaderSource;
	private final DeviceInfo deviceInfo;
	private final boolean anisotropySupported;
	private final WebGL2VertexArray vertexArrayCache;

	private WebGL2CommandEncoder encoder;

	private final Map<RenderPipeline, WebGL2RenderPipeline> pipelineCache = new IdentityHashMap<>();
	private final Map<ShaderKey, WebGL2ShaderModule> shaderCache = new HashMap<>();

	public WebGL2Device(final WebGL2RenderingContextExt gl, final ShaderSource defaultShaderSource, final GpuDebugOptions debugOptions) {
		this.gl = gl;
		this.defaultShaderSource = defaultShaderSource;
		this.vertexArrayCache = new WebGL2VertexArray(gl);

		Set<String> enabledExtensions = new HashSet<>();
		int maxAnisotropy = 1;
		boolean aniso = false;
		if (gl.getExtension("EXT_texture_filter_anisotropic") != null) {
			aniso = true;
			enabledExtensions.add("EXT_texture_filter_anisotropic");
			maxAnisotropy = (int) gl.getParameterf(34047); // MAX_TEXTURE_MAX_ANISOTROPY_EXT
			if (maxAnisotropy < 1) {
				maxAnisotropy = 1;
			}
		}
		this.anisotropySupported = aniso;
		if (gl.getExtension("EXT_color_buffer_float") != null) {
			enabledExtensions.add("EXT_color_buffer_float");
		}

		String renderer = safeStr(gl.getParameterString(WebGL2Const.GL_RENDERER), "WebGL 2.0");
		String vendor = safeStr(gl.getParameterString(WebGL2Const.GL_VENDOR), "unknown");
		String version = safeStr(gl.getParameterString(WebGL2Const.GL_VERSION), "WebGL 2.0");

		int maxTextureSize = gl.getParameteri(WebGL2Const.GL_MAX_TEXTURE_SIZE);
		int maxColorAttachments = gl.getParameteri(WebGL2Const.GL_MAX_COLOR_ATTACHMENTS);
		int uboAlign = gl.getParameteri(WebGL2Const.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
		if (uboAlign <= 0) {
			uboAlign = 256;
		}

		DeviceLimits limits = new DeviceLimits(maxAnisotropy, uboAlign, maxTextureSize, Long.MAX_VALUE, 0, maxColorAttachments);
		// ALL device features false (recon §3): no multiDraw*, no drawIndirect, no
		// baseInstance (nonZeroFirstInstance), no persistent mapping.
		DeviceFeatures features = new DeviceFeatures(false, false, false, false, false, false, false);
		this.deviceInfo = new DeviceInfo(renderer, vendor, version, /* isZZeroToOne */ false, "WebGL2", 0.0F, limits, features,
				Collections.unmodifiableSet(enabledExtensions), new HintsAndWorkarounds(false, false), DeviceType.OTHER);
	}

	private static String safeStr(final String s, final String fallback) {
		return s == null ? fallback : s;
	}

	public WebGL2RenderingContextExt gl() {
		return this.gl;
	}

	public WebGL2VertexArray vertexArrayCache() {
		return this.vertexArrayCache;
	}

	@Override
	public GpuSurfaceBackend createSurface(final long windowHandle) {
		return new WebGL2Surface(this.gl);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		if (this.encoder == null) {
			this.encoder = new WebGL2CommandEncoder(this);
		}
		return this.encoder;
	}

	@Override
	public GpuSampler createSampler(final AddressMode addressModeU, final AddressMode addressModeV, final FilterMode minFilter,
			final FilterMode magFilter, final int maxAnisotropy, final OptionalDouble maxLod) {
		return new WebGL2Sampler(this.gl, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod, this.anisotropySupported);
	}

	@Override
	public GpuTexture createTexture(final Supplier<String> label, final @GpuTexture.Usage int usage, final GpuFormat format, final int width,
			final int height, final int depthOrLayers, final int mipLevels) {
		return this.createTexture(label != null ? label.get() : null, usage, format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTexture createTexture(String label, final @GpuTexture.Usage int usage, final GpuFormat format, final int width, final int height,
			final int depthOrLayers, final int mipLevels) {
		boolean isCubemap = (usage & 16) != 0;
		WebGLTexture id = this.gl.createTexture();
		if (id == null) {
			throw new GpuOutOfMemoryException("Could not create texture object of " + width + "x" + height);
		}
		try {
		if (label == null) {
			label = "webgl2-tex";
		}
		int target = isCubemap ? WebGL2Const.GL_TEXTURE_CUBE_MAP : WebGL2Const.GL_TEXTURE_2D;
		this.gl.bindTexture(target, id);
		this.gl.texParameteri(target, WebGL2Const.GL_TEXTURE_MAX_LEVEL, mipLevels - 1);
		this.gl.texParameteri(target, WebGL2Const.GL_TEXTURE_BASE_LEVEL, 0);
		if (format.hasDepthAspect()) {
			this.gl.texParameteri(target, WebGL2Const.GL_TEXTURE_COMPARE_MODE, 0);
		}

		int glInternalID = WebGL2Const.toGlInternalId(format);
		int glExternalID = WebGL2Const.toGlExternalId(format);
		int glType = WebGL2Const.toGlType(format);
		if (glInternalID == 0 || glExternalID == 0 || glType == 0) {
			throw new IllegalArgumentException(format + " format cannot be used to create textures");
		}

		// Eagler: WebGL getError() returns the FIRST error since the previous getError() call,
		// so an unrelated error left by an earlier draw/UBO/upload (e.g. the sampler/format-
		// mismatch warnings) would be MISATTRIBUTED to this texImage2D and crash the game (the
		// intermittent vignette 1282). Drain any stale error here so the check after texImage2D
		// reflects ONLY this texture's real errors.
		for (int drain = 0; drain < 64 && this.gl.getError() != WebGL2Const.GL_NO_ERROR; drain++) {
		}

		if (isCubemap) {
			for (int cubeTarget : WebGL2Const.CUBEMAP_TARGETS) {
				for (int i = 0; i < mipLevels; i++) {
					this.gl.texImage2D(cubeTarget, i, glInternalID, width >> i, height >> i, 0, glExternalID, glType,
							(org.teavm.jso.typedarrays.ArrayBufferView) null);
				}
			}
		} else {
			for (int i = 0; i < mipLevels; i++) {
				this.gl.texImage2D(target, i, glInternalID, width >> i, height >> i, 0, glExternalID, glType,
						(org.teavm.jso.typedarrays.ArrayBufferView) null);
			}
		}

		int error = this.gl.getError();
		if (error == WebGL2Const.GL_OUT_OF_MEMORY) {
			throw new GpuOutOfMemoryException("Could not allocate texture of " + width + "x" + height + " for " + label);
		}
		if (error != WebGL2Const.GL_NO_ERROR) {
			// getError() returns the FIRST error since the last check, so this may be a
			// stale error left by an earlier draw/upload rather than this texImage2D
			// (that is exactly how a UBO-bind 1282 got misattributed to a texture). Name
			// the format/dims so the real cause is at least narrowable from the report.
			throw new IllegalStateException("WebGL error " + error + " creating texture '" + label + "' ("
					+ format + " " + width + "x" + height + " mips=" + mipLevels + " internal=" + glInternalID
					+ " external=" + glExternalID + " type=" + glType + "); note: may be a stale error from a"
					+ " prior GL call (glGetError is deferred).");
		}
		return new WebGL2Texture(this.gl, usage, label, format, width, height, depthOrLayers, mipLevels, id);
		} catch (RuntimeException | Error exception) {
			try {
				this.gl.deleteTexture(id);
			} catch (RuntimeException | Error cleanupException) {
				exception.addSuppressed(cleanupException);
			}

			throw exception;
		}
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture) {
		return this.createTextureView(texture, 0, texture.getMipLevels());
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		return new WebGL2TextureView((WebGL2Texture) texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(final Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
		return new WebGL2Buffer(this.gl, usage, size, null);
	}

	@Override
	public GpuBuffer createBuffer(final Supplier<String> label, final @GpuBuffer.Usage int usage, final ByteBuffer data) {
		return new WebGL2Buffer(this.gl, usage, data.remaining(), data);
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
	public com.mojang.blaze3d.pipeline.CompiledRenderPipeline precompilePipeline(final RenderPipeline pipeline, final ShaderSource shaderSource) {
		ShaderSource src = shaderSource == null ? this.defaultShaderSource : shaderSource;
		return this.pipelineCache.computeIfAbsent(pipeline, p -> this.compilePipeline(p, src));
	}

	public WebGL2RenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
		return this.pipelineCache.computeIfAbsent(pipeline, p -> this.compilePipeline(p, this.defaultShaderSource));
	}

	private WebGL2RenderPipeline compilePipeline(final RenderPipeline pipeline, final ShaderSource shaderSource) {
		WebGL2ShaderModule vsh = this.getOrCompileShader(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), shaderSource);
		WebGL2ShaderModule fsh = this.getOrCompileShader(pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), shaderSource);
		if (vsh == WebGL2ShaderModule.INVALID_SHADER || fsh == WebGL2ShaderModule.INVALID_SHADER) {
			return new WebGL2RenderPipeline(pipeline, WebGL2Program.INVALID_PROGRAM);
		}
		try {
			WebGL2Program program = WebGL2Program.link(this.gl, vsh, fsh, pipeline.getVertexFormatBindings(), pipeline.getLocation().toString());
			program.setupBindGroupLayouts(pipeline.getBindGroupLayouts());
			return new WebGL2RenderPipeline(pipeline, program);
		} catch (WebGL2ShaderException | IllegalArgumentException e) {
			System.err.println("eagler-webgl2: couldn't compile pipeline " + pipeline.getLocation() + ": " + e.getMessage());
			return new WebGL2RenderPipeline(pipeline, WebGL2Program.INVALID_PROGRAM);
		}
	}

	private WebGL2ShaderModule getOrCompileShader(final Identifier id, final ShaderType type, final ShaderDefines defines, final ShaderSource shaderSource) {
		ShaderKey key = new ShaderKey(id, type, defines);
		return this.shaderCache.computeIfAbsent(key, k -> {
			if (shaderSource == null) {
				return WebGL2ShaderModule.INVALID_SHADER;
			}
			String source = shaderSource.get(id, type);
			if (source == null) {
				System.err.println("eagler-webgl2: no source for " + type + " shader (" + id + ")");
				return WebGL2ShaderModule.INVALID_SHADER;
			}
			String withDefines = GlslPreprocessor.injectDefines(source, defines);
			return WebGL2Program.compileShaderModule(this.gl, withDefines, id.toString(), type);
		});
	}

	@Override
	public void clearPipelineCache() {
		for (WebGL2RenderPipeline pipeline : this.pipelineCache.values()) {
			if (pipeline.program() != WebGL2Program.INVALID_PROGRAM) {
				pipeline.program().close();
			}
		}
		this.pipelineCache.clear();
		for (WebGL2ShaderModule shader : this.shaderCache.values()) {
			if (shader != WebGL2ShaderModule.INVALID_SHADER) {
				shader.close();
			}
		}
		this.shaderCache.clear();
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
		return new WebGL2QueryPool(size);
	}

	@Override
	public long getTimestampNow() {
		return 0L; // timestamp queries unsupported on WebGL2
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.deviceInfo;
	}

	private record ShaderKey(Identifier id, ShaderType type, ShaderDefines defines) {
	}
}

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.joml.Vector4fc;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Uint8Array;
import org.teavm.jso.webgl.WebGLFramebuffer;
import org.teavm.jso.webgl.WebGLProgram;
import org.teavm.jso.webgl.WebGLShader;
import org.teavm.jso.webgl.WebGLUniformLocation;
import org.teavm.jso.webgl.WebGLVertexArrayObject;

/**
 * Phase 3.3a WebGL2 CommandEncoderBackend — the bulk of the backend, ported from
 * com.mojang.blaze3d.opengl.GlCommandEncoder. Structural changes vs GL (recon):
 *  - submit() is NON-BLOCKING (recon §5): GlCommandEncoder.submit() blocks on the
 *    2-frames-ago fence via clientWaitSync(MAX), which would freeze the browser.
 *    Browser/compositor submission already provides back-pressure at the rAF
 *    boundary, so per-frame pacing fences are unnecessary here.
 *  - DirectStateAccess/FrameBufferCache collapse into two scratch FBOs bound
 *    directly (no DSA on WebGL2).
 *  - Indexed baseVertex is folded into vertex-attribute offsets (WebGL2VertexArray).
 *  - glDrawBuffer(single) → drawBuffers([...]) (recon §3).
 *  - glPolygonMode / wireframe: no-op. multiDraw and indirect: UnsupportedOperation.
 */
public class WebGL2CommandEncoder implements CommandEncoderBackend, AutoCloseable {

	private final WebGL2Device device;
	private final WebGL2RenderingContextExt gl;
	private WebGL2TransientMemory transientMemory;
	private final WebGLFramebuffer readFbo;
	private final WebGLFramebuffer drawFbo;

	private RenderPipeline lastPipeline;
	private WebGL2Program lastProgram;
	private WebGL2VertexArray.Entry lastVertexArray;
	private int lastDrawBufferCount = -1;

	protected WebGL2CommandEncoder(final WebGL2Device device) {
		this.device = device;
		this.gl = device.gl();
		this.readFbo = this.gl.createFramebuffer();
		this.drawFbo = this.gl.createFramebuffer();
	}

	WebGL2Device device() {
		return this.device;
	}

	WebGL2RenderingContextExt gl() {
		return this.gl;
	}

	private static Int8Array asInt8(final ByteBuffer data) {
		return Int8Array.fromJavaBuffer(data);
	}

	/**
	 * WebGL2 requires the pixel view for an UNSIGNED_BYTE (5121) tex(Sub)Image2D
	 * upload to be a Uint8Array/Uint8ClampedArray; a signed Int8Array is rejected
	 * with INVALID_OPERATION (1282). Reinterpret the same bytes as unsigned (a
	 * zero-copy view over the identical ArrayBuffer range), mirroring
	 * TeaVMUtils.unwrapUnsignedByteArray.
	 */
	private static Uint8Array asUint8(final ByteBuffer data) {
		Int8Array i8 = Int8Array.fromJavaBuffer(data);
		return Uint8Array.create(i8.getBuffer(), i8.getByteOffset(), i8.getLength());
	}

	/** Phase 3.3b title probe: running draw-call counter (drawFromBuffers). */
	private long drawCallsTotal;

	public long getDrawCallsTotal() {
		return this.drawCallsTotal;
	}

	@Override
	public void submit() {
		// The canvas is submitted asynchronously and WebGL defers deletion of buffers
		// referenced by queued commands. One rAF in WebGL2Surface.present() is the only
		// frame boundary needed; extra submit fences caused multi-frame stalls.
		if (this.transientMemory != null) {
			this.transientMemory.rotate();
		}
	}

	@Override
	public TransientMemory transientMemory() {
		if (this.transientMemory == null) {
			this.transientMemory = new WebGL2TransientMemory(this.gl);
		}
		return this.transientMemory;
	}

	private void bindColorDepthFbo(final WebGLFramebuffer fbo, final int target, final List<WebGL2Texture> colors, final WebGL2Texture depth,
			final int depthMipLevel) {
		this.bindColorDepthFbo(fbo, target, colors, 0, depth, depthMipLevel);
	}

	private void bindColorDepthFbo(final WebGLFramebuffer fbo, final int target, final List<WebGL2Texture> colors, final int colorMipLevel,
			final WebGL2Texture depth, final int depthMipLevel) {
		// A new pass, copy or texture clear owns a new framebuffer state epoch.
		// This also covers clearDepthTexture's temporary NONE draw-buffer list.
		this.lastDrawBufferCount = -1;
		this.gl.bindFramebuffer(target, fbo);
		for (int i = 0; i < colors.size(); i++) {
			WebGL2Texture c = colors.get(i);
			this.gl.framebufferTexture2D(target, WebGL2Const.GL_COLOR_ATTACHMENT0 + i, WebGL2Const.GL_TEXTURE_2D, c == null ? null : c.id, colorMipLevel);
		}
		this.gl.framebufferTexture2D(target, WebGL2Const.GL_DEPTH_ATTACHMENT, WebGL2Const.GL_TEXTURE_2D, depth == null ? null : depth.id, depthMipLevel);
	}

	@Override
	public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();
		List<WebGL2Texture> colorTextures = new ArrayList<>();
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> a : colorAttachments) {
			colorTextures.add(a != null ? ((WebGL2TextureView) a.textureView()).texture() : null);
		}
		RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
		WebGL2Texture depthTex = depthAttachment == null ? null : ((WebGL2TextureView) depthAttachment.textureView()).texture();

		// Honor the color view's baseMipLevel: TextureAtlas.uploadInitialContents GPU-blits
		// each sprite mip into a single-mip view (baseMipLevel=level). Hard-coding level 0
		// here rendered EVERY mip blit into atlas mip 0 — mips 1-3 stayed transparent-black
		// (gray-cyan leaves at minification) and the smaller levels stamped into mip 0's
		// top-left corner (neighbor-sprite bleed specks). Scene passes use baseMipLevel=0.
		int colorMipLevel = 0;
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> a : colorAttachments) {
			if (a != null) {
				colorMipLevel = ((WebGL2TextureView) a.textureView()).baseMipLevel();
				break;
			}
		}
		this.bindColorDepthFbo(this.drawFbo, WebGL2Const.GL_FRAMEBUFFER, colorTextures, colorMipLevel, depthTex, 0);

		this.gl.enable(WebGL2Const.GL_SCISSOR_TEST);
		this.gl.scissor(descriptor.renderArea.x(), descriptor.renderArea.y(), descriptor.renderArea.width(), descriptor.renderArea.height());

		for (int i = 0; i < colorAttachments.size(); i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> a = colorAttachments.get(i);
			if (a != null && a.clearValue().isPresent()) {
				Vector4fc cv = a.clearValue().get();
				this.gl.colorMask(true, true, true, true);
				this.gl.clearColor(cv.x(), cv.y(), cv.z(), cv.w());
				this.gl.clear(WebGL2Const.GL_COLOR_BUFFER_BIT);
			}
		}
		if (depthAttachment != null && depthAttachment.clearValue().isPresent()) {
			this.gl.depthMask(true);
			this.gl.clearDepth((float) depthAttachment.clearValue().getAsDouble());
			this.gl.clear(WebGL2Const.GL_DEPTH_BUFFER_BIT);
		}

		int width = 0;
		int height = 0;
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> a : colorAttachments) {
			if (a != null) {
				width = a.textureView().getWidth(0);
				height = a.textureView().getHeight(0);
			}
		}
		if (width == 0 && depthAttachment != null) {
			width = depthAttachment.textureView().getWidth(0);
			height = depthAttachment.textureView().getHeight(0);
		}
		this.gl.viewport(0, 0, width, height);
		this.lastPipeline = null;

		ScissorState scissorState = new ScissorState();
		scissorState.enable(descriptor.renderArea.x(), descriptor.renderArea.y(), descriptor.renderArea.width(), descriptor.renderArea.height());
		return new WebGL2RenderPass(this, this.device, depthAttachment != null, colorTextures.size(), scissorState);
	}

	@Override
	public void submitRenderPass() {
		this.lastDrawBufferCount = -1;
		this.gl.bindFramebuffer(WebGL2Const.GL_FRAMEBUFFER, null);
	}

	@Override
	public void clearColorTexture(final GpuTexture colorTexture, final Vector4fc clearColor) {
		this.bindColorDepthFbo(this.drawFbo, WebGL2Const.GL_FRAMEBUFFER, List.of((WebGL2Texture) colorTexture), null, 0);
		this.gl.clearColor(clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w());
		this.gl.disable(WebGL2Const.GL_SCISSOR_TEST);
		this.gl.colorMask(true, true, true, true);
		this.gl.clear(WebGL2Const.GL_COLOR_BUFFER_BIT);
		this.gl.bindFramebuffer(WebGL2Const.GL_FRAMEBUFFER, null);
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth) {
		this.bindColorDepthFbo(this.drawFbo, WebGL2Const.GL_FRAMEBUFFER, List.of((WebGL2Texture) colorTexture), (WebGL2Texture) depthTexture, 0);
		this.gl.disable(WebGL2Const.GL_SCISSOR_TEST);
		this.gl.clearDepth((float) clearDepth);
		this.gl.clearColor(clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w());
		this.gl.depthMask(true);
		this.gl.colorMask(true, true, true, true);
		this.gl.clear(WebGL2Const.GL_COLOR_BUFFER_BIT | WebGL2Const.GL_DEPTH_BUFFER_BIT);
		this.gl.bindFramebuffer(WebGL2Const.GL_FRAMEBUFFER, null);
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture,
			final double clearDepth, final int regionX, final int regionY, final int regionWidth, final int regionHeight) {
		this.bindColorDepthFbo(this.drawFbo, WebGL2Const.GL_FRAMEBUFFER, List.of((WebGL2Texture) colorTexture), (WebGL2Texture) depthTexture, 0);
		this.gl.scissor(regionX, regionY, regionWidth, regionHeight);
		this.gl.enable(WebGL2Const.GL_SCISSOR_TEST);
		this.gl.clearDepth((float) clearDepth);
		this.gl.clearColor(clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w());
		this.gl.depthMask(true);
		this.gl.colorMask(true, true, true, true);
		this.gl.clear(WebGL2Const.GL_COLOR_BUFFER_BIT | WebGL2Const.GL_DEPTH_BUFFER_BIT);
		this.gl.bindFramebuffer(WebGL2Const.GL_FRAMEBUFFER, null);
	}

	@Override
	public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
		this.bindColorDepthFbo(this.drawFbo, WebGL2Const.GL_FRAMEBUFFER, new ArrayList<>(), (WebGL2Texture) depthTexture, 0);
		this.gl.drawBuffers(new int[] { 0 });
		this.gl.clearDepth((float) clearDepth);
		this.gl.depthMask(true);
		this.gl.disable(WebGL2Const.GL_SCISSOR_TEST);
		this.gl.clear(WebGL2Const.GL_DEPTH_BUFFER_BIT);
		this.gl.drawBuffers(new int[] { WebGL2Const.GL_COLOR_ATTACHMENT0 });
		this.gl.bindFramebuffer(WebGL2Const.GL_FRAMEBUFFER, null);
	}

	@Override
	public void writeToBuffer(final GpuBufferSlice slice, final ByteBuffer data) {
		WebGL2Buffer buffer = (WebGL2Buffer) slice.buffer();
		buffer.checkCanBeUsed();
		this.gl.bindBuffer(buffer.target(), buffer.handle());
		this.gl.bufferSubData(buffer.target(), (int) slice.offset(), asInt8(data));
		this.gl.bindBuffer(buffer.target(), null);
	}

	@Override
	public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
		WebGL2Buffer src = (WebGL2Buffer) source.buffer();
		WebGL2Buffer dst = (WebGL2Buffer) target.buffer();
		src.checkCanBeUsed();
		dst.checkCanBeUsed();
		// THE menu/skin black-render bug (real GPU only): WebGL2 (spec 3.7.3, enforced by
		// ANGLE but IGNORED by headless SwiftShader) raises GL_INVALID_OPERATION on
		// copyBufferSubData when exactly one endpoint is an ELEMENT_ARRAY_BUFFER. MC's
		// StagedVertexBuffer copies its index slice from a NON-element staging buffer into
		// an ELEMENT (index) GPU buffer in one copyToBuffer — so on real hardware the index
		// buffer silently stayed empty and every indexed draw (panorama cubemap = gray menu,
		// GUI skin/entity picture-in-picture = black box, textured menu-background quads =
		// black) drew nothing. When the element/non-element categories differ, round-trip
		// through CPU via each buffer's own native bind target (no single call ever mixes
		// the two categories). Index slices here are small (GUI/PiP/panorama meshes).
		final boolean srcElem = src.target() == WebGL2Const.GL_ELEMENT_ARRAY_BUFFER;
		final boolean dstElem = dst.target() == WebGL2Const.GL_ELEMENT_ARRAY_BUFFER;
		if (srcElem != dstElem) {
			final int len = (int) source.length();
			final org.teavm.jso.typedarrays.Int8Array view = org.teavm.jso.typedarrays.Int8Array.create(len);
			this.gl.bindBuffer(src.target(), src.handle());
			this.gl.getBufferSubData(src.target(), (int) source.offset(), view, 0, len);
			this.gl.bindBuffer(src.target(), null);
			this.gl.bindBuffer(dst.target(), dst.handle());
			this.gl.bufferSubData(dst.target(), (int) target.offset(), view);
			this.gl.bindBuffer(dst.target(), null);
			return;
		}
		this.gl.bindBuffer(WebGL2Const.GL_COPY_READ_BUFFER, src.handle());
		this.gl.bindBuffer(WebGL2Const.GL_COPY_WRITE_BUFFER, dst.handle());
		this.gl.copyBufferSubData(WebGL2Const.GL_COPY_READ_BUFFER, WebGL2Const.GL_COPY_WRITE_BUFFER, (int) source.offset(), (int) target.offset(),
				(int) source.length());
		this.gl.bindBuffer(WebGL2Const.GL_COPY_READ_BUFFER, null);
		this.gl.bindBuffer(WebGL2Const.GL_COPY_WRITE_BUFFER, null);
	}

	@Override
	public void writeToTexture(final GpuTexture destination, final ByteBuffer source, final int mipLevel, final int depthOrLayer, final int destX,
			final int destY, final int width, final int height) {
		WebGL2Texture tex = (WebGL2Texture) destination;
		int target;
		if (tex.cubemap) {
			target = WebGL2Const.CUBEMAP_TARGETS[depthOrLayer % 6];
			this.gl.bindTexture(WebGL2Const.GL_TEXTURE_CUBE_MAP, tex.id);
		} else {
			target = WebGL2Const.GL_TEXTURE_2D;
			this.gl.bindTexture(WebGL2Const.GL_TEXTURE_2D, tex.id);
		}
		this.gl.pixelStorei(WebGL2Const.GL_UNPACK_ROW_LENGTH, width);
		this.gl.pixelStorei(WebGL2Const.GL_UNPACK_SKIP_PIXELS, 0);
		this.gl.pixelStorei(WebGL2Const.GL_UNPACK_SKIP_ROWS, 0);
		this.gl.pixelStorei(WebGL2Const.GL_UNPACK_ALIGNMENT, destination.getFormat().componentCount());
		this.gl.texSubImage2D(target, mipLevel, destX, destY, width, height, WebGL2Const.toGlExternalId(destination.getFormat()), 5121, asUint8(source));
	}

	@Override
	public void copyBufferToTexture(final GpuBufferSlice source, final int sourceX, final int sourceY, final int sourceWidth, final int sourceHeight,
			final GpuTexture destination, final int destinationX, final int destinationY, final int copyWidth, final int copyHeight, final int mipLevel,
			final int arrayLayer) {
		WebGL2Texture tex = (WebGL2Texture) destination;
		int target;
		if (tex.cubemap) {
			target = WebGL2Const.CUBEMAP_TARGETS[arrayLayer % 6];
			this.gl.bindTexture(WebGL2Const.GL_TEXTURE_CUBE_MAP, tex.id);
		} else {
			target = WebGL2Const.GL_TEXTURE_2D;
			this.gl.bindTexture(WebGL2Const.GL_TEXTURE_2D, tex.id);
		}
		int texelSize = destination.getFormat().blockSize();
		long skipBytes = (sourceX + (long) sourceY * sourceWidth) * texelSize;
		WebGL2Buffer srcBuf = (WebGL2Buffer) source.buffer();
		this.gl.bindBuffer(WebGL2Const.GL_PIXEL_UNPACK_BUFFER, srcBuf.handle());
		this.gl.pixelStorei(WebGL2Const.GL_UNPACK_ROW_LENGTH, sourceWidth);
		this.gl.pixelStorei(WebGL2Const.GL_UNPACK_IMAGE_HEIGHT, sourceHeight);
		this.gl.pixelStorei(WebGL2Const.GL_UNPACK_SKIP_PIXELS, 0);
		this.gl.pixelStorei(WebGL2Const.GL_UNPACK_SKIP_ROWS, 0);
		this.gl.pixelStorei(WebGL2Const.GL_UNPACK_ALIGNMENT, destination.getFormat().byteAlignment());
		this.gl.texSubImage2D(target, mipLevel, destinationX, destinationY, copyWidth, copyHeight,
				WebGL2Const.toGlExternalId(destination.getFormat()), WebGL2Const.toGlType(destination.getFormat()), (int) (source.offset() + skipBytes));
		this.gl.bindBuffer(WebGL2Const.GL_PIXEL_UNPACK_BUFFER, null);
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel,
			final int x, final int y, final int width, final int height) {
		WebGL2Buffer dst = (WebGL2Buffer) destination;
		dst.checkCanBeUsed();
		this.bindColorDepthFbo(this.readFbo, WebGL2Const.GL_READ_FRAMEBUFFER, List.of((WebGL2Texture) source), null, mipLevel);
		this.gl.bindBuffer(WebGL2Const.GL_PIXEL_PACK_BUFFER, dst.handle());
		this.gl.pixelStorei(WebGL2Const.GL_PACK_ROW_LENGTH, width);
		this.gl.readPixels(x, y, width, height, WebGL2Const.toGlExternalId(source.getFormat()), WebGL2Const.toGlType(source.getFormat()), (int) offset);
		// async completion: schedule the callback behind a fence (like GL)
		RenderSystem.queueFencedTask(callback);
		this.gl.bindBuffer(WebGL2Const.GL_PIXEL_PACK_BUFFER, null);
		this.gl.bindFramebuffer(WebGL2Const.GL_READ_FRAMEBUFFER, null);
	}

	@Override
	public void copyTextureToTexture(final GpuTexture source, final GpuTexture destination, final int mipLevel, final int destX, final int destY,
			final int sourceX, final int sourceY, final int width, final int height) {
		boolean isDepth = source.getFormat().hasDepthAspect();
		WebGL2Texture src = (WebGL2Texture) source;
		WebGL2Texture dst = (WebGL2Texture) destination;
		this.bindColorDepthFbo(this.readFbo, WebGL2Const.GL_READ_FRAMEBUFFER, isDepth ? new ArrayList<>() : List.of(src), isDepth ? src : null, 0);
		this.bindColorDepthFbo(this.drawFbo, WebGL2Const.GL_DRAW_FRAMEBUFFER, isDepth ? new ArrayList<>() : List.of(dst), isDepth ? dst : null, 0);
		int mask = isDepth ? WebGL2Const.GL_DEPTH_BUFFER_BIT : WebGL2Const.GL_COLOR_BUFFER_BIT;
		this.gl.blitFramebuffer(sourceX, sourceY, sourceX + width, sourceY + height, destX, destY, destX + width, destY + height, mask, WebGL2Const.GL_NEAREST);
		this.gl.bindFramebuffer(WebGL2Const.GL_READ_FRAMEBUFFER, null);
		this.gl.bindFramebuffer(WebGL2Const.GL_DRAW_FRAMEBUFFER, null);
	}

	// Present pipeline (built once, shared across the per-frame command encoders —
	// there is exactly one WebGL2 context per client). Upstream
	// GlCommandEncoder.presentTexture blits the offscreen color target into the
	// default framebuffer with glBlitFramebuffer. That silently NO-OPS on
	// ANGLE/SwiftShader when the DRAW target is the default framebuffer (nothing is
	// written to the canvas, and getError() stays 0), which is why the canvas was
	// black despite a colored source and no GL error. We present instead with a
	// fullscreen-triangle draw that samples the color target — an ordinary
	// fragment-pipeline write that reliably lands on fbo 0 (the same approach as
	// EaglercraftGPU's screen copy).
	private static WebGLProgram presentProgram;
	private static WebGLUniformLocation presentTexLoc;
	private static WebGLVertexArrayObject presentVao;
	private static boolean presentPipelineFailed;

	private void ensurePresentPipeline() {
		if (presentProgram != null || presentPipelineFailed) {
			return;
		}
		// gl_VertexID fullscreen triangle: ids 0,1,2 -> clip (-1,-1),(3,-1),(-1,3);
		// v_uv 0..1 across the visible area, origin bottom-left (matches the GL blit
		// orientation, so no vertical flip vs the upstream present).
		final String vsh = "#version 300 es\n"
				+ "precision highp float;\n"
				+ "out vec2 v_uv;\n"
				+ "void main() {\n"
				+ "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
				+ "  v_uv = p;\n"
				+ "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
				+ "}\n";
		final String fsh = "#version 300 es\n"
				+ "precision highp float;\n"
				+ "uniform sampler2D u_tex;\n"
				+ "in vec2 v_uv;\n"
				+ "out vec4 fragColor;\n"
				+ "void main() {\n"
				+ "  fragColor = texture(u_tex, v_uv);\n"
				+ "}\n";
		WebGLShader vs = this.gl.createShader(WebGL2Const.GL_VERTEX_SHADER);
		this.gl.shaderSource(vs, vsh);
		this.gl.compileShader(vs);
		if (!this.gl.getShaderParameterb(vs, WebGL2Const.GL_COMPILE_STATUS)) {
			System.err.println("eagler-present: present VS compile failed: " + this.gl.getShaderInfoLog(vs));
			this.gl.deleteShader(vs);
			presentPipelineFailed = true;
			return;
		}
		WebGLShader fs = this.gl.createShader(WebGL2Const.GL_FRAGMENT_SHADER);
		this.gl.shaderSource(fs, fsh);
		this.gl.compileShader(fs);
		if (!this.gl.getShaderParameterb(fs, WebGL2Const.GL_COMPILE_STATUS)) {
			System.err.println("eagler-present: present FS compile failed: " + this.gl.getShaderInfoLog(fs));
			this.gl.deleteShader(vs);
			this.gl.deleteShader(fs);
			presentPipelineFailed = true;
			return;
		}
		WebGLProgram prog = this.gl.createProgram();
		this.gl.attachShader(prog, vs);
		this.gl.attachShader(prog, fs);
		this.gl.linkProgram(prog);
		if (!this.gl.getProgramParameterb(prog, WebGL2Const.GL_LINK_STATUS)) {
			System.err.println("eagler-present: present program link failed: " + this.gl.getProgramInfoLog(prog));
			this.gl.deleteProgram(prog);
			this.gl.deleteShader(vs);
			this.gl.deleteShader(fs);
			presentPipelineFailed = true;
			return;
		}
		this.gl.deleteShader(vs);
		this.gl.deleteShader(fs);
		presentTexLoc = this.gl.getUniformLocation(prog, "u_tex");
		presentVao = this.gl.createVertexArray();
		presentProgram = prog;
		System.err.println("eagler-present: fullscreen-quad present pipeline ready");
	}

	/** Phase 3.3b present diagnostic: log the first few presents (source + canvas
	 *  center pixels) to confirm the quad lands on fbo 0. Remove once verified. */
	private static int presentDiagCount = 0;

	public void presentTexture(final GpuTextureView textureView, final int swapchainWidth, final int swapchainHeight) {
		int copyWidth = Math.min(swapchainWidth, textureView.getWidth(0));
		int copyHeight = Math.min(swapchainHeight, textureView.getHeight(0));
		this.ensurePresentPipeline();
		WebGL2Texture srcTex = ((WebGL2TextureView) textureView).texture();

		this.gl.bindFramebuffer(WebGL2Const.GL_FRAMEBUFFER, null);
		this.gl.viewport(0, 0, swapchainWidth, swapchainHeight);
		this.gl.disable(WebGL2Const.GL_SCISSOR_TEST);
		this.gl.disable(WebGL2Const.GL_DEPTH_TEST);
		this.gl.disable(WebGL2Const.GL_BLEND);
		this.gl.disable(WebGL2Const.GL_CULL_FACE);
		this.gl.disable(WebGL2Const.GL_STENCIL_TEST);
		this.gl.depthMask(true);
		this.gl.colorMask(true, true, true, true);

		if (presentProgram != null) {
			this.gl.useProgram(presentProgram);
			this.gl.activeTexture(WebGL2Const.GL_TEXTURE0);
			// unbind any sampler object so the texture's own params govern the fetch
			this.gl.bindSampler(0, null);
			this.gl.bindTexture(WebGL2Const.GL_TEXTURE_2D, srcTex.id);
			// the color target's default min filter is NEAREST_MIPMAP_LINEAR, which is
			// sampling-INCOMPLETE with a single mip level (would fetch black). Force a
			// clamped nearest fetch so the present is always complete.
			this.gl.texParameteri(WebGL2Const.GL_TEXTURE_2D, WebGL2Const.GL_TEXTURE_MIN_FILTER, WebGL2Const.GL_NEAREST);
			this.gl.texParameteri(WebGL2Const.GL_TEXTURE_2D, WebGL2Const.GL_TEXTURE_MAG_FILTER, WebGL2Const.GL_NEAREST);
			this.gl.texParameteri(WebGL2Const.GL_TEXTURE_2D, WebGL2Const.GL_TEXTURE_WRAP_S, WebGL2Const.GL_CLAMP_TO_EDGE);
			this.gl.texParameteri(WebGL2Const.GL_TEXTURE_2D, WebGL2Const.GL_TEXTURE_WRAP_T, WebGL2Const.GL_CLAMP_TO_EDGE);
			this.gl.uniform1i(presentTexLoc, 0);
			this.gl.bindVertexArray(presentVao);
			this.gl.drawArrays(WebGL2Const.GL_TRIANGLES, 0, 3);
			this.gl.bindVertexArray(null);
			// present just bound presentProgram + presentVao via untracked GL calls. Invalidate the
			// encoder's cached program/VAO so the next pass's first draw re-binds its own program.
			// Otherwise trySetup() skips useProgram (sees no program change) yet still fires
			// uniform1i(sampler.location(),…) under presentProgram -> "location is not from the
			// associated program", and the sampler keeps a stale texture unit -> corrupt atlas/textures.
			// Both the Wasm-GC and JavaScript targets share this GL state, so both caches
			// must be invalidated before bindVertexArray can safely skip identical binds.
			this.lastProgram = null;
			this.lastVertexArray = null;
		}

		if (WebGL2Surface.titleProbeEnabled && presentDiagCount < 3 && copyWidth > 4 && copyHeight > 4) {
			int errAfter = this.gl.getError();
			Uint8Array fbo0 = Uint8Array.create(4);
			this.gl.bindFramebuffer(WebGL2Const.GL_READ_FRAMEBUFFER, null);
			this.gl.readPixels(swapchainWidth / 2, swapchainHeight / 2, 1, 1, 6408 /*RGBA*/, 5121 /*UBYTE*/, fbo0);
			Uint8Array srcMid = Uint8Array.create(4);
			this.bindColorDepthFbo(this.drawFbo, WebGL2Const.GL_READ_FRAMEBUFFER, List.of(srcTex), null, 0);
			this.gl.readPixels(copyWidth / 2, copyHeight / 2, 1, 1, 6408, 5121, srcMid);
			this.gl.bindFramebuffer(WebGL2Const.GL_READ_FRAMEBUFFER, null);
			System.err.println("eagler-present-diag#" + presentDiagCount + ": quad swap=" + swapchainWidth + "x" + swapchainHeight
					+ " tex=" + textureView.getWidth(0) + "x" + textureView.getHeight(0)
					+ " SRC_center=" + srcMid.get(0) + "," + srcMid.get(1) + "," + srcMid.get(2)
					+ " FBO0_center=" + fbo0.get(0) + "," + fbo0.get(1) + "," + fbo0.get(2)
					+ " progReady=" + (presentProgram != null) + " glErr=" + errAfter);
			presentDiagCount++;
		}
	}

	@Override
	public GpuFence createFence() {
		return new WebGL2Fence(this.gl);
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		((WebGL2QueryPool) pool).writeTimestamp(index);
	}

	// ==== draw path ====

	protected void executeDraw(final WebGL2RenderPass renderPass, final int baseVertex, final int firstIndex, final int drawCount,
			final IndexType indexType, final int instanceCount, final int firstInstance) {
		if (!this.trySetup(renderPass)) {
			return;
		}
		this.lastVertexArray = this.device.vertexArrayCache().bindVertexArray(renderPass.pipeline.info().getVertexFormatBindings(),
				renderPass.vertexBuffers, indexType != null ? baseVertex : 0,
				renderPass.vertexBufferDirty ? null : this.lastVertexArray);
		renderPass.vertexBufferDirty = false;
		this.drawFromBuffers(renderPass, baseVertex, firstIndex, drawCount, indexType, renderPass.pipeline, instanceCount);
	}

	/**
	 * Ported from GlCommandEncoder.executeDrawMultiple — a per-draw CPU loop (NOT a
	 * GPU multi-draw). drawMultipleIndexed is not feature-gated, so it must work on
	 * WebGL2; each element's baseVertex is folded via the VAO path.
	 */
	protected <T> void executeDrawMultiple(final WebGL2RenderPass renderPass, final java.util.Collection<com.mojang.blaze3d.systems.RenderPass.Draw<T>> draws,
			final GpuBuffer defaultIndexBuffer, IndexType defaultIndexType, final T uniformArgument) {
		if (!this.trySetup(renderPass)) {
			return;
		}
		if (defaultIndexType == null) {
			defaultIndexType = IndexType.SHORT;
		}
		for (com.mojang.blaze3d.systems.RenderPass.Draw<T> draw : draws) {
			IndexType indexType = draw.indexType() == null ? defaultIndexType : draw.indexType();
			renderPass.setIndexBuffer(draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(), indexType);
			renderPass.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
			java.util.function.BiConsumer<T, com.mojang.blaze3d.systems.RenderPass.UniformUploader> uploader = draw.uniformUploaderConsumer();
			if (uploader != null) {
				uploader.accept(uniformArgument, (name, buffer) -> {
					WebGL2Uniform u = renderPass.pipeline.program().getUniform(name);
					if (u instanceof WebGL2Uniform.Ubo ubo) {
						bindUbo(ubo, buffer);
					}
				});
			}
			if (renderPass.vertexBufferDirty) {
				this.lastVertexArray = this.device.vertexArrayCache().bindVertexArray(renderPass.pipeline.info().getVertexFormatBindings(),
						renderPass.vertexBuffers, draw.baseVertex(), null);
				renderPass.vertexBufferDirty = false;
			} else {
				this.lastVertexArray = this.device.vertexArrayCache().bindVertexArray(renderPass.pipeline.info().getVertexFormatBindings(),
						renderPass.vertexBuffers, draw.baseVertex(), this.lastVertexArray);
			}
			this.drawFromBuffers(renderPass, draw.baseVertex(), draw.firstIndex(), draw.indexCount(), indexType, renderPass.pipeline, 1);
		}
	}

	private void drawFromBuffers(final WebGL2RenderPass renderPass, final int baseVertex, final int firstIndex, final int drawCount,
			final IndexType indexType, final WebGL2RenderPipeline pipeline, final int instanceCount) {
		++this.drawCallsTotal;
		int mode = WebGL2Const.toGl(pipeline.info().getPrimitiveTopology());
		if (indexType != null) {
			this.gl.bindBuffer(WebGL2Const.GL_ELEMENT_ARRAY_BUFFER, ((WebGL2Buffer) renderPass.indexBuffer).handle());
			// baseVertex is folded into the VAO attribute offsets, so a plain
			// drawElementsInstanced (no baseVertex arg — WebGL2 has none) is correct.
			this.gl.drawElementsInstanced(mode, drawCount, WebGL2Const.toGl(indexType), firstIndex * indexType.bytes, instanceCount);
		} else {
			// Non-indexed draws support firstVertex directly. Folding it into attribute
			// offsets would discard gl_VertexID (sprite interpolation encodes progress
			// there); leave those offsets unshifted and preserve the shader-visible ID.
			this.gl.drawArraysInstanced(mode, baseVertex, drawCount, instanceCount);
		}
	}

	private static final java.util.Set<Long> loggedShortUboBinds = new java.util.HashSet<>();

	// ANGLE needs the whole uniform block, even when MC wrote fewer bytes.
	private void bindUbo(final WebGL2Uniform.Ubo ubo, final GpuBufferSlice slice) {
		final WebGL2Buffer buf = (WebGL2Buffer) slice.buffer();
		final int offset = (int) slice.offset();
		final int sliceLen = (int) slice.length();
		// GL_UNIFORM_BLOCK_DATA_SIZE; fall back to the 16-rounded slice if the query gave 0.
		final int need = ubo.dataSize() > 0 ? ubo.dataSize() : ((sliceLen + 15) & ~15);
		int bindSize = sliceLen;
		if (need > sliceLen) {
			final int room = buf.glCapacity() - offset;
			bindSize = Math.min(need, room);
			final long key = (((long) ubo.blockBinding()) << 40) ^ (((long) sliceLen) << 20) ^ (need & 0xFFFFFL);
			if (bindSize < need && loggedShortUboBinds.size() < 32 && loggedShortUboBinds.add(key)) {
				System.err.println("eagler-webgl2: UBO range too short: binding=" + ubo.blockBinding()
						+ " required=" + need + " available=" + room);
			}
		}
		this.gl.bindBufferRange(WebGL2Const.GL_UNIFORM_BUFFER, ubo.blockBinding(), buf.handle(), offset, bindSize);
	}

	private boolean trySetup(final WebGL2RenderPass renderPass) {
		if (renderPass.pipeline == null || renderPass.pipeline.program() == WebGL2Program.INVALID_PROGRAM) {
			return false;
		}
		RenderPipeline pipeline = renderPass.pipeline.info();
		WebGL2Program glProgram = renderPass.pipeline.program();
		this.applyPipelineState(pipeline);
		boolean differentProgram = this.lastProgram != glProgram;
		if (differentProgram) {
			this.gl.useProgram(glProgram.getProgramId());
			this.lastProgram = glProgram;
		}

		for (Map.Entry<String, WebGL2Uniform> entry : glProgram.getUniforms().entrySet()) {
			String name = entry.getKey();
			boolean isDirty = renderPass.dirtyUniforms.contains(name);
			switch (entry.getValue()) {
				case WebGL2Uniform.Ubo ubo -> {
					// Rebind on program switch too (not just when dirty): UBO binding
					// indices are per-program, so a reused index can otherwise keep a
					// previous program's (smaller) buffer bound.
					if (differentProgram || isDirty) {
						GpuBufferSlice bufferView = renderPass.uniforms.get(name);
						if (bufferView != null) {
							bindUbo(ubo, bufferView);
						}
					}
				}
				case WebGL2Uniform.Sampler sampler -> {
					WebGL2RenderPass.TextureViewAndSampler vs = renderPass.samplers.get(name);
					if (vs != null) {
						if (differentProgram || isDirty) {
							this.gl.uniform1i(sampler.location(), sampler.samplerIndex());
						}
						this.gl.activeTexture(33984 + sampler.samplerIndex());
						WebGL2Texture texture = vs.view().texture();
						int target = texture.cubemap ? WebGL2Const.GL_TEXTURE_CUBE_MAP : WebGL2Const.GL_TEXTURE_2D;
						this.gl.bindTexture(target, texture.id);
						this.gl.bindSampler(sampler.samplerIndex(), vs.sampler().getId());
						this.gl.texParameteri(target, WebGL2Const.GL_TEXTURE_BASE_LEVEL, vs.view().baseMipLevel());
						this.gl.texParameteri(target, WebGL2Const.GL_TEXTURE_MAX_LEVEL, vs.view().baseMipLevel() + vs.view().mipLevels() - 1);
					}
				}
			}
		}

		renderPass.dirtyUniforms.clear();
		this.setupDrawBuffers(renderPass.colorAttachmentCount);
		if (renderPass.isScissorEnabled()) {
			this.gl.enable(WebGL2Const.GL_SCISSOR_TEST);
			this.gl.scissor(renderPass.getScissorX(), renderPass.getScissorY(), renderPass.getScissorWidth(), renderPass.getScissorHeight());
		} else {
			this.gl.disable(WebGL2Const.GL_SCISSOR_TEST);
		}
		return true;
	}

	private void setupDrawBuffers(final int colorAttachmentCount) {
		// The attachment list stays fixed within a pass. Preserve the first setup
		// at its original point, but avoid allocating/marshalling the same list
		// again for each sprite, GUI element and draw batch in that pass.
		if (this.lastDrawBufferCount == colorAttachmentCount) {
			return;
		}
		int[] drawBuffers = new int[colorAttachmentCount];
		for (int i = 0; i < colorAttachmentCount; i++) {
			drawBuffers[i] = WebGL2Const.GL_COLOR_ATTACHMENT0 + i;
		}
		this.gl.drawBuffers(drawBuffers);
		this.lastDrawBufferCount = colorAttachmentCount;
	}

	private void applyPipelineState(final RenderPipeline pipeline) {
		if (this.lastPipeline == pipeline) {
			return;
		}
		this.lastPipeline = pipeline;
		DepthStencilState depthStencilState = pipeline.getDepthStencilState();
		if (depthStencilState != null) {
			this.gl.enable(WebGL2Const.GL_DEPTH_TEST);
			this.gl.depthFunc(WebGL2Const.toGl(depthStencilState.depthTest()));
			this.gl.depthMask(depthStencilState.writeDepth());
			if (depthStencilState.depthBiasConstant() == 0.0F && depthStencilState.depthBiasScaleFactor() == 0.0F) {
				this.gl.disable(WebGL2Const.GL_POLYGON_OFFSET_FILL);
			} else {
				this.gl.polygonOffset(depthStencilState.depthBiasScaleFactor(), depthStencilState.depthBiasConstant());
				this.gl.enable(WebGL2Const.GL_POLYGON_OFFSET_FILL);
			}
		} else {
			this.gl.disable(WebGL2Const.GL_DEPTH_TEST);
			this.gl.depthMask(false);
			this.gl.disable(WebGL2Const.GL_POLYGON_OFFSET_FILL);
		}

		if (pipeline.isCull()) {
			this.gl.enable(WebGL2Const.GL_CULL_FACE);
		} else {
			this.gl.disable(WebGL2Const.GL_CULL_FACE);
		}

		ColorTargetState[] colorTargetStates = pipeline.getColorTargetStates();
		// WebGL2 blend state is global (no per-attachment glEnablei); apply the
		// first active target's blend, matching the pipeline's single-blend rule.
		boolean blendApplied = false;
		for (ColorTargetState state : colorTargetStates) {
			if (state != null && !blendApplied) {
				if (state.blendFunction().isPresent()) {
					this.gl.enable(WebGL2Const.GL_BLEND);
					BlendFunction bf = state.blendFunction().get();
					this.gl.blendFuncSeparate(WebGL2Const.toGl(bf.color().sourceFactor()), WebGL2Const.toGl(bf.color().destFactor()),
							WebGL2Const.toGl(bf.alpha().sourceFactor()), WebGL2Const.toGl(bf.alpha().destFactor()));
					this.gl.blendEquationSeparate(WebGL2Const.toGl(bf.color().op()), WebGL2Const.toGl(bf.alpha().op()));
				} else {
					this.gl.disable(WebGL2Const.GL_BLEND);
				}
				blendApplied = true;
			}
		}
		// glPolygonMode: WebGL2 has none — wireframe is a no-op (recon §3).
		ColorTargetState first = colorTargetStates.length > 0 ? colorTargetStates[0] : null;
		if (first != null) {
			this.gl.colorMask(first.writeRed(), first.writeGreen(), first.writeBlue(), first.writeAlpha());
		}
	}

	@Override
	public void close() {
		if (this.transientMemory != null) {
			this.transientMemory.close();
		}
	}
}

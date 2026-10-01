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

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.webgl.WebGLBuffer;

/**
 * Ported from com.mojang.blaze3d.opengl.GlBuffer, but the CENTRAL GAP (recon §3):
 * WebGL2 has NO buffer mapping. GlBuffer.Direct.map hands out a real mapped
 * pointer; WebGL2 cannot. FIX per the recon: a per-buffer CPU-shadow
 * {@link ByteBuffer}. map() returns the shadow; on close, a WRITE mapping flushes
 * via bufferSubData; a READ mapping first pulls current GPU contents via
 * getBufferSubData. persistentMapping is reported false so the command encoder
 * picks the host-buffer Fallback transient path (WebGL2TransientMemory).
 */
public class WebGL2Buffer extends GpuBuffer {

	private final WebGL2RenderingContextExt gl;
	private final WebGLBuffer handle;
	private final int target;
	/** Actual bytes allocated in GL — equals size() for most buffers, but UNIFORM
	 *  buffers are rounded up to a multiple of 16 so a bindBufferRange can always cover
	 *  the block's std140 (16-byte-rounded) size even when MC only wrote the tight size.
	 *  Without this, a UBO buffer allocated at exactly its data size leaves no room and
	 *  drawArrays/ElementsInstanced raises GL_INVALID_OPERATION on real ANGLE. */
	private final int glCapacity;
	private boolean closed;
	int mappingRefCount;

	public WebGL2Buffer(final WebGL2RenderingContextExt gl, final @GpuBuffer.Usage int usage, final long size, final ByteBuffer initialData) {
		super(usage, initialData != null ? initialData.remaining() : size);
		this.gl = gl;
		this.target = WebGL2Const.selectBufferBindTarget(usage);
		this.handle = gl.createBuffer();
		final int logical = (int) Math.min(initialData != null ? initialData.remaining() : size, Integer.MAX_VALUE);
		this.glCapacity = (usage & 128) != 0 ? ((logical + 15) & ~15) : logical;
		gl.bindBuffer(this.target, this.handle);
		if (initialData != null) {
			if (this.glCapacity > initialData.remaining()) {
				// UBO with tight initial data: allocate the rounded size, then upload.
				gl.bufferData(this.target, this.glCapacity, WebGL2Const.bufferUsageToGlEnum(usage));
				gl.bufferSubData(this.target, 0, Int8Array.fromJavaBuffer(initialData));
			} else {
				gl.bufferData(this.target, Int8Array.fromJavaBuffer(initialData), WebGL2Const.bufferUsageToGlEnum(usage));
			}
		} else {
			gl.bufferData(this.target, this.glCapacity, WebGL2Const.bufferUsageToGlEnum(usage));
		}
		gl.bindBuffer(this.target, null);
	}

	/** GL-allocated capacity (>= size(); UNIFORM buffers rounded up to 16 for std140). */
	public int glCapacity() {
		return this.glCapacity;
	}

	public WebGLBuffer handle() {
		return this.handle;
	}

	public int target() {
		return this.target;
	}

	void checkCanBeUsed() {
		if (this.mappingRefCount != 0) {
			throw new IllegalStateException("Attempt to use buffer while mapped (WebGL2 has no persistent mapping)");
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			if (this.mappingRefCount != 0) {
				throw new IllegalStateException("Attempt to close a mapped buffer");
			}
			this.gl.deleteBuffer(this.handle);
		}
	}

	@Override
	public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
		if (this.closed) {
			throw new IllegalStateException("Buffer already closed");
		}
		if (!read && !write) {
			throw new IllegalArgumentException("At least read or write must be true");
		}
		if (read && (this.usage() & 1) == 0) {
			throw new IllegalStateException("Buffer is not readable");
		}
		if (write && (this.usage() & 2) == 0) {
			throw new IllegalStateException("Buffer is not writable");
		}
		if (offset + length > this.size()) {
			throw new IllegalArgumentException("Cannot map more data than this buffer can hold");
		}
		if (offset > Integer.MAX_VALUE || length > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Mapping buffers larger than 2GB is not supported");
		}

		this.mappingRefCount++;
		// GL buffer memory is LITTLE_ENDIAN across this platform (MemoryUtil.memAlloc /
		// EaglerFakeHeap all set it). MC's writers (e.g. Std140Builder.putFloat) use the
		// buffer's own byte order, so a default BIG_ENDIAN shadow would upload byte-
		// swapped floats — every UBO matrix/color read back as garbage (the black-GUI
		// bug: ModelViewMat came out non-invertible so all geometry collapsed).
		final ByteBuffer shadow = ByteBuffer.allocateDirect((int) length).order(java.nio.ByteOrder.LITTLE_ENDIAN);

		if (read) {
			// pull current GPU contents into the shadow
			this.gl.bindBuffer(this.target, this.handle);
			Int8Array view = Int8Array.fromJavaBuffer(shadow);
			this.gl.getBufferSubData(this.target, (int) offset, view, 0, (int) length);
			this.gl.bindBuffer(this.target, null);
			shadow.position(0);
			shadow.limit((int) length);
		}

		final boolean doFlush = write;
		return new GpuBufferSlice.MappedView(this.slice(offset, length), shadow, new Runnable() {
			private boolean done;

			@Override
			public void run() {
				if (!this.done) {
					this.done = true;
					if (doFlush) {
						shadow.position(0);
						shadow.limit((int) length);
						WebGL2Buffer.this.gl.bindBuffer(WebGL2Buffer.this.target, WebGL2Buffer.this.handle);
						WebGL2Buffer.this.gl.bufferSubData(WebGL2Buffer.this.target, (int) offset, Int8Array.fromJavaBuffer(shadow));
						WebGL2Buffer.this.gl.bindBuffer(WebGL2Buffer.this.target, null);
					}
					WebGL2Buffer.this.mappingRefCount--;
				}
			}
		});
	}
}

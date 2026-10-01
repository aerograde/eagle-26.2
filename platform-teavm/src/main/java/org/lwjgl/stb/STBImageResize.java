package org.lwjgl.stb;

import java.nio.ByteBuffer;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap;

/**
 * Eagler 26.2 Phase 3.3b LWJGL stub (web build only). Real implementation:
 * NativeImage.resizeSubRectTo runs on live paths (e.g. HTTP texture downscale),
 * so a simple bilinear filter over the fake heap replaces stb_image_resize2's
 * "linear" mode. The final int parameter is the stbir_pixel_layout enum, which
 * equals the channel count for the 1..4-channel layouts vanilla uses.
 */
public final class STBImageResize {

	private STBImageResize() {
	}

	public static long nstbir_resize_uint8_linear(final long inPtr, final int inW, final int inH, final int strideIn,
			final long outPtr, final int outW, final int outH, final int strideOut, final int pixelLayout) {
		int comp = Math.max(1, Math.min(4, pixelLayout));
		int inStride = strideIn == 0 ? inW * comp : strideIn;
		int outStride = strideOut == 0 ? outW * comp : strideOut;
		ByteBuffer in = EaglerFakeHeap.resolveBase(inPtr);
		int inOff = EaglerFakeHeap.offsetOf(inPtr);
		ByteBuffer out = EaglerFakeHeap.resolveBase(outPtr);
		int outOff = EaglerFakeHeap.offsetOf(outPtr);
		for (int y = 0; y < outH; ++y) {
			float sy = outH <= 1 ? 0.0F : (y + 0.5F) * inH / outH - 0.5F;
			int y0 = Math.max(0, Math.min(inH - 1, (int) Math.floor(sy)));
			int y1 = Math.min(inH - 1, y0 + 1);
			float fy = Math.max(0.0F, Math.min(1.0F, sy - y0));
			for (int x = 0; x < outW; ++x) {
				float sx = outW <= 1 ? 0.0F : (x + 0.5F) * inW / outW - 0.5F;
				int x0 = Math.max(0, Math.min(inW - 1, (int) Math.floor(sx)));
				int x1 = Math.min(inW - 1, x0 + 1);
				float fx = Math.max(0.0F, Math.min(1.0F, sx - x0));
				for (int c = 0; c < comp; ++c) {
					int p00 = in.get(inOff + y0 * inStride + x0 * comp + c) & 0xFF;
					int p10 = in.get(inOff + y0 * inStride + x1 * comp + c) & 0xFF;
					int p01 = in.get(inOff + y1 * inStride + x0 * comp + c) & 0xFF;
					int p11 = in.get(inOff + y1 * inStride + x1 * comp + c) & 0xFF;
					float top = p00 + (p10 - p00) * fx;
					float bottom = p01 + (p11 - p01) * fx;
					out.put(outOff + y * outStride + x * comp + c, (byte) Math.round(top + (bottom - top) * fy));
				}
			}
		}
		return outPtr;
	}
}

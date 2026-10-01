package org.lwjgl.stb;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.system.MemoryUtil;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerPngDecoder;

/**
 * Eagler 26.2 Phase 3.3b LWJGL stub (web build only; desktop never sees this
 * module). Unlike the pure-linkage stubs this one is REAL: every vanilla texture
 * decodes through NativeImage.read -> stbi_load_from_memory, so it is backed by
 * the pure-Java EaglerPngDecoder (validated against all 3,908 vanilla PNGs) and
 * returns pixels in a tracked fake-heap buffer so memAddress()/free work.
 */
public final class STBImage {

	private static String failureReason = null;

	private STBImage() {
	}

	public static ByteBuffer stbi_load_from_memory(final ByteBuffer buffer, final IntBuffer x, final IntBuffer y,
			final IntBuffer channels_in_file, final int desired_channels) {
		try {
			ByteBuffer dup = buffer.duplicate();
			byte[] data = new byte[dup.remaining()];
			dup.get(data);
			EaglerPngDecoder.Result result = EaglerPngDecoder.decode(data, 0, data.length, desired_channels);
			int comp = desired_channels == 0 ? result.sourceChannels : desired_channels;
			ByteBuffer out = MemoryUtil.memAlloc(result.width * result.height * comp);
			out.put(result.pixels);
			out.flip();
			x.put(0, result.width);
			y.put(0, result.height);
			channels_in_file.put(0, result.sourceChannels);
			return out;
		} catch (Exception e) {
			failureReason = e.getMessage() == null ? e.toString() : e.getMessage();
			return null;
		}
	}

	public static String stbi_failure_reason() {
		return failureReason == null ? "unknown error" : failureReason;
	}

	public static void nstbi_image_free(final long retval_from_stbi_load) {
		MemoryUtil.eaglerFreeAddress(retval_from_stbi_load);
	}
}

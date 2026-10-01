package net.lax1dude.eaglercraft.v1_8.internal;

import java.nio.ByteBuffer;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap;

/**
 * Browser complete-vertex writer. Resolving a fake native address repeatedly
 * dominated entity preparation; both formats now resolve once per vertex.
 */
public final class PlatformVertexWriter {

	private PlatformVertexWriter() {
	}

	public static void writeBlockVertex(long pointer, float x, float y, float z, int color,
			float u, float v, int lightCoords) {
		ByteBuffer buffer = EaglerFakeHeap.resolveBase(pointer);
		byte[] data = buffer.array();
		int offset = buffer.arrayOffset() + EaglerFakeHeap.offsetOf(pointer);
		putFloatLE(data, offset, x);
		putFloatLE(data, offset + 4, y);
		putFloatLE(data, offset + 8, z);
		putIntLE(data, offset + 12, abgr(color));
		putFloatLE(data, offset + 16, u);
		putFloatLE(data, offset + 20, v);
		putIntLE(data, offset + 24, lightCoords);
	}

	public static void writeEntityVertex(long pointer, float x, float y, float z, int color,
			float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
		ByteBuffer buffer = EaglerFakeHeap.resolveBase(pointer);
		byte[] data = buffer.array();
		int offset = buffer.arrayOffset() + EaglerFakeHeap.offsetOf(pointer);
		putFloatLE(data, offset, x);
		putFloatLE(data, offset + 4, y);
		putFloatLE(data, offset + 8, z);
		putIntLE(data, offset + 12, abgr(color));
		putFloatLE(data, offset + 16, u);
		putFloatLE(data, offset + 20, v);
		putIntLE(data, offset + 24, overlayCoords);
		putIntLE(data, offset + 28, lightCoords);
		data[offset + 32] = packedNormal(nx);
		data[offset + 33] = packedNormal(ny);
		data[offset + 34] = packedNormal(nz);
	}

	public static void writeGuiTextVertex(long pointer, float x, float y, float z, int color,
			float u, float v) {
		ByteBuffer buffer = EaglerFakeHeap.resolveBase(pointer);
		byte[] data = buffer.array();
		int offset = buffer.arrayOffset() + EaglerFakeHeap.offsetOf(pointer);
		putFloatLE(data, offset, x);
		putFloatLE(data, offset + 4, y);
		putFloatLE(data, offset + 8, z);
		putFloatLE(data, offset + 12, u);
		putFloatLE(data, offset + 16, v);
		putIntLE(data, offset + 20, abgr(color));
	}

	public static void writeWorldTextVertex(long pointer, float x, float y, float z, int color,
			float u, float v, int lightCoords) {
		ByteBuffer buffer = EaglerFakeHeap.resolveBase(pointer);
		byte[] data = buffer.array();
		int offset = buffer.arrayOffset() + EaglerFakeHeap.offsetOf(pointer);
		putFloatLE(data, offset, x);
		putFloatLE(data, offset + 4, y);
		putFloatLE(data, offset + 8, z);
		putFloatLE(data, offset + 12, u);
		putFloatLE(data, offset + 16, v);
		putIntLE(data, offset + 20, lightCoords);
		putIntLE(data, offset + 24, abgr(color));
	}

	private static void putFloatLE(byte[] data, int offset, float value) {
		putIntLE(data, offset, Float.floatToRawIntBits(value));
	}

	private static void putIntLE(byte[] data, int offset, int value) {
		data[offset] = (byte)value;
		data[offset + 1] = (byte)(value >>> 8);
		data[offset + 2] = (byte)(value >>> 16);
		data[offset + 3] = (byte)(value >>> 24);
	}

	private static int abgr(int argb) {
		return (argb & 0xFF00FF00) | (argb & 0x00FF0000) >>> 16 | (argb & 0x000000FF) << 16;
	}

	private static byte packedNormal(float value) {
		return (byte)((int)(Math.max(-1.0F, Math.min(1.0F, value)) * 127.0F) & 0xFF);
	}
}

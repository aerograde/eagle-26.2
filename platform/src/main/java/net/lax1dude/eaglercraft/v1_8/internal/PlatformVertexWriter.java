package net.lax1dude.eaglercraft.v1_8.internal;

import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * Desktop implementation of the complete-vertex write seam. The web target
 * shadows this class and resolves its fake native heap only once per vertex.
 */
public final class PlatformVertexWriter {

	private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

	private PlatformVertexWriter() {
	}

	public static void writeBlockVertex(long pointer, float x, float y, float z, int color,
			float u, float v, int lightCoords) {
		MemoryUtil.memPutFloat(pointer, x);
		MemoryUtil.memPutFloat(pointer + 4L, y);
		MemoryUtil.memPutFloat(pointer + 8L, z);
		MemoryUtil.memPutInt(pointer + 12L, nativeColor(color));
		MemoryUtil.memPutFloat(pointer + 16L, u);
		MemoryUtil.memPutFloat(pointer + 20L, v);
		putPackedUv(pointer + 24L, lightCoords);
	}

	public static void writeEntityVertex(long pointer, float x, float y, float z, int color,
			float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
		MemoryUtil.memPutFloat(pointer, x);
		MemoryUtil.memPutFloat(pointer + 4L, y);
		MemoryUtil.memPutFloat(pointer + 8L, z);
		MemoryUtil.memPutInt(pointer + 12L, nativeColor(color));
		MemoryUtil.memPutFloat(pointer + 16L, u);
		MemoryUtil.memPutFloat(pointer + 20L, v);
		putPackedUv(pointer + 24L, overlayCoords);
		putPackedUv(pointer + 28L, lightCoords);
		MemoryUtil.memPutByte(pointer + 32L, packedNormal(nx));
		MemoryUtil.memPutByte(pointer + 33L, packedNormal(ny));
		MemoryUtil.memPutByte(pointer + 34L, packedNormal(nz));
	}

	public static void writeGuiTextVertex(long pointer, float x, float y, float z, int color,
			float u, float v) {
		MemoryUtil.memPutFloat(pointer, x);
		MemoryUtil.memPutFloat(pointer + 4L, y);
		MemoryUtil.memPutFloat(pointer + 8L, z);
		MemoryUtil.memPutFloat(pointer + 12L, u);
		MemoryUtil.memPutFloat(pointer + 16L, v);
		MemoryUtil.memPutInt(pointer + 20L, nativeColor(color));
	}

	public static void writeWorldTextVertex(long pointer, float x, float y, float z, int color,
			float u, float v, int lightCoords) {
		MemoryUtil.memPutFloat(pointer, x);
		MemoryUtil.memPutFloat(pointer + 4L, y);
		MemoryUtil.memPutFloat(pointer + 8L, z);
		MemoryUtil.memPutFloat(pointer + 12L, u);
		MemoryUtil.memPutFloat(pointer + 16L, v);
		putPackedUv(pointer + 20L, lightCoords);
		MemoryUtil.memPutInt(pointer + 24L, nativeColor(color));
	}

	private static int nativeColor(int argb) {
		int abgr = (argb & 0xFF00FF00) | (argb & 0x00FF0000) >>> 16 | (argb & 0x000000FF) << 16;
		return LITTLE_ENDIAN ? abgr : Integer.reverseBytes(abgr);
	}

	private static void putPackedUv(long pointer, int packedUv) {
		if (LITTLE_ENDIAN) {
			MemoryUtil.memPutInt(pointer, packedUv);
		} else {
			MemoryUtil.memPutShort(pointer, (short)(packedUv & 65535));
			MemoryUtil.memPutShort(pointer + 2L, (short)(packedUv >>> 16));
		}
	}

	private static byte packedNormal(float value) {
		return (byte)((int)(Math.max(-1.0F, Math.min(1.0F, value)) * 127.0F) & 0xFF);
	}
}

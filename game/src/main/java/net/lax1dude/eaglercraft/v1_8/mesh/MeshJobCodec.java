/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Little-endian byte codec for a chunk-section snapshot.
 *
 * <p>Layout:
 * <pre>
 * Header (fixed 36 B):
 *   u32   magic        = 0x454D4A31 ("EMJ1")
 *   u8    version      = 1
 *   u8    flags        (bit0 AO, bit1 cutoutLeaves, bit2 isDebug, bit3 isRecompile)
 *   u16   reserved     = 0
 *   s32   jobId
 *   s32   sectionX
 *   s32   sectionY
 *   s32   sectionZ
 *   f32   cameraRelX
 *   f32   cameraRelY
 *   f32   cameraRelZ
 *
 * Block-state grid (18³ = 5832 cells, palette + packed indices):
 *   u16   volumeDim    = 18            (sanity marker; must equal VOLUME_DIM)
 *   u16   paletteLen   = P
 *   s32   globalStateIds[P]            (verbatim palette, order preserved)
 *   u8    bitsPerCell  = bitsFor(P)    (= max(0, ceil(log2(P))))
 *   u32   packedLen                    (byte count of the packed index stream)
 *   u8    packedIndices[packedLen]     (P-palette indices, LSB-first bit packing,
 *                                       x-major cell = (x*18 + y)*18 + z)
 *
 * Light (nibble-packed over the same 18³ volume):
 *   u16   lightDim     = 18
 *   u8    blockLightNibbles[2916]
 *   u8    skyLightNibbles[2916]
 *
 * Biome (quart-resolution over the center section):
 *   u8    biomeBlendRadius
 *   u16   biomeQuartDim = 4
 *   s32   biomeQuartIds[64]            (global biome ids)
 *
 * Version 3 tail:
 *   s32   levelMinY
 *   s32   levelHeight
 *   f32   cardinalLighting[6]          (down,up,north,south,west,east)
 *   u16   tintDim         = 18
 *   u8    tintResolverCount = 4
 *   repeat 4 tint resolver rows:
 *     u16 valueCount
 *     u8  presentMask[729]
 *     s32 presentValues[valueCount]    (ascending center-cell order)
 * </pre>
 */
public final class MeshJobCodec {

	/** Little-endian "EMJ1" magic. */
	public static final int MAGIC = 0x454D4A31;

	/** Layout version byte. */
	public static final byte VERSION = 3;

	private static final int HEADER_BYTES = 36;

	private MeshJobCodec() {
	}

	/** Bits needed to index {@code paletteLen} distinct entries (0 when ≤1). */
	static int bitsFor(int paletteLen) {
		if (paletteLen <= 1) {
			return 0;
		}
		return 32 - Integer.numberOfLeadingZeros(paletteLen - 1);
	}

	private static int packedLen(int cells, int bits) {
		if (bits == 0) {
			return 0;
		}
		return (cells * bits + 7) >> 3;
	}

	/**
	 * Serialize a snapshot into a freshly-allocated {@code byte[]} with the exact
	 * layout documented above. Deterministic: identical inputs produce identical
	 * bytes.
	 */
	public static byte[] encode(SectionSnapshot s) {
		int p = s.blockStatePalette.length;
		int bits = bitsFor(p);
		int packed = packedLen(SectionSnapshot.VOLUME_CELLS, bits);

		if (s.blockStateIndices.length != SectionSnapshot.VOLUME_CELLS) {
			throw new IllegalArgumentException("blockStateIndices must be " + SectionSnapshot.VOLUME_CELLS
					+ " cells, was " + s.blockStateIndices.length);
		}
		if (s.blockLightNibbles.length != SectionSnapshot.LIGHT_NIBBLE_BYTES
				|| s.skyLightNibbles.length != SectionSnapshot.LIGHT_NIBBLE_BYTES) {
			throw new IllegalArgumentException("light nibble arrays must be " + SectionSnapshot.LIGHT_NIBBLE_BYTES
					+ " bytes");
		}
		if (s.biomeQuartIds.length != SectionSnapshot.BIOME_QUART_CELLS) {
			throw new IllegalArgumentException("biomeQuartIds must be " + SectionSnapshot.BIOME_QUART_CELLS
					+ " cells, was " + s.biomeQuartIds.length);
		}
		if (s.cardinalLighting == null || s.cardinalLighting.length != 6) {
			throw new IllegalArgumentException("cardinalLighting must be 6 floats");
		}
		boolean sparseTints = s.sparseTintValues != null;
		if (sparseTints) {
			if (s.sparseTintValues.length != SectionSnapshot.TINT_RESOLVER_COUNT) {
				throw new IllegalArgumentException("sparseTintValues must be " + SectionSnapshot.TINT_RESOLVER_COUNT + " rows");
			}
		} else if (s.tints == null || s.tints.length != SectionSnapshot.TINT_RESOLVER_COUNT) {
			throw new IllegalArgumentException("tints must be " + SectionSnapshot.TINT_RESOLVER_COUNT + " rows");
		}
		if (s.tintMask == null || s.tintMask.length != SectionSnapshot.TINT_RESOLVER_COUNT) {
			throw new IllegalArgumentException("tintMask must be " + SectionSnapshot.TINT_RESOLVER_COUNT + " rows");
		}
		int[] tintCounts = new int[SectionSnapshot.TINT_RESOLVER_COUNT];
		for (int r = 0; r < SectionSnapshot.TINT_RESOLVER_COUNT; ++r) {
			if (!sparseTints && (s.tints[r] == null || s.tints[r].length != SectionSnapshot.TINT_CELLS)) {
				throw new IllegalArgumentException("tints[" + r + "] must be " + SectionSnapshot.TINT_CELLS + " cells");
			}
			if (s.tintMask[r] == null || s.tintMask[r].length != SectionSnapshot.TINT_MASK_BYTES) {
				throw new IllegalArgumentException("tintMask[" + r + "] must be " + SectionSnapshot.TINT_MASK_BYTES + " bytes");
			}
			for (int i = 0; i < SectionSnapshot.TINT_MASK_BYTES; ++i) {
				tintCounts[r] += Integer.bitCount(s.tintMask[r][i] & 0xFF);
			}
			if (sparseTints && (s.sparseTintValues[r] == null || s.sparseTintValues[r].length != tintCounts[r])) {
				throw new IllegalArgumentException("sparseTintValues[" + r + "] must be " + tintCounts[r] + " values");
			}
		}

		int total = HEADER_BYTES
				+ 2 + 2 + (p << 2) + 1 + 4 + packed // block grid
				+ 2 + SectionSnapshot.LIGHT_NIBBLE_BYTES + SectionSnapshot.LIGHT_NIBBLE_BYTES // light
				+ 1 + 2 + (SectionSnapshot.BIOME_QUART_CELLS << 2) // biome
				+ 4 + 4 + (6 << 2) // level bounds + cardinal lighting
				+ 2 + 1 + SectionSnapshot.TINT_RESOLVER_COUNT * (2 + SectionSnapshot.TINT_MASK_BYTES); // tint headers/masks
		for (int r = 0; r < SectionSnapshot.TINT_RESOLVER_COUNT; ++r) {
			total += tintCounts[r] << 2;
		}

		ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);

		// header
		buf.putInt(MAGIC);
		buf.put(VERSION);
		buf.put((byte) (s.flags & 0xFF));
		buf.putShort((short) 0); // reserved
		buf.putInt(s.jobId);
		buf.putInt(s.sectionX);
		buf.putInt(s.sectionY);
		buf.putInt(s.sectionZ);
		buf.putFloat(s.cameraRelX);
		buf.putFloat(s.cameraRelY);
		buf.putFloat(s.cameraRelZ);

		// block-state grid
		buf.putShort((short) SectionSnapshot.VOLUME_DIM);
		buf.putShort((short) p);
		for (int i = 0; i < p; ++i) {
			buf.putInt(s.blockStatePalette[i]);
		}
		buf.put((byte) bits);
		buf.putInt(packed);
		if (packed > 0) {
			packIndicesInto(buf, s.blockStateIndices, bits);
		}

		// light
		buf.putShort((short) SectionSnapshot.VOLUME_DIM);
		buf.put(s.blockLightNibbles);
		buf.put(s.skyLightNibbles);

		// biome
		buf.put((byte) (s.biomeBlendRadius & 0xFF));
		buf.putShort((short) SectionSnapshot.BIOME_QUART_DIM);
		for (int i = 0; i < SectionSnapshot.BIOME_QUART_CELLS; ++i) {
			buf.putInt(s.biomeQuartIds[i]);
		}

		// Version 3 fields.
		buf.putInt(s.levelMinY);
		buf.putInt(s.levelHeight);
		for (int i = 0; i < 6; ++i) {
			buf.putFloat(s.cardinalLighting[i]);
		}
		buf.putShort((short) SectionSnapshot.TINT_DIM);
		buf.put((byte) SectionSnapshot.TINT_RESOLVER_COUNT);
		for (int r = 0; r < SectionSnapshot.TINT_RESOLVER_COUNT; ++r) {
			int[] row = sparseTints ? s.sparseTintValues[r] : s.tints[r];
			byte[] mask = s.tintMask[r];
			buf.putShort((short) tintCounts[r]);
			buf.put(mask);
			int next = 0;
			for (int i = 0; i < SectionSnapshot.TINT_CELLS; ++i) {
				if ((mask[i >> 3] & (1 << (i & 7))) != 0) {
					buf.putInt(sparseTints ? row[next++] : row[i]);
				}
			}
		}

		return buf.array();
	}

	/**
	 * Mirror-image of {@link #encode(SectionSnapshot)}: reconstruct the snapshot
	 * from its bytes. Throws {@link IllegalStateException} on a magic/version/dim
	 * mismatch or truncation.
	 */
	public static SectionSnapshot decode(byte[] data) {
		ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		SectionSnapshot s = new SectionSnapshot();

		// header
		int magic = buf.getInt();
		if (magic != MAGIC) {
			throw new IllegalStateException("bad mesh-job magic 0x" + Integer.toHexString(magic));
		}
		int version = buf.get() & 0xFF;
		if (version != (VERSION & 0xFF)) {
			throw new IllegalStateException("unsupported mesh-job version " + version);
		}
		s.flags = buf.get() & 0xFF;
		buf.getShort(); // reserved
		s.jobId = buf.getInt();
		s.sectionX = buf.getInt();
		s.sectionY = buf.getInt();
		s.sectionZ = buf.getInt();
		s.cameraRelX = buf.getFloat();
		s.cameraRelY = buf.getFloat();
		s.cameraRelZ = buf.getFloat();

		// block-state grid
		int volDim = buf.getShort() & 0xFFFF;
		if (volDim != SectionSnapshot.VOLUME_DIM) {
			throw new IllegalStateException("bad block volume dim " + volDim);
		}
		int p = buf.getShort() & 0xFFFF;
		int[] palette = new int[p];
		for (int i = 0; i < p; ++i) {
			palette[i] = buf.getInt();
		}
		s.blockStatePalette = palette;
		int bits = buf.get() & 0xFF;
		int packed = buf.getInt();
		s.blockStateIndices = unpackIndicesFrom(buf, SectionSnapshot.VOLUME_CELLS, bits, packed);

		// light
		int lightDim = buf.getShort() & 0xFFFF;
		if (lightDim != SectionSnapshot.VOLUME_DIM) {
			throw new IllegalStateException("bad light volume dim " + lightDim);
		}
		s.blockLightNibbles = new byte[SectionSnapshot.LIGHT_NIBBLE_BYTES];
		buf.get(s.blockLightNibbles);
		s.skyLightNibbles = new byte[SectionSnapshot.LIGHT_NIBBLE_BYTES];
		buf.get(s.skyLightNibbles);

		// biome
		s.biomeBlendRadius = buf.get() & 0xFF;
		int biomeDim = buf.getShort() & 0xFFFF;
		if (biomeDim != SectionSnapshot.BIOME_QUART_DIM) {
			throw new IllegalStateException("bad biome quart dim " + biomeDim);
		}
		int[] biome = new int[SectionSnapshot.BIOME_QUART_CELLS];
		for (int i = 0; i < SectionSnapshot.BIOME_QUART_CELLS; ++i) {
			biome[i] = buf.getInt();
		}
		s.biomeQuartIds = biome;

		// Version 3 fields.
		s.levelMinY = buf.getInt();
		s.levelHeight = buf.getInt();
		float[] cardinal = new float[6];
		for (int i = 0; i < 6; ++i) {
			cardinal[i] = buf.getFloat();
		}
		s.cardinalLighting = cardinal;
		int tintDim = buf.getShort() & 0xFFFF;
		if (tintDim != SectionSnapshot.TINT_DIM) {
			throw new IllegalStateException("bad tint dim " + tintDim);
		}
		int tintResolvers = buf.get() & 0xFF;
		if (tintResolvers != SectionSnapshot.TINT_RESOLVER_COUNT) {
			throw new IllegalStateException("bad tint resolver count " + tintResolvers);
		}
		int[][] tints = new int[SectionSnapshot.TINT_RESOLVER_COUNT][];
		byte[][] tintMask = new byte[SectionSnapshot.TINT_RESOLVER_COUNT][];
		for (int r = 0; r < SectionSnapshot.TINT_RESOLVER_COUNT; ++r) {
			int valueCount = buf.getShort() & 0xFFFF;
			byte[] mask = new byte[SectionSnapshot.TINT_MASK_BYTES];
			buf.get(mask);
			int actualCount = 0;
			for (int i = 0; i < mask.length; ++i) {
				actualCount += Integer.bitCount(mask[i] & 0xFF);
			}
			if (valueCount != actualCount) {
				throw new IllegalStateException("bad tint value count " + valueCount + ", mask has " + actualCount);
			}
			int[] row = new int[SectionSnapshot.TINT_CELLS];
			for (int i = 0; i < SectionSnapshot.TINT_CELLS; ++i) {
				row[i] = (mask[i >> 3] & (1 << (i & 7))) != 0 ? buf.getInt() : -1;
			}
			tints[r] = row;
			tintMask[r] = mask;
		}
		s.tintMask = tintMask;
		s.tints = tints;

		return s;
	}

	/**
	 * LSB-first bit packing of {@code indices} at {@code bits} bits each into
	 * {@code outLen} bytes. Unused high bits of the final byte are zero. Symmetric
	 * with {@link #unpackIndices(byte[], int, int)}.
	 */
	static byte[] packIndices(int[] indices, int bits, int outLen) {
		byte[] out = new byte[outLen];
		packIndicesInto(ByteBuffer.wrap(out), indices, bits);
		return out;
	}

	/** Packs whole palette values through a bit accumulator, directly into the final
	 *  job buffer. This removes the old temporary packed byte[] and per-bit loop. */
	private static void packIndicesInto(ByteBuffer out, int[] indices, int bits) {
		if (bits == 0) return;
		long accumulator = 0L;
		int accumulatorBits = 0;
		long mask = (1L << bits) - 1L;
		for (int value : indices) {
			accumulator |= ((long)value & mask) << accumulatorBits;
			accumulatorBits += bits;
			while (accumulatorBits >= 8) {
				out.put((byte)(accumulator & 0xFFL));
				accumulator >>>= 8;
				accumulatorBits -= 8;
			}
		}
		if (accumulatorBits > 0) out.put((byte)(accumulator & 0xFFL));
	}

	/** Mirror of {@link #packIndices(int[], int, int)}. */
	static int[] unpackIndices(byte[] data, int count, int bits) {
		return unpackIndicesFrom(ByteBuffer.wrap(data), count, bits, data.length);
	}

	private static int[] unpackIndicesFrom(ByteBuffer in, int count, int bits, int byteLength) {
		int[] out = new int[count];
		if (bits == 0) return out;
		long accumulator = 0L;
		int accumulatorBits = 0;
		int remaining = byteLength;
		long mask = (1L << bits) - 1L;
		for (int i = 0; i < count; ++i) {
			while (accumulatorBits < bits && remaining > 0) {
				accumulator |= (long)(in.get() & 0xFF) << accumulatorBits;
				accumulatorBits += 8;
				--remaining;
			}
			out[i] = (int)(accumulator & mask);
			accumulator >>>= bits;
			accumulatorBits -= bits;
		}
		while (remaining-- > 0) in.get();
		return out;
	}

}

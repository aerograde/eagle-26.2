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

/**
 * Registry-free inputs for one chunk-section compile job.
 * The 18-cube volume includes the one-block ambient-occlusion border.
 */
public final class SectionSnapshot {
	SectionSnapshotBuilder.Scratch scratchOwner;

	/** Neighbor-inclusive volume edge length (16 + 2 for the ±1 AO ring). */
	public static final int VOLUME_DIM = 18;

	/** Total cells in the 18³ block-state / light volume (= 5832). */
	public static final int VOLUME_CELLS = VOLUME_DIM * VOLUME_DIM * VOLUME_DIM;

	/** Nibble-packed light array size for {@link #VOLUME_CELLS} cells (= 2916). */
	public static final int LIGHT_NIBBLE_BYTES = VOLUME_CELLS / 2;

	/** Biome quart-resolution edge length for the center section (4³ = 64). */
	public static final int BIOME_QUART_DIM = 4;

	/** Biome quart cell count (= 64). */
	public static final int BIOME_QUART_CELLS = BIOME_QUART_DIM * BIOME_QUART_DIM * BIOME_QUART_DIM;

	/** Tint volume edge length, including the same one-block neighbor ring as states. */
	public static final int TINT_DIM = VOLUME_DIM;

	/** Tint cells per resolver (= 5832, sparsely serialized). */
	public static final int TINT_CELLS = TINT_DIM * TINT_DIM * TINT_DIM;

	/** Presence bitmap bytes for one sparse tint row (= 512). */
	public static final int TINT_MASK_BYTES = (TINT_CELLS + 7) >> 3;

	/** Number of precomputed tint resolvers: grass, foliage, dry-foliage, water. */
	public static final int TINT_RESOLVER_COUNT = 4;

	/** {@link #tints} row indices; must match the worker resolver mapping. */
	public static final int TINT_GRASS = 0;
	public static final int TINT_FOLIAGE = 1;
	public static final int TINT_DRY_FOLIAGE = 2;
	public static final int TINT_WATER = 3;

	// Header
	/** Monotonic job id used for cancellation and matching. */
	public int jobId;
	/** Center section position (in section coords). */
	public int sectionX;
	public int sectionY;
	public int sectionZ;
	/** camera - sectionPos.minBlock, precomputed main-side for VertexSorting. */
	public float cameraRelX;
	public float cameraRelY;
	public float cameraRelZ;
	/** bit0 ambientOcclusion, bit1 cutoutLeaves, bit2 isDebug, bit3 isRecompile. */
	public int flags;

	// Block-state grid
	/** Distinct global {@code Block.BLOCK_STATE_REGISTRY} ids for this volume. */
	public int[] blockStatePalette;
	/** {@link #VOLUME_CELLS} palette indices, x-major. */
	public int[] blockStateIndices;

	// Nibble-packed light
	/** {@link #LIGHT_NIBBLE_BYTES} bytes of block-light nibbles. */
	public byte[] blockLightNibbles;
	/** {@link #LIGHT_NIBBLE_BYTES} bytes of sky-light nibbles. */
	public byte[] skyLightNibbles;

	// Biome and tint
	/** biomeBlendRadius the tint was averaged with (0 on the low-end profile). */
	public int biomeBlendRadius;
	/** {@link #BIOME_QUART_CELLS} global biome ids at quart resolution. */
	public int[] biomeQuartIds;

	// Codec v2
	/** Level minimum block Y (LevelHeightAccessor.getMinY). */
	public int levelMinY;
	/** Level total build height (LevelHeightAccessor.getHeight). */
	public int levelHeight;
	/** Per-level directional shading constant, order {down,up,north,south,west,east}
	 *  (matches CardinalLighting record field order). Length 6. */
	public float[] cardinalLighting;
	/** Presence bitmap for {@link #tints}; only colors a block model can request
	 *  are captured and sent to the worker. */
	public byte[][] tintMask;
	/** Precomputed block tints. Cells absent from {@link #tintMask} are not serialized. */
	public int[][] tints;
	/** Optional values in ascending set-bit order, used by the main-thread encoder. */
	public int[][] sparseTintValues;

}

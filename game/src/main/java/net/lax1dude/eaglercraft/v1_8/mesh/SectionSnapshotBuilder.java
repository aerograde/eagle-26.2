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

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluids;

/**
 * Captures the live section data required by an isolated mesh worker.
 */
public final class SectionSnapshotBuilder {

	private static final ColorResolver[] TINT_RESOLVERS = new ColorResolver[] {
			BiomeColors.GRASS_COLOR_RESOLVER,
			BiomeColors.FOLIAGE_COLOR_RESOLVER,
			BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER,
			BiomeColors.WATER_COLOR_RESOLVER
	};
	private static final int SCRATCH_POOL_CAPACITY = 2;
	private static final int[] EMPTY_TINTS = new int[0];
	private static final ArrayDeque<Scratch> SCRATCH_POOL = new ArrayDeque<>(SCRATCH_POOL_CAPACITY);
	private static long scratchCreated;
	private static long scratchReused;
	private static long scratchDropped;
	private static long scratchCleared;
	private static long scratchReusedBytes;

	static final class Scratch {
		static final int FIXED_BYTES = SectionSnapshot.VOLUME_CELLS * Integer.BYTES
				+ SectionSnapshot.LIGHT_NIBBLE_BYTES * 2
				+ SectionSnapshot.TINT_MASK_BYTES * SectionSnapshot.TINT_RESOLVER_COUNT
				+ SectionSnapshot.BIOME_QUART_CELLS * Integer.BYTES + 6 * Float.BYTES;

		final SectionSnapshot snapshot = new SectionSnapshot();
		final int[] indices = new int[SectionSnapshot.VOLUME_CELLS];
		final byte[] blockLight = new byte[SectionSnapshot.LIGHT_NIBBLE_BYTES];
		final byte[] skyLight = new byte[SectionSnapshot.LIGHT_NIBBLE_BYTES];
		final byte[][] tintMask = new byte[SectionSnapshot.TINT_RESOLVER_COUNT][SectionSnapshot.TINT_MASK_BYTES];
		final int[] biomeQuartIds = new int[SectionSnapshot.BIOME_QUART_CELLS];
		final float[] cardinalLighting = new float[6];
		final int[][] sparseTints = new int[SectionSnapshot.TINT_RESOLVER_COUNT][];
		final Int2IntOpenHashMap paletteMap = new Int2IntOpenHashMap();
		final IntArrayList paletteList = new IntArrayList();
		final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		Scratch() {
			this.paletteMap.defaultReturnValue(-1);
		}

		SectionSnapshot prepare() {
			Arrays.fill(this.blockLight, (byte) 0);
			Arrays.fill(this.skyLight, (byte) 0);
			for (byte[] mask : this.tintMask) {
				Arrays.fill(mask, (byte) 0);
			}
			Arrays.fill(this.biomeQuartIds, 0);
			Arrays.fill(this.sparseTints, null);
			this.paletteMap.clear();
			this.paletteList.clear();
			SectionSnapshot s = this.snapshot;
			s.scratchOwner = this;
			s.blockStatePalette = null;
			s.blockStateIndices = this.indices;
			s.blockLightNibbles = this.blockLight;
			s.skyLightNibbles = this.skyLight;
			s.biomeQuartIds = this.biomeQuartIds;
			s.cardinalLighting = this.cardinalLighting;
			s.tintMask = this.tintMask;
			s.tints = null;
			s.sparseTintValues = this.sparseTints;
			return s;
		}
	}

	private SectionSnapshotBuilder() {
	}

	private static Scratch acquireScratch() {
		synchronized (SCRATCH_POOL) {
			Scratch scratch = SCRATCH_POOL.pollFirst();
			if (scratch != null) {
				++scratchReused;
				scratchReusedBytes += Scratch.FIXED_BYTES;
				return scratch;
			}
			++scratchCreated;
			return new Scratch();
		}
	}

	public static void release(final SectionSnapshot snapshot) {
		if (snapshot == null || snapshot.scratchOwner == null) {
			return;
		}
		Scratch scratch = snapshot.scratchOwner;
		snapshot.scratchOwner = null;
		snapshot.blockStatePalette = null;
		Arrays.fill(scratch.sparseTints, null);
		synchronized (SCRATCH_POOL) {
			if (SCRATCH_POOL.size() < SCRATCH_POOL_CAPACITY) {
				SCRATCH_POOL.addFirst(scratch);
			} else {
				++scratchDropped;
			}
		}
	}

	public static void clearScratchPool() {
		synchronized (SCRATCH_POOL) {
			scratchCleared += SCRATCH_POOL.size();
			SCRATCH_POOL.clear();
		}
	}

	public static void fillScratchStats(final long[] stats) {
		if (stats == null || stats.length < 6) {
			throw new IllegalArgumentException("scratch stats array must have 6 entries");
		}
		synchronized (SCRATCH_POOL) {
			stats[0] = scratchCreated;
			stats[1] = scratchReused;
			stats[2] = SCRATCH_POOL.size();
			stats[3] = scratchDropped;
			stats[4] = scratchCleared;
			stats[5] = scratchReusedBytes;
		}
	}

	/** True when the section must stay inline for fluid rendering. */
	public static boolean sectionHasFluid(final BlockAndTintGetter region, final int sectionX, final int sectionY,
			final int sectionZ) {
		if (region instanceof RenderSectionRegion) {
			return ((RenderSectionRegion) region).sectionHasFluid(sectionX, sectionY, sectionZ);
		}
		int minBlockX = sectionX << 4;
		int minBlockY = sectionY << 4;
		int minBlockZ = sectionZ << 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = 0; x < 16; ++x) {
			for (int y = 0; y < 16; ++y) {
				for (int z = 0; z < 16; ++z) {
					pos.set(minBlockX + x, minBlockY + y, minBlockZ + z);
					BlockState state = region.getBlockState(pos);
					if (!state.isAir() && !state.getFluidState().isEmpty()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static SectionSnapshot build(final BlockAndTintGetter region, final int sectionX, final int sectionY,
			final int sectionZ, final boolean ambientOcclusion, final boolean cutoutLeaves, final boolean isDebug,
			final boolean isRecompile, final double cameraX, final double cameraY, final double cameraZ) {
		Scratch scratch = acquireScratch();
		SectionSnapshot s = scratch.prepare();
		try {
		s.jobId = 0;
		s.sectionX = sectionX;
		s.sectionY = sectionY;
		s.sectionZ = sectionZ;

		int minBlockX = sectionX << 4;
		int minBlockY = sectionY << 4;
		int minBlockZ = sectionZ << 4;
		s.cameraRelX = (float) (cameraX - minBlockX);
		s.cameraRelY = (float) (cameraY - minBlockY);
		s.cameraRelZ = (float) (cameraZ - minBlockZ);

		s.flags = (ambientOcclusion ? 1 : 0) | (cutoutLeaves ? 2 : 0) | (isDebug ? 4 : 0) | (isRecompile ? 8 : 0);

		BlockPos.MutableBlockPos pos = scratch.pos;

		// Block states and light over the neighbor-inclusive volume.
		int volOriginX = minBlockX - 1;
		int volOriginY = minBlockY - 1;
		int volOriginZ = minBlockZ - 1;
		int[] indices = scratch.indices;
		Int2IntOpenHashMap paletteMap = scratch.paletteMap;
		IntArrayList paletteList = scratch.paletteList;
			byte[] blockLight = scratch.blockLight;
			byte[] skyLight = scratch.skyLight;
			byte[][] tintMask = scratch.tintMask;
			BlockColors blockColors = Minecraft.getInstance().getBlockColors();
		RenderSectionRegion renderRegion = region instanceof RenderSectionRegion ? (RenderSectionRegion) region : null;
		LevelLightEngine lightEngine = region.getLightEngine();
		LayerLightEventListener blockLightReader = lightEngine.getLayerListener(LightLayer.BLOCK);
		LayerLightEventListener skyLightReader = lightEngine.getLayerListener(LightLayer.SKY);

		int cell = 0;
		for (int x = 0; x < SectionSnapshot.VOLUME_DIM; ++x) {
			for (int y = 0; y < SectionSnapshot.VOLUME_DIM; ++y) {
				for (int z = 0; z < SectionSnapshot.VOLUME_DIM; ++z, ++cell) {
					pos.set(volOriginX + x, volOriginY + y, volOriginZ + z);
					BlockState state = renderRegion != null
							? renderRegion.getBlockState(volOriginX + x, volOriginY + y, volOriginZ + z)
							: region.getBlockState(pos);
					int id = Block.getId(state);
					int pi = paletteMap.get(id);
					if (pi < 0) {
						pi = paletteList.size();
						paletteMap.put(id, pi);
						paletteList.add(id);
					}
					indices[cell] = pi;
					if (!state.isAir() && x > 0 && x < 17 && y > 0 && y < 17 && z > 0 && z < 17) {
						List<BlockTintSource> sources = blockColors.getTintSources(state);
						for (int i = 0; i < sources.size(); ++i) {
							BlockTintSource source = sources.get(i);
								int row = resolverRow(source.biomeColorResolver());
								int tintY = y + source.biomeColorYOffset(state);
								if (row >= 0 && tintY >= 0 && tintY < SectionSnapshot.TINT_DIM) {
									int tintCell = (x * SectionSnapshot.TINT_DIM + tintY) * SectionSnapshot.TINT_DIM + z;
									markTintCell(tintMask[row], tintCell);
								}
							}
							if (state.getFluidState().getType().isSame(Fluids.WATER)) {
								markTintCell(tintMask[SectionSnapshot.TINT_WATER], cell);
							}
					}

					int bl = blockLightReader.getLightValue(pos) & 0x0F;
					int sl = skyLightReader.getLightValue(pos) & 0x0F;
					int nb = cell >> 1;
					if ((cell & 1) == 0) {
						blockLight[nb] |= (byte) bl;
						skyLight[nb] |= (byte) sl;
					} else {
						blockLight[nb] |= (byte) (bl << 4);
						skyLight[nb] |= (byte) (sl << 4);
					}
				}
			}
		}

		s.blockStatePalette = paletteList.toIntArray();
		s.blockStateIndices = indices;
		s.blockLightNibbles = blockLight;
		s.skyLightNibbles = skyLight;

		// Biome fields retained by the codec.
		s.biomeBlendRadius = 0;
		s.biomeQuartIds = scratch.biomeQuartIds;

		// Level bounds and cardinal lighting.
		s.levelMinY = region.getMinY();
		s.levelHeight = region.getHeight();
		CardinalLighting cl = region.cardinalLighting();
		s.cardinalLighting = scratch.cardinalLighting;
		s.cardinalLighting[0] = cl.down();
		s.cardinalLighting[1] = cl.up();
		s.cardinalLighting[2] = cl.north();
		s.cardinalLighting[3] = cl.south();
		s.cardinalLighting[4] = cl.west();
		s.cardinalLighting[5] = cl.east();

		// Capture only tint cells referenced by block models.
		int[][] sparseTints = scratch.sparseTints;
		for (int r = 0; r < SectionSnapshot.TINT_RESOLVER_COUNT; ++r) {
			sparseTints[r] = captureTintValues(tintMask[r], region, pos, TINT_RESOLVERS[r], volOriginX, volOriginY, volOriginZ);
		}
		s.tintMask = tintMask;
		s.sparseTintValues = sparseTints;

			return s;
		} catch (Throwable failure) {
			release(s);
			throw failure;
		}
		}

	static int[] captureTintValues(final byte[] mask, final BlockAndTintGetter region, final BlockPos.MutableBlockPos pos,
			final ColorResolver resolver, final int volOriginX, final int volOriginY, final int volOriginZ) {
		int count = 0;
		for (int i = 0; i < mask.length; ++i) {
			count += Integer.bitCount(mask[i] & 0xFF);
		}
		if (count == 0) {
			return EMPTY_TINTS;
		}
		int[] row = new int[count];
		int next = 0;
		// The mask is sparse (and often entirely empty for stone/building
		// sections). Visit set bits in codec order instead of testing all
		// 5,832 cells again for each resolver on the client thread.
		for (int byteIndex = 0; byteIndex < mask.length; ++byteIndex) {
			int bits = mask[byteIndex] & 0xFF;
			while (bits != 0) {
				int tc = (byteIndex << 3) + Integer.numberOfTrailingZeros(bits);
				int z = tc % SectionSnapshot.TINT_DIM;
				int y = (tc / SectionSnapshot.TINT_DIM) % SectionSnapshot.TINT_DIM;
				int x = tc / (SectionSnapshot.TINT_DIM * SectionSnapshot.TINT_DIM);
				pos.set(volOriginX + x, volOriginY + y, volOriginZ + z);
				row[next++] = region.getBlockTint(pos, resolver);
				bits &= bits - 1;
			}
		}
		return row;
	}

		private static void markTintCell(final byte[] mask, final int cell) {
			int byteIndex = cell >> 3;
			int bit = 1 << (cell & 7);
			if ((mask[byteIndex] & bit) == 0) {
				mask[byteIndex] |= (byte) bit;
			}
		}

		private static int resolverRow(final ColorResolver resolver) {
		for (int i = 0; i < TINT_RESOLVERS.length; ++i) {
			if (resolver == TINT_RESOLVERS[i]) {
				return i;
			}
		}
		return -1;
	}
}

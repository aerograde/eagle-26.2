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

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * Snapshot-backed {@link BlockAndTintGetter} for mesh workers.
 */
public final class WorkerRenderSectionRegion implements BlockAndTintGetter {

	private final SectionSnapshot snap;
	private final BlockState air;
	private final CardinalLighting cardinal;
	/** World coord of the [-1] edge of the 18³ volume, per axis. */
	private final int volOriginX;
	private final int volOriginY;
	private final int volOriginZ;
	public WorkerRenderSectionRegion(final SectionSnapshot snap) {
		this.snap = snap;
		this.air = Blocks.AIR.defaultBlockState();
		float[] c = snap.cardinalLighting;
		this.cardinal = new CardinalLighting(c[0], c[1], c[2], c[3], c[4], c[5]);
		this.volOriginX = (snap.sectionX << 4) - 1;
		this.volOriginY = (snap.sectionY << 4) - 1;
		this.volOriginZ = (snap.sectionZ << 4) - 1;
	}

	/** 18³ volume cell for a world pos, or -1 if outside the snapshot's neighbor range. */
	private int volCell(final int wx, final int wy, final int wz) {
		int x = wx - this.volOriginX;
		int y = wy - this.volOriginY;
		int z = wz - this.volOriginZ;
		if (x < 0 || y < 0 || z < 0 || x >= SectionSnapshot.VOLUME_DIM || y >= SectionSnapshot.VOLUME_DIM
				|| z >= SectionSnapshot.VOLUME_DIM) {
			return -1;
		}
		return (x * SectionSnapshot.VOLUME_DIM + y) * SectionSnapshot.VOLUME_DIM + z;
	}

	@Override
	public BlockState getBlockState(final BlockPos pos) {
		int cell = volCell(pos.getX(), pos.getY(), pos.getZ());
		if (cell < 0) {
			return this.air;
		}
		int globalId = this.snap.blockStatePalette[this.snap.blockStateIndices[cell]];
		BlockState state = Block.stateById(globalId);
		return state == null ? this.air : state;
	}

	@Override
	public FluidState getFluidState(final BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(final BlockPos pos) {
		return null; // Block entities are handled on the main thread.
	}

	@Override
	public int getBrightness(final LightLayer layer, final BlockPos pos) {
		int cell = volCell(pos.getX(), pos.getY(), pos.getZ());
		if (cell < 0) {
			return 0;
		}
		byte[] nibbles = (layer == LightLayer.SKY) ? this.snap.skyLightNibbles : this.snap.blockLightNibbles;
		int b = nibbles[cell >> 1] & 0xFF;
		return (cell & 1) == 0 ? (b & 0x0F) : (b >> 4);
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return LevelLightEngine.EMPTY; // unused: light is served via getBrightness override
	}

	@Override
	public CardinalLighting cardinalLighting() {
		return this.cardinal;
	}

	@Override
	public int getBlockTint(final BlockPos pos, final ColorResolver resolver) {
		int row = resolverRow(resolver);
		if (row < 0) {
			return -1;
		}
		int rx = pos.getX() - this.volOriginX;
		int ry = pos.getY() - this.volOriginY;
		int rz = pos.getZ() - this.volOriginZ;
		if (rx < 0 || ry < 0 || rz < 0 || rx >= SectionSnapshot.TINT_DIM || ry >= SectionSnapshot.TINT_DIM
				|| rz >= SectionSnapshot.TINT_DIM) {
			return -1;
		}
		int cell = (rx * SectionSnapshot.TINT_DIM + ry) * SectionSnapshot.TINT_DIM + rz;
		return this.snap.tints[row][cell];
	}

	private static int resolverRow(final ColorResolver resolver) {
		if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
			return SectionSnapshot.TINT_GRASS;
		}
		if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
			return SectionSnapshot.TINT_FOLIAGE;
		}
		if (resolver == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER) {
			return SectionSnapshot.TINT_DRY_FOLIAGE;
		}
		if (resolver == BiomeColors.WATER_COLOR_RESOLVER) {
			return SectionSnapshot.TINT_WATER;
		}
		return -1;
	}

	@Override
	public int getMinY() {
		return this.snap.levelMinY;
	}

	@Override
	public int getHeight() {
		return this.snap.levelHeight;
	}
}

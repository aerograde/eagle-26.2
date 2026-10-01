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

import java.util.Map;

import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.SharedConstants;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;

/**
 * Pure-Java section compiler used inside mesh workers.
 */
public final class MeshWorkerCompiler {

	private BlockStateModelSet modelSet;
	/** Persistent model map shared with the long-lived compiler. */
	private final java.util.HashMap<net.minecraft.world.level.block.state.BlockState,
		net.minecraft.client.renderer.block.dispatch.BlockStateModel> modelMap = new java.util.HashMap<>(1 << 12);
	private BlockColors blockColors;
	private FluidStateModelSet fluidModelSet;
	private SectionCompiler compiler;
	private boolean compilerAo;
	private boolean compilerCutout;
	private final SectionBufferBuilderPack buffers = new SectionBufferBuilderPack();

	/** Job id of the most recently compiled snapshot. */
	public int lastJobId;

	/** Populates the registries required before model-table decoding. Idempotent. */
	public static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrapSlim();
	}

	/** Replaces the model table and invalidates the cached compiler. */
	public void setModelTable(final byte[] tableBytes) {
		// A base table resets the map before later deltas are applied.
		this.modelMap.clear();
		ModelTableCodec.decodeIntoMap(tableBytes, this.modelMap);
		this.modelSet = new BlockStateModelSet(this.modelMap, ModelTableCodec.emptyMissingModel());
		this.blockColors = BlockColors.createDefault();
		this.fluidModelSet = ModelTableCodec.takeLastDecodedFluidModels();
		if (this.fluidModelSet == null) {
			FluidModel dummy = new FluidModel(ChunkSectionLayer.SOLID, null, null, null, null);
			this.fluidModelSet = new FluidStateModelSet(Map.of(), dummy);
		}
		// Rebuild against the new model set on the next job.
		this.compiler = null;
	}

	/** Merges a model-table delta into the shared map. */
	public void mergeModelTable(final byte[] deltaBytes) {
		ModelTableCodec.decodeIntoMap(deltaBytes, this.modelMap);
	}

	public boolean isReady() {
		return this.modelSet != null;
	}

	/** Compile one snapshot and return the encoded result blob. */
	public byte[] compile(final byte[] jobBytes) {
		SectionSnapshot snap = MeshJobCodec.decode(jobBytes);
		this.lastJobId = snap.jobId;
		boolean cutout = (snap.flags & 2) != 0;
		boolean ao = (snap.flags & 1) != 0;
		// Match the main thread's leaf-face culling mode.
		net.minecraft.world.level.block.LeavesBlock.setCutoutLeaves(cutout);
		// Rebuild when rendering options change.
		if (this.compiler == null || this.compilerAo != ao || this.compilerCutout != cutout) {
			this.compiler = new SectionCompiler(ao, cutout, this.modelSet, this.fluidModelSet, this.blockColors);
			this.compilerAo = ao;
			this.compilerCutout = cutout;
		}
		WorkerRenderSectionRegion region = new WorkerRenderSectionRegion(snap);
		SectionPos sectionPos = SectionPos.of(snap.sectionX, snap.sectionY, snap.sectionZ);
		VertexSorting sorting = VertexSorting.byDistance(snap.cameraRelX, snap.cameraRelY, snap.cameraRelZ);
		this.buffers.clearAll();
		SectionCompiler.Results results = this.compiler.compile(sectionPos, region, sorting, this.buffers);
		try {
			return MeshResultCodec.encode(results);
		} finally {
			results.release();
		}
	}
}

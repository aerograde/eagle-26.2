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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * Serializes baked block-state models for mesh workers.
 * Worker models preserve the original {@code collectParts} random consumption.
 */
public final class ModelTableCodec {

	/** "EMT1" model-table magic. */
	public static final int MAGIC = 0x454D5431;
	/** "EFL1" optional fluid metadata magic. */
	private static final int FLUID_MAGIC = 0x45464C31;
	/** Version 2 includes a state identity hash for registry-order checks. */
	public static final byte VERSION = 2;

	public static final int KIND_NONE = 0; // no state at this id (Block.stateById == null)
	public static final int KIND_SINGLE = 1;
	public static final int KIND_WEIGHTED = 2;
	public static final int KIND_MULTIPART = 3;
	public static final int KIND_UNSUPPORTED = 4;
	/** Top-level per-id marker: an entry follows (i32 stateHash + one recursive model). */
	public static final int KIND_PRESENT = 5;

	/** Summary of the most recent registry verification. */
	public static String lastVerifyReport = "";
	private static FluidStateModelSet lastDecodedFluidModels;

	private static final Direction[] DIRS = Direction.values();
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();

	private ModelTableCodec() {
	}

	// Main-thread encoding

	/** Serializes every registry id in {@code [0, stateCount)}. */
	public static byte[] encode(final BlockStateModelSet modelSet, final int stateCount) {
		return encode(modelSet, stateCount, null);
	}

	/** Serializes only {@code includedIds}, or all ids when null. */
	public static byte[] encode(final BlockStateModelSet modelSet, final int stateCount,
			final java.util.Set<Integer> includedIds) {
		return encode(modelSet, stateCount, includedIds, null);
	}

	/**
	 * Base-table variant which appends the baked water/lava sprite coordinates.
	 * Deltas omit this trailer because the atlas metadata is unchanged for their
	 * table epoch.
	 */
	public static byte[] encode(final BlockStateModelSet modelSet, final int stateCount,
			final java.util.Set<Integer> includedIds, final FluidStateModelSet fluidModels) {
		Writer w = new Writer(1 << 20);
		w.i32(MAGIC);
		w.u8(VERSION);
		w.i32(stateCount);

		RandomSource scratch = RandomSource.createThreadLocalInstance(0L);
		List<BlockStateModelPart> tmp = new ArrayList<>();
		for (int id = 0; id < stateCount; ++id) {
			if (includedIds != null && !includedIds.contains(id)) {
				w.u8(KIND_NONE);
				continue;
			}
			encodeOneId(w, id, modelSet, scratch, tmp);
		}
		if (fluidModels != null) {
			encodeFluidModels(w, fluidModels);
		}
		return w.toBytes();
	}

	private static void encodeFluidModels(final Writer w, final FluidStateModelSet fluidModels) {
		w.i32(FLUID_MAGIC);
		encodeFluidModel(w, fluidModels.get(Fluids.WATER.defaultFluidState()));
		encodeFluidModel(w, fluidModels.get(Fluids.LAVA.defaultFluidState()));
	}

	private static void encodeFluidModel(final Writer w, final FluidModel model) {
		w.u8(model.layer().ordinal());
		w.u8(model.tintSource() != null ? 1 : 0);
		encodeFluidMaterial(w, model.stillMaterial());
		encodeFluidMaterial(w, model.flowingMaterial());
		w.u8(model.overlayMaterial() != null ? 1 : 0);
		if (model.overlayMaterial() != null) {
			encodeFluidMaterial(w, model.overlayMaterial());
		}
	}

	private static void encodeFluidMaterial(final Writer w, final Material.Baked material) {
		TextureAtlasSprite sprite = material.sprite();
		w.f32(sprite.getU0());
		w.f32(sprite.getU1());
		w.f32(sprite.getV0());
		w.f32(sprite.getV1());
	}

	/** Encode a single registry id's entry (KIND_NONE, or KIND_PRESENT + canary hash + model). */
	private static void encodeOneId(final Writer w, final int id, final BlockStateModelSet modelSet,
			final RandomSource scratch, final List<BlockStateModelPart> tmp) {
		BlockState state = Block.stateById(id);
		if (state == null) {
			w.u8(KIND_NONE);
			return;
		}
		// Detect main-to-worker registry-order mismatches.
		w.u8(KIND_PRESENT);
		w.i32(state.toString().hashCode());
		BlockStateModel model = modelSet.get(state);
		encodeModel(w, model, scratch, tmp);
	}

	/**
	 * Time-sliced full-table encoder. Call {@link #encodeSome(double)} until complete.
	 */
	public static final class IncrementalEncoder {
		private final BlockStateModelSet modelSet;
		private final int stateCount;
		private final Writer w;
		private final RandomSource scratch = RandomSource.createThreadLocalInstance(0L);
		private final List<BlockStateModelPart> tmp = new ArrayList<>();
		private int nextId;
		private double encodeMsTotal;
		private int slices;

		public IncrementalEncoder(final BlockStateModelSet modelSet, final int stateCount) {
			this.modelSet = modelSet;
			this.stateCount = stateCount;
			this.w = new Writer(1 << 22);
			this.w.i32(MAGIC);
			this.w.u8(VERSION);
			this.w.i32(stateCount);
		}

		/** Encode ids until {@code budgetMs} elapses or the table is complete.
		 *  @return true once every id in [0, stateCount) has been encoded. */
		public boolean encodeSome(final double budgetMs) {
			if (this.nextId >= this.stateCount) {
				return true;
			}
			long t0 = System.nanoTime();
			long budgetNanos = (long) (budgetMs * 1.0e6);
			while (this.nextId < this.stateCount) {
				encodeOneId(this.w, this.nextId++, this.modelSet, this.scratch, this.tmp);
				if (System.nanoTime() - t0 >= budgetNanos) {
					break;
				}
			}
			this.encodeMsTotal += (System.nanoTime() - t0) / 1.0e6;
			++this.slices;
			return this.nextId >= this.stateCount;
		}

		/** Finished table bytes, identical to the one-shot encoder. */
		public byte[] toBytes() {
			return this.w.toBytes();
		}

		/** Cumulative CPU ms spent encoding across all slices (for the c82 counters). */
		public double encodeMsTotal() {
			return this.encodeMsTotal;
		}

		/** Number of per-frame slices it took. */
		public int slices() {
			return this.slices;
		}

		public int stateCount() {
			return this.stateCount;
		}
	}

	private static void encodeModel(final Writer w, final BlockStateModel model, final RandomSource scratch,
			final List<BlockStateModelPart> tmp) {
		if (model instanceof SingleVariant) {
			w.u8(KIND_SINGLE);
			tmp.clear();
			// SingleVariant.collectParts ignores the RNG and adds its one fixed part.
			model.collectParts(scratch, tmp);
			encodePart(w, tmp.get(0));
		} else if (model instanceof WeightedVariants) {
			w.u8(KIND_WEIGHTED);
			List<Weighted<BlockStateModel>> entries = ((WeightedVariants) model).meshWorkerVariantList().unwrap();
			w.u16(entries.size());
			for (Weighted<BlockStateModel> e : entries) {
				w.i32(e.weight());
				encodeModel(w, e.value(), scratch, tmp);
			}
		} else if (model instanceof MultiPartModel) {
			w.u8(KIND_MULTIPART);
			List<BlockStateModel> children = ((MultiPartModel) model).meshWorkerSelectedModels();
			w.u16(children.size());
			for (BlockStateModel c : children) {
				encodeModel(w, c, scratch, tmp);
			}
		} else {
			w.u8(KIND_UNSUPPORTED);
		}
	}

	private static void encodePart(final Writer w, final BlockStateModelPart part) {
		w.u8(part.useAmbientOcclusion() ? 1 : 0);
		w.i32(part.materialFlags()); // real value: sprite present main-side, worker never recomputes it
		// 7 quad slots in the fixed order the worker rebuilds: unculled (null) then Direction ordinals 0..5
		encodeQuadList(w, part.getQuads(null));
		for (Direction d : DIRS) {
			encodeQuadList(w, part.getQuads(d));
		}
	}

	private static void encodeQuadList(final Writer w, final List<BakedQuad> quads) {
		w.u16(quads.size());
		for (BakedQuad q : quads) {
			encodeQuad(w, q);
		}
	}

	private static void encodeQuad(final Writer w, final BakedQuad q) {
		for (int v = 0; v < 4; ++v) {
			Vector3fc p = q.position(v);
			w.f32(p.x());
			w.f32(p.y());
			w.f32(p.z());
		}
		for (int v = 0; v < 4; ++v) {
			w.i64(q.packedUV(v));
		}
		w.u8(q.direction().ordinal());
		BakedQuad.MaterialInfo mi = q.materialInfo();
		w.u8(mi.layer().ordinal());
		w.i32(mi.tintIndex());
		w.u8(mi.shade() ? 1 : 0);
		w.i32(mi.lightEmission());
	}

	// Worker decoding

	/** Rebuilds a worker model set. Requires bootstrapped block registries. */
	public static BlockStateModelSet decodeToModelSet(final byte[] data) {
		Map<BlockState, BlockStateModel> map = new HashMap<>(1 << 12);
		decodeIntoMap(data, map);
		return new BlockStateModelSet(map, emptyMissingModel());
	}

	/** The zero-quad model workers use for ids the table does not cover. */
	public static BlockStateModel emptyMissingModel() {
		return new WorkerSingleModel(new WorkerModelPart(QuadCollection.EMPTY, false, 0));
	}

	/** Decodes a base table or delta into an existing model map. */
	public static int decodeIntoMap(final byte[] data, final Map<BlockState, BlockStateModel> map) {
		lastDecodedFluidModels = null;
		Reader r = new Reader(data);
		int magic = r.i32();
		if (magic != MAGIC) {
			throw new IllegalStateException("bad model-table magic 0x" + Integer.toHexString(magic));
		}
		int version = r.u8();
		if (version != (VERSION & 0xFF)) {
			throw new IllegalStateException("unsupported model-table version " + version);
		}
		int stateCount = r.i32();

		int verified = 0;
		int hashMiss = 0;
		String firstMiss = null;
		int decoded = 0;
		for (int id = 0; id < stateCount; ++id) {
			int marker = r.u8();
			if (marker == KIND_NONE) {
				continue;
			}
			if (marker != KIND_PRESENT) {
				throw new IllegalStateException("bad top-level marker " + marker + " at id " + id);
			}
			int expectHash = r.i32();
			BlockState state = Block.stateById(id);
			int gotHash = (state == null) ? 0 : state.toString().hashCode();
			if (state == null || gotHash != expectHash) {
				++hashMiss;
				if (firstMiss == null) {
					firstMiss = "id=" + id + " expectHash=" + Integer.toHexString(expectHash) + " got="
							+ (state == null ? "null" : state.toString());
				}
			} else {
				++verified;
			}
			BlockStateModel model = decodeModel(r);
			if (model != null && state != null) {
				map.put(state, model);
				++decoded;
			}
		}
		if (r.remaining() >= 4 && r.i32() == FLUID_MAGIC) {
			FluidModel water = decodeFluidModel(r, true);
			FluidModel lava = decodeFluidModel(r, false);
			lastDecodedFluidModels = new FluidStateModelSet(
				Map.of(Fluids.WATER, water, Fluids.FLOWING_WATER, water,
					Fluids.LAVA, lava, Fluids.FLOWING_LAVA, lava),
				lava
			);
		}
		lastVerifyReport = "reg=" + Block.BLOCK_STATE_REGISTRY.size() + " verified=" + verified + " hashMiss="
				+ hashMiss + (firstMiss == null ? "" : " first[" + firstMiss + "]");
		return decoded;
	}

	public static FluidStateModelSet takeLastDecodedFluidModels() {
		FluidStateModelSet result = lastDecodedFluidModels;
		lastDecodedFluidModels = null;
		return result;
	}

	private static FluidModel decodeFluidModel(final Reader r, final boolean water) {
		ChunkSectionLayer layer = LAYERS[r.u8()];
		boolean tinted = r.u8() != 0;
		Material.Baked still = decodeFluidMaterial(r);
		Material.Baked flowing = decodeFluidMaterial(r);
		Material.Baked overlay = r.u8() != 0 ? decodeFluidMaterial(r) : null;
		return new FluidModel(layer, still, flowing, overlay,
			tinted && water ? net.minecraft.client.color.block.BlockTintSources.water() : null);
	}

	private static Material.Baked decodeFluidMaterial(final Reader r) {
		TextureAtlasSprite sprite = TextureAtlasSprite.createForMeshWorker(
			Identifier.withDefaultNamespace("textures/atlas/blocks.png"),
			r.f32(), r.f32(), r.f32(), r.f32()
		);
		return new Material.Baked(sprite, false);
	}

	private static BlockStateModel decodeModel(final Reader r) {
		int kind = r.u8();
		switch (kind) {
			case KIND_NONE:
			case KIND_UNSUPPORTED:
				return null;
			case KIND_SINGLE:
				return new WorkerSingleModel(decodePart(r));
			case KIND_WEIGHTED: {
				int n = r.u16();
				int[] weights = new int[n];
				BlockStateModel[] children = new BlockStateModel[n];
				int total = 0;
				for (int i = 0; i < n; ++i) {
					weights[i] = r.i32();
					total += weights[i];
					children[i] = decodeModel(r);
				}
				return new WorkerWeightedModel(weights, children, total);
			}
			case KIND_MULTIPART: {
				int n = r.u16();
				BlockStateModel[] children = new BlockStateModel[n];
				for (int i = 0; i < n; ++i) {
					children[i] = decodeModel(r);
				}
				return new WorkerMultiPartModel(children);
			}
			default:
				throw new IllegalStateException("bad model kind " + kind);
		}
	}

	private static WorkerModelPart decodePart(final Reader r) {
		boolean ao = r.u8() != 0;
		int flags = r.i32();
		QuadCollection.Builder builder = new QuadCollection.Builder();
		// slot 0 = unculled (null)
		int unculled = r.u16();
		for (int i = 0; i < unculled; ++i) {
			builder.addUnculledFace(decodeQuad(r));
		}
		for (Direction d : DIRS) {
			int cnt = r.u16();
			for (int i = 0; i < cnt; ++i) {
				builder.addCulledFace(d, decodeQuad(r));
			}
		}
		return new WorkerModelPart(builder.build(), ao, flags);
	}

	private static BakedQuad decodeQuad(final Reader r) {
		Vector3f p0 = new Vector3f(r.f32(), r.f32(), r.f32());
		Vector3f p1 = new Vector3f(r.f32(), r.f32(), r.f32());
		Vector3f p2 = new Vector3f(r.f32(), r.f32(), r.f32());
		Vector3f p3 = new Vector3f(r.f32(), r.f32(), r.f32());
		long uv0 = r.i64();
		long uv1 = r.i64();
		long uv2 = r.i64();
		long uv3 = r.i64();
		Direction dir = DIRS[r.u8()];
		ChunkSectionLayer layer = LAYERS[r.u8()];
		int tintIndex = r.i32();
		boolean shade = r.u8() != 0;
		int lightEmission = r.i32();
		// Sprite and item render type are unused during section compilation.
		BakedQuad.MaterialInfo mi = new BakedQuad.MaterialInfo(null, layer, null, tintIndex, shade, lightEmission);
		return new BakedQuad(p0, p1, p2, p3, uv0, uv1, uv2, uv3, dir, mi);
	}

	// Worker model implementations

	static final class WorkerModelPart implements BlockStateModelPart {
		private final QuadCollection quads;
		private final boolean ao;
		private final int flags;

		WorkerModelPart(final QuadCollection quads, final boolean ao, final int flags) {
			this.quads = quads;
			this.ao = ao;
			this.flags = flags;
		}

		@Override
		public List<BakedQuad> getQuads(final Direction direction) {
			return this.quads.getQuads(direction);
		}

		@Override
		public boolean useAmbientOcclusion() {
			return this.ao;
		}

		@Override
		public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() {
			return null; // never read on the section-compile path
		}

		@Override
		public int materialFlags() {
			return this.flags;
		}
	}

	static final class WorkerSingleModel implements BlockStateModel {
		private final BlockStateModelPart part;

		WorkerSingleModel(final BlockStateModelPart part) {
			this.part = part;
		}

		@Override
		public void collectParts(final RandomSource random, final List<BlockStateModelPart> output) {
			output.add(this.part);
		}

		@Override
		public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() {
			return null;
		}

		@Override
		public int materialFlags() {
			return this.part.materialFlags();
		}
	}

	static final class WorkerWeightedModel implements BlockStateModel {
		private final int[] weights;
		private final BlockStateModel[] children;
		private final int totalWeight;

		WorkerWeightedModel(final int[] weights, final BlockStateModel[] children, final int totalWeight) {
			this.weights = weights;
			this.children = children;
			this.totalWeight = totalWeight;
		}

		@Override
		public void collectParts(final RandomSource random, final List<BlockStateModelPart> output) {
			if (this.totalWeight <= 0) {
				return;
			}
			// Mirror WeightedList.getRandomOrThrow: one nextInt(totalWeight) then a
			// cumulative-subtract walk over the entries in insertion order.
			int selection = random.nextInt(this.totalWeight);
			for (int i = 0; i < this.children.length; ++i) {
				selection -= this.weights[i];
				if (selection < 0) {
					this.children[i].collectParts(random, output);
					return;
				}
			}
		}

		@Override
		public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() {
			return null;
		}

		@Override
		public int materialFlags() {
			int flags = 0;
			for (BlockStateModel child : this.children) {
				flags |= child.materialFlags();
			}
			return flags;
		}
	}

	static final class WorkerMultiPartModel implements BlockStateModel {
		private final BlockStateModel[] children;

		WorkerMultiPartModel(final BlockStateModel[] children) {
			this.children = children;
		}

		@Override
		public void collectParts(final RandomSource random, final List<BlockStateModelPart> output) {
			// Mirror MultiPartModel.collectParts exactly: capture one nextLong(), then
			// re-seed with it before every selected sub-model.
			long seed = random.nextLong();
			for (BlockStateModel child : this.children) {
				random.setSeed(seed);
				child.collectParts(random, output);
			}
		}

		@Override
		public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() {
			return null;
		}

		@Override
		public int materialFlags() {
			int flags = 0;
			for (BlockStateModel child : this.children) {
				flags |= child.materialFlags();
			}
			return flags;
		}
	}

	// ============================ tiny LE byte writer / reader ============================

	private static final class Writer {
		private byte[] buf;
		private int len;

		Writer(final int initial) {
			this.buf = new byte[initial];
		}

		private void ensure(final int extra) {
			int need = this.len + extra;
			if (need > this.buf.length) {
				int cap = this.buf.length;
				while (cap < need) {
					cap <<= 1;
				}
				byte[] n = new byte[cap];
				System.arraycopy(this.buf, 0, n, 0, this.len);
				this.buf = n;
			}
		}

		void u8(final int v) {
			ensure(1);
			this.buf[this.len++] = (byte) v;
		}

		void u16(final int v) {
			ensure(2);
			this.buf[this.len++] = (byte) v;
			this.buf[this.len++] = (byte) (v >> 8);
		}

		void i32(final int v) {
			ensure(4);
			this.buf[this.len++] = (byte) v;
			this.buf[this.len++] = (byte) (v >> 8);
			this.buf[this.len++] = (byte) (v >> 16);
			this.buf[this.len++] = (byte) (v >> 24);
		}

		void i64(final long v) {
			ensure(8);
			for (int i = 0; i < 8; ++i) {
				this.buf[this.len++] = (byte) (v >> (i << 3));
			}
		}

		void f32(final float v) {
			i32(Float.floatToRawIntBits(v));
		}

		byte[] toBytes() {
			byte[] out = new byte[this.len];
			System.arraycopy(this.buf, 0, out, 0, this.len);
			return out;
		}
	}

	private static final class Reader {
		private final byte[] buf;
		private int pos;

		Reader(final byte[] buf) {
			this.buf = buf;
		}

		int u8() {
			return this.buf[this.pos++] & 0xFF;
		}

		int u16() {
			int v = (this.buf[this.pos] & 0xFF) | ((this.buf[this.pos + 1] & 0xFF) << 8);
			this.pos += 2;
			return v;
		}

		int i32() {
			int v = (this.buf[this.pos] & 0xFF) | ((this.buf[this.pos + 1] & 0xFF) << 8)
					| ((this.buf[this.pos + 2] & 0xFF) << 16) | ((this.buf[this.pos + 3] & 0xFF) << 24);
			this.pos += 4;
			return v;
		}

		long i64() {
			long v = 0L;
			for (int i = 0; i < 8; ++i) {
				v |= (long) (this.buf[this.pos + i] & 0xFF) << (i << 3);
			}
			this.pos += 8;
			return v;
		}

		float f32() {
			return Float.intBitsToFloat(i32());
		}

		int remaining() {
			return this.buf.length - this.pos;
		}
	}

}

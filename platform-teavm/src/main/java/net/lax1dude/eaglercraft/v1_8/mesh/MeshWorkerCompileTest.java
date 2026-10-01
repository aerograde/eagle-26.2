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

import java.util.Arrays;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.dom.events.ErrorEvent;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.workers.Worker;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerMain.BinaryHandler;
import net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerMain.MetaHandler;
import net.lax1dude.eaglercraft.v1_8.sp.internal.ClientPlatformSingleplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import com.mojang.blaze3d.vertex.VertexSorting;

/**
 * Mesh-worker plan Phase B correctness gate — the byte-parity shadow compare (design doc
 * §6 Phase B). Opt-in via {@code eaglercraftXOpts.meshWorkerCompileTest === true} or the
 * {@code ?meshtest2} URL param; {@code ClientMain} guards the call so this is dead code
 * otherwise. Runs after {@code worldReady} so a real section near spawn exists.
 *
 * <p>It: (1) picks one real, non-empty, fluid-free section near the player whose block
 * states all have supported (Single/Weighted/MultiPart) models; (2) compiles it
 * <b>inline</b> on the main thread with the stock {@code SectionCompiler} and serializes the
 * result; (3) ships the serialized model table + a {@link SectionSnapshot} of that section
 * to one worker, which runs the identical {@code SectionCompiler} and serializes its result;
 * (4) byte-compares the two blobs and logs {@code [MeshWorkerCompileTest] PASS/FAIL
 * byte-parity} plus inline-ms and worker-rtt-ms timings. The dispatcher is NOT swapped
 * (that is Phase C).
 */
public class MeshWorkerCompileTest {

	@JSBody(params = { "msg" }, script = "if (typeof console !== \"undefined\") console.log(msg);")
	private static native void log(String msg);

	@JSBody(params = { "w", "meta", "bin" }, script = "w.onmessage = function(e) {"
			+ " if (e.data instanceof ArrayBuffer) { bin(e.data); }"
			+ " else if (e.data && typeof e.data.meta === \"string\") { meta(e.data.meta); } };")
	private static native void registerDualChannel(Worker w, MetaHandler meta, BinaryHandler bin);

	@JSBody(params = { "w", "buf" }, script = "w.postMessage(buf, [buf]);")
	private static native void postBinaryTransfer(Worker w, ArrayBuffer buf);

	@JSBody(params = { "w", "str" }, script = "w.postMessage({ meta: str });")
	private static native void postMeta(Worker w, String str);

	@JSBody(params = {}, script = "return (typeof performance !== \"undefined\" && performance.now)"
			+ " ? performance.now() : Date.now();")
	private static native double nowMs();

	@JSBody(params = { "pass", "size", "inlineMs", "rtt" }, script = "try { globalThis.__meshWorkerCompileTest ="
			+ " { pass: pass, size: size, inlineMs: inlineMs, rttMs: rtt }; } catch(e) {}")
	private static native void publishResult(boolean pass, int size, double inlineMs, double rtt);

	@JSFunctor
	public interface Callback extends JSObject {
		void run();
	}

	@JSBody(params = { "cb", "ms" }, script = "if (typeof setTimeout !== \"undefined\") setTimeout(cb, ms);")
	private static native void setTimeout(Callback cb, int ms);

	@JSBody(params = { "tail" }, script = "try {"
			+ " var url = null;"
			+ " if (typeof eaglercraftXClientScriptURL === \"string\") url = eaglercraftXClientScriptURL;"
			+ " if (!url) { var ss = document.getElementsByTagName(\"script\");"
			+ "   for (var i = 0; i < ss.length; ++i) { var sc = ss[i].src || \"\";"
			+ "     if (sc.indexOf(\"classes\") >= 0) { url = sc; break; } } }"
			+ " if (!url) return null;"
			+ " var xhr = new XMLHttpRequest(); xhr.open(\"GET\", url, false); xhr.send(null);"
			+ " if (xhr.status && xhr.status >= 400) return null;"
			+ " var blob = new Blob([xhr.responseText, tail], { type: \"text/javascript;charset=utf8\" });"
			+ " return URL.createObjectURL(blob);"
			+ "} catch(e) { return null; }")
	private static native String resolveWorkerURLFallback(String tail);

	private static final String WORKER_TAIL = "\n\nmain([\"_worker_process_\"]);";

	private static Worker worker;
	private static byte[] expectedBytes; // inline result blob (A)
	private static byte[] tableBytes;
	private static byte[] snapshotBytes;
	private static double inlineMs;
	private static double jobSentMs;
	private static boolean finished = false;
	private static boolean started = false;
	private static int attempts = 0;
	private static final int MAX_ATTEMPTS = 60; // ~30s of polling for a loaded section

	/**
	 * Entry point (from ClientMain at boot). The world is not loaded yet, so this only
	 * kicks off a poll that retries until a suitable section near the player exists.
	 */
	public static void run() {
		log("[MeshWorkerCompileTest] armed; polling for a loaded section near the player");
		scheduleNext();
	}

	private static void scheduleNext() {
		if (finished) {
			return;
		}
		if (attempts++ >= MAX_ATTEMPTS) {
			log("[MeshWorkerCompileTest] SKIP timed out waiting for a loaded, suitable section");
			publishResult(false, 0, 0.0, 0.0);
			finished = true;
			return;
		}
		setTimeout(MeshWorkerCompileTest::tryAttempt, attempts == 1 ? 1500 : 500);
	}

	private static void tryAttempt() {
		if (finished) {
			return;
		}
		boolean startedFlow;
		try {
			startedFlow = attemptOnce();
		} catch (Throwable t) {
			log("[MeshWorkerCompileTest] EXCEPTION during attempt " + t);
			EagRuntime.debugPrintStackTrace(t);
			finish(false, 0, 0.0);
			return;
		}
		if (!startedFlow) {
			scheduleNext();
		}
	}

	/** One attempt. Returns true once the worker flow is launched (or a terminal state is
	 *  reached); false to retry later (world/section not ready yet). */
	private static boolean attemptOnce() {
		if (started) {
			return true;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc == null ? null : mc.level;
			LocalPlayer player = mc == null ? null : mc.player;
			if (level == null || player == null) {
				return false; // retry
			}

			BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
			FluidStateModelSet fluidSet = mc.getModelManager().getFluidStateModelSet();
			BlockColors blockColors = mc.getBlockColors();
			boolean ao = mc.options.ambientOcclusion().get();
			boolean cutout = mc.options.cutoutLeaves().get();
			boolean isDebug = level.isDebug();

			double camX = player.getX();
			double camY = player.getY();
			double camZ = player.getZ();
			BlockPos playerPos = player.blockPosition();
			int psx = SectionPos.blockToSectionCoord(playerPos.getX());
			int psy = SectionPos.blockToSectionCoord(playerPos.getY());
			int psz = SectionPos.blockToSectionCoord(playerPos.getZ());

			// ---- pick a suitable section near the player ----
			int[] dy = { 0, -1, -2, 1, 2 };
			int[] dh = { 0, 1, -1, 2, -2 };
			int chosenX = 0, chosenY = 0, chosenZ = 0;
			RenderSectionRegion chosenRegion = null;
			outer:
			for (int oy : dy) {
				for (int ox : dh) {
					for (int oz : dh) {
						int sx = psx + ox, sy = psy + oy, sz = psz + oz;
						RenderRegionCache cache = new RenderRegionCache();
						RenderSectionRegion region = cache.createRegion(level, SectionPos.asLong(sx, sy, sz));
						if (isSectionSuitable(level, region, modelSet, sx, sy, sz)) {
							chosenX = sx;
							chosenY = sy;
							chosenZ = sz;
							chosenRegion = region;
							break outer;
						}
					}
				}
			}

			if (chosenRegion == null) {
				return false; // no loaded/suitable section yet — retry later
			}
			started = true;
			log("[MeshWorkerCompileTest] chosen section (" + chosenX + "," + chosenY + "," + chosenZ + ")");

			// ---- build snapshot from the live region ----
			SectionSnapshot snap = SectionSnapshotBuilder.build(chosenRegion, chosenX, chosenY, chosenZ, ao, cutout,
					isDebug, false, camX, camY, camZ);

			// ---- inline compile (the reference) ----
			SectionCompiler inlineCompiler = new SectionCompiler(ao, cutout, modelSet, fluidSet, blockColors);
			SectionBufferBuilderPack pack = new SectionBufferBuilderPack();
			SectionPos sp = SectionPos.of(chosenX, chosenY, chosenZ);
			VertexSorting sorting = VertexSorting.byDistance(snap.cameraRelX, snap.cameraRelY, snap.cameraRelZ);
			double t0 = nowMs();
			SectionCompiler.Results inlineResults = inlineCompiler.compile(sp, chosenRegion, sorting, pack);
			inlineMs = nowMs() - t0;
			expectedBytes = MeshResultCodec.encode(inlineResults);
			inlineResults.release();
			pack.close();
			log("[MeshWorkerCompileTest] inline result " + expectedBytes.length + " bytes in " + fmtMs(inlineMs) + "ms");

			// ---- encode job + model table ----
			snapshotBytes = MeshJobCodec.encode(snap);
			// Only the center section's block states need models (the compiler fetches
			// models solely for the tesselated center 16³). Scoping the table to those ids
			// keeps it small/fast for the self-test while staying keyed by global id, so the
			// worker hydration path is identical to the full Phase C broadcast.
			java.util.HashSet<Integer> neededIds = collectNeededIds(chosenRegion, chosenX, chosenY, chosenZ);
			int stateCount = Block.BLOCK_STATE_REGISTRY.size();
			double tt0 = nowMs();
			tableBytes = ModelTableCodec.encode(modelSet, stateCount, neededIds);
			log("[MeshWorkerCompileTest] model table " + tableBytes.length + " bytes (" + neededIds.size()
					+ " of " + stateCount + " states) in " + fmtMs(nowMs() - tt0) + "ms; snapshot "
					+ snapshotBytes.length + " bytes");

			// ---- prechecks: decode the snapshot exactly as the worker will, and compare
			//      the worker-side region's every read against the live region synchronously.
			//      A mismatch here explains a parity FAIL before any vertex is baked.
			try {
				runPrechecks(chosenRegion, chosenX, chosenY, chosenZ, modelSet, neededIds);
			} catch (Throwable t) {
				log("[MeshWorkerCompileTest] precheck EXCEPTION " + t);
			}

			// ---- hydration parity: decode the table ON THE MAIN THREAD and compare the
			//      hydrated models against the vanilla ones quad-for-quad across seeds. If
			//      this passes but the worker still diverges, the fault is in the worker
			//      environment (registry order etc. — see the table-ready canary), not in
			//      the model table.
			try {
				runHydrationParity(modelSet, tableBytes, neededIds);
			} catch (Throwable t) {
				log("[MeshWorkerCompileTest] hydration parity EXCEPTION " + t);
			}

			// ---- spawn the worker + handshake ----
			String url = null;
			try {
				url = ClientPlatformSingleplayer.createMeshWorkerScriptURLTeaVM();
			} catch (Throwable t) {
				log("[MeshWorkerCompileTest] SP worker-URL threw (" + t + "), trying fallback");
			}
			if (url == null && !net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC()) {
				// WORKERS PORT: the XHR Blob fallback must not run on wasm-gc (it would
				// blob-wrap the main-less classes.wasm-runtime.js into a broken worker).
				url = resolveWorkerURLFallback(WORKER_TAIL);
			}
			if (url == null) {
				log("[MeshWorkerCompileTest] FAIL could not resolve a worker script URL");
				finish(false, 0, 0.0);
				return true;
			}

			worker = net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC()
					? net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.spawnWasmWorker(url)
					: Worker.create(url);
			worker.addEventListener("error", new EventListener<ErrorEvent>() {
				@Override
				public void handleEvent(ErrorEvent evt) {
					log("[MeshWorkerCompileTest] FAIL worker error: " + evt.getError());
					finish(false, 0, 0.0);
				}
			});
			registerDualChannel(worker, MeshWorkerCompileTest::onMeta, MeshWorkerCompileTest::onResult);

			postMeta(worker, "role:mesh|mip:0");
			postMeta(worker, "mode:compile"); // triggers registry bootstrap + compile-booted
			log("[MeshWorkerCompileTest] worker spawned, awaiting compile-booted...");
			return true;
		} catch (Throwable t) {
			log("[MeshWorkerCompileTest] EXCEPTION " + t);
			EagRuntime.debugPrintStackTrace(t);
			finish(false, 0, 0.0);
			return true;
		}
	}

	/**
	 * Synchronous main-side parity prechecks (targeted at the likely divergence causes):
	 * decode {@link #snapshotBytes} exactly like the worker, wrap it in a
	 * {@link WorkerRenderSectionRegion}, and compare every read the compiler will make —
	 * block-state identity + block/sky light over the full 18³, and the four tint resolvers
	 * over the 18³ (inside-16³ mismatches are fatal; boundary-ring mismatches only matter if
	 * a tint source actually samples outside, e.g. doubleTallGrass reads pos.below()).
	 * Also logs which of the section's states use RNG-consuming models (Weighted/MultiPart).
	 */
	private static void runPrechecks(final RenderSectionRegion live, final int sx, final int sy, final int sz,
			final BlockStateModelSet modelSet, final java.util.HashSet<Integer> neededIds) {
		SectionSnapshot decoded = MeshJobCodec.decode(snapshotBytes);
		WorkerRenderSectionRegion wr = new WorkerRenderSectionRegion(decoded);
		int ox = (sx << 4) - 1, oy = (sy << 4) - 1, oz = (sz << 4) - 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		int blockMiss = 0, lightMiss = 0;
		String firstBlockMiss = null, firstLightMiss = null;
		for (int x = 0; x < SectionSnapshot.VOLUME_DIM; ++x) {
			for (int y = 0; y < SectionSnapshot.VOLUME_DIM; ++y) {
				for (int z = 0; z < SectionSnapshot.VOLUME_DIM; ++z) {
					pos.set(ox + x, oy + y, oz + z);
					BlockState a = live.getBlockState(pos);
					BlockState b = wr.getBlockState(pos);
					if (a != b) {
						++blockMiss;
						if (firstBlockMiss == null) {
							firstBlockMiss = pos.toString() + " live=" + a + " worker=" + b;
						}
					}
					int lb = live.getBrightness(LightLayer.BLOCK, pos), wb = wr.getBrightness(LightLayer.BLOCK, pos);
					int ls = live.getBrightness(LightLayer.SKY, pos), ws = wr.getBrightness(LightLayer.SKY, pos);
					if (lb != wb || ls != ws) {
						++lightMiss;
						if (firstLightMiss == null) {
							firstLightMiss = pos.toString() + " block " + lb + "->" + wb + " sky " + ls + "->" + ws;
						}
					}
				}
			}
		}
		log("[MeshWorkerCompileTest] precheck blocks: " + (blockMiss == 0 ? "OK (18^3 identical)"
				: blockMiss + " MISMATCHES, first: " + firstBlockMiss));
		log("[MeshWorkerCompileTest] precheck light: " + (lightMiss == 0 ? "OK (18^3 identical)"
				: lightMiss + " MISMATCHES, first: " + firstLightMiss));

		ColorResolver[] resolvers = { BiomeColors.GRASS_COLOR_RESOLVER, BiomeColors.FOLIAGE_COLOR_RESOLVER,
				BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER, BiomeColors.WATER_COLOR_RESOLVER };
		String[] resolverNames = { "grass", "foliage", "dryFoliage", "water" };
		for (int r = 0; r < resolvers.length; ++r) {
			int insideMiss = 0, ringMiss = 0;
			String firstInside = null;
			for (int x = 0; x < SectionSnapshot.VOLUME_DIM; ++x) {
				for (int y = 0; y < SectionSnapshot.VOLUME_DIM; ++y) {
					for (int z = 0; z < SectionSnapshot.VOLUME_DIM; ++z) {
						pos.set(ox + x, oy + y, oz + z);
						int a = live.getBlockTint(pos, resolvers[r]);
						int b = wr.getBlockTint(pos, resolvers[r]);
						if (a == b) {
							continue;
						}
						boolean inside = x >= 1 && x <= 16 && y >= 1 && y <= 16 && z >= 1 && z <= 16;
						if (inside) {
							++insideMiss;
							if (firstInside == null) {
								firstInside = pos.toString() + " live=0x" + Integer.toHexString(a) + " worker=0x"
										+ Integer.toHexString(b);
							}
						} else {
							++ringMiss;
						}
					}
				}
			}
			log("[MeshWorkerCompileTest] precheck tint." + resolverNames[r] + ": "
					+ (insideMiss == 0 ? "inside-16^3 OK" : insideMiss + " INSIDE MISMATCHES, first: " + firstInside)
					+ (ringMiss == 0 ? "" : " (+" + ringMiss
							+ " boundary-ring diffs — fatal only if a tint source samples outside the section,"
							+ " e.g. doubleTallGrass pos.below())"));
		}

		StringBuilder rng = new StringBuilder();
		int rngCount = 0;
		for (Integer id : neededIds) {
			BlockState state = Block.stateById(id);
			if (state == null) {
				continue;
			}
			BlockStateModel model = modelSet.get(state);
			String kind = (model instanceof WeightedVariants) ? "Weighted"
					: (model instanceof MultiPartModel) ? "MultiPart" : null;
			if (kind != null) {
				if (rngCount++ > 0) {
					rng.append(", ");
				}
				if (rngCount <= 12) {
					rng.append(kind).append(':').append(state.getBlock()).append("#").append(id);
				}
			}
		}
		log("[MeshWorkerCompileTest] precheck RNG models: " + (rngCount == 0 ? "none (all SingleVariant)"
				: rngCount + " states consume RNG: " + rng + (rngCount > 12 ? " ..." : "")));
	}

	/**
	 * Main-thread hydration parity check: decode {@code tableBytes} with the same
	 * {@link ModelTableCodec#decodeToModelSet} the worker runs, then for every census state
	 * and a set of deterministic seeds, run vanilla-vs-hydrated {@code collectParts} with
	 * two identically-seeded {@code SingleThreadedRandomSource}s (the exact RNG class
	 * ModelBlockRenderer uses) and compare: part count, useAmbientOcclusion, per-slot quad
	 * counts, every quad field the compiler reads (positions, packedUVs, direction, layer,
	 * tintIndex, shade, lightEmission), and the post-collect RNG state (one extra nextLong
	 * from each — catches a differing number of RNG draws). If this is quad-for-quad clean,
	 * the worker's models are provably identical too (same code, same table bytes).
	 */
	private static void runHydrationParity(final BlockStateModelSet vanillaSet, final byte[] table,
			final java.util.HashSet<Integer> ids) {
		net.minecraft.client.renderer.block.BlockStateModelSet hydrated = ModelTableCodec.decodeToModelSet(table);
		log("[MeshWorkerCompileTest] hydration decode (main-side): " + ModelTableCodec.lastVerifyReport);
		long[] seeds = { 0L, 1L, -1L, 42L, 0x9E3779B97F4A7C15L, 1234567890123456789L, -987654321098765432L };
		int statesOk = 0;
		int statesBad = 0;
		java.util.List<BlockStateModelPart> pa = new java.util.ArrayList<>();
		java.util.List<BlockStateModelPart> pb = new java.util.ArrayList<>();
		for (Integer id : ids) {
			BlockState state = Block.stateById(id);
			if (state == null) {
				continue;
			}
			BlockStateModel vm = vanillaSet.get(state);
			BlockStateModel hm = hydrated.get(state);
			String bad = null;
			for (long seed : seeds) {
				RandomSource ra = RandomSource.createThreadLocalInstance(0L);
				RandomSource rb = RandomSource.createThreadLocalInstance(0L);
				ra.setSeed(seed);
				rb.setSeed(seed);
				pa.clear();
				pb.clear();
				vm.collectParts(ra, pa);
				hm.collectParts(rb, pb);
				bad = comparePartLists(pa, pb);
				if (bad == null && ra.nextLong() != rb.nextLong()) {
					bad = "RNG draw-count diverged (post-collect state differs)";
				}
				if (bad != null) {
					bad = "seed=" + seed + ": " + bad;
					break;
				}
			}
			if (bad == null) {
				++statesOk;
			} else {
				++statesBad;
				if (statesBad <= 8) {
					log("[MeshWorkerCompileTest] hydration MISMATCH state#" + id + " " + state.getBlock() + ": " + bad);
				}
			}
		}
		log("[MeshWorkerCompileTest] hydration parity: " + (statesBad == 0
				? "OK — " + statesOk + " states x " + seeds.length + " seeds quad-for-quad identical"
				: statesBad + " of " + (statesOk + statesBad) + " states DIVERGE (see above)"));
	}

	/** Deep compare of two collectParts outputs; null when identical, else a description. */
	private static String comparePartLists(final java.util.List<BlockStateModelPart> a,
			final java.util.List<BlockStateModelPart> b) {
		if (a.size() != b.size()) {
			return "partCount " + a.size() + " vs " + b.size();
		}
		for (int p = 0; p < a.size(); ++p) {
			BlockStateModelPart x = a.get(p);
			BlockStateModelPart y = b.get(p);
			if (x.useAmbientOcclusion() != y.useAmbientOcclusion()) {
				return "part" + p + " useAO " + x.useAmbientOcclusion() + " vs " + y.useAmbientOcclusion();
			}
			for (int slot = -1; slot < 6; ++slot) {
				Direction d = slot < 0 ? null : Direction.values()[slot];
				java.util.List<BakedQuad> qa = x.getQuads(d);
				java.util.List<BakedQuad> qb = y.getQuads(d);
				if (qa.size() != qb.size()) {
					return "part" + p + " slot=" + d + " quadCount " + qa.size() + " vs " + qb.size();
				}
				for (int q = 0; q < qa.size(); ++q) {
					String diff = quadDiff(qa.get(q), qb.get(q));
					if (diff != null) {
						return "part" + p + " slot=" + d + " quad" + q + " " + diff;
					}
				}
			}
		}
		return null;
	}

	/** Compare every quad field the section compiler reads; null when identical. */
	private static String quadDiff(final BakedQuad a, final BakedQuad b) {
		for (int v = 0; v < 4; ++v) {
			org.joml.Vector3fc va = a.position(v);
			org.joml.Vector3fc vb = b.position(v);
			if (Float.floatToRawIntBits(va.x()) != Float.floatToRawIntBits(vb.x())
					|| Float.floatToRawIntBits(va.y()) != Float.floatToRawIntBits(vb.y())
					|| Float.floatToRawIntBits(va.z()) != Float.floatToRawIntBits(vb.z())) {
				return "pos" + v + " (" + va.x() + "," + va.y() + "," + va.z() + ") vs (" + vb.x() + "," + vb.y()
						+ "," + vb.z() + ")";
			}
			if (a.packedUV(v) != b.packedUV(v)) {
				return "packedUV" + v;
			}
		}
		if (a.direction() != b.direction()) {
			return "direction " + a.direction() + " vs " + b.direction();
		}
		BakedQuad.MaterialInfo ma = a.materialInfo();
		BakedQuad.MaterialInfo mb = b.materialInfo();
		if (ma.layer() != mb.layer()) {
			return "layer " + ma.layer() + " vs " + mb.layer();
		}
		if (ma.tintIndex() != mb.tintIndex()) {
			return "tintIndex " + ma.tintIndex() + " vs " + mb.tintIndex();
		}
		if (ma.shade() != mb.shade()) {
			return "shade";
		}
		if (ma.lightEmission() != mb.lightEmission()) {
			return "lightEmission " + ma.lightEmission() + " vs " + mb.lightEmission();
		}
		return null;
	}

	/** Global BlockState ids of every non-air block in the center 16³ (the states whose
	 *  models the compiler will fetch). */
	private static java.util.HashSet<Integer> collectNeededIds(final RenderSectionRegion region, final int sx,
			final int sy, final int sz) {
		java.util.HashSet<Integer> ids = new java.util.HashSet<>();
		int minX = sx << 4, minY = sy << 4, minZ = sz << 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = 0; x < 16; ++x) {
			for (int y = 0; y < 16; ++y) {
				for (int z = 0; z < 16; ++z) {
					pos.set(minX + x, minY + y, minZ + z);
					BlockState state = region.getBlockState(pos);
					if (!state.isAir()) {
						ids.add(Block.getId(state));
					}
				}
			}
		}
		return ids;
	}

	private static boolean isSectionSuitable(final ClientLevel level, final RenderSectionRegion region,
			final BlockStateModelSet modelSet, final int sx, final int sy, final int sz) {
		// require the section to actually contain geometry
		LevelChunk chunk = level.getChunk(sx, sz);
		int idx = chunk.getSectionIndexFromSectionY(sy);
		if (idx < 0 || idx >= chunk.getSections().length || chunk.getSection(idx).hasOnlyAir()) {
			return false;
		}
		int minX = sx << 4, minY = sy << 4, minZ = sz << 4;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean anyBlock = false;
		for (int x = 0; x < 16; ++x) {
			for (int y = 0; y < 16; ++y) {
				for (int z = 0; z < 16; ++z) {
					pos.set(minX + x, minY + y, minZ + z);
					BlockState state = region.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}
					anyBlock = true;
					if (!state.getFluidState().isEmpty()) {
						return false; // fluids are a Phase C item (design doc §1.6 / §6)
					}
					BlockStateModel model = modelSet.get(state);
					if (!(model instanceof SingleVariant || model instanceof WeightedVariants
							|| model instanceof MultiPartModel)) {
						return false; // unsupported model impl
					}
				}
			}
		}
		return anyBlock;
	}

	private static void onMeta(String meta) {
		log("[MeshWorkerCompileTest] worker meta: " + meta);
		if (meta == null || finished) {
			return;
		}
		if (meta.equals("mesh-worker:compile-booted")) {
			// send the model table
			byte[] wire = tableBytes.clone();
			postMeta(worker, "model-table");
			postBinaryTransfer(worker, Int8Array.fromJavaArray(wire).getBuffer());
			log("[MeshWorkerCompileTest] model table posted (" + tableBytes.length + " bytes), awaiting table-ready...");
		} else if (meta.startsWith("mesh-worker:table-ready")) {
			// send the compile job
			byte[] wire = snapshotBytes.clone();
			postMeta(worker, "job");
			jobSentMs = nowMs();
			postBinaryTransfer(worker, Int8Array.fromJavaArray(wire).getBuffer());
			log("[MeshWorkerCompileTest] job posted, awaiting worker result...");
		} else if (meta.startsWith("mesh-worker:compile-boot-error")
				|| meta.startsWith("mesh-worker:table-error")
				|| meta.startsWith("mesh-worker:compile-error")) {
			log("[MeshWorkerCompileTest] FAIL worker reported: " + meta);
			finish(false, 0, 0.0);
		}
	}

	private static void onResult(ArrayBuffer buf) {
		if (finished) {
			return;
		}
		double rtt = nowMs() - jobSentMs;
		try {
			byte[] got = new Int8Array(buf).copyToJavaArray();
			boolean eq = Arrays.equals(got, expectedBytes);
			if (eq) {
				log("[MeshWorkerCompileTest] PASS byte-parity: " + got.length + " bytes, inline=" + fmtMs(inlineMs)
						+ "ms worker-rtt=" + fmtMs(rtt) + "ms");
			} else {
				int diff = firstDiff(got, expectedBytes);
				log("[MeshWorkerCompileTest] FAIL byte-parity mismatch: inline=" + expectedBytes.length + " worker="
						+ got.length + " firstDiffAt=" + diff + " inlineMs=" + fmtMs(inlineMs) + " rtt=" + fmtMs(rtt)
						+ "ms");
				// One-shot self-diagnosis: layout region + attribute of every diff cluster,
				// histogram, and hex windows (see MeshResultCodec.diagnoseMismatch).
				try {
					for (String line : MeshResultCodec.diagnoseMismatch(expectedBytes, got)) {
						log("[MeshWorkerCompileTest] diag " + line);
					}
				} catch (Throwable t) {
					log("[MeshWorkerCompileTest] diag EXCEPTION " + t);
				}
			}
			finish(eq, got.length, rtt);
		} catch (Throwable t) {
			log("[MeshWorkerCompileTest] EXCEPTION decoding worker result " + t);
			finish(false, 0, rtt);
		}
	}

	private static void finish(boolean pass, int size, double rtt) {
		if (finished) {
			return;
		}
		finished = true;
		publishResult(pass, size, inlineMs, rtt);
		try {
			if (worker != null) {
				worker.terminate();
				worker = null;
			}
		} catch (Throwable t) {
		}
	}

	private static int firstDiff(byte[] a, byte[] b) {
		int n = Math.min(a.length, b.length);
		for (int i = 0; i < n; ++i) {
			if (a[i] != b[i]) {
				return i;
			}
		}
		return (a.length == b.length) ? -1 : n;
	}

	private static String fmtMs(double ms) {
		long tenths = Math.round(ms * 10.0);
		return (tenths / 10) + "." + (tenths % 10);
	}
}

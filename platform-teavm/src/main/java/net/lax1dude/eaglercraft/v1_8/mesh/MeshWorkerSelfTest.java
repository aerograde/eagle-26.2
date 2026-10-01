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

/**
 * Opt-in worker transport and snapshot-codec self-test.
 */
public class MeshWorkerSelfTest {

	@JSBody(params = { "msg" }, script = "if (typeof console !== \"undefined\") console.log(msg);")
	private static native void log(String msg);

	// Split binary and control messages.
	@JSBody(params = { "w", "meta", "bin" }, script = "w.onmessage = function(e) {"
			+ " if (e.data instanceof ArrayBuffer) { bin(e.data); }"
			+ " else if (e.data && typeof e.data.meta === \"string\") { meta(e.data.meta); } };")
	private static native void registerDualChannel(Worker w, MetaHandler meta, BinaryHandler bin);

	// Transfer binary data to the worker.
	@JSBody(params = { "w", "buf" }, script = "w.postMessage(buf, [buf]);")
	private static native void postBinaryTransfer(Worker w, ArrayBuffer buf);

	// Send control metadata to the worker.
	@JSBody(params = { "w", "str" }, script = "w.postMessage({ meta: str });")
	private static native void postMeta(Worker w, String str);

	@JSBody(params = { "buf" }, script = "return buf.byteLength;")
	private static native int byteLength(ArrayBuffer buf);

	@JSBody(params = {}, script = "return (typeof performance !== \"undefined\" && performance.now)"
			+ " ? performance.now() : Date.now();")
	private static native double nowMs();

	@JSBody(params = { "pass", "size", "rtt" }, script = "try { globalThis.__meshWorkerSelfTest ="
			+ " { pass: pass, size: size, rttMs: rtt }; } catch(e) {}")
	private static native void publishResult(boolean pass, int size, double rtt);

	// Fallback worker URL resolver for the JS target.
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
	private static byte[] expectedBytes;
	private static double sendTimeMs;
	private static boolean finished = false;

	public static void run() {
		log("[MeshWorkerSelfTest] begin");
		try {
			String url = null;
			try {
				url = ClientPlatformSingleplayer.createMeshWorkerScriptURLTeaVM();
			} catch (Throwable t) {
				log("[MeshWorkerSelfTest] SP worker-URL mechanism threw (" + t + "), trying fallback");
			}
			if (url != null) {
				log("[MeshWorkerSelfTest] worker URL via ClientPlatformSingleplayer");
			} else if (!net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC()) {
				// The XHR Blob fallback is only valid for the JS target.
				url = resolveWorkerURLFallback(WORKER_TAIL);
				if (url != null) {
					log("[MeshWorkerSelfTest] worker URL via XHR Blob fallback");
				}
			}
			if (url == null) {
				log("[MeshWorkerSelfTest] FAIL could not resolve a worker script URL (classes.js not locatable)");
				publishResult(false, 0, 0.0);
				return;
			}

			worker = net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC()
					? net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.spawnWasmWorker(url)
					: Worker.create(url);
			worker.addEventListener("error", new EventListener<ErrorEvent>() {
				@Override
				public void handleEvent(ErrorEvent evt) {
					log("[MeshWorkerSelfTest] FAIL worker error: " + evt.getError());
					finish(false, 0, 0.0);
				}
			});
			registerDualChannel(worker, MeshWorkerSelfTest::onMeta, MeshWorkerSelfTest::onEcho);

			// Role handshake.
			postMeta(worker, "role:mesh|mip:0");

			// Build and encode the synthetic snapshot.
			SectionSnapshot snap = buildSyntheticSnapshot();
			expectedBytes = MeshJobCodec.encode(snap);
			snap.sparseTintValues = toSparseTints(snap);
			byte[] sparseBytes = MeshJobCodec.encode(snap);
			if (!Arrays.equals(expectedBytes, sparseBytes)) {
				throw new IllegalStateException("sparse tint encoding changed mesh-job bytes");
			}
			log("[MeshWorkerSelfTest] encoded synthetic snapshot: " + expectedBytes.length + " bytes"
					+ " (palette=" + snap.blockStatePalette.length + ", cells=" + SectionSnapshot.VOLUME_CELLS
					+ ", sparseTintParity=OK)");

			// Transfer a clone because posting detaches its backing storage.
			byte[] wire = expectedBytes.clone();
			ArrayBuffer job = Int8Array.fromJavaArray(wire).getBuffer();
			int before = byteLength(job);
			sendTimeMs = nowMs();
			postBinaryTransfer(worker, job);

			// The local buffer must be detached after transfer.
			int after = byteLength(job);
			if (after == 0 && before == expectedBytes.length) {
				log("[MeshWorkerSelfTest] transfer-neuter OK (local buffer detached: " + before + " -> " + after + ")");
			} else {
				log("[MeshWorkerSelfTest] WARN transfer-neuter check unexpected: before=" + before + " after=" + after
						+ " (expected before=" + expectedBytes.length + ", after=0)");
			}

			log("[MeshWorkerSelfTest] job posted, awaiting echo...");
		} catch (Throwable t) {
			log("[MeshWorkerSelfTest] EXCEPTION " + t);
			EagRuntime.debugPrintStackTrace(t);
			finish(false, 0, 0.0);
		}
	}

	private static void onMeta(String meta) {
		log("[MeshWorkerSelfTest] worker meta: " + meta);
	}

	private static void onEcho(ArrayBuffer buf) {
		if (finished) {
			return;
		}
		double rtt = nowMs() - sendTimeMs;
		try {
			byte[] got = new Int8Array(buf).copyToJavaArray();
			boolean eq = Arrays.equals(got, expectedBytes);
			if (eq) {
				log("[MeshWorkerSelfTest] PASS echo is byte-identical: " + got.length + " bytes, rtt="
						+ fmtMs(rtt) + "ms");
			} else {
				int diff = firstDiff(got, expectedBytes);
				log("[MeshWorkerSelfTest] FAIL echo mismatch: sent=" + expectedBytes.length + " got=" + got.length
						+ " firstDiffAt=" + diff + " rtt=" + fmtMs(rtt) + "ms");
			}
			finish(eq, got.length, rtt);
		} catch (Throwable t) {
			log("[MeshWorkerSelfTest] EXCEPTION decoding echo " + t);
			finish(false, 0, rtt);
		}
	}

	private static void finish(boolean pass, int size, double rtt) {
		if (finished) {
			return;
		}
		finished = true;
		publishResult(pass, size, rtt);
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

	private static int[][] toSparseTints(final SectionSnapshot snap) {
		int[][] result = new int[SectionSnapshot.TINT_RESOLVER_COUNT][];
		for (int r = 0; r < result.length; ++r) {
			byte[] mask = snap.tintMask[r];
			int count = 0;
			for (int i = 0; i < mask.length; ++i) {
				count += Integer.bitCount(mask[i] & 0xFF);
			}
			int[] values = new int[count];
			int next = 0;
			for (int i = 0; i < SectionSnapshot.TINT_CELLS; ++i) {
				if ((mask[i >> 3] & (1 << (i & 7))) != 0) {
					values[next++] = snap.tints[r][i];
				}
			}
			result[r] = values;
		}
		return result;
	}

	private static String fmtMs(double ms) {
		// One decimal place without String.format.
		long tenths = Math.round(ms * 10.0);
		return (tenths / 10) + "." + (tenths % 10);
	}

	/** Builds a deterministic synthetic snapshot. */
	private static SectionSnapshot buildSyntheticSnapshot() {
		SectionSnapshot s = new SectionSnapshot();
		s.jobId = 0x1234ABCD;
		s.sectionX = 1;
		s.sectionY = 2;
		s.sectionZ = 3;
		s.cameraRelX = 0.5f;
		s.cameraRelY = 1.5f;
		s.cameraRelZ = -2.25f;
		s.flags = 0b1011; // AO + cutoutLeaves + isRecompile

		final int paletteLen = 37; // exercises a 6-bit packed index
		int[] palette = new int[paletteLen];
		for (int i = 0; i < paletteLen; ++i) {
			palette[i] = i * 7 + 3; // arbitrary distinct fake global state ids
		}
		s.blockStatePalette = palette;

		long r = 0x9E3779B97F4A7C15L;
		int[] idx = new int[SectionSnapshot.VOLUME_CELLS];
		for (int i = 0; i < idx.length; ++i) {
			r = r * 6364136223846793005L + 1442695040888963407L;
			idx[i] = (int) ((r >>> 33) % paletteLen);
		}
		s.blockStateIndices = idx;

		byte[] bl = new byte[SectionSnapshot.LIGHT_NIBBLE_BYTES];
		byte[] sl = new byte[SectionSnapshot.LIGHT_NIBBLE_BYTES];
		for (int i = 0; i < bl.length; ++i) {
			r = r * 6364136223846793005L + 1442695040888963407L;
			bl[i] = (byte) (r >>> 40);
			sl[i] = (byte) (r >>> 48);
		}
		s.blockLightNibbles = bl;
		s.skyLightNibbles = sl;

		s.biomeBlendRadius = 2;
		int[] biome = new int[SectionSnapshot.BIOME_QUART_CELLS];
		for (int i = 0; i < biome.length; ++i) {
			r = r * 6364136223846793005L + 1442695040888963407L;
			biome[i] = (int) ((r >>> 40) & 0x3F); // small fake biome ids
		}
		s.biomeQuartIds = biome;

		// Deterministic version 2 fields.
		s.levelMinY = -64;
		s.levelHeight = 384;
		s.cardinalLighting = new float[] { 0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f };
		int[][] tints = new int[SectionSnapshot.TINT_RESOLVER_COUNT][];
		byte[][] tintMask = new byte[SectionSnapshot.TINT_RESOLVER_COUNT][];
		for (int rr = 0; rr < SectionSnapshot.TINT_RESOLVER_COUNT; ++rr) {
			int[] row = new int[SectionSnapshot.TINT_CELLS];
			byte[] mask = new byte[SectionSnapshot.TINT_MASK_BYTES];
			for (int i = 0; i < row.length; ++i) {
				r = r * 6364136223846793005L + 1442695040888963407L;
				row[i] = (int) (r >>> 32);
				if ((i + rr) % 5 == 0) {
					mask[i >> 3] |= (byte) (1 << (i & 7));
				}
			}
			tints[rr] = row;
			tintMask[rr] = mask;
		}
		s.tintMask = tintMask;
		s.tints = tints;

		return s;
	}

}

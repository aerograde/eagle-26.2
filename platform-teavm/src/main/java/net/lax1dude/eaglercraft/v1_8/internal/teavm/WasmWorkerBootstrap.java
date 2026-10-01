/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.workers.Worker;

/**
 * WORKERS PORT (wasm-gc) — the worker-spawn seam for the wasm-gc target.
 *
 * <p>The JS build spawns every Web Worker (integrated-server worker + the mesh worker
 * pool) from a Blob of the page's own {@code classes.js} text with a
 * {@code main(["_worker_process_"]);} tail. That mechanism cannot work on wasm-gc: the
 * program is a 115 MB {@code classes.wasm} binary, not evaluable script text. Instead
 * the wasm shell ({@code target_teavm_wasm_gc/src/web/index.html}) compiles the client
 * module and a DCE-stripped mesh module once, then stashes them plus two URLs on the page:
 * <ul>
 *   <li>{@code window.__eaglerWasmModule} — the precompiled {@code WebAssembly.Module}.
 *       Structured-cloning it to a worker SHARES the compiled code in V8 (no per-worker
 *       recompile or re-download; each worker only adds its own wasm-gc heap);</li>
 *   <li>{@code window.__eaglerMeshWasmModule} — the mesh-only module used by the pool,
 *       avoiding a full client/server heap in every section compiler;</li>
 *   <li>{@code window.__eaglerWasmWorkerBootstrapURL} — {@code worker-bootstrap.js}, the
 *       tiny classic-worker script every wasm worker is spawned from;</li>
 *   <li>{@code window.__eaglerWasmRuntimeURL} — {@code classes.wasm-runtime.js} for the
 *       worker's {@code importScripts}.</li>
 * </ul>
 *
 * <p>{@link #spawnWasmWorker} creates the worker on the bootstrap script and immediately
 * posts the boot payload as the FIRST message; postMessage ordering guarantees it arrives
 * before any role meta / model table / IPC the caller sends right after, and the bootstrap
 * queues + replays those until {@code exports.main(["_worker_process_"])} has installed the
 * real {@code self.onmessage}. From that point the eag26 two-channel protocol, the
 * {@code ~!SVDATA}/{@code ~!IPC} channels and the mesh codec run byte-identically to the
 * JS build (they are realm-agnostic postMessage protocols).
 *
 * <p><b>Gating rule (memory: wasm-gc-port-toolchain):</b> every call into this class is
 * guarded by {@link #isWasmGC()} at the call site. {@code PlatformDetector.isWebAssemblyGC()}
 * is a {@code @PlatformMarker} compile-time constant in app/platform code, so on the JS
 * target the whole class folds away (byte-identical JS build). NEVER call it from a
 * {@code :teavm-compat} facade or injected donor — it does not constant-fold there.
 */
public final class WasmWorkerBootstrap {

	private WasmWorkerBootstrap() {
	}

	/** Compile-time platform constant: true only when linking the wasm-gc target. */
	public static boolean isWasmGC() {
		return org.teavm.classlib.PlatformDetector.isWebAssemblyGC();
	}

	/**
	 * The {@code worker-bootstrap.js} URL for {@code Worker.create}, or {@code null} when
	 * the shell did not stash a compiled module (old shell / compile-once path failed) —
	 * callers then take their existing no-worker fallbacks (mesh pool disables itself,
	 * integrated server falls back to single-thread mode).
	 */
	public static String resolveBootstrapURL() {
		return resolveBootstrapURLJS();
	}

	@JSBody(params = {}, script = "try {"
			+ " if (typeof window !== \"undefined\" && window.__eaglerWasmModule"
			+ "   && typeof window.__eaglerWasmWorkerBootstrapURL === \"string\")"
			+ "   return window.__eaglerWasmWorkerBootstrapURL;"
			+ " return null; } catch(e) { return null; }")
	private static native String resolveBootstrapURLJS();

	public static String resolveServerBootstrapURL() {
		return resolveServerBootstrapURLJS();
	}

	@JSBody(params = {}, script = "try {"
			+ " if (typeof window !== \"undefined\" && window.__eaglerServerWasmModule) {"
			+ "   if (typeof window.__eaglerWasmServerWorkerBootstrapURL === \"string\")"
			+ "     return window.__eaglerWasmServerWorkerBootstrapURL;"
			+ "   if (typeof window.__eaglerWasmWorkerBootstrapURL === \"string\")"
			+ "     return window.__eaglerWasmWorkerBootstrapURL;"
			+ " }"
			+ " return null; } catch(e) { return null; }")
	private static native String resolveServerBootstrapURLJS();

	/**
	 * Spawn a wasm worker: create it on {@code worker-bootstrap.js} and immediately post
	 * the precompiled-module boot payload as its FIRST message. The caller may post its
	 * role meta right after this returns — the bootstrap queues and replays in order.
	 */
	public static Worker spawnWasmWorker(String url) {
		Worker w = Worker.create(url);
		postWasmBoot(w);
		return w;
	}

	/** Spawn a worker from the DCE-stripped mesh image when the shell provides it. */
	public static Worker spawnMeshWasmWorker(String url) {
		Worker w = Worker.create(url);
		postMeshWasmBoot(w);
		return w;
	}

	// logForward (?workerlog): opt-in mirror of the worker's console to the page as
	// {meta:"wlog:..."} strings so headless harnesses that only read window.__log can
	// see worker-side logs (unknown metas are ignored/logged by both consumers).
	@JSBody(params = { "w" }, script = "w.postMessage({ eaglerWasmBoot: {"
			+ " module: window.__eaglerServerWasmModule,"
			+ " runtime: (typeof window.__eaglerWasmRuntimeURL === \"string\") ? window.__eaglerWasmRuntimeURL : null,"
			+ " args: [\"_worker_process_\"],"
			+ " perfDebug: window.__eaglerPerfEnabled === true,"
			+ " chunkUnloadHardCap: window.__eaglerChunkUnloadHardCap === true,"
			+ " logForward: !!(typeof location !== \"undefined\" && location.search"
			+ "   && location.search.indexOf(\"workerlog\") >= 0)"
			+ " } });")
	private static native void postWasmBoot(Worker w);

	@JSBody(params = { "w" }, script = "w.postMessage({ eaglerWasmBoot: {"
			+ " module: window.__eaglerMeshWasmModule || window.__eaglerWasmModule,"
			+ " runtime: (typeof window.__eaglerWasmRuntimeURL === \"string\") ? window.__eaglerWasmRuntimeURL : null,"
			+ " args: [],"
			+ " perfDebug: window.__eaglerPerfEnabled === true,"
			+ " logForward: !!(typeof location !== \"undefined\" && location.search"
			+ "   && location.search.indexOf(\"workerlog\") >= 0)"
			+ " } });")
	private static native void postMeshWasmBoot(Worker w);

}

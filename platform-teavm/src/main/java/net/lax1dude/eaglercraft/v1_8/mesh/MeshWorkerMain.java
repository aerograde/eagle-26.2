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

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Message-driven entry point shared by mesh and integrated-server workers.
 */
public class MeshWorkerMain {

	@JSFunctor
	public interface MetaHandler extends JSObject {
		void onMeta(String meta);
	}

	@JSFunctor
	public interface BinaryHandler extends JSObject {
		void onBinary(ArrayBuffer buf);
	}

	// Split binary and control messages.
	@JSBody(params = { "meta", "bin" }, script = "self.onmessage = function(e) {"
			+ " if (e.data instanceof ArrayBuffer) { bin(e.data); }"
			+ " else if (e.data && typeof e.data.meta === \"string\") { meta(e.data.meta); } };")
	private static native void registerSelfDualChannel(MetaHandler meta, BinaryHandler bin);

	// Transfer binary data to the main thread.
	@JSBody(params = { "buf" }, script = "self.postMessage(buf, [buf]);")
	private static native void postSelfBinaryTransfer(ArrayBuffer buf);

	@JSBody(params = { "jobId", "buf" }, script = "self.postMessage({ jobId: jobId, buf: buf }, [buf]);")
	private static native void postSelfJobTransfer(int jobId, ArrayBuffer buf);

	// Send control metadata to the main thread.
	@JSBody(params = { "str" }, script = "self.postMessage({ meta: str });")
	private static native void postSelfMeta(String str);

	@JSBody(params = { "msg" }, script = "if (typeof console !== \"undefined\") console.log(msg);")
	private static native void log(String msg);

	private static boolean started = false;
	private static String role = null;

	// Compile mode
	private static final int KIND_JOB = 0;
	private static final int KIND_MODEL_TABLE = 1;
	/** Model-table delta merged into the live map. */
	private static final int KIND_TABLE_DELTA = 2;
	private static boolean compileMode = false;
	private static int pendingBinaryKind = KIND_JOB; // set by the preceding control message
	private static MeshWorkerCompiler compiler = null;
	// Pool results carry the job id beside the transferable payload.
	private static boolean poolFraming = false;

	/**
	 * Install the dual-channel handlers and announce readiness. Called from the
	 * {@code _worker_process_} branch of {@code MainClass.main}. Idempotent.
	 */
	public static void workerMain() {
		if (started) {
			return;
		}
		started = true;
		registerSelfDualChannel(MeshWorkerMain::onMeta, MeshWorkerMain::onBinary);
		postSelfMeta("mesh-worker:booted");
	}

	private static void onMeta(String meta) {
		if (meta == null) {
			return;
		}
		if (meta.startsWith("role:server")) {
			// Server role replaces the message handler and does not return.
			role = meta;
			net.lax1dude.eaglercraft.v1_8.sp.server.internal.teavm.ServerWorkerHost.boot(meta);
			return;
		}
		if (meta.startsWith("role:mesh")) {
			role = meta;
			postSelfMeta("mesh-worker:role-ack:" + meta);
		} else if (meta.equals("mode:compile")) {
			// Switch from echo to section compilation.
			enterCompileMode();
		} else if (meta.equals("framing:pool")) {
			poolFraming = true; // Prefix results with their job id.
		} else if (meta.equals("model-table")) {
			pendingBinaryKind = KIND_MODEL_TABLE; // next binary is the model table
		} else if (meta.equals("table-delta")) {
			pendingBinaryKind = KIND_TABLE_DELTA; // Next binary is a table delta.
		} else if (meta.equals("job")) {
			pendingBinaryKind = KIND_JOB; // next binary is a compile job snapshot
		} else {
			// Ignore unknown controls.
			log("[MeshWorker] ignoring non-mesh control: " + meta);
		}
	}

	private static void enterCompileMode() {
		if (compileMode) {
			return;
		}
		try {
			// Bootstrap registries before resolving model ids.
			MeshWorkerCompiler.bootstrapRegistries();
			compiler = new MeshWorkerCompiler();
			compileMode = true;
			postSelfMeta("mesh-worker:compile-booted");
		} catch (Throwable t) {
			postSelfMeta("mesh-worker:compile-boot-error:" + t);
		}
	}

	private static void onBinary(ArrayBuffer buf) {
		byte[] in = new Int8Array(buf).copyToJavaArray();

		if (!compileMode) {
			// Echo mode decodes and re-encodes the payload.
			byte[] out;
			try {
				SectionSnapshot snap = MeshJobCodec.decode(in);
				out = MeshJobCodec.encode(snap);
			} catch (Throwable t) {
				out = in;
				postSelfMeta("mesh-worker:codec-error:" + t);
			}
			postSelfBinaryTransfer(Int8Array.fromJavaArray(out).getBuffer());
			return;
		}

		// Compile mode.
		if (pendingBinaryKind == KIND_MODEL_TABLE) {
			pendingBinaryKind = KIND_JOB;
			try {
				compiler.setModelTable(in);
				// Include the worker registry verification report.
				postSelfMeta("mesh-worker:table-ready:" + in.length + "|" + ModelTableCodec.lastVerifyReport);
			} catch (Throwable t) {
				postSelfMeta("mesh-worker:table-error:" + t);
			}
			return;
		}

		if (pendingBinaryKind == KIND_TABLE_DELTA) {
			pendingBinaryKind = KIND_JOB;
			try {
				// Message order applies the delta before dependent jobs.
				compiler.mergeModelTable(in);
				postSelfMeta("mesh-worker:delta-ready:" + in.length);
			} catch (Throwable t) {
				postSelfMeta("mesh-worker:table-error:delta:" + t);
			}
			return;
		}

		// A compile job.
		try {
			byte[] out = compiler.compile(in);
			if (poolFraming) {
				// Keep metadata outside the payload. Prefixing used to allocate a second
				// result array and copy every vertex byte before the transferable copy.
				postSelfJobTransfer(compiler.lastJobId, Int8Array.fromJavaArray(out).getBuffer());
				return;
			}
			// Transfer detaches this fresh backing array.
			postSelfBinaryTransfer(Int8Array.fromJavaArray(out).getBuffer());
		} catch (Throwable t) {
			postSelfMeta("mesh-worker:compile-error:" + t);
		}
	}

}

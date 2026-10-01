/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 */
package net.lax1dude.eaglercraft.v1_8.mesh;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Mesh-only worker protocol root. Unlike {@link MeshWorkerMain}, this class has no
 * integrated-server branch, which lets TeaVM remove that entire graph from the
 * dedicated mesh-worker Wasm image.
 */
public final class MeshOnlyWorkerMain {

	@JSFunctor
	private interface MetaHandler extends JSObject {
		void onMeta(String meta);
	}

	@JSFunctor
	private interface BinaryHandler extends JSObject {
		void onBinary(ArrayBuffer buf);
	}

	private static final int KIND_JOB = 0;
	private static final int KIND_MODEL_TABLE = 1;
	private static final int KIND_TABLE_DELTA = 2;

	private static boolean started;
	private static boolean compileMode;
	private static boolean poolFraming;
	private static int pendingBinaryKind = KIND_JOB;
	private static MeshWorkerCompiler compiler;

	private MeshOnlyWorkerMain() {
	}

	@JSBody(params = { "meta", "bin" }, script = "self.onmessage = function(e) {"
			+ " if (e.data instanceof ArrayBuffer) { bin(e.data); }"
			+ " else if (e.data && typeof e.data.meta === 'string') { meta(e.data.meta); } };")
	private static native void registerSelfDualChannel(MetaHandler meta, BinaryHandler bin);

	@JSBody(params = { "buf" }, script = "self.postMessage(buf, [buf]);")
	private static native void postSelfBinaryTransfer(ArrayBuffer buf);

	@JSBody(params = { "jobId", "buf" }, script = "self.postMessage({ jobId: jobId, buf: buf }, [buf]);")
	private static native void postSelfJobTransfer(int jobId, ArrayBuffer buf);

	@JSBody(params = { "str" }, script = "self.postMessage({ meta: str });")
	private static native void postSelfMeta(String str);

	public static void workerMain() {
		if (started) {
			return;
		}
		started = true;
		registerSelfDualChannel(MeshOnlyWorkerMain::onMeta, MeshOnlyWorkerMain::onBinary);
		postSelfMeta("mesh-worker:booted");
	}

	private static void onMeta(String meta) {
		if (meta == null) {
			return;
		}
		if (meta.startsWith("role:mesh")) {
			postSelfMeta("mesh-worker:role-ack:" + meta);
		} else if (meta.equals("mode:compile")) {
			enterCompileMode();
		} else if (meta.equals("framing:pool")) {
			poolFraming = true;
		} else if (meta.equals("model-table")) {
			pendingBinaryKind = KIND_MODEL_TABLE;
		} else if (meta.equals("table-delta")) {
			pendingBinaryKind = KIND_TABLE_DELTA;
		} else if (meta.equals("job")) {
			pendingBinaryKind = KIND_JOB;
		}
	}

	private static void enterCompileMode() {
		if (compileMode) {
			return;
		}
		try {
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
			byte[] out;
			try {
				SectionSnapshot snapshot = MeshJobCodec.decode(in);
				out = MeshJobCodec.encode(snapshot);
			} catch (Throwable t) {
				out = in;
				postSelfMeta("mesh-worker:codec-error:" + t);
			}
			postSelfBinaryTransfer(Int8Array.fromJavaArray(out).getBuffer());
			return;
		}

		if (pendingBinaryKind == KIND_MODEL_TABLE) {
			pendingBinaryKind = KIND_JOB;
			try {
				compiler.setModelTable(in);
				postSelfMeta("mesh-worker:table-ready:" + in.length + "|" + ModelTableCodec.lastVerifyReport);
			} catch (Throwable t) {
				postSelfMeta("mesh-worker:table-error:" + t);
			}
			return;
		}

		if (pendingBinaryKind == KIND_TABLE_DELTA) {
			pendingBinaryKind = KIND_JOB;
			try {
				compiler.mergeModelTable(in);
				postSelfMeta("mesh-worker:delta-ready:" + in.length);
			} catch (Throwable t) {
				postSelfMeta("mesh-worker:table-error:delta:" + t);
			}
			return;
		}

		try {
			byte[] out = compiler.compile(in);
			if (poolFraming) {
				postSelfJobTransfer(compiler.lastJobId, Int8Array.fromJavaArray(out).getBuffer());
				return;
			}
			postSelfBinaryTransfer(Int8Array.fromJavaArray(out).getBuffer());
		} catch (Throwable t) {
			postSelfMeta("mesh-worker:compile-error:" + t);
		}
	}
}

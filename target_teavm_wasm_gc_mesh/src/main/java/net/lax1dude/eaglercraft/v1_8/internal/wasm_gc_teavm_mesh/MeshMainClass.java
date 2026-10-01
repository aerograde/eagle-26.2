package net.lax1dude.eaglercraft.v1_8.internal.wasm_gc_teavm_mesh;

import org.teavm.jso.JSBody;

/** Entry point for the dead-code-eliminated mesh-only wasm-gc worker image. */
public final class MeshMainClass {

	private MeshMainClass() {
	}

	public static void main(String[] args) {
		setStackTraceLimit();
		net.lax1dude.eaglercraft.v1_8.mesh.MeshOnlyWorkerMain.workerMain();
	}

	@JSBody(script = "Error.stackTraceLimit = 128;")
	private static native void setStackTraceLimit();
}

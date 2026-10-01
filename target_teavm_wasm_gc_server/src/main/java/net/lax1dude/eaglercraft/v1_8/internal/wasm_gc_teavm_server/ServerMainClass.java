package net.lax1dude.eaglercraft.v1_8.internal.wasm_gc_teavm_server;

import org.teavm.jso.JSBody;

/** Entry point for the dead-code-eliminated integrated-server Wasm image. */
public final class ServerMainClass {

	private ServerMainClass() {
	}

	public static void main(String[] args) {
		setStackTraceLimit();
		net.lax1dude.eaglercraft.v1_8.sp.server.internal.teavm.ServerOnlyWorkerMain.workerMain();
	}

	@JSBody(script = "Error.stackTraceLimit = 128;")
	private static native void setStackTraceLimit();
}

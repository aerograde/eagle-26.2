package net.lax1dude.eaglercraft.v1_8.internal;

import org.teavm.jso.JSBody;

/**
 * Web impl of the boot-splash signals. Sets JS globals the index.html boot flow polls.
 * Shadows the :platform stub by FQN on the web classpath.
 */
public final class PlatformBootSignal {

	private PlatformBootSignal() {
	}

	// Initial load done + title screen up -> index.html swaps the eagtek splash for the
	// enable-sound gate. Guarded globalThis write (TeaVM 0.13 emits strict-mode JS).
	@JSBody(params = {}, script = "globalThis.__eaglerMenuReady = true;")
	public static native void menuReady();

	// Headless autotest: a CDP probe sets __eaglerAutoTest='sp' before boot; the client
	// then auto-creates a test world to validate the integrated-server path end-to-end.
	@JSBody(params = {}, script = "return globalThis.__eaglerAutoTest === 'sp';")
	public static native boolean autoTestSp();

	// A ClientLevel arrived from the (integrated) server = world join succeeded.
	@JSBody(params = {}, script = "globalThis.__eaglerWorldReady = true;")
	public static native void worldReady();

	@JSBody(params = {}, script = "globalThis.__eaglerTerrainReady = false;")
	public static native void terrainLoading();

	@JSBody(params = {}, script = "globalThis.__eaglerTerrainReady = true;")
	public static native void terrainReady();
}

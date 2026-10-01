package net.lax1dude.eaglercraft.v1_8.internal;

/**
 * Web boot-splash signals. The real impl (in :platform-teavm) sets JS globals the
 * index.html boot flow polls; desktop is a no-op. FQN-shadowed on the web classpath like
 * the other platform stubs.
 */
public final class PlatformBootSignal {

	private PlatformBootSignal() {
	}

	/** Initial resource load finished and the title screen is up. No-op on desktop. */
	public static void menuReady() {
	}

	/**
	 * Headless autotest flag: true when a CDP probe set {@code __eaglerAutoTest='sp'}
	 * before boot — the client then creates a test world automatically so the
	 * integrated-server path can be validated without UI input. Always false on desktop.
	 */
	public static boolean autoTestSp() {
		return false;
	}

	/** A ClientLevel was received from the (integrated) server. No-op on desktop. */
	public static void worldReady() {
	}

	/** A new terrain load started. No-op on desktop. */
	public static void terrainLoading() {
	}

	/** The player's terrain section is compiled and visible. No-op on desktop. */
	public static void terrainReady() {
	}
}

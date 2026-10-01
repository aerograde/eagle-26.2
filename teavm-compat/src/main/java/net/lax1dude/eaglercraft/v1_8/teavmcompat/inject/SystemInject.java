package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.util.Collections;
import java.util.Map;

/**
 * Donor for java.lang.System (see plugin.JdkMethodInjector).
 */
public final class SystemInject {

	public static Map<String, String> getenv() {
		return Collections.emptyMap();
	}

	public static String getenv(String name) {
		return null;
	}

	public static void load(String filename) {
		// inert: native libraries do not exist in the browser runtime
	}

	public static void loadLibrary(String libname) {
		// inert: native libraries do not exist in the browser runtime
	}

	public static String mapLibraryName(String libname) {
		return libname;
	}

	// Phase 3.3b: reached from client Main / ClientShutdownWatchdog. TeaVM's
	// TSystem has no exit(int); route it to Runtime.exit so the platform's
	// "game tried to exit" handling (PlatformRuntime.exit) runs instead of a hard
	// process kill (which does not exist in the browser).
	public static void exit(int status) {
		Runtime.getRuntime().exit(status);
	}

}

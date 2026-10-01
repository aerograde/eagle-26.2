package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

/**
 * java.lang.Module — TeaVM 0.13 classlib has no module system (AOT, single
 * module universe). Reached from Class.getModule() (see ClassInject). Unnamed,
 * open, everything readable — the JDK answers for the unnamed module.
 */
public final class TModule {

	public static final TModule EAGLER_UNNAMED = new TModule();

	private TModule() {
	}

	public String getName() {
		return null; // unnamed module
	}

	public boolean isNamed() {
		return false;
	}

	public ClassLoader getClassLoader() {
		return null;
	}

	public boolean isOpen(String pn) {
		return true;
	}

	/**
	 * Reached from JvmProfiler.INSTANCE's clinit (getModule().getLayer().findModule("jdk.jfr")),
	 * which DOES run on wasm-gc when Commands is constructed during world creation. Returns a
	 * non-null empty layer so the probe short-circuits to NoOpProfiler instead of NPEing.
	 *
	 * NOTE: this is a teavm-compat FACADE method (TModule -> java.lang.Module). PlatformDetector
	 * .isWebAssemblyGC() does NOT constant-fold inside facade classes (it folds only in real classlib
	 * classes + the injected TClass donor, e.g. ClassInject.getModule() above), so a gate here would
	 * evaluate the fallback (false) and return null even on wasm-gc -> NPE. Hence UNCONDITIONAL. This
	 * is JS-safe: getModule() (gated in the TClass donor, which DOES fold) returns null on JS, so this
	 * getLayer() is only reached with a non-null module on wasm-gc; it is unreachable (DCE'd) on JS.
	 */
	public TModuleLayer getLayer() {
		return TModuleLayer.EAGLER_EMPTY;
	}
}

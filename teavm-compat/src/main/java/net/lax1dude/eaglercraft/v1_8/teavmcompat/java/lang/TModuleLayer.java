package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

import java.util.Optional;

/**
 * java.lang.ModuleLayer — link-only stub. Reached from JvmProfiler's
 * getModule().getLayer().findModule("jdk.jfr") probe, which is dead unless
 * --jfrProfile is passed (it is not). No module layers exist under TeaVM.
 */
public final class TModuleLayer {

	/**
	 * A single empty layer instance returned by TModule.getLayer() on wasm-gc so the
	 * JvmProfiler jfr-module probe (getModule().getLayer().findModule("jdk.jfr")) does not
	 * NPE. Only referenced from the wasm-gc-gated branch, so DCE removes it on the JS build.
	 */
	public static final TModuleLayer EAGLER_EMPTY = new TModuleLayer();

	private TModuleLayer() {
	}

	public Optional<TModule> findModule(String name) {
		return Optional.empty();
	}
}

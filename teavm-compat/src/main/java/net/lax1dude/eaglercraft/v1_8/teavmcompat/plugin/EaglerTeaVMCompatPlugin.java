package net.lax1dude.eaglercraft.v1_8.teavmcompat.plugin;

import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/**
 * Phase 3.0 classlib-compat plugin. Discovered by TeaVM's plugin loader via
 * META-INF/services/org.teavm.vm.spi.TeaVMPlugin on the compile classpath;
 * installs the {@link JdkMethodInjector} transformer that grafts missing JDK
 * methods onto existing teavm-classlib classes.
 *
 * <p>Brand-new JDK classes are NOT handled here — those are supplied by the
 * mapPackageHierarchy/mapClass rules in META-INF/teavm.properties.</p>
 */
public class EaglerTeaVMCompatPlugin implements TeaVMPlugin {

	@Override
	public void install(TeaVMHost host) {
		host.add(new JdkMethodInjector());
		// wasm-gc-ONLY: supply synthetic stubs for classlib-omitted supertypes that
		// break the wasm-gc virtual-table builder (see WasmGCMissingSupertypeStubber).
		// Gated on the TeaVMWasmGCHost extension so the JS link stays byte-identical
		// (JavaScriptTarget does not provide this extension -> getExtension == null).
		if (host.getExtension(org.teavm.backend.wasm.gc.TeaVMWasmGCHost.class) != null) {
			host.add(new WasmGCMissingSupertypeStubber());
			// wasm-gc-ONLY: re-enable classlib methods over-marked @UnsupportedOn(WEBASSEMBLY_GC)
			// that actually have a working wasm-gc body (e.g. java.util.Date.toString).
			host.add(new WasmGCUnsupportedOnStripper());
		}
	}

}

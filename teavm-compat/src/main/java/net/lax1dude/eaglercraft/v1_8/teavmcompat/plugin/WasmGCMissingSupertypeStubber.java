package net.lax1dude.eaglercraft.v1_8.teavmcompat.plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.teavm.model.AccessLevel;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.ElementModifier;

/**
 * wasm-gc-only classlib-gap fix: supplies minimal synthetic stubs for supertypes
 * that teavm-classlib 0.13 deliberately omits but that appear as the declared
 * superclass / superinterface of a reachable (link-only, never-instantiated)
 * library class in a Bootstrap-scale graph.
 *
 * <p><b>Why this is needed only on wasm-gc.</b> The wasm-gc backend builds real
 * GC-struct virtual tables and walks the entire supertype chain
 * ({@code WasmGCVirtualTableBuilder.initTable} recurses on {@code cls.getParent()}).
 * If a reachable class {@code C} declares a superclass {@code S} that is absent
 * from the final class source, {@code classes.get(S)} is null and the builder
 * NPEs ({@code "cls" is null}). The JS backend has no such pass, so the identical
 * graph links there (dangling parent, never dereferenced). Example seen in the
 * Bootstrap graph: {@code net.minecraft.server.gui.StatsComponent extends
 * javax.swing.JComponent} (the dedicated-server monitoring GUI; Swing/AWT is
 * entirely absent from teavm-classlib and dead on web), reachable because
 * {@code Bootstrap.bootStrap()} is the SERVER bootstrap.</p>
 *
 * <p><b>Correctness.</b> A missing supertype is by construction on a link-only,
 * never-instantiated path — otherwise the dependency analyzer would have pulled
 * the real class in. So an empty stub only needs to LINK: a class stub extends
 * {@code java.lang.Object}; an interface stub is {@code INTERFACE|ABSTRACT} with
 * no parent. This mirrors teavm-classlib's own choice to omit
 * ForkJoin/serialization/AWT/Swing machinery.</p>
 *
 * <p><b>Timing.</b> {@code DependencyClassSource.findClass} falls back to submitted
 * classes and {@code DependencyAnalyzer.linkClass(C)} links {@code C.getParent()}
 * AFTER the class transform runs, so a stub submitted while transforming {@code C}
 * is resolvable in time for the vtable builder.</p>
 *
 * <p>Installed by {@link EaglerTeaVMCompatPlugin} ONLY when the host provides the
 * {@code org.teavm.backend.wasm.gc.TeaVMWasmGCHost} extension (non-null on
 * wasm-gc, null on JS) — so the JS client link stays byte-identical.</p>
 */
public class WasmGCMissingSupertypeStubber implements ClassHolderTransformer {

	/** names already stubbed (dependency analysis can be multi-threaded). */
	private final Set<String> stubbed = ConcurrentHashMap.newKeySet();

	@Override
	public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
		ClassReaderSource source = context.getHierarchy().getClassSource();

		String parent = cls.getParent();
		if (parent != null && !parent.equals(cls.getName())) {
			ensureStub(parent, false, cls.getName(), "superclass", source, context);
		}
		for (String itf : cls.getInterfaces()) {
			ensureStub(itf, true, cls.getName(), "interface", source, context);
		}
	}

	private void ensureStub(String name, boolean asInterface, String fromClass, String kind,
			ClassReaderSource source, ClassHolderTransformerContext context) {
		// getClassSource().get(name) loads from the classpath (same call
		// JdkMethodInjector uses for donors), so a null result means the type is
		// genuinely absent — not merely not-yet-loaded.
		if (source.get(name) != null) {
			return;
		}
		if (!stubbed.add(name)) {
			return;
		}
		System.out.println("EAGLER_WASMGC_STUB: " + fromClass + " -> MISSING " + kind + " " + name
				+ " (submitting synthetic " + (asInterface ? "interface" : "class") + ")");
		ClassHolder stub = new ClassHolder(name);
		stub.setLevel(AccessLevel.PUBLIC);
		if (asInterface) {
			stub.getModifiers().add(ElementModifier.INTERFACE);
			stub.getModifiers().add(ElementModifier.ABSTRACT);
			// interfaces have no concrete parent; leave parent null
		} else {
			stub.getModifiers().add(ElementModifier.ABSTRACT);
			stub.setParent("java.lang.Object");
		}
		context.submit(stub);
	}

}

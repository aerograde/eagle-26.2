package net.lax1dude.eaglercraft.v1_8.teavmcompat.plugin;

import java.util.Set;

import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.MethodHolder;

/**
 * wasm-gc-only classlib-gap fix: strips {@code @org.teavm.interop.UnsupportedOn(WEBASSEMBLY_GC)}
 * from teavm-classlib methods that actually DO have a working wasm-gc code path, so the dependency
 * analyzer stops rejecting them ("... is not supported on current target").
 *
 * <p>Case seen first in the full client graph (not the Bootstrap spike):
 * {@code java.util.Date.toString()} (teavm {@code TDate.toString}) is annotated
 * {@code @UnsupportedOn(WEBASSEMBLY_GC)}, yet its body has a real wasm-gc branch
 * ({@code JS.unwrapString(toStringWebAssemblyGC(value))} → the {@code teavmDate.dateToString}
 * runtime import, which the wasm-gc runtime template DOES provide). The annotation is an upstream
 * over-marking; removing it lets the existing branch generate. Reached in the client via the F3
 * debug version dump ({@code VersionCommand.dumpVersion} → {@code Component.translationArg(new
 * Date())}), so it is statically reachable and must link even though it is runtime-rare.</p>
 *
 * <p>Installed by {@link EaglerTeaVMCompatPlugin} ONLY on the wasm-gc host (TeaVMWasmGCHost
 * extension present) → the JS client link is byte-identical. Targeted by an explicit
 * (class, method, descriptor-arity) allow-list so no method that lacks a real wasm-gc body is ever
 * re-enabled.</p>
 */
public class WasmGCUnsupportedOnStripper implements ClassHolderTransformer {

	private static final String UNSUPPORTED_ON = "org.teavm.interop.UnsupportedOn";

	/** class internal name -> method names (0-arg) whose @UnsupportedOn(WEBASSEMBLY_GC) to strip. */
	private static final Set<String> TDATE_ZERO_ARG_METHODS = Set.of("toString");

	@Override
	public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
		// Match either the classlib-internal name or the renamed name -- TeaVM may present the class
		// to transformers under either depending on the rename/transform phase order.
		var name = cls.getName();
		if (!name.equals("org.teavm.classlib.java.util.TDate") && !name.equals("java.util.Date")) {
			return;
		}
		for (MethodHolder method : cls.getMethods()) {
			if (method.parameterCount() == 0 && TDATE_ZERO_ARG_METHODS.contains(method.getName())
					&& method.getAnnotations().get(UNSUPPORTED_ON) != null) {
				method.getAnnotations().remove(UNSUPPORTED_ON);
				System.err.println("EAGLER_UNSUPPORTED_STRIP " + name + "." + method.getName()
						+ " (removed @UnsupportedOn)");
			}
		}
	}
}

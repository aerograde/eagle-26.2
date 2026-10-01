package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.lang.reflect.Type;
import java.net.URL;

/**
 * Donor for java.lang.Class (see plugin.JdkMethodInjector).
 *
 * <p>getGenericSuperclass returns the raw superclass: TeaVM keeps no generic
 * signatures, and teavm-classlib's TClass already implements
 * java.lang.reflect.Type, so the cast holds at runtime. Anonymous gson
 * TypeToken subclasses (which need a real ParameterizedType) will throw in
 * gson's own validation — a documented Phase 3 runtime risk, not a link
 * problem.</p>
 */
public final class ClassInject {

	/** placeholder: the target already implements getSuperclass (never copied) */
	public Class<?> getSuperclass() {
		return null;
	}

	/** placeholder: the target already implements getInterfaces (never copied) */
	public Class<?>[] getInterfaces() {
		return null;
	}

	public Type getGenericSuperclass() {
		return (Type) (Object) getSuperclass();
	}

	public Type[] getGenericInterfaces() {
		// valid array covariance at runtime: classlib TClass implements Type
		return (Type[]) (Object) getInterfaces();
	}

	@SuppressWarnings("rawtypes")
	public java.lang.reflect.TypeVariable[] getTypeParameters() {
		return new java.lang.reflect.TypeVariable[0];
	}

	public boolean isAnonymousClass() {
		return false;
	}

	public URL getResource(String name) {
		// no classpath resources by URL in the browser runtime
		return null;
	}

	// Phase 3.3b: reached from client Main via jspecify / logging / reflective
	// scans. No nested-class or module metadata under TeaVM (AOT, single module).
	public Class<?>[] getDeclaredClasses() {
		return new Class<?>[0];
	}

	public Object[] getSigners() {
		return null; // JDK returns null when a class has no signers
	}

	public Module getModule() {
		// Reached from JvmProfiler.INSTANCE's clinit (getModule().getLayer().findModule("jdk.jfr")),
		// which DOES run when net.minecraft.commands.Commands is constructed during world creation
		// (Commands L260: JvmProfiler.INSTANCE.isAvailable()). Returning null there NPEs at .getLayer().
		// Return the non-null unnamed module (TModule.EAGLER_UNNAMED; the stripPrefix rule renames
		// TModule -> java.lang.Module at build time, so the (Object) detour is only for javac).
		// UNCONDITIONAL (no isWebAssemblyGC gate): PlatformDetector.isWebAssemblyGC() does NOT constant-fold
		// inside teavm-compat facade classes OR injected donor methods (it only folds in real classlib +
		// application code), so a gate here evaluates the fallback (false) and returns null even on wasm-gc.
		// This is functionally correct on BOTH targets (getModule() -> unnamed -> getLayer() -> empty layer
		// -> findModule("jdk.jfr")=empty -> isPresent()=false -> NoOpProfiler, the intended web result). It
		// is also what the JDK contract requires (getModule() must never return null). The original
		// author's "clinit never runs" assumption was stale (it DOES run at world creation).
		return (Module) (Object) net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.TModule.EAGLER_UNNAMED;
	}

}

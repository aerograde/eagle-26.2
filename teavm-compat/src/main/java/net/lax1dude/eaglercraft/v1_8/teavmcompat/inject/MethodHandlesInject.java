package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.lang.invoke.MethodHandles;

/**
 * Donor for java.lang.invoke.MethodHandles (see plugin.JdkMethodInjector).
 * teavm-classlib 0.13's TMethodHandles is an empty shell kept for lambda
 * metafactory linkage; every reaching call site (netty Java25 cleaner, DFU
 * optics fallbacks) is runtime-guarded, so throwing is correct.
 */
public final class MethodHandlesInject {

	public static MethodHandles.Lookup lookup() {
		// Return null instead of throwing: netty's PlatformDependent0.<clinit> calls
		// lookup() BEFORE its VarHandle try/catch, so a thrown exception kills the whole
		// class init (=> "failed to create a child event loop" when the integrated server
		// builds its LocalChannel event-loop group). null lets it proceed into the try
		// (null.findVarHandle -> NPE -> caught -> non-Unsafe fallback queues). Every other
		// reaching call site catches Throwable, so null-then-NPE falls back identically.
		return null;
	}

	public static MethodHandles.Lookup publicLookup() {
		// Same rationale as lookup(): on a real JVM publicLookup() NEVER throws, so
		// netty calls it outside its try/catch guards (PlatformDependent probes).
		// Return null; the subsequent Lookup.find*() throws the checked
		// ReflectiveOperationException those guards actually catch (see LookupInject).
		return null;
	}

	public static java.lang.invoke.MethodHandle filterArguments(java.lang.invoke.MethodHandle target, int pos,
			java.lang.invoke.MethodHandle... filters) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

}

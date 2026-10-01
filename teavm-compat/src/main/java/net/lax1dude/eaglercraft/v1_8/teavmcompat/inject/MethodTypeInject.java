package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.lang.invoke.MethodType;

/**
 * Donor for java.lang.invoke.MethodType (see plugin.JdkMethodInjector and
 * MethodHandlesInject for the rationale).
 *
 * JVM-faithful degradation: on a real JVM {@code MethodType.methodType(...)}
 * NEVER throws for valid classes, so netty computes MethodTypes OUTSIDE its
 * reflective try/catch guards (ConcurrentSkipListIntObjMultimap.&lt;clinit&gt;
 * guards only the findStatic call, with catch(ReflectiveOperationException)).
 * Throwing unchecked here escaped those guards and killed the integrated
 * server's LocalChannel bind. Return null instead — the value only flows into
 * Lookup.find*(...), which throws checked NoSuchMethodException (LookupInject)
 * before ever dereferencing the type.
 */
public final class MethodTypeInject {

	public static MethodType methodType(Class<?> rtype) {
		return null;
	}

	public static MethodType methodType(Class<?> rtype, Class<?> ptype0) {
		return null;
	}

	public static MethodType methodType(Class<?> rtype, Class<?>[] ptypes) {
		return null;
	}

	public static MethodType methodType(Class<?> rtype, Class<?> ptype0, Class<?>... ptypes) {
		return null;
	}

}

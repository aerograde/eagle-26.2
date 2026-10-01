package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;

/**
 * Donor for java.lang.invoke.MethodHandles$Lookup (see plugin.JdkMethodInjector
 * and MethodHandlesInject for the rationale).
 *
 * JVM-faithful degradation: the real find* methods throw CHECKED
 * ReflectiveOperationException subtypes (NoSuchMethodException /
 * NoSuchFieldException / IllegalAccessException) and that is exactly what
 * netty's probe guards catch — e.g. ConcurrentSkipListIntObjMultimap.&lt;clinit&gt;
 * uses {@code catch (ReflectiveOperationException)}, NOT catch(Throwable).
 * The previous UnsupportedOperationException stubs (unchecked) sailed straight
 * through those narrow guards and killed the integrated server's LocalChannel
 * bind. "The browser runtime can't find this method" is semantically true.
 */
public final class LookupInject {

	public MethodHandle findStatic(Class<?> refc, String name, MethodType type) throws NoSuchMethodException {
		throw new NoSuchMethodException("MethodHandles are unsupported in the browser runtime: " + name);
	}

	public MethodHandle findVirtual(Class<?> refc, String name, MethodType type) throws NoSuchMethodException {
		throw new NoSuchMethodException("MethodHandles are unsupported in the browser runtime: " + name);
	}

	public MethodHandle findConstructor(Class<?> refc, MethodType type) throws NoSuchMethodException {
		throw new NoSuchMethodException("MethodHandles are unsupported in the browser runtime: <init>");
	}

	public VarHandle findVarHandle(Class<?> recv, String name, Class<?> type) throws NoSuchFieldException {
		throw new NoSuchFieldException("MethodHandles are unsupported in the browser runtime: " + name);
	}

	public VarHandle findStaticVarHandle(Class<?> decl, String name, Class<?> type) throws NoSuchFieldException {
		throw new NoSuchFieldException("MethodHandles are unsupported in the browser runtime: " + name);
	}

	public MethodHandle unreflectConstructor(Constructor<?> c) throws IllegalAccessException {
		throw new IllegalAccessException("MethodHandles are unsupported in the browser runtime");
	}

	public MethodHandle findStaticGetter(Class<?> refc, String name, Class<?> type) throws NoSuchFieldException {
		throw new NoSuchFieldException("MethodHandles are unsupported in the browser runtime: " + name);
	}

	public MethodHandle findGetter(Class<?> refc, String name, Class<?> type) throws NoSuchFieldException {
		throw new NoSuchFieldException("MethodHandles are unsupported in the browser runtime: " + name);
	}

}

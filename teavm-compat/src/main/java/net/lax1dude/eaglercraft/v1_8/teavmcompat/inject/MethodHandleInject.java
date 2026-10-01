package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Donor for java.lang.invoke.MethodHandle (see plugin.JdkMethodInjector).
 * invokeExact is signature-polymorphic in the real JDK; TeaVM sees one plain
 * descriptor per call site, so each reached shape is declared here (netty's
 * Java25 cleaner + PlatformDependent probes — all runtime-guarded).
 * Return-type-only overloads that Java source cannot express live in the
 * injector's synthetic-thrower list instead.
 */
public final class MethodHandleInject {

	public MethodHandle bindTo(Object x) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public ByteBuffer invokeExact(long address, int capacity) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public ByteBuffer invokeExact(long address, long capacity) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public long invokeExact(long address) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public Object invokeExact(Class<?> type, int size) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public long invokeExact(Buffer buffer) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public ByteBuffer invokeExact(ByteBuffer buffer, int position, int limit) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public void invokeExact(ByteBuffer buffer) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public MethodHandles.Lookup invokeExact(Class<?> type, MethodHandles.Lookup lookup) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public VarHandle invokeExact(MethodHandles.Lookup lookup, Class<?> recv, String name, Class<?> type) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public MethodHandle asType(MethodType newType) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public VarHandle invokeExact(Class<?> viewArrayClass, ByteOrder byteOrder) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public boolean invokeExact(Thread thread) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public Object invoke() {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

	public java.util.List<?> invoke(Object arg) {
		throw new UnsupportedOperationException("MethodHandles are unsupported in the browser runtime");
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.reflect;

import java.lang.reflect.InvocationHandler;

/**
 * java.lang.reflect.Proxy — link-only stub; dynamic proxies are impossible
 * under AOT compilation. Reached from guava/gson service plumbing that never
 * actually runs in the browser client.
 */
public class TProxy {

	protected TProxy() {
	}

	public static Object newProxyInstance(ClassLoader loader, Class<?>[] interfaces, InvocationHandler h) {
		throw new UnsupportedOperationException("no dynamic proxies under TeaVM");
	}

	public static boolean isProxyClass(Class<?> cl) {
		return false;
	}

	public static InvocationHandler getInvocationHandler(Object proxy) {
		throw new IllegalArgumentException("not a proxy instance");
	}

}

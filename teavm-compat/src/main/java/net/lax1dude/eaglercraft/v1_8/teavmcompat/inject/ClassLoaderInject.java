package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Donor for java.lang.ClassLoader (see plugin.JdkMethodInjector).
 */
public final class ClassLoaderInject {

	public Class<?> loadClass(String name) throws ClassNotFoundException {
		return Class.forName(name);
	}

	public Enumeration<URL> getResources(String name) throws IOException {
		return Collections.emptyEnumeration();
	}

	public static Enumeration<URL> getSystemResources(String name) throws IOException {
		return Collections.emptyEnumeration();
	}

	public URL getResource(String name) {
		return null;
	}

	public static URL getSystemResource(String name) {
		return null;
	}

}

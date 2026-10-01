package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.io.IOException;
import java.net.URL;

/**
 * java.net.URLClassLoader — link-only stub; no dynamic class loading exists
 * under AOT compilation.
 */
public class TURLClassLoader extends ClassLoader {

	private final URL[] urls;

	public TURLClassLoader(URL[] urls) {
		this.urls = urls;
	}

	public TURLClassLoader(URL[] urls, ClassLoader parent) {
		this.urls = urls;
	}

	public URL[] getURLs() {
		return urls;
	}

	public void close() throws IOException {
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.io.IOException;
import java.net.Proxy;
import java.net.URLConnection;

/**
 * Donor for java.net.URL (see plugin.JdkMethodInjector). Browser networking
 * cannot honor a JVM Proxy object, so the proxy overload uses TeaVM's existing
 * URL handler instead of remaining a link-only throwing stub.
 */
public abstract class URLInject {

	public URLConnection openConnection(Proxy proxy) throws IOException {
		return openConnection();
	}

	/** Placeholder for the existing TeaVM URL implementation. */
	public abstract URLConnection openConnection() throws IOException;

}

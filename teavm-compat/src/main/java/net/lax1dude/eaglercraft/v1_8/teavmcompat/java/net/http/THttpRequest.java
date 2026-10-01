package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net.http;

import java.net.URI;

/**
 * java.net.http.HttpRequest — link-only stub (see THttpClient). newBuilder
 * throws; no request is ever built in the browser runtime.
 */
public abstract class THttpRequest {

	protected THttpRequest() {
	}

	public static THttpRequest$Builder newBuilder() {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	public static THttpRequest$Builder newBuilder(URI uri) {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	public abstract URI uri();

	public abstract String method();

}

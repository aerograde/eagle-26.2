package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net.http;

/**
 * java.net.http.HttpClient — link-only stub. The JDK HTTP client cannot open
 * raw connections in the browser; all HTTP that MC 26.2 actually performs goes
 * through the browser fetch/WebSocket paths in platform-teavm. newHttpClient
 * throws so no instance ever exists.
 */
public abstract class THttpClient {

	protected THttpClient() {
	}

	public static THttpClient newHttpClient() {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	public static THttpClient$Builder newBuilder() {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	public void close() {
	}

	public java.util.concurrent.CompletableFuture sendAsync(THttpRequest request, THttpResponse$BodyHandler handler) {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	public THttpResponse send(THttpRequest request, THttpResponse$BodyHandler handler) {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

}

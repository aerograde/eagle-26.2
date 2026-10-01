package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net.http;

/**
 * java.net.http.HttpResponse$BodyHandlers — link-only marker with an inert
 * factory method. The HTTP client never runs in the browser, so ofInputStream
 * throws; it exists purely so references to it resolve at link time.
 */
public interface THttpResponse$BodyHandlers {

	static THttpResponse$BodyHandler ofInputStream() {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	static THttpResponse$BodyHandler discarding() {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	static THttpResponse$BodyHandler ofString(java.nio.charset.Charset charset) {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	static THttpResponse$BodyHandler ofString() {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	static THttpResponse$BodyHandler ofByteArray() {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	static THttpResponse$BodyHandler ofFile(java.nio.file.Path file) {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

}

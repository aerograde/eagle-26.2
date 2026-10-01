package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net.http;

import java.io.InputStream;
import java.util.function.Supplier;

/**
 * java.net.http.HttpRequest$BodyPublishers — link-only marker with an inert
 * factory method. The HTTP client never runs in the browser, so ofInputStream
 * throws; it exists purely so references to it resolve at link time.
 */
public interface THttpRequest$BodyPublishers {

	static THttpRequest$BodyPublisher ofInputStream(Supplier<? extends InputStream> streamSupplier) {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

	static THttpRequest$BodyPublisher fromPublisher(
			net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.TFlow$Publisher publisher, long contentLength) {
		throw new UnsupportedOperationException("no java.net.http HTTP client in the browser runtime");
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net.http;

import java.net.URI;
import java.time.Duration;

/**
 * java.net.http.HttpRequest$Builder — interface facade (see THttpClient). Only
 * the value-shaping methods that do not reference out-of-scope BodyPublisher are
 * declared; the client never runs so a builder is never obtained.
 */
public interface THttpRequest$Builder {

	THttpRequest$Builder uri(URI uri);

	THttpRequest$Builder header(String name, String value);

	THttpRequest$Builder setHeader(String name, String value);

	THttpRequest$Builder headers(String... headers);

	THttpRequest$Builder GET();

	THttpRequest$Builder HEAD();

	THttpRequest$Builder DELETE();

	THttpRequest$Builder POST(THttpRequest$BodyPublisher bodyPublisher);

	THttpRequest$Builder PUT(THttpRequest$BodyPublisher bodyPublisher);

	THttpRequest$Builder method(String method, THttpRequest$BodyPublisher bodyPublisher);

	THttpRequest$Builder timeout(Duration duration);

	THttpRequest build();

}

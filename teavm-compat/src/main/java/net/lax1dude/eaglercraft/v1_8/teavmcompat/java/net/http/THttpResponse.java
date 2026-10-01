package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net.http;

import java.net.URI;

/**
 * java.net.http.HttpResponse — interface facade (see THttpClient). Generic in
 * the body type like the real interface; never instantiated in the browser.
 */
public interface THttpResponse<T> {

	int statusCode();

	T body();

	URI uri();

	THttpHeaders headers();

}

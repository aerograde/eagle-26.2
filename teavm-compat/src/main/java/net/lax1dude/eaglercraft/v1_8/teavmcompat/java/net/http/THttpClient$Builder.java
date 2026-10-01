package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net.http;

/**
 * java.net.http.HttpClient$Builder — link-only marker (see THttpClient).
 *
 * HttpClient.newBuilder() throws, so a builder is never obtained; the interface
 * exists purely so references to it (as newBuilder's return type) resolve at
 * link time.
 */
public interface THttpClient$Builder {

	THttpClient$Builder executor(java.util.concurrent.Executor executor);

	THttpClient$Builder connectTimeout(java.time.Duration duration);

	THttpClient build();

}

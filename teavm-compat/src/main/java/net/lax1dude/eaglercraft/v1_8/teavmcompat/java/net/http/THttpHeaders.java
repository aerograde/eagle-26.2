package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net.http;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * java.net.http.HttpHeaders — link-only stub (see THttpClient). No HTTP response
 * is ever produced in the browser runtime, so this is only reached as a return
 * type; all accessors report an empty header set.
 */
public final class THttpHeaders {

	public Map<String, List<String>> map() {
		return Collections.emptyMap();
	}

	public Optional<String> firstValue(String name) {
		return Optional.empty();
	}

	public List<String> allValues(String name) {
		return Collections.emptyList();
	}

	public java.util.OptionalLong firstValueAsLong(String name) {
		return java.util.OptionalLong.empty();
	}
}

package org.slf4j;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.helpers.BasicMarker;

public final class MarkerFactory {

	private static final Map<String, Marker> markers = new HashMap<>();

	private MarkerFactory() {
	}

	public static Marker getMarker(String name) {
		synchronized (markers) {
			Marker ret = markers.get(name);
			if (ret == null) {
				ret = new BasicMarker(name);
				markers.put(name, ret);
			}
			return ret;
		}
	}

	public static Marker getDetachedMarker(String name) {
		return new BasicMarker(name);
	}

}

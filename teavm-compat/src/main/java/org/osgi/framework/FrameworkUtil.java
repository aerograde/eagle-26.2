package org.osgi.framework;

/**
 * Minimal OSGi FrameworkUtil (see Bundle) — always reports "not in OSGi".
 */
public final class FrameworkUtil {

	private FrameworkUtil() {
	}

	public static Bundle getBundle(Class<?> classFromBundle) {
		return null;
	}

}

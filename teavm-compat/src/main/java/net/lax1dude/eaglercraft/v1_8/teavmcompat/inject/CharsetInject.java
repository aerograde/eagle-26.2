package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.nio.charset.Charset;

/**
 * Donor for java.nio.charset.Charset (see plugin.JdkMethodInjector).
 */
public final class CharsetInject {

	public static boolean isSupported(String charsetName) {
		try {
			return Charset.forName(charsetName) != null;
		} catch (Exception e) {
			return false;
		}
	}

}

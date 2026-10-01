package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.lang.Character (see plugin.JdkMethodInjector). Donor rules:
 * no lambdas, no inner classes, no string concatenation, no static state.
 */
public final class CharacterInject {

	public static String toString(int codePoint) {
		return new String(Character.toChars(codePoint));
	}

	public static int codePointOf(String name) {
		// Unicode name tables do not exist under TeaVM
		throw new IllegalArgumentException("Character.codePointOf is unsupported in the browser runtime");
	}

}

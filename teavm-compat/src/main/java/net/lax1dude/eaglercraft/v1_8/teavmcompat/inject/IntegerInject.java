package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.lang.Integer (see plugin.JdkMethodInjector).
 */
public final class IntegerInject {

	public static long toUnsignedLong(int x) {
		return x & 0xFFFFFFFFL;
	}

	public static int sum(int a, int b) {
		return a + b;
	}

	public static int parseUnsignedInt(String s) throws NumberFormatException {
		return parseUnsignedInt(s, 10);
	}

	public static int parseUnsignedInt(String s, int radix) throws NumberFormatException {
		if (s == null) {
			throw new NumberFormatException("null");
		}
		if (s.length() > 0 && s.charAt(0) == '-') {
			throw new NumberFormatException("Illegal leading minus sign");
		}
		long value = Long.parseLong(s, radix);
		if (value < 0L || value > 0xFFFFFFFFL) {
			throw new NumberFormatException("String value exceeds range of unsigned int");
		}
		return (int) value;
	}

}

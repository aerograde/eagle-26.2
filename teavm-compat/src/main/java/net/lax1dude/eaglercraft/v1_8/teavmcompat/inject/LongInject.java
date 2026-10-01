package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.lang.Long (see plugin.JdkMethodInjector).
 */
public final class LongInject {

	public static long sum(long a, long b) {
		return a + b;
	}

	public static long parseUnsignedLong(String s) throws NumberFormatException {
		return parseUnsignedLong(s, 10);
	}

	public static long parseUnsignedLong(String s, int radix) throws NumberFormatException {
		if (s == null) {
			throw new NumberFormatException("null");
		}
		int len = s.length();
		if (len == 0) {
			throw new NumberFormatException("empty string");
		}
		if (s.charAt(0) == '-') {
			throw new NumberFormatException("Illegal leading minus sign");
		}
		// limit = floor(2^64-1 / radix): result beyond it must overflow on *radix
		long limit = Long.divideUnsigned(-1L, radix);
		long result = 0L;
		for (int i = 0; i < len; ++i) {
			int digit = Character.digit(s.charAt(i), radix);
			if (digit < 0) {
				throw new NumberFormatException(s);
			}
			if (Long.compareUnsigned(result, limit) > 0) {
				throw new NumberFormatException("String value exceeds range of unsigned long");
			}
			result *= radix;
			long next = result + digit;
			if (Long.compareUnsigned(next, result) < 0) {
				throw new NumberFormatException("String value exceeds range of unsigned long");
			}
			result = next;
		}
		return result;
	}

}

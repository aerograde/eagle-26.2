package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util;

/**
 * java.util.HexFormat — functional port of the subset MC 26.2 uses:
 * of()/ofDelimiter, withUpperCase/withLowerCase/withDelimiter/withPrefix/
 * withSuffix, formatHex, parseHex, toHexDigits, fromHexDigits(+ToLong).
 */
public final class THexFormat {

	private static final char[] LOWER = "0123456789abcdef".toCharArray();
	private static final char[] UPPER = "0123456789ABCDEF".toCharArray();

	private static final THexFormat HEX_FORMAT = new THexFormat("", "", "", false);

	private final String delimiter;
	private final String prefix;
	private final String suffix;
	private final boolean upperCase;

	private THexFormat(String delimiter, String prefix, String suffix, boolean upperCase) {
		this.delimiter = delimiter;
		this.prefix = prefix;
		this.suffix = suffix;
		this.upperCase = upperCase;
	}

	public static THexFormat of() {
		return HEX_FORMAT;
	}

	public static THexFormat ofDelimiter(String delimiter) {
		return new THexFormat(delimiter, "", "", false);
	}

	public THexFormat withDelimiter(String delimiter) {
		return new THexFormat(delimiter, prefix, suffix, upperCase);
	}

	public THexFormat withPrefix(String prefix) {
		return new THexFormat(delimiter, prefix, suffix, upperCase);
	}

	public THexFormat withSuffix(String suffix) {
		return new THexFormat(delimiter, prefix, suffix, upperCase);
	}

	public THexFormat withUpperCase() {
		return new THexFormat(delimiter, prefix, suffix, true);
	}

	public THexFormat withLowerCase() {
		return new THexFormat(delimiter, prefix, suffix, false);
	}

	public String delimiter() {
		return delimiter;
	}

	public String prefix() {
		return prefix;
	}

	public String suffix() {
		return suffix;
	}

	public boolean isUpperCase() {
		return upperCase;
	}

	private char[] digits() {
		return upperCase ? UPPER : LOWER;
	}

	public String formatHex(byte[] bytes) {
		return formatHex(bytes, 0, bytes.length);
	}

	public String formatHex(byte[] bytes, int fromIndex, int toIndex) {
		StringBuilder sb = new StringBuilder();
		char[] digits = digits();
		for (int i = fromIndex; i < toIndex; ++i) {
			if (i > fromIndex) {
				sb.append(delimiter);
			}
			sb.append(prefix);
			int b = bytes[i] & 0xFF;
			sb.append(digits[b >> 4]).append(digits[b & 0x0F]);
			sb.append(suffix);
		}
		return sb.toString();
	}

	public byte[] parseHex(CharSequence string) {
		return parseHex(string, 0, string.length());
	}

	public byte[] parseHex(CharSequence string, int fromIndex, int toIndex) {
		int stride = prefix.length() + 2 + suffix.length() + delimiter.length();
		int len = toIndex - fromIndex;
		if (len == 0) {
			return new byte[0];
		}
		int count = (len + delimiter.length()) / stride;
		if (count * stride - delimiter.length() != len) {
			throw new IllegalArgumentException("extra or missing hex characters");
		}
		byte[] out = new byte[count];
		int pos = fromIndex;
		for (int i = 0; i < count; ++i) {
			pos += prefix.length();
			int hi = fromHexDigit(string.charAt(pos));
			int lo = fromHexDigit(string.charAt(pos + 1));
			out[i] = (byte) ((hi << 4) | lo);
			pos += 2 + suffix.length() + delimiter.length();
		}
		return out;
	}

	public byte[] parseHex(char[] chars, int fromIndex, int toIndex) {
		return parseHex(new String(chars, fromIndex, toIndex - fromIndex));
	}

	public char toLowHexDigit(int value) {
		return digits()[value & 0x0F];
	}

	public char toHighHexDigit(int value) {
		return digits()[(value >> 4) & 0x0F];
	}

	public String toHexDigits(byte value) {
		return toHexDigits(value & 0xFFL, 2);
	}

	public String toHexDigits(char value) {
		return toHexDigits(value & 0xFFFFL, 4);
	}

	public String toHexDigits(short value) {
		return toHexDigits(value & 0xFFFFL, 4);
	}

	public String toHexDigits(int value) {
		return toHexDigits(value & 0xFFFFFFFFL, 8);
	}

	public String toHexDigits(long value) {
		return toHexDigits(value, 16);
	}

	public String toHexDigits(long value, int digits) {
		if (digits < 0 || digits > 16) {
			throw new IllegalArgumentException("number of digits: " + digits);
		}
		char[] table = digits();
		char[] out = new char[digits];
		for (int i = digits - 1; i >= 0; --i) {
			out[i] = table[(int) (value & 0x0FL)];
			value >>>= 4;
		}
		return new String(out);
	}

	public static boolean isHexDigit(int ch) {
		return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
	}

	public static int fromHexDigit(int ch) {
		if (ch >= '0' && ch <= '9') {
			return ch - '0';
		}
		if (ch >= 'a' && ch <= 'f') {
			return ch - 'a' + 10;
		}
		if (ch >= 'A' && ch <= 'F') {
			return ch - 'A' + 10;
		}
		throw new NumberFormatException("not a hexadecimal digit: \"" + (char) ch + "\" = " + ch);
	}

	public static int fromHexDigits(CharSequence string) {
		return fromHexDigits(string, 0, string.length());
	}

	public static int fromHexDigits(CharSequence string, int fromIndex, int toIndex) {
		int len = toIndex - fromIndex;
		if (len > 8) {
			throw new IllegalArgumentException("string length greater than 8: " + len);
		}
		int value = 0;
		for (int i = fromIndex; i < toIndex; ++i) {
			value = (value << 4) | fromHexDigit(string.charAt(i));
		}
		return value;
	}

	public static long fromHexDigitsToLong(CharSequence string) {
		return fromHexDigitsToLong(string, 0, string.length());
	}

	public static long fromHexDigitsToLong(CharSequence string, int fromIndex, int toIndex) {
		int len = toIndex - fromIndex;
		if (len > 16) {
			throw new IllegalArgumentException("string length greater than 16: " + len);
		}
		long value = 0L;
		for (int i = fromIndex; i < toIndex; ++i) {
			value = (value << 4) | fromHexDigit(string.charAt(i));
		}
		return value;
	}

	@Override
	public String toString() {
		return "uppercase: " + upperCase + ", delimiter: \"" + delimiter + "\", prefix: \"" + prefix
				+ "\", suffix: \"" + suffix + "\"";
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser implementation of {@code java.net.IDN}. TeaVM does not provide this
 * JDK class, but Minecraft uses it to canonicalize multiplayer host names.
 *
 * <p>The codec implements RFC 3492 Punycode, IDNA dot folding, the common
 * Nameprep mappings used by Java's IDNA 2003 API, label-length checks, and the
 * optional STD3 ASCII rules. It deliberately stays in Java so it works in the
 * main Wasm runtime and in Web Workers without depending on a window API.</p>
 */
public final class TIDN {

	public static final int ALLOW_UNASSIGNED = 0x01;
	public static final int USE_STD3_ASCII_RULES = 0x02;

	private static final int BASE = 36;
	private static final int TMIN = 1;
	private static final int TMAX = 26;
	private static final int SKEW = 38;
	private static final int DAMP = 700;
	private static final int INITIAL_BIAS = 72;
	private static final int INITIAL_N = 128;

	private TIDN() {
	}

	public static String toASCII(String input) {
		return toASCII(input, 0);
	}

	public static String toASCII(String input, int flag) {
		if (input == null) {
			throw new NullPointerException("input");
		}
		if ((flag & ~(ALLOW_UNASSIGNED | USE_STD3_ASCII_RULES)) != 0) {
			throw new IllegalArgumentException("unsupported IDN flag");
		}
		return transformLabels(input, flag, true);
	}

	public static String toUnicode(String input) {
		return toUnicode(input, 0);
	}

	public static String toUnicode(String input, int flag) {
		if (input == null) {
			throw new NullPointerException("input");
		}
		if ((flag & ~(ALLOW_UNASSIGNED | USE_STD3_ASCII_RULES)) != 0) {
			return input;
		}
		try {
			return transformLabels(input, flag, false);
		} catch (IllegalArgumentException ex) {
			// java.net.IDN.toUnicode is specified to return the original input
			// when conversion cannot be completed.
			return input;
		}
	}

	private static String transformLabels(String input, int flag, boolean ascii) {
		String value = foldDots(input);
		if (value.isEmpty()) {
			return value;
		}
		StringBuilder result = new StringBuilder(value.length() + 16);
		int start = 0;
		for (int i = 0; i <= value.length(); ++i) {
			if (i != value.length() && value.charAt(i) != '.') {
				continue;
			}
			if (i == start) {
				if (i == value.length() && i > 0) {
					break; // a final dot denotes the DNS root label
				}
				throw new IllegalArgumentException("empty IDN label");
			}
			String label = value.substring(start, i);
			String converted = ascii ? labelToASCII(label, flag) : labelToUnicode(label, flag);
			if (result.length() != 0) {
				result.append('.');
			}
			result.append(converted);
			start = i + 1;
		}
		if (value.charAt(value.length() - 1) == '.') {
			result.append('.');
		}
		if (ascii && result.length() > 255) {
			throw new IllegalArgumentException("IDN name exceeds 255 characters");
		}
		return result.toString();
	}

	private static String labelToASCII(String label, int flag) {
		boolean inputIsASCII = true;
		for (int i = 0; i < label.length(); ++i) {
			if (label.charAt(i) >= 128) {
				inputIsASCII = false;
				break;
			}
		}
		String result;
		if (inputIsASCII) {
			// The JDK preserves the spelling/case of labels that need no
			// Nameprep or Punycode conversion.
			result = label;
		} else {
			String mapped = mapLabel(label);
			boolean mappedIsASCII = true;
			for (int i = 0; i < mapped.length(); ++i) {
				if (mapped.charAt(i) >= 128) {
					mappedIsASCII = false;
					break;
				}
			}
			result = mappedIsASCII ? mapped : "xn--" + encodePunycode(toCodePoints(mapped));
		}
		validateASCIILabel(result, flag);
		return result;
	}

	private static String labelToUnicode(String label, int flag) {
		if (!startsWithACEPrefix(label)) {
			return label;
		}
		String decoded;
		try {
			decoded = fromCodePoints(decodePunycode(label.substring(4)));
			if (!labelToASCII(decoded, flag).equalsIgnoreCase(label)) {
				return label;
			}
		} catch (IllegalArgumentException ex) {
			return label;
		}
		return decoded;
	}

	private static String foldDots(String value) {
		StringBuilder result = null;
		for (int i = 0; i < value.length(); ++i) {
			char c = value.charAt(i);
			char mapped = c == '\u3002' || c == '\uFF0E' || c == '\uFF61' ? '.' : c;
			if (result != null) {
				result.append(mapped);
			} else if (mapped != c) {
				result = new StringBuilder(value.length());
				result.append(value, 0, i).append(mapped);
			}
		}
		return result == null ? value : result.toString();
	}

	private static String mapLabel(String label) {
		StringBuilder result = new StringBuilder(label.length());
		int[] codePoints = toCodePoints(label);
		for (int codePoint : codePoints) {
			// RFC 3454 table B.1 characters commonly encountered in names.
			if (codePoint == 0x00AD || codePoint == 0x034F || codePoint == 0x1806
					|| codePoint >= 0x180B && codePoint <= 0x180D
					|| codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
					|| codePoint == 0x2060 || codePoint >= 0xFE00 && codePoint <= 0xFE0F
					|| codePoint == 0xFEFF) {
				continue;
			}
			// Java's IDNA 2003 Nameprep mappings for the two important
			// multi/special-code-point cases.
			if (codePoint == 0x00DF) {
				result.append("ss");
				continue;
			}
			if (codePoint == 0x03C2) {
				codePoint = 0x03C3;
			}
			appendCodePoint(result, Character.toLowerCase(codePoint));
		}
		return result.toString();
	}

	private static void validateASCIILabel(String label, int flag) {
		if (label.isEmpty() || label.length() > 63) {
			throw new IllegalArgumentException("IDN label length is outside 1..63");
		}
		if ((flag & USE_STD3_ASCII_RULES) == 0) {
			return;
		}
		if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
			throw new IllegalArgumentException("IDN label begins or ends with a hyphen");
		}
		for (int i = 0; i < label.length(); ++i) {
			char c = label.charAt(i);
			if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
					|| c >= '0' && c <= '9' || c == '-')) {
				throw new IllegalArgumentException("IDN label violates STD3 ASCII rules");
			}
		}
	}

	private static boolean startsWithACEPrefix(String label) {
		return label.length() > 4
				&& (label.charAt(0) == 'x' || label.charAt(0) == 'X')
				&& (label.charAt(1) == 'n' || label.charAt(1) == 'N')
				&& label.charAt(2) == '-' && label.charAt(3) == '-';
	}

	private static String encodePunycode(int[] input) {
		StringBuilder output = new StringBuilder(input.length + 8);
		int basicCount = 0;
		for (int codePoint : input) {
			if (codePoint < 128) {
				output.append((char)codePoint);
				++basicCount;
			}
		}
		int handled = basicCount;
		if (basicCount > 0) {
			output.append('-');
		}
		long delta = 0;
		int n = INITIAL_N;
		int bias = INITIAL_BIAS;
		while (handled < input.length) {
			int next = Integer.MAX_VALUE;
			for (int codePoint : input) {
				if (codePoint >= n && codePoint < next) {
					next = codePoint;
				}
			}
			if (next == Integer.MAX_VALUE) {
				throw new IllegalArgumentException("invalid Punycode input");
			}
			delta += (long)(next - n) * (handled + 1L);
			if (delta > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("Punycode overflow");
			}
			n = next;
			for (int codePoint : input) {
				if (codePoint < n && ++delta > Integer.MAX_VALUE) {
					throw new IllegalArgumentException("Punycode overflow");
				}
				if (codePoint != n) {
					continue;
				}
				long q = delta;
				for (int k = BASE;; k += BASE) {
					int threshold = threshold(k, bias);
					if (q < threshold) {
						break;
					}
					output.append(encodeDigit(threshold + (int)((q - threshold) % (BASE - threshold))));
					q = (q - threshold) / (BASE - threshold);
				}
				output.append(encodeDigit((int)q));
				bias = adapt((int)delta, handled + 1, handled == basicCount);
				delta = 0;
				++handled;
			}
			++delta;
			++n;
		}
		return output.toString();
	}

	private static int[] decodePunycode(String input) {
		if (input.isEmpty()) {
			throw new IllegalArgumentException("empty Punycode label");
		}
		List<Integer> output = new ArrayList<>();
		int delimiter = input.lastIndexOf('-');
		int index = 0;
		if (delimiter >= 0) {
			for (int i = 0; i < delimiter; ++i) {
				char c = input.charAt(i);
				if (c >= 128) {
					throw new IllegalArgumentException("non-ASCII basic Punycode character");
				}
				output.add((int)c);
			}
			index = delimiter + 1;
		}
		int n = INITIAL_N;
		int i = 0;
		int bias = INITIAL_BIAS;
		while (index < input.length()) {
			int oldI = i;
			long weight = 1;
			for (int k = BASE;; k += BASE) {
				if (index >= input.length()) {
					throw new IllegalArgumentException("truncated Punycode label");
				}
				int digit = decodeDigit(input.charAt(index++));
				long nextI = i + (long)digit * weight;
				if (nextI > Integer.MAX_VALUE) {
					throw new IllegalArgumentException("Punycode overflow");
				}
				i = (int)nextI;
				int threshold = threshold(k, bias);
				if (digit < threshold) {
					break;
				}
				weight *= BASE - threshold;
				if (weight > Integer.MAX_VALUE) {
					throw new IllegalArgumentException("Punycode overflow");
				}
			}
			int size = output.size() + 1;
			bias = adapt(i - oldI, size, oldI == 0);
			long nextN = n + (long)i / size;
			if (nextN > 0x10FFFF || nextN >= 0xD800 && nextN <= 0xDFFF) {
				throw new IllegalArgumentException("invalid Punycode code point");
			}
			n = (int)nextN;
			i %= size;
			output.add(i, n);
			++i;
		}
		int[] result = new int[output.size()];
		for (int p = 0; p < result.length; ++p) {
			result[p] = output.get(p);
		}
		return result;
	}

	private static int adapt(int delta, int pointCount, boolean firstTime) {
		long value = firstTime ? delta / DAMP : delta / 2;
		value += value / pointCount;
		int k = 0;
		while (value > (BASE - TMIN) * TMAX / 2) {
			value /= BASE - TMIN;
			k += BASE;
		}
		return k + (int)((BASE - TMIN + 1L) * value / (value + SKEW));
	}

	private static int threshold(int k, int bias) {
		if (k <= bias) {
			return TMIN;
		}
		if (k >= bias + TMAX) {
			return TMAX;
		}
		return k - bias;
	}

	private static char encodeDigit(int digit) {
		return (char)(digit < 26 ? 'a' + digit : '0' + digit - 26);
	}

	private static int decodeDigit(char value) {
		if (value >= 'a' && value <= 'z') {
			return value - 'a';
		}
		if (value >= 'A' && value <= 'Z') {
			return value - 'A';
		}
		if (value >= '0' && value <= '9') {
			return value - '0' + 26;
		}
		throw new IllegalArgumentException("invalid Punycode digit");
	}

	private static int[] toCodePoints(String value) {
		int count = 0;
		for (int i = 0; i < value.length();) {
			char high = value.charAt(i++);
			if (Character.isHighSurrogate(high)) {
				if (i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
					throw new IllegalArgumentException("unpaired high surrogate");
				}
				++i;
			} else if (Character.isLowSurrogate(high)) {
				throw new IllegalArgumentException("unpaired low surrogate");
			}
			++count;
		}
		int[] result = new int[count];
		int offset = 0;
		for (int i = 0; i < value.length();) {
			char high = value.charAt(i++);
			result[offset++] = Character.isHighSurrogate(high)
					? Character.toCodePoint(high, value.charAt(i++)) : high;
		}
		return result;
	}

	private static String fromCodePoints(int[] codePoints) {
		StringBuilder result = new StringBuilder(codePoints.length);
		for (int codePoint : codePoints) {
			appendCodePoint(result, codePoint);
		}
		return result.toString();
	}

	private static void appendCodePoint(StringBuilder result, int codePoint) {
		if (codePoint < 0 || codePoint > 0x10FFFF
				|| codePoint >= 0xD800 && codePoint <= 0xDFFF) {
			throw new IllegalArgumentException("invalid Unicode code point");
		}
		if (codePoint < 0x10000) {
			result.append((char)codePoint);
		} else {
			int value = codePoint - 0x10000;
			result.append((char)(0xD800 | value >>> 10));
			result.append((char)(0xDC00 | value & 0x3FF));
		}
	}
}

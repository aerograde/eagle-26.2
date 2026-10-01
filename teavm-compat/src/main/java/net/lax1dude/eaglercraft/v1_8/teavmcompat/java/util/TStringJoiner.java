package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util;

/**
 * java.util.StringJoiner — absent from teavm-classlib 0.13. Full, faithful
 * implementation: it is a pure data structure with no platform dependencies.
 */
public final class TStringJoiner {

	private final String prefix;
	private final String delimiter;
	private final String suffix;

	/** The accumulated value, without prefix/suffix, built up on the fly. */
	private StringBuilder value;

	/**
	 * The value returned when no elements have been added. When null, an empty
	 * joiner renders as prefix + suffix.
	 */
	private String emptyValue;

	public TStringJoiner(CharSequence delimiter) {
		this(delimiter, "", "");
	}

	public TStringJoiner(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
		if (prefix == null) {
			throw new NullPointerException("The prefix must not be null");
		}
		if (delimiter == null) {
			throw new NullPointerException("The delimiter must not be null");
		}
		if (suffix == null) {
			throw new NullPointerException("The suffix must not be null");
		}
		this.prefix = prefix.toString();
		this.delimiter = delimiter.toString();
		this.suffix = suffix.toString();
		this.emptyValue = this.prefix + this.suffix;
	}

	public TStringJoiner setEmptyValue(CharSequence emptyValue) {
		if (emptyValue == null) {
			throw new NullPointerException("The empty value must not be null");
		}
		this.emptyValue = emptyValue.toString();
		return this;
	}

	@Override
	public String toString() {
		if (value == null) {
			return emptyValue;
		}
		if (suffix.isEmpty()) {
			return value.toString();
		}
		int initialLength = value.length();
		String result = value.append(suffix).toString();
		// reset the value so a subsequent toString() does not append suffix again
		value.setLength(initialLength);
		return result;
	}

	public TStringJoiner add(CharSequence newElement) {
		prepareBuilder().append(newElement);
		return this;
	}

	public TStringJoiner merge(TStringJoiner other) {
		if (other == null) {
			throw new NullPointerException();
		}
		if (other.value == null) {
			return this;
		}
		int otherLength = other.value.length();
		// append the other joiner's content (without its prefix/suffix)
		String otherContent = other.value.substring(other.prefix.length(), otherLength);
		prepareBuilder().append(otherContent);
		return this;
	}

	private StringBuilder prepareBuilder() {
		if (value != null) {
			value.append(delimiter);
		} else {
			value = new StringBuilder().append(prefix);
		}
		return value;
	}

	public int length() {
		if (value == null) {
			return emptyValue.length();
		}
		return value.length() + suffix.length();
	}
}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.lang.String (registered with copyConstructors, see
 * plugin.JdkMethodInjector): supplies the deprecated latin/hibyte constructor
 * String(byte[],int,int,int) by delegating to the char[] constructor the
 * classlib already has — after renaming, this(...) becomes
 * java.lang.String.&lt;init&gt;([C)V.
 */
public final class StringInject {

	/** placeholder: the target already has the (char[]) constructor (never copied) */
	private StringInject(char[] value) {
	}

	/** placeholder: the target already implements split(String) (never copied) */
	public String[] split(String regex) {
		return null;
	}

	public java.util.stream.Stream<String> lines() {
		return java.util.Arrays.stream(split("\n"));
	}

	public StringInject(byte[] ascii, int hibyte, int offset, int count) {
		this(latinChars(ascii, hibyte, offset, count));
	}

	private static char[] latinChars(byte[] ascii, int hibyte, int offset, int count) {
		char[] value = new char[count];
		int high = hibyte << 8;
		for (int i = 0; i < count; ++i) {
			value[i] = (char) (high | (ascii[offset + i] & 0xFF));
		}
		return value;
	}

}

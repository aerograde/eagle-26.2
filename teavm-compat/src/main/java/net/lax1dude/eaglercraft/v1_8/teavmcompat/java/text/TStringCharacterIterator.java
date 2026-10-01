package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.text;

import java.text.CharacterIterator;

/**
 * java.text.StringCharacterIterator — full port (TeaVM 0.13 classlib omits it).
 * Reached from ICU4J / text layout on the title-screen font path, so this is a
 * REAL implementation, not a throwing stub.
 */
public final class TStringCharacterIterator implements CharacterIterator {

	private final String text;
	private final int begin;
	private final int end;
	private int pos;

	public TStringCharacterIterator(String text) {
		this(text, 0);
	}

	public TStringCharacterIterator(String text, int pos) {
		this(text, 0, text.length(), pos);
	}

	public TStringCharacterIterator(String text, int begin, int end, int pos) {
		if (text == null) throw new NullPointerException();
		this.text = text;
		if (begin < 0 || begin > end || end > text.length()) throw new IllegalArgumentException("Invalid substring range");
		if (pos < begin || pos > end) throw new IllegalArgumentException("Invalid position");
		this.begin = begin;
		this.end = end;
		this.pos = pos;
	}

	@Override
	public char first() {
		pos = begin;
		return current();
	}

	@Override
	public char last() {
		pos = end == begin ? end : end - 1;
		return current();
	}

	@Override
	public char current() {
		return (pos >= begin && pos < end) ? text.charAt(pos) : DONE;
	}

	@Override
	public char next() {
		if (pos < end - 1) {
			pos++;
			return text.charAt(pos);
		}
		pos = end;
		return DONE;
	}

	@Override
	public char previous() {
		if (pos > begin) {
			pos--;
			return text.charAt(pos);
		}
		return DONE;
	}

	@Override
	public char setIndex(int position) {
		if (position < begin || position > end) throw new IllegalArgumentException("Invalid index");
		pos = position;
		return current();
	}

	@Override
	public int getBeginIndex() {
		return begin;
	}

	@Override
	public int getEndIndex() {
		return end;
	}

	@Override
	public int getIndex() {
		return pos;
	}

	@Override
	public Object clone() {
		try {
			return super.clone();
		} catch (CloneNotSupportedException e) {
			throw new InternalError(e);
		}
	}
}

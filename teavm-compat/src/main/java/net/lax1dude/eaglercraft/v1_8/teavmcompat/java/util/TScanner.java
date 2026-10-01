package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util;

import java.io.Closeable;
import java.io.InputStream;
import java.util.NoSuchElementException;

/**
 * java.util.Scanner — link-only stub. teavm-classlib does not provide Scanner
 * and the reaching paths are dead in the browser runtime; the scanner is always
 * empty (hasNext/hasNextLine are false and the readers throw).
 */
public final class TScanner implements Closeable {

	public TScanner(InputStream source) {
	}

	public TScanner(String source) {
	}

	public TScanner(Readable source) {
	}

	public boolean hasNext() {
		return false;
	}

	public boolean hasNextLine() {
		return false;
	}

	public String next() {
		throw new NoSuchElementException();
	}

	public String nextLine() {
		throw new NoSuchElementException();
	}

	@Override
	public void close() {
	}

	public TScanner useLocale(java.util.Locale locale) {
		return this;
	}

	public float nextFloat() {
		throw new NoSuchElementException();
	}

	public int nextInt() {
		throw new NoSuchElementException();
	}

	public long nextLong() {
		throw new NoSuchElementException();
	}

	public double nextDouble() {
		throw new NoSuchElementException();
	}

	public boolean nextBoolean() {
		throw new NoSuchElementException();
	}
}

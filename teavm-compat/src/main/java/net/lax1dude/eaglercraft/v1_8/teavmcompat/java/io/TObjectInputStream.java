package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.io;

import java.io.IOException;
import java.io.InputStream;

/**
 * java.io.ObjectInputStream — link-only stub; Java serialization does not
 * exist in the browser runtime (constructor throws).
 */
public class TObjectInputStream extends InputStream {

	public TObjectInputStream(InputStream in) throws IOException {
		throw new UnsupportedOperationException("no Java serialization in the browser runtime");
	}

	public final Object readObject() throws IOException, ClassNotFoundException {
		throw new UnsupportedOperationException("no Java serialization in the browser runtime");
	}

	@Override
	public int read() throws IOException {
		return -1;
	}

}

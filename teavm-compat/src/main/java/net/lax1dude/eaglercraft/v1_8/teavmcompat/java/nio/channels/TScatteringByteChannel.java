package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/** java.nio.channels.ScatteringByteChannel — signature surface used by netty's dead NIO transport. */
public interface TScatteringByteChannel extends ReadableByteChannel {

	long read(ByteBuffer[] dsts, int offset, int length) throws IOException;

	default long read(ByteBuffer[] dsts) throws IOException {
		return read(dsts, 0, dsts.length);
	}
}

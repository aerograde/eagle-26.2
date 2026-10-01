package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/** java.nio.channels.GatheringByteChannel — signature surface used by netty's dead NIO transport. */
public interface TGatheringByteChannel extends WritableByteChannel {

	long write(ByteBuffer[] srcs, int offset, int length) throws IOException;

	default long write(ByteBuffer[] srcs) throws IOException {
		return write(srcs, 0, srcs.length);
	}
}

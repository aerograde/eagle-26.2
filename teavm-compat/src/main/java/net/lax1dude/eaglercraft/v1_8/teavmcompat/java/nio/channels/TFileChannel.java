package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/**
 * java.nio.channels.FileChannel — link-only stub referenced by the
 * RandomAccessFile.getChannel() injection (which throws); no instance ever
 * exists in the browser runtime.
 */
public abstract class TFileChannel implements SeekableByteChannel {

	protected TFileChannel() {
	}

	public abstract int read(ByteBuffer dst) throws IOException;

	public abstract int write(ByteBuffer src) throws IOException;

	public abstract long size() throws IOException;

	public abstract long position() throws IOException;

	public abstract void force(boolean metaData) throws IOException;

	public int read(ByteBuffer dst, long position) throws IOException {
		throw new UnsupportedOperationException("no FileChannel support in the browser runtime");
	}

	public int write(ByteBuffer src, long position) throws IOException {
		throw new UnsupportedOperationException("no FileChannel support in the browser runtime");
	}

	public long transferTo(long position, long count, java.nio.channels.WritableByteChannel target)
			throws IOException {
		throw new UnsupportedOperationException("no FileChannel support in the browser runtime");
	}

}

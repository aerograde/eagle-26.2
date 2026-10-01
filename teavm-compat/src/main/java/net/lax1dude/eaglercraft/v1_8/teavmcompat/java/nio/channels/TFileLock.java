package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

import java.io.IOException;

/**
 * java.nio.channels.FileLock — link-only abstract stub. File locks are
 * meaningless in the browser sandbox; FileChannel is never obtained (see
 * TFileChannel), so no lock instance is ever acquired. release throws.
 */
public abstract class TFileLock implements AutoCloseable {

	protected TFileLock() {
	}

	public abstract boolean isValid();

	public abstract void release() throws IOException;

	public final boolean isShared() {
		return false;
	}

	public final long position() {
		return 0L;
	}

	public final long size() {
		return 0L;
	}

	public final boolean overlaps(long position, long size) {
		return false;
	}

	@Override
	public final void close() throws IOException {
		release();
	}

}

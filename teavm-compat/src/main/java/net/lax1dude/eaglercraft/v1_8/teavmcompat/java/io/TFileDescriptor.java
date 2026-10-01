package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.io;

/**
 * java.io.FileDescriptor — link-only stub. Referenced by FileInputStream /
 * FileOutputStream signatures; no real fds in the browser runtime.
 */
public final class TFileDescriptor {

	public static final TFileDescriptor in = new TFileDescriptor();
	public static final TFileDescriptor out = new TFileDescriptor();
	public static final TFileDescriptor err = new TFileDescriptor();

	public TFileDescriptor() {
	}

	public boolean valid() {
		return false;
	}

	public void sync() {
	}
}

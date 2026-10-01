package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.nio.channels.FileChannel;

/**
 * Donor for java.io.RandomAccessFile (see plugin.JdkMethodInjector).
 */
public final class RandomAccessFileInject {

	public FileChannel getChannel() {
		throw new UnsupportedOperationException("RandomAccessFile.getChannel is unsupported in the browser runtime");
	}

}

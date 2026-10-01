package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.nio.channels.FileChannel;

/**
 * Donor for java.io.FileInputStream (see plugin.JdkMethodInjector). getChannel()
 * is reached from client Main (resource/mmap paths); there are no real file
 * channels in the browser, so it throws — callers on the title-screen path
 * stream bytes directly instead.
 */
public final class FileInputStreamInject {

	public FileChannel getChannel() {
		throw new UnsupportedOperationException("no FileChannel support in the browser runtime");
	}

}

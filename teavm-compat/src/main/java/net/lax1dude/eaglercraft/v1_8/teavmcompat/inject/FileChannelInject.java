package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;

/**
 * Donor for java.nio.channels.FileChannel (see plugin.JdkMethodInjector). World
 * storage uses the Eagler VFS, not real file channels; the static open() and the
 * lock/position/truncate surface therefore throw (never reached on the title
 * path).
 */
public final class FileChannelInject {

	public static FileChannel open(Path path, OpenOption... options) throws IOException {
		throw new UnsupportedOperationException("FileChannel.open is unsupported in the browser runtime");
	}

}

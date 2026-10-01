package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file;

import java.io.Closeable;
import java.io.IOException;

/**
 * java.nio.file.WatchService — interface facade. The browser VFS cannot observe
 * external filesystem changes, so no watch service is ever created; this exists
 * only so FileSystem.newWatchService signatures link.
 */
public interface TWatchService extends Closeable {

	@Override
	void close() throws IOException;

	TWatchKey poll();

	TWatchKey take() throws InterruptedException;

}

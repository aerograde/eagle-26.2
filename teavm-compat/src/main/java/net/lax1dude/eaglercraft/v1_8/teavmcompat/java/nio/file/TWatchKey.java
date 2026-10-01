package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file;

import java.util.List;

/**
 * java.nio.file.WatchKey — interface facade (see TWatchService). No key is ever
 * registered in the browser VFS.
 */
public interface TWatchKey {

	boolean isValid();

	List<TWatchEvent<?>> pollEvents();

	boolean reset();

	void cancel();

	TWatchable watchable();

}

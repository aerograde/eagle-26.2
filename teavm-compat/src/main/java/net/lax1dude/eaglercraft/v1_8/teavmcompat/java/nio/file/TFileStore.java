package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file;

import java.io.IOException;

/**
 * java.nio.file.FileStore — link-only stub; Files.getFileStore (see
 * FilesInject) throws, so no instance ever exists. MC uses it only for
 * disk-space warnings.
 */
public abstract class TFileStore {

	protected TFileStore() {
	}

	public abstract String name();

	public abstract String type();

	public abstract boolean isReadOnly();

	public abstract long getTotalSpace() throws IOException;

	public abstract long getUsableSpace() throws IOException;

	public abstract long getUnallocatedSpace() throws IOException;

}

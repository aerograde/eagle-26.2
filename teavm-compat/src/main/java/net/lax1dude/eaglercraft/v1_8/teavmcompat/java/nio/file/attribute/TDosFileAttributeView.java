package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file.attribute;

import java.io.IOException;

/**
 * java.nio.file.attribute.DosFileAttributeView — interface facade. Extends
 * TBasicFileAttributeView with the covariant DOS readAttributes plus the DOS
 * flag setters; none of these are reachable in the browser VFS.
 */
public interface TDosFileAttributeView extends TBasicFileAttributeView {

	@Override
	TDosFileAttributes readAttributes() throws IOException;

	void setReadOnly(boolean value) throws IOException;

	void setHidden(boolean value) throws IOException;

	void setSystem(boolean value) throws IOException;

	void setArchive(boolean value) throws IOException;

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file.attribute;

import java.nio.file.attribute.BasicFileAttributes;

/**
 * java.nio.file.attribute.DosFileAttributes — interface facade. Extends the
 * classlib-provided BasicFileAttributes; DOS attributes are never populated in
 * the browser VFS.
 */
public interface TDosFileAttributes extends BasicFileAttributes {

	boolean isReadOnly();

	boolean isHidden();

	boolean isArchive();

	boolean isSystem();

}

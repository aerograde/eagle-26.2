package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file.attribute;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * java.nio.file.attribute.BasicFileAttributeView — interface facade. The real
 * super-interface FileAttributeView is absent from teavm-classlib, so this is a
 * standalone interface. BasicFileAttributes and FileTime are provided by the
 * classlib and referenced directly.
 */
public interface TBasicFileAttributeView {

	String name();

	BasicFileAttributes readAttributes() throws IOException;

	void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException;

}

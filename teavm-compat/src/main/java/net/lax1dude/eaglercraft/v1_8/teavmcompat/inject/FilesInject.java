package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

/**
 * Donor for java.nio.file.Files (see plugin.JdkMethodInjector). The browser has
 * no POSIX/DOS filesystem — world storage is served through the Eagler VFS on a
 * different path — so the low-level attribute/link/channel operations degrade to
 * throwing stubs; they sit on world-save code that the title screen never runs.
 */
public final class FilesInject {

	public static FileStore getFileStore(Path path) throws IOException {
		// MC only uses this for free-disk-space warnings
		throw new UnsupportedOperationException("Files.getFileStore is unsupported in the browser runtime");
	}

	public static Path createLink(Path link, Path existing) throws IOException {
		throw new UnsupportedOperationException("Files.createLink is unsupported in the browser runtime");
	}

	public static <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
		throw new UnsupportedOperationException("Files.getFileAttributeView is unsupported in the browser runtime");
	}

	public static Object getAttribute(Path path, String attribute, LinkOption... options) throws IOException {
		throw new UnsupportedOperationException("Files.getAttribute is unsupported in the browser runtime");
	}

	public static UserPrincipal getOwner(Path path, LinkOption... options) throws IOException {
		throw new UnsupportedOperationException("Files.getOwner is unsupported in the browser runtime");
	}

	public static Set<PosixFilePermission> getPosixFilePermissions(Path path, LinkOption... options) throws IOException {
		throw new UnsupportedOperationException("Files.getPosixFilePermissions is unsupported in the browser runtime");
	}

	public static SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
			FileAttribute<?>... attrs) throws IOException {
		throw new UnsupportedOperationException("Files.newByteChannel is unsupported in the browser runtime");
	}

	public static Path setLastModifiedTime(Path path, FileTime time) throws IOException {
		throw new UnsupportedOperationException("Files.setLastModifiedTime is unsupported in the browser runtime");
	}

	public static Path setPosixFilePermissions(Path path, Set<PosixFilePermission> perms) throws IOException {
		throw new UnsupportedOperationException("Files.setPosixFilePermissions is unsupported in the browser runtime");
	}

}

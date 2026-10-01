package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file.attribute;

/**
 * java.nio.file.attribute.PosixFilePermission — enum mirror. POSIX permissions
 * are meaningless in the browser VFS, but the enum constants are referenced by
 * attribute-view signatures, so the full set is mirrored.
 */
public enum TPosixFilePermission {
	OWNER_READ,
	OWNER_WRITE,
	OWNER_EXECUTE,
	GROUP_READ,
	GROUP_WRITE,
	GROUP_EXECUTE,
	OTHERS_READ,
	OTHERS_WRITE,
	OTHERS_EXECUTE
}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file;

import java.nio.file.FileSystemException;

/**
 * java.nio.file.AtomicMoveNotSupportedException — absent from teavm-classlib
 * 0.13. Extends FileSystemException in the real JDK; only referenced by
 * Files.move catch clauses that never run in the browser VFS.
 */
public class TAtomicMoveNotSupportedException extends FileSystemException {

	public TAtomicMoveNotSupportedException(String source, String target, String reason) {
		super(source, target, reason);
	}

}

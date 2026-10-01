package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file;

/**
 * java.nio.file.ProviderMismatchException — absent from teavm-classlib 0.13.
 * Extends IllegalArgumentException in the real JDK; only a guard/catch type on
 * the file-provider paths that never diverge in the single browser VFS.
 */
public class TProviderMismatchException extends IllegalArgumentException {

	public TProviderMismatchException() {
	}

	public TProviderMismatchException(String message) {
		super(message);
	}

}

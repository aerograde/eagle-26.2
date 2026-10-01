package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

/**
 * java.lang.UnsatisfiedLinkError — absent from teavm-classlib 0.13. Thrown by
 * native-library guards in JNA/netty/oshi fallback paths.
 */
public class TUnsatisfiedLinkError extends LinkageError {

	public TUnsatisfiedLinkError() {
	}

	public TUnsatisfiedLinkError(String message) {
		super(message);
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

/**
 * java.net.PortUnreachableException — absent from teavm-classlib 0.13. Extends
 * SocketException (mirrored here by TSocketException); only a catch-clause type.
 */
public class TPortUnreachableException extends TSocketException {

	public TPortUnreachableException() {
	}

	public TPortUnreachableException(String message) {
		super(message);
	}

}

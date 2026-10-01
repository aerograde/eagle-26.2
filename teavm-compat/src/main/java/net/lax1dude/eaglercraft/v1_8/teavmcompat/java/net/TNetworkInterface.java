package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.util.Collections;
import java.util.Enumeration;

/**
 * java.net.NetworkInterface — link-only stub (see TInetAddress).
 */
public class TNetworkInterface {

	TNetworkInterface() {
	}

	public static Enumeration<TNetworkInterface> getNetworkInterfaces() {
		return Collections.emptyEnumeration();
	}

	public Enumeration<TInetAddress> getInetAddresses() {
		return Collections.emptyEnumeration();
	}

	public String getName() {
		return "lo";
	}

	public String getDisplayName() {
		return "loopback";
	}

	public byte[] getHardwareAddress() {
		return null;
	}

	public boolean isUp() {
		return false;
	}

	public boolean isLoopback() {
		return true;
	}

	// Phase 3.3b: reached from SystemReport / netty enumeration (compile-linked).
	public static TNetworkInterface getByInetAddress(TInetAddress addr) {
		return null;
	}

	public boolean isVirtual() {
		return false;
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.jar;

/**
 * java.util.jar.JarFile — link-only stub; jar files do not exist in the
 * browser runtime (see TJarURLConnection).
 */
public class TJarFile {

	TJarFile() {
	}

	public String getName() {
		return "";
	}

	public java.util.Enumeration<?> entries() {
		return java.util.Collections.emptyEnumeration();
	}

	public void close() {
	}

}

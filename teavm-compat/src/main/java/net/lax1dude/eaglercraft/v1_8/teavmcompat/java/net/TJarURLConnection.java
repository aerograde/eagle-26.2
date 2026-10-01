package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarFile;

/**
 * java.net.JarURLConnection — link-only stub; jar: URLs do not exist in the
 * browser runtime (assets ship via EPK, see PLAN.md).
 */
public abstract class TJarURLConnection extends URLConnection {

	protected TJarURLConnection(URL url) {
		super(url);
	}

	public URL getJarFileURL() {
		return null;
	}

	public String getEntryName() {
		return null;
	}

	public JarFile getJarFile() throws IOException {
		throw new IOException("no jar: URLs in the browser runtime");
	}

}

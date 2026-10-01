package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * java.lang.Process — link-only stub; Runtime.exec (see RuntimeInject) always
 * throws IOException in the browser runtime, so no instance ever exists.
 */
public class TProcess {

	public TProcess() {
	}

	public OutputStream getOutputStream() {
		throw new UnsupportedOperationException("no processes in the browser runtime");
	}

	public InputStream getInputStream() {
		throw new UnsupportedOperationException("no processes in the browser runtime");
	}

	public InputStream getErrorStream() {
		throw new UnsupportedOperationException("no processes in the browser runtime");
	}

	public int waitFor() throws InterruptedException {
		throw new UnsupportedOperationException("no processes in the browser runtime");
	}

	public int exitValue() {
		throw new IllegalThreadStateException();
	}

	public void destroy() {
	}

	public TProcess destroyForcibly() {
		return this;
	}

	public boolean isAlive() {
		return false;
	}

	public long pid() {
		return 0L;
	}

}

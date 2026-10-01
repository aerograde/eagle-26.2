package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.io.IOException;

/**
 * Donor for java.lang.Runtime (see plugin.JdkMethodInjector).
 */
public final class RuntimeInject {

	public Process exec(String command) throws IOException {
		throw new IOException("no processes in the browser runtime");
	}

	public Process exec(String[] cmdarray) throws IOException {
		throw new IOException("no processes in the browser runtime");
	}

	public Process exec(String[] cmdarray, String[] envp) throws IOException {
		throw new IOException("no processes in the browser runtime");
	}

	public long maxMemory() {
		// 512 MB: matches the upstream EaglercraftX guidance for browser heaps
		return 512L * 1024L * 1024L;
	}

	public void addShutdownHook(Thread hook) {
		// inert: the page lifecycle replaces JVM shutdown hooks
	}

	public boolean removeShutdownHook(Thread hook) {
		return false;
	}

}

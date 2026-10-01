package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * java.lang.ProcessBuilder — link-only stub. No child processes exist in the
 * browser runtime; start() throws (callers sit in catch-and-continue paths).
 */
public class TProcessBuilder {

	private List<String> command;

	public TProcessBuilder(String... command) {
		this.command = new ArrayList<>(Arrays.asList(command));
	}

	public TProcessBuilder(List<String> command) {
		this.command = command;
	}

	public TProcessBuilder command(String... command) {
		this.command = new ArrayList<>(Arrays.asList(command));
		return this;
	}

	public TProcessBuilder command(List<String> command) {
		this.command = command;
		return this;
	}

	public List<String> command() {
		return this.command;
	}

	public Map<String, String> environment() {
		return new HashMap<>();
	}

	public TProcessBuilder directory(java.io.File directory) {
		return this;
	}

	public TProcessBuilder redirectErrorStream(boolean redirectErrorStream) {
		return this;
	}

	public TProcessBuilder inheritIO() {
		return this;
	}

	public Process start() throws IOException {
		throw new IOException("no child processes in the browser runtime");
	}
}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.management;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.TThread$State;

/**
 * java.lang.management.ThreadInfo — inert subset; TThreadMXBean never produces
 * instances (thread dumps are empty in the browser runtime).
 */
public class TThreadInfo {

	TThreadInfo() {
	}

	public String getThreadName() {
		return "main";
	}

	public long getThreadId() {
		return 1L;
	}

	public StackTraceElement[] getStackTrace() {
		return new StackTraceElement[0];
	}

	public TThread$State getThreadState() {
		return null;
	}

	public boolean isDaemon() {
		return false;
	}

}

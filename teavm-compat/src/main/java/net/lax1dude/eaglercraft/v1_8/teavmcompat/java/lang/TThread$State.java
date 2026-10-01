package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

/**
 * java.lang.Thread$State — complete enum mirror. Reached because
 * Thread.getState()/ThreadInfo.getThreadState() return it and telemetry code
 * reads Thread.State.RUNNABLE / TERMINATED, so all six real constants are
 * present.
 */
public enum TThread$State {
	NEW,
	RUNNABLE,
	BLOCKED,
	WAITING,
	TIMED_WAITING,
	TERMINATED
}

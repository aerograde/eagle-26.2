package net.lax1dude.eaglercraft.v1_8.teavmcompat.jdk.jfr;

/**
 * jdk.jfr.Event — inert base class. Many netty and Minecraft event classes
 * extend jdk.jfr.Event and inherit these methods; making this a concrete class
 * with no-op bodies lets all of those subclasses link and run. Flight
 * recording does not exist in the browser, so nothing is ever recorded:
 * isEnabled()/shouldCommit() return false and begin/end/commit/set do nothing.
 */
public class TEvent {

	public TEvent() {
	}

	public void begin() {
	}

	public void end() {
	}

	public void commit() {
	}

	public boolean isEnabled() {
		return false;
	}

	public boolean shouldCommit() {
		return false;
	}

	public void set(int index, Object value) {
	}
}

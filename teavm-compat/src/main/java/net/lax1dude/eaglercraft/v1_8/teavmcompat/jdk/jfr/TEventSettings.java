package net.lax1dude.eaglercraft.v1_8.teavmcompat.jdk.jfr;

import java.time.Duration;

/**
 * jdk.jfr.EventSettings — inert fluent stub (see TEvent/TRecording). JfrProfiler
 * chains Recording.enable(...).withStackTrace()/withPeriod(...); flight recording
 * does not exist in the browser, so every setter is a no-op returning this.
 */
public class TEventSettings {

	public TEventSettings with(String name, String value) {
		return this;
	}

	public TEventSettings withStackTrace() {
		return this;
	}

	public TEventSettings withoutStackTrace() {
		return this;
	}

	public TEventSettings withThreshold(Duration threshold) {
		return this;
	}

	public TEventSettings withPeriod(Duration period) {
		return this;
	}
}

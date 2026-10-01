package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.time.Instant;
import java.util.Date;

/**
 * Donor for java.util.Date (see plugin.JdkMethodInjector). java.time maps to
 * the threeten-bp port bundled inside teavm-classlib, which has
 * toEpochMilli/ofEpochMilli.
 */
public final class DateInject {

	/** placeholder: the target already implements getTime (never copied) */
	public long getTime() {
		return 0L;
	}

	public static Date from(Instant instant) {
		return new Date(instant.toEpochMilli());
	}

	public Instant toInstant() {
		return Instant.ofEpochMilli(getTime());
	}

}

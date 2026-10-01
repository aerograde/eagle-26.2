package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Donor for java.util.concurrent.TimeUnit (see plugin.JdkMethodInjector). The
 * java.time.Duration overload of convert() is missing from TeaVM 0.13's TTimeUnit.
 */
public final class TimeUnitInject {

	public long convert(Duration duration) {
		return convert(duration.toNanos(), TimeUnit.NANOSECONDS);
	}

	/** placeholder: the target already has convert(long, TimeUnit) (never copied) */
	public long convert(long sourceDuration, TimeUnit sourceUnit) {
		return 0L;
	}

}

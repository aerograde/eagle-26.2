package net.lax1dude.eaglercraft.v1_8.teavmcompat.jdk.jfr;

/**
 * jdk.jfr.FlightRecorder — inert stub; MC 26.2's JFR profiler guards on
 * isAvailable() before doing anything.
 */
public final class TFlightRecorder {

	private TFlightRecorder() {
	}

	public static boolean isAvailable() {
		return false;
	}

	public static boolean isInitialized() {
		return false;
	}

	public static TFlightRecorder getFlightRecorder() {
		throw new IllegalStateException("Flight Recorder is not available in the browser runtime");
	}

	public static void addListener(TFlightRecorderListener listener) {
	}

	public static boolean removeListener(TFlightRecorderListener listener) {
		return false;
	}

	public static void register(Class<? extends TEvent> eventClass) {
	}

	public static void unregister(Class<? extends TEvent> eventClass) {
	}

	public static void addPeriodicEvent(Class<? extends TEvent> eventClass, Runnable hook) {
	}

	public static boolean removePeriodicEvent(Runnable hook) {
		return false;
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.jdk.jfr;

/**
 * jdk.jfr.FlightRecorderListener — link-only interface. Needed as the
 * parameter type of FlightRecorder.addListener(); flight recording never
 * happens in the browser, so the callbacks are never fired. Default no-op
 * bodies mirror the real interface so any implementer links.
 */
public interface TFlightRecorderListener {

	default void recorderInitialized(TFlightRecorder recorder) {
	}

	default void recordingStateChanged(TRecording recording) {
	}
}

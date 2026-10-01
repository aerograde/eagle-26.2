package net.lax1dude.eaglercraft.v1_8.teavmcompat.jdk.jfr;

/**
 * jdk.jfr.EventType — link-only stub. Flight recording is unavailable in the
 * browser, so there are no registered event types; getEventType() returns null.
 */
public final class TEventType {

	private TEventType() {
	}

	public static TEventType getEventType(Class<? extends TEvent> eventClass) {
		return null;
	}

	public String getName() {
		return "";
	}

	public String getLabel() {
		return "";
	}

	public long getId() {
		return 0L;
	}

	public boolean isEnabled() {
		return false;
	}
}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.management;

/**
 * javax.management.Notification — value-object stub (see TMBeanServer).
 * All JDK constructor shapes are provided so log4j2's JMX plumbing links.
 */
public class TNotification {

	private final String type;
	private final Object source;
	private final long sequenceNumber;
	private final long timeStamp;
	private final String message;

	public TNotification(String type, Object source, long sequenceNumber) {
		this(type, source, sequenceNumber, 0L, "");
	}

	public TNotification(String type, Object source, long sequenceNumber, String message) {
		this(type, source, sequenceNumber, 0L, message);
	}

	public TNotification(String type, Object source, long sequenceNumber, long timeStamp) {
		this(type, source, sequenceNumber, timeStamp, "");
	}

	public TNotification(String type, Object source, long sequenceNumber, long timeStamp, String message) {
		this.type = type;
		this.source = source;
		this.sequenceNumber = sequenceNumber;
		this.timeStamp = timeStamp;
		this.message = message;
	}

	public String getType() {
		return type;
	}

	public Object getSource() {
		return source;
	}

	public long getSequenceNumber() {
		return sequenceNumber;
	}

	public long getTimeStamp() {
		return timeStamp;
	}

	public String getMessage() {
		return message;
	}

}

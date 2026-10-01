package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.management;

/**
 * javax.management.MBeanNotificationInfo — value-object stub (see
 * TMBeanServer).
 */
public class TMBeanNotificationInfo {

	private final String[] notifTypes;
	private final String name;
	private final String description;

	public TMBeanNotificationInfo(String[] notifTypes, String name, String description) {
		this.notifTypes = notifTypes;
		this.name = name;
		this.description = description;
	}

	public String[] getNotifTypes() {
		return notifTypes;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.management;

/**
 * javax.management.NotificationBroadcasterSupport — inert stub (see
 * TMBeanServer; nothing ever registers, so notifications go nowhere).
 */
public class TNotificationBroadcasterSupport {

	public TNotificationBroadcasterSupport() {
	}

	public TNotificationBroadcasterSupport(java.util.concurrent.Executor executor, TMBeanNotificationInfo... info) {
	}

	public TNotificationBroadcasterSupport(TMBeanNotificationInfo... info) {
	}

	public void sendNotification(TNotification notification) {
	}

	public TMBeanNotificationInfo[] getNotificationInfo() {
		return new TMBeanNotificationInfo[0];
	}

}

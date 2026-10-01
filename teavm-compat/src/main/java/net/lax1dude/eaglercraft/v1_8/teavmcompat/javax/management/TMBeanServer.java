package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.management;

/**
 * javax.management.MBeanServer — interface stub; getPlatformMBeanServer
 * throws, so no implementation ever exists in the browser runtime.
 */
public interface TMBeanServer {

	boolean isRegistered(TObjectName name);

	java.util.Set<TObjectName> queryNames(TObjectName name, TQueryExp query);

	TObjectInstance registerMBean(Object object, TObjectName name);

	void unregisterMBean(TObjectName name);

}

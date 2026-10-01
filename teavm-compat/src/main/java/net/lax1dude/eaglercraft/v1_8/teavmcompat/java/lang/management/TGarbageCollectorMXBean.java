package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.management;

/**
 * java.lang.management.GarbageCollectorMXBean — inert subset (see
 * TManagementFactory; the list of collectors is always empty).
 */
public interface TGarbageCollectorMXBean {

	String getName();

	long getCollectionCount();

	long getCollectionTime();

}

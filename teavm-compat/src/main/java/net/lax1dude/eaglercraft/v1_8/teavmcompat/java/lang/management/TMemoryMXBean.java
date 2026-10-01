package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.management;

/**
 * java.lang.management.MemoryMXBean — inert subset (see TManagementFactory).
 */
public interface TMemoryMXBean {

	TMemoryUsage getHeapMemoryUsage();

	TMemoryUsage getNonHeapMemoryUsage();

	void gc();

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.management;

/**
 * java.lang.management.ThreadMXBean — inert subset (see TManagementFactory).
 */
public interface TThreadMXBean {

	int getThreadCount();

	long[] getAllThreadIds();

	TThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers);

	TThreadInfo[] getThreadInfo(long[] ids, int maxDepth);

	boolean isSynchronizerUsageSupported();

}

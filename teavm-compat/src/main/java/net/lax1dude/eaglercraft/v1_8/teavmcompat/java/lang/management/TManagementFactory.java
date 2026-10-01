package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.management;

import java.util.Collections;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.management.TMBeanServer;

/**
 * java.lang.management.ManagementFactory — inert JMX facade. MC 26.2 reads
 * getMemoryMXBean/getRuntimeMXBean/getThreadMXBean for crash-report and F3
 * telemetry only.
 */
public final class TManagementFactory {

	private static final TRuntimeMXBean RUNTIME_BEAN = new InertRuntimeMXBean();
	private static final TMemoryMXBean MEMORY_BEAN = new InertMemoryMXBean();
	private static final TThreadMXBean THREAD_BEAN = new InertThreadMXBean();

	private TManagementFactory() {
	}

	public static TRuntimeMXBean getRuntimeMXBean() {
		return RUNTIME_BEAN;
	}

	public static TMemoryMXBean getMemoryMXBean() {
		return MEMORY_BEAN;
	}

	public static TThreadMXBean getThreadMXBean() {
		return THREAD_BEAN;
	}

	public static List<TGarbageCollectorMXBean> getGarbageCollectorMXBeans() {
		return Collections.emptyList();
	}

	public static TMBeanServer getPlatformMBeanServer() {
		throw new UnsupportedOperationException("no JMX in the browser runtime");
	}

	static class InertRuntimeMXBean implements TRuntimeMXBean {

		@Override
		public String getName() {
			return "0@teavm";
		}

		@Override
		public String getVmName() {
			return "TeaVM";
		}

		@Override
		public String getVmVendor() {
			return "TeaVM";
		}

		@Override
		public String getVmVersion() {
			return "0.13.0";
		}

		@Override
		public List<String> getInputArguments() {
			return Collections.emptyList();
		}

		@Override
		public long getStartTime() {
			return 0L;
		}

		@Override
		public long getUptime() {
			return 0L;
		}

	}

	static class InertMemoryMXBean implements TMemoryMXBean {

		@Override
		public TMemoryUsage getHeapMemoryUsage() {
			Runtime runtime = Runtime.getRuntime();
			long total = runtime.totalMemory();
			return new TMemoryUsage(0L, total - runtime.freeMemory(), total, total);
		}

		@Override
		public TMemoryUsage getNonHeapMemoryUsage() {
			return new TMemoryUsage(0L, 0L, 0L, 0L);
		}

		@Override
		public void gc() {
			Runtime.getRuntime().gc();
		}

	}

	static class InertThreadMXBean implements TThreadMXBean {

		@Override
		public int getThreadCount() {
			return 1;
		}

		@Override
		public long[] getAllThreadIds() {
			return new long[0];
		}

		@Override
		public TThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers) {
			return new TThreadInfo[0];
		}

		@Override
		public TThreadInfo[] getThreadInfo(long[] ids, int maxDepth) {
			return new TThreadInfo[0];
		}

		@Override
		public boolean isSynchronizerUsageSupported() {
			return false;
		}

	}

}

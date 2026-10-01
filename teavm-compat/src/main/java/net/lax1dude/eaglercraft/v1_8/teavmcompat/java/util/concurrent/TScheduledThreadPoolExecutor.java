package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

/**
 * java.util.concurrent.ScheduledThreadPoolExecutor — inline executor (see
 * TExecutors.InlineScheduledExecutorService for the semantics).
 */
public class TScheduledThreadPoolExecutor extends TExecutors.InlineScheduledExecutorService {

	public TScheduledThreadPoolExecutor(int corePoolSize) {
	}

	public TScheduledThreadPoolExecutor(int corePoolSize, TThreadFactory threadFactory) {
	}

	public void setRemoveOnCancelPolicy(boolean value) {
	}

	public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
	}

	public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
	}

	public void setKeepAliveTime(long time, java.util.concurrent.TimeUnit unit) {
	}

	public void allowCoreThreadTimeOut(boolean value) {
	}

	public int getCorePoolSize() {
		return 0;
	}

	public void setCorePoolSize(int corePoolSize) {
	}

	public void setMaximumPoolSize(int maximumPoolSize) {
	}

	public java.util.concurrent.BlockingQueue<Runnable> getQueue() {
		// always empty: inline execution leaves nothing queued
		return new TLinkedBlockingQueue<>();
	}

}

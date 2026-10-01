package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.ThreadPoolExecutor — absent from teavm-classlib 0.13.
 * The browser runtime is single-threaded, so this is a minimal facade: it
 * extends the module's AbstractExecutorService (which routes submit() through
 * execute()) and runs every command inline on the calling thread. Constructor
 * arguments (pool sizes, keep-alive, work queue, thread factory, rejection
 * handler) are accepted and recorded where cheap, but do not influence
 * behavior — nothing is ever queued or rejected.
 */
public class TThreadPoolExecutor extends TAbstractExecutorService {

	private volatile boolean shutdown;
	private int corePoolSize;
	private int maximumPoolSize;

	public TThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
			BlockingQueue<Runnable> workQueue) {
		this.corePoolSize = corePoolSize;
		this.maximumPoolSize = maximumPoolSize;
	}

	public TThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
			BlockingQueue<Runnable> workQueue, TThreadFactory threadFactory) {
		this.corePoolSize = corePoolSize;
		this.maximumPoolSize = maximumPoolSize;
	}

	public TThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
			BlockingQueue<Runnable> workQueue, TRejectedExecutionHandler handler) {
		this.corePoolSize = corePoolSize;
		this.maximumPoolSize = maximumPoolSize;
	}

	public TThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
			BlockingQueue<Runnable> workQueue, TThreadFactory threadFactory, TRejectedExecutionHandler handler) {
		this.corePoolSize = corePoolSize;
		this.maximumPoolSize = maximumPoolSize;
	}

	@Override
	public void execute(Runnable command) {
		if (command == null) {
			throw new NullPointerException();
		}
		// single-threaded runtime: run inline, immediately
		command.run();
	}

	@Override
	public void shutdown() {
		shutdown = true;
	}

	@Override
	public List<Runnable> shutdownNow() {
		shutdown = true;
		return Collections.emptyList();
	}

	@Override
	public boolean isShutdown() {
		return shutdown;
	}

	@Override
	public boolean isTerminated() {
		return shutdown;
	}

	public boolean isTerminating() {
		return false;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		return true;
	}

	public void purge() {
	}

	public void allowCoreThreadTimeOut(boolean value) {
	}

	public int getCorePoolSize() {
		return corePoolSize;
	}

	public void setCorePoolSize(int corePoolSize) {
		this.corePoolSize = corePoolSize;
	}

	public int getMaximumPoolSize() {
		return maximumPoolSize;
	}

	public void setMaximumPoolSize(int maximumPoolSize) {
		this.maximumPoolSize = maximumPoolSize;
	}

	public int getPoolSize() {
		return 0;
	}

	public int getActiveCount() {
		return 0;
	}

	public long getKeepAliveTime(TimeUnit unit) {
		return 0L;
	}

	public void setKeepAliveTime(long time, TimeUnit unit) {
	}

	public long getTaskCount() {
		return 0L;
	}

	public long getCompletedTaskCount() {
		return 0L;
	}

	public int getLargestPoolSize() {
		return 0;
	}
}

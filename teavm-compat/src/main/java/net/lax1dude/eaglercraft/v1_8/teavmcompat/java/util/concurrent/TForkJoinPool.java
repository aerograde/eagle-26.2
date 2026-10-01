package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.ForkJoinPool for the single-threaded browser runtime:
 * an inline executor. MC 26.2 (net.minecraft.util.Util.makeExecutor)
 * constructs it with a ForkJoinWorkerThreadFactory + UncaughtExceptionHandler,
 * both of which are accepted and ignored - no worker threads are ever spawned,
 * tasks run on the caller.
 */
public class TForkJoinPool extends TAbstractExecutorService {

	private static TForkJoinPool commonPool;

	private boolean shutdown;

	public TForkJoinPool() {
	}

	public TForkJoinPool(int parallelism) {
	}

	public TForkJoinPool(int parallelism, ForkJoinWorkerThreadFactory factory,
			Thread.UncaughtExceptionHandler handler, boolean asyncMode) {
	}

	public static TForkJoinPool commonPool() {
		if (commonPool == null) {
			commonPool = new TForkJoinPool();
		}
		return commonPool;
	}

	public static int getCommonPoolParallelism() {
		return 1;
	}

	@Override
	public void execute(Runnable command) {
		command.run();
	}

	public int getParallelism() {
		return 1;
	}

	public int getPoolSize() {
		return 0;
	}

	public int getActiveThreadCount() {
		return 0;
	}

	public int getRunningThreadCount() {
		return 0;
	}

	public int getQueuedSubmissionCount() {
		return 0;
	}

	public long getQueuedTaskCount() {
		return 0L;
	}

	public boolean isQuiescent() {
		return true;
	}

	public boolean awaitQuiescence(long timeout, TimeUnit unit) {
		return true;
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

	public static void managedBlock(ManagedBlocker blocker) throws InterruptedException {
		while (!blocker.isReleasable() && !blocker.block()) {
			// keep blocking inline until the blocker reports completion
		}
	}

	/** Mirrors java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory. */
	public interface ForkJoinWorkerThreadFactory {

		TForkJoinWorkerThread newThread(TForkJoinPool pool);

	}

	/** Mirrors java.util.concurrent.ForkJoinPool.ManagedBlocker. */
	public interface ManagedBlocker {

		boolean block() throws InterruptedException;

		boolean isReleasable();

	}

}

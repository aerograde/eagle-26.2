package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.Executors for the single-threaded browser runtime.
 * Every factory returns an executor that runs work inline on the calling
 * (green) thread; scheduled work runs immediately, once. This is the correct
 * degenerate behavior for a cooperatively-scheduled single-threaded target:
 * MC 26.2's background executors are only used for parallelism, never for
 * ordering guarantees that inline execution would violate.
 */
public final class TExecutors {

	private TExecutors() {
	}

	public static TExecutorService newFixedThreadPool(int nThreads) {
		return new InlineExecutorService();
	}

	public static TExecutorService newFixedThreadPool(int nThreads, TThreadFactory threadFactory) {
		return new InlineExecutorService();
	}

	public static TExecutorService newSingleThreadExecutor() {
		return new InlineExecutorService();
	}

	public static TExecutorService newSingleThreadExecutor(TThreadFactory threadFactory) {
		return new InlineExecutorService();
	}

	public static TExecutorService newCachedThreadPool() {
		return new InlineExecutorService();
	}

	public static TExecutorService newCachedThreadPool(TThreadFactory threadFactory) {
		return new InlineExecutorService();
	}

	public static TExecutorService newWorkStealingPool() {
		return new InlineExecutorService();
	}

	public static TExecutorService newWorkStealingPool(int parallelism) {
		return new InlineExecutorService();
	}

	public static TExecutorService newVirtualThreadPerTaskExecutor() {
		return new InlineExecutorService();
	}

	public static TScheduledExecutorService newSingleThreadScheduledExecutor() {
		return new InlineScheduledExecutorService();
	}

	public static TScheduledExecutorService newSingleThreadScheduledExecutor(TThreadFactory threadFactory) {
		return new InlineScheduledExecutorService();
	}

	public static TScheduledExecutorService newScheduledThreadPool(int corePoolSize) {
		return new InlineScheduledExecutorService();
	}

	public static TScheduledExecutorService newScheduledThreadPool(int corePoolSize, TThreadFactory threadFactory) {
		return new InlineScheduledExecutorService();
	}

	public static TExecutorService unconfigurableExecutorService(TExecutorService executor) {
		return executor;
	}

	public static TScheduledExecutorService unconfigurableScheduledExecutorService(
			TScheduledExecutorService executor) {
		return executor;
	}

	public static TThreadFactory defaultThreadFactory() {
		return new DefaultThreadFactory();
	}

	public static <T> Callable<T> callable(Runnable task, T result) {
		return new TFutureTask.RunnableAdapter<>(task, result);
	}

	public static Callable<Object> callable(Runnable task) {
		return new TFutureTask.RunnableAdapter<>(task, null);
	}

	static class DefaultThreadFactory implements TThreadFactory {

		@Override
		public Thread newThread(Runnable r) {
			return new Thread(r);
		}

	}

	/** Runs everything inline; shutdown state is tracked but nothing pends. */
	static class InlineExecutorService extends TAbstractExecutorService {

		private boolean shutdown;

		@Override
		public void execute(Runnable command) {
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

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return true;
		}

	}

	/** Scheduled tasks run immediately, once; periodic tasks never repeat. */
	static class InlineScheduledExecutorService extends InlineExecutorService implements TScheduledExecutorService {

		@Override
		public TScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			InlineScheduledFuture<Void> future = new InlineScheduledFuture<>(callable(command, null));
			future.run();
			return future;
		}

		@Override
		public <V> TScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
			InlineScheduledFuture<V> future = new InlineScheduledFuture<>(callable);
			future.run();
			return future;
		}

		@Override
		public TScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
				TimeUnit unit) {
			return schedule(command, initialDelay, unit);
		}

		@Override
		public TScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
				TimeUnit unit) {
			return schedule(command, initialDelay, unit);
		}

	}

	@SuppressWarnings("unchecked")
	static class InlineScheduledFuture<V> extends TFutureTask<V> implements TScheduledFuture<V> {

		InlineScheduledFuture(Callable<?> callable) {
			super((Callable<V>) callable);
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return 0L;
		}

		@Override
		public int compareTo(TDelayed o) {
			return 0;
		}

	}

}

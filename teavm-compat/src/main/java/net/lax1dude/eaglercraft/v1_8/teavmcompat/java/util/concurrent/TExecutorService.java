package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.ExecutorService — absent from teavm-classlib 0.13.
 * Implementations in this module execute submitted work inline (the browser
 * runtime is single-threaded).
 */
public interface TExecutorService extends Executor, AutoCloseable {

	void shutdown();

	List<Runnable> shutdownNow();

	boolean isShutdown();

	boolean isTerminated();

	boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

	<T> TFuture<T> submit(Callable<T> task);

	<T> TFuture<T> submit(Runnable task, T result);

	TFuture<?> submit(Runnable task);

	<T> List<TFuture<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException;

	<T> List<TFuture<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
			throws InterruptedException;

	<T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException;

	<T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TTimeoutException;

	@Override
	default void close() {
		shutdown();
	}

}

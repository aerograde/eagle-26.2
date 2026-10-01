package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.AbstractExecutorService — mirrors the JDK contract
 * (submit goes through newTaskFor + execute) so subclasses in libraries
 * (guava's AbstractListeningExecutorService) keep working. All concrete
 * executors in this module execute inline, so tasks are complete when
 * submit() returns.
 */
public abstract class TAbstractExecutorService implements TExecutorService {

	protected <T> TRunnableFuture<T> newTaskFor(Runnable runnable, T value) {
		return new TFutureTask<>(runnable, value);
	}

	protected <T> TRunnableFuture<T> newTaskFor(Callable<T> callable) {
		return new TFutureTask<>(callable);
	}

	@Override
	public TFuture<?> submit(Runnable task) {
		TRunnableFuture<Void> future = newTaskFor(task, null);
		execute(future);
		return future;
	}

	@Override
	public <T> TFuture<T> submit(Runnable task, T result) {
		TRunnableFuture<T> future = newTaskFor(task, result);
		execute(future);
		return future;
	}

	@Override
	public <T> TFuture<T> submit(Callable<T> task) {
		TRunnableFuture<T> future = newTaskFor(task);
		execute(future);
		return future;
	}

	@Override
	public <T> List<TFuture<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
		List<TFuture<T>> futures = new ArrayList<>(tasks.size());
		for (Callable<T> task : tasks) {
			TRunnableFuture<T> future = newTaskFor(task);
			execute(future);
			futures.add(future);
		}
		return futures;
	}

	@Override
	public <T> List<TFuture<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
			throws InterruptedException {
		return invokeAll(tasks);
	}

	@Override
	public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
		Throwable lastFailure = null;
		for (Callable<T> task : tasks) {
			try {
				return task.call();
			} catch (Throwable t) {
				lastFailure = t;
			}
		}
		throw new ExecutionException(lastFailure);
	}

	@Override
	public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TTimeoutException {
		return invokeAny(tasks);
	}

}

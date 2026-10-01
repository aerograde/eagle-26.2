package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.FutureTask — synchronous implementation for the
 * single-threaded browser runtime: run() executes the task inline, get()
 * requires the task to have run already (there is no other thread that could
 * complete it later).
 */
public class TFutureTask<V> implements TRunnableFuture<V> {

	private Callable<V> callable;
	private V result;
	private Throwable exception;
	private boolean done;
	private boolean cancelled;

	public TFutureTask(Callable<V> callable) {
		if (callable == null) {
			throw new NullPointerException();
		}
		this.callable = callable;
	}

	public TFutureTask(Runnable runnable, V result) {
		if (runnable == null) {
			throw new NullPointerException();
		}
		this.callable = new RunnableAdapter<>(runnable, result);
	}

	@Override
	public void run() {
		if (done || cancelled) {
			return;
		}
		try {
			set(callable.call());
		} catch (Throwable t) {
			setException(t);
		}
	}

	protected void set(V v) {
		result = v;
		done = true;
		done();
	}

	protected void setException(Throwable t) {
		exception = t;
		done = true;
		done();
	}

	/** Completion hook, mirrors the JDK API. */
	protected void done() {
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		if (done) {
			return false;
		}
		cancelled = true;
		done = true;
		done();
		return true;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public boolean isDone() {
		return done;
	}

	@Override
	public V get() throws InterruptedException, ExecutionException {
		if (cancelled) {
			throw new CancellationException();
		}
		if (!done) {
			// no other thread exists that could complete this task later
			throw new IllegalStateException("FutureTask.get() before run() would deadlock in the browser runtime");
		}
		if (exception != null) {
			throw new ExecutionException(exception);
		}
		return result;
	}

	@Override
	public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TTimeoutException {
		if (!done) {
			throw new TTimeoutException();
		}
		return get();
	}

	static class RunnableAdapter<V> implements Callable<V> {

		private final Runnable runnable;
		private final V result;

		RunnableAdapter(Runnable runnable, V result) {
			this.runnable = runnable;
			this.result = result;
		}

		@Override
		public V call() {
			runnable.run();
			return result;
		}

	}

}

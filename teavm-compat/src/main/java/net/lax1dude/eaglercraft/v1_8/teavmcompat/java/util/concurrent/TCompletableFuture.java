package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * java.util.concurrent.CompletableFuture — synchronous promise for the
 * single-threaded browser runtime. Dependent stages fire immediately when the
 * upstream completes (or immediately on attach if it already has); *Async
 * variants without an executor run inline, with an executor they go through
 * executor.execute() (which is also inline for this module's executors, but
 * respects game-provided executors such as MC's TracingExecutor wrappers).
 *
 * <p>get()/join() on an incomplete future throws IllegalStateException rather
 * than deadlocking — with inline executors every async chain is complete by
 * the time it is observed, so hitting that path indicates a real porting bug
 * we want loudly reported.</p>
 */
public class TCompletableFuture<T> implements TFuture<T>, TCompletionStage<T> {

	private static final ArrayDeque<Runnable> completionQueue = new ArrayDeque<>();
	private static boolean drainingCompletions;

	private boolean done;
	private boolean cancelled;
	private T result;
	private Throwable exception;
	private List<Runnable> dependents;

	public TCompletableFuture() {
	}

	// ------------------------------------------------------------------ static factories

	public static <U> TCompletableFuture<U> completedFuture(U value) {
		TCompletableFuture<U> future = new TCompletableFuture<>();
		future.complete(value);
		return future;
	}

	public static <U> TCompletableFuture<U> failedFuture(Throwable ex) {
		TCompletableFuture<U> future = new TCompletableFuture<>();
		future.completeExceptionally(ex);
		return future;
	}

	public static TCompletableFuture<Void> runAsync(Runnable runnable) {
		TCompletableFuture<Void> future = new TCompletableFuture<>();
		try {
			runnable.run();
			future.complete(null);
		} catch (Throwable t) {
			future.completeExceptionally(t);
		}
		return future;
	}

	public static TCompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
		TCompletableFuture<Void> future = new TCompletableFuture<>();
		executor.execute(() -> {
			try {
				runnable.run();
				future.complete(null);
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		return future;
	}

	public static <U> TCompletableFuture<U> supplyAsync(Supplier<U> supplier) {
		TCompletableFuture<U> future = new TCompletableFuture<>();
		try {
			future.complete(supplier.get());
		} catch (Throwable t) {
			future.completeExceptionally(t);
		}
		return future;
	}

	public static <U> TCompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor) {
		TCompletableFuture<U> future = new TCompletableFuture<>();
		executor.execute(() -> {
			try {
				future.complete(supplier.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		return future;
	}

	public static TCompletableFuture<Void> allOf(TCompletableFuture<?>... futures) {
		TCompletableFuture<Void> all = new TCompletableFuture<>();
		int count = futures.length;
		if (count == 0) {
			all.complete(null);
			return all;
		}
		int[] remaining = { count };
		for (TCompletableFuture<?> future : futures) {
			future.whenDone(() -> {
				if (--remaining[0] == 0) {
					Throwable failure = null;
					for (TCompletableFuture<?> f : futures) {
						if (f.exception != null) {
							failure = f.exception;
							break;
						}
					}
					if (failure != null) {
						all.completeExceptionally(failure);
					} else {
						all.complete(null);
					}
				}
			});
		}
		return all;
	}

	public static TCompletableFuture<Object> anyOf(TCompletableFuture<?>... futures) {
		TCompletableFuture<Object> any = new TCompletableFuture<>();
		for (TCompletableFuture<?> future : futures) {
			future.whenDone(() -> {
				if (!any.done) {
					if (future.exception != null) {
						any.completeExceptionally(future.exception);
					} else {
						any.complete(future.result);
					}
				}
			});
		}
		return any;
	}

	// ------------------------------------------------------------------ completion

	public boolean complete(T value) {
		if (done) {
			return false;
		}
		result = value;
		done = true;
		fire();
		return true;
	}

	public boolean completeExceptionally(Throwable ex) {
		if (done) {
			return false;
		}
		exception = ex;
		done = true;
		fire();
		return true;
	}

	public TCompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
		try {
			complete(supplier.get());
		} catch (Throwable t) {
			completeExceptionally(t);
		}
		return this;
	}

	public TCompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
		executor.execute(() -> {
			try {
				complete(supplier.get());
			} catch (Throwable t) {
				completeExceptionally(t);
			}
		});
		return this;
	}

	public void obtrudeValue(T value) {
		result = value;
		exception = null;
		boolean wasDone = done;
		done = true;
		if (!wasDone) {
			fire();
		}
	}

	public void obtrudeException(Throwable ex) {
		exception = ex;
		result = null;
		boolean wasDone = done;
		done = true;
		if (!wasDone) {
			fire();
		}
	}

	/** Timeouts are meaningless with inline completion; keep the chain intact. */
	public TCompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
		return this;
	}

	public TCompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
		return this;
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		if (done) {
			return false;
		}
		cancelled = true;
		return completeExceptionally(new CancellationException());
	}

	// ------------------------------------------------------------------ observation

	@Override
	public boolean isDone() {
		return done;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	public boolean isCompletedExceptionally() {
		return done && exception != null;
	}

	public int getNumberOfDependents() {
		return dependents == null ? 0 : dependents.size();
	}

	@Override
	public T get() throws InterruptedException, ExecutionException {
		checkDone();
		if (exception != null) {
			if (exception instanceof CancellationException) {
				throw (CancellationException) exception;
			}
			throw new ExecutionException(exception);
		}
		return result;
	}

	@Override
	public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TTimeoutException {
		if (!done) {
			throw new TTimeoutException();
		}
		return get();
	}

	public T join() {
		checkDone();
		if (exception != null) {
			if (exception instanceof CancellationException) {
				throw (CancellationException) exception;
			}
			if (exception instanceof TCompletionException) {
				throw (TCompletionException) exception;
			}
			throw new TCompletionException(exception);
		}
		return result;
	}

	public T getNow(T valueIfAbsent) {
		if (!done) {
			return valueIfAbsent;
		}
		return join();
	}

	public T resultNow() {
		if (!done || exception != null) {
			throw new IllegalStateException("Task did not complete with a result");
		}
		return result;
	}

	public Throwable exceptionNow() {
		if (!done || exception == null) {
			throw new IllegalStateException("Task did not complete with an exception");
		}
		return exception;
	}

	private void checkDone() {
		if (!done) {
			// no other thread exists that could complete this future later
			throw new IllegalStateException(
					"CompletableFuture observed before completion; this would deadlock in the browser runtime");
		}
	}

	// ------------------------------------------------------------------ dependents

	private void whenDone(Runnable action) {
		if (done) {
			enqueueCompletion(action);
		} else {
			if (dependents == null) {
				dependents = new ArrayList<>();
			}
			dependents.add(action);
		}
	}

	private void fire() {
		if (dependents != null) {
			List<Runnable> actions = dependents;
			dependents = null;
			for (Runnable action : actions) {
				enqueueCompletion(action);
			}
		}
	}

	/**
	 * TeaVM executes future dependents on the current JavaScript stack. Long chains
	 * produced by chunk generation used to recurse until the worker hit the browser's
	 * call-stack limit. Drain completions iteratively while retaining the observable
	 * synchronous contract: the outermost complete/attach call still runs every
	 * dependent before returning.
	 */
	private static void enqueueCompletion(Runnable action) {
		completionQueue.addLast(action);
		if (drainingCompletions) {
			return;
		}

		drainingCompletions = true;
		try {
			while (!completionQueue.isEmpty()) {
				completionQueue.removeFirst().run();
			}
		} catch (Throwable t) {
			completionQueue.clear();
			throw t;
		} finally {
			drainingCompletions = false;
		}
	}

	private static void dispatch(Executor executor, Runnable action) {
		if (executor == null) {
			action.run();
		} else {
			executor.execute(action);
		}
	}

	// ------------------------------------------------------------------ stages

	private <U> TCompletableFuture<U> attach(Executor executor, BiConsumer<TCompletableFuture<U>, TCompletableFuture<T>> relay) {
		TCompletableFuture<U> downstream = new TCompletableFuture<>();
		whenDone(() -> dispatch(executor, () -> relay.accept(downstream, this)));
		return downstream;
	}

	@Override
	public <U> TCompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
		return thenApplyAsync(fn, null);
	}

	@Override
	public <U> TCompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
		return thenApplyAsync(fn, null);
	}

	@Override
	public <U> TCompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
		return attach(executor, (downstream, upstream) -> {
			if (upstream.exception != null) {
				downstream.completeExceptionally(upstream.exception);
				return;
			}
			try {
				downstream.complete(fn.apply(upstream.result));
			} catch (Throwable t) {
				downstream.completeExceptionally(t);
			}
		});
	}

	@Override
	public TCompletableFuture<Void> thenAccept(Consumer<? super T> action) {
		return thenAcceptAsync(action, null);
	}

	@Override
	public TCompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
		return thenAcceptAsync(action, null);
	}

	@Override
	public TCompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
		return attach(executor, (downstream, upstream) -> {
			if (upstream.exception != null) {
				downstream.completeExceptionally(upstream.exception);
				return;
			}
			try {
				action.accept(upstream.result);
				downstream.complete(null);
			} catch (Throwable t) {
				downstream.completeExceptionally(t);
			}
		});
	}

	@Override
	public TCompletableFuture<Void> thenRun(Runnable action) {
		return thenRunAsync(action, null);
	}

	@Override
	public TCompletableFuture<Void> thenRunAsync(Runnable action) {
		return thenRunAsync(action, null);
	}

	@Override
	public TCompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
		return attach(executor, (downstream, upstream) -> {
			if (upstream.exception != null) {
				downstream.completeExceptionally(upstream.exception);
				return;
			}
			try {
				action.run();
				downstream.complete(null);
			} catch (Throwable t) {
				downstream.completeExceptionally(t);
			}
		});
	}

	@Override
	public <U, V> TCompletableFuture<V> thenCombine(TCompletionStage<? extends U> other,
			BiFunction<? super T, ? super U, ? extends V> fn) {
		return thenCombineAsync(other, fn, null);
	}

	@Override
	public <U, V> TCompletableFuture<V> thenCombineAsync(TCompletionStage<? extends U> other,
			BiFunction<? super T, ? super U, ? extends V> fn) {
		return thenCombineAsync(other, fn, null);
	}

	@Override
	public <U, V> TCompletableFuture<V> thenCombineAsync(TCompletionStage<? extends U> other,
			BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
		TCompletableFuture<V> downstream = new TCompletableFuture<>();
		TCompletableFuture<? extends U> otherFuture = other.toCompletableFuture();
		Runnable tryFire = () -> {
			if (!done || !otherFuture.isDone() || downstream.done) {
				return;
			}
			dispatch(executor, () -> {
				if (exception != null) {
					downstream.completeExceptionally(exception);
					return;
				}
				if (otherFuture.exception != null) {
					downstream.completeExceptionally(otherFuture.exception);
					return;
				}
				try {
					downstream.complete(fn.apply(result, otherFuture.result));
				} catch (Throwable t) {
					downstream.completeExceptionally(t);
				}
			});
		};
		whenDone(tryFire);
		otherFuture.whenDone(tryFire);
		return downstream;
	}

	@Override
	public <U> TCompletableFuture<U> thenCompose(Function<? super T, ? extends TCompletionStage<U>> fn) {
		return thenComposeAsync(fn, null);
	}

	@Override
	public <U> TCompletableFuture<U> thenComposeAsync(Function<? super T, ? extends TCompletionStage<U>> fn) {
		return thenComposeAsync(fn, null);
	}

	@Override
	public <U> TCompletableFuture<U> thenComposeAsync(Function<? super T, ? extends TCompletionStage<U>> fn,
			Executor executor) {
		return attach(executor, (downstream, upstream) -> {
			if (upstream.exception != null) {
				downstream.completeExceptionally(upstream.exception);
				return;
			}
			try {
				TCompletableFuture<U> inner = fn.apply(upstream.result).toCompletableFuture();
				inner.whenDone(() -> {
					if (inner.exception != null) {
						downstream.completeExceptionally(inner.exception);
					} else {
						downstream.complete(inner.result);
					}
				});
			} catch (Throwable t) {
				downstream.completeExceptionally(t);
			}
		});
	}

	@Override
	public <U> TCompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
		return handleAsync(fn, null);
	}

	@Override
	public <U> TCompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
		return handleAsync(fn, null);
	}

	@Override
	public <U> TCompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn,
			Executor executor) {
		return attach(executor, (downstream, upstream) -> {
			try {
				downstream.complete(fn.apply(upstream.result, upstream.exception));
			} catch (Throwable t) {
				downstream.completeExceptionally(t);
			}
		});
	}

	@Override
	public TCompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
		return whenCompleteAsync(action, null);
	}

	@Override
	public TCompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
		return whenCompleteAsync(action, null);
	}

	@Override
	public TCompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action,
			Executor executor) {
		return attach(executor, (downstream, upstream) -> {
			Throwable actionFailure = null;
			try {
				action.accept(upstream.result, upstream.exception);
			} catch (Throwable t) {
				actionFailure = t;
			}
			if (upstream.exception != null) {
				downstream.completeExceptionally(upstream.exception);
			} else if (actionFailure != null) {
				downstream.completeExceptionally(actionFailure);
			} else {
				downstream.complete(upstream.result);
			}
		});
	}

	@Override
	public TCompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
		return attach(null, (downstream, upstream) -> {
			if (upstream.exception == null) {
				downstream.complete(upstream.result);
				return;
			}
			try {
				downstream.complete(fn.apply(upstream.exception));
			} catch (Throwable t) {
				downstream.completeExceptionally(t);
			}
		});
	}

	// ------------------------------------------------------------------ either

	public <U> TCompletableFuture<U> applyToEither(TCompletionStage<? extends T> other, Function<? super T, U> fn) {
		return applyToEitherAsync(other, fn, null);
	}

	public <U> TCompletableFuture<U> applyToEitherAsync(TCompletionStage<? extends T> other, Function<? super T, U> fn) {
		return applyToEitherAsync(other, fn, null);
	}

	public <U> TCompletableFuture<U> applyToEitherAsync(TCompletionStage<? extends T> other, Function<? super T, U> fn,
			Executor executor) {
		TCompletableFuture<U> downstream = new TCompletableFuture<>();
		TCompletableFuture<? extends T> otherFuture = other.toCompletableFuture();
		// Either-semantics: the FIRST of the two sources to complete (normally or
		// exceptionally) determines the result; the second firing is a no-op.
		Runnable tryFire = () -> {
			if (downstream.done) {
				return;
			}
			final Throwable ex;
			final T value;
			if (done) {
				ex = exception;
				value = result;
			} else if (otherFuture.isDone()) {
				ex = otherFuture.exception;
				value = otherFuture.result;
			} else {
				return;
			}
			dispatch(executor, () -> {
				if (downstream.done) {
					return;
				}
				if (ex != null) {
					downstream.completeExceptionally(ex);
					return;
				}
				try {
					downstream.complete(fn.apply(value));
				} catch (Throwable t) {
					downstream.completeExceptionally(t);
				}
			});
		};
		whenDone(tryFire);
		otherFuture.whenDone(tryFire);
		return downstream;
	}

	// ------------------------------------------------------------------ exceptionally (async, JDK 12)

	public TCompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn) {
		return exceptionallyAsync(fn, null);
	}

	public TCompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor) {
		return attach(executor, (downstream, upstream) -> {
			if (upstream.exception == null) {
				downstream.complete(upstream.result);
				return;
			}
			try {
				downstream.complete(fn.apply(upstream.exception));
			} catch (Throwable t) {
				downstream.completeExceptionally(t);
			}
		});
	}

	public TCompletableFuture<T> exceptionallyCompose(Function<Throwable, ? extends TCompletionStage<T>> fn) {
		return exceptionallyComposeAsync(fn, null);
	}

	public TCompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends TCompletionStage<T>> fn) {
		return exceptionallyComposeAsync(fn, null);
	}

	public TCompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends TCompletionStage<T>> fn,
			Executor executor) {
		return attach(executor, (downstream, upstream) -> {
			if (upstream.exception == null) {
				downstream.complete(upstream.result);
				return;
			}
			try {
				TCompletableFuture<T> inner = fn.apply(upstream.exception).toCompletableFuture();
				inner.whenDone(() -> {
					if (inner.exception != null) {
						downstream.completeExceptionally(inner.exception);
					} else {
						downstream.complete(inner.result);
					}
				});
			} catch (Throwable t) {
				downstream.completeExceptionally(t);
			}
		});
	}

	@Override
	public TCompletableFuture<T> toCompletableFuture() {
		return this;
	}

	public TCompletableFuture<T> copy() {
		return thenApply(v -> v);
	}

	public <U> TCompletableFuture<U> newIncompleteFuture() {
		return new TCompletableFuture<>();
	}

}

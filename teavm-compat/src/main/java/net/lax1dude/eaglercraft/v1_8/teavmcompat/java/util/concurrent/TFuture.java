package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.Future — absent from teavm-classlib 0.13. Also unblocks
 * method resolution on subinterfaces such as guava's ListenableFuture.
 */
public interface TFuture<V> {

	boolean cancel(boolean mayInterruptIfRunning);

	boolean isCancelled();

	boolean isDone();

	V get() throws InterruptedException, ExecutionException;

	V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TTimeoutException;

}

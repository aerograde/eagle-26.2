package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.ScheduledExecutorService — absent from teavm-classlib
 * 0.13. The inline implementation in TExecutors runs scheduled tasks
 * immediately, once (see there for the rationale).
 */
public interface TScheduledExecutorService extends TExecutorService {

	TScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

	<V> TScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

	TScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

	TScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);

}

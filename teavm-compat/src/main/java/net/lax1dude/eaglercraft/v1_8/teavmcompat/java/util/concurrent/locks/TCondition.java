package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.locks;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.locks.Condition for the single-threaded browser
 * runtime. Waiting on a condition with no other thread to signal it would
 * deadlock, so implementations treat await as an immediate (spurious) wakeup
 * and signal as a no-op.
 */
public interface TCondition {

	void await() throws InterruptedException;

	void awaitUninterruptibly();

	long awaitNanos(long nanosTimeout) throws InterruptedException;

	boolean await(long time, TimeUnit unit) throws InterruptedException;

	boolean awaitUntil(Date deadline) throws InterruptedException;

	void signal();

	void signalAll();

}

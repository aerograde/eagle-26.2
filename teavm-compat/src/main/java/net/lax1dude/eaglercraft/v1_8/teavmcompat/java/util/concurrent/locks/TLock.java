package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.locks.Lock for the single-threaded browser runtime.
 * TeaVM JS uses cooperative green threads, so mutual exclusion is inherent;
 * implementations are inert (lock always succeeds immediately).
 */
public interface TLock {

	void lock();

	void lockInterruptibly() throws InterruptedException;

	boolean tryLock();

	boolean tryLock(long time, TimeUnit unit) throws InterruptedException;

	void unlock();

	TCondition newCondition();

}

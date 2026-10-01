package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.locks;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.locks.ReentrantLock — inert single-threaded
 * implementation. tryLock() always succeeds and isHeldByCurrentThread() is
 * always true, which keeps guava's LocalCache / MapMakerInternalMap segments
 * (which extend ReentrantLock) working unmodified.
 */
public class TReentrantLock implements TLock {

	/** Inert condition shared by all lock instances. */
	static final TCondition INERT_CONDITION = new InertCondition();

	private int holdCount;

	public TReentrantLock() {
	}

	public TReentrantLock(boolean fair) {
	}

	@Override
	public void lock() {
		++holdCount;
	}

	@Override
	public void lockInterruptibly() throws InterruptedException {
		++holdCount;
	}

	@Override
	public boolean tryLock() {
		++holdCount;
		return true;
	}

	@Override
	public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
		++holdCount;
		return true;
	}

	@Override
	public void unlock() {
		if (holdCount > 0) {
			--holdCount;
		}
	}

	@Override
	public TCondition newCondition() {
		return INERT_CONDITION;
	}

	public int getHoldCount() {
		return holdCount;
	}

	public boolean isHeldByCurrentThread() {
		return true;
	}

	public boolean isLocked() {
		return holdCount > 0;
	}

	public final boolean isFair() {
		return false;
	}

	public final boolean hasQueuedThreads() {
		return false;
	}

	public final int getQueueLength() {
		return 0;
	}

	static class InertCondition implements TCondition {

		@Override
		public void await() throws InterruptedException {
		}

		@Override
		public void awaitUninterruptibly() {
		}

		@Override
		public long awaitNanos(long nanosTimeout) throws InterruptedException {
			return nanosTimeout;
		}

		@Override
		public boolean await(long time, TimeUnit unit) throws InterruptedException {
			return true;
		}

		@Override
		public boolean awaitUntil(Date deadline) throws InterruptedException {
			return true;
		}

		@Override
		public void signal() {
		}

		@Override
		public void signalAll() {
		}

	}

}

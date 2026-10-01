package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.locks.ReentrantReadWriteLock — inert single-threaded
 * implementation. The nested ReadLock/WriteLock classes are mirrored exactly
 * because caller bytecode references their concrete types
 * (readLock()Ljava/util/concurrent/locks/ReentrantReadWriteLock$ReadLock;).
 */
public class TReentrantReadWriteLock implements TReadWriteLock {

	private final ReadLock readLock = new ReadLock();
	private final WriteLock writeLock = new WriteLock();

	public TReentrantReadWriteLock() {
	}

	public TReentrantReadWriteLock(boolean fair) {
	}

	@Override
	public ReadLock readLock() {
		return readLock;
	}

	@Override
	public WriteLock writeLock() {
		return writeLock;
	}

	public final boolean isFair() {
		return false;
	}

	public boolean isWriteLocked() {
		return false;
	}

	public boolean isWriteLockedByCurrentThread() {
		return true;
	}

	public int getReadLockCount() {
		return 0;
	}

	public int getReadHoldCount() {
		return 0;
	}

	public int getWriteHoldCount() {
		return 0;
	}

	/** Inert lock base for the nested views. */
	public static class ReadLock implements TLock {

		ReadLock() {
		}

		@Override
		public void lock() {
		}

		@Override
		public void lockInterruptibly() throws InterruptedException {
		}

		@Override
		public boolean tryLock() {
			return true;
		}

		@Override
		public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
			return true;
		}

		@Override
		public void unlock() {
		}

		@Override
		public TCondition newCondition() {
			return TReentrantLock.INERT_CONDITION;
		}

	}

	public static class WriteLock implements TLock {

		WriteLock() {
		}

		@Override
		public void lock() {
		}

		@Override
		public void lockInterruptibly() throws InterruptedException {
		}

		@Override
		public boolean tryLock() {
			return true;
		}

		@Override
		public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
			return true;
		}

		@Override
		public void unlock() {
		}

		@Override
		public TCondition newCondition() {
			return TReentrantLock.INERT_CONDITION;
		}

		public boolean isHeldByCurrentThread() {
			return true;
		}

		public int getHoldCount() {
			return 0;
		}

	}

}

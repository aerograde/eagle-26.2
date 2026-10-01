package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.locks.StampedLock — inert single-threaded
 * implementation. All acquisitions succeed with a non-zero stamp and
 * optimistic reads always validate.
 */
public class TStampedLock {

	private static final long STAMP = 1L;

	private final ReadLockView readLockView = new ReadLockView();
	private final WriteLockView writeLockView = new WriteLockView();

	public long writeLock() {
		return STAMP;
	}

	public long tryWriteLock() {
		return STAMP;
	}

	public long tryWriteLock(long time, TimeUnit unit) throws InterruptedException {
		return STAMP;
	}

	public long writeLockInterruptibly() throws InterruptedException {
		return STAMP;
	}

	public long readLock() {
		return STAMP;
	}

	public long tryReadLock() {
		return STAMP;
	}

	public long tryReadLock(long time, TimeUnit unit) throws InterruptedException {
		return STAMP;
	}

	public long readLockInterruptibly() throws InterruptedException {
		return STAMP;
	}

	public long tryOptimisticRead() {
		return STAMP;
	}

	public boolean validate(long stamp) {
		return true;
	}

	public void unlockWrite(long stamp) {
	}

	public void unlockRead(long stamp) {
	}

	public void unlock(long stamp) {
	}

	public long tryConvertToWriteLock(long stamp) {
		return STAMP;
	}

	public long tryConvertToReadLock(long stamp) {
		return STAMP;
	}

	public long tryConvertToOptimisticRead(long stamp) {
		return STAMP;
	}

	public boolean tryUnlockWrite() {
		return false;
	}

	public boolean tryUnlockRead() {
		return false;
	}

	public boolean isWriteLocked() {
		return false;
	}

	public boolean isReadLocked() {
		return false;
	}

	public TLock asReadLock() {
		return readLockView;
	}

	public TLock asWriteLock() {
		return writeLockView;
	}

	public TReadWriteLock asReadWriteLock() {
		return new TReadWriteLock() {
			@Override
			public TLock readLock() {
				return readLockView;
			}

			@Override
			public TLock writeLock() {
				return writeLockView;
			}
		};
	}

	class ReadLockView implements TLock {

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

	class WriteLockView implements TLock {

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

}

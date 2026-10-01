package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

/**
 * java.util.concurrent.Semaphore — absent from teavm-classlib 0.13.
 * Functional single-thread implementation backed by an int permit counter.
 * The browser runtime is cooperatively single-threaded, so acquire() can never
 * block on another thread; it decrements the counter and proceeds. Callers use
 * semaphores here for permit accounting, not for cross-thread blocking.
 */
public class TSemaphore {

	private int permits;

	public TSemaphore(int permits) {
		this.permits = permits;
	}

	public TSemaphore(int permits, boolean fair) {
		this.permits = permits;
	}

	public void acquire() throws InterruptedException {
		acquire(1);
	}

	public void acquire(int permitsToAcquire) throws InterruptedException {
		if (permitsToAcquire < 0) {
			throw new IllegalArgumentException();
		}
		// single-threaded: nothing else can release, so just take the permits
		permits -= permitsToAcquire;
	}

	public void acquireUninterruptibly() {
		acquireUninterruptibly(1);
	}

	public void acquireUninterruptibly(int permitsToAcquire) {
		if (permitsToAcquire < 0) {
			throw new IllegalArgumentException();
		}
		permits -= permitsToAcquire;
	}

	public boolean tryAcquire() {
		return tryAcquire(1);
	}

	public boolean tryAcquire(int permitsToAcquire) {
		if (permitsToAcquire < 0) {
			throw new IllegalArgumentException();
		}
		if (permits >= permitsToAcquire) {
			permits -= permitsToAcquire;
			return true;
		}
		return false;
	}

	public void release() {
		release(1);
	}

	public void release(int permitsToRelease) {
		if (permitsToRelease < 0) {
			throw new IllegalArgumentException();
		}
		permits += permitsToRelease;
	}

	public int availablePermits() {
		return permits;
	}

	public int drainPermits() {
		int drained = permits;
		if (drained > 0) {
			permits = 0;
		}
		return drained;
	}

	@Override
	public String toString() {
		return super.toString() + "[Permits = " + permits + "]";
	}
}

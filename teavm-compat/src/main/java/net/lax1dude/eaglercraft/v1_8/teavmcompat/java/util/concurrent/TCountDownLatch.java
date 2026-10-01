package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.CountDownLatch for the single-threaded browser runtime.
 * Counting works normally; awaiting a non-zero latch would deadlock (no other
 * thread can count it down) and throws instead.
 */
public class TCountDownLatch {

	private long count;

	public TCountDownLatch(int count) {
		if (count < 0) {
			throw new IllegalArgumentException("count < 0");
		}
		this.count = count;
	}

	public void await() throws InterruptedException {
		if (count > 0) {
			throw new IllegalStateException("CountDownLatch.await() with count > 0 would deadlock in the browser runtime");
		}
	}

	public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
		return count == 0;
	}

	public void countDown() {
		if (count > 0) {
			--count;
		}
	}

	public long getCount() {
		return count;
	}

	@Override
	public String toString() {
		return super.toString() + "[Count = " + count + "]";
	}

}

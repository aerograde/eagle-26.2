package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.atomic;

/**
 * java.util.concurrent.atomic.AtomicLongArray — plain long[] wrapper;
 * atomicity is inherent in the single-threaded browser runtime.
 */
public class TAtomicLongArray {

	private final long[] array;

	public TAtomicLongArray(int length) {
		if (length < 0) throw new NegativeArraySizeException(Integer.toString(length));
		this.array = new long[length];
	}

	public TAtomicLongArray(long[] source) {
		this.array = source.clone();
	}

	public final int length() {
		return array.length;
	}

	public final long get(int i) {
		checkIndex(i);
		return array[i];
	}

	public final void set(int i, long newValue) {
		checkIndex(i);
		array[i] = newValue;
	}

	public final void lazySet(int i, long newValue) {
		checkIndex(i);
		array[i] = newValue;
	}

	public final long getAndSet(int i, long newValue) {
		checkIndex(i);
		long old = array[i];
		array[i] = newValue;
		return old;
	}

	public final boolean compareAndSet(int i, long expect, long update) {
		checkIndex(i);
		if (array[i] == expect) {
			array[i] = update;
			return true;
		}
		return false;
	}

	public final boolean weakCompareAndSet(int i, long expect, long update) {
		checkIndex(i);
		return compareAndSet(i, expect, update);
	}

	public final long getAndIncrement(int i) {
		checkIndex(i);
		return array[i]++;
	}

	public final long getAndDecrement(int i) {
		checkIndex(i);
		return array[i]--;
	}

	public final long getAndAdd(int i, long delta) {
		checkIndex(i);
		long old = array[i];
		array[i] += delta;
		return old;
	}

	public final long incrementAndGet(int i) {
		checkIndex(i);
		return ++array[i];
	}

	public final long decrementAndGet(int i) {
		checkIndex(i);
		return --array[i];
	}

	public final long addAndGet(int i, long delta) {
		checkIndex(i);
		array[i] += delta;
		return array[i];
	}

	private void checkIndex(int i) {
		if (i < 0 || i >= array.length) throw new ArrayIndexOutOfBoundsException(i);
	}

	@Override
	public String toString() {
		return java.util.Arrays.toString(array);
	}

}

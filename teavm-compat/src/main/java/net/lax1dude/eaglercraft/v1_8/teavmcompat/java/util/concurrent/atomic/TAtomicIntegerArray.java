package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.atomic;

/**
 * java.util.concurrent.atomic.AtomicIntegerArray — plain int[] wrapper;
 * atomicity is inherent in the single-threaded browser runtime.
 */
public class TAtomicIntegerArray {

	private final int[] array;

	public TAtomicIntegerArray(int length) {
		if (length < 0) throw new NegativeArraySizeException(Integer.toString(length));
		this.array = new int[length];
	}

	public TAtomicIntegerArray(int[] source) {
		this.array = source.clone();
	}

	public final int length() {
		return array.length;
	}

	public final int get(int i) {
		checkIndex(i);
		return array[i];
	}

	public final void set(int i, int newValue) {
		checkIndex(i);
		array[i] = newValue;
	}

	public final void lazySet(int i, int newValue) {
		checkIndex(i);
		array[i] = newValue;
	}

	public final int getAndSet(int i, int newValue) {
		checkIndex(i);
		int old = array[i];
		array[i] = newValue;
		return old;
	}

	public final boolean compareAndSet(int i, int expect, int update) {
		checkIndex(i);
		if (array[i] == expect) {
			array[i] = update;
			return true;
		}
		return false;
	}

	public final boolean weakCompareAndSet(int i, int expect, int update) {
		checkIndex(i);
		return compareAndSet(i, expect, update);
	}

	public final int getAndIncrement(int i) {
		checkIndex(i);
		return array[i]++;
	}

	public final int getAndDecrement(int i) {
		checkIndex(i);
		return array[i]--;
	}

	public final int getAndAdd(int i, int delta) {
		checkIndex(i);
		int old = array[i];
		array[i] += delta;
		return old;
	}

	public final int incrementAndGet(int i) {
		checkIndex(i);
		return ++array[i];
	}

	public final int decrementAndGet(int i) {
		checkIndex(i);
		return --array[i];
	}

	public final int addAndGet(int i, int delta) {
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

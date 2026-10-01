package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.atomic;

/**
 * java.util.concurrent.atomic.LongAdder — just a long; the striped-cell design
 * is pointless in the single-threaded browser runtime.
 */
public class TLongAdder extends Number {

	private long value;

	public TLongAdder() {
	}

	public void add(long x) {
		value += x;
	}

	public void increment() {
		++value;
	}

	public void decrement() {
		--value;
	}

	public long sum() {
		return value;
	}

	public void reset() {
		value = 0L;
	}

	public long sumThenReset() {
		long v = value;
		value = 0L;
		return v;
	}

	@Override
	public long longValue() {
		return value;
	}

	@Override
	public int intValue() {
		return (int) value;
	}

	@Override
	public float floatValue() {
		return (float) value;
	}

	@Override
	public double doubleValue() {
		return (double) value;
	}

	@Override
	public String toString() {
		return Long.toString(value);
	}

}

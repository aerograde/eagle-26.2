package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.management;

/**
 * java.lang.management.MemoryUsage — value object for TMemoryMXBean.
 */
public class TMemoryUsage {

	private final long init;
	private final long used;
	private final long committed;
	private final long max;

	public TMemoryUsage(long init, long used, long committed, long max) {
		this.init = init;
		this.used = used;
		this.committed = committed;
		this.max = max;
	}

	public long getInit() {
		return init;
	}

	public long getUsed() {
		return used;
	}

	public long getCommitted() {
		return committed;
	}

	public long getMax() {
		return max;
	}

	@Override
	public String toString() {
		return "init = " + init + " used = " + used + " committed = " + committed + " max = " + max;
	}

}

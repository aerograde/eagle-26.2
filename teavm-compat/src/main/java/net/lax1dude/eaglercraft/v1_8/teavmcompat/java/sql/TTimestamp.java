package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.sql;

/**
 * java.sql.Timestamp — thin wrapper over java.util.Date (see java.sql.TDate).
 */
public class TTimestamp extends java.util.Date {

	private int nanos;

	public TTimestamp(long time) {
		super(0L);
		assignTime(time);
	}

	@Override
	public void setTime(long time) {
		assignTime(time);
	}

	private void assignTime(long time) {
		super.setTime((time / 1000L) * 1000L);
		nanos = (int) ((time % 1000L) * 1000000L);
		if (nanos < 0) {
			nanos += 1000000000;
			super.setTime(super.getTime() - 1000L);
		}
	}

	public int getNanos() {
		return nanos;
	}

	public void setNanos(int n) {
		if (n < 0 || n > 999999999) {
			throw new IllegalArgumentException("nanos out of range: " + n);
		}
		nanos = n;
	}

	@Override
	public long getTime() {
		return super.getTime() + (nanos / 1000000L);
	}

}

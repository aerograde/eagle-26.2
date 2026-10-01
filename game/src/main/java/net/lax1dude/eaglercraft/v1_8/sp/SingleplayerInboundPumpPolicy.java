package net.lax1dude.eaglercraft.v1_8.sp;

/**
 * Bounded scheduling policy for the browser singleplayer server-to-client
 * packet queue. Packet order is owned by the caller; this class only selects
 * one of the existing per-frame time budgets.
 */
final class SingleplayerInboundPumpPolicy {

	static final double NORMAL_BUDGET_MILLIS = 6.0;
	static final double BACKLOG_BUDGET_MILLIS = 9.0;
	static final double DEEP_BACKLOG_BUDGET_MILLIS = 12.0;
	static final int BACKLOG_QUEUE_DEPTH = 256;
	static final int DEEP_BACKLOG_QUEUE_DEPTH = 2048;
	static final long STALE_QUEUE_NANOS = 250_000_000L;
	static final long DEEP_STALE_QUEUE_NANOS = 1_000_000_000L;

	private SingleplayerInboundPumpPolicy() {
	}

	static double budgetMillis(final int queueDepth, final long oldestFrameNanos) {
		if(queueDepth >= DEEP_BACKLOG_QUEUE_DEPTH || oldestFrameNanos >= DEEP_STALE_QUEUE_NANOS) {
			return DEEP_BACKLOG_BUDGET_MILLIS;
		}
		if(queueDepth >= BACKLOG_QUEUE_DEPTH || oldestFrameNanos >= STALE_QUEUE_NANOS) {
			return BACKLOG_BUDGET_MILLIS;
		}
		return NORMAL_BUDGET_MILLIS;
	}

	/**
	 * Arrival timestamps aligned with the caller's FIFO frame queue. The age used by
	 * {@link #budgetMillis} is the age of the packet currently at the queue head, not the age of
	 * a continuous nonempty episode; after the old head is delivered, newly queued packets must
	 * not inherit its residence time.
	 */
	static final class ArrivalTimes {

		private long[] ring = new long[16];
		private int head;
		private int size;

		void enqueue(final long arrivalNanos) {
			if(this.size == this.ring.length) {
				final long[] next = new long[this.ring.length << 1];
				System.arraycopy(this.ring, this.head, next, 0, this.ring.length - this.head);
				System.arraycopy(this.ring, 0, next, this.ring.length - this.head, this.head);
				this.ring = next;
				this.head = 0;
			}
			this.ring[(this.head + this.size) % this.ring.length] = arrivalNanos;
			++this.size;
		}

		long headNanos() {
			return this.size == 0 ? 0L : this.ring[this.head];
		}

		void dequeue() {
			if(this.size == 0) {
				throw new IllegalStateException("arrival queue empty");
			}
			this.ring[this.head] = 0L;
			this.head = (this.head + 1) % this.ring.length;
			--this.size;
			if(this.size == 0) {
				this.head = 0;
			}
		}

		void clear() {
			java.util.Arrays.fill(this.ring, 0L);
			this.head = 0;
			this.size = 0;
		}

		boolean isEmpty() {
			return this.size == 0;
		}

		int size() {
			return this.size;
		}
	}
}

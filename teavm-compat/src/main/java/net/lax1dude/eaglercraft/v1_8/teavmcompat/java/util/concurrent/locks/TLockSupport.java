package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.locks;

/**
 * java.util.concurrent.locks.LockSupport for the single-threaded browser runtime.
 *
 * <p>The TeaVM client runs Java threads as cooperative GREEN THREADS on the one JS
 * thread: a coroutine keeps the CPU until it hits an async suspension point. So
 * park() MUST actually suspend (yield to the scheduler + browser event loop) — a
 * no-op park would let any thread that idle-waits for work (e.g. a
 * {@code BlockableEventLoop} like {@code SoundEngineExecutor} or the integrated
 * server, whose {@code managedBlock} loop never sees its stop flag flip during a
 * frame) SPIN forever and STARVE the main render thread, hanging the whole client.
 *
 * <p>We implement parking as a short timed green-thread sleep and return
 * (spuriously if untimed — which the LockSupport contract explicitly permits).
 * Callers (AbstractQueuedSynchronizer, BlockableEventLoop, ...) all re-check their
 * own condition after park returns, so a spurious/poll-style wakeup is correct;
 * unpark stays a no-op because park always returns on its own. The sleep yields
 * the JS thread so the main loop, other event loops, and rAF actually run.</p>
 */
public final class TLockSupport {

	/** Poll cadence for parked green threads (ms). Small enough to stay responsive,
	 *  large enough not to busy the scheduler; setTimeout clamps very small values. */
	private static final long PARK_SLICE_MS = 1L;
	// Cap the park slice so a parked green thread re-checks its condition (and polls queued tasks)
	// within ~5 ms. Since unpark is a no-op, a thread parked for a full server tick (~50 ms) otherwise
	// ignores a just-scheduled cross-realm packet (block-break, chunk-ack) for up to ~44 ms — the root
	// of the server-worker "break reappears / chunks crawl" latency. 5 ms adds modest wakeup churn but
	// cuts per-hop IPC latency ~9x. Does NOT change TPS (ticks are gated by haveTime(), not park length).
	private static final long MAX_PARK_MS = 5L;

	private TLockSupport() {
	}

	private static void yieldSlice(long millis) {
		try {
			Thread.sleep(millis <= 0L ? PARK_SLICE_MS : Math.min(MAX_PARK_MS, millis));
		} catch (InterruptedException e) {
			// LockSupport.park returns (without throwing) on interrupt, leaving the
			// interrupt status set for the caller to observe.
			Thread.currentThread().interrupt();
		}
	}

	public static void park() {
		yieldSlice(PARK_SLICE_MS);
	}

	public static void park(Object blocker) {
		yieldSlice(PARK_SLICE_MS);
	}

	public static void parkNanos(long nanos) {
		if (nanos <= 0L) {
			return;
		}
		yieldSlice(Math.max(PARK_SLICE_MS, nanos / 1_000_000L));
	}

	public static void parkNanos(Object blocker, long nanos) {
		parkNanos(nanos);
	}

	public static void parkUntil(long deadline) {
		yieldSlice(PARK_SLICE_MS);
	}

	public static void parkUntil(Object blocker, long deadline) {
		yieldSlice(PARK_SLICE_MS);
	}

	public static void unpark(Thread thread) {
		// no-op: park always returns on its own (timed/spurious), so there is no
		// suspended permit to release.
	}

	public static Object getBlocker(Thread t) {
		return null;
	}

}

package net.lax1dude.eaglercraft.v1_8.socket;

/**
 * Selects a bounded WebSocket receive budget from the queue depth observed at
 * the beginning of a client network tick.
 *
 * <p>The original EaglercraftX 1.8 client consumes every frame made available
 * by its platform socket on each network tick. Modern 26.2 packet handling is
 * heavier, so this keeps a time and frame bound while still allowing bursty
 * EaglerX servers to catch up. In particular, it avoids the old fixed limit of
 * 16 frames per 20 Hz network tick (only 320 messages per second).</p>
 */
final class WebSocketPumpPolicy {

	private static final int MEDIUM_BACKLOG = 512;
	private static final int SEVERE_BACKLOG = 4096;

	private WebSocketPumpPolicy() {
	}

	static int frameLimit(final int queuedFrames) {
		if (queuedFrames >= SEVERE_BACKLOG) {
			return 4096;
		}
		if (queuedFrames >= MEDIUM_BACKLOG) {
			return 1024;
		}
		return 256;
	}

	static long timeBudgetMillis(final int queuedFrames) {
		if (queuedFrames >= SEVERE_BACKLOG) {
			return 24L;
		}
		if (queuedFrames >= MEDIUM_BACKLOG) {
			return 12L;
		}
		return 6L;
	}
}

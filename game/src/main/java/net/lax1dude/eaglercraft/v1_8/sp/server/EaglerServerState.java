package net.lax1dude.eaglercraft.v1_8.sp.server;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Worker-side mirror of the client state IntegratedServer used to read directly
 * from Minecraft statics (the shared-memory couplings the eagler split removes).
 * Fed by IPCPacket20OptionsSnapshot in EaglerIntegratedServerWorker26; read by the
 * hosted-gated patches in IntegratedServer.tickServer.
 */
public class EaglerServerState {

	private static volatile boolean pauseRequested = false;
	private static volatile boolean lanPublished = false;
	private static volatile int renderDistance = 10;
	private static volatile int simulationDistance = 10;
	private static volatile float entityDistanceScaling = 1.0f;
	private static volatile boolean interactiveWorldgen = false;
	private static volatile long nextServerTickDeadlineMillis = -1L;
	private static volatile long lastWorldgenTimerHandoffMillis = -1L;
	private static final long WORLDGEN_TIMER_HANDOFF_MIN_INTERVAL_MILLIS = 40L;
	private static final int NORMAL_WORLDGEN_LAYER_BATCH = 2;
	private static final int FAST_WORLDGEN_LAYER_BATCH = 4;
	private static final int WORLDGEN_BACKLOG_HIGH_WATER = 8;
	private static final int FAST_TICKS_REQUIRED = 10;
	private static volatile int worldgenQueueDepth = 0;
	private static volatile int worldgenLayerBatch = NORMAL_WORLDGEN_LAYER_BATCH;
	private static volatile int fastServerTickStreak = 0;
	private static volatile long serverTickEwmaTimesEight = 400L;
	private static volatile ResourceKey<Level> sparseStoredDimension = null;
	private static volatile long sparseStoredChunk = Long.MIN_VALUE;

	public static void updateFromSnapshot(int render, int simulation, float entityScale, boolean paused) {
		renderDistance = render;
		simulationDistance = simulation;
		entityDistanceScaling = entityScale;
		pauseRequested = resolvePauseRequested(paused, lanPublished);
	}

	public static void setInitialDistances(int render, int simulation) {
		renderDistance = render;
		simulationDistance = simulation;
		lanPublished = false;
		pauseRequested = false;
		interactiveWorldgen = false;
		nextServerTickDeadlineMillis = -1L;
		lastWorldgenTimerHandoffMillis = -1L;
		resetWorldgenThroughputController();
		clearSparseStoredChunk();
	}

	public static void setLANPublished(boolean published) {
		lanPublished = published;
		if (published) {
			pauseRequested = false;
		}
	}

	static boolean resolvePauseRequested(boolean requested, boolean published) {
		return requested && !published;
	}

	public static void setSparseStoredChunk(ResourceKey<Level> dimension, ChunkPos pos) {
		sparseStoredDimension = dimension;
		sparseStoredChunk = pos.pack();
	}

	public static void clearSparseStoredChunk() {
		sparseStoredDimension = null;
		sparseStoredChunk = Long.MIN_VALUE;
	}

	public static boolean isSparseStoredChunk(ResourceKey<Level> dimension, ChunkPos pos) {
		return sparseStoredDimension != null && sparseStoredDimension.equals(dimension)
				&& sparseStoredChunk == pos.pack();
	}

	public static void setInteractiveWorldgen(boolean interactive) {
		interactiveWorldgen = interactive;
		if (!interactive) {
			lastWorldgenTimerHandoffMillis = -1L;
			resetWorldgenThroughputController();
		}
	}

	public static boolean isInteractiveWorldgen() {
		return interactiveWorldgen;
	}

	/**
	 * Raise only the already-bounded chunk-layer scheduling window when the live
	 * server has both sustained tick headroom and queued generation work. A slow
	 * tick immediately restores the conservative window so entities and gameplay
	 * keep their 20 TPS budget. Generation order and the worldgen time slice are
	 * unchanged.
	 */
	public static void recordServerTickMillis(final long tickMillis) {
		if (!interactiveWorldgen) {
			return;
		}
		long clampedMillis = Math.max(0L, Math.min(tickMillis, 250L));
		long ewma = (serverTickEwmaTimesEight * 7L + clampedMillis * 8L) / 8L;
		serverTickEwmaTimesEight = ewma;
		if (clampedMillis >= 35L || ewma >= 208L) {
			worldgenLayerBatch = NORMAL_WORLDGEN_LAYER_BATCH;
			fastServerTickStreak = 0;
		} else if (worldgenQueueDepth >= WORLDGEN_BACKLOG_HIGH_WATER
				&& clampedMillis <= 18L && ewma <= 144L) {
			int streak = Math.min(FAST_TICKS_REQUIRED, fastServerTickStreak + 1);
			fastServerTickStreak = streak;
			if (streak >= FAST_TICKS_REQUIRED) {
				worldgenLayerBatch = FAST_WORLDGEN_LAYER_BATCH;
			}
		} else if (clampedMillis > 22L || ewma > 176L) {
			worldgenLayerBatch = NORMAL_WORLDGEN_LAYER_BATCH;
			fastServerTickStreak = 0;
		} else if (worldgenQueueDepth < 4 && fastServerTickStreak > 0) {
			fastServerTickStreak--;
		}
	}

	public static void updateWorldgenQueueDepth(final int depth) {
		worldgenQueueDepth = Math.max(0, depth);
	}

	public static int getWorldgenLayerBatchSize() {
		return worldgenLayerBatch;
	}

	public static long getServerTickEwmaMillis() {
		return serverTickEwmaTimesEight / 8L;
	}

	private static void resetWorldgenThroughputController() {
		worldgenQueueDepth = 0;
		worldgenLayerBatch = NORMAL_WORLDGEN_LAYER_BATCH;
		fastServerTickStreak = 0;
		serverTickEwmaTimesEight = 400L;
	}

	public static void updateNextServerTickDeadline(final long nanosUntilTick) {
		nextServerTickDeadlineMillis = EagRuntime.steadyTimeMillis()
				+ Math.max(0L, nanosUntilTick / 1000000L);
	}

	/**
	 * Keep world generation busy while the server is sleeping, but return control in
	 * time for its next 20 TPS deadline. The cap also services worker messages and
	 * chunk-light futures when the server has a long idle window.
	 */
	public static long worldgenMillisUntilYield(final long now, final long maxSliceMillis) {
		final long minSliceMillis = Math.min(8L, maxSliceMillis);
		long deadline = nextServerTickDeadlineMillis;
		if (deadline < 0L) {
			return minSliceMillis;
		}
		long remaining = deadline - now;
		// A stale/passed deadline must not produce a zero-length yield loop. The
		// server continuation is now sub-millisecond, so give generation a useful
		// minimum quantum before checking the tick deadline again.
		return Math.max(minSliceMillis, Math.min(remaining, maxSliceMillis));
	}

	/**
	 * MessageChannel is the low-overhead continuation for normal worldgen, but a
	 * continuously replenished message queue can run ahead of the timer used by the
	 * sleeping server tick. Request a timer-queue handoff only after the next tick is
	 * actually due. The interval bound prevents hot inner loops from paying the
	 * browser's timer clamp repeatedly while the server continuation is still pending.
	 */
	public static boolean claimWorldgenTimerHandoff(final long now) {
		long deadline = nextServerTickDeadlineMillis;
		if (!interactiveWorldgen || deadline < 0L || now < deadline) {
			return false;
		}
		long last = lastWorldgenTimerHandoffMillis;
		if (last >= 0L && now - last < WORLDGEN_TIMER_HANDOFF_MIN_INTERVAL_MILLIS) {
			return false;
		}
		lastWorldgenTimerHandoffMillis = now;
		return true;
	}

	public static boolean isPauseRequested() {
		return pauseRequested;
	}

	public static int getRenderDistance() {
		return renderDistance;
	}

	public static int getSimulationDistance() {
		return simulationDistance;
	}

	public static float getEntityDistanceScaling() {
		return entityDistanceScaling;
	}

}

/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.mesh;

/**
 * Bridge between the game dispatcher and the platform worker pool.
 * Without an installed bridge, compilation stays on the inline path.
 */
public final class MeshWorkerRuntime {

	/**
	 * Implemented by the platform layer's {@code MeshWorkerPool}. The dispatcher submits
	 * encoded {@link MeshJobCodec} jobs and receives back encoded {@link MeshResultCodec}
	 * blobs through a {@link MeshResultSink}.
	 */
	public interface MeshWorkerBridge {

		/** True once at least one worker has hydrated its model table and can compile. */
		boolean isReady();

		/** True after the first model-table broadcast is attempted. */
		boolean isStarted();

		/** Starts worker bootstrap before the model table is available. Idempotent. */
		void prewarm();

		/** Permit the remaining workers to boot after the first playable terrain mesh. */
		void releaseRemainingWorkers();

		/** Releases all world-owned workers and retained model/job data. */
		void releaseWorld();

		/** Broadcasts a model-table delta and retains it for late workers. */
		void broadcastDelta(byte[] deltaTable);

		/** Replaces many retained sparse deltas with one equivalent compact delta. */
		void compactRetainedDeltas(byte[] compactDeltaTable);

		/** Starts the pool and broadcasts the initial model table. Idempotent. */
		void ensureStarted(byte[] modelTable, double encodeMs);

		/** Routes compilation inline until a refreshed model table is acknowledged. */
		void invalidateForReload(String reason);

		/** Broadcasts a refreshed model table under a new epoch. */
		void rebroadcastTable(byte[] modelTable, double encodeMs, String reason);

		/** Updates render and mesh timing counters. */
		void reportFrameStats(double previousFrameMs, double worstFrameMs, double snapshotMsFrame, double installMsFrame,
					int queuedSections, int queuedResults, int dispatcherInFlight);

		/** Propagates real focus/visibility/resume state to the adaptive growth controller. */
		void reportDisplayState(boolean displayActive, boolean pageVisible, boolean resumed);

			/** Bounded renderer-residency counters used by the browser memory plateau gate. */
			void reportMemoryStats(int gpuHeaps, int gpuAllocations, int stagedAllocations,
					double gpuCapacityBytes, double gpuAllocatedBytes);

		/** Submits to the least-loaded worker with capacity. */
		boolean submit(int jobId, byte[] jobBytes, MeshResultSink sink, boolean priority);

		/** True when at least one hydrated worker can accept a job without mailboxing it. */
		boolean hasCapacity(boolean priority);

		/**
		 * Drop an obsolete job's callback and retained bookkeeping. The worker may finish its
		 * synchronous compile, but the returned buffer is discarded before it is copied into the
		 * main Java heap.
		 */
		void cancel(int jobId);

		/** True when sampled parity verification is enabled. */
		boolean verifyEnabled();

		/** Counter hook: an async task fell back to the inline path (pool busy/unavailable). */
		void reportInlineFallback();

		/** Counter hook for fluid sections compiled inline. */
		void reportFluidInline();

		/** Counter hook: a 1-in-32 verify ran; {@code mismatch} true iff it diverged. */
		void reportVerify(boolean mismatch);
	}

	/** Callback surface for a routed job's result, always invoked on the main thread. */
	public interface MeshResultSink {
		/** The worker returned an encoded {@link MeshResultCodec} blob for {@code jobId}. */
		void onResult(int jobId, byte[] resultBytes);

		/** The worker failed the job (compile threw / worker crashed). */
		void onError(int jobId, String message);
	}

	/** Encodes a model-table delta for the requested ids. */
	public interface ModelTableDeltaEncoder {
		byte[] encodeDelta(java.util.Set<Integer> ids);
	}

	private static MeshWorkerBridge bridge;
	private static ModelTableDeltaEncoder deltaEncoder;
	/** Ids already covered by the base table + all broadcast deltas (main thread only). */
	private static final java.util.BitSet tableIds = new java.util.BitSet();
	/** IDs learned after the base table; periodically re-encoded as one retained delta. */
	private static final java.util.BitSet dynamicTableIds = new java.util.BitSet();
	private static int deltasSinceCompaction;
	private static final int DELTA_COMPACTION_INTERVAL = 16;

	private MeshWorkerRuntime() {
	}

	/** Installs the delta encoder and resets table coverage. */
	public static void installDeltaEncoder(final ModelTableDeltaEncoder enc, final java.util.Set<Integer> coveredIds) {
		deltaEncoder = enc;
		tableIds.clear();
		dynamicTableIds.clear();
		deltasSinceCompaction = 0;
		if (coveredIds != null) {
			for (Integer id : coveredIds) {
				tableIds.set(id.intValue());
			}
		}
	}

	/** Broadcasts missing model ids before a dependent job is submitted. */
	public static boolean ensureTableCoverage(final int[] paletteIds) {
		java.util.Set<Integer> missing = null;
		for (int id : paletteIds) {
			if (id >= 0 && !tableIds.get(id)) {
				if (missing == null) {
					missing = new java.util.HashSet<>();
				}
				missing.add(id);
			}
		}
		if (missing == null) {
			return true;
		}
		if (deltaEncoder == null || bridge == null) {
			return false;
		}
		byte[] delta = deltaEncoder.encodeDelta(missing);
		bridge.broadcastDelta(delta);
		for (Integer id : missing) {
			tableIds.set(id.intValue());
			dynamicTableIds.set(id.intValue());
		}
		if (++deltasSinceCompaction >= DELTA_COMPACTION_INTERVAL) {
			java.util.Set<Integer> allDynamic = new java.util.HashSet<>();
			for (int id = dynamicTableIds.nextSetBit(0); id >= 0; id = dynamicTableIds.nextSetBit(id + 1)) {
				allDynamic.add(id);
				if (id == Integer.MAX_VALUE) break;
			}
			bridge.compactRetainedDeltas(deltaEncoder.encodeDelta(allDynamic));
			deltasSinceCompaction = 0;
		}
		return true;
	}

	public static void setBridge(final MeshWorkerBridge b) {
		bridge = b;
	}

	public static boolean hasBridge() {
		return bridge != null;
	}

	public static boolean isReady() {
		return bridge != null && bridge.isReady();
	}

	public static boolean isStarted() {
		return bridge != null && bridge.isStarted();
	}

	public static void ensureStarted(final byte[] modelTable, final double encodeMs) {
		if (bridge != null) {
			bridge.ensureStarted(modelTable, encodeMs);
		}
	}

	public static void invalidateForReload(final String reason) {
		if (bridge != null) {
			bridge.invalidateForReload(reason);
		}
	}

	public static void rebroadcastTable(final byte[] modelTable, final double encodeMs, final String reason) {
		if (bridge != null) {
			bridge.rebroadcastTable(modelTable, encodeMs, reason);
		}
	}

	public static void reportFrameStats(final double previousFrameMs, final double worstFrameMs, final double snapshotMsFrame,
			final double installMsFrame, final int queuedSections, final int queuedResults,
			final int dispatcherInFlight) {
		if (bridge != null) {
			bridge.reportFrameStats(previousFrameMs, worstFrameMs, snapshotMsFrame, installMsFrame,
					queuedSections, queuedResults, dispatcherInFlight);
		}
	}

	public static void reportDisplayState(final boolean displayActive, final boolean pageVisible,
			final boolean resumed) {
		if (bridge != null) {
			bridge.reportDisplayState(displayActive, pageVisible, resumed);
		}
	}

	public static void reportMemoryStats(final int gpuHeaps, final int gpuAllocations,
			final int stagedAllocations, final double gpuCapacityBytes, final double gpuAllocatedBytes) {
		if (bridge != null) {
			bridge.reportMemoryStats(gpuHeaps, gpuAllocations, stagedAllocations,
					gpuCapacityBytes, gpuAllocatedBytes);
		}
	}

	public static boolean submit(final int jobId, final byte[] jobBytes, final MeshResultSink sink,
			final boolean priority) {
		return bridge != null && bridge.submit(jobId, jobBytes, sink, priority);
	}

	public static boolean hasCapacity(final boolean priority) {
		return bridge != null && bridge.hasCapacity(priority);
	}

	public static void cancel(final int jobId) {
		if (bridge != null) {
			bridge.cancel(jobId);
		}
	}

	/** Starts worker bootstrap before world entry. */
	public static void prewarm() {
		if (bridge != null) {
			bridge.prewarm();
		}
	}

	public static void releaseRemainingWorkers() {
		if (bridge != null) {
			bridge.releaseRemainingWorkers();
		}
	}

	public static void releaseWorld() {
		deltaEncoder = null;
		tableIds.clear();
		dynamicTableIds.clear();
		deltasSinceCompaction = 0;
		SectionSnapshotBuilder.clearScratchPool();
		if (bridge != null) {
			bridge.releaseWorld();
		}
	}

	public static boolean verifyEnabled() {
		return bridge != null && bridge.verifyEnabled();
	}

	public static void reportInlineFallback() {
		if (bridge != null) {
			bridge.reportInlineFallback();
		}
	}

	public static void reportFluidInline() {
		if (bridge != null) {
			bridge.reportFluidInline();
		}
	}

	public static void reportVerify(final boolean mismatch) {
		if (bridge != null) {
			bridge.reportVerify(mismatch);
		}
	}
}

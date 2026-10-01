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

import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

import org.teavm.jso.JSBody;
import org.teavm.jso.dom.events.ErrorEvent;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.workers.Worker;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap;
import net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerMain.MetaHandler;
import net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerRuntime.MeshResultSink;
import net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerRuntime.MeshWorkerBridge;
import net.lax1dude.eaglercraft.v1_8.sp.internal.ClientPlatformSingleplayer;

/**
 * Web Worker pool for chunk-section compilation.
 * Unavailable workers fall back to inline compilation.
 */
public final class MeshWorkerPool implements MeshWorkerBridge {

	private static final int PER_WORKER_INFLIGHT_CAP = 2;
	private static final String WORKER_TAIL = "\n\nmain([\"_worker_process_\"]);";
	// Keep one compiled worker warm at all times. Under browser heap pressure, reclaim each extra
	// Wasm-GC isolate as soon as that worker drains; Apple platforms also shrink after a quiet period.
	private static final boolean IDLE_SHRINK_ENABLED = true;
	private static final int IDLE_FLOOR = 1;
	private static final int IDLE_SHRINK_MS = 45_000;
	private static final int SHRINK_HEARTBEAT_MS = 10_000;
	private static final double HEAP_PRESSURE_SHRINK_RATIO = 0.52;
	private static final double HEAP_PRESSURE_RECOVER_RATIO = 0.42;
	private static final int FRAME_SAMPLE_COUNT = 180;

	private final boolean verify;
	/** Runtime-selected upper bound. This is a ceiling, not an eager worker count. */
	private final int poolSize;
	private final int initialWorkerTarget;
	private final boolean explicitWorkerCount;
	private final MeshWorkerPolicy.Profile policyProfile;
	private final MeshWorkerPolicy.GrowthController growthPolicy;
	/** Auto mode lowers this when a growth trial hurts frame pacing on this device. */
	private int learnedWorkerCeiling;

	private WorkerSlot[] slots;
	private boolean started;
	/** True after title-screen worker startup. */
	private boolean prewarmed;
	private boolean disabled;
	private byte[] modelTable;
	/** Ordered deltas retained for late and replacement workers. */
	private final java.util.ArrayList<byte[]> deltas = new java.util.ArrayList<>();
	private long deltasBroadcast;
	private double deltaBytesTotal;
	private int readyCount;
	// The speed profile boots every runtime at the title screen so world entry never waits
	// for another 10-15 second Wasm parse after the center section becomes visible.
	private String workerUrl;
	private int spawnedCount;
	/** Number of workers currently requested; grows one step at a time under measured saturation. */
	private int desiredWorkerCount;
	private boolean allowPoolExpansion;
	/** Invalidates delayed spawn/heartbeat callbacks and late messages from a terminated world. */
	private int lifecycleGeneration;
	private static final int SPAWN_FALLBACK_MS = 20000;
	private static final int GROWTH_SPAWN_TIMEOUT_MS = 12000;

	// jobId -> sink + send timestamp (for the workerMs rtt counter)
	private final Map<Integer, MeshResultSink> sinksByJob = new HashMap<>();
	private final Map<Integer, Double> sendTimeByJob = new HashMap<>();
	// jobId -> owning slot index (to decrement its in-flight on return)
	private final Map<Integer, Integer> slotByJob = new HashMap<>();
	// Retained until the worker returns, including for cancelled jobs, so per-slot byte load can
	// be decremented without retaining the transferred Java byte[] itself.
	private final Map<Integer, Integer> bytesByJob = new HashMap<>();
	// Model-table epoch for each in-flight job.
	private final Map<Integer, Integer> epochByJob = new HashMap<>();
	/** Current model-table epoch; bumped on every re-broadcast (atlas re-stitch / model rebake). */
	private int tableEpoch;
	/** performance.now() of the last reload/invalidate (published; -1 = none). */
	private double lastReloadMs = -1.0;

	// Idle-shrink state.
	private double lastJobMs;
	private int saturationSamples;
	private long growthEvents;
	private long rejectedGrowthEvents;
	private boolean heartbeatScheduled;
	private boolean memoryPressureActive;
	private final double[] recentFrameMs = new double[FRAME_SAMPLE_COUNT];
	private int recentFrameCursor;
	private int recentFrameCount;
	private double frameEwmaMs;
	private double frameP95Ms;
	private double frameP99Ms;
	private double lastFrameP95UpdateMs;
	private final long[] snapshotScratchStats = new long[6];
	private int growthTrialWorker = -1;
	private boolean growthTrialPending;
	private boolean growthTrialActive;
	private boolean growthTrialRollbackPending;
	private double growthTrialStartedMs;
	private double growthTrialBaselineP95;
	private double growthTrialBaselineP99;
	private double growthTrialBaselineEwma;
	private int growthTrialBaselineLongFrames;
	private int growthTrialStartQueue;
	private int growthTrialStartCompletedJobs;
	private double growthTrialSpawnCostMs;

	// Render-thread telemetry.
	private double worstFrameMs;
	private double snapshotMsFrame;
	private double installMsFrame;
	private int queuedSections;
	private int queuedResults;
	private int dispatcherInFlight;

	// counters published to globalThis.__meshWorkerPool
	private long jobsRouted;
	private long completedJobs;
	private long inlineFallbacks;
	private long fluidInline;
	private long verifyChecks;
	private long verifyMismatches;
	private long errors;
	private long cancelledJobs;
	private double cancelledBytes;
	private double workerMsTotal;
	private double heapPressureRatio;
	/** Real render/display state gates auto-growth; hidden/background frames never grow. */
	private boolean displayActive = true;
	private boolean pageVisible = true;
	private boolean displayResumed;
	private long shrinkEvents;
	/** Cumulative CPU time spent encoding the model table. */
	private double tableEncodeMs;

	private static final class WorkerSlot {
		final int index;
		final Worker worker;
		int inFlight;
		int inFlightBytes;
		boolean ready;
		/** Worker can accept a model table. */
		boolean booted;
		/** Base model table has been posted. */
		boolean baseSent;
		/** Worker creation timestamp. */
		double spawnedAtMs;
		/** Worker startup duration, or -1 until booted. */
		double bootMs = -1.0;
		/** Hydrated and pending model-table epochs. */
		int hydratedEpoch = -1;
		int pendingEpoch = -1;

		WorkerSlot(final int index, final Worker worker) {
			this.index = index;
			this.worker = worker;
		}
	}

	@org.teavm.jso.JSFunctor
	private interface PoolBinaryHandler extends org.teavm.jso.JSObject {
		void onBinary(int jobId, ArrayBuffer buf);
	}

	public MeshWorkerPool(final boolean verify) {
		this.verify = verify;
		double memoryGb = deviceMemoryGb();
		int cores = hardwareConcurrency();
		int override = meshWorkerCountOverride();
		this.policyProfile = MeshWorkerPolicy.select(cores, memoryGb, override);
		this.poolSize = this.policyProfile.ceiling();
		this.initialWorkerTarget = this.policyProfile.initialWorkers();
		this.explicitWorkerCount = override >= 1;
		this.growthPolicy = new MeshWorkerPolicy.GrowthController(this.policyProfile);
		this.learnedWorkerCeiling = this.growthPolicy.learnedCeiling();
		this.desiredWorkerCount = this.initialWorkerTarget;
		publishCounters();
	}

	// ============================ MeshWorkerBridge ============================

	@Override
	public boolean isReady() {
		return this.started && !this.disabled && this.readyCount > 0;
	}

	@Override
	public boolean isStarted() {
		return this.started;
	}

	@Override
	public boolean verifyEnabled() {
		return this.verify;
	}

	@Override
	public void reportFrameStats(final double previousFrameMsIn, final double worstFrameMsIn, final double snapshotMsFrameIn,
			final double installMsFrameIn, final int queuedSectionsIn, final int queuedResultsIn,
			final int dispatcherInFlightIn) {
		if (previousFrameMsIn > 0.0 && previousFrameMsIn < 1000.0) {
			this.recentFrameMs[this.recentFrameCursor] = previousFrameMsIn;
			this.recentFrameCursor = (this.recentFrameCursor + 1) % FRAME_SAMPLE_COUNT;
			if (this.recentFrameCount < FRAME_SAMPLE_COUNT) {
				this.recentFrameCount++;
			}
			this.frameEwmaMs = this.frameEwmaMs <= 0.0 ? previousFrameMsIn
					: this.frameEwmaMs * 0.95 + previousFrameMsIn * 0.05;
			double now = nowMs();
			if (now - this.lastFrameP95UpdateMs >= 1000.0) {
				this.frameP95Ms = recentFrameP95();
				this.frameP99Ms = recentFramePercentile(0.99);
				this.lastFrameP95UpdateMs = now;
			}
		}
		this.worstFrameMs = worstFrameMsIn;
		this.snapshotMsFrame = snapshotMsFrameIn;
		this.installMsFrame = installMsFrameIn;
		this.queuedSections = queuedSectionsIn;
		this.queuedResults = queuedResultsIn;
		this.dispatcherInFlight = dispatcherInFlightIn;
		boolean queueSufficient = queuedSectionsIn >= Math.max(2, this.readyCount);
		boolean inFlightSufficient = totalInFlight() >= this.readyCount * PER_WORKER_INFLIGHT_CAP;
		boolean resultBacklogClear = queuedResultsIn <= Math.max(2, this.readyCount * 2);
		boolean saturated = queueSufficient && inFlightSufficient && resultBacklogClear;
		// Observe every frame, including unsaturated/hidden frames, so evidence cannot bridge a
		// background gap or a workload that ended while a spawn was in flight. This deliberately
		// runs before trial evaluation: a demand/visibility loss on the expiry frame must make the
		// trial inconclusive instead of allowing stale benefit samples to accept it.
		noteSaturatedDemand(saturated, queueSufficient, inFlightSufficient, resultBacklogClear);
		evaluateGrowthTrial();
		// Publish with job and worker transitions, not every frame.
	}

	@Override
	public void reportDisplayState(final boolean displayActiveIn, final boolean pageVisibleIn,
			final boolean resumedIn) {
		this.displayActive = displayActiveIn;
		this.pageVisible = pageVisibleIn;
		this.displayResumed = resumedIn;
		if (!displayActiveIn || !pageVisibleIn || resumedIn) {
			if (this.growthTrialPending) {
				// A foreground decision must not leave a not-yet-ready isolate booting while the
				// page is hidden/backgrounded. The generation check on the scheduled timeout still
				// protects the old callback if the world is released concurrently.
				cancelPendingGrowth("display became inactive/hidden");
			}
			// Reset the five-second evidence window immediately; a hidden tab must never wake and
			// allocate a new isolate from a stale foreground queue.
			boolean queueSufficient = this.queuedSections >= Math.max(2, this.readyCount);
			boolean inFlightSufficient = totalInFlight() >= this.readyCount * PER_WORKER_INFLIGHT_CAP;
			boolean resultBacklogClear = this.queuedResults <= Math.max(2, this.readyCount * 2);
			this.growthPolicy.observeDemand((long)nowMs(), this.spawnedCount, false, displayActiveIn,
					pageVisibleIn, resumedIn, this.frameEwmaMs, this.heapPressureRatio,
					this.queuedSections, this.completedJobs, queueSufficient, inFlightSufficient,
					resultBacklogClear);
			this.saturationSamples = this.growthPolicy.saturationSamples();
		}
	}

	@Override
	public void reportMemoryStats(final int gpuHeaps, final int gpuAllocations,
			final int stagedAllocations, final double gpuCapacityBytes, final double gpuAllocatedBytes) {
		publishMemoryStats(gpuHeaps, gpuAllocations, stagedAllocations,
				gpuCapacityBytes, gpuAllocatedBytes);
	}

	@JSBody(params = { "gpuHeaps", "gpuAllocations", "stagedAllocations",
			"gpuCapacityBytes", "gpuAllocatedBytes" }, script = "try {"
			+ " var p = globalThis.__meshWorkerPool;"
			+ " if (p) { p.gpuHeaps = gpuHeaps; p.gpuAllocations = gpuAllocations;"
			+ " p.stagedAllocations = stagedAllocations; p.gpuCapacityBytes = gpuCapacityBytes;"
			+ " p.gpuAllocatedBytes = gpuAllocatedBytes; }"
			+ "} catch(e) {}")
	private static native void publishMemoryStats(int gpuHeaps, int gpuAllocations,
			int stagedAllocations, double gpuCapacityBytes, double gpuAllocatedBytes);

	/** Routes jobs inline until workers acknowledge a refreshed model table. */
	@Override
	public void invalidateForReload(final String reason) {
		if (!this.started || this.disabled || this.slots == null) {
			return;
		}
		this.lastReloadMs = nowMs();
		this.readyCount = 0;
		for (WorkerSlot s : this.slots) {
			if (s != null) {
				s.ready = false;
			}
		}
		log("[MeshWorkerPool] invalidateForReload (" + reason + ") — pool not-ready, routing inline until re-broadcast");
		publishCounters();
	}

	/** Broadcasts a refreshed table under a new epoch. */
	@Override
	public void rebroadcastTable(final byte[] table, final double encodeMs, final String reason) {
		if (!this.started || this.disabled || this.slots == null) {
			// Fall back to initial startup.
			ensureStarted(table, encodeMs);
			return;
		}
		this.tableEpoch++;
		this.modelTable = table;
		this.tableEncodeMs = encodeMs;
		this.deltas.clear(); // Deltas belong to the previous base table.
		this.readyCount = 0;
		for (WorkerSlot s : this.slots) {
			if (s == null) {
				continue;
			}
			s.ready = false;
			s.baseSent = false;
			if (s.booted) {
				sendBaseTable(s);
			}
		}
		log("[MeshWorkerPool] table epoch " + this.tableEpoch + " re-broadcast (reason: " + reason + "); "
			+ table.length + " bytes to " + this.spawnedCount + " worker(s)");
		publishCounters();
	}

	/** Starts workers at the title screen before the model table is available. */
	@Override
	public void prewarm() {
		if (this.prewarmed || this.started || this.disabled) {
			return;
		}
		this.prewarmed = true;
		if (!resolveUrlAndAllocSlots()) {
			return;
		}
		this.allowPoolExpansion = true;
		this.desiredWorkerCount = this.initialWorkerTarget;
		log("[MeshWorkerPool] prewarm: starting " + this.initialWorkerTarget + " mesh worker(s), adaptive ceiling "
			+ this.poolSize + (this.explicitWorkerCount ? " (explicit override)" : ""));
		this.lastJobMs = nowMs();
		while (this.spawnedCount < this.desiredWorkerCount) {
			spawnNextWorker();
		}
		startHeartbeat();
		publishCounters();
	}

	@Override
	public void releaseRemainingWorkers() {
		if (this.disabled || this.poolSize <= 1) {
			return;
		}
		this.allowPoolExpansion = true;
		startHeartbeat();
	}

	@Override
	public void releaseWorld() {
		++this.lifecycleGeneration;
		this.heartbeatScheduled = false;
		this.allowPoolExpansion = false;

		if (this.slots != null) {
			for (WorkerSlot slot : this.slots) {
				if (slot != null) {
					terminateWorker(slot.worker);
				}
			}
		}

		// SectionRenderDispatcher cancels its callbacks before this hook runs. Do not invoke
		// those callbacks here: a teardown callback would only requeue work into a closed
		// dispatcher and retain the old ClientLevel again.
		this.sinksByJob.clear();
		this.sendTimeByJob.clear();
		this.slotByJob.clear();
		this.bytesByJob.clear();
		this.epochByJob.clear();
		this.deltas.clear();
		this.modelTable = null;
		this.slots = null;
		this.workerUrl = null;
		this.spawnedCount = 0;
		this.desiredWorkerCount = this.initialWorkerTarget;
		this.readyCount = 0;
		this.started = false;
		this.prewarmed = false;
		this.disabled = false;
		this.tableEpoch = 0;
		this.lastReloadMs = -1.0;
		this.lastJobMs = 0.0;
		this.heapPressureRatio = 0.0;
		this.memoryPressureActive = false;
		this.displayActive = true;
		this.pageVisible = true;
		this.displayResumed = false;
		this.saturationSamples = 0;
		// Keep the learned capacity across world transitions in this browser session. Repeating a
		// growth trial that already hurt this device would add both latency and another Wasm-GC arena.
		this.recentFrameCursor = 0;
		this.recentFrameCount = 0;
		this.frameEwmaMs = 0.0;
		this.frameP95Ms = 0.0;
		this.frameP99Ms = 0.0;
		this.lastFrameP95UpdateMs = 0.0;
		this.growthTrialWorker = -1;
		this.growthTrialPending = false;
		this.growthTrialActive = false;
		this.growthTrialRollbackPending = false;
		this.growthPolicy.resetForWorld();
		this.deltasBroadcast = 0L;
		this.deltaBytesTotal = 0.0;
		this.tableEncodeMs = 0.0;
		publishMemoryStats(0, 0, 0, 0.0, 0.0);
		publishCounters();
		log("[MeshWorkerPool] world release: terminated all mesh workers and cleared retained tables/jobs");
	}

	@Override
	public void ensureStarted(final byte[] table, final double encodeMs) {
		if (this.started || this.disabled) {
			return;
		}
		this.started = true;
		this.modelTable = table;
		this.tableEncodeMs = encodeMs;
		this.deltas.clear();
		if (this.slots == null) {
			// Spawn now when prewarm did not run.
			if (!resolveUrlAndAllocSlots()) {
				return;
			}
			this.allowPoolExpansion = true;
			this.desiredWorkerCount = this.initialWorkerTarget;
			log("[MeshWorkerPool] starting " + this.desiredWorkerCount + " mesh worker(s), adaptive ceiling "
				+ this.poolSize + "; table=" + table.length + " bytes");
			this.lastJobMs = nowMs();
			while (this.spawnedCount < this.desiredWorkerCount) {
				spawnNextWorker();
			}
		} else {
			// Prewarmed at the title screen: workers are parsed + registry-booted (or still
			// booting). Send the base table to every booted slot now; late slots get it from
			// their compile-booted handler.
			int sent = 0;
			for (WorkerSlot s : this.slots) {
				if (s != null && s.booted && !s.baseSent) {
					sendBaseTable(s);
					++sent;
				}
			}
			log("[MeshWorkerPool] table available (" + table.length + " bytes): sent to " + sent
				+ " prewarmed worker(s); " + (this.spawnedCount - sent) + " still booting");
		}
		startHeartbeat();
		publishCounters();
	}

	/** Resolve the classes.js worker URL and allocate the slot array. False = pool disabled. */
	private boolean resolveUrlAndAllocSlots() {
		if (!workerSupported()) {
			log("[MeshWorkerPool] Web Workers unavailable — mesh workers disabled (inline only)");
			this.disabled = true;
			return false;
		}
		// Wasm-GC workers start from the bootstrap and precompiled module.
		if (net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC()) {
			String wasmUrl = net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.resolveBootstrapURL();
			if (wasmUrl == null) {
				log("[MeshWorkerPool] wasm-gc: no precompiled module / worker-bootstrap.js on the shell — mesh workers disabled (inline only)");
				this.disabled = true;
				return false;
			}
			this.slots = new WorkerSlot[this.poolSize];
			this.workerUrl = wasmUrl;
			return true;
		}
		String url;
		try {
			url = ClientPlatformSingleplayer.createMeshWorkerScriptURLTeaVM();
		} catch (Throwable t) {
			url = null;
		}
		if (url == null) {
			url = resolveWorkerURLFallback(WORKER_TAIL);
		}
		if (url == null) {
			log("[MeshWorkerPool] could not resolve classes.js worker URL — mesh workers disabled");
			this.disabled = true;
			return false;
		}
		this.slots = new WorkerSlot[this.poolSize];
		this.workerUrl = url;
		return true;
	}

	/** Post the current base table (+ every stored delta, in order) to one booted worker. */
	private void sendBaseTable(final WorkerSlot slot) {
		slot.pendingEpoch = this.tableEpoch;
		slot.baseSent = true;
		postMeta(slot.worker, "model-table");
		postBinaryTransfer(slot.worker, Int8Array.fromJavaArray(transferSource(this.modelTable)).getBuffer());
		for (byte[] d : this.deltas) {
			postMeta(slot.worker, "table-delta");
			postBinaryTransfer(slot.worker, Int8Array.fromJavaArray(transferSource(d)).getBuffer());
		}
	}

	/** Broadcasts and retains a model-table delta for current and future workers. */
	@Override
	public void broadcastDelta(final byte[] delta) {
		if (this.disabled || this.slots == null) {
			return;
		}
		this.deltas.add(delta);
		this.deltasBroadcast++;
		this.deltaBytesTotal += delta.length;
		for (WorkerSlot s : this.slots) {
			if (s != null && s.baseSent) {
				postMeta(s.worker, "table-delta");
				postBinaryTransfer(s.worker, Int8Array.fromJavaArray(transferSource(delta)).getBuffer());
			}
		}
	}

	@Override
	public void compactRetainedDeltas(final byte[] compactDelta) {
		if (this.disabled || this.slots == null || compactDelta == null) {
			return;
		}
		// Current workers already received the individual deltas. Only late/regrown
		// workers need replay state, so replace that unbounded list with one equivalent
		// sparse table without interrupting in-flight jobs.
		this.deltas.clear();
		this.deltas.add(compactDelta);
		this.deltasBroadcast = 1L;
		this.deltaBytesTotal = compactDelta.length;
		log("[MeshWorkerPool] compacted retained model deltas to " + compactDelta.length + " bytes");
		publishCounters();
	}

	/**
	 * Spawn the next not-yet-started worker (staggered boot). No-op once every slot is spawned or
	 * the pool is disabled. Schedules a fallback timer so a worker that never reports table-ready
	 * still lets its successor boot after {@link #SPAWN_FALLBACK_MS}.
	 */
	private void spawnNextWorker() {
		if (this.disabled || this.slots == null
				|| (this.memoryPressureActive && this.spawnedCount >= IDLE_FLOOR)) {
			return;
		}
		final int i = this.spawnedCount;
		if (i >= this.poolSize || i >= this.desiredWorkerCount) {
			return;
		}
		this.slots[i] = spawnWorker(i, this.workerUrl);
		this.spawnedCount++;
		// Fallback: if the worker we just spawned has not triggered the next spawn within
		// SPAWN_FALLBACK_MS (i.e. spawnedCount is still where it is now), spawn the next anyway
		// so a slow/failed table-ready never strands the remaining workers.
		if (this.allowPoolExpansion && this.spawnedCount < this.desiredWorkerCount) {
			final int spawnedSnapshot = this.spawnedCount;
			final int generationSnapshot = this.lifecycleGeneration;
			scheduleTimeout(() -> {
				if (this.lifecycleGeneration == generationSnapshot && this.spawnedCount == spawnedSnapshot) {
					spawnNextWorker();
				}
			}, SPAWN_FALLBACK_MS);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Successful submission transfers and detaches {@code jobBytes}. Clone it first
	 * when the caller needs to retain the data.
	 */
	@Override
	public boolean submit(final int jobId, final byte[] jobBytes, final MeshResultSink sink, final boolean priority) {
		if (!isReady()) {
			return false;
		}
		this.lastJobMs = nowMs(); // Keep the pool warm after demand.
		if (this.explicitWorkerCount && !this.memoryPressureActive && this.spawnedCount < this.poolSize) {
			this.desiredWorkerCount = this.poolSize;
			spawnNextWorker();
		}
		WorkerSlot best = null;
		for (WorkerSlot s : this.slots) {
			if (s != null && s.ready && s.inFlight < PER_WORKER_INFLIGHT_CAP
					&& !(this.growthTrialRollbackPending && s.index == this.growthTrialWorker)
					&& (best == null || s.inFlight < best.inFlight
							|| s.inFlight == best.inFlight && s.inFlightBytes < best.inFlightBytes)) {
				best = s;
			}
		}
			// Priority affects queue order, not capacity. Exceeding the cap queued unlimited
			// work inside a worker and defeated the render-thread frame budget during block edits.
		if (best == null) {
			// Every ready worker is at its in-flight cap. If the pool was idle-shrunk below its
			// configured size, regrow one worker (staggered) so sustained load gets its capacity
			// back; the caller re-queues this job and it routes to the new worker once it acks.
			noteSaturatedDemand(true,
				this.queuedSections >= Math.max(2, this.readyCount),
				totalInFlight() >= this.readyCount * PER_WORKER_INFLIGHT_CAP,
				this.queuedResults <= Math.max(2, this.readyCount * 2));
			return false;
		}
			best.inFlight++;
			best.inFlightBytes += jobBytes.length;
			this.sinksByJob.put(jobId, sink);
		this.sendTimeByJob.put(jobId, nowMs());
			this.slotByJob.put(jobId, best.index);
			this.bytesByJob.put(jobId, jobBytes.length);
		this.epochByJob.put(jobId, this.tableEpoch); // ready workers are all at the current epoch
		postMeta(best.worker, "job");
		postBinaryTransfer(best.worker, Int8Array.fromJavaArray(jobBytes).getBuffer());
		this.jobsRouted++;
		publishCounters();
		return true;
	}

	@Override
	public boolean hasCapacity(final boolean priority) {
		if (!isReady()) {
			return false;
		}
		for (WorkerSlot slot : this.slots) {
			if (slot != null && slot.ready && slot.inFlight < PER_WORKER_INFLIGHT_CAP
					&& !(this.growthTrialRollbackPending && slot.index == this.growthTrialWorker)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void cancel(final int jobId) {
		MeshResultSink removed = this.sinksByJob.remove(jobId);
		this.sendTimeByJob.remove(jobId);
		this.slotByJob.remove(jobId);
		this.epochByJob.remove(jobId);
		if (removed != null) {
			this.cancelledJobs++;
			Integer bytes = this.bytesByJob.get(jobId);
			if (bytes != null) {
				this.cancelledBytes += bytes.intValue();
			}
			publishCounters();
		}
	}

	@Override
	public void reportInlineFallback() {
		this.inlineFallbacks++;
		publishCounters();
	}

	@Override
	public void reportFluidInline() {
		this.fluidInline++;
		publishCounters();
	}

	@Override
	public void reportVerify(final boolean mismatch) {
		this.verifyChecks++;
		if (mismatch) {
			this.verifyMismatches++;
		}
		publishCounters();
	}

	// Worker lifecycle

	private WorkerSlot spawnWorker(final int index, final String url) {
		// Wasm-GC bootstrap receives the compiled module before queued controls.
		Worker w = net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC()
				? net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.spawnMeshWasmWorker(url)
				: Worker.create(url);
		final WorkerSlot slot = new WorkerSlot(index, w);
		slot.spawnedAtMs = nowMs();
		w.addEventListener("error", new EventListener<ErrorEvent>() {
			@Override
			public void handleEvent(final ErrorEvent evt) {
				if (isCurrentSlot(slot)) {
					onWorkerError(index, "worker error: " + evt.getError());
				}
			}
		});
		// @JSFunctor handlers must be lambdas/method refs on TeaVM (not anonymous classes).
		MetaHandler metaHandler = (final String meta) -> onWorkerMeta(slot, meta);
		PoolBinaryHandler binaryHandler = (final int jobId, final ArrayBuffer buf) -> onWorkerBinary(slot, jobId, buf);
		registerDualChannel(w, metaHandler, binaryHandler);
		// Hand it its role + compile mode + pool framing up front (processed in order); the
		// model table is sent once the worker reports compile-booted (registry bootstrapped).
		postMeta(w, "role:mesh|mip:" + index);
		postMeta(w, "mode:compile");
		postMeta(w, "framing:pool");
		return slot;
	}

	private void onWorkerMeta(final WorkerSlot slot, final String meta) {
		if (meta == null || !isCurrentSlot(slot)) {
			return;
		}
		if (meta.equals("mesh-worker:compile-booted")) {
			// Bootstrap is complete; the model table may arrive later.
			slot.booted = true;
			if (slot.bootMs < 0.0) {
				slot.bootMs = nowMs() - slot.spawnedAtMs; // classes.js parse + registry bootstrap
			}
			log("[MeshWorkerPool] worker " + slot.index + " booted (parse+registry) bootMs=" + (long) slot.bootMs
				+ (this.modelTable != null ? "; sending table" : "; awaiting table (prewarm)"));
			if (this.allowPoolExpansion && this.spawnedCount < this.desiredWorkerCount) {
				spawnNextWorker();
			}
			if (this.modelTable != null && !slot.baseSent) {
				sendBaseTable(slot);
			}
			publishCounters();
		} else if (meta.startsWith("mesh-worker:table-ready")) {
			// Re-acknowledgement advances the hydrated epoch.
			slot.hydratedEpoch = slot.pendingEpoch >= 0 ? slot.pendingEpoch : this.tableEpoch;
			if (!slot.ready) {
				slot.ready = true;
				this.readyCount++;
			}
			if (!this.explicitWorkerCount && this.growthTrialPending && slot.index == this.growthTrialWorker) {
				this.growthTrialPending = false;
				this.growthTrialActive = true;
				this.growthTrialStartedMs = nowMs();
				this.growthTrialStartQueue = this.queuedSections;
				this.growthTrialStartCompletedJobs = (int) this.completedJobs;
				this.growthTrialSpawnCostMs = nowMs() - slot.spawnedAtMs;
				this.growthPolicy.trialReady((long)this.growthTrialStartedMs, this.growthTrialStartQueue,
						this.completedJobs, this.growthTrialSpawnCostMs);
				log("[MeshWorkerPool] adaptive trial started for worker " + slot.index
					+ " (baseline p95=" + (long)this.growthTrialBaselineP95 + "ms, ewma="
					+ (long)this.growthTrialBaselineEwma + "ms, queue=" + this.growthTrialStartQueue + ")");
			}
			log("[MeshWorkerPool] worker " + slot.index + " ready (" + this.readyCount + "/" + this.poolSize
				+ ") epoch=" + slot.hydratedEpoch + " bootMs=" + (long) slot.bootMs + "; " + meta);
			publishCounters();
		} else if (meta.startsWith("mesh-worker:delta-ready")) {
			// Merge was counted at broadcast time.
		} else if (meta.startsWith("mesh-worker:compile-boot-error") || meta.startsWith("mesh-worker:table-error")) {
			onWorkerError(slot.index, meta);
		} else if (meta.startsWith("wlog:")) {
			// WORKERS PORT (?workerlog): worker-bootstrap.js forwards the worker's console as
			// wlog: metas so headless harnesses (which only read window.__log) can see them.
			// Dead on the JS build (its Blob workers never send wlog metas).
			log("[MeshWorker " + slot.index + "] " + meta.substring(5));
		}
		// mesh-worker:role-ack / mesh-worker:booted / mesh-worker:compile-booted are informational
	}

	private void onWorkerBinary(final WorkerSlot slot, final int jobId, final ArrayBuffer buf) {
		if (!isCurrentSlot(slot)) {
			return;
		}
		if (slot.inFlight > 0) {
			slot.inFlight--;
		}
		this.completedJobs++;
		Integer jobBytes = this.bytesByJob.remove(jobId);
		if (jobBytes != null) {
			slot.inFlightBytes = Math.max(0, slot.inFlightBytes - jobBytes.intValue());
		}
		Double sent = this.sendTimeByJob.remove(jobId);
		if (sent != null) {
			this.workerMsTotal += nowMs() - sent.doubleValue();
		}
		this.slotByJob.remove(jobId);
		Integer jobEpoch = this.epochByJob.remove(jobId);
		MeshResultSink sink = this.sinksByJob.remove(jobId);
			if (sink != null) {
				if (jobEpoch != null && jobEpoch.intValue() != this.tableEpoch) {
				// Discard results from an older model-table epoch.
				sink.onError(jobId, "stale table epoch " + jobEpoch + " != " + this.tableEpoch);
			} else {
				// Copy only the payload and only when the task still wants it. Cancelled/replaced
				// sections never materialize the often-large result in the main Wasm-GC heap.
				byte[] blob = new Int8Array(buf).copyToJavaArray();
					sink.onResult(jobId, blob);
				}
			}
			if (this.growthTrialRollbackPending && slot.index == this.growthTrialWorker
					&& slot.inFlight == 0) {
				rollbackGrowthTrial("trial worker drained");
			}
			if (this.memoryPressureActive && this.spawnedCount > IDLE_FLOOR) {
				// This callback runs before the render thread can submit another job. Retire a
				// just-drained tail worker now, even while lower workers remain busy, so sustained
				// terrain travel can actually shed Wasm-GC isolates under memory pressure.
				shrinkToFloor();
			}
			publishCounters();
		}

	private void onWorkerError(final int index, final String message) {
		this.errors++;
		log("[MeshWorkerPool] FATAL from worker " + index + ": " + message + " — disabling pool, falling back inline");
		this.disabled = true;
		this.readyCount = 0;
		// Fail every outstanding job so the dispatcher re-bakes it inline.
			for (Map.Entry<Integer, MeshResultSink> e : new HashMap<>(this.sinksByJob).entrySet()) {
			this.sinksByJob.remove(e.getKey());
			this.sendTimeByJob.remove(e.getKey());
				this.slotByJob.remove(e.getKey());
				this.bytesByJob.remove(e.getKey());
			this.epochByJob.remove(e.getKey());
				e.getValue().onError(e.getKey(), message);
			}
			this.bytesByJob.clear();
			if (this.slots != null) {
				for (WorkerSlot slot : this.slots) {
					if (slot != null) {
						slot.inFlight = 0;
						slot.inFlightBytes = 0;
					}
				}
			}
			publishCounters();
		}

	// Idle shrink

	private void startHeartbeat() {
		if (!IDLE_SHRINK_ENABLED || this.heartbeatScheduled || this.disabled) {
			return;
		}
		this.heartbeatScheduled = true;
		final int generationSnapshot = this.lifecycleGeneration;
		scheduleTimeout(() -> {
			if (this.lifecycleGeneration == generationSnapshot) {
				heartbeat();
			}
		}, SHRINK_HEARTBEAT_MS);
	}

	private boolean isCurrentSlot(final WorkerSlot slot) {
		return this.slots != null && slot.index >= 0 && slot.index < this.slots.length
				&& this.slots[slot.index] == slot;
	}

	private void heartbeat() {
		this.heartbeatScheduled = false;
		if (this.disabled || this.slots == null) {
			return;
		}
		this.heapPressureRatio = browserHeapPressureRatio();
		if (this.heapPressureRatio >= HEAP_PRESSURE_SHRINK_RATIO) {
			this.memoryPressureActive = true;
		} else if (this.memoryPressureActive && this.heapPressureRatio <= HEAP_PRESSURE_RECOVER_RATIO) {
			this.memoryPressureActive = false;
		}
		// Do not shrink prewarmed workers before their first model table.
		boolean awaitingFirstWorld = this.prewarmed && this.modelTable == null;
		double idleMs = this.lastJobMs <= 0.0 ? Double.MAX_VALUE : nowMs() - this.lastJobMs;
		boolean quietLongEnough = idleMs >= IDLE_SHRINK_MS;
		boolean workDrained = this.sinksByJob.isEmpty() && totalInFlight() == 0;
		if (!awaitingFirstWorld && this.spawnedCount > IDLE_FLOOR && this.memoryPressureActive) {
			// Do not require the entire pool to drain. shrinkToFloor only removes contiguous tail
			// workers whose own in-flight count is zero; busy workers continue uninterrupted.
			shrinkToFloor();
		} else if (!awaitingFirstWorld && this.spawnedCount > IDLE_FLOOR && workDrained && quietLongEnough) {
			shrinkToFloor();
		}
		publishCounters();
		startHeartbeat(); // keep ticking
	}

	private int totalInFlight() {
		int result = 0;
		if (this.slots != null) {
			for (WorkerSlot slot : this.slots) {
				if (slot != null) {
					result += slot.inFlight;
				}
			}
		}
		return result;
	}

	/**
	 * Grow by one worker only after the pure policy observes a sustained, visible saturated queue
	 * and the browser still has frame and heap headroom. This deliberately ignores UA strings:
	 * mobile/Chromebook/desktop all use the same observed-capacity rule.
	 */
	private void noteSaturatedDemand(final boolean saturated) {
		noteSaturatedDemand(saturated,
				this.queuedSections >= Math.max(2, this.readyCount),
				totalInFlight() >= this.readyCount * PER_WORKER_INFLIGHT_CAP,
				this.queuedResults <= Math.max(2, this.readyCount * 2));
	}

	private void noteSaturatedDemand(final boolean saturated, final boolean queueSufficient,
			final boolean inFlightSufficient, final boolean resultBacklogClear) {
		if (this.explicitWorkerCount || !this.allowPoolExpansion || this.disabled || this.readyCount <= 0) {
			return;
		}
		double now = nowMs();
		double currentHeapPressure = browserHeapPressureRatio();
		MeshWorkerPolicy.Decision decision = this.growthPolicy.observeDemand((long)now, this.spawnedCount,
				saturated, this.displayActive, this.pageVisible, this.displayResumed,
				this.frameEwmaMs, currentHeapPressure, this.queuedSections, this.completedJobs,
				queueSufficient, inFlightSufficient, resultBacklogClear);
		this.saturationSamples = this.growthPolicy.saturationSamples();
		if (decision.action() != MeshWorkerPolicy.Decision.Action.GROW) {
			return;
		}
		this.desiredWorkerCount = Math.min(this.poolSize, Math.max(this.spawnedCount + 1,
				this.desiredWorkerCount + 1));
		this.saturationSamples = 0;
		this.growthEvents++;
		this.growthTrialWorker = decision.worker();
		this.growthTrialPending = true;
		this.growthTrialBaselineP95 = recentFrameP95();
		this.growthTrialBaselineP99 = recentFramePercentile(0.99);
		this.frameP95Ms = this.growthTrialBaselineP95;
		this.frameP99Ms = this.growthTrialBaselineP99;
		this.growthTrialBaselineEwma = this.frameEwmaMs;
		this.growthTrialBaselineLongFrames = recentLongFrameCount();
		log("[MeshWorkerPool] adaptive grow to " + this.desiredWorkerCount + "/" + this.poolSize
			+ " after sustained saturation (frameEwma=" + (long)this.frameEwmaMs + "ms, p95="
			+ (long)this.growthTrialBaselineP95 + "ms, heap="
			+ (long)(currentHeapPressure * 100.0) + "%)");
		spawnNextWorker();
		final int pendingWorker = this.growthTrialWorker;
		final int generationSnapshot = this.lifecycleGeneration;
		scheduleTimeout(() -> pendingGrowthSpawnTimeout(generationSnapshot, pendingWorker),
				GROWTH_SPAWN_TIMEOUT_MS);
		publishCounters();
	}

	/** Terminate a growth worker that never hydrates; stale callbacks cannot affect a new world. */
	private void pendingGrowthSpawnTimeout(final int generationSnapshot, final int pendingWorker) {
		if (this.lifecycleGeneration != generationSnapshot || !this.growthTrialPending
				|| this.growthTrialWorker != pendingWorker || this.slots == null
				|| pendingWorker < 0 || pendingWorker >= this.spawnedCount) {
			return;
		}
		cancelPendingGrowth("pending spawn timeout");
	}

	private void cancelPendingGrowth(final String reason) {
		if (!this.growthTrialPending || this.slots == null || this.growthTrialWorker < 0
				|| this.growthTrialWorker >= this.spawnedCount) {
			return;
		}
		final int pendingWorker = this.growthTrialWorker;
		MeshWorkerPolicy.Decision decision = this.growthPolicy.pendingTimeout();
		WorkerSlot slot = this.slots[pendingWorker];
		if (slot != null) {
			if (slot.ready) {
				this.readyCount--;
			}
			terminateWorker(slot.worker);
			this.slots[pendingWorker] = null;
		}
		if (pendingWorker == this.spawnedCount - 1) {
			this.spawnedCount--;
		}
		this.desiredWorkerCount = this.spawnedCount;
		this.growthTrialWorker = -1;
		this.growthTrialPending = false;
		this.saturationSamples = 0;
		log("[MeshWorkerPool] adaptive pending spawn cancelled: terminated worker " + pendingWorker
				+ " (" + reason + "; " + decision.reason() + "), learned ceiling remains "
				+ this.learnedWorkerCeiling);
		publishCounters();
	}

	private double recentFrameP95() {
		return recentFramePercentile(0.95);
	}

	private double recentFramePercentile(final double percentile) {
		if (this.recentFrameCount <= 0) {
			return 0.0;
		}
		double[] copy = new double[this.recentFrameCount];
		System.arraycopy(this.recentFrameMs, 0, copy, 0, this.recentFrameCount);
		Arrays.sort(copy);
		return copy[Math.min(copy.length - 1, (int)Math.ceil(copy.length * percentile) - 1)];
	}

	private int recentLongFrameCount() {
		int count = 0;
		for (int i = 0; i < this.recentFrameCount; ++i) {
			if (this.recentFrameMs[i] > 50.0) {
				count++;
			}
		}
		return count;
	}

	/** Accept or reject the newly added isolate from observed frame pacing and queue drain. */
	private void evaluateGrowthTrial() {
		if (!this.growthTrialActive || nowMs() - this.growthTrialStartedMs < this.policyProfile.trialMs()) {
			return;
		}
		double p95 = recentFrameP95();
		double p99 = recentFramePercentile(0.99);
		this.frameP95Ms = p95;
		this.frameP99Ms = p99;
		int longFrames = recentLongFrameCount();
		MeshWorkerPolicy.Decision decision = this.growthPolicy.evaluateTrial((long)nowMs(),
				this.growthTrialBaselineP95, this.growthTrialBaselineP99, this.growthTrialBaselineEwma,
				this.growthTrialBaselineLongFrames, p95, p99, this.frameEwmaMs, longFrames);
		if (decision.action() == MeshWorkerPolicy.Decision.Action.HOLD) {
			return;
		}
		this.growthTrialActive = false;
		if (decision.action() == MeshWorkerPolicy.Decision.Action.ROLLBACK) {
			this.rejectedGrowthEvents++;
			this.learnedWorkerCeiling = this.growthPolicy.learnedCeiling();
			this.growthTrialRollbackPending = true;
			log("[MeshWorkerPool] adaptive trial rejected worker " + this.growthTrialWorker
				+ " (p95 " + (long)this.growthTrialBaselineP95 + "->" + (long)p95
				+ "ms, p99 " + (long)this.growthTrialBaselineP99 + "->" + (long)p99
				+ "ms, ewma " + (long)this.growthTrialBaselineEwma + "->" + (long)this.frameEwmaMs
				+ "ms, queue " + this.growthTrialStartQueue + "->" + this.queuedSections
				+ ", reason=" + decision.reason() + ")");
			rollbackGrowthTrial(decision.reason());
		} else {
			log("[MeshWorkerPool] adaptive trial accepted worker " + this.growthTrialWorker
				+ " (p95 " + (long)this.growthTrialBaselineP95 + "->" + (long)p95
				+ "ms, p99 " + (long)this.growthTrialBaselineP99 + "->" + (long)p99
				+ "ms, queue " + this.growthTrialStartQueue + "->" + this.queuedSections + ")");
			this.growthTrialWorker = -1;
		}
		publishCounters();
	}

	private void rollbackGrowthTrial(final String reason) {
		if (!this.growthTrialRollbackPending || this.slots == null || this.growthTrialWorker < 0
				|| this.growthTrialWorker != this.spawnedCount - 1) {
			return;
		}
		WorkerSlot slot = this.slots[this.growthTrialWorker];
		if (slot == null || slot.inFlight > 0) {
			return;
		}
		if (slot.ready) {
			this.readyCount--;
		}
		terminateWorker(slot.worker);
		this.slots[this.growthTrialWorker] = null;
		this.spawnedCount--;
		this.desiredWorkerCount = this.spawnedCount;
		log("[MeshWorkerPool] adaptive rollback: retired worker " + this.growthTrialWorker
			+ " (" + reason + "), learned ceiling " + this.learnedWorkerCeiling);
		this.growthTrialWorker = -1;
		this.growthTrialRollbackPending = false;
		this.saturationSamples = 0;
		this.growthPolicy.rollbackComplete();
	}

	/**
	 * Terminate idle workers from the top down to {@link #IDLE_FLOOR}, contiguously (so
	 * {@code spawnedCount} stays the index of the first free slot for a later regrow). Only slots
	 * with no in-flight job are terminated; if a high slot is still busy the shrink stops there.
	 */
	private void shrinkToFloor() {
		int terminated = 0;
		for (int i = this.spawnedCount - 1; i >= IDLE_FLOOR; --i) {
			WorkerSlot s = this.slots[i];
			if (s == null) {
				this.spawnedCount = i; // already gone
				continue;
			}
			if (s.inFlight > 0) {
				break; // Leave active workers alone.
			}
			if (s.ready) {
				this.readyCount--;
			}
			terminateWorker(s.worker);
			this.slots[i] = null;
			this.spawnedCount = i;
			terminated++;
		}
		if (terminated > 0) {
			this.desiredWorkerCount = this.spawnedCount;
			this.saturationSamples = 0;
			if (this.growthTrialWorker >= this.spawnedCount) {
				// Memory pressure or an idle reclaim can supersede an unfinished capacity trial.
				this.learnedWorkerCeiling = Math.min(this.learnedWorkerCeiling, this.spawnedCount);
				this.growthPolicy.trimAfterShrink(this.spawnedCount);
				this.growthTrialWorker = -1;
				this.growthTrialPending = false;
				this.growthTrialActive = false;
				this.growthTrialRollbackPending = false;
			}
			this.shrinkEvents++;
			log("[MeshWorkerPool] memory-reclaim: terminated " + terminated + " idle worker(s); "
				+ this.spawnedCount + " left (heapPressure=" + (long)(this.heapPressureRatio * 100.0)
				+ "%, floor " + IDLE_FLOOR + ")");
			publishCounters();
		}
	}

	// ============================ JS natives ============================

	@JSBody(params = { "w", "meta", "bin" }, script = "w.onmessage = function(e) {"
			+ " if (e.data && e.data.buf instanceof ArrayBuffer) { bin(e.data.jobId | 0, e.data.buf); }"
			+ " else if (e.data && typeof e.data.meta === \"string\") { meta(e.data.meta); } };")
	private static native void registerDualChannel(Worker w, MetaHandler meta, PoolBinaryHandler bin);

	private static byte[] transferSource(final byte[] bytes) {
		// wasm-gc @JSByRef marshals numeric arrays as a fresh typed-array copy, so cloning
		// here only doubled transient heap use. The JS target transfers its typed backing
		// array directly and must preserve retained model/delta arrays with a clone.
		return net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC()
				? bytes : bytes.clone();
	}

	@JSBody(params = { "w", "buf" }, script = "w.postMessage(buf, [buf]);")
	private static native void postBinaryTransfer(Worker w, ArrayBuffer buf);

	@JSBody(params = { "w" }, script = "try { w.terminate(); } catch(e) {}")
	private static native void terminateWorker(Worker w);

	/** {@code ?meshworkers=N} query param or {@code eaglercraftXOpts.meshWorkerCount}, else -1.
	 *  Note bare {@code ?meshworkers} (the enable flag) has no digits and does not match. */
	@JSBody(params = {}, script = "try {"
			+ " var m = /[?&]meshworkers=([0-9]+)/.exec(location.search); if (m) return parseInt(m[1], 10) | 0;"
			+ " if (typeof eaglercraftXOpts !== \"undefined\" && eaglercraftXOpts && eaglercraftXOpts.meshWorkerCount)"
			+ "   return eaglercraftXOpts.meshWorkerCount | 0;"
			+ " return -1; } catch(e) { return -1; }")
	private static native int meshWorkerCountOverride();

	// @JSFunctor handlers must be lambdas/method refs on TeaVM (not anonymous classes).
	@org.teavm.jso.JSFunctor
	private interface TimeoutCallback extends org.teavm.jso.JSObject {
		void run();
	}

	@JSBody(params = { "cb", "ms" }, script = "if (typeof setTimeout !== \"undefined\") setTimeout(cb, ms);")
	private static native void scheduleTimeout(TimeoutCallback cb, int ms);

	@JSBody(params = { "w", "str" }, script = "w.postMessage({ meta: str });")
	private static native void postMeta(Worker w, String str);

	@JSBody(params = {}, script = "return (typeof performance !== \"undefined\" && performance.now)"
			+ " ? performance.now() : Date.now();")
	private static native double nowMs();

	@JSBody(params = {}, script = "return (typeof Worker !== \"undefined\");")
	private static native boolean workerSupported();

	@JSBody(params = {}, script = "try { return (navigator && navigator.hardwareConcurrency) | 0; } catch(e) { return 0; }")
	private static native int hardwareConcurrencyRaw();

	private static int hardwareConcurrency() {
		int hc = hardwareConcurrencyRaw();
		// Unknown is intentionally kept unknown. The policy treats it as one CPU and a
		// two-worker memory ceiling, rather than manufacturing a desktop-sized default.
		return Math.max(0, hc);
	}

	@JSBody(params = {}, script = "try { return (navigator && navigator.deviceMemory) ? navigator.deviceMemory : 0; } catch(e) { return 0; }")
	private static native double deviceMemoryGb();

	@JSBody(params = {}, script = "try {"
			+ " if (location && /[?&]meshreclaimtest(?:[=&]|$)/.test(location.search)) return 1;"
			+ " var m = performance && performance.memory; if (!m) return 0;"
			+ " var limit = Number(m.jsHeapSizeLimit) || 0; var used = Number(m.usedJSHeapSize) || 0;"
			+ " return limit > 0 ? used / limit : 0; } catch(e) { return 0; }")
	private static native double browserHeapPressureRatio();

	@JSBody(params = {}, script = "try { return /Mac|iPhone|iPad|iPod/.test("
			+ "(navigator.platform || '') + ' ' + (navigator.userAgent || '')); } catch(e) { return false; }")
	private static native boolean isApplePlatform();

	@JSBody(params = { "msg" }, script = "if (typeof console !== \"undefined\") console.log(msg);")
	private static native void log(String msg);

	@JSBody(params = { "tail" }, script = "try {"
			+ " var url = null;"
			+ " if (typeof eaglercraftXClientScriptURL === \"string\") url = eaglercraftXClientScriptURL;"
			+ " if (!url) { var ss = document.getElementsByTagName(\"script\");"
			+ "   for (var i = 0; i < ss.length; ++i) { var sc = ss[i].src || \"\";"
			+ "     if (sc.indexOf(\"classes\") >= 0) { url = sc; break; } } }"
			+ " if (!url) return null;"
			+ " var xhr = new XMLHttpRequest(); xhr.open(\"GET\", url, false); xhr.send(null);"
			+ " if (xhr.status && xhr.status >= 400) return null;"
			+ " var blob = new Blob([xhr.responseText, tail], { type: \"text/javascript;charset=utf8\" });"
			+ " return URL.createObjectURL(blob);"
			+ "} catch(e) { return null; }")
	private static native String resolveWorkerURLFallback(String tail);

	@JSBody(params = { "workers", "ready", "jobs", "completedJobs", "inline", "fluidInline", "workerMs", "verifyChecks",
				"verifyMismatches", "errors", "inFlight", "encodeMs", "bootMsJson", "spawned", "tableEpoch",
				"lastReloadMs", "worstFrameMs", "snapshotMsFrame", "installMsFrame", "queuedSections", "queuedResults",
				"dispatcherInFlight", "deltas", "deltaBytes", "cancelledJobs", "cancelledBytes", "inFlightBytes",
				"heapPressure", "shrinks" }, script = "try {"
			+ " var bm = []; try { bm = JSON.parse(bootMsJson); } catch(e2) {}"
			+ " globalThis.__meshWorkerPool = {"
			+ " workers: workers, spawned: spawned, ready: ready, jobs: jobs, completedJobs: completedJobs, inline: inline, fluidInline: fluidInline,"
			+ " workerMs: workerMs, verifyChecks: verifyChecks, verifyMismatches: verifyMismatches,"
			+ " errors: errors, inFlight: inFlight, encodeMs: encodeMs, bootMs: bm,"
			+ " tableEpoch: tableEpoch, lastReloadMs: lastReloadMs,"
				+ " worstFrameMs: worstFrameMs, snapshotMsFrame: snapshotMsFrame, installMsFrame: installMsFrame,"
				+ " queuedSections: queuedSections, queuedResults: queuedResults, dispatcherInFlight: dispatcherInFlight,"
				+ " deltas: deltas, deltaBytes: deltaBytes, cancelledJobs: cancelledJobs,"
				+ " cancelledBytes: cancelledBytes, inFlightBytes: inFlightBytes,"
				+ " heapPressure: heapPressure, shrinks: shrinks }; } catch(e) {}")
	private static native void publish(int workers, int ready, double jobs, double completedJobs, double inline, double fluidInline,
			double workerMs, double verifyChecks, double verifyMismatches, double errors, int inFlight,
			double encodeMs, String bootMsJson, int spawned, int tableEpoch, double lastReloadMs,
					double worstFrameMs, double snapshotMsFrame, double installMsFrame, int queuedSections,
					int queuedResults, int dispatcherInFlight, double deltas, double deltaBytes,
					double cancelledJobs, double cancelledBytes, int inFlightBytes, double heapPressure,
					double shrinks);

	@JSBody(params = { "liveBytes", "peakBytes", "liveAllocations", "allocations", "frees" }, script = "try {"
			+ " var p = globalThis.__meshWorkerPool;"
			+ " if (!p) return;"
			+ " p.fakeHeapLiveBytes = liveBytes; p.fakeHeapPeakBytes = peakBytes;"
			+ " p.fakeHeapLiveAllocations = liveAllocations;"
			+ " p.fakeHeapAllocations = allocations; p.fakeHeapFrees = frees;"
			+ "} catch(e) {}")
	private static native void publishFakeHeap(double liveBytes, double peakBytes, int liveAllocations,
			double allocations, double frees);

	/** Publishes adaptive core state; each JSBody bridge stays under the Wasm-GC 32-parameter limit. */
	@JSBody(params = { "ceiling", "learnedCeiling", "initial", "desired", "adaptive", "growths", "rejectedGrowths",
			"saturationSamples", "trialWorker", "trialActive", "trialPending", "trialRollbackPending", "frameEwmaMs",
			"frameP95Ms", "frameP99Ms", "modelTableBytes", "displayActive", "pageVisible", "resumed" }, script = "try {"
			+ " var p = globalThis.__meshWorkerPool; if (!p) return;"
			+ " p.workerCeiling = ceiling; p.learnedWorkerCeiling = learnedCeiling;"
			+ " p.initialWorkerTarget = initial; p.desiredWorkers = desired;"
			+ " p.adaptiveWorkers = adaptive; p.growths = growths; p.rejectedGrowths = rejectedGrowths;"
			+ " p.saturationSamples = saturationSamples; p.trialWorker = trialWorker; p.trialActive = trialActive;"
			+ " p.trialPending = trialPending; p.trialRollbackPending = trialRollbackPending;"
			+ " p.frameEwmaMs = frameEwmaMs; p.frameP95Ms = frameP95Ms; p.frameP99Ms = frameP99Ms;"
			+ " p.modelTableBytes = modelTableBytes;"
			+ " p.displayActive = displayActive; p.pageVisible = pageVisible; p.resumed = resumed;"
			+ "} catch(e) {}")
	private static native void publishAdaptiveCoreStats(int ceiling, int learnedCeiling, int initial, int desired,
			boolean adaptive, double growths, double rejectedGrowths, int saturationSamples,
			int trialWorker, boolean trialActive, boolean trialPending, boolean trialRollbackPending,
			double frameEwmaMs, double frameP95Ms, double frameP99Ms, int modelTableBytes,
			boolean displayActive, boolean pageVisible, boolean resumed);

	/** Publishes comparable baseline/trial windows without clearing fields written by other groups. */
	@JSBody(params = { "baselineRate", "trialRate", "baselineQueue", "trialQueue", "baselineWindowMs", "trialWindowMs",
			"baselineCompletedJobs", "trialCompletedJobs", "baselineQueueAreaMs", "trialQueueAreaMs",
			"lastBaselineRate", "lastTrialRate", "lastBaselineWindowMs", "lastTrialWindowMs",
			"lastBaselineCompletedJobs", "lastTrialCompletedJobs", "lastBaselineQueueAreaMs", "lastTrialQueueAreaMs",
			"lastComparisonComparable", "cumulativeSaturationMs", "maxContinuousSaturationMs" }, script = "try {"
			+ " var p = globalThis.__meshWorkerPool; if (!p) return;"
			+ " p.baselineCompletionRate = baselineRate; p.trialCompletionRate = trialRate;"
			+ " p.baselineMeanQueue = baselineQueue; p.trialMeanQueue = trialQueue;"
			+ " p.baselineWindowMs = baselineWindowMs; p.trialWindowMs = trialWindowMs;"
			+ " p.baselineCompletedJobs = baselineCompletedJobs; p.trialCompletedJobs = trialCompletedJobs;"
			+ " p.baselineQueueAreaMs = baselineQueueAreaMs; p.trialQueueAreaMs = trialQueueAreaMs;"
			+ " p.lastBaselineCompletionRate = lastBaselineRate; p.lastTrialCompletionRate = lastTrialRate;"
			+ " p.lastBaselineWindowMs = lastBaselineWindowMs; p.lastTrialWindowMs = lastTrialWindowMs;"
			+ " p.lastBaselineCompletedJobs = lastBaselineCompletedJobs; p.lastTrialCompletedJobs = lastTrialCompletedJobs;"
			+ " p.lastBaselineQueueAreaMs = lastBaselineQueueAreaMs; p.lastTrialQueueAreaMs = lastTrialQueueAreaMs;"
			+ " p.lastComparisonComparable = lastComparisonComparable;"
			+ " p.cumulativeSaturationMs = cumulativeSaturationMs; p.maxContinuousSaturationMs = maxContinuousSaturationMs;"
			+ "} catch(e) {}")
	private static native void publishAdaptiveWindowStats(double baselineRate, double trialRate, double baselineQueue,
			double trialQueue, double baselineWindowMs, double trialWindowMs, double baselineCompletedJobs,
			double trialCompletedJobs, double baselineQueueAreaMs, double trialQueueAreaMs,
			double lastBaselineRate, double lastTrialRate, double lastBaselineWindowMs, double lastTrialWindowMs,
			double lastBaselineCompletedJobs, double lastTrialCompletedJobs, double lastBaselineQueueAreaMs,
			double lastTrialQueueAreaMs, boolean lastComparisonComparable, double cumulativeSaturationMs,
			double maxContinuousSaturationMs);

	/** Publishes non-exclusive blocker counts and latest reason without wiping core/window fields. */
	@JSBody(params = { "blockerInsufficientQueue", "blockerInsufficientInFlight", "blockerResultBacklog",
			"blockerDisplayInactive", "blockerPageHidden", "blockerResumed", "blockerFrameHeadroom",
			"blockerHeapHeadroom", "blockerPendingSpawn", "blockerTrialActive", "lastBlockerMask",
			"lastBlockerReason" }, script = "try {"
			+ " var p = globalThis.__meshWorkerPool; if (!p) return;"
			+ " p.blockerInsufficientQueue = blockerInsufficientQueue;"
			+ " p.blockerInsufficientInFlight = blockerInsufficientInFlight;"
			+ " p.blockerResultBacklog = blockerResultBacklog; p.blockerDisplayInactive = blockerDisplayInactive;"
			+ " p.blockerPageHidden = blockerPageHidden; p.blockerResumed = blockerResumed;"
			+ " p.blockerFrameHeadroom = blockerFrameHeadroom; p.blockerHeapHeadroom = blockerHeapHeadroom;"
			+ " p.blockerPendingSpawn = blockerPendingSpawn; p.blockerTrialActive = blockerTrialActive;"
			+ " p.lastBlockerMask = lastBlockerMask; p.lastBlockerReason = lastBlockerReason;"
			+ "} catch(e) {}")
	private static native void publishAdaptiveBlockerStats(double blockerInsufficientQueue,
			double blockerInsufficientInFlight, double blockerResultBacklog, double blockerDisplayInactive,
			double blockerPageHidden, double blockerResumed, double blockerFrameHeadroom,
			double blockerHeapHeadroom, double blockerPendingSpawn, double blockerTrialActive,
			int lastBlockerMask, String lastBlockerReason);

	@JSBody(params = { "created", "reused", "pooled", "dropped", "cleared", "reusedBytes" }, script = "try {"
			+ " var p = globalThis.__meshWorkerPool; if (!p) return;"
			+ " p.snapshotScratchCreated = created; p.snapshotScratchReused = reused;"
			+ " p.snapshotScratchPooled = pooled; p.snapshotScratchDropped = dropped;"
			+ " p.snapshotScratchCleared = cleared; p.snapshotScratchReusedBytes = reusedBytes;"
			+ "} catch(e) {}")
	private static native void publishSnapshotScratch(double created, double reused, int pooled,
			double dropped, double cleared, double reusedBytes);

	private void publishCounters() {
			int inFlight = 0;
			int inFlightBytes = 0;
		StringBuilder bootMs = new StringBuilder("[");
		if (this.slots != null) {
			// Emit a full poolSize-length array (staggered boot leaves later slots null until
			// spawned); -1 for a not-yet-spawned OR not-yet-ready slot preserves prior semantics.
			for (int i = 0; i < this.slots.length; ++i) {
				WorkerSlot s = this.slots[i];
				if (i > 0) {
					bootMs.append(',');
				}
				if (s != null) {
						inFlight += s.inFlight;
						inFlightBytes += s.inFlightBytes;
					bootMs.append((long) s.bootMs); // -1 until table-ready (parse-to-ready wall ms)
				} else {
					bootMs.append(-1L); // not spawned yet / idle-shrunk
				}
			}
		}
		bootMs.append(']');
		publish(this.poolSize, this.readyCount, this.jobsRouted, this.completedJobs, this.inlineFallbacks, this.fluidInline,
			this.workerMsTotal, this.verifyChecks, this.verifyMismatches, this.errors, inFlight,
				this.tableEncodeMs, bootMs.toString(), this.spawnedCount, this.tableEpoch, this.lastReloadMs,
				this.worstFrameMs, this.snapshotMsFrame, this.installMsFrame, this.queuedSections,
						this.queuedResults, this.dispatcherInFlight, this.deltasBroadcast,
						this.deltaBytesTotal, this.cancelledJobs, this.cancelledBytes, inFlightBytes,
						this.heapPressureRatio, this.shrinkEvents);
			publishFakeHeap(EaglerFakeHeap.getLiveBytes(), EaglerFakeHeap.getPeakBytes(),
					EaglerFakeHeap.getLiveAllocations(), EaglerFakeHeap.getAllocationCount(),
					EaglerFakeHeap.getFreeCount());
			publishAdaptiveCoreStats(this.poolSize, this.learnedWorkerCeiling, this.initialWorkerTarget,
					this.desiredWorkerCount, !this.explicitWorkerCount, this.growthEvents,
					this.rejectedGrowthEvents, this.saturationSamples, this.growthTrialWorker,
					this.growthTrialPending || this.growthTrialActive || this.growthTrialRollbackPending,
					this.growthTrialPending, this.growthTrialRollbackPending,
					this.frameEwmaMs, this.frameP95Ms, this.frameP99Ms,
					this.modelTable != null ? this.modelTable.length : 0, this.displayActive, this.pageVisible,
					this.displayResumed);
			publishAdaptiveWindowStats(this.growthPolicy.baselineCompletionRate(),
					this.growthPolicy.trialCompletionRate(), this.growthPolicy.baselineMeanQueue(),
					this.growthPolicy.trialMeanQueue(), this.growthPolicy.baselineWindowMs(),
					this.growthPolicy.trialWindowMs(), this.growthPolicy.baselineCompletedJobs(),
					this.growthPolicy.trialCompletedJobs(), this.growthPolicy.baselineQueueAreaMs(),
					this.growthPolicy.trialQueueAreaMs(), this.growthPolicy.lastBaselineCompletionRate(),
					this.growthPolicy.lastTrialCompletionRate(), this.growthPolicy.lastBaselineWindowMs(),
					this.growthPolicy.lastTrialWindowMs(), this.growthPolicy.lastBaselineCompletedJobs(),
					this.growthPolicy.lastTrialCompletedJobs(), this.growthPolicy.lastBaselineQueueAreaMs(),
					this.growthPolicy.lastTrialQueueAreaMs(), this.growthPolicy.lastComparisonComparable(),
					this.growthPolicy.cumulativeSaturationMs(), this.growthPolicy.maximumContinuousSaturationMs());
			publishAdaptiveBlockerStats(
					this.growthPolicy.blockerInsufficientQueue(), this.growthPolicy.blockerInsufficientInFlight(),
					this.growthPolicy.blockerResultBacklog(), this.growthPolicy.blockerDisplayInactive(),
					this.growthPolicy.blockerPageHidden(), this.growthPolicy.blockerResumed(),
					this.growthPolicy.blockerFrameHeadroom(), this.growthPolicy.blockerHeapHeadroom(),
					this.growthPolicy.blockerPendingSpawn(), this.growthPolicy.blockerTrialActive(),
					this.growthPolicy.lastBlockerMask(), this.growthPolicy.lastBlockerReason());
			SectionSnapshotBuilder.fillScratchStats(this.snapshotScratchStats);
			publishSnapshotScratch(this.snapshotScratchStats[0], this.snapshotScratchStats[1],
					(int) this.snapshotScratchStats[2], this.snapshotScratchStats[3],
					this.snapshotScratchStats[4], this.snapshotScratchStats[5]);
	}
}

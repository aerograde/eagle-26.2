/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 */

package net.lax1dude.eaglercraft.v1_8.mesh;

/**
 * Pure-Java mesh-worker selection and growth policy.
 *
 * <p>Hardware hints select only a safety ceiling. Automatic mode always starts at one worker;
 * an additional Wasm-GC isolate is earned by a continuous, visible, active workload and a
 * comparable measured trial. This class intentionally has no user-agent or browser dependency,
 * which keeps the state machine deterministic and testable.</p>
 */
public final class MeshWorkerPolicy {

	public static final int MAX_WORKERS = 4;
	private static final int MIN_WORKERS = 1;
	private static final long MIN_SUSTAINED_SATURATION_MS = 5_000L;
	private static final long MAX_SATURATION_GAP_MS = 750L;
	private static final int MIN_COMPARABLE_SAMPLES = 6;
	private static final double RATE_BENEFIT_RATIO = 1.05;
	private static final double QUEUE_BENEFIT_RATIO = 0.80;

	/** Diagnostic bits describing why a frame cannot be used as clean scale-up evidence. */
	public static final int BLOCKER_INSUFFICIENT_QUEUE = 1 << 0;
	public static final int BLOCKER_INSUFFICIENT_IN_FLIGHT = 1 << 1;
	public static final int BLOCKER_RESULT_BACKLOG = 1 << 2;
	public static final int BLOCKER_DISPLAY_INACTIVE = 1 << 3;
	public static final int BLOCKER_PAGE_HIDDEN = 1 << 4;
	public static final int BLOCKER_RESUMED = 1 << 5;
	public static final int BLOCKER_FRAME_HEADROOM = 1 << 6;
	public static final int BLOCKER_HEAP_HEADROOM = 1 << 7;
	public static final int BLOCKER_PENDING_SPAWN = 1 << 8;
	public static final int BLOCKER_TRIAL_ACTIVE = 1 << 9;

	private MeshWorkerPolicy() {
	}

	public enum Tier {
		PROTECTED,
		STANDARD,
		CAPABLE
	}

	/** Immutable hardware ceilings and controller thresholds for one browser session. */
	public static final class Profile {
		private final int hardwareConcurrency;
		private final double deviceMemoryGb;
		private final boolean memoryKnown;
		private final int cpuCeiling;
		private final int memoryCeiling;
		private final int ceiling;
		private final int initialWorkers;
		private final Tier tier;
		private final int growthCooldownMs;
		private final int trialMs;
		private final int maxSpawnCostMs;
		private final double frameHeadroomMs;
		private final double heapPressureLimit;

		private Profile(int hardwareConcurrency, double deviceMemoryGb, int cpuCeiling, int memoryCeiling,
				int ceiling, int initialWorkers, Tier tier, int growthCooldownMs, int trialMs,
				int maxSpawnCostMs, double frameHeadroomMs, double heapPressureLimit) {
			this.hardwareConcurrency = hardwareConcurrency;
			this.deviceMemoryGb = deviceMemoryGb;
			this.memoryKnown = deviceMemoryGb > 0.0;
			this.cpuCeiling = cpuCeiling;
			this.memoryCeiling = memoryCeiling;
			this.ceiling = ceiling;
			this.initialWorkers = initialWorkers;
			this.tier = tier;
			this.growthCooldownMs = growthCooldownMs;
			this.trialMs = trialMs;
			this.maxSpawnCostMs = maxSpawnCostMs;
			this.frameHeadroomMs = frameHeadroomMs;
			this.heapPressureLimit = heapPressureLimit;
		}

		public int hardwareConcurrency() { return this.hardwareConcurrency; }
		public double deviceMemoryGb() { return this.deviceMemoryGb; }
		public boolean memoryKnown() { return this.memoryKnown; }
		public int cpuCeiling() { return this.cpuCeiling; }
		public int memoryCeiling() { return this.memoryCeiling; }
		public int ceiling() { return this.ceiling; }
		public int initialWorkers() { return this.initialWorkers; }
		public Tier tier() { return this.tier; }
		public int growthCooldownMs() { return this.growthCooldownMs; }
		public int trialMs() { return this.trialMs; }
		public int maxSpawnCostMs() { return this.maxSpawnCostMs; }
		public double frameHeadroomMs() { return this.frameHeadroomMs; }
		public double heapPressureLimit() { return this.heapPressureLimit; }
		public long minSustainedSaturationMs() { return MIN_SUSTAINED_SATURATION_MS; }
		public long maxSaturationGapMs() { return MAX_SATURATION_GAP_MS; }
		public int minComparableSamples() { return MIN_COMPARABLE_SAMPLES; }
	}

	public static final class Decision {
		public enum Action { HOLD, GROW, ACCEPT, ROLLBACK }

		private final Action action;
		private final int worker;
		private final String reason;
		private final boolean lowersLearnedCeiling;

		private Decision(Action action, int worker, String reason, boolean lowersLearnedCeiling) {
			this.action = action;
			this.worker = worker;
			this.reason = reason;
			this.lowersLearnedCeiling = lowersLearnedCeiling;
		}

		public Action action() { return this.action; }
		public int worker() { return this.worker; }
		public String reason() { return this.reason; }
		public boolean lowersLearnedCeiling() { return this.lowersLearnedCeiling; }
	}

	private static final class Metrics {
		boolean started;
		boolean invalid;
		long firstMs;
		long lastMs;
		double queueArea;
		int samples;
		long firstCompleted;
		long lastCompleted;

		void reset() {
			this.started = false;
			this.invalid = false;
			this.firstMs = 0L;
			this.lastMs = 0L;
			this.queueArea = 0.0;
			this.samples = 0;
			this.firstCompleted = 0L;
			this.lastCompleted = 0L;
		}

		void copyFrom(Metrics other) {
			this.started = other.started;
			this.invalid = other.invalid;
			this.firstMs = other.firstMs;
			this.lastMs = other.lastMs;
			this.queueArea = other.queueArea;
			this.samples = other.samples;
			this.firstCompleted = other.firstCompleted;
			this.lastCompleted = other.lastCompleted;
		}

		void add(long nowMs, int queue, long completed) {
			if (!this.started) {
				this.started = true;
				this.firstMs = nowMs;
				this.lastMs = nowMs;
				this.firstCompleted = completed;
				this.lastCompleted = completed;
				this.samples = 1;
				return;
			}
			long gap = nowMs - this.lastMs;
			if (gap < 0L || gap > MAX_SATURATION_GAP_MS) {
				this.invalid = true;
				return;
			}
			this.queueArea += (double) queue * (double) gap;
			this.lastMs = nowMs;
			this.lastCompleted = completed;
			this.samples++;
		}

		long durationMs() { return this.started ? this.lastMs - this.firstMs : 0L; }
		long completedDelta() { return Math.max(0L, this.lastCompleted - this.firstCompleted); }
		double meanQueue() {
			long duration = durationMs();
			return duration > 0L ? this.queueArea / (double) duration : 0.0;
		}
	}

	/**
	 * Stateful, browser-independent growth controller. Queue area and completed-job counts are
	 * accumulated over separate baseline and trial windows; a raw counter delta is never treated as
	 * throughput without its elapsed time and a comparable baseline sample count.
	 */
	public static final class GrowthController {
		private final Profile profile;
		private int learnedCeiling;
		private int activeWorkers;
		private long saturationStartMs = Long.MIN_VALUE;
		private long lastSaturationMs = Long.MIN_VALUE;
		private long lastGrowthMs = Long.MIN_VALUE;
		private int trialWorker = -1;
		private long trialStartedMs;
		private double trialSpawnCostMs;
		private boolean trialDemandLost;
		private TrialState trialState = TrialState.IDLE;
		private final Metrics baseline = new Metrics();
		private final Metrics trial = new Metrics();
		/** Frozen at GROW so published baseline fields exclude pending spawn time. */
		private final Metrics comparisonBaseline = new Metrics();
		/** Last finished comparison is retained after the active windows are reset. */
		private final Metrics lastBaseline = new Metrics();
		private final Metrics lastTrial = new Metrics();
		private boolean lastComparisonComparable;

		/** Saturation evidence spans all demand windows for this world/session. Each elapsed gap
		 * between eligible samples contributes once to cumulativeSaturationMs; maxContinuous records
		 * the largest contiguous segment and never bridges a gap, hidden frame, or lifecycle reset. */
		private long cumulativeSaturationMs;
		private long maximumContinuousSaturationMs;

		/** Per-observation blocker counters; counters are reset only at world lifecycle reset. */
		private long blockerInsufficientQueue;
		private long blockerInsufficientInFlight;
		private long blockerResultBacklog;
		private long blockerDisplayInactive;
		private long blockerPageHidden;
		private long blockerResumed;
		private long blockerFrameHeadroom;
		private long blockerHeapHeadroom;
		private long blockerPendingSpawn;
		private long blockerTrialActive;
		private int lastBlockerMask;

		private enum TrialState { IDLE, PENDING, ACTIVE, ROLLBACK_PENDING }

		public GrowthController(Profile profile) {
			this.profile = profile;
			this.learnedCeiling = profile.ceiling();
			this.activeWorkers = profile.initialWorkers();
			this.baseline.reset();
			this.trial.reset();
			this.comparisonBaseline.reset();
			this.lastBaseline.reset();
			this.lastTrial.reset();
		}

		public int learnedCeiling() { return this.learnedCeiling; }
		public int activeWorkers() { return this.activeWorkers; }
		public int saturationSamples() { return this.baseline.samples; }
		public int trialWorker() { return this.trialWorker; }
		public boolean trialPending() { return this.trialState == TrialState.PENDING; }
		public boolean trialActive() { return this.trialState == TrialState.ACTIVE; }
		public boolean rollbackPending() { return this.trialState == TrialState.ROLLBACK_PENDING; }
		/**
		 * During PENDING/ACTIVE, baseline getters use the snapshot taken at GROW, so spawn time is
		 * excluded from the published comparison. Completed-job values are monotonic deltas between
		 * the first and last sample; queue-area values are queue-size milliseconds over that window.
		 * last* getters retain the finished comparison after the transient windows are cleared.
		 */
		private Metrics comparableBaseline() {
			return this.trialState == TrialState.IDLE ? this.baseline : this.comparisonBaseline;
		}
		public double baselineMeanQueue() { return comparableBaseline().meanQueue(); }
		public double trialMeanQueue() { return this.trial.meanQueue(); }
		public double baselineCompletionRate() { return completionRate(comparableBaseline()); }
		public double trialCompletionRate() { return completionRate(this.trial); }
		public long baselineWindowMs() { return comparableBaseline().durationMs(); }
		public long trialWindowMs() { return this.trial.durationMs(); }
		public long baselineCompletedJobs() { return comparableBaseline().completedDelta(); }
		public long trialCompletedJobs() { return this.trial.completedDelta(); }
		public double baselineQueueAreaMs() { return comparableBaseline().queueArea; }
		public double trialQueueAreaMs() { return this.trial.queueArea; }
		public long lastBaselineWindowMs() { return this.lastBaseline.durationMs(); }
		public long lastTrialWindowMs() { return this.lastTrial.durationMs(); }
		public long lastBaselineCompletedJobs() { return this.lastBaseline.completedDelta(); }
		public long lastTrialCompletedJobs() { return this.lastTrial.completedDelta(); }
		public double lastBaselineQueueAreaMs() { return this.lastBaseline.queueArea; }
		public double lastTrialQueueAreaMs() { return this.lastTrial.queueArea; }
		public double lastBaselineCompletionRate() { return completionRate(this.lastBaseline); }
		public double lastTrialCompletionRate() { return completionRate(this.lastTrial); }
		public boolean lastComparisonComparable() { return this.lastComparisonComparable; }
		public long cumulativeSaturationMs() { return this.cumulativeSaturationMs; }
		public long maximumContinuousSaturationMs() { return this.maximumContinuousSaturationMs; }
		public long blockerInsufficientQueue() { return this.blockerInsufficientQueue; }
		public long blockerInsufficientInFlight() { return this.blockerInsufficientInFlight; }
		public long blockerResultBacklog() { return this.blockerResultBacklog; }
		public long blockerDisplayInactive() { return this.blockerDisplayInactive; }
		public long blockerPageHidden() { return this.blockerPageHidden; }
		public long blockerResumed() { return this.blockerResumed; }
		public long blockerFrameHeadroom() { return this.blockerFrameHeadroom; }
		public long blockerHeapHeadroom() { return this.blockerHeapHeadroom; }
		public long blockerPendingSpawn() { return this.blockerPendingSpawn; }
		public long blockerTrialActive() { return this.blockerTrialActive; }
		public int lastBlockerMask() { return this.lastBlockerMask; }
		public String lastBlockerReason() { return blockerReason(this.lastBlockerMask); }

		/** Record one frame; hidden/inactive/resume or an unsaturated gap resets demand evidence. */
		public Decision observeDemand(long nowMs, int currentWorkers, boolean saturated, boolean displayActive,
				boolean pageVisible, boolean resumed, double frameEwmaMs, double heapPressureRatio,
				int queueSize, long completedJobs) {
			return observeDemand(nowMs, currentWorkers, saturated, displayActive, pageVisible, resumed,
				frameEwmaMs, heapPressureRatio, queueSize, completedJobs, saturated, saturated, saturated);
		}

		/**
		 * Record one frame and its saturation components. The three component booleans are
		 * diagnostic-only; {@code saturated} remains the exact pre-existing growth predicate.
		 * Counters count observations (not milliseconds), are intentionally non-exclusive, and
		 * survive demand-window resets so a gate can explain why a comparable window did not form.
		 */
		public Decision observeDemand(long nowMs, int currentWorkers, boolean saturated, boolean displayActive,
				boolean pageVisible, boolean resumed, double frameEwmaMs, double heapPressureRatio,
				int queueSize, long completedJobs, boolean queueSufficient, boolean inFlightSufficient,
				boolean resultBacklogClear) {
			recordBlockers(saturated, displayActive, pageVisible, resumed, frameEwmaMs, heapPressureRatio,
				queueSufficient, inFlightSufficient, resultBacklogClear);
			boolean eligible = saturated && displayActive && pageVisible && !resumed;
			if (!eligible) {
				if (this.trialState == TrialState.ACTIVE) this.trialDemandLost = true;
				resetDemandWindow();
				return hold();
			}
			if (this.lastSaturationMs != Long.MIN_VALUE
					&& nowMs - this.lastSaturationMs > MAX_SATURATION_GAP_MS) {
				if (this.trialState == TrialState.ACTIVE) this.trialDemandLost = true;
				resetDemandWindow();
			}
			noteSaturation(nowMs);
			if (this.saturationStartMs == Long.MIN_VALUE) {
				this.saturationStartMs = nowMs;
				this.baseline.reset();
			}
			if (this.trialState == TrialState.ACTIVE) {
				this.trial.add(nowMs, queueSize, completedJobs);
				return hold();
			}
			this.baseline.add(nowMs, queueSize, completedJobs);
			if (currentWorkers >= this.learnedCeiling
					|| currentWorkers >= this.profile.ceiling()
					|| nowMs - this.saturationStartMs < MIN_SUSTAINED_SATURATION_MS
					|| this.baseline.samples < MIN_COMPARABLE_SAMPLES) return hold();
			if (this.lastGrowthMs != Long.MIN_VALUE
					&& nowMs - this.lastGrowthMs < this.profile.growthCooldownMs()) {
				resetDemandWindow();
				return hold();
			}
			boolean frameHeadroom = hasFrameHeadroom(frameEwmaMs);
			boolean heapHeadroom = hasHeapHeadroom(heapPressureRatio);
			if (!frameHeadroom || !heapHeadroom) {
				resetDemandWindow();
				return hold();
			}
			this.trialWorker = currentWorkers;
			this.comparisonBaseline.copyFrom(this.baseline);
			this.activeWorkers = Math.min(this.profile.ceiling(), currentWorkers + 1);
			this.lastGrowthMs = nowMs;
			this.trialState = TrialState.PENDING;
			return new Decision(Decision.Action.GROW, this.trialWorker,
					"five-second visible saturation with frame/heap headroom", false);
		}

		/** Starts a trial after the new worker has hydrated its table. */
		public void trialReady(long nowMs, int queueSize, long completedJobs, double spawnCostMs) {
			if (this.trialState != TrialState.PENDING) return;
			this.trialStartedMs = nowMs;
			this.trialSpawnCostMs = spawnCostMs;
			this.trialDemandLost = false;
			this.trial.reset();
			this.trial.add(nowMs, queueSize, completedJobs);
			this.trialState = TrialState.ACTIVE;
		}

		/** Accept only comparable rate/queue benefit; hard frame guards always reject. */
		public Decision evaluateTrial(long nowMs, double baselineP95, double baselineP99,
				double baselineEwma, int baselineLongFrames, double p95, double p99, double ewma,
				int longFrames) {
			if (this.trialState != TrialState.ACTIVE || nowMs - this.trialStartedMs < this.profile.trialMs()) return hold();
			// A lost/invalid demand window cannot attribute either a benefit or a regression to the
			// extra isolate. Keep the learned ceiling unchanged and let the next visible workload earn
			// a fresh comparison, even if stale pacing values happen to look alarming.
			// comparisonBaseline is frozen at GROW; mutable baseline may receive pending-spawn frames
			// or be reset by the cooldown guard before the worker becomes ready.
			if (this.trialDemandLost || this.comparisonBaseline.invalid || this.trial.invalid
					|| this.comparisonBaseline.samples < MIN_COMPARABLE_SAMPLES
					|| this.trial.samples < MIN_COMPARABLE_SAMPLES
					|| this.comparisonBaseline.durationMs() < MIN_SUSTAINED_SATURATION_MS
					|| this.trial.durationMs() < MIN_SUSTAINED_SATURATION_MS
					|| this.trialSpawnCostMs > this.profile.maxSpawnCostMs()) {
				captureLastComparison(false);
				return rollback(false, "inconclusive demand/window/spawn cost");
			}
			boolean hardRegression = (baselineP95 > 0.0
					&& p95 > Math.max(baselineP95 + 3.0, baselineP95 * 1.10))
					|| (baselineP99 > 0.0
					&& p99 > Math.max(baselineP99 + 8.0, baselineP99 * 1.15))
					|| longFrames > baselineLongFrames + 1
					|| (baselineEwma > 0.0 && ewma > 1000.0 / 30.0 && ewma > baselineEwma * 1.20);
			if (hardRegression) {
				captureLastComparison(true);
				return rollback(true, "hard p95/p99/long-frame guard");
			}
			double baseRate = completionRate(this.comparisonBaseline);
			double trialRate = completionRate(this.trial);
			double baseQueue = this.comparisonBaseline.meanQueue();
			double trialQueue = this.trial.meanQueue();
		boolean rateComparable = baseRate > 0.0 && trialRate >= baseRate * RATE_BENEFIT_RATIO;
		boolean queueComparable = baseQueue > 0.0 && trialQueue <= baseQueue * QUEUE_BENEFIT_RATIO;
		if (!rateComparable && !queueComparable) {
			captureLastComparison(true);
			return rollback(true, "no measured rate/queue-area benefit");
		}
		captureLastComparison(true);
		this.trialWorker = -1;
		this.trialState = TrialState.IDLE;
		resetDemandWindow();
		return new Decision(Decision.Action.ACCEPT, -1, "comparable rate/queue-area benefit", false);
		}

		/** Pending boot timeout preserves the learned ceiling. */
		public Decision pendingTimeout() {
			if (this.trialState != TrialState.PENDING) return hold();
			int worker = this.trialWorker;
			this.trialWorker = -1;
			this.activeWorkers = Math.max(MIN_WORKERS, worker);
			this.trialState = TrialState.IDLE;
			resetDemandWindow();
			return new Decision(Decision.Action.ROLLBACK, worker, "pending spawn timeout", false);
		}

		public void rollbackComplete() {
			if (this.trialState == TrialState.ROLLBACK_PENDING) {
				this.trialWorker = -1;
				this.trialState = TrialState.IDLE;
				resetDemandWindow();
			}
		}

		public void trimAfterShrink(int currentWorkers) {
			if (this.trialWorker >= currentWorkers) {
				this.learnedCeiling = Math.min(this.learnedCeiling, Math.max(MIN_WORKERS, currentWorkers));
				this.activeWorkers = Math.min(this.activeWorkers, this.learnedCeiling);
				this.trialWorker = -1;
				this.trialState = TrialState.IDLE;
				resetDemandWindow();
			}
		}

		/** World release clears transient windows/trials while retaining learned safe capacity. */
		public void resetForWorld() {
			this.activeWorkers = Math.min(MIN_WORKERS, this.learnedCeiling);
			this.trialWorker = -1;
			this.trialState = TrialState.IDLE;
			this.lastGrowthMs = Long.MIN_VALUE;
			this.baseline.reset();
			this.trial.reset();
			this.comparisonBaseline.reset();
			this.lastBaseline.reset();
			this.lastTrial.reset();
			this.lastComparisonComparable = false;
			this.cumulativeSaturationMs = 0L;
			this.maximumContinuousSaturationMs = 0L;
			this.blockerInsufficientQueue = 0L;
			this.blockerInsufficientInFlight = 0L;
			this.blockerResultBacklog = 0L;
			this.blockerDisplayInactive = 0L;
			this.blockerPageHidden = 0L;
			this.blockerResumed = 0L;
			this.blockerFrameHeadroom = 0L;
			this.blockerHeapHeadroom = 0L;
			this.blockerPendingSpawn = 0L;
			this.blockerTrialActive = 0L;
			this.lastBlockerMask = 0;
			resetDemandWindow();
		}

		private Decision rollback(boolean lowerCeiling, String reason) {
			if (lowerCeiling) this.learnedCeiling = Math.max(MIN_WORKERS, this.trialWorker);
			this.activeWorkers = Math.max(MIN_WORKERS, this.trialWorker);
			this.trialState = TrialState.ROLLBACK_PENDING;
			return new Decision(Decision.Action.ROLLBACK, this.trialWorker, reason, lowerCeiling);
		}

		private void resetDemandWindow() {
			closeSaturationSegment();
			this.saturationStartMs = Long.MIN_VALUE;
			this.lastSaturationMs = Long.MIN_VALUE;
			this.baseline.reset();
		}

		private void noteSaturation(long nowMs) {
			if (this.saturationStartMs == Long.MIN_VALUE || this.lastSaturationMs == Long.MIN_VALUE) {
				this.saturationStartMs = nowMs;
				this.lastSaturationMs = nowMs;
				return;
			}
			long gap = nowMs - this.lastSaturationMs;
			if (gap < 0L || gap > MAX_SATURATION_GAP_MS) {
				closeSaturationSegment();
				this.saturationStartMs = nowMs;
				this.lastSaturationMs = nowMs;
				return;
			}
			this.cumulativeSaturationMs += gap;
			this.lastSaturationMs = nowMs;
			closeSaturationSegment();
		}

		private void closeSaturationSegment() {
			if (this.saturationStartMs != Long.MIN_VALUE && this.lastSaturationMs != Long.MIN_VALUE) {
				long duration = Math.max(0L, this.lastSaturationMs - this.saturationStartMs);
				if (duration > this.maximumContinuousSaturationMs) {
					this.maximumContinuousSaturationMs = duration;
				}
			}
		}

		private void recordBlockers(boolean saturated, boolean displayActive, boolean pageVisible,
				boolean resumed, double frameEwmaMs, double heapPressureRatio, boolean queueSufficient,
				boolean inFlightSufficient, boolean resultBacklogClear) {
			int mask = 0;
			if (!queueSufficient) {
				mask |= BLOCKER_INSUFFICIENT_QUEUE;
				this.blockerInsufficientQueue++;
			}
			if (!inFlightSufficient) {
				mask |= BLOCKER_INSUFFICIENT_IN_FLIGHT;
				this.blockerInsufficientInFlight++;
			}
			if (!resultBacklogClear) {
				mask |= BLOCKER_RESULT_BACKLOG;
				this.blockerResultBacklog++;
			}
			if (!displayActive) {
				mask |= BLOCKER_DISPLAY_INACTIVE;
				this.blockerDisplayInactive++;
			}
			if (!pageVisible) {
				mask |= BLOCKER_PAGE_HIDDEN;
				this.blockerPageHidden++;
			}
			if (resumed) {
				mask |= BLOCKER_RESUMED;
				this.blockerResumed++;
			}
			if (saturated && !hasFrameHeadroom(frameEwmaMs)) {
				mask |= BLOCKER_FRAME_HEADROOM;
				this.blockerFrameHeadroom++;
			}
			if (saturated && !hasHeapHeadroom(heapPressureRatio)) {
				mask |= BLOCKER_HEAP_HEADROOM;
				this.blockerHeapHeadroom++;
			}
			if (this.trialState == TrialState.PENDING) {
				mask |= BLOCKER_PENDING_SPAWN;
				this.blockerPendingSpawn++;
			} else if (this.trialState == TrialState.ACTIVE) {
				mask |= BLOCKER_TRIAL_ACTIVE;
				this.blockerTrialActive++;
			}
			this.lastBlockerMask = mask;
		}

		private void captureLastComparison(boolean comparable) {
			this.lastBaseline.copyFrom(this.comparisonBaseline);
			this.lastTrial.copyFrom(this.trial);
			this.lastComparisonComparable = comparable;
		}

		private static boolean hasFrameHeadroom(double frameEwmaMs, Profile profile) {
			return frameEwmaMs <= 0.0 || frameEwmaMs <= profile.frameHeadroomMs();
		}

		private boolean hasFrameHeadroom(double frameEwmaMs) {
			return hasFrameHeadroom(frameEwmaMs, this.profile);
		}

		private boolean hasHeapHeadroom(double heapPressureRatio) {
			return heapPressureRatio <= 0.0 || heapPressureRatio < this.profile.heapPressureLimit();
		}

		private static String blockerReason(int mask) {
			if ((mask & BLOCKER_INSUFFICIENT_QUEUE) != 0) return "insufficient-queue";
			if ((mask & BLOCKER_INSUFFICIENT_IN_FLIGHT) != 0) return "insufficient-in-flight";
			if ((mask & BLOCKER_RESULT_BACKLOG) != 0) return "result-backlog";
			if ((mask & BLOCKER_DISPLAY_INACTIVE) != 0) return "display-inactive";
			if ((mask & BLOCKER_PAGE_HIDDEN) != 0) return "page-hidden";
			if ((mask & BLOCKER_RESUMED) != 0) return "resumed";
			if ((mask & BLOCKER_FRAME_HEADROOM) != 0) return "frame-headroom";
			if ((mask & BLOCKER_HEAP_HEADROOM) != 0) return "heap-headroom";
			if ((mask & BLOCKER_PENDING_SPAWN) != 0) return "pending-spawn";
			if ((mask & BLOCKER_TRIAL_ACTIVE) != 0) return "trial-active";
			return "none";
		}

		private static double completionRate(Metrics metrics) {
			long duration = metrics.durationMs();
			return duration > 0L ? metrics.completedDelta() * 1000.0 / duration : 0.0;
		}
		private static Decision hold() { return new Decision(Decision.Action.HOLD, -1, "hold", false); }
	}

	/** Select normal hardware safety bounds, or an exact 1..4 diagnostic override. */
	public static Profile select(int hardwareConcurrency, double deviceMemoryGb, int override) {
		int cores = Math.max(0, hardwareConcurrency);
		double memory = deviceMemoryGb > 0.0 ? deviceMemoryGb : 0.0;
		boolean explicit = override >= MIN_WORKERS;
		int cpuCeiling = clamp(cores - 2, MIN_WORKERS, MAX_WORKERS);
		int memoryCeiling = memory <= 4.0 ? 2 : MAX_WORKERS;
		int ceiling = explicit ? clamp(override, MIN_WORKERS, MAX_WORKERS) : Math.min(cpuCeiling, memoryCeiling);
		Tier tier = ceiling <= 1 || (memory > 0.0 && memory <= 2.0) ? Tier.PROTECTED
				: cores >= 8 && memory > 4.0 ? Tier.CAPABLE : Tier.STANDARD;
		int cooldown = tier == Tier.CAPABLE ? 4_000 : tier == Tier.PROTECTED ? 12_000 : 8_000;
		double frame = tier == Tier.CAPABLE ? 30.0 : tier == Tier.PROTECTED ? 24.0 : 28.0;
		double heap = tier == Tier.CAPABLE ? 0.45 : tier == Tier.PROTECTED ? 0.34 : 0.42;
		int initial = explicit ? ceiling : 1;
		return new Profile(cores, memory, cpuCeiling, memoryCeiling, ceiling, initial, tier, cooldown,
				6_000, 10_000, frame, heap);
	}

	/** Legacy eager formula from the old worker teardown. */
	public static int legacyEagerWorkers(int hardwareConcurrency, double deviceMemoryGb) {
		int cpu = clamp(Math.max(0, hardwareConcurrency) - 2, MIN_WORKERS, MAX_WORKERS);
		return deviceMemoryGb > 0.0 && deviceMemoryGb <= 4.0 ? Math.min(cpu, 2) : cpu;
	}

	public static int currentStartOneWorkers() { return 1; }

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}

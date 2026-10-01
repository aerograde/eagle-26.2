package net.lax1dude.eaglercraft.v1_8.sp.server;

import com.mojang.logging.LogUtils;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import org.slf4j.Logger;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/** Low-overhead sampled telemetry for the hosted server's cooperative scheduler. */
public final class EaglerServerPerf {

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final long REPORT_INTERVAL_MILLIS = 5000L;
	private static volatile boolean enabled;

	/*
	 * Live state is deliberately separate from the five-second aggregate below.  The
	 * aggregate is emitted by the server tick itself, so it becomes useless precisely
	 * when a tick is stuck in a future/join.  The supervisor can call heartbeat() while
	 * the server coroutine is blocked and capture the last completed tick plus the
	 * phase that was in progress.  All of this remains behind the explicit perfdebug
	 * switch and does not alter scheduling.
	 */
	private static volatile long liveTickStartedAtMillis;
	private static volatile long liveLastTickCompletedAtMillis;
	private static volatile int liveTickNumber;
	private static volatile int liveLastCompletedTick;
	private static volatile boolean liveHasProgressed;
	private static volatile String liveTickPhase = "idle";
	private static final ThreadLocal<ChunkWaitCaller> ACTIVE_CHUNK_CALLER = new ThreadLocal<>();
	private static volatile long liveWorldgenOldestQueuedAtMillis;
	private static volatile long liveWorldgenActiveStartedAtMillis;
	private static volatile int liveWorldgenQueueDepth;
	private static volatile boolean liveWorldgenActivePriority;
	private static volatile long liveGenerationClaims;
	private static volatile long liveGenerationTasks;
	private static volatile long liveWorkerMessageBatches;
	private static volatile long liveSaveStartedAtMillis;
	private static volatile long liveLastSaveCompletedAtMillis;
	private static volatile boolean liveSaveActive;
	private static volatile long liveVfsWriteStartedAtMillis;
	private static volatile long liveVfsLastWriteCompletedAtMillis;
	private static volatile long liveVfsWrites;
	private static volatile boolean liveVfsWriteActive;
	private static volatile String liveVfsWriteKind = "none";
	private static volatile long liveEntityProgress;
	private static volatile String liveEntityType = "none";
	private static long lastHeartbeatAtMillis;

	private static long reportStarted;
	private static long ticks;
	private static long tickMillisTotal;
	private static long tickMillisMax;
	private static long worldgenTasks;
	private static long worldgenMillisTotal;
	private static long worldgenMillisMax;
	private static long worldgenYields;
	private static long worldgenYieldMillisTotal;
	private static long worldgenYieldMillisMax;
	/** Monotonic while perfdebug is enabled; stage probes use deltas across cooperative suspension. */
	private static long worldgenYieldMillisLifetime;
	private static long worldgenTimerYields;
	private static long obsoleteGenerationCompleted;
	private static int worldgenQueue;
	private static int worldgenQueueMax;
	private static long chunksSent;
	private static long chunkSendDistanceTotal;
	private static int chunkSendDistanceMax;
	private static long chunksSentNearPlayer;
	private static long blockChanges;
	private static long levelTicks;
	private static long levelPreMillis;
	private static long levelChunkMillis;
	private static long levelChunkMaxMillis;
	private static long levelEntityMillis;
	private static long levelEntityMaxMillis;
	private static long serverPhaseSamples;
	private static final long[] serverPhaseMillis = new long[5];
	private static final long[] serverPhaseMaxMillis = new long[5];
	private static final String[] SERVER_PHASE_NAMES = { "pre", "levels", "connection", "players", "sendChunks" };
	private static long chunkCachePhaseSamples;
	private static final long[] chunkCachePhaseMillis = new long[5];
	private static final long[] chunkCachePhaseMaxMillis = new long[5];
	private static final String[] CHUNK_CACHE_PHASE_NAMES = { "purge", "distance", "tick", "tracking", "unload" };
	private static int residentChunks;
	private static int residentChunksMax;
	private static int pendingUnloads;
	private static int pendingUnloadsMax;
	private static int unloadQueue;
	private static int unloadQueueMax;
	private static int poiChunks;
	private static int poiChunksMax;
	private static int poiSections;
	private static int poiSectionsMax;
	private static int structureChunks;
	private static int structureChunksMax;
	private static int structureChecks;
	private static int structureChecksMax;
	private static long movementTooQuick;
	private static long movementCollisionRollback;
	private static long movementTeleportRetries;
	private static long movementTeleportAcks;
	private static long movementTerrainDeferrals;
	private static long movementTargetUnloaded;
	private static int movementAckTicksMax;
	public static final int MOVEMENT_TOO_QUICK = 0;
	public static final int MOVEMENT_COLLISION_ROLLBACK = 1;
	public static final int MOVEMENT_TELEPORT_RETRY = 2;
	public static final int MOVEMENT_TELEPORT_ACK = 3;
	public static final int MOVEMENT_TERRAIN_DEFERRED = 4;
	private static final int ENTITY_TYPE_SLOTS = 32;
	private static final String[] entityTypeNames = new String[ENTITY_TYPE_SLOTS];
	private static final long[] entityTypeNanos = new long[ENTITY_TYPE_SLOTS];
	private static final long[] entityTypeMaxNanos = new long[ENTITY_TYPE_SLOTS];
	private static final long[] entityTypeSamples = new long[ENTITY_TYPE_SLOTS];
	private static final long[] stageMillis = new long[12];
	private static final long[] stageMaxMillis = new long[12];
	private static final long[] stageCount = new long[12];
	private static final String[] STAGE_NAMES = { "empty", "structures", "references", "biomes", "noise", "surface",
			"carvers", "features", "initLight", "light", "spawn", "full" };
	public static final int KERNEL_NOISE_FILL = 0;
	public static final int KERNEL_LIGHT_PROPAGATION = 1;
	private static final String[] KERNEL_NAMES = { "noiseFill", "lightPropagation" };
	private static final long[] kernelWallMillis = new long[KERNEL_NAMES.length];
	private static final long[] kernelYieldMillis = new long[KERNEL_NAMES.length];
	private static final long[] kernelActiveMillis = new long[KERNEL_NAMES.length];
	private static final long[] kernelMaxActiveMillis = new long[KERNEL_NAMES.length];
	private static final long[] kernelItems = new long[KERNEL_NAMES.length];
	private static final long[] kernelCount = new long[KERNEL_NAMES.length];

	private EaglerServerPerf() {
	}

	public static void setEnabled(final boolean value) {
		enabled = value;
		ACTIVE_CHUNK_CALLER.remove();
		reportStarted = value ? EagRuntime.steadyTimeMillis() : 0L;
		if (value) {
			long now = EagRuntime.steadyTimeMillis();
			liveTickStartedAtMillis = 0L;
			liveLastTickCompletedAtMillis = now;
			liveTickNumber = liveLastCompletedTick = 0;
			liveHasProgressed = false;
			liveTickPhase = "idle";
			liveWorldgenOldestQueuedAtMillis = liveWorldgenActiveStartedAtMillis = 0L;
			liveWorldgenQueueDepth = 0;
			liveWorldgenActivePriority = false;
			liveGenerationClaims = liveGenerationTasks = 0L;
			liveWorkerMessageBatches = 0L;
			liveSaveStartedAtMillis = liveLastSaveCompletedAtMillis = 0L;
			liveSaveActive = false;
			liveVfsWriteStartedAtMillis = liveVfsLastWriteCompletedAtMillis = 0L;
			liveVfsWrites = 0L;
			liveVfsWriteActive = false;
			liveVfsWriteKind = "none";
			liveEntityProgress = 0L;
			liveEntityType = "none";
			lastHeartbeatAtMillis = now;
		}
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void serverTickStarted(final int tick) {
		if (enabled) {
			liveTickNumber = tick;
			liveTickStartedAtMillis = EagRuntime.steadyTimeMillis();
			liveTickPhase = "packets";
		}
	}

	public static void serverTickPhase(final String phase) {
		if (enabled && phase != null) {
			liveTickPhase = phase;
		}
	}

	/**
	 * Installs the small diagnostic context used to identify synchronous chunk waits.
	 * It is deliberately thread-local: a worker-side getChunk must not borrow the
	 * caller details from the server fiber, and no gameplay state is stored here.
	 */
	public static void beginChunkCaller(final String kind, final String type, final String id,
			final int chunkX, final int chunkZ) {
		if (enabled) {
			ACTIVE_CHUNK_CALLER.set(new ChunkWaitCaller(kind, type, id, chunkX, chunkZ));
		}
	}

	public static void clearChunkCaller() {
		ACTIVE_CHUNK_CALLER.remove();
	}

	/** Captures all wait metadata before managedBlock can run nested server work. */
	public static ChunkWaitContext captureChunkWaitContext() {
		if (!enabled) {
			return ChunkWaitContext.EMPTY;
		}
		ChunkWaitCaller caller = ACTIVE_CHUNK_CALLER.get();
		return new ChunkWaitContext(liveTickPhase, caller);
	}

	public static void chunkWait(final long elapsedMillis, final String status, final int requestedX,
			final int requestedZ, final boolean loadOrGenerate, final ChunkWaitContext context) {
		if (enabled && elapsedMillis >= 1000L) {
			ChunkWaitContext safeContext = context == null ? ChunkWaitContext.EMPTY : context;
			LOGGER.info("[EagPerfChunkWait] elapsed={}ms status={} requested={},{} load={} phase={} "
					+ "callerKind={} callerType={} callerId={} callerChunk={},{} thread={}", elapsedMillis,
					status, requestedX, requestedZ, loadOrGenerate, safeContext.phase, safeContext.callerKind,
					safeContext.callerType, safeContext.callerId, safeContext.callerChunkX, safeContext.callerChunkZ,
					Thread.currentThread().getName());
		}
	}

	private static final class ChunkWaitCaller {
		private final String kind;
		private final String type;
		private final String id;
		private final int chunkX;
		private final int chunkZ;

		private ChunkWaitCaller(final String kind, final String type, final String id, final int chunkX,
				final int chunkZ) {
			this.kind = kind == null ? "unknown" : kind;
			this.type = type == null ? "unknown" : type;
			this.id = id == null ? "unknown" : id;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
		}
	}

	public static final class ChunkWaitContext {
		private static final ChunkWaitContext EMPTY = new ChunkWaitContext("idle", null);
		private final String phase;
		private final String callerKind;
		private final String callerType;
		private final String callerId;
		private final int callerChunkX;
		private final int callerChunkZ;

		private ChunkWaitContext(final String phase, final ChunkWaitCaller caller) {
			this.phase = phase == null ? "unknown" : phase;
			this.callerKind = caller == null ? "none" : caller.kind;
			this.callerType = caller == null ? "none" : caller.type;
			this.callerId = caller == null ? "none" : caller.id;
			this.callerChunkX = caller == null ? 0 : caller.chunkX;
			this.callerChunkZ = caller == null ? 0 : caller.chunkZ;
		}
	}

	public static void serverTickCompleted(final int tick) {
		if (enabled) {
			if (tick > liveLastCompletedTick) {
				liveHasProgressed = true;
			}
			liveLastCompletedTick = tick;
			liveLastTickCompletedAtMillis = EagRuntime.steadyTimeMillis();
			liveTickStartedAtMillis = 0L;
			liveTickPhase = "idle";
		}
	}

	/** Called by the serialized executor whenever its queue/active command changes. */
	public static void worldgenState(final int depth, final long oldestQueuedAtMillis,
			final long activeStartedAtMillis, final boolean activePriority) {
		if (enabled) {
			liveWorldgenQueueDepth = Math.max(0, depth);
			liveWorldgenOldestQueuedAtMillis = Math.max(0L, oldestQueuedAtMillis);
			liveWorldgenActiveStartedAtMillis = Math.max(0L, activeStartedAtMillis);
			liveWorldgenActivePriority = activePriority;
		}
	}

	public static void generationClaimAcquired() {
		if (enabled) {
			synchronized (EaglerServerPerf.class) {
				liveGenerationClaims++;
			}
		}
	}

	public static void generationClaimReleased() {
		if (enabled) {
			synchronized (EaglerServerPerf.class) {
				liveGenerationClaims = Math.max(0L, liveGenerationClaims - 1L);
			}
		}
	}

	public static void generationTaskCreated() {
		if (enabled) {
			synchronized (EaglerServerPerf.class) {
				liveGenerationTasks++;
			}
		}
	}

	public static void generationTaskCompleted() {
		if (enabled) {
			synchronized (EaglerServerPerf.class) {
				liveGenerationTasks = Math.max(0L, liveGenerationTasks - 1L);
			}
		}
	}

	public static void workerMessageBatch(final int count) {
		if (enabled && count > 0) {
			liveWorkerMessageBatches += count;
		}
	}

	public static void saveStarted() {
		if (enabled) {
			liveSaveStartedAtMillis = EagRuntime.steadyTimeMillis();
			liveSaveActive = true;
		}
	}

	public static void saveCompleted() {
		if (enabled) {
			liveSaveActive = false;
			liveLastSaveCompletedAtMillis = EagRuntime.steadyTimeMillis();
		}
	}

	public static void vfsWriteStarted(final String kind) {
		if (enabled) {
			liveVfsWriteStartedAtMillis = EagRuntime.steadyTimeMillis();
			liveVfsWriteActive = true;
			liveVfsWriteKind = kind == null ? "unknown" : kind;
			liveVfsWrites++;
		}
	}

	public static void vfsWriteCompleted() {
		if (enabled) {
			liveVfsWriteActive = false;
			liveVfsLastWriteCompletedAtMillis = EagRuntime.steadyTimeMillis();
		}
	}

	/**
	 * Supervisor-side liveness sample.  This method must not be called from the server
	 * tick: its purpose is to remain callable while that tick is blocked.  It records
	 * no payload and emits only numeric/phase diagnostics under perfdebug.
	 */
	public static void heartbeat() {
		if (!enabled) {
			return;
		}
		long now = EagRuntime.steadyTimeMillis();
		if (now - lastHeartbeatAtMillis < REPORT_INTERVAL_MILLIS) {
			return;
		}
		lastHeartbeatAtMillis = now;
		long activeTickMillis = liveTickStartedAtMillis == 0L ? 0L : Math.max(0L, now - liveTickStartedAtMillis);
		long sinceCompletedMillis = Math.max(0L, now - liveLastTickCompletedAtMillis);
		long oldestQueueAge = liveWorldgenOldestQueuedAtMillis == 0L ? 0L
				: Math.max(0L, now - liveWorldgenOldestQueuedAtMillis);
		long activeWorldgenMillis = liveWorldgenActiveStartedAtMillis == 0L ? 0L
				: Math.max(0L, now - liveWorldgenActiveStartedAtMillis);
		long saveMillis = !liveSaveActive || liveSaveStartedAtMillis == 0L ? 0L
				: Math.max(0L, now - liveSaveStartedAtMillis);
		long vfsMillis = !liveVfsWriteActive || liveVfsWriteStartedAtMillis == 0L ? 0L
				: Math.max(0L, now - liveVfsWriteStartedAtMillis);
		long heapTotal = safeTotalMemory();
		long heapFree = safeFreeMemory();
		long heapUsed = heapTotal >= 0L && heapFree >= 0L ? Math.max(0L, heapTotal - heapFree) : -1L;
		long heapMax = safeMaxMemory();
		long gcCount = safeGcCount(false);
		long gcTime = safeGcCount(true);
		LOGGER.info("[EagPerfHeartbeat] steadyNow={}ms lastTick={} startedTick={} progressed={} "
				+ "tickStartedAt={}ms tickCompletedAt={}ms sinceCompleted={}ms activeTick={}ms phase={} "
				+ "worldgenQueue={} oldestQueueAge={}ms oldestQueueAt={}ms activeWorldgen={}ms "
				+ "activeWorldgenAt={}ms priority={} claims={} tasks={} "
				+ "workerMessageBatches={} saveActive={} saveAge={}ms lastSaveAgo={}ms "
				+ "saveStartedAt={}ms saveKind=world vfsActive={} vfsAge={}ms vfsStartedAt={}ms "
				+ "vfsWrites={} vfsKind={} lastVfsAgo={}ms "
				+ "entityProgress={} entityType={} heapUsed={}B heapTotal={}B heapMax={}B gcCount={} gcTime={}ms",
				now, liveLastCompletedTick, liveTickNumber, liveHasProgressed,
				liveTickStartedAtMillis, liveLastTickCompletedAtMillis, sinceCompletedMillis, activeTickMillis, liveTickPhase,
				liveWorldgenQueueDepth, oldestQueueAge, liveWorldgenOldestQueuedAtMillis,
				activeWorldgenMillis, liveWorldgenActiveStartedAtMillis, liveWorldgenActivePriority,
				liveGenerationClaims, liveGenerationTasks, liveWorkerMessageBatches, liveSaveActive, saveMillis,
				liveLastSaveCompletedAtMillis == 0L ? 0L : Math.max(0L, now - liveLastSaveCompletedAtMillis),
				liveSaveActive ? liveSaveStartedAtMillis : 0L,
				liveVfsWriteActive, vfsMillis, liveVfsWriteActive ? liveVfsWriteStartedAtMillis : 0L,
				liveVfsWrites, liveVfsWriteKind,
				liveVfsLastWriteCompletedAtMillis == 0L ? 0L : Math.max(0L, now - liveVfsLastWriteCompletedAtMillis),
				liveEntityProgress, liveEntityType, heapUsed, heapTotal, heapMax, gcCount, gcTime);
	}

	private static long safeMaxMemory() {
		try {
			return EagRuntime.maxMemory();
		} catch (Throwable ignored) {
			return -1L;
		}
	}

	private static long safeTotalMemory() {
		try {
			return EagRuntime.totalMemory();
		} catch (Throwable ignored) {
			return -1L;
		}
	}

	private static long safeFreeMemory() {
		try {
			return EagRuntime.freeMemory();
		} catch (Throwable ignored) {
			return -1L;
		}
	}

	/** Returns -1 when this runtime does not expose GC management counters. */
	private static long safeGcCount(final boolean collectionTime) {
		try {
			long total = 0L;
			for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
				long value = collectionTime ? bean.getCollectionTime() : bean.getCollectionCount();
				if (value >= 0L) {
					total += value;
				}
			}
			return total;
		} catch (Throwable ignored) {
			return -1L;
		}
	}

	public static void actionTrace(final String action, final int key, final String stage,
			final int tick, final String detail) {
		if (enabled) {
			LOGGER.info("[EagActionTrace] realm=server action={} key={} stage={} epochMs={} steadyNs={} tick={} {}",
					action, key, stage, System.currentTimeMillis(), System.nanoTime(), tick, detail);
		}
	}

	/**
	 * Diagnostic-only breadcrumb for a cooperatively suspended server operation. The
	 * elapsed value is wall time on purpose: it includes native worldgen work run while
	 * the Java fiber is parked, which is exactly what a player experiences as a stalled
	 * tick. Kept behind perfdebug and a threshold so production and normal local runs do
	 * not log or pay formatting costs.
	 */
	public static void slowPhase(final String name, final long millis) {
		if (enabled && millis >= 25L) {
			LOGGER.info("[EagPerf] slow phase {}={}ms", name, millis);
		}
	}

	public static void worldgenQueued(final int depth) {
		if (!enabled) {
			return;
		}
		worldgenQueue = depth;
		if (depth > worldgenQueueMax) {
			worldgenQueueMax = depth;
		}
	}

	public static void worldgenCompleted(final long millis, final int depth) {
		if (!enabled) {
			return;
		}
		worldgenQueue = depth;
		worldgenTasks++;
		worldgenMillisTotal += millis;
		if (millis > worldgenMillisMax) {
			worldgenMillisMax = millis;
		}
	}

	public static void worldgenYield(final long millis, final boolean timerHandoff) {
		if (!enabled) {
			return;
		}
		worldgenYields++;
		if (timerHandoff) {
			worldgenTimerYields++;
		}
		worldgenYieldMillisTotal += millis;
		worldgenYieldMillisLifetime += millis;
		if (millis > worldgenYieldMillisMax) {
			worldgenYieldMillisMax = millis;
		}
	}

	public static void obsoleteGenerationCompleted() {
		if (enabled) {
			obsoleteGenerationCompleted++;
		}
	}

	/** Snapshot used only by perfdebug probes to subtract cooperative wait from wall time. */
	public static long worldgenYieldMillisSnapshot() {
		return enabled ? worldgenYieldMillisLifetime : 0L;
	}

	/** Records real synchronous kernel work separately from async chunk-stage completion time. */
	public static void kernelWork(final int index, final long wallMillis, final long yieldedMillis,
			final long items) {
		if (!enabled || index < 0 || index >= KERNEL_NAMES.length) {
			return;
		}
		long yielded = Math.max(0L, Math.min(wallMillis, yieldedMillis));
		long active = Math.max(0L, wallMillis - yielded);
		kernelWallMillis[index] += wallMillis;
		kernelYieldMillis[index] += yielded;
		kernelActiveMillis[index] += active;
		kernelMaxActiveMillis[index] = Math.max(kernelMaxActiveMillis[index], active);
		kernelItems[index] += Math.max(0L, items);
		kernelCount[index]++;
	}

	public static void chunkSent(final int chessboardDistance) {
		if (enabled) {
			chunksSent++;
			int distance = Math.max(0, chessboardDistance);
			chunkSendDistanceTotal += distance;
			chunkSendDistanceMax = Math.max(chunkSendDistanceMax, distance);
			if (distance <= 2) {
				chunksSentNearPlayer++;
			}
		}
	}

	public static void blockChanged() {
		if (enabled) {
			blockChanges++;
		}
	}

	public static void chunkStage(final int index, final long millis) {
		if (!enabled) {
			return;
		}
		if (index >= 0 && index < stageMillis.length) {
			stageMillis[index] += millis;
			stageCount[index]++;
			if (millis > stageMaxMillis[index]) {
				stageMaxMillis[index] = millis;
			}
		}
	}

	public static void levelTick(final long preMillis, final long chunkMillis, final long entityMillis) {
		if (!enabled) {
			return;
		}
		levelTicks++;
		levelPreMillis += preMillis;
		levelChunkMillis += chunkMillis;
		levelChunkMaxMillis = Math.max(levelChunkMaxMillis, chunkMillis);
		levelEntityMillis += entityMillis;
		levelEntityMaxMillis = Math.max(levelEntityMaxMillis, entityMillis);
	}

	public static void serverPhases(final long preMillis, final long levelsMillis, final long connectionMillis,
			final long playersMillis, final long sendChunksMillis) {
		if (!enabled) {
			return;
		}
		serverPhaseSamples++;
		long[] sample = { preMillis, levelsMillis, connectionMillis, playersMillis, sendChunksMillis };
		for (int i = 0; i < sample.length; ++i) {
			serverPhaseMillis[i] += sample[i];
			serverPhaseMaxMillis[i] = Math.max(serverPhaseMaxMillis[i], sample[i]);
		}
	}

	public static void chunkCachePhases(final long purgeMillis, final long distanceMillis,
			final long tickingMillis, final long trackingMillis, final long unloadMillis) {
		if (!enabled) {
			return;
		}
		chunkCachePhaseSamples++;
		chunkCachePhaseMillis[0] += purgeMillis;
		chunkCachePhaseMillis[1] += distanceMillis;
		chunkCachePhaseMillis[2] += tickingMillis;
		chunkCachePhaseMillis[3] += trackingMillis;
		chunkCachePhaseMillis[4] += unloadMillis;
		chunkCachePhaseMaxMillis[0] = Math.max(chunkCachePhaseMaxMillis[0], purgeMillis);
		chunkCachePhaseMaxMillis[1] = Math.max(chunkCachePhaseMaxMillis[1], distanceMillis);
		chunkCachePhaseMaxMillis[2] = Math.max(chunkCachePhaseMaxMillis[2], tickingMillis);
		chunkCachePhaseMaxMillis[3] = Math.max(chunkCachePhaseMaxMillis[3], trackingMillis);
		chunkCachePhaseMaxMillis[4] = Math.max(chunkCachePhaseMaxMillis[4], unloadMillis);
	}

	public static void entityType(final String name, final long nanos) {
		if (!enabled) {
			return;
		}
		liveEntityProgress++;
		liveEntityType = name == null ? "unknown" : name;
		int empty = -1;
		for (int i = 0; i < ENTITY_TYPE_SLOTS; ++i) {
			String slotName = entityTypeNames[i];
			if (name.equals(slotName)) {
				entityTypeSamples[i]++;
				entityTypeNanos[i] += nanos;
				entityTypeMaxNanos[i] = Math.max(entityTypeMaxNanos[i], nanos);
				return;
			}
			if (empty < 0 && slotName == null) {
				empty = i;
			}
		}
		if (empty >= 0) {
			entityTypeNames[empty] = name;
			entityTypeSamples[empty] = 1L;
			entityTypeNanos[empty] = nanos;
			entityTypeMaxNanos[empty] = nanos;
		}
	}

	public static void movement(final int kind, final int ackTicks, final boolean targetLoaded) {
		if (!enabled) {
			return;
		}
		switch (kind) {
		case MOVEMENT_TOO_QUICK:
			movementTooQuick++;
			break;
		case MOVEMENT_COLLISION_ROLLBACK:
			movementCollisionRollback++;
			break;
		case MOVEMENT_TELEPORT_RETRY:
			movementTeleportRetries++;
			break;
		case MOVEMENT_TELEPORT_ACK:
			movementTeleportAcks++;
			break;
		case MOVEMENT_TERRAIN_DEFERRED:
			movementTerrainDeferrals++;
			break;
		default:
			break;
		}
		if (!targetLoaded) {
			movementTargetUnloaded++;
		}
		movementAckTicksMax = Math.max(movementAckTicksMax, ackTicks);
	}

	public static void beginChunkResidencyTick() {
		if (!enabled) {
			return;
		}
		residentChunks = 0;
		pendingUnloads = 0;
		unloadQueue = 0;
		poiChunks = 0;
		poiSections = 0;
		structureChunks = 0;
		structureChecks = 0;
	}

	public static void chunkResidency(final int resident, final int pending, final int queued,
			final int loadedPoiChunks, final int loadedPoiSections,
			final int loadedStructureChunks, final int loadedStructureChecks) {
		if (!enabled) {
			return;
		}
		residentChunks += resident;
		residentChunksMax = Math.max(residentChunksMax, residentChunks);
		pendingUnloads += pending;
		pendingUnloadsMax = Math.max(pendingUnloadsMax, pendingUnloads);
		unloadQueue += queued;
		unloadQueueMax = Math.max(unloadQueueMax, unloadQueue);
		poiChunks += loadedPoiChunks;
		poiChunksMax = Math.max(poiChunksMax, poiChunks);
		poiSections += loadedPoiSections;
		poiSectionsMax = Math.max(poiSectionsMax, poiSections);
		structureChunks += loadedStructureChunks;
		structureChunksMax = Math.max(structureChunksMax, structureChunks);
		structureChecks += loadedStructureChecks;
		structureChecksMax = Math.max(structureChecksMax, structureChecks);
	}

	public static void serverTick(final long millis) {
		if (!enabled) {
			return;
		}
		ticks++;
		tickMillisTotal += millis;
		if (millis > tickMillisMax) {
			tickMillisMax = millis;
		}

		long now = EagRuntime.steadyTimeMillis();
		if (EaglerServerState.isInteractiveWorldgen() && now - reportStarted >= REPORT_INTERVAL_MILLIS) {
			long tickAvg = ticks == 0L ? 0L : tickMillisTotal / ticks;
			long worldgenAvg = worldgenTasks == 0L ? 0L : worldgenMillisTotal / worldgenTasks;
			LOGGER.info("[EagPerf] tick avg/max={}/{}ms count={} ewma={}ms | worldgen avg/max={}/{}ms tasks={} queue={}/{} batch={} | chunksSent={} blockChanges={}",
				tickAvg, tickMillisMax, ticks, EaglerServerState.getServerTickEwmaMillis(),
				worldgenAvg, worldgenMillisMax, worldgenTasks, worldgenQueue, worldgenQueueMax,
				EaglerServerState.getWorldgenLayerBatchSize(), chunksSent, blockChanges);
			if (chunksSent != 0L) {
				LOGGER.info("[EagPerf] chunk send distance avg/max={}/{} chunks near2={}/{}",
					chunkSendDistanceTotal / chunksSent, chunkSendDistanceMax, chunksSentNearPlayer, chunksSent);
			}
			if (worldgenYields != 0L) {
				LOGGER.info("[EagPerf] worldgen yields avg/max/count={}/{}ms/{} timer={}",
					worldgenYieldMillisTotal / worldgenYields, worldgenYieldMillisMax,
					worldgenYields, worldgenTimerYields);
			}
			if (obsoleteGenerationCompleted != 0L) {
				LOGGER.info("[EagPerf] obsolete generation completions={}", obsoleteGenerationCompleted);
			}
				if (levelTicks != 0L) {
					LOGGER.info("[EagPerf] level phases avg: pre={}ms chunk={}/{}ms entity={}/{}ms count={}",
							levelPreMillis / levelTicks, levelChunkMillis / levelTicks, levelChunkMaxMillis,
							levelEntityMillis / levelTicks, levelEntityMaxMillis, levelTicks);
				}
				if (serverPhaseSamples != 0L) {
					StringBuilder phases = new StringBuilder();
					for (int i = 0; i < serverPhaseMillis.length; ++i) {
						if (i != 0) {
							phases.append(' ');
						}
						phases.append(SERVER_PHASE_NAMES[i]).append('=')
								.append(serverPhaseMillis[i] / serverPhaseSamples).append('/')
								.append(serverPhaseMaxMillis[i]).append("ms");
					}
					LOGGER.info("[EagPerf] server phases avg/max count={}: {}", serverPhaseSamples, phases);
				}
				if (chunkCachePhaseSamples != 0L) {
					StringBuilder phases = new StringBuilder();
					for (int i = 0; i < chunkCachePhaseMillis.length; ++i) {
						if (i != 0) {
							phases.append(' ');
						}
						phases.append(CHUNK_CACHE_PHASE_NAMES[i]).append('=')
								.append(chunkCachePhaseMillis[i] / chunkCachePhaseSamples).append('/')
								.append(chunkCachePhaseMaxMillis[i]).append("ms");
					}
					LOGGER.info("[EagPerf] chunk cache phases avg/max count={}: {}", chunkCachePhaseSamples, phases);
				}
				LOGGER.info("[EagPerf] residency current/max: chunks={}/{} pendingUnload={}/{} unloadQueue={}/{} poiChunks={}/{} poiSections={}/{} structureChunks={}/{} structureChecks={}/{}",
						residentChunks, residentChunksMax, pendingUnloads, pendingUnloadsMax,
						unloadQueue, unloadQueueMax, poiChunks, poiChunksMax, poiSections, poiSectionsMax,
						structureChunks, structureChunksMax, structureChecks, structureChecksMax);
			if (movementTooQuick != 0L || movementCollisionRollback != 0L || movementTeleportRetries != 0L
					|| movementTeleportAcks != 0L || movementTerrainDeferrals != 0L) {
				LOGGER.info("[EagPerf] movement corrections quick={} collision={} teleportRetry={} ack={} terrainDeferred={} maxAckTicks={} targetUnloaded={}",
						movementTooQuick, movementCollisionRollback, movementTeleportRetries,
						movementTeleportAcks, movementTerrainDeferrals, movementAckTicksMax, movementTargetUnloaded);
			}
			StringBuilder stages = new StringBuilder();
			for (int i = 0; i < stageCount.length; ++i) {
				if (stageCount[i] != 0L) {
					if (stages.length() != 0) {
						stages.append(' ');
					}
					stages.append(STAGE_NAMES[i]).append('=')
						.append(stageMillis[i] / stageCount[i]).append('/')
						.append(stageMaxMillis[i]).append("msx").append(stageCount[i]);
					stageMillis[i] = 0L;
					stageMaxMillis[i] = 0L;
					stageCount[i] = 0L;
				}
			}
			if (stages.length() != 0) {
				LOGGER.info("[EagPerf] chunk stages avg/max/count: {}", stages);
			}
			StringBuilder kernels = new StringBuilder();
			for (int i = 0; i < kernelCount.length; ++i) {
				long count = kernelCount[i];
				if (count != 0L) {
					if (kernels.length() != 0) {
						kernels.append(' ');
					}
					kernels.append(KERNEL_NAMES[i]).append("=active")
							.append(kernelActiveMillis[i] / count).append('/')
							.append(kernelMaxActiveMillis[i]).append("ms wall=")
							.append(kernelWallMillis[i] / count).append("ms yield=")
							.append(kernelYieldMillis[i] / count).append("ms items=")
							.append(kernelItems[i] / count).append('x').append(count);
					kernelWallMillis[i] = kernelYieldMillis[i] = kernelActiveMillis[i] = 0L;
					kernelMaxActiveMillis[i] = kernelItems[i] = kernelCount[i] = 0L;
				}
			}
			if (kernels.length() != 0) {
				LOGGER.info("[EagPerf] native candidates: {}", kernels);
			}
			for (int i = 0; i < ENTITY_TYPE_SLOTS; ++i) {
				long samples = entityTypeSamples[i];
				if (samples != 0L) {
					LOGGER.info("[EagPerf] entity type={} ticks={} avg/max={}/{}ms", entityTypeNames[i], samples,
							millis(entityTypeNanos[i] / samples), millis(entityTypeMaxNanos[i]));
					entityTypeNanos[i] = entityTypeMaxNanos[i] = entityTypeSamples[i] = 0L;
				}
			}
			reportStarted = now;
			ticks = 0L;
			tickMillisTotal = 0L;
			tickMillisMax = 0L;
			worldgenTasks = 0L;
			worldgenMillisTotal = 0L;
			worldgenMillisMax = 0L;
			worldgenYields = 0L;
			worldgenYieldMillisTotal = 0L;
			worldgenYieldMillisMax = 0L;
			worldgenTimerYields = 0L;
			obsoleteGenerationCompleted = 0L;
			worldgenQueueMax = worldgenQueue;
			chunksSent = 0L;
			chunkSendDistanceTotal = 0L;
			chunkSendDistanceMax = 0;
			chunksSentNearPlayer = 0L;
				blockChanges = 0L;
					levelTicks = levelPreMillis = levelChunkMillis = levelChunkMaxMillis = 0L;
					levelEntityMillis = levelEntityMaxMillis = 0L;
					serverPhaseSamples = 0L;
					java.util.Arrays.fill(serverPhaseMillis, 0L);
					java.util.Arrays.fill(serverPhaseMaxMillis, 0L);
					chunkCachePhaseSamples = 0L;
					java.util.Arrays.fill(chunkCachePhaseMillis, 0L);
					java.util.Arrays.fill(chunkCachePhaseMaxMillis, 0L);
					residentChunksMax = residentChunks;
					pendingUnloadsMax = pendingUnloads;
					unloadQueueMax = unloadQueue;
					poiChunksMax = poiChunks;
					poiSectionsMax = poiSections;
					structureChunksMax = structureChunks;
					structureChecksMax = structureChecks;
					movementTooQuick = movementCollisionRollback = movementTeleportRetries = movementTeleportAcks = 0L;
					movementTerrainDeferrals = 0L;
					movementTargetUnloaded = 0L;
					movementAckTicksMax = 0;
			}
	}

	private static String millis(final long nanos) {
		return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0);
	}

	private static String micros(final long micros) {
		return String.format(java.util.Locale.ROOT, "%.2f", micros / 1000.0);
	}
}

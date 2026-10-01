package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.sp.server.internal.ServerPlatformSingleplayer;
import net.minecraft.ReportedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the web server's serialized worldgen queue on a TeaVM coroutine. TeaVM timer
 * suspension is too expensive to pay after every tiny ConsecutiveExecutor stage, so
 * queued stages run in bounded FIFO bursts before yielding to the server coroutine.
 */
public final class EaglerWorldgenExecutor implements Executor {

	public static final EaglerWorldgenExecutor INSTANCE = new EaglerWorldgenExecutor();
	private static final Logger LOGGER = LoggerFactory.getLogger(EaglerWorldgenExecutor.class);
	private static final long INITIAL_SLICE_MILLIS = 128L;
	// The live tick deadline remains the hard bound; keep interactive slices short
	// enough to leave time for entity and gameplay work between generation bursts.
	private static final long INTERACTIVE_SLICE_MILLIS = 24L;
	private static final int MAX_PRIORITY_BURST = 8;

	private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
	private final ArrayDeque<Runnable> priorityQueue = new ArrayDeque<>();
	private final ArrayDeque<Long> queueEnqueuedAt = new ArrayDeque<>();
	private final ArrayDeque<Long> priorityEnqueuedAt = new ArrayDeque<>();
	private final Executor lightingExecutor = this::executeLighting;
	private final Thread workerThread;
	private volatile long activeStartedAtMillis;
	private volatile boolean activePriority;

	private EaglerWorldgenExecutor() {
		workerThread = new Thread(this::runLoop, "Eagler worldgen");
		workerThread.setDaemon(true);
		workerThread.start();
	}

	/**
	 * The browser timer handoff is required only while this continuously busy
	 * worldgen coroutine could starve the integrated server's timer. A generation
	 * checkpoint can also run on the server coroutine itself (for example while the
	 * ticket-distance graph is being advanced). Suspending that live tick on a
	 * zero-delay browser timer adds no fairness and can strand entities/TPS behind a
	 * heavily delayed timer callback.
	 */
	public static boolean isExecutorThread() {
		return Thread.currentThread() == INSTANCE.workerThread;
	}

	@Override
	public void execute(Runnable command) {
		enqueue(command, false);
	}

	/**
	 * Executor used by the light engine. Lighting continuations unblock FULL chunks, so
	 * putting them at the tail of the much larger terrain-generation FIFO can leave a
	 * completed world showing only one or two chunks for several seconds. They share the
	 * same cooperative worker thread, but receive bounded priority over new worldgen work.
	 */
	public Executor lightingExecutor() {
		return this.lightingExecutor;
	}

	/** Bounded same-thread priority for cancellation bookkeeping only. */
	public Executor cancellationExecutor() {
		return this.lightingExecutor;
	}

	private void executeLighting(Runnable command) {
		enqueue(command, true);
	}

	private void enqueue(Runnable command, boolean priority) {
		if (command == null) {
			throw new NullPointerException("command");
		}
		synchronized (queue) {
			long now = EagRuntime.steadyTimeMillis();
			(priority ? priorityQueue : queue).addLast(command);
			(priority ? priorityEnqueuedAt : queueEnqueuedAt).addLast(now);
			publishStateLocked();
			if (EaglerServerPerf.isEnabled()) {
				EaglerServerPerf.worldgenQueued(queue.size() + priorityQueue.size());
			}
			queue.notify();
		}
	}

	private void runLoop() {
		long nextYieldAt = 0L;
		int priorityBurst = 0;
		while (true) {
			Runnable command;
			boolean resumedFromIdle = false;
			synchronized (queue) {
				while (queue.isEmpty() && priorityQueue.isEmpty()) {
					try {
						queue.wait();
						resumedFromIdle = true;
					} catch (InterruptedException ignored) {
					}
				}
				boolean pickedPriority;
				if (!priorityQueue.isEmpty() && (queue.isEmpty() || priorityBurst < MAX_PRIORITY_BURST)) {
					command = priorityQueue.removeFirst();
					priorityEnqueuedAt.removeFirst();
					pickedPriority = true;
					priorityBurst++;
				} else {
					command = queue.removeFirst();
					queueEnqueuedAt.removeFirst();
					pickedPriority = false;
					priorityBurst = 0;
				}
				activePriority = pickedPriority;
				activeStartedAtMillis = EagRuntime.steadyTimeMillis();
				publishStateLocked();
			}
			long now = EagRuntime.steadyTimeMillis();
			boolean interactive = EaglerServerState.isInteractiveWorldgen();
			long sliceMillis = interactive ? INTERACTIVE_SLICE_MILLIS : INITIAL_SLICE_MILLIS;
			if (resumedFromIdle || nextYieldAt == 0L || nextYieldAt > now + sliceMillis) {
				nextYieldAt = now + (interactive
						? EaglerServerState.worldgenMillisUntilYield(now, sliceMillis)
						: sliceMillis);
			}

			boolean perf = EaglerServerPerf.isEnabled();
			long taskStarted = perf ? EagRuntime.steadyTimeMillis() : 0L;
			try {
				command.run();
			} catch (Throwable t) {
				if (t instanceof ReportedException reported) {
					LOGGER.error("Worldgen task failed: {}\n{}", reported.getReport().getExceptionMessage(),
							reported.getReport().getDetails());
				}
				Thread current = Thread.currentThread();
				Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
				if (handler != null) {
					handler.uncaughtException(current, t);
				}
			}
			int remaining;
			synchronized (queue) {
				remaining = queue.size() + priorityQueue.size();
				activeStartedAtMillis = 0L;
				activePriority = false;
				publishStateLocked();
			}
			EaglerServerState.updateWorldgenQueueDepth(remaining);
			if (perf) {
				EaglerServerPerf.worldgenCompleted(EagRuntime.steadyTimeMillis() - taskStarted, remaining);
			}

			if (EagRuntime.steadyTimeMillis() >= nextYieldAt) {
				long yieldDecisionAt = EagRuntime.steadyTimeMillis();
				long yieldStarted = perf ? yieldDecisionAt : 0L;
				// MessageChannel yields are not subject to the browser's nested-timer
				// clamp. Use a timer only when the server's own timer deadline is due;
				// otherwise a continuously busy message queue can starve its 20 TPS wakeup.
				boolean timerHandoff = EaglerServerState.claimWorldgenTimerHandoff(yieldDecisionAt);
				if (timerHandoff) {
					EagUtils.sleep(0L);
				} else {
					ServerPlatformSingleplayer.immediateContinue();
				}
				long resumedAt = EagRuntime.steadyTimeMillis();
				if (perf) {
					EaglerServerPerf.worldgenYield(resumedAt - yieldStarted, timerHandoff);
				}
				boolean resumedInteractive = EaglerServerState.isInteractiveWorldgen();
				nextYieldAt = resumedAt + (resumedInteractive
						? EaglerServerState.worldgenMillisUntilYield(resumedAt, INTERACTIVE_SLICE_MILLIS)
						: INITIAL_SLICE_MILLIS);
			}
		}
	}

	/** Publish only monotonic numeric state consumed by the opt-in server heartbeat. */
	private void publishStateLocked() {
		long oldest = 0L;
		if (!queueEnqueuedAt.isEmpty()) {
			oldest = queueEnqueuedAt.peekFirst();
		}
		if (!priorityEnqueuedAt.isEmpty()) {
			long priorityOldest = priorityEnqueuedAt.peekFirst();
			oldest = oldest == 0L ? priorityOldest : Math.min(oldest, priorityOldest);
		}
		int depth = queue.size() + priorityQueue.size();
		EaglerServerState.updateWorldgenQueueDepth(depth);
		if (EaglerServerPerf.isEnabled()) {
			EaglerServerPerf.worldgenState(depth, oldest, activeStartedAtMillis, activePriority);
		}
	}
}

package net.lax1dude.eaglercraft.v1_8.sp.server;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;
import net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted;
import net.lax1dude.eaglercraft.v1_8.sp.server.internal.ServerPlatformSingleplayer;

/** Time-slices long worldgen loops without changing their order or output. */
public final class EaglerWorldgenYield {

	// Inner-loop checkpoints keep long feature and lighting passes responsive
	// without yielding after every placed block.
	// Use the idle part of a 50 ms server tick without letting long generation
	// loops consume the tick itself. EaglerServerState clamps this to the live
	// next-tick deadline, so busy ticks still get a shorter slice.
	private static final long MAX_SLICE_MILLIS = 24L;
	private static long nextYieldAt;
	private static int frequentCalls;

	private EaglerWorldgenYield() {
	}

	public static void ifNeeded() {
		if (!EaglerHosted.isActive() || EagRuntime.getPlatformType() == EnumPlatformType.DESKTOP) {
			return;
		}
		// The loading screen has no gameplay ticks to protect. Run initial spawn
		// generation at full speed, then begin slicing once the player is live.
		if (!EaglerServerState.isInteractiveWorldgen()) {
			return;
		}
		long now = EagRuntime.steadyTimeMillis();
		if (now >= nextYieldAt) {
			boolean perf = EaglerServerPerf.isEnabled();
			long yieldStarted = perf ? now : 0L;
			// Only the continuously busy worldgen coroutine needs to hand control to the
			// browser timer queue. This checkpoint is also used by ticket/distance graph
			// loops running inside the live server tick; putting that coroutine on a
			// setTimeout(0) was measured resuming 2.631 seconds late during fast flight.
			// A MessageChannel continuation still yields fairly to already queued
			// worldgen/I/O work without freezing the current server tick on that timer.
			boolean timerHandoff = EaglerWorldgenExecutor.isExecutorThread()
					&& EaglerServerState.claimWorldgenTimerHandoff(now);
			if (timerHandoff) {
				EagUtils.sleep(0L);
			} else {
				ServerPlatformSingleplayer.immediateContinue();
			}
			long resumedAt = EagRuntime.steadyTimeMillis();
			if (perf) {
				EaglerServerPerf.worldgenYield(resumedAt - yieldStarted, timerHandoff);
			}
			nextYieldAt = resumedAt + EaglerServerState.worldgenMillisUntilYield(resumedAt, MAX_SLICE_MILLIS);
		}
	}

	/**
	 * Cheap checkpoint for very hot inner loops. Reading the browser clock for every
	 * placed block costs measurable worldgen time, so only one call in 32 performs
	 * the full deadline check. Initial spawn generation bypasses this method entirely;
	 * the tighter interval applies only while gameplay ticks and entities need service.
	 */
	public static void ifNeededFrequent() {
		if (!EaglerServerState.isInteractiveWorldgen()) {
			return;
		}
		if ((++frequentCalls & 31) == 0) {
			ifNeeded();
		}
	}
}

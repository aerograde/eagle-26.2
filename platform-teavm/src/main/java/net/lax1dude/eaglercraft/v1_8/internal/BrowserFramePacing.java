package net.lax1dude.eaglercraft.v1_8.internal;

/** Pure deadline calculations shared by the cooperative browser frame pacer. */
public final class BrowserFramePacing {

	private BrowserFramePacing() {
	}

	public static boolean isLimited(int fpsLimit) {
		return fpsLimit > 0 && fpsLimit <= 1000;
	}

	public static double frameMillis(int fpsLimit) {
		return 1000.0 / fpsLimit;
	}

	public static double initialDeadline(double now, double frameMillis) {
		return now + frameMillis;
	}

	public static double nextDeadline(double previousDeadline, double now, double frameMillis) {
		double next = previousDeadline + frameMillis;
		return next <= now ? now + frameMillis : next;
	}
}

package net.minecraft.client;

/**
 * Browser same-FQN shadow for the desktop busy-wait limiter. The render loop
 * publishes its effective limit here after each frame; WebGL2/WebGPU consume it
 * cooperatively during the following surface present, where TeaVM can yield.
 */
public class FramerateLimiter {
	private static int framerateLimit;

	public static void limitDisplayFPS(final int framerateLimit) {
		// 260 is the Options "unlimited" sentinel. The browser surfaces consume
		// this value on their next present, where yielding is safe for TeaVM.
		FramerateLimiter.framerateLimit = framerateLimit >= 260 ? 0 : Math.max(framerateLimit, 1);
	}

	public static int getFramerateLimit() {
		return framerateLimit;
	}
}

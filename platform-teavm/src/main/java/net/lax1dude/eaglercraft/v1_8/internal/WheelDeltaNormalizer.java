package net.lax1dude.eaglercraft.v1_8.internal;

public final class WheelDeltaNormalizer {

	public static final int DELTA_PIXEL = 0;
	public static final int DELTA_LINE = 1;
	public static final int DELTA_PAGE = 2;

	private static final double PIXELS_PER_STEP = 100.0;
	private static final double DISCRETE_PIXEL_THRESHOLD = 40.0;
	private static final double LINES_PER_STEP = 3.0;
	private static final double LEGACY_STEP = 120.0;
	private static final double EVENT_LIMIT = 8.0;

	private WheelDeltaNormalizer() {
	}

	public static float normalize(double value, int mode, double legacyValue) {
		double normalized;
		if (isDiscreteLegacyDelta(legacyValue)) {
			normalized = legacyValue / LEGACY_STEP;
		} else if (mode == DELTA_PIXEL) {
			normalized = value / PIXELS_PER_STEP;
			// Chromium does not expose wheelDelta consistently on ChromeOS. A physical
			// wheel notch can therefore arrive as an integer pixel delta around 40-100.
			// Keep small trackpad deltas fractional, but ensure a real notch always
			// advances Minecraft's integer scroll accumulator immediately.
			if (Math.abs(value) >= DISCRETE_PIXEL_THRESHOLD && value == Math.rint(value)
					&& Math.abs(normalized) < 1.0) {
				normalized = Math.signum(value);
			}
		} else if (mode == DELTA_LINE) {
			normalized = value / LINES_PER_STEP;
			if (Math.abs(normalized) < 1.0 && Math.abs(value) >= 1.0 && value == Math.rint(value)) {
				normalized = Math.signum(value);
			}
		} else {
			normalized = Math.signum(value);
		}
		return (float)Math.max(-EVENT_LIMIT, Math.min(EVENT_LIMIT, normalized));
	}

	private static boolean isDiscreteLegacyDelta(double value) {
		if (!Double.isFinite(value) || Math.abs(value) < LEGACY_STEP) {
			return false;
		}
		double steps = Math.rint(value / LEGACY_STEP);
		return steps != 0.0 && Math.abs(value - steps * LEGACY_STEP) < 0.01;
	}
}

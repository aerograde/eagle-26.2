package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.lang.Math (see plugin.JdkMethodInjector).
 */
public final class MathInject {

	/**
	 * Exact: a float product is at most 48 significand bits, so computing in
	 * double arithmetic rounds exactly once — identical to a fused multiply-add.
	 */
	public static float fma(float a, float b, float c) {
		return (float) ((double) a * (double) b + (double) c);
	}

	/** Not fused (double rounding); acceptable per the Phase 3 spike notes. */
	public static double fma(double a, double b, double c) {
		return a * b + c;
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.lang.Double (see plugin.JdkMethodInjector). sum() backs the
 * Collectors/summing reductions MC uses; TeaVM 0.13's TDouble lacks it.
 */
public final class DoubleInject {

	public static double sum(double a, double b) {
		return a + b;
	}

}

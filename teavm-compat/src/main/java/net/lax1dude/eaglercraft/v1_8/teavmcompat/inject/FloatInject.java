package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.lang.Float (see plugin.JdkMethodInjector).
 */
public final class FloatInject {

	public static float sum(float a, float b) {
		return a + b;
	}

}

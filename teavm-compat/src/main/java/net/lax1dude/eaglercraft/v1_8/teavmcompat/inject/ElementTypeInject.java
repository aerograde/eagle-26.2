package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.lang.annotation.ElementType;

/**
 * Donor for java.lang.annotation.ElementType (see plugin.JdkMethodInjector):
 * supplies enum constants teavm-classlib 0.13 predates. Field-only injection —
 * the values are null at runtime (no clinit merge), which only degrades
 * identity comparisons in annotation processors that never run in-browser.
 */
public final class ElementTypeInject {

	public static ElementType MODULE;
	public static ElementType RECORD_COMPONENT;

}

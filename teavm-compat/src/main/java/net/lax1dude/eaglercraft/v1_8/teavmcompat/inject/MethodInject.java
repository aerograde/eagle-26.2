package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.lang.annotation.Annotation;

/**
 * Donor for java.lang.reflect.Method (see plugin.JdkMethodInjector).
 */
public final class MethodInject {

	public Annotation[][] getParameterAnnotations() {
		return new Annotation[0][];
	}

	public Class<?>[] getExceptionTypes() {
		return new Class<?>[0];
	}

}

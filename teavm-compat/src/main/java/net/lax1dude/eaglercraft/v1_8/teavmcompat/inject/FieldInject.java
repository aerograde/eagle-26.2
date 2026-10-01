package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.lang.reflect.Type;

/**
 * Donor for java.lang.reflect.Field (see plugin.JdkMethodInjector). TeaVM has
 * no generic signatures, so the generic type IS the raw type (classlib TClass
 * implements Type, making the cast valid at runtime).
 */
public final class FieldInject {

	/** placeholder: the target already implements getType (never copied) */
	public Class<?> getType() {
		return null;
	}

	public Type getGenericType() {
		return (Type) (Object) getType();
	}

}

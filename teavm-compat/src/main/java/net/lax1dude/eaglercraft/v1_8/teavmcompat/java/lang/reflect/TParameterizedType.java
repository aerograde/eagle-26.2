package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.reflect;

import java.lang.reflect.Type;

/**
 * java.lang.reflect.ParameterizedType — interface mirror so gson/guava type
 * plumbing links. TeaVM keeps no generic metadata, so no instance is ever
 * produced by Class.getGenericSuperclass (it returns the raw class); code
 * paths requiring a real ParameterizedType (anonymous gson TypeToken) fail at
 * runtime and must be avoided in ported code.
 */
public interface TParameterizedType extends Type {

	Type[] getActualTypeArguments();

	Type getRawType();

	Type getOwnerType();

}

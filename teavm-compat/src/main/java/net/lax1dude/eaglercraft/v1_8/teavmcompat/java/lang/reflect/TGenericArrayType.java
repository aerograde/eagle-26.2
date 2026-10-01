package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.reflect;

import java.lang.reflect.Type;

/**
 * java.lang.reflect.GenericArrayType — interface mirror (see
 * TParameterizedType).
 */
public interface TGenericArrayType extends Type {

	Type getGenericComponentType();

}

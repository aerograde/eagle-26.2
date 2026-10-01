package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.reflect;

import java.lang.reflect.Type;

/**
 * java.lang.reflect.WildcardType — interface mirror (see TParameterizedType).
 */
public interface TWildcardType extends Type {

	Type[] getUpperBounds();

	Type[] getLowerBounds();

}

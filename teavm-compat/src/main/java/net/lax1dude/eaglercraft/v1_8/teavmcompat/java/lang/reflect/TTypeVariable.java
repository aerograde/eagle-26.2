package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.reflect;

import java.lang.reflect.Type;

/**
 * java.lang.reflect.TypeVariable — interface mirror (see TParameterizedType).
 */
public interface TTypeVariable<D extends TGenericDeclaration> extends Type {

	Type[] getBounds();

	D getGenericDeclaration();

	String getName();

}

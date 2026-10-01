package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.io;

/**
 * java.io.ObjectStreamField — link-only stub (reached from serializable-type
 * descriptors; no real Java serialization stream runs in the browser).
 */
public class TObjectStreamField {

	private final String name;
	private final Class<?> type;

	public TObjectStreamField(String name, Class<?> type) {
		this.name = name;
		this.type = type;
	}

	public TObjectStreamField(String name, Class<?> type, boolean unshared) {
		this.name = name;
		this.type = type;
	}

	public String getName() {
		return name;
	}

	public Class<?> getType() {
		return type;
	}

	public char getTypeCode() {
		if (type == null) return 'L';
		if (!type.isPrimitive()) return type.isArray() ? '[' : 'L';
		if (type == int.class) return 'I';
		if (type == long.class) return 'J';
		if (type == double.class) return 'D';
		if (type == float.class) return 'F';
		if (type == boolean.class) return 'Z';
		if (type == byte.class) return 'B';
		if (type == char.class) return 'C';
		if (type == short.class) return 'S';
		return 'L';
	}

	public boolean isPrimitive() {
		return type != null && type.isPrimitive();
	}
}

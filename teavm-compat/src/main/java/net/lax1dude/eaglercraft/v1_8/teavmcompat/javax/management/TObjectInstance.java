package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.management;

/**
 * javax.management.ObjectInstance — value-object stub (see TMBeanServer).
 */
public class TObjectInstance {

	private final TObjectName name;
	private final String className;

	public TObjectInstance(TObjectName objectName, String className) {
		this.name = objectName;
		this.className = className;
	}

	public TObjectName getObjectName() {
		return name;
	}

	public String getClassName() {
		return className;
	}

}

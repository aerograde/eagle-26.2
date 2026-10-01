package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.management;

/**
 * javax.management.ObjectName — value-object stub (no JMX exists in the
 * browser runtime, see TMBeanServer).
 */
public class TObjectName {

	private final String name;

	public TObjectName(String name) {
		this.name = name;
	}

	public String getCanonicalName() {
		return name;
	}

	@Override
	public String toString() {
		return name;
	}

}

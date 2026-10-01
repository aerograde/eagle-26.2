package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

import java.security.Permission;

/**
 * java.lang.RuntimePermission — inert permission object (nothing in the
 * browser runtime ever checks permissions).
 */
public class TRuntimePermission extends Permission {

	public TRuntimePermission(String name) {
		super(name);
	}

	public TRuntimePermission(String name, String actions) {
		super(name);
	}

	@Override
	public boolean implies(Permission permission) {
		return false;
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof TRuntimePermission && ((TRuntimePermission) obj).getName().equals(getName());
	}

	@Override
	public int hashCode() {
		return getName().hashCode();
	}

	@Override
	public String getActions() {
		return "";
	}

}

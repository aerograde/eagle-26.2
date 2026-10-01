package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.security.Permission;

/**
 * Donor for java.security.AccessController (see plugin.JdkMethodInjector).
 */
public final class AccessControllerInject {

	public static void checkPermission(Permission perm) {
		// inert: everything is permitted in the sandboxed browser runtime
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.lang.SecurityManager (see plugin.JdkMethodInjector). Caller
 * introspection hacks get a small array of Object.class so index arithmetic
 * does not explode.
 */
public final class SecurityManagerInject {

	protected Class<?>[] getClassContext() {
		return new Class<?>[] { Object.class, Object.class, Object.class, Object.class, Object.class, Object.class };
	}

	public ThreadGroup getThreadGroup() {
		return null;
	}

}

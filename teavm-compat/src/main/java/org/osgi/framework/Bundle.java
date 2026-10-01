package org.osgi.framework;

/**
 * Minimal OSGi Bundle — log4j2's activator plumbing links against this
 * optional dependency; no OSGi framework ever exists in the browser runtime.
 */
public interface Bundle {

	int UNINSTALLED = 0x00000001;
	int INSTALLED = 0x00000002;
	int RESOLVED = 0x00000004;
	int STARTING = 0x00000008;
	int STOPPING = 0x00000010;
	int ACTIVE = 0x00000020;

	String getSymbolicName();

	BundleContext getBundleContext();

	int getState();

	<A> A adapt(Class<A> type);

}

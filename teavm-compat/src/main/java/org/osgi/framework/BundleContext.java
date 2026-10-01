package org.osgi.framework;

/**
 * Minimal OSGi BundleContext (see Bundle).
 */
public interface BundleContext {

	Bundle getBundle();

	java.util.Collection<?> getServiceReferences(Class<?> clazz, String filter);

}

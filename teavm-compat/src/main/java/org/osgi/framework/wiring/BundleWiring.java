package org.osgi.framework.wiring;

/**
 * Minimal OSGi BundleWiring (see org.osgi.framework.Bundle).
 */
public interface BundleWiring {

	ClassLoader getClassLoader();

	java.util.Collection<String> listResources(String path, String filePattern, int options);

}

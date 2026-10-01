package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.management;

import java.util.List;

/**
 * java.lang.management.RuntimeMXBean — inert subset (see TManagementFactory).
 */
public interface TRuntimeMXBean {

	String getName();

	String getVmName();

	String getVmVendor();

	String getVmVersion();

	List<String> getInputArguments();

	long getStartTime();

	long getUptime();

}

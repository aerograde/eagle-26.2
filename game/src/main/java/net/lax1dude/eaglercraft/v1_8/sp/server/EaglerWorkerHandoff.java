package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.util.function.Supplier;

import net.minecraft.client.server.IntegratedServer;

/**
 * Transfers a server launch from the client thread to the worker thread and exposes
 * the current server to client code that still needs it.
 */
public class EaglerWorkerHandoff {

	private static volatile Supplier<IntegratedServer> pendingLaunch = null;
	private static volatile IntegratedServer currentServer = null;
	private static volatile java.net.SocketAddress localSocketAddress = null;

	/** [CLIENT] queue a launch; the worker picks it up on its next loop pass. */
	public static void submitLaunch(Supplier<IntegratedServer> launch) {
		pendingLaunch = launch;
	}

	/** [WORKER] */
	static Supplier<IntegratedServer> takeLaunch() {
		Supplier<IntegratedServer> l = pendingLaunch;
		if(l != null) {
			pendingLaunch = null;
		}
		return l;
	}

	static void setCurrentServer(IntegratedServer server) {
		currentServer = server;
		if(server == null) {
			localSocketAddress = null;
		}
	}

	static void setLocalSocketAddress(java.net.SocketAddress address) {
		localSocketAddress = address;
	}

	/** [CLIENT] desktop bridge: the LocalChannel address to connect the data plane. */
	public static java.net.SocketAddress getLocalSocketAddress() {
		return localSocketAddress;
	}

	/**
	 * [CLIENT] read-only access for client paths that need the live server object,
	 * including the debug overlay and chunk status view.
	 */
	public static IntegratedServer getCurrentServer() {
		return currentServer;
	}

}

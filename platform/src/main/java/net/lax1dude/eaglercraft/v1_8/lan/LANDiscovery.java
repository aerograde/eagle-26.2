/*
 * Copyright (c) 2022-2024 lax1dude, ayunami2000. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.
 *
 */

package net.lax1dude.eaglercraft.v1_8.lan;

/**
 * Automatic same-network peer discovery, desktop/unknown-runtime view.
 *
 * <p>The web client gets this for free: the boot shell loads
 * {@code lan-discovery.js} before the Wasm starts, that module finds the peers
 * on the same network through the page's own discovery service
 * ({@code tools/lan-server.mjs}) and keeps a direct WebRTC data channel to each
 * one. This class is the bridge the game layer reads: peer list, status and the
 * event stream, all as JSON strings so no platform type leaks into :game.
 *
 * <p>On any runtime without the browser module (desktop LWJGL, a plain static
 * host) every method returns the empty/unsupported answer, so callers never
 * need to branch on the platform: the manual join-code flow stays the only
 * path there.
 *
 * <p>The mirror class with the same name in {@code :platform-teavm} is the real
 * implementation and wins on the web target (the platform classpath is ordered
 * platform-teavm, platform). Keep both signatures identical.
 */
public final class LANDiscovery {

	/** Version of the JS module this bridge speaks to. */
	public static final int VERSION = 1;

	private LANDiscovery() {
	}

	/** True when the discovery module is installed (state is not disabled/idle). */
	public static boolean isInstalled() {
		return false;
	}

	/** True when the module found a discovery service on this page's origin. */
	public static boolean isServiceAvailable() {
		return false;
	}

	/** disabled | idle | probing | unavailable | online */
	public static String getState() {
		return "disabled";
	}

	/** One line a player could read, e.g. "Automatic LAN discovery: 1 connected, 2 found". */
	public static String getStatus() {
		return "Automatic LAN discovery is not available on this platform";
	}

	/** Network scope the origin grouped this peer into, null when unknown. */
	public static String getScope() {
		return null;
	}

	/** This client's discovery id (also the peer id other players see). */
	public static String getSelfId() {
		return null;
	}

	public static String getSelfName() {
		return null;
	}

	/** Base URL of the discovery service in use. */
	public static String getServiceUrl() {
		return null;
	}

	/** Last error the module recorded, null when healthy. */
	public static String getLastError() {
		return null;
	}

	/** Peers found on this network (connected or still handshaking). */
	public static int getPeerCount() {
		return 0;
	}

	/** Peers whose data channel is open. */
	public static int getOpenPeerCount() {
		return 0;
	}

	/** JSON array of {id,name,addr,state,rttMs,ageMs}. */
	public static String getPeersJson() {
		return "[]";
	}

	/** Full state snapshot as a JSON object, for screens and diagnostics. */
	public static String getSnapshotJson() {
		return "{\"version\":" + VERSION + ",\"state\":\"disabled\",\"status\":\"not available on this platform\","
				+ "\"service\":null,\"scope\":null,\"self\":null,\"peers\":[],\"openPeers\":0,\"lastError\":null}";
	}

	/** Drain queued discovery events (peer-found / peer-open / message / ...) as JSON. */
	public static String pollEventsJson() {
		return "[]";
	}

	/** Send a text payload to one discovered peer; false when it has no open channel. */
	public static boolean sendText(String peerId, String text) {
		return false;
	}

	/**
	 * Ask a discovered peer to join the local world. The payload is a JSON object
	 * string; the guest's client receives it as an {@code invite} event.
	 */
	public static boolean invite(String peerId, String payloadJson) {
		return false;
	}

	/** Change the name other players see in their online list. */
	public static boolean setName(String name) {
		return false;
	}

	/** Log the boot-time discovery state once; a no-op off the web target. */
	public static void announceAtBoot() {
	}
}

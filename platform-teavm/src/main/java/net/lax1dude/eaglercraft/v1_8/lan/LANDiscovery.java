/*
 * Copyright (c) 2022-2024 lax1dude, ayunami2000. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.
 *
 */

package net.lax1dude.eaglercraft.v1_8.lan;

import java.util.ArrayDeque;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

/**
 * Automatic same-network peer discovery, web view.
 *
 * <p>The real work happens in {@code lan-discovery.js}, which the boot shell
 * (target_teavm_wasm_gc/src/web/index.html) loads before the client Wasm is
 * compiled. That module keeps a presence list against the discovery service on
 * the page's own origin ({@code tools/lan-server.mjs}) and holds a direct WebRTC
 * data channel to every peer it finds, so by the time the game is playable the
 * peers are already connected with no code exchange.
 *
 * <p>This class is the game-side view of that state. It mirrors the
 * {@code :platform} stub one-for-one (the platform classpath gives this class
 * priority on the web target) and needs no other platform plumbing: the module
 * runs on ordinary JS timers, not on a game tick.
 *
 * <p>Typical use in a later multiplayer pass: read {@link #getSnapshotJson()}
 * for the "online" list, {@link #invite(String, String)} to ask a peer into the
 * local world, and {@link #pollEventsJson()} for the accept/decline replies.
 */
public final class LANDiscovery {

	public static final int VERSION = 1;

	private static final Logger logger = LogManager.getLogger("LANDiscovery");

	/** Events delivered by the JS module, drained by {@link #pollEventsJson()}. */
	private static final ArrayDeque<String> pendingEvents = new ArrayDeque<>();
	private static final int MAX_PENDING_EVENTS = 256;

	private static boolean handlerInstalled = false;

	private LANDiscovery() {
	}

	@JSFunctor
	public interface DiscoveryEventHandler extends JSObject {
		void onEvent(String eventJson);
	}

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "return !!(a && a.version === 1);")
	private static native boolean moduleInstalled();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return \"disabled\";"
			+ "try { return String(a.state()); } catch(e) { return \"error\"; }")
	private static native String stateImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return \"Automatic LAN discovery is not available on this page\";"
			+ "try { return String(a.status()); } catch(e) { return \"Automatic LAN discovery status unavailable\"; }")
	private static native String statusImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return null;"
			+ "try { var v = a.scope(); return (v === undefined || v === null) ? null : String(v); } catch(e) { return null; }")
	private static native String scopeImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return null;"
			+ "try { var s = a.self(); return s ? String(s.id) : null; } catch(e) { return null; }")
	private static native String selfIdImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return null;"
			+ "try { var s = a.self(); return s ? String(s.name) : null; } catch(e) { return null; }")
	private static native String selfNameImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return null;"
			+ "try { return String(a.serviceUrl()); } catch(e) { return null; }")
	private static native String serviceUrlImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return null;"
			+ "try { var v = a.lastError(); return (v === undefined || v === null) ? null : String(v); } catch(e) { return null; }")
	private static native String lastErrorImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return 0;"
			+ "try { return a.peerCount() | 0; } catch(e) { return 0; }")
	private static native int peerCountImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return 0;"
			+ "try { return a.openPeerCount() | 0; } catch(e) { return 0; }")
	private static native int openPeerCountImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return \"[]\";"
			+ "try { return JSON.stringify(a.peers()); } catch(e) { return \"[]\"; }")
	private static native String peersJsonImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return \"{}\";"
			+ "try { return a.snapshotJson(); } catch(e) { return \"{}\"; }")
	private static native String snapshotJsonImpl();

	@JSBody(params = { "peerId", "text" }, script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return false;"
			+ "try { return !!a.sendJson(peerId, text); } catch(e) { return false; }")
	private static native boolean sendTextImpl(String peerId, String text);

	@JSBody(params = { "peerId", "payloadJson" }, script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return false;"
			+ "try { var payload = payloadJson ? JSON.parse(payloadJson) : null; return !!a.invite(peerId, payload); } catch(e) { return false; }")
	private static native boolean inviteImpl(String peerId, String payloadJson);

	@JSBody(params = { "name" }, script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a) return false;"
			+ "try { return !!a.setName(name); } catch(e) { return false; }")
	private static native boolean setNameImpl(String name);

	@JSBody(params = { "handler" }, script = "var a = (typeof window !== \"undefined\") ? window.__eaglerLAN : null;"
			+ "if(!a || !a.onEvent) return false;"
			+ "try { a.onEvent(function(ev) { try { handler(JSON.stringify(ev)); } catch(e) {} }); return true; } catch(e) { return false; }")
	private static native boolean installEventHandlerImpl(DiscoveryEventHandler handler);

	public static boolean isInstalled() {
		return moduleInstalled();
	}

	public static boolean isServiceAvailable() {
		String state = getState();
		return "online".equals(state) || "probing".equals(state);
	}

	public static String getState() {
		try {
			return stateImpl();
		}catch(Throwable t) {
			return "error";
		}
	}

	public static String getStatus() {
		try {
			return statusImpl();
		}catch(Throwable t) {
			return "Automatic LAN discovery status unavailable";
		}
	}

	public static String getScope() {
		try {
			return scopeImpl();
		}catch(Throwable t) {
			return null;
		}
	}

	public static String getSelfId() {
		try {
			return selfIdImpl();
		}catch(Throwable t) {
			return null;
		}
	}

	public static String getSelfName() {
		try {
			return selfNameImpl();
		}catch(Throwable t) {
			return null;
		}
	}

	public static String getServiceUrl() {
		try {
			return serviceUrlImpl();
		}catch(Throwable t) {
			return null;
		}
	}

	public static String getLastError() {
		try {
			return lastErrorImpl();
		}catch(Throwable t) {
			return null;
		}
	}

	public static int getPeerCount() {
		try {
			return peerCountImpl();
		}catch(Throwable t) {
			return 0;
		}
	}

	public static int getOpenPeerCount() {
		try {
			return openPeerCountImpl();
		}catch(Throwable t) {
			return 0;
		}
	}

	public static String getPeersJson() {
		try {
			return peersJsonImpl();
		}catch(Throwable t) {
			return "[]";
		}
	}

	public static String getSnapshotJson() {
		try {
			return snapshotJsonImpl();
		}catch(Throwable t) {
			return "{}";
		}
	}

	public static String pollEventsJson() {
		String ret;
		synchronized(pendingEvents) {
			if(pendingEvents.isEmpty()) {
				return "[]";
			}
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			while(!pendingEvents.isEmpty()) {
				String next = pendingEvents.poll();
				if(next == null) {
					break;
				}
				if(!first) {
					sb.append(',');
				}
				first = false;
				sb.append(next);
			}
			ret = sb.append(']').toString();
		}
		return ret;
	}

	public static boolean sendText(String peerId, String text) {
		if(peerId == null || text == null) {
			return false;
		}
		try {
			return sendTextImpl(peerId, text);
		}catch(Throwable t) {
			return false;
		}
	}

	public static boolean invite(String peerId, String payloadJson) {
		if(peerId == null) {
			return false;
		}
		try {
			return inviteImpl(peerId, payloadJson);
		}catch(Throwable t) {
			return false;
		}
	}

	public static boolean setName(String name) {
		if(name == null || name.isEmpty()) {
			return false;
		}
		try {
			return setNameImpl(name);
		}catch(Throwable t) {
			return false;
		}
	}

	/**
	 * Call once from client boot: subscribes to the module's event stream and
	 * logs where discovery stands, so a run's log shows whether the automatic
	 * path is live. Failure is never fatal - the module is an optimization over
	 * the manual join-code flow, not a requirement.
	 */
	public static void announceAtBoot() {
		try {
			if(!handlerInstalled) {
				handlerInstalled = installEventHandlerImpl(eventJson -> {
					if(eventJson == null) {
						return;
					}
					synchronized(pendingEvents) {
						if(pendingEvents.size() >= MAX_PENDING_EVENTS) {
							pendingEvents.poll();
						}
						pendingEvents.add(eventJson);
					}
				});
			}
			if(!moduleInstalled()) {
				logger.info("Automatic LAN discovery: module not loaded on this page (manual join codes only)");
				return;
			}
			logger.info("{}", getStatus());
			if(!isServiceAvailable()) {
				logger.info("Automatic LAN discovery: no service on this origin; start it with \"node tools/lan-server.mjs\" to use the automatic path");
			}
		}catch(Throwable t) {
			logger.warn("Automatic LAN discovery: boot check failed ({})", t.toString());
		}
	}
}

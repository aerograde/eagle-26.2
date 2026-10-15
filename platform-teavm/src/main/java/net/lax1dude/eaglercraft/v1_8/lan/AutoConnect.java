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
 * Serverless automatic LAN connection, web view.
 *
 * <p>This is the game-side half of {@code target_teavm_wasm_gc/src/web/autoconnect.js},
 * the module that implements the AutoConnect plan: shared ICE credentials, a port
 * lottery pool, a subnet sweep, a peer-port exchange and a final peer connection
 * with no server, no signalling and no codes. The module runs on ordinary JS
 * timers before the client Wasm is compiled; this class only reads its state and
 * asks it to start, show a code or send a payload.
 *
 * <p>The code fallback is the second tier: after the plan's 20-second budget with
 * no peer the module raises {@code fallback}, the game asks for a 6-character
 * code, and both peers derive the same credentials from it (SHA-256) so the same
 * phases run synchronized. {@link #newCode()} shows one, {@link #enterCode(String)}
 * types one.
 *
 * <p>The mirror class with the same name in {@code :platform} is the desktop
 * stub; the platform classpath gives this class priority on the web target. Keep
 * both signatures identical.
 */
public final class AutoConnect {

	/**
	 * Version of the JS module this bridge speaks to. Must match
	 * {@code autoconnect.js}'s own VERSION, because {@link #moduleInstalled()}
	 * gates every call on an exact match - a stale number on either side silently
	 * switches the whole auto-connect tier off (the module loads, the LAN screen
	 * offers the entry, and nothing ever happens). The two halves agree on 4; the
	 * comparison below is built from this constant so the next bump cannot leave
	 * the literal behind again.
	 */
	public static final int VERSION = 4;

	private static final Logger logger = LogManager.getLogger("AutoConnect");

	/** Events delivered by the JS module, drained by {@link #pollEventsJson()}. */
	private static final ArrayDeque<String> pendingEvents = new ArrayDeque<>();
	private static final int MAX_PENDING_EVENTS = 256;

	private static boolean handlerInstalled = false;
	private static boolean startRequested = false;

	private AutoConnect() {
	}

	@JSFunctor
	public interface AutoConnectEventHandler extends JSObject {
		void onEvent(String eventJson);
	}

	// The comparison is built from VERSION, not spelled out: the literal 4 that
	// used to be here outlived the module's own bump to 8, and because this one
	// method gates every other call, the whole auto-connect tier went quiet
	// without a single error - the LAN screen still offered the entry and the
	// bridge still answered, it just never started anything.
	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "return !!(a && a.version === " + VERSION + ");")
	private static native boolean moduleInstalled();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return \"idle\";"
			+ "try { return String(a.state()); } catch(e) { return \"error\"; }")
	private static native String stateImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return null;"
			+ "try { return String(a.phase()); } catch(e) { return null; }")
	private static native String phaseImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return \"Auto-connect is not available on this page\";"
			+ "try { return String(a.status()); } catch(e) { return \"Auto-connect status unavailable\"; }")
	private static native String statusImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a || !a.fallback) return false;"
			+ "try { return !!a.fallback().active; } catch(e) { return false; }")
	private static native boolean fallbackImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return null;"
			+ "try { var c = a.code(); return (c === undefined || c === null) ? null : String(c); } catch(e) { return null; }")
	private static native String codeImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a || !a.codeMode) return false;"
			+ "try { return !!a.codeMode(); } catch(e) { return false; }")
	private static native boolean codeModeImpl();

	@JSBody(params = { "optsJson" }, script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return false;"
			+ "try { return !!a.startJson(optsJson); } catch(e) { return false; }")
	private static native boolean startImpl(String optsJson);

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return false;"
			+ "try { a.stop(); return true; } catch(e) { return false; }")
	private static native boolean stopImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return null;"
			+ "try { return String(a.newCode()); } catch(e) { return null; }")
	private static native String newCodeImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return null;"
			+ "try { var s = a.self(); return s ? String(s.name) : null; } catch(e) { return null; }")
	private static native String selfNameImpl();

	@JSBody(params = { "code" }, script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a || !code) return false;"
			+ "try { return !!a.enterCode(code); } catch(e) { return false; }")
	private static native boolean enterCodeImpl(String code);

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return \"[]\";"
			+ "try { return JSON.stringify(a.peers()); } catch(e) { return \"[]\"; }")
	private static native String peersJsonImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return \"{}\";"
			+ "try { return a.snapshotJson(); } catch(e) { return \"{}\"; }")
	private static native String snapshotJsonImpl();

	@JSBody(script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return \"[]\";"
			+ "try { return a.pollEventsJson(); } catch(e) { return \"[]\"; }")
	private static native String pollEventsJsonImpl();

	@JSBody(params = { "text" }, script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a) return false;"
			+ "try { return !!a.sendJson(text); } catch(e) { return false; }")
	private static native boolean sendTextImpl(String text);

	@JSBody(params = { "handler" }, script = "var a = (typeof window !== \"undefined\") ? window.__eaglerAC : null;"
			+ "if(!a || !a.onEvent) return false;"
			+ "try { a.onEvent(function(ev) { try { handler(JSON.stringify(ev)); } catch(e) {} }); return true; } catch(e) { return false; }")
	private static native boolean installEventHandlerImpl(AutoConnectEventHandler handler);

	public static boolean isInstalled() {
		return moduleInstalled();
	}

	/** idle | listening | scanning | linking | fallback | connected | failed | stopped */
	public static String getState() {
		try {
			return stateImpl();
		}catch(Throwable t) {
			return "error";
		}
	}

	/** The plan phase the engine is in, e.g. "phase 1 - discovery". */
	public static String getPhase() {
		try {
			return phaseImpl();
		}catch(Throwable t) {
			return null;
		}
	}

	/** One line a player could read. */
	public static String getStatus() {
		try {
			return statusImpl();
		}catch(Throwable t) {
			return "Auto-connect status unavailable";
		}
	}

	/** True when the 20-second budget passed with no peer, i.e. ask for a code. */
	public static boolean isFallback() {
		try {
			return fallbackImpl();
		}catch(Throwable t) {
			return false;
		}
	}

	/** The 6-character code in use, null when the shared constants are in use. */
	public static String getCode() {
		try {
			return codeImpl();
		}catch(Throwable t) {
			return null;
		}
	}

	public static boolean isCodeMode() {
		try {
			return codeModeImpl();
		}catch(Throwable t) {
			return false;
		}
	}

	/**
	 * Starts the phases and subscribes to the module's events. Idempotent: the
	 * module returns at once when it is already running, and this bridge only
	 * crosses into JS once. The name is what other players see.
	 */
	public static void start(String name) {
		if(!isInstalled() || startRequested) {
			return;
		}
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
			startRequested = true;
			StringBuilder opts = new StringBuilder("{");
			if(name != null && !name.isEmpty()) {
				opts.append("\"name\":\"").append(escape(name)).append('"');
			}
			opts.append('}');
			startImpl(opts.toString());
			logger.info("Auto-connect started: {}", getStatus());
		}catch(Throwable t) {
			logger.warn("Auto-connect could not start ({})", t.toString());
		}
	}

	/** Closes every connection the module owns. */
	public static void stop() {
		startRequested = false;
		try {
			stopImpl();
		}catch(Throwable t) {
			logger.warn("Auto-connect could not stop ({})", t.toString());
		}
	}

	/**
	 * Generates a 6-character code and applies it, so this player shows it and
	 * the other types it into their screen.
	 */
	public static String newCode() {
		try {
			return newCodeImpl();
		}catch(Throwable t) {
			return null;
		}
	}

	/** The name other players see for this client, null when the module is not running. */
	public static String getSelfName() {
		try {
			return selfNameImpl();
		}catch(Throwable t) {
			return null;
		}
	}

	/** Applies the other player's code; false when it is not 6 valid characters. */
	public static boolean enterCode(String code) {
		if(code == null || code.isEmpty()) {
			return false;
		}
		try {
			return enterCodeImpl(code);
		}catch(Throwable t) {
			return false;
		}
	}

	/** Peers the module found or connected: [{id,address,port,state}]. */
	public static String getPeersJson() {
		try {
			return peersJsonImpl();
		}catch(Throwable t) {
			return "[]";
		}
	}

	/** Full state snapshot as a JSON object, for screens and diagnostics. */
	public static String getSnapshotJson() {
		try {
			return snapshotJsonImpl();
		}catch(Throwable t) {
			return "{}";
		}
	}

	/** Drain queued events (peer-found / connected / message / fallback ...) as JSON. */
	public static String pollEventsJson() {
		StringBuilder sb = new StringBuilder("[");
		synchronized(pendingEvents) {
			if(pendingEvents.isEmpty()) {
				return "[]";
			}
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
		}
		return sb.append(']').toString();
	}

	/** Send a string over the peer data channel; false when it is not open. */
	public static boolean sendText(String text) {
		if(text == null) {
			return false;
		}
		try {
			return sendTextImpl(text);
		}catch(Throwable t) {
			return false;
		}
	}

	/** Log the boot-time state once; a no-op off the web target. */
	public static void announceAtBoot() {
		try {
			if(!moduleInstalled()) {
				logger.info("Auto-connect: module not loaded on this page (manual join codes only)");
				return;
			}
			logger.info("{}{}", getPhase() == null ? "" : getPhase() + " - ", getStatus());
		}catch(Throwable t) {
			logger.warn("Auto-connect: boot check failed ({})", t.toString());
		}
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}

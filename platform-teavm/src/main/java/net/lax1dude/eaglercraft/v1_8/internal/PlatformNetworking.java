/*
 * Copyright (c) 2022-2026 lax1dude and contributors.
 *
 * Browser implementation of the platform networking seam. Web browsers cannot
 * open arbitrary TCP sockets, so multiplayer uses a binary WebSocket whose
 * peer exposes a constrained TCP bridge.
 */
package net.lax1dude.eaglercraft.v1_8.internal;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMDirectTCPClient;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.LegacyLANWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.DirectLANWebSocketClient;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSFunctor;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;

public class PlatformNetworking {

	/** Keep relay URL validation aligned with the browser's mixed-content rules. */
	@JSBody(params = { "socketURI" }, script = """
		var value = typeof socketURI === 'string' ? socketURI.trim() : '';
		if(!/^ws:\\/\\//i.test(value)) return true;
		var page = (globalThis.location && globalThis.location.protocol || '').toLowerCase();
		if(page !== 'https:') return true;
		try {
			var host = new URL(value).hostname.toLowerCase().replace(/^\\[|\\]$/g, '');
			if(host === 'localhost' || host.endsWith('.localhost') || host === '::1') return true;
			var match = /^127\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$/.exec(host);
			if(!match) return false;
			for(var i = 1; i < match.length; ++i) {
				if(Number(match[i]) > 255) return false;
			}
			return true;
		} catch(e) { return false; }
		""")
	public static native boolean isInsecureWebSocketAllowed(String socketURI);

	public static IWebSocketClient openWebSocket(String socketURI) {
		try {
			return openWebSocketUnsafe(socketURI);
		} catch (Throwable t) {
			return null;
		}
	}

	public static IWebSocketClient openWebSocketUnsafe(String socketURI) {
		if (socketURI == null) {
			throw new IllegalArgumentException("WebSocket URI is null");
		}
		String lower = socketURI.toLowerCase();
		if(lower.startsWith(LegacyLANWebSocketClient.URI_PREFIX)) {
			return new LegacyLANWebSocketClient(socketURI);
		}
		if(lower.startsWith(DirectLANWebSocketClient.URI_PREFIX)) {
			return new DirectLANWebSocketClient(socketURI);
		}
		if (!lower.startsWith("ws://") && !lower.startsWith("wss://")) {
			throw new IllegalArgumentException("Expected ws:// or wss:// URI");
		}
		if (!isInsecureWebSocketAllowed(socketURI)) {
			throw new IllegalArgumentException("HTTPS pages require a wss:// relay");
		}
		return new TeaVMWebSocketClient(socketURI);
	}

	public static IWebSocketClient openWebSocketImpl(String socketURI) {
		return openWebSocketUnsafe(socketURI);
	}

	// Wispcraft's UMD bundle exports its live endpoint and settings UI. Read the
	// endpoint on each connection so changing it does not require a page reload.
	@JSBody(script = "var w = globalThis.wispcraft; return !!w && typeof w.showSettingsUI === 'function';")
	public static native boolean isWispcraftLoaded();

	@JSBody(script = "var w = globalThis.wispcraft; if(!w || typeof w.wispUrl !== 'string') return null; try { var u = new URL(w.wispUrl); if((u.protocol !== 'ws:' && u.protocol !== 'wss:') || u.username || u.password || u.hash) return null; return u.href; } catch(e) { return null; }")
	public static native String getWispcraftWispURL();

	@JSBody(script = "var w = globalThis.wispcraft; if(w && typeof w.showSettingsUI === 'function') w.showSettingsUI();")
	public static native void openWispcraftSettings();

	// Only public identity crosses into Java. Tokens stay in the injected script's
	// live auth store and are never persisted by this client or put in diagnostics.
	@Async
	public static native String getWispcraftAccountProfile();

	private static void getWispcraftAccountProfile(AsyncCallback<String> callback) {
		getWispcraftAccountProfile0(callback::complete);
	}

	@JSBody(params = { "callback" }, script = """
		var w = globalThis.wispcraft, a = w && w.authstore;
		function result(status, profile) {
			callback(JSON.stringify(profile ? {status:status,id:profile.id,name:profile.name} : {status:status}));
		}
		if (!w || !a) { result('unavailable'); return; }
		delete w.__eagler26PreparedAccount;
		var token = a.yggToken;
		if (typeof token !== 'string' || !token) { result(a.user ? 'pending' : 'signed_out'); return; }
		var done = false, timer;
		var controller = typeof AbortController === 'function' ? new AbortController() : null;
		function finish(status, profile) {
			if (done) return;
			done = true; clearTimeout(timer); token = null; result(status, profile);
		}
		timer = setTimeout(function() {
			if (controller) controller.abort();
			finish('unreachable');
		}, 20000);
		Promise.resolve().then(function() {
			return globalThis.fetch('https://api.minecraftservices.com/minecraft/profile', {
				headers:{Authorization:'Bearer ' + token}, credentials:'omit', redirect:'error',
				signal:controller ? controller.signal : undefined
			});
		}).then(function(response) {
			if (done) return null;
			if (!response || response.status !== 200) { finish('invalid'); return null; }
			return response.json();
		}).then(function(profile) {
			if (done) return;
			if (globalThis.wispcraft !== w || w.authstore !== a || a.yggToken !== token) {
				finish('pending'); return;
			}
			var id = profile && typeof profile.id === 'string' ? profile.id.replace(/-/g, '').toLowerCase() : '';
			if (!/^[0-9a-f]{32}$/.test(id) || typeof profile.name !== 'string' || !/^[A-Za-z0-9_]{1,16}$/.test(profile.name)) {
				finish('invalid'); return;
			}
			// A one-use closure proves the token still belongs to this verified UUID.
			// It exports neither the token nor any account credentials to Java.
			var preparedToken = token;
			Object.defineProperty(w, '__eagler26PreparedAccount', {configurable:true, value:function(expected) {
				return expected === id && globalThis.wispcraft === w && w.authstore === a && a.yggToken === preparedToken;
			}});
			finish('ready', {id:id,name:profile.name});
		}, function() { finish('unreachable'); });
		""")
	private static native void getWispcraftAccountProfile0(JoinResult callback);

	/** Suspends only the login green thread; null means the session service accepted the join. */
	@Async
	public static native String joinWispcraftServer(String uuid, String digest);

	private static void joinWispcraftServer(String uuid, String digest, AsyncCallback<String> callback) {
		joinWispcraftServer0(uuid, digest, callback::complete);
	}

	@JSFunctor
	private interface JoinResult extends JSObject {
		void complete(String error);
	}

	@JSBody(params = { "uuid", "digest", "callback" }, script = """
		var w = globalThis.wispcraft, a = w && w.authstore;
		uuid = typeof uuid === 'string' ? uuid.replace(/-/g, '').toLowerCase() : '';
		var prepared = w && w.__eagler26PreparedAccount;
		if (w) delete w.__eagler26PreparedAccount;
		function identityMatches() {
			return typeof prepared === 'function' && prepared(uuid);
		}
		if (!/^[0-9a-f]{32}$/.test(uuid) || !/^-?[0-9a-f]{1,40}$/.test(digest)) {
			callback('Invalid Minecraft session join request.'); return;
		}
		if (!identityMatches() || typeof a.yggToken !== 'string' || !a.yggToken) {
			callback('The selected Wispcraft account changed or is not ready. Reconnect after signing in.'); return;
		}
		var token = a.yggToken, done = false, timer;
		var controller = typeof AbortController === 'function' ? new AbortController() : null;
		function finish(message) {
			if (done) return;
			done = true; clearTimeout(timer); token = null; callback(message);
		}
		timer = setTimeout(function() {
			if (controller) controller.abort();
			finish('Minecraft session authentication timed out. Check the Wisp endpoint and reconnect.');
		}, 20000);
		Promise.resolve().then(function() {
			return globalThis.fetch('https://sessionserver.mojang.com/session/minecraft/join', {
				method:'POST', headers:{'Content-Type':'application/json'}, credentials:'omit', redirect:'error',
				signal:controller ? controller.signal : undefined,
				body:JSON.stringify({selectedProfile:uuid,serverId:digest,accessToken:token})
			});
		}).then(function(response) {
			if (done) return;
			if (!identityMatches() || a.yggToken !== token) {
				finish('The selected Wispcraft account changed during authentication. Reconnect.'); return;
			}
			if (response && response.status === 204) { finish(null); return; }
			if (response && (response.status === 401 || response.status === 403)) {
				finish('Minecraft rejected this Wispcraft session. Sign in again in Wisp Settings.'); return;
			}
			finish('Minecraft session authentication failed. Try again after checking Wisp Settings.');
		}, function() {
			finish('Could not reach Minecraft session authentication. Check the Wisp endpoint and reconnect.');
		});
		""")
	private static native void joinWispcraftServer0(String uuid, String digest, JoinResult callback);

	@JSBody(script = "return typeof globalThis.location === 'object' && globalThis.location !== null && globalThis.location.protocol === 'isolated-app:';")
	public static native boolean isIsolatedWebApp();

	@JSBody(script = "var value = globalThis.eaglercraftXIwaBundleURL; if(typeof value !== 'string' || value.trim() === '') return null; try { return new URL(value, globalThis.location.href).href; } catch(e) { return null; }")
	public static native String getIsolatedWebAppBundleURL();

	@JSBody(script = "return typeof globalThis.TCPSocket === 'function';")
	public static native boolean supportsDirectTCP();

	public static IWebSocketClient openDirectTCP(String host, int port) {
		if (!supportsDirectTCP()) {
			throw new UnsupportedOperationException("Direct TCP requires an installed Isolated Web App");
		}
		return new TeaVMDirectTCPClient(host, port);
	}
}

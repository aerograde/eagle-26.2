/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 */
package net.lax1dude.eaglercraft.v1_8.sp.server.internal.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Server-only worker protocol root. It waits for the normal role metadata, then
 * hands the realm to {@link ServerWorkerHost}. Unlike the shared worker root it
 * has no reachable mesh compiler, client renderer, UI or audio graph.
 */
public final class ServerOnlyWorkerMain {

	@JSFunctor
	private interface MetaHandler extends JSObject {
		void onMeta(String meta);
	}

	private static boolean started;

	private ServerOnlyWorkerMain() {
	}

	@JSBody(params = { "meta" }, script = "self.onmessage = function(e) {"
			+ " if (e.data && typeof e.data.meta === 'string') meta(e.data.meta); };" )
	private static native void registerInitialHandler(MetaHandler meta);

	@JSBody(params = { "str" }, script = "self.postMessage({ meta: str });")
	private static native void postMeta(String str);

	public static void workerMain() {
		if (started) {
			return;
		}
		started = true;
		registerInitialHandler(ServerOnlyWorkerMain::onMeta);
		postMeta("server-worker:image-booted");
	}

	private static void onMeta(String meta) {
		if (meta != null && meta.startsWith("role:server")) {
			ServerWorkerHost.boot(meta);
		}
	}
}

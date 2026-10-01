/*
 * Copyright (c) 2023-2024 lax1dude. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 */

// 26.2 adaptations (Phase 3.1, see report):
//  - PlatformWebRTC.serverLANPeerPassIPC dropped (WebRTC is on the DROP list), TODO(3.2)
//  - PlatformRuntime.downloadRemoteURI stubbed (PlatformRuntime is a later increment), TODO(3.2)
//  - TeaVMUtils.tryResolveClassesSource[Inline] stubbed (needs ClassesJSLocator), TODO(3.2)
//  - ClientMain crash overlay calls stubbed (ClientMain is a later increment), TODO(3.2)
//  - TeaVMUtils wrap/unwrap -> 0.13 Int8Array copyToJavaArray/fromJavaArray

package net.lax1dude.eaglercraft.v1_8.sp.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.dom.events.ErrorEvent;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.html.HTMLScriptElement;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.workers.Worker;

import net.lax1dude.eaglercraft.v1_8.internal.IPCPacketData;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformWebRTC;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMBlobURLManager;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMClientConfigAdapter;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.LegacyLANHost;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.server.internal.teavm.SingleThreadWorker;
import net.lax1dude.eaglercraft.v1_8.sp.server.ServerWorkerProtocol;

public class ClientPlatformSingleplayer {

	private static final Logger logger = LogManager.getLogger("ClientPlatformSingleplayer");

	private static final LinkedList<IPCPacketData> messageQueue = new LinkedList<>();

	@JSBody(params = {}, script = "return (typeof eaglercraftXClientScriptElement !== \"undefined\") ? eaglercraftXClientScriptElement : null;")
	private static native JSObject loadIntegratedServerSourceOverride();

	@JSBody(params = {}, script = "return (typeof eaglercraftXClientScriptURL === \"string\") ? eaglercraftXClientScriptURL : null;")
	private static native String loadIntegratedServerSourceOverrideURL();

	@JSBody(params = {}, script = "try{throw new Error();}catch(ex){return ex.stack||null;}return null;")
	private static native String loadIntegratedServerSourceStack();

	@JSBody(params = { "csc" }, script = "if(typeof csc.src === \"string\" && csc.src.length > 0) return csc.src; else return null;")
	private static native String loadIntegratedServerSourceURL(JSObject scriptTag);

	@JSBody(params = { "csc", "tail" }, script = "var cscText = csc.text;"
			+ "if(typeof cscText === \"string\" && cscText.length > 0) return new Blob([cscText, tail], { type: \"text/javascript;charset=utf8\" });"
			+ "else return null;")
	private static native JSObject loadIntegratedServerSourceInline(JSObject scriptTag, String tail);

	@JSBody(params = { "csc" }, script = "var cscText = csc.text;"
			+ "if(typeof cscText === \"string\" && cscText.length > 0) return cscText;"
			+ "else return null;")
	private static native String loadIntegratedServerSourceInlineStr(JSObject scriptTag);

	// Wired (Seam B runtime): delegate to the real synchronous @Async downloader in
	// PlatformRuntime (fetch, XHR fallback) — the same one EPKDownloadHelper uses. This
	// is what lets loadIntegratedServerSource() build the classes.js Blob URL for BOTH the
	// integrated-server worker (serverWorker mode) and the mesh worker pool
	// (createMeshWorkerScriptURLTeaVM). Returns null on any download failure, so the callers
	// keep their existing null-fallbacks (server worker -> single-thread; mesh pool -> XHR).
	private static ArrayBuffer downloadRemoteURI(String uri, boolean forceCache) {
		if(uri == null) {
			return null;
		}
		try {
			ArrayBuffer buf = PlatformRuntime.downloadRemoteURI(uri, forceCache);
			if(buf == null) {
				logger.error("downloadRemoteURI returned null for: {}", truncateURL(uri));
			}
			return buf;
		}catch(Throwable t) {
			logger.error("downloadRemoteURI failed for {}: {}", truncateURL(uri), t.toString());
			return null;
		}
	}

	// TODO(3.2): upstream calls TeaVMUtils.tryResolveClassesSource[Inline] which needs
	// ClassesJSLocator; stubbed until TeaVMUtils is ported
	private static String tryResolveClassesSource() {
		return null;
	}

	private static HTMLScriptElement tryResolveClassesSourceInline() {
		return null;
	}

	private static String integratedServerSource = null;
	private static String integratedServerSourceOriginalURL = null;
	private static boolean serverSourceLoaded = false;
	private static boolean isSingleThreadMode = false;

	private static Worker workerObj = null;
	private static int workerErrorProbeSequence = 0;
	private static int pendingWorkerErrorProbe = 0;
	private static String pendingWorkerErrorReport = null;
	private static final int WORKER_ERROR_PROBE_TIMEOUT_MS = 15000;

	@JSFunctor
	private static interface WorkerBinaryPacketHandler extends JSObject {
		public void onMessage(String channel, ArrayBuffer buf);
	}

	@JSBody(params = { "w", "wb" }, script = "w.addEventListener(\"message\", function(o) { wb(o.data.ch, o.data.dat); });")
	private static native void registerPacketHandler(Worker w, WorkerBinaryPacketHandler wb);

	@JSBody(params = { "w", "ch", "dat" }, script = "w.postMessage({ ch: ch, dat : dat });")
	private static native void sendWorkerPacket(Worker w, String channel, ArrayBuffer arr);

	// ---- INTEGRATED SERVER WORKER (serverWorker mode) ----
	// Flag: eaglercraftXOpts.serverWorker === true OR ?serverworker (default OFF this
	// build). Falls back to single-thread mode when off or Worker is unavailable.
	@JSBody(params = {}, script = "try {"
			+ " if (typeof location !== \"undefined\" && location.search && location.search.indexOf(\"serverworker\") >= 0) return true;"
			+ " if (typeof eaglercraftXOpts !== \"undefined\" && eaglercraftXOpts && eaglercraftXOpts.serverWorker === true) return true;"
			+ " return false; } catch(e) { return false; }")
	private static native boolean isServerWorkerFlagJS();

	@JSBody(params = {}, script = "return (typeof Worker !== \"undefined\");")
	private static native boolean workerSupportedJS();

	@JSBody(params = { "w", "str" }, script = "w.postMessage({ meta: str });")
	private static native void postMetaToWorker(Worker w, String str);

	@JSBody(params = { "evt" }, script = "try {"
			+ " var err = evt && evt.error;"
			+ " var msg = evt && typeof evt.message === \"string\" ? evt.message : \"Integrated server worker error\";"
			+ " var out = msg;"
			+ " if (err && typeof err.stack === \"string\" && err.stack.length) out += \"\\n\" + err.stack;"
			+ " else if (evt && typeof evt.filename === \"string\" && evt.filename.length)"
			+ "   out += \"\\n\" + evt.filename + \":\" + (evt.lineno || 0) + \":\" + (evt.colno || 0);"
			+ " return out.length > 8192 ? out.substring(0, 8192) + \"\\n...(truncated)\" : out;"
			+ "} catch(e) { return \"Integrated server worker error\"; }")
	private static native String describeWorkerError(ErrorEvent evt);

	@JSBody(params = { "evt" }, script = "try { if (evt && evt.preventDefault) evt.preventDefault(); } catch(e) {}")
	private static native void suppressWorkerErrorDefault(ErrorEvent evt);

	@JSFunctor
	private static interface TimeoutCallback extends JSObject {
		void run();
	}

	@JSBody(params = { "cb", "ms" }, script = "if (typeof setTimeout !== \"undefined\") setTimeout(cb, ms);")
	private static native void scheduleTimeout(TimeoutCallback cb, int ms);

	// page -> worker vanilla-packet batch, transferring the backing buffer (zero-copy).
	// H2: dat is neutered after this call — callers must not retain it.
	@JSBody(params = { "w", "ch", "dat" }, script = "w.postMessage({ ch: ch, dat : dat }, [dat]);")
	private static native void sendWorkerPacketTransfer(Worker w, String channel, ArrayBuffer arr);

	@JSFunctor
	private static interface WorkerMetaHandler extends JSObject {
		public void onMeta(String meta);
	}

	// meta-aware inbound splitter for server-worker mode: {ch,dat} -> binary router,
	// {meta:string} -> control-string handler (role-ack / booted / boot-error).
	@JSBody(params = { "w", "wb", "wm" }, script = "w.addEventListener(\"message\", function(o) {"
			+ " if (o.data && o.data.eaglerFsRequest === true) return;"
			+ " if (o.data && typeof o.data.meta === \"string\") { wm(o.data.meta); }"
			+ " else { wb(o.data.ch, o.data.dat); } });")
	private static native void registerServerWorkerHandler(Worker w, WorkerBinaryPacketHandler wb, WorkerMetaHandler wm);

	private static boolean serverWorkerMode = false;
	private static Runnable closeWorkerFilesystem;

	public static boolean isServerWorkerMode() {
		return serverWorkerMode;
	}

	/** serverWorker mode REQUESTED (flag + Worker support), resolvable before the worker boots.
	 *  Mirrors the exact gate {@link #startIntegratedServer(boolean)} uses to pick the worker
	 *  path, so the client can persist level.dat to the shared VFS ahead of the worker build. */
	public static boolean isServerWorkerModeRequested() {
		return isServerWorkerFlagJS() && workerSupportedJS();
	}

	/** page -> worker vanilla-packet data batch (transferable). Used by the client-side
	 *  server bridge when serverWorker mode is active; no-op otherwise. */
	public static void sendDataBatch(String channel, ArrayBuffer batch) {
		if(serverWorkerMode && workerObj != null) {
			sendWorkerPacketTransfer(workerObj, channel, batch);
		}
	}

	/** Game-facing overload (Phase 3.4 seam c): copy the Java batch into a fresh
	 *  ArrayBuffer and transfer it to the worker. The copy (not the Java array) is
	 *  neutered by the transfer, so the caller may keep using its byte[]. */
	public static void sendDataBatch(String channel, byte[] batch) {
		if(serverWorkerMode && workerObj != null && batch != null) {
			sendWorkerPacketTransfer(workerObj, channel, Int8Array.fromJavaArray(batch).getBuffer());
		}
	}

	/** The current direct-connect mode: 0 = off, 1 = hosting, 2 = joining. */
	private static volatile int directMode = 0;

	/** Host: mint the offer code for the next guest. */
	public static String directHostNewInviteCode() {
		return PlatformWebRTC.directRoomNewInvite();
	}

	/** Host: apply the guest's answer code; the room then arms the next invite. */
	public static void directHostCompleteAnswer(String answerCode) {
		PlatformWebRTC.directRoomAcceptAnswer(answerCode);
	}

	/** Host: true while a direct-connect room is open. */
	public static boolean isDirectRoomHosting() {
		return directMode == 1 && PlatformWebRTC.directRoomIsHosting();
	}

	/** Host: links in the room, connected or still handshaking. */
	public static int getDirectGuestCount() {
		return directMode == 1 ? PlatformWebRTC.directRoomGuestCount() : 0;
	}

	/** Host: links whose data channel is actually open. */
	public static int getDirectConnectedGuestCount() {
		return directMode == 1 ? PlatformWebRTC.directRoomConnectedGuestCount() : 0;
	}

	public static String directJoinCreateAnswerCode(String offerCode) {
		closeDirectConnect();
		String code = PlatformWebRTC.directGuestAcceptOffer(offerCode);
		directMode = 2;
		return code;
	}

	public static boolean isDirectJoinConnected() {
		return directMode == 2 && PlatformWebRTC.directGuestChannelOpen();
	}

	public static void closeDirectConnect() {
		boolean wasDirect = directMode != 0;
		directMode = 0;
		PlatformWebRTC.directCloseSession();
		if(wasDirect && serverWorkerMode && workerObj != null) {
			sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
					"close-all".getBytes(StandardCharsets.UTF_8));
		}
	}

	/** Host: turn the room's peer lifecycle into the worker's ~!LAN peer map
	 *  commands, one entry per guest link. */
	private static void updateDirectConnect() {
		if(directMode != 1) {
			return;
		}
		PlatformWebRTC.DirectRoomEvent event;
		while((event = PlatformWebRTC.directRoomPollEvent()) != null) {
			boolean open = event.kind == PlatformWebRTC.DirectRoomEvent.PEER_OPEN;
			sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
					((open ? "open:" : "close:") + event.peerId).getBytes(StandardCharsets.UTF_8));
			if(open) {
				logger.info("Direct connect guest {} joined (no relay involved)", event.peerId);
			}else {
				logger.info("Direct connect guest {} left", event.peerId);
			}
		}
	}

	private static IWebSocketClient lanRelaySocket = null;
	private static String lanRelayCode = null;
	private static String lanRelayError = null;
	private static boolean lanLegacyMode = false;
	private static final int LAN_RELAY_PAUSE_BYTES = 8 * 1024 * 1024;
	private static final int LAN_RELAY_RESUME_BYTES = 2 * 1024 * 1024;
	private static final int LAN_RELAY_ABORT_BYTES = 32 * 1024 * 1024;
	private static final java.util.Set<String> lanRelayPausedPeers = new java.util.HashSet<>();

	public static boolean openLANRelay(String hostURI, int timeoutMillis) {
		closeLANRelay();
		if(hostURI != null && hostURI.startsWith("eagler-direct:")) {
			// Direct connect: no relay socket. The world publishes through the
			// ordinary worker ConfigureLAN and the room mints one invite code
			// per guest, managed by the Direct Connect host screen.
			directMode = 1;
			lanRelayError = null;
			try {
				lanRelayCode = PlatformWebRTC.directRoomOpen();
			}catch(Throwable t) {
				logger.error("Could not open the direct connect room");
				logger.error(t);
				lanRelayCode = null;
				lanRelayError = t.getMessage() != null ? t.getMessage() : "Could not create the direct connect invite";
				directMode = 0;
				PlatformWebRTC.directCloseSession();
			}
			return lanRelayCode != null;
		}
		if(!serverWorkerMode || workerObj == null) {
			lanRelayError = "The integrated server Web Worker is not running";
			return false;
		}
		if(hostURI != null && hostURI.startsWith(LegacyLANHost.URI_PREFIX)) {
			lanLegacyMode = true;
			boolean opened = LegacyLANHost.open(hostURI, timeoutMillis);
			lanRelayCode = LegacyLANHost.getCode();
			lanRelayError = LegacyLANHost.getError();
			if(!opened) {
				lanLegacyMode = false;
			}
			return opened;
		}
		try {
			IWebSocketClient socket = PlatformNetworking.openWebSocket(hostURI);
			if(socket == null) {
				lanRelayError = "Could not create the LAN relay WebSocket";
				return false;
			}
			socket.setEnableStringFrames(true);
			socket.setEnableBinaryFrames(true);
			if(!socket.connectBlocking(timeoutMillis)) {
				socket.close();
				lanRelayError = "Could not connect to the LAN relay";
				return false;
			}
			lanRelaySocket = socket;
			long deadline = net.lax1dude.eaglercraft.v1_8.EagRuntime.steadyTimeMillis()
					+ Math.max(1000, timeoutMillis);
			while(lanRelayCode == null && lanRelaySocket != null && lanRelaySocket.isOpen()
					&& net.lax1dude.eaglercraft.v1_8.EagRuntime.steadyTimeMillis() < deadline) {
				updateLANRelay();
				if(lanRelayCode == null) {
					net.lax1dude.eaglercraft.v1_8.EagUtils.sleep(10);
				}
			}
			if(lanRelayCode == null) {
				if(lanRelayError == null) {
					lanRelayError = "The relay did not return a LAN join code";
				}
				closeLANRelaySocket(false);
				return false;
			}
			logger.info("Browser LAN relay room opened with code {}", lanRelayCode);
			return true;
		}catch(Throwable t) {
			lanRelayError = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
			closeLANRelaySocket(false);
			logger.error("Could not open browser LAN relay: {}", lanRelayError);
			return false;
		}
	}

	public static void updateLANRelay() {
		if(directMode != 0) {
			updateDirectConnect();
			return;
		}
		if(lanLegacyMode) {
			LegacyLANHost.update();
			lanRelayCode = LegacyLANHost.getCode();
			lanRelayError = LegacyLANHost.getError();
			return;
		}
		IWebSocketClient socket = lanRelaySocket;
		if(socket == null) {
			return;
		}
		IWebSocketFrame frame;
		while((frame = socket.getNextFrame()) != null) {
			if(frame.isString()) {
				handleLANRelayControl(frame.getString());
			}else {
				handleLANRelayBinary(frame.getByteArray());
			}
		}
		int buffered = socket.getBufferedAmount();
		if(buffered <= LAN_RELAY_RESUME_BYTES && !lanRelayPausedPeers.isEmpty()) {
			for(String peer : new java.util.ArrayList<>(lanRelayPausedPeers)) {
				sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
						("resume:" + peer).getBytes(StandardCharsets.UTF_8));
			}
			lanRelayPausedPeers.clear();
		}
		if(socket.isClosed()) {
			if(lanRelayError == null) {
				lanRelayError = formatLANRelayClose(socket, "The LAN relay connection closed");
			}
			closeLANRelaySocket(true);
		}
	}

	public static boolean isLANRelayOpen() {
		if(directMode == 1) {
			return PlatformWebRTC.directRoomConnectedGuestCount() > 0;
		}
		if(directMode == 2) {
			return PlatformWebRTC.directGuestChannelOpen();
		}
		if(lanLegacyMode) {
			return LegacyLANHost.isOpen();
		}
		return lanRelaySocket != null && lanRelaySocket.isOpen() && lanRelayCode != null;
	}

	public static String getLANRelayCode() {
		if(directMode == 1) {
			// The room owns the invite codes; each guest consumes one.
			return PlatformWebRTC.directRoomInviteCode();
		}
		return lanRelayCode;
	}

	public static String getLANRelayError() {
		return lanRelayError;
	}

	public static void closeLANRelay() {
		if(directMode != 0) {
			closeDirectConnect();
		}
		if(LegacyLANHost.isOpen()) {
			logger.error("Closing an active legacy LAN relay; tracing the caller");
			logger.error(new Throwable("Legacy LAN relay close caller"));
		}
		LegacyLANHost.close();
		lanLegacyMode = false;
		closeLANRelaySocket(true);
		lanRelayCode = null;
		lanRelayError = null;
	}

	private static void closeLANRelaySocket(boolean notifyWorker) {
		IWebSocketClient socket = lanRelaySocket;
		lanRelaySocket = null;
		lanRelayCode = null;
		lanRelayPausedPeers.clear();
		if(socket != null) {
			try {
				socket.clearFrames();
				socket.close();
			}catch(Throwable t) {
			}
		}
		if(notifyWorker && serverWorkerMode && workerObj != null) {
			sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
					"close-all".getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void handleLANRelayControl(String json) {
		String type = jsonField(json, "type");
		if("hosted".equals(type)) {
			String code = jsonField(json, "code");
			if(isValidLANCode(code)) {
				lanRelayCode = code;
			}
			}else if("peer-close".equals(type)) {
				String peer = jsonField(json, "peer");
				if(isValidLANPeer(peer)) {
					sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
							("close:" + peer).getBytes(StandardCharsets.UTF_8));
				}
			}else if("peer-open".equals(type)) {
				String peer = jsonField(json, "peer");
				if(isValidLANPeer(peer)) {
					sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
							("open:" + peer).getBytes(StandardCharsets.UTF_8));
				}
			}else if("error".equals(type)) {
				lanRelayError = jsonField(json, "message");
			}
	}

	private static boolean isValidLANCode(String code) {
		if(code == null || code.length() < 6 || code.length() > 10) {
			return false;
		}
		for(int i = 0; i < code.length(); ++i) {
			char c = code.charAt(i);
			if("ABCDEFGHJKLMNPQRSTUVWXYZ23456789".indexOf(c) < 0) {
				return false;
			}
		}
		return true;
	}

	private static void handleLANRelayBinary(byte[] frame) {
		if(frame == null || frame.length < 3 || frame[0] != 1) {
			lanRelayError = "The relay sent an invalid LAN frame";
			closeLANRelaySocket(true);
			return;
		}
		int idLength = frame[1] & 0xFF;
		if(idLength < 8 || idLength > 32 || frame.length < 2 + idLength) {
			lanRelayError = "The relay sent an invalid LAN peer id";
			closeLANRelaySocket(true);
			return;
		}
		String peer = new String(frame, 2, idLength, StandardCharsets.US_ASCII);
		if(!isValidLANPeer(peer)) {
			lanRelayError = "The relay sent an invalid LAN peer id";
			closeLANRelaySocket(true);
			return;
		}
		int payloadLength = frame.length - 2 - idLength;
		if(payloadLength == 0) {
			return;
		}
		byte[] payload = new byte[payloadLength];
		System.arraycopy(frame, 2 + idLength, payload, 0, payloadLength);
		sendDataBatch(ServerWorkerProtocol.LAN_DATA_PREFIX + peer, payload);
	}

	private static void sendLANRelayPeer(String peer, byte[] payload) {
		if(lanLegacyMode) {
			LegacyLANHost.send(peer, payload);
			return;
		}
		IWebSocketClient socket = lanRelaySocket;
		if(socket == null || !socket.isOpen() || !isValidLANPeer(peer) || payload == null) {
			return;
		}
		int buffered = socket.getBufferedAmount();
		if(buffered >= LAN_RELAY_ABORT_BYTES) {
			lanRelayError = "LAN relay upload stalled (browser buffered " + (buffered / (1024 * 1024))
					+ " MiB); closing the room to protect the page from running out of memory";
			closeLANRelaySocket(true);
			return;
		}
		if(buffered >= LAN_RELAY_PAUSE_BYTES && lanRelayPausedPeers.add(peer)) {
			sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
					("pause:" + peer).getBytes(StandardCharsets.UTF_8));
			logger.warn("Pausing LAN peer {} at {} MiB browser WebSocket backlog", peer,
					buffered / (1024 * 1024));
		}
		byte[] id = peer.getBytes(StandardCharsets.US_ASCII);
		byte[] frame = new byte[2 + id.length + payload.length];
		frame[0] = 1;
		frame[1] = (byte)id.length;
		System.arraycopy(id, 0, frame, 2, id.length);
		System.arraycopy(payload, 0, frame, 2 + id.length, payload.length);
		socket.send(frame);
	}

	private static String formatLANRelayClose(IWebSocketClient socket, String fallback) {
		String reason = socket.getCloseReason();
		int code = socket.getCloseCode();
		if(reason != null && !reason.isBlank()) {
			return "LAN relay disconnected: " + reason + (code > 0 ? " (code " + code + ")" : "");
		}
		return code > 0 ? fallback + " (code " + code + ")" : fallback;
	}

	private static boolean isValidLANPeer(String peer) {
		if(peer == null || peer.length() != 16) {
			return false;
		}
		for(int i = 0; i < peer.length(); ++i) {
			char c = peer.charAt(i);
			if(!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
				return false;
			}
		}
		return true;
	}

	private static String jsonField(String json, String key) {
		if(json == null) {
			return null;
		}
		String marker = "\"" + key + "\":\"";
		int start = json.indexOf(marker);
		if(start < 0) {
			return null;
		}
		start += marker.length();
		int end = json.indexOf('"', start);
		return end < 0 ? null : json.substring(start, end);
	}

	private static class ServerWorkerMetaHandlerImpl implements WorkerMetaHandler {
		public void onMeta(String meta) {
			if(meta == null) {
				return;
			}
			if(meta.startsWith("server-worker:health-ok:")) {
				int probe = -1;
				try {
					probe = Integer.parseInt(meta.substring("server-worker:health-ok:".length()));
				}catch(NumberFormatException ex) {
				}
				if(probe == pendingWorkerErrorProbe) {
					pendingWorkerErrorProbe = 0;
					pendingWorkerErrorReport = null;
					logger.warn("Integrated server worker remained responsive after a runtime error; continuing the world");
				}
			}else if(meta.startsWith("server-worker:boot-error") || meta.startsWith("server-worker:fatal")) {
				pendingWorkerErrorProbe = 0;
				pendingWorkerErrorReport = null;
				logger.error("Integrated server worker reported: {}", meta);
				// A worker boot/fatal failure (e.g. EPK fetch or Bootstrap.bootStrap() threw in the
				// worker realm) otherwise only reaches the console — the client's world-launch wait
				// loop watches for an IPC 0x15 crash / STATE_FAILED, not this meta, so without this it
				// would hang forever on the busy screen. Surface it as a 0x15 crash → readable server
				// crash screen (same path as an uncaught worker error event).
				reportWorkerErrorToClient(meta);
			}else {
				logger.info("Integrated server worker: {}", meta);
			}
		}
	}

	private static void probeWorkerAfterError(String details) {
		if(!serverWorkerMode || workerObj == null) {
			reportWorkerErrorToClient(details);
			return;
		}
		if(pendingWorkerErrorProbe != 0) {
			return;
		}
		int probe = ++workerErrorProbeSequence;
		if(probe <= 0) {
			workerErrorProbeSequence = probe = 1;
		}
		pendingWorkerErrorProbe = probe;
		pendingWorkerErrorReport = details;
		logger.error("Integrated server worker raised an uncaught runtime error; checking whether it survived: {}", details);
		postMetaToWorker(workerObj, "server-worker:health-probe:" + probe);
		final int expectedProbe = probe;
		scheduleTimeout(() -> {
			if(pendingWorkerErrorProbe == expectedProbe) {
				String report = pendingWorkerErrorReport;
				pendingWorkerErrorProbe = 0;
				pendingWorkerErrorReport = null;
				logger.error("Integrated server worker did not answer the liveness probe");
				reportWorkerErrorToClient(report);
			}
		}, WORKER_ERROR_PROBE_TIMEOUT_MS);
	}

	@JSBody(params = { "w", "workerArgs" }, script = "w.postMessage({ msg : workerArgs });")
	private static native void sendWorkerStartPacket(Worker w, String workerArgs);

	private static class WorkerBinaryPacketHandlerImpl implements WorkerBinaryPacketHandler {

		public void onMessage(String channel, ArrayBuffer buf) {
			if(channel == null) {
				logger.error("Recieved IPC packet with null channel");
				return;
			}

			if(buf == null) {
				logger.error("Recieved IPC packet with null buffer");
				return;
			}

			if(channel.startsWith(ServerWorkerProtocol.LAN_DATA_PREFIX)) {
				String lanPeer = channel.substring(ServerWorkerProtocol.LAN_DATA_PREFIX.length());
				if(directMode == 1) {
					// One worker peer id per guest link, no relay socket involved.
					PlatformWebRTC.directRoomSendToGuest(lanPeer, new Int8Array(buf).copyToJavaArray());
					return;
				}
				sendLANRelayPeer(lanPeer, new Int8Array(buf).copyToJavaArray());
				return;
			}

			// TODO(3.2): upstream passes LAN world packets to PlatformWebRTC.serverLANPeerPassIPC
			// here first; WebRTC is on the DROP list for this increment

			synchronized(messageQueue) {
				messageQueue.add(new IPCPacketData(channel, new Int8Array(buf).copyToJavaArray()));
			}
		}

	}

	@JSBody(params = { "blobObj" }, script = "return URL.createObjectURL(blobObj);")
	private static native String createWorkerScriptURL(JSObject blobObj);

	@JSBody(params = { "cscText", "tail" }, script = "return new Blob([cscText, tail], { type: \"text/javascript;charset=utf8\" });")
	private static native JSObject createBlobObj(ArrayBuffer buf, String tail);

	private static final String workerBootstrapCode = "\n\nmain([\"_worker_process_\"]);";

	private static JSObject loadIntegratedServerSource() {
		String str = loadIntegratedServerSourceOverrideURL();
		if(str != null) {
			ArrayBuffer buf = downloadRemoteURI(str, true);
			if(buf != null) {
				integratedServerSourceOriginalURL = str;
				logger.info("Using integrated server at: {}", truncateURL(str));
				return createBlobObj(buf, workerBootstrapCode);
			}else {
				logger.error("Failed to load integrated server: {}", truncateURL(str));
			}
		}
		JSObject el = loadIntegratedServerSourceOverride();
		if(el != null) {
			String url = loadIntegratedServerSourceURL(el);
			if(url == null) {
				el = loadIntegratedServerSourceInline(el, workerBootstrapCode);
				if(el != null) {
					integratedServerSourceOriginalURL = "inline script tag";
					logger.info("Loading integrated server from inline script tag");
					return el;
				}
			}else {
				ArrayBuffer buf = downloadRemoteURI(url, true);
				if(buf != null) {
					integratedServerSourceOriginalURL = url;
					logger.info("Using integrated server from script tag src: {}", truncateURL(url));
					return createBlobObj(buf, workerBootstrapCode);
				}else {
					logger.error("Failed to load integrated server from script tag src: {}", truncateURL(url));
				}
			}
		}
		str = tryResolveClassesSource();
		if(str != null) {
			ArrayBuffer buf = downloadRemoteURI(str, true);
			if(buf != null) {
				integratedServerSourceOriginalURL = str;
				logger.info("Using integrated server from script src: {}", truncateURL(str));
				return createBlobObj(buf, workerBootstrapCode);
			}else {
				logger.error("Failed to load integrated server from script src: {}", truncateURL(str));
			}
		}
		HTMLScriptElement sc = tryResolveClassesSourceInline();
		if(sc != null) {
			el = loadIntegratedServerSourceInline(sc, workerBootstrapCode);
			if(el != null) {
				integratedServerSourceOriginalURL = "inline script tag (client guess)";
				logger.info("Loading integrated server from (likely) inline script tag");
				return el;
			}
		}
		logger.info("Could not resolve the location of client's classes.js!");
		logger.info("Make sure client's classes.js is linked/embedded in a dedicated <script> tag");
		logger.info("Define \"window.eaglercraftXClientScriptElement\" or \"window.eaglercraftXClientScriptURL\" to force");
		return null;
	}

	private static String truncateURL(String url) {
		if(url == null) return null;
		if(url.length() > 256) {
			url = url.substring(0, 254) + "...";
		}
		return url;
	}

	private static String createIntegratedServerWorkerURL() {
		JSObject blobObj = loadIntegratedServerSource();
		if(blobObj == null) {
			return null;
		}
		return TeaVMBlobURLManager.registerNewURLBlob(blobObj).toExternalForm();
	}

	/**
	 * Mesh-worker plan Phase A: build a fresh Blob-URL for a Web Worker running the
	 * same classes.js module (module text + {@link #workerBootstrapCode} tail),
	 * reusing the exact SP worker source-resolution + Blob mechanism. Returns null
	 * if the client's classes.js cannot be located (e.g. the async source-download
	 * path is not wired up yet); the mesh self-test then uses its own XHR fallback.
	 * Does not disturb the integrated-server worker state.
	 *
	 * <p>WORKERS PORT (wasm-gc): there is no classes.js — return the worker-bootstrap.js
	 * URL instead (null when the shell has no precompiled module; callers keep their
	 * null-fallbacks). Spawners must pair this with
	 * {@link net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap#spawnWasmWorker}.
	 */
	public static String createMeshWorkerScriptURLTeaVM() {
		if(net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC()) {
			return net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.resolveBootstrapURL();
		}
		return createIntegratedServerWorkerURL();
	}

	public static byte[] getIntegratedServerSourceTeaVM() {
		String str = loadIntegratedServerSourceOverrideURL();
		if(str != null) {
			ArrayBuffer buf = downloadRemoteURI(str, true);
			if(buf != null) {
				return new Int8Array(buf).copyToJavaArray();
			}
		}
		JSObject el = loadIntegratedServerSourceOverride();
		if(el != null) {
			String url = loadIntegratedServerSourceURL(el);
			if(url == null) {
				str = loadIntegratedServerSourceInlineStr(el);
				if(str != null) {
					return str.getBytes(StandardCharsets.UTF_8);
				}
			}else {
				ArrayBuffer buf = downloadRemoteURI(url, true);
				if(buf != null) {
					return new Int8Array(buf).copyToJavaArray();
				}
			}
		}
		str = tryResolveClassesSource();
		if(str != null) {
			ArrayBuffer buf = downloadRemoteURI(str, true);
			if(buf != null) {
				return new Int8Array(buf).copyToJavaArray();
			}
		}
		HTMLScriptElement sc = tryResolveClassesSourceInline();
		if(sc != null) {
			return sc.getText().getBytes(StandardCharsets.UTF_8);
		}
		return null;
	}

	public static String getLoadedWorkerURLTeaVM() {
		return (serverSourceLoaded && workerObj != null) ? integratedServerSource : null;
	}

	public static String getLoadedWorkerSourceURLTeaVM() {
		return (serverSourceLoaded && workerObj != null) ? integratedServerSourceOriginalURL : null;
	}

	private static void startSingleThreadFallback() {
		serverWorkerMode = false;
		if(!isSingleThreadMode) {
			SingleThreadWorker.singleThreadStartup((pkt) -> {
				synchronized(messageQueue) {
					messageQueue.add(pkt);
				}
			});
			isSingleThreadMode = true;
		}
	}

	public static void startIntegratedServer(boolean singleThreadMode) {
		// serverWorker mode (default OFF): move the integrated server into a real Web
		// Worker. Overrides single-thread mode when enabled and Worker is available;
		// otherwise falls through to today's single-thread path (the fallback).
		boolean wasmGC = net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC();
		boolean forceServerWorker = workerSupportedJS() && (wasmGC || isServerWorkerFlagJS());
		if(forceServerWorker) {
			singleThreadMode = false;
			logger.info("serverWorker mode ENABLED — integrated server will run in a Web Worker");
		}else {
			singleThreadMode |= ((TeaVMClientConfigAdapter)PlatformRuntime.getClientConfigAdapter()).isSingleThreadModeTeaVM();
		}
		if(singleThreadMode) {
			startSingleThreadFallback();
		}else {
			if(!serverSourceLoaded) {
				// WORKERS PORT (wasm-gc): spawn from worker-bootstrap.js + the page's ONE
				// precompiled WebAssembly.Module instead of a classes.js Blob (there is no
				// classes.js on wasm). null (no module on the shell) falls through to the
				// existing single-thread fallback below. Constant-folds away on JS.
				if(net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC()) {
					integratedServerSource = net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.resolveServerBootstrapURL();
				}else {
					integratedServerSource = createIntegratedServerWorkerURL();
				}
				serverSourceLoaded = true;
			}

			if(integratedServerSource == null) {
				if(wasmGC) {
					logger.error("Dedicated server-worker Wasm is unavailable; refusing an invalid full-client fallback");
					reportWorkerErrorToClient("Dedicated server-worker Wasm is unavailable or failed to compile");
					return;
				}
				logger.error("Could not resolve the location of client's classes.js! Make sure client's classes.js is linked/embedded in a dedicated <script> tag. Define \"window.eaglercraftXClientScriptElement\" or \"window.eaglercraftXClientScriptURL\" to force");
				logger.error("Falling back to single thread mode...");
				// Do not recurse through startIntegratedServer(true): an explicit
				// serverWorker flag would force the worker branch again and overflow
				// the Wasm stack when the bootstrap URL/module is unavailable.
				startSingleThreadFallback();
				return;
			}

			workerObj = net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.isWasmGC()
					? net.lax1dude.eaglercraft.v1_8.internal.teavm.WasmWorkerBootstrap.spawnWasmWorker(integratedServerSource)
					: Worker.create(integratedServerSource);
			workerObj.addEventListener("error", new EventListener<ErrorEvent>() {
				@Override
				public void handleEvent(ErrorEvent evt) {
					// An uncaught worker task can raise an ErrorEvent even when the worker and
					// server loop survive (Chrome does this for a recoverable JS RangeError).
					// Suppress the page-level duplicate and ask the worker event loop to prove
					// liveness before turning the event into a fatal IPC crash.
					String details = describeWorkerError(evt);
					suppressWorkerErrorDefault(evt);
					probeWorkerAfterError(details);
				}
			});
			if(forceServerWorker) {
				serverWorkerMode = true;
				closeWorkerFilesystem = net.lax1dude.eaglercraft.v1_8.internal.teavm.WorkerFilesystemBridge.attachPage(
						workerObj, PlatformRuntime.getGameDirectoryFilesystem());
				// eag26 handshake: the FIRST control message decides the worker's role.
				// The worker boots into MeshWorkerMain (mesh splitter), sees this
				// {meta:"role:server"}, and hands off to ServerWorkerHost, which replaces
				// self.onmessage with the {ch,dat} IPC/data router and runs serverMain().
				registerServerWorkerHandler(workerObj, new WorkerBinaryPacketHandlerImpl(), new ServerWorkerMetaHandlerImpl());
				// eag26 first-meta role handshake + seam-b EPK handoff: the meta carries the
				// resolved assets.epk URL(s) so the worker realm (empty PlatformAssets map) can
				// re-fetch the datapack assets itself before world construction.
				postMetaToWorker(workerObj, net.lax1dude.eaglercraft.v1_8.internal.teavm.ClientMain.buildServerWorkerRoleMeta());
			}else {
				serverWorkerMode = false;
				registerPacketHandler(workerObj, new WorkerBinaryPacketHandlerImpl());
				sendWorkerStartPacket(workerObj, PlatformRuntime.getClientConfigAdapter().getIntegratedServerOpts().toString());
			}
		}
	}

	// A worker error (uncaught throw / OOM / exit) is reported to the controller as a
	// crash so the client shows the readable server-crash screen instead of hanging.
	private static void reportWorkerErrorToClient(String message) {
		String report = "The integrated server Web Worker crashed or exited unexpectedly.\n\n"
				+ (message != null ? message : "(no message)")
				+ "\n\nThe world could not continue. Try disabling serverWorker mode (remove"
				+ " ?serverworker / eaglercraftXOpts.serverWorker) to fall back to single-thread mode.";
		byte[] bytes;
		try {
			net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacketManager pm = new net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacketManager();
			bytes = pm.IPCSerialize(new net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket15Crashed(report));
		}catch(Throwable t) {
			logger.error("Failed to synthesize worker-crash IPC packet");
			return;
		}
		synchronized(messageQueue) {
			messageQueue.add(new IPCPacketData("~!IPC", bytes));
		}
	}

	public static void sendPacket(IPCPacketData packet) {
		if(isSingleThreadMode) {
			SingleThreadWorker.sendPacketToWorker(packet);
		}else {
			sendPacketTeaVM(packet.channel, Int8Array.fromJavaArray(packet.contents).getBuffer());
		}
	}

	public static void sendPacketTeaVM(String channel, ArrayBuffer packet) {
		if(isSingleThreadMode) {
			SingleThreadWorker.sendPacketToWorker(new IPCPacketData(channel, new Int8Array(packet).copyToJavaArray()));
		}else {
			if(workerObj != null) {
				sendWorkerPacket(workerObj, channel, packet);
			}
		}
	}

	public static List<IPCPacketData> recieveAllPacket() {
		synchronized(messageQueue) {
			if(messageQueue.size() == 0) {
				return null;
			}else {
				List<IPCPacketData> ret = new ArrayList<>(messageQueue);
				messageQueue.clear();
				return ret;
			}
		}
	}

	public static boolean canKillWorker() {
		return !isSingleThreadMode;
	}

	public static void killWorker() {
		if(closeWorkerFilesystem != null) {
			closeWorkerFilesystem.run();
			closeWorkerFilesystem = null;
		}
		closeLANRelay();
		if(workerObj != null) {
			workerObj.terminate();
			workerObj = null;
		}
		synchronized(messageQueue) {
			messageQueue.clear();
		}
		pendingWorkerErrorProbe = 0;
		pendingWorkerErrorReport = null;
	}

	public static boolean isRunningSingleThreadMode() {
		return isSingleThreadMode;
	}

	public static boolean isSingleThreadModeSupported() {
		return true;
	}

	public static void updateSingleThreadMode() {
		if(isSingleThreadMode) {
			SingleThreadWorker.singleThreadUpdate();
		}
	}

	public static void showCrashReportOverlay(String report, int x, int y, int w, int h) {
		// Scrollable monospace panel over the canvas showing the full server crash report;
		// x/y/w/h are framebuffer (device) pixels, so divide by devicePixelRatio for CSS.
		try {
			jsShowCrashOverlay(report, x, y, w, h);
		}catch(Throwable t) {
			logger.error("Failed to show integrated server crash overlay: {}", t.toString());
		}
	}

	public static void hideCrashReportOverlay() {
		try {
			jsHideCrashOverlay();
		}catch(Throwable t) {
		}
	}

	@org.teavm.jso.JSBody(params = { "report", "x", "y", "w", "h" }, script =
			"var dpr = window.devicePixelRatio || 1;"
			+ "var el = document.getElementById('_eaglerx_sp_crash_overlay');"
			+ "if(!el){ el = document.createElement('div'); el.id = '_eaglerx_sp_crash_overlay';"
			+ "  el.style.cssText = 'position:fixed;z-index:2147483640;background:rgba(15,15,18,0.95);"
			+ "    color:#e6e6e6;font-family:monospace;font-size:12px;line-height:1.35;white-space:pre-wrap;"
			+ "    word-break:break-word;overflow:auto;padding:10px;box-sizing:border-box;border:1px solid #666;"
			+ "    border-radius:3px;user-select:text;-webkit-user-select:text;';"
			+ "  document.body.appendChild(el); }"
			+ "el.style.left=(x/dpr)+'px'; el.style.top=(y/dpr)+'px';"
			+ "el.style.width=(w/dpr)+'px'; el.style.height=(h/dpr)+'px';"
			+ "el.textContent = report; el.style.display = 'block';")
	private static native void jsShowCrashOverlay(String report, int x, int y, int w, int h);

	@org.teavm.jso.JSBody(params = {}, script =
			"var el = document.getElementById('_eaglerx_sp_crash_overlay');"
			+ "if(el){ el.style.display = 'none'; el.textContent = ''; }")
	private static native void jsHideCrashOverlay();

}

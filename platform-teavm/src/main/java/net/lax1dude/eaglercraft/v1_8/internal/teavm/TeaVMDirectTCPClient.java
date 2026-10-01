package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.AbstractWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;

/** Ordered byte-stream adapter for Chrome's IWA-only Direct Sockets API. */
public final class TeaVMDirectTCPClient extends AbstractWebSocketClient {

	@JSFunctor
	private interface OpenCallback extends JSObject {
		boolean call(JSObject socket, JSObject writer, JSObject reader);
	}

	@JSFunctor
	private interface DataCallback extends JSObject {
		void call(JSObject data);
	}

	@JSFunctor
	private interface ErrorCallback extends JSObject {
		void call(String message);
	}

	@JSFunctor
	private interface CloseCallback extends JSObject {
		void call();
	}

	private volatile boolean connecting = true;
	private volatile boolean connected;
	private volatile boolean failed;
	private volatile boolean closed;
	private boolean resourcesReleased;
	private String closeReason;
	private JSObject socket;
	private JSObject writer;
	private JSObject reader;

	// Keep every functor strongly reachable until teardown.
	private final OpenCallback openCallback = this::handleOpen;
	private final DataCallback dataCallback = this::handleData;
	private final ErrorCallback errorCallback = this::handleError;
	private final CloseCallback closeCallback = this::handleClose;

	public TeaVMDirectTCPClient(String host, int port) {
		super("tcp://" + host + ":" + port);
		if (host == null || host.isBlank() || port < 1 || port > 65535) {
			throw new IllegalArgumentException("Invalid TCP destination");
		}
		socket = openSocket(host, port, openCallback, dataCallback, errorCallback, closeCallback);
	}

	@JSBody(params = { "host", "port", "opened", "data", "failed", "closed" }, script =
		"try {"
		+ "var socket = new TCPSocket(host, port, { noDelay: true, keepAlive: true });"
		+ "var terminal = false;"
		+ "function failOnce(error) { if (terminal) return; terminal = true;"
		+ "  failed(String(error && (error.message || error.name) || error)); }"
		+ "function closeOnce() { if (terminal) return; terminal = true; closed(); }"
		+ "socket.opened.then(function(info) {"
		+ "  var writer = info.writable.getWriter();"
		+ "  var reader = info.readable.getReader();"
		+ "  if (!opened(socket, writer, reader)) { terminal = true; return; }"
		+ "  function readNext() { reader.read().then(function(result) {"
		+ "    if (result.done) { closeOnce(); return; }"
		+ "    var view = result.value;"
		+ "    var buffer = view.byteOffset === 0 && view.byteLength === view.buffer.byteLength"
		+ "      ? view.buffer : view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength);"
		+ "    data(buffer); readNext();"
		+ "  }).catch(failOnce); }"
		+ "  readNext();"
		+ "}).catch(failOnce);"
		+ "socket.closed.then(closeOnce, failOnce); return socket;"
		+ "} catch (error) { failed(String(error && (error.message || error.name) || error)); return null; }")
	private static native JSObject openSocket(String host, int port, OpenCallback opened,
			DataCallback data, ErrorCallback failed, CloseCallback closed);

	@JSBody(params = { "writer", "buffer", "failed" }, script =
		"writer.write(buffer).catch(function(error) {"
		+ " failed(String(error && (error.message || error.name) || error)); });")
	private static native void writeSocket(JSObject writer, ArrayBuffer buffer, ErrorCallback failed);

	@JSBody(params = { "socket", "writer", "reader" }, script =
		"var closeUnlocked = function() { try { if (socket) {"
		+ "  var result = socket.close(); if (result && result.catch) result.catch(function() {});"
		+ "} } catch (e) {} };"
		+ "try { if (writer) writer.releaseLock(); } catch (e) {}"
		+ "if (reader) { var canceled = null;"
		+ "  try { canceled = reader.cancel(); } catch (e) {}"
		+ "  if (canceled && canceled.then) { canceled.catch(function() {}).then(function() {"
		+ "    try { reader.releaseLock(); } catch (e) {} closeUnlocked();"
		+ "  }); } else { try { reader.releaseLock(); } catch (e) {} closeUnlocked(); }"
		+ "} else { closeUnlocked(); }")
	private static native void closeSocket(JSObject socket, JSObject writer, JSObject reader);

	private boolean handleOpen(JSObject openedSocket, JSObject openedWriter, JSObject openedReader) {
		if (closed || failed || resourcesReleased) {
			closeSocket(openedSocket, openedWriter, openedReader);
			return false;
		}
		socket = openedSocket;
		writer = openedWriter;
		reader = openedReader;
		connecting = false;
		connected = true;
		return true;
	}

	private void handleData(JSObject data) {
		if (connected && !resourcesReleased) {
			addRecievedFrame(new TeaVMWebSocketFrame(data));
		}
	}

	private void handleError(String message) {
		if (closed || failed || resourcesReleased) {
			return;
		}
		closeReason = message == null || message.isBlank() ? "Direct TCP socket failed" : message;
		failed = true;
		connecting = false;
		connected = false;
		releaseResources();
	}

	private void handleClose() {
		if (closed || failed || resourcesReleased) {
			return;
		}
		connecting = false;
		connected = false;
		closed = true;
		releaseResources();
	}

	private void releaseResources() {
		if (resourcesReleased) {
			return;
		}
		resourcesReleased = true;
		JSObject releasedSocket = socket;
		JSObject releasedWriter = writer;
		JSObject releasedReader = reader;
		socket = null;
		writer = null;
		reader = null;
		closeSocket(releasedSocket, releasedWriter, releasedReader);
	}

	@Override
	public boolean connectBlocking(int timeoutMS) {
		long started = PlatformRuntime.steadyTimeMillis();
		while (connecting && !connected && !failed) {
			EagUtils.sleep(10);
			if (PlatformRuntime.steadyTimeMillis() - started > timeoutMS) {
				closeReason = "Direct TCP connection timed out";
				close();
				break;
			}
		}
		return connected;
	}

	@Override
	public EnumEaglerConnectionState getState() {
		return connected ? EnumEaglerConnectionState.CONNECTED
				: failed ? EnumEaglerConnectionState.FAILED
				: connecting ? EnumEaglerConnectionState.CONNECTING : EnumEaglerConnectionState.CLOSED;
	}

	@Override
	public boolean isOpen() {
		return connected;
	}

	@Override
	public boolean isClosed() {
		return closed || failed;
	}

	@Override
	public int getCloseCode() {
		return failed ? 1006 : closed ? 1000 : 0;
	}

	@Override
	public String getCloseReason() {
		return closeReason;
	}

	@Override
	public void close() {
		if (closed && resourcesReleased) {
			return;
		}
		connecting = false;
		connected = false;
		closed = true;
		clearFrames();
		releaseResources();
	}

	@Override
	public void send(String str) {
		throw new UnsupportedOperationException("A TCP byte stream cannot send text frames");
	}

	@Override
	public void send(byte[] bytes) {
		if (connected && bytes != null && bytes.length != 0) {
			writeSocket(writer, Int8Array.fromJavaArray(bytes).getBuffer(), errorCallback);
		}
	}
}

/*
 * Copyright (c) 2022-2024 lax1dude. All Rights Reserved.
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
//  - TeaVMUtils.addEventListener -> WebSocket.addEventListener (0.13 WebSocket is an EventTarget)
//  - TeaVMUtils.unwrapArrayBuffer -> 0.13 Int8Array.fromJavaArray(...).getBuffer()

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.MessageEvent;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.websocket.WebSocket;
import org.teavm.jso.websocket.CloseEvent;

import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.AbstractWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;

public class TeaVMWebSocketClient extends AbstractWebSocketClient {

	private final WebSocket sock;
	private boolean sockIsConnecting = true;
	private boolean sockIsConnected = false;
	private boolean sockIsFailed = false;
	private int closeCode = 0;
	private String closeReason = null;
	private final EventListener<Event> openListener;
	private final EventListener<CloseEvent> closeListener;
	private final EventListener<MessageEvent> messageListener;
	private final EventListener<Event> errorListener;
	private boolean closeStarted;

	public TeaVMWebSocketClient(String socketURI) {
		super(socketURI);
		WebSocket nativeSocket = createClientNativeWebSocket(socketURI);
		sock = nativeSocket != null ? nativeSocket : WebSocket.create(socketURI);
		sock.setBinaryType("arraybuffer");
		openListener = new EventListener<Event>() {
			@Override
			public void handleEvent(Event evt) {
				if (closeStarted) return;
				sockIsConnecting = false;
				sockIsConnected = true;
			}
		};
		closeListener = new EventListener<CloseEvent>() {
			@Override
			public void handleEvent(CloseEvent evt) {
				if (closeStarted) return;
				sockIsConnecting = false;
				sockIsConnected = false;
				closeCode = evt.getCode();
				closeReason = evt.getReason();
			}
		};
		messageListener = new EventListener<MessageEvent>() {
			@Override
			public void handleEvent(MessageEvent evt) {
				if (closeStarted) return;
				addRecievedFrame(new TeaVMWebSocketFrame(evt.getData()));
			}
		};
		errorListener = new EventListener<Event>() {
			@Override
			public void handleEvent(Event evt) {
				if (closeStarted) return;
				if(sockIsConnecting) {
					sockIsFailed = true;
					sockIsConnecting = false;
				}
			}
		};
		sock.addEventListener("open", openListener);
		sock.addEventListener("close", closeListener);
		sock.addEventListener("message", messageListener);
		sock.addEventListener("error", errorListener);
	}

	/**
	 * Return a browser WebSocket that bypasses Wispcraft's construct-only Proxy
	 * when the patcher captured the native constructor before Wispcraft ran. The
	 * Wispcraft site injector can prepend its UMD bundle ahead of the client's
	 * head, so in that order its global marker lets us recover the native
	 * constructor from the unchanged WebSocket prototype. Other wrappers are not
	 * guessed at; they use the ordinary TeaVM WebSocket path below.
	 */
	@JSBody(params = { "uri" }, script =
			"var root = typeof globalThis !== 'undefined' ? globalThis : null;"
			+ "var current = typeof WebSocket !== 'undefined' ? WebSocket : null;"
			+ "var marker = root && root.wispcraft;"
			+ "var isWispcraft = false;"
			+ "try { isWispcraft = !!(marker && typeof marker === 'object'"
			+ " && typeof marker.setWispUrl === 'function'"
			+ " && typeof marker.showSettingsUI === 'function'); } catch (e) {}"
			+ "var isWebSocket = function(candidate) { try { return typeof candidate === 'function'"
			+ " && candidate.prototype != null && typeof candidate.prototype.send === 'function'"
			+ " && typeof candidate.prototype.close === 'function'"
			+ " && typeof candidate.prototype.addEventListener === 'function'"
			+ " && typeof candidate.prototype.removeEventListener === 'function'; } catch (e) { return false; } };"
			+ "var captured = root && root.__eaglerNativeWebSocket;"
			+ "if (isWispcraft && isWebSocket(current)) {"
			+ " try { var protoConstructor = current.prototype.constructor;"
			+ " if (protoConstructor !== current && isWebSocket(protoConstructor)"
			+ " && (!isWebSocket(captured) || captured === current)) captured = protoConstructor;"
			+ " } catch (e) {}"
			+ "}"
			+ "return isWispcraft && isWebSocket(captured) ? new captured(uri) : null;")
	private static native WebSocket createClientNativeWebSocket(String uri);

	/**
	 * Detach client callbacks immediately, then close the adapter only after the
	 * current browser callback has unwound. Wispcraft's fallback transports may
	 * synchronously dispatch close while tearing down a Rust-backed socket.
	 */
	@JSBody(params = { "socket", "openListener", "closeListener", "messageListener", "errorListener" }, script =
			"var detach = function(type, listener) { try { socket.removeEventListener(type, listener); } catch (e) {} };"
			+ "detach('open', openListener); detach('close', closeListener);"
			+ "detach('message', messageListener); detach('error', errorListener);"
			+ "var closeLater = function() { var state;"
			+ " try { state = socket.readyState; } catch (e) { return; }"
			+ " if (state === 2 || state === 3) return;"
			+ " try { socket.close(); } catch (e) {} };"
			+ "try { if (typeof queueMicrotask === 'function') queueMicrotask(closeLater);"
			+ " else setTimeout(closeLater, 0); } catch (e) {"
			+ " try { setTimeout(closeLater, 0); } catch (ignored) {} }")
	private static native void closeSocketAfterCallback(WebSocket socket, EventListener<Event> openListener,
		EventListener<CloseEvent> closeListener, EventListener<MessageEvent> messageListener,
		EventListener<Event> errorListener);

	@Override
	public boolean connectBlocking(int timeoutMS) {
		long startTime = PlatformRuntime.steadyTimeMillis();
		while(sockIsConnecting && !sockIsConnected && !sockIsFailed) {
			EagUtils.sleep(50);
			if(PlatformRuntime.steadyTimeMillis() - startTime > timeoutMS) {
				break;
			}
		}
		// Browsers dispatch "error" immediately before "close" for rejected
		// WebSocket upgrades. Give the close event a brief turn so callers can
		// report its policy code/reason instead of a generic connection error.
		if(!sockIsConnected && closeCode == 0) {
			long closeDeadline = PlatformRuntime.steadyTimeMillis() + 250L;
			while(closeCode == 0 && PlatformRuntime.steadyTimeMillis() < closeDeadline) {
				EagUtils.sleep(10);
			}
		}
		return sockIsConnected;
	}

	@Override
	public EnumEaglerConnectionState getState() {
		return sockIsConnected ? EnumEaglerConnectionState.CONNECTED
				: (sockIsFailed ? EnumEaglerConnectionState.FAILED
						: (sockIsConnecting ? EnumEaglerConnectionState.CONNECTING : EnumEaglerConnectionState.CLOSED));
	}

	@Override
	public boolean isOpen() {
		return sockIsConnected;
	}

	@Override
	public boolean isClosed() {
		return !sockIsConnecting && !sockIsConnected;
	}

	@Override
	public int getCloseCode() {
		return closeCode;
	}

	@Override
	public String getCloseReason() {
		return closeReason;
	}

	@Override
	public int getBufferedAmount() {
		return sock.getBufferedAmount();
	}

	@Override
	public void close() {
		if (closeStarted) {
			return;
		}
		closeStarted = true;
		sockIsConnecting = false;
		sockIsConnected = false;
		sockIsFailed = false;
		clearFrames();
		closeSocketAfterCallback(sock, openListener, closeListener, messageListener, errorListener);
	}

	@Override
	public void send(String str) {
		if(sockIsConnected) {
			sock.send(str);
		}
	}

	@Override
	public void send(byte[] bytes) {
		if(sockIsConnected) {
			sock.send(Int8Array.fromJavaArray(bytes).getBuffer());
		}
	}

}

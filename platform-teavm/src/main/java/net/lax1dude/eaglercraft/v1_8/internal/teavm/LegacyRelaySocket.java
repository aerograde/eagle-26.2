package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.io.DataInputStream;
import java.util.LinkedList;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.IRelayLogger;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket;

/** Binary signaling socket for the original Eagler 1.8 shared-world relay protocol. */
final class LegacyRelaySocket {

	private static final Logger logger = LogManager.getLogger("LegacyRelaySocket");
	private static final IRelayLogger packetLogger = new IRelayLogger() {
		@Override public void debug(String msg, Object... args) { logger.debug(msg, args); }
		@Override public void info(String msg, Object... args) { logger.info(msg, args); }
		@Override public void warn(String msg, Object... args) { logger.warn(msg, args); }
		@Override public void error(String msg, Object... args) { logger.error(msg, args); }
		@Override public void error(Throwable th) { logger.error(th); }
	};

	private final String uri;
	private final IWebSocketClient socket;
	private final List<RelayPacket> packets = new LinkedList<>();
	private String error;

	LegacyRelaySocket(String uri) {
		this.uri = uri;
		this.socket = PlatformNetworking.openWebSocket(uri);
		if(this.socket != null) {
			this.socket.setEnableStringFrames(false);
			this.socket.setEnableBinaryFrames(true);
		}
	}

	boolean connectBlocking(int timeoutMillis) {
		if(socket == null) {
			error = "Could not create signaling WebSocket";
			return false;
		}
		if(!socket.connectBlocking(timeoutMillis)) {
			error = closeDetail("Could not connect to signaling relay");
			return false;
		}
		return true;
	}

	void update() {
		if(socket == null) {
			return;
		}
		IWebSocketFrame frame;
		while((frame = socket.getNextFrame()) != null) {
			if(frame.isString()) {
				error = "Legacy relay sent a text frame";
				close();
				return;
			}
			try {
				packets.add(RelayPacket.readPacket(new DataInputStream(frame.getInputStream()), packetLogger));
			}catch(Throwable t) {
				error = "Invalid legacy relay packet: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
				logger.error(t);
				close();
				return;
			}
		}
		if(socket.isClosed() && error == null) {
			error = closeDetail("Legacy relay disconnected");
		}
	}

	void send(RelayPacket packet) {
		if(socket == null || !socket.isOpen()) {
			return;
		}
		try {
			socket.send(RelayPacket.writePacket(packet, packetLogger));
		}catch(Throwable t) {
			error = "Could not encode legacy relay packet: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
			close();
		}
	}

	RelayPacket read() {
		return packets.isEmpty() ? null : packets.remove(0);
	}

	boolean isOpen() {
		return socket != null && socket.isOpen();
	}

	boolean isClosed() {
		return socket == null || socket.isClosed();
	}

	String getError() {
		return error;
	}

	String getURI() {
		return uri;
	}

	void close() {
		if(socket != null) {
			socket.close();
		}
	}

	private String closeDetail(String fallback) {
		if(socket == null) {
			return fallback;
		}
		String reason = socket.getCloseReason();
		int code = socket.getCloseCode();
		if(reason != null && !reason.isBlank()) {
			return reason + (code > 0 ? " (code " + code + ")" : "");
		}
		return code > 0 ? fallback + " (code " + code + ")" : fallback;
	}
}

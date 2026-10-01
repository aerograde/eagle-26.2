package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.util.ArrayList;
import java.util.List;

import org.teavm.jso.typedarrays.Int8Array;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.AbstractWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformWebRTC;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket00Handshake;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket01ICEServers;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket03ICECandidate;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket04Description;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket05ClientSuccess;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket06ClientFailure;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacketFFErrorCode;

/**
 * Presents an Eagler 1.8 WebRTC data channel as the byte-stream socket expected
 * by the 26.2 Netty bridge. The relay WebSocket is used only for ICE/SDP setup.
 */
public final class LegacyLANWebSocketClient extends AbstractWebSocketClient {

	public static final String URI_PREFIX = "eagler-p2p-lan:";
	private static final int DATA_FRAGMENT_BYTES = 60 * 1024;
	private static final int MAX_PUMP_PACKETS = 16;
	private static final int MAX_PUMP_BYTES = 1024 * 1024;

	private final String relayURI;
	private final String joinCode;
	private boolean connecting = true;
	private boolean connected;
	private boolean failed;
	private int closeCode;
	private String closeReason;

	public LegacyLANWebSocketClient(String uri) {
		super(uri);
		if(uri == null || !uri.startsWith(URI_PREFIX)) {
			throw new IllegalArgumentException("Invalid Eagler P2P LAN URI");
		}
		int split = uri.lastIndexOf('#');
		if(split <= URI_PREFIX.length() || split + 1 >= uri.length()) {
			throw new IllegalArgumentException("Eagler P2P LAN URI is missing a relay or join code");
		}
		this.relayURI = uri.substring(URI_PREFIX.length(), split);
		this.joinCode = uri.substring(split + 1).trim().toUpperCase(java.util.Locale.ROOT);
	}

	@Override
	public boolean connectBlocking(int timeoutMillis) {
		long deadline = EagRuntime.steadyTimeMillis() + Math.max(1000, timeoutMillis);
		PlatformWebRTC.startRTCLANClient();
		PlatformWebRTC.clearLANClientState();
		LegacyRelaySocket relay = new LegacyRelaySocket(relayURI);
		int socketTimeout = (int)Math.max(1000L, deadline - EagRuntime.steadyTimeMillis());
		if(!relay.connectBlocking(socketTimeout)) {
			return fail(relay.getError() != null ? relay.getError() : "Could not connect to P2P signaling relay");
		}
		relay.send(new RelayPacket00Handshake(0x02, 1, joinCode));
		int state = 0;
		String peerId = "";
		while(EagRuntime.steadyTimeMillis() < deadline && !relay.isClosed()) {
			PlatformWebRTC.runScheduledTasks();
			relay.update();
			RelayPacket packet;
			while((packet = relay.read()) != null) {
				if(packet instanceof RelayPacket00Handshake && state == 0) {
					state = 1;
				}else if(packet instanceof RelayPacket01ICEServers ice && state == 1) {
					List<String> servers = new ArrayList<>();
					for(RelayPacket01ICEServers.RelayServer server : ice.servers) {
						servers.add(server.getICEString());
					}
					PlatformWebRTC.clientLANSetICEServersAndConnect(servers.toArray(new String[servers.size()]));
					state = 2;
				}else if(packet instanceof RelayPacket04Description description && state == 3) {
					PlatformWebRTC.clientLANSetDescription(description.getDescriptionString());
					state = 4;
				}else if(packet instanceof RelayPacket03ICECandidate candidate && state == 5) {
					peerId = candidate.peerId;
					PlatformWebRTC.clientLANSetICECandidate(candidate.getCandidateString());
					state = 6;
				}else if(packet instanceof RelayPacketFFErrorCode error) {
					relay.close();
					return fail("P2P relay rejected the join: " + error.desc + " (code " + error.code + ")");
				}else {
					relay.close();
					return fail("Unexpected P2P signaling packet " + packet.getClass().getSimpleName() + " in state " + state);
				}
			}
			if(state == 2) {
				String description = PlatformWebRTC.clientLANAwaitDescription();
				if(description != null) {
					relay.send(new RelayPacket04Description("", description));
					state = 3;
				}
			}else if(state == 4) {
				String candidate = PlatformWebRTC.clientLANAwaitICECandidate();
				if(candidate != null) {
					relay.send(new RelayPacket03ICECandidate("", candidate));
					state = 5;
				}
			}else if(state == 6 && PlatformWebRTC.clientLANAwaitChannel()) {
				relay.send(new RelayPacket05ClientSuccess(peerId));
				relay.close();
				connecting = false;
				connected = true;
				return true;
			}
			EagUtils.sleep(10);
		}
		if(state == 6 && !peerId.isEmpty()) {
			relay.send(new RelayPacket06ClientFailure(peerId));
		}
		relay.close();
		return fail(relay.getError() != null ? relay.getError() : "P2P LAN handshake timed out");
	}

	private boolean fail(String reason) {
		connecting = false;
		connected = false;
		failed = true;
		closeCode = 1006;
		closeReason = reason;
		PlatformWebRTC.clientLANCloseConnection();
		return false;
	}

	private void refill() {
		if(super.availableFrames() != 0) return;
		List<byte[]> packets = PlatformWebRTC.clientLANReadPacketBatch(MAX_PUMP_PACKETS, MAX_PUMP_BYTES);
		if(packets != null) {
			for(byte[] packet : packets) {
				addRecievedFrame(new TeaVMWebSocketFrame(Int8Array.fromJavaArray(packet).getBuffer()));
			}
		}
	}

	private void updateClosedState() {
		if(PlatformWebRTC.clientLANClosed()) {
			connected = false;
			closeCode = 1006;
			closeReason = "P2P LAN data channel disconnected";
		}
	}

	@Override
	public int availableFrames() {
		long available = (long)super.availableFrames() + PlatformWebRTC.clientLANPendingPacketCount();
		return available > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)available;
	}

	@Override
	public IWebSocketFrame getNextFrame() {
		IWebSocketFrame frame = super.getNextFrame();
		if(frame == null) {
			refill();
			frame = super.getNextFrame();
		}
		return frame;
	}

	@Override
	public EnumEaglerConnectionState getState() {
		updateClosedState();
		return connected ? EnumEaglerConnectionState.CONNECTED
				: failed ? EnumEaglerConnectionState.FAILED
				: connecting ? EnumEaglerConnectionState.CONNECTING : EnumEaglerConnectionState.CLOSED;
	}

	@Override public boolean isOpen() { updateClosedState(); return connected; }
	@Override public boolean isClosed() { updateClosedState(); return !connecting && !connected; }
	@Override public int getCloseCode() { return closeCode; }
	@Override public String getCloseReason() { return closeReason; }

	@Override
	public void close() {
		connecting = false;
		connected = false;
		clearFrames();
		PlatformWebRTC.clearLANClientPackets();
		PlatformWebRTC.clientLANCloseConnection();
	}

	@Override public void send(String str) { throw new UnsupportedOperationException("P2P LAN is binary only"); }

	@Override
	public void send(byte[] bytes) {
		if(!connected || bytes == null) {
			return;
		}
		for(int offset = 0; offset < bytes.length; offset += DATA_FRAGMENT_BYTES) {
			int length = Math.min(DATA_FRAGMENT_BYTES, bytes.length - offset);
			if(offset == 0 && length == bytes.length) {
				PlatformWebRTC.clientLANSendPacket(bytes);
			}else {
				byte[] fragment = new byte[length];
				System.arraycopy(bytes, offset, fragment, 0, length);
				PlatformWebRTC.clientLANSendPacket(fragment);
			}
		}
	}
}

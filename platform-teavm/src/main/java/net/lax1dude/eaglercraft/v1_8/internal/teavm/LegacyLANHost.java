package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformWebRTC;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.internal.ClientPlatformSingleplayer;
import net.lax1dude.eaglercraft.v1_8.sp.lan.LANPeerEvent;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket00Handshake;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket01ICEServers;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket02NewClient;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket03ICECandidate;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket04Description;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket05ClientSuccess;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacket06ClientFailure;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacketFEDisconnectClient;
import net.lax1dude.eaglercraft.v1_8.sp.relay.pkt.RelayPacketFFErrorCode;
import net.lax1dude.eaglercraft.v1_8.sp.server.ServerWorkerProtocol;

/** Host side of the original Eagler shared-world relay + WebRTC protocol. */
public final class LegacyLANHost {

	public static final String URI_PREFIX = "eagler-p2p-host:";
	private static final int DATA_FRAGMENT_BYTES = 60 * 1024;
	private static final int DATA_PAUSE_BYTES = 8 * 1024 * 1024;
	private static final int DATA_RESUME_BYTES = 2 * 1024 * 1024;
	private static final int DATA_ABORT_BYTES = 32 * 1024 * 1024;
	private static final Logger logger = LogManager.getLogger("LegacyLANHost");

	private static LegacyRelaySocket relay;
	private static String code;
	private static String error;
	private static final Map<String, Peer> peers = new HashMap<>();

	private LegacyLANHost() {
	}

	public static boolean open(String hostURI, int timeoutMillis) {
		close();
		if(hostURI == null || !hostURI.startsWith(URI_PREFIX)) {
			error = "Invalid P2P signaling relay URI";
			return false;
		}
		String relayURI = hostURI.substring(URI_PREFIX.length());
		PlatformWebRTC.startRTCLANServer();
		LegacyRelaySocket socket = new LegacyRelaySocket(relayURI);
		if(!socket.connectBlocking(timeoutMillis)) {
			error = socket.getError();
			return false;
		}
		relay = socket;
		relay.send(new RelayPacket00Handshake(0x01, 1, "Eaglercraft 26.2;0"));
		long deadline = EagRuntime.steadyTimeMillis() + Math.max(1000, timeoutMillis);
		boolean handshake = false;
		while(EagRuntime.steadyTimeMillis() < deadline && relay != null && !relay.isClosed()) {
			relay.update();
			RelayPacket packet;
			while((packet = relay.read()) != null) {
				if(packet instanceof RelayPacket00Handshake response && !handshake) {
					code = response.connectionCode;
					handshake = true;
				}else if(packet instanceof RelayPacket01ICEServers ice && handshake) {
					List<String> servers = new ArrayList<>();
					for(RelayPacket01ICEServers.RelayServer server : ice.servers) {
						servers.add(server.getICEString());
					}
					PlatformWebRTC.serverLANInitializeServer(servers.toArray(new String[servers.size()]));
					logger.info("P2P LAN room opened on {} with code {}", relayURI, code);
					return code != null && !code.isBlank();
				}else if(packet instanceof RelayPacketFFErrorCode relayError) {
					error = "P2P relay rejected hosting: " + relayError.desc + " (code " + relayError.code + ")";
					closeSocketOnly();
					return false;
				}else {
					error = "Unexpected P2P host packet " + packet.getClass().getSimpleName();
					closeSocketOnly();
					return false;
				}
			}
			EagUtils.sleep(10);
		}
		error = relay != null && relay.getError() != null ? relay.getError() : "P2P relay host handshake timed out";
		closeSocketOnly();
		return false;
	}

	public static void update() {
		LegacyRelaySocket socket = relay;
		if(socket == null) {
			return;
		}
		socket.update();
		RelayPacket packet;
		while((packet = socket.read()) != null) {
			if(packet instanceof RelayPacket02NewClient opened) {
				if(isValidPeer(opened.clientId) && !peers.containsKey(opened.clientId)) {
					peers.put(opened.clientId, new Peer(opened.clientId));
				}else {
					logger.warn("P2P relay supplied invalid or duplicate peer id {}", opened.clientId);
				}
			}else if(packet instanceof RelayPacket04Description description) {
				Peer peer = peers.get(description.peerId);
				if(peer != null) peer.remoteDescription(description.getDescriptionString());
			}else if(packet instanceof RelayPacket03ICECandidate candidate) {
				Peer peer = peers.get(candidate.peerId);
				if(peer != null) peer.remoteCandidate(candidate.getCandidateString());
			}else if(packet instanceof RelayPacket05ClientSuccess success) {
				Peer peer = peers.get(success.clientId);
				if(peer != null) peer.remoteSuccess = true;
			}else if(packet instanceof RelayPacket06ClientFailure failure) {
				Peer peer = peers.get(failure.clientId);
				if(peer != null) peer.close("guest reported WebRTC failure");
			}else if(packet instanceof RelayPacketFEDisconnectClient closed) {
				Peer peer = peers.get(closed.clientId);
				if(peer != null) peer.close(closed.reason);
			}else if(packet instanceof RelayPacketFFErrorCode relayError) {
				error = "P2P relay error: " + relayError.desc + " (code " + relayError.code + ")";
			}
		}
		Iterator<Peer> iterator = peers.values().iterator();
		while(iterator.hasNext()) {
			Peer peer = iterator.next();
			peer.update();
			if(peer.dead) iterator.remove();
		}
		if(socket.isClosed()) {
			error = socket.getError() != null ? socket.getError() : "P2P signaling relay disconnected";
			closeSocketOnly();
		}
	}

	public static boolean isOpen() {
		return relay != null && relay.isOpen() && code != null;
	}

	public static String getCode() {
		return code;
	}

	public static String getError() {
		return error;
	}

	public static void send(String peerId, byte[] payload) {
		Peer peer = peers.get(peerId);
		if(peer != null) peer.send(payload);
	}

	public static void close() {
		for(Peer peer : new ArrayList<>(peers.values())) peer.close("host closed LAN world");
		peers.clear();
		closeSocketOnly();
		code = null;
		error = null;
		PlatformWebRTC.startRTCLANServer();
		PlatformWebRTC.serverLANCloseServer();
	}

	private static void closeSocketOnly() {
		LegacyRelaySocket socket = relay;
		relay = null;
		code = null;
		if(socket != null) socket.close();
	}

	private static boolean isValidPeer(String peer) {
		if(peer == null || peer.length() != 16) return false;
		for(int i = 0; i < peer.length(); ++i) {
			char c = peer.charAt(i);
			if(!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) return false;
		}
		return true;
	}

	private static final class Peer {
		final String id;
		final long created = EagRuntime.steadyTimeMillis();
		boolean gotRemoteDescription;
		boolean sentDescription;
		boolean gotRemoteCandidate;
		boolean sentCandidate;
		boolean channelOpen;
		boolean remoteSuccess;
		boolean opened;
		boolean outboundPaused;
		boolean dead;
		String localCandidate;
		final List<byte[]> prebuffer = new LinkedList<>();

		Peer(String id) {
			this.id = id;
			PlatformWebRTC.serverLANCreatePeer(id);
		}

		void remoteDescription(String description) {
			if(!gotRemoteDescription) {
				gotRemoteDescription = true;
				PlatformWebRTC.serverLANPeerDescription(id, description);
			}
		}

		void remoteCandidate(String candidate) {
			if(sentDescription && !gotRemoteCandidate) {
				gotRemoteCandidate = true;
				PlatformWebRTC.serverLANPeerICECandidates(id, candidate);
			}
		}

		void update() {
			if(dead) return;
			List<LANPeerEvent> events = PlatformWebRTC.serverLANGetAllEvent(id);
			if(events != null) {
				for(LANPeerEvent event : events) {
					if(event instanceof LANPeerEvent.LANPeerDescriptionEvent description) {
						relay.send(new RelayPacket04Description(id, description.description));
						sentDescription = true;
					}else if(event instanceof LANPeerEvent.LANPeerICECandidateEvent candidate) {
						localCandidate = candidate.candidates;
					}else if(event instanceof LANPeerEvent.LANPeerDataChannelEvent) {
						channelOpen = true;
					}else if(event instanceof LANPeerEvent.LANPeerPacketEvent data) {
						if(opened) {
							ClientPlatformSingleplayer.sendDataBatch(ServerWorkerProtocol.LAN_DATA_PREFIX + id, data.payload);
						}else {
							prebuffer.add(data.payload);
						}
					}else if(event instanceof LANPeerEvent.LANPeerDisconnectEvent) {
						close("WebRTC data channel disconnected");
					}
				}
			}
			if(gotRemoteCandidate && localCandidate != null && !sentCandidate) {
				relay.send(new RelayPacket03ICECandidate(id, localCandidate));
				sentCandidate = true;
				localCandidate = null;
			}
			if(sentCandidate && channelOpen && remoteSuccess && !opened) {
				opened = true;
				ClientPlatformSingleplayer.sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
						("open:" + id).getBytes(StandardCharsets.UTF_8));
				for(byte[] data : prebuffer) {
					ClientPlatformSingleplayer.sendDataBatch(ServerWorkerProtocol.LAN_DATA_PREFIX + id, data);
				}
				prebuffer.clear();
				PlatformWebRTC.serverLANPeerMapIPC(id, ServerWorkerProtocol.LAN_DATA_PREFIX + id);
			}
			updateBackpressure();
			if(!opened && EagRuntime.steadyTimeMillis() - created > 20000L) close("P2P handshake timed out");
		}

		void send(byte[] payload) {
			if(dead || !opened || payload == null) return;
			if(PlatformWebRTC.serverLANPeerBufferedAmount(id) >= DATA_ABORT_BYTES) {
				close("WebRTC upload stalled");
				return;
			}
			for(int offset = 0; offset < payload.length; offset += DATA_FRAGMENT_BYTES) {
				int length = Math.min(DATA_FRAGMENT_BYTES, payload.length - offset);
				if(offset == 0 && length == payload.length) {
					PlatformWebRTC.serverLANWritePacket(id, payload);
				}else {
					byte[] fragment = new byte[length];
					System.arraycopy(payload, offset, fragment, 0, length);
					PlatformWebRTC.serverLANWritePacket(id, fragment);
				}
			}
			updateBackpressure();
		}

		void updateBackpressure() {
			if(dead || !opened) return;
			int buffered = PlatformWebRTC.serverLANPeerBufferedAmount(id);
			if(buffered >= DATA_ABORT_BYTES) {
				close("WebRTC upload stalled");
			}else if(buffered >= DATA_PAUSE_BYTES && !outboundPaused) {
				outboundPaused = true;
				ClientPlatformSingleplayer.sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
						("pause:" + id).getBytes(StandardCharsets.UTF_8));
				logger.warn("Pausing P2P LAN peer {} at {} MiB data-channel backlog", id,
						buffered / (1024 * 1024));
			}else if(buffered <= DATA_RESUME_BYTES && outboundPaused) {
				outboundPaused = false;
				ClientPlatformSingleplayer.sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
						("resume:" + id).getBytes(StandardCharsets.UTF_8));
			}
		}

		void close(String reason) {
			if(dead) return;
			dead = true;
			PlatformWebRTC.serverLANDisconnectPeer(id);
			if(opened) {
				ClientPlatformSingleplayer.sendDataBatch(ServerWorkerProtocol.LAN_CONTROL_CHANNEL,
						("close:" + id).getBytes(StandardCharsets.UTF_8));
			}
			logger.info("P2P LAN peer {} closed: {}", id, reason != null ? reason : "disconnected");
		}
	}
}

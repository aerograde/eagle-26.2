package net.lax1dude.eaglercraft.v1_8.socket;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import com.mojang.serialization.JsonOps;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.HiddenByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.util.LenientJsonParser;
import org.slf4j.Logger;

/**
 * Adapts Minecraft's normal TCP byte pipeline to a browser binary WebSocket.
 * WebSocket message boundaries are deliberately ignored: inbound frames are
 * appended to Netty's VarInt frame decoder and outbound buffers are forwarded
 * as byte chunks to the relay's TCP stream.
 */
public final class WebSocketPacketBridge {

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final long TRANSPORT_WINDOW_MILLIS = 5000L;
	private static final int DIRECT_INBOUND_BATCH_FRAMES = 64;
	private static final int DIRECT_INBOUND_BATCH_BYTES = 512 * 1024;

	private final IWebSocketClient socket;
	private final EmbeddedChannel channel;
	private final Connection connection;
	private final boolean eaglerFrames;
	private final boolean directTCP;
	private final EaglerXHandshake.Result eaglerProfile;
	private boolean closed;
	private boolean receivedEaglerMinecraftFrame;
	private long transportWindowStarted;
	private long transportLastInboundMillis = -1L;
	private long transportInboundFrames;
	private long transportInboundBytes;
	private long transportOutboundFrames;
	private long transportOutboundBytes;
	private long transportPumps;
	private long transportEmptyPumps;
	private long transportPumpMaxMillis;
	private long transportPumpBudgetHits;
	private long transportPumpFrameLimitHits;
	private long transportBacklogPumps;
	private long transportArrivalGapMaxMillis;
	private long transportArrivalGapsOver100Millis;
	private long transportArrivalGapsOver500Millis;
	private long transportArrivalGapsOver1000Millis;
	private int transportQueueMax;
	private int transportQueueEndMax;
	private int transportPumpMaxFrames;
	private int transportBufferedAmountMax;

	private WebSocketPacketBridge(IWebSocketClient socket, EmbeddedChannel channel, Connection connection,
			boolean eaglerFrames, boolean directTCP, EaglerXHandshake.Result eaglerProfile) {
		this.socket = socket;
		this.channel = channel;
		this.connection = connection;
		this.eaglerFrames = eaglerFrames;
		this.directTCP = directTCP;
		this.eaglerProfile = eaglerProfile;
		this.transportWindowStarted = EagRuntime.steadyTimeMillis();
		// A direct Eagler WebSocket is packet framed, just like the 1.8 client. Send
		// flushed packets immediately instead of waiting for the next 20 Hz pump and
		// then putting the send behind all queued inbound work. Relay WebSockets and
		// direct TCP remain byte streams and retain their existing coalescing.
		this.connection.setEaglerTransport(this::pump, this::close, eaglerFrames ? this::drainOutbound : null);
		if (eaglerProfile != null) {
			this.connection.setEaglerMessageTransport(eaglerProfile.handshakeVersion(), payload -> {
				byte[] frame = new byte[payload.length + 1];
				frame[0] = (byte) 0xEE;
				System.arraycopy(payload, 0, frame, 1, payload.length);
				this.socket.send(frame);
			});
		}
	}

	public static WebSocketPacketBridge connect(String uri, int timeoutMillis) {
		return connect(uri, timeoutMillis, null);
	}

	public static WebSocketPacketBridge connectEaglerX(String uri, int timeoutMillis, String username) {
		java.util.List<String> candidates = AddressResolver.eaglerXConnectionCandidates(uri);
		int attempts = candidates.size() == 1 ? 2 : candidates.size();
		int attemptTimeout = Math.max(3500, timeoutMillis / attempts);
		RuntimeException lastFailure = null;
		for (int i = 0; i < attempts; ++i) {
			String candidate = candidates.get(i % candidates.size());
			try {
				return connect(candidate, attemptTimeout, username);
			} catch (RuntimeException failure) {
				lastFailure = failure;
				System.err.println("[EaglerX] endpoint " + (i + 1) + "/" + attempts
					+ " failed (" + candidate + "): " + failure.getMessage());
				if (candidates.size() == 1 && !isRetryableEaglerFailure(failure)) {
					break;
				}
				if (i + 1 < attempts) {
					EagUtils.sleep(150);
				}
			}
		}
		throw lastFailure != null ? lastFailure
			: new IllegalStateException(Component.translatableWithFallback("multiplayer.connection.noEaglerXEndpoint",
					"No direct EaglerX endpoint is available").getString());
	}

	public static WebSocketPacketBridge connectDirectTCP(String host, int port, int timeoutMillis) {
		return connectSocket(PlatformNetworking.openDirectTCP(host, port), timeoutMillis, null, true);
	}

	private static WebSocketPacketBridge connect(String uri, int timeoutMillis, String eaglerUsername) {
		if (AddressResolver.isWispURI(uri)) {
			if (eaglerUsername != null) throw new IllegalArgumentException("Wisp uses Java server protocol");
			net.minecraft.client.multiplayer.resolver.ServerAddress target =
				net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(AddressResolver.extractTarget(uri, ""));
			String endpoint = AddressResolver.extractWispEndpoint(uri);
			IWebSocketClient relay = PlatformNetworking.openWebSocket(endpoint);
			if (relay == null) throw new IllegalStateException(Component.translatableWithFallback("multiplayer.connection.wispOpenFailed",
					"Could not open Wisp relay").getString());
			try {
				return connectSocket(new WispSocketClient(uri, relay, target.getHost(), target.getPort()),
					timeoutMillis, null, false);
			} catch (RuntimeException | Error failure) {
				relay.close();
				throw failure;
			}
		}
		return connectSocket(PlatformNetworking.openWebSocket(uri), timeoutMillis, eaglerUsername, false);
	}

	private static WebSocketPacketBridge connectSocket(IWebSocketClient socket, int timeoutMillis,
			String eaglerUsername, boolean directTCP) {
		if (socket == null || !socket.connectBlocking(timeoutMillis)) {
			String detail = socket != null ? socketCloseDetail(socket) : null;
			if (socket != null) {
				socket.close();
			}
			String base = (directTCP ? Component.translatableWithFallback("multiplayer.connection.directTcpFailed", "Could not connect directly to Minecraft server") : eaglerUsername != null
				? Component.translatableWithFallback("multiplayer.connection.eaglerWebSocketFailed", "Could not connect to direct EaglerX WebSocket")
				: Component.translatableWithFallback("multiplayer.connection.relayWebSocketFailed", "Could not connect to relay WebSocket")).getString();
			throw new IllegalStateException(detail == null ? base : base + ": " + detail);
		}
		try {
			boolean eaglerFrames = eaglerUsername != null;
			EaglerXHandshake.Result eaglerProfile = eaglerFrames
				? EaglerXHandshake.perform(socket, timeoutMillis, eaglerUsername)
				: null;

			final Connection connection = new Connection(PacketFlow.CLIENTBOUND);
			EmbeddedChannel channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
				@Override
				protected void initChannel(Channel channel) {
					ChannelPipeline pipeline = channel.pipeline();
					Connection.configureSerialization(pipeline, PacketFlow.CLIENTBOUND, false, null);
					connection.configurePacketHandler(pipeline);
				}
			});
			return new WebSocketPacketBridge(socket, channel, connection, eaglerFrames, directTCP, eaglerProfile);
		} catch (RuntimeException | Error failure) {
			try {
				socket.close();
			} catch (RuntimeException ignored) {
			}
			throw failure;
		}
	}

	private static boolean isRetryableEaglerFailure(RuntimeException failure) {
		String message = failure.getMessage();
		return message == null || message.startsWith("Could not connect")
				|| message.startsWith("Connection closed") || message.startsWith("Timed out");
	}

	public Connection getConnection() {
		return connection;
	}

	public EaglerXHandshake.Result getEaglerProfile() {
		return eaglerProfile;
	}

	public void pump() {
		if (closed) {
			return;
		}
		int queuedAtStart = socket.availableFrames();
		boolean transportClosed = socket.getState() != EnumEaglerConnectionState.CONNECTED;
		if (transportClosed && queuedAtStart == 0) {
			handleTransportClosed();
			return;
		}

		boolean perfDebug = EaglerClientPerf.isEnabled();
		IWebSocketFrame frame;
		int frames = 0;
		int frameLimit = WebSocketPumpPolicy.frameLimit(queuedAtStart);
		long timeBudgetMillis = WebSocketPumpPolicy.timeBudgetMillis(queuedAtStart);
		boolean budgetHit = false;
		boolean frameLimitHit = false;
		long pumpStarted = EagRuntime.steadyTimeMillis();
		ByteBuf directInboundBatch = null;
		int directInboundBatchFrames = 0;
		if (perfDebug) {
			if (transportClosed) {
				LOGGER.info("[EagPerfClient] draining {} final WebSocket frame(s) after transport close: {}",
						queuedAtStart, socketCloseDetail(socket));
			}
			transportPumps++;
			transportQueueMax = Math.max(transportQueueMax, queuedAtStart);
			if (queuedAtStart >= 512) {
				transportBacklogPumps++;
			}
		}
		while ((frame = socket.getNextFrame()) != null) {
			++frames;
			if (perfDebug) {
				long arrivalMillis = frame.getTimestamp();
				if (transportLastInboundMillis >= 0L && arrivalMillis >= transportLastInboundMillis) {
					long gapMillis = arrivalMillis - transportLastInboundMillis;
					transportArrivalGapMaxMillis = Math.max(transportArrivalGapMaxMillis, gapMillis);
					if (gapMillis > 100L) {
						transportArrivalGapsOver100Millis++;
					}
					if (gapMillis > 500L) {
						transportArrivalGapsOver500Millis++;
					}
					if (gapMillis > 1000L) {
						transportArrivalGapsOver1000Millis++;
					}
				}
				transportLastInboundMillis = arrivalMillis;
				transportInboundFrames++;
				transportInboundBytes += frame.getLength();
			}
			if (frame.isString()) {
				close();
				throw new IllegalStateException("Relay sent a text frame on the Minecraft byte stream");
			}
			byte[] bytes = frame.getByteArray();
			if (bytes != null && bytes.length != 0) {
				if (eaglerFrames && (bytes[0] & 0xFF) == 0xEE) {
					if (directInboundBatch != null) {
						channel.writeInbound(directInboundBatch);
						directInboundBatch = null;
						directInboundBatchFrames = 0;
					}
					byte[] payload = new byte[bytes.length - 1];
					System.arraycopy(bytes, 1, payload, 0, payload.length);
					connection.receiveEaglerMessage(payload);
				} else {
					if (eaglerFrames) {
						if (!receivedEaglerMinecraftFrame) {
							Component legacyDisconnect = readPostHandshakeLoginDisconnect(bytes);
							if (legacyDisconnect != null) {
								LOGGER.warn("Direct Eagler server sent a login-state disconnect after finish-login; "
									+ "the server's modern login transition failed: {}", legacyDisconnect.getString());
								connection.disconnect(legacyDisconnect);
								close();
								return;
							}
							receivedEaglerMinecraftFrame = true;
						}
						int framedLength = VarInt.getByteSize(bytes.length) + bytes.length;
						if (directInboundBatch != null && directInboundBatchFrames != 0
								&& directInboundBatch.readableBytes() + framedLength > DIRECT_INBOUND_BATCH_BYTES) {
							channel.writeInbound(directInboundBatch);
							directInboundBatch = null;
							directInboundBatchFrames = 0;
						}
						if (directInboundBatch == null) {
							directInboundBatch = Unpooled.buffer(Math.min(DIRECT_INBOUND_BATCH_BYTES,
								Math.max(64 * 1024, framedLength)));
						}
						VarInt.write(directInboundBatch, bytes.length);
						directInboundBatch.writeBytes(bytes);
						if (++directInboundBatchFrames >= DIRECT_INBOUND_BATCH_FRAMES
								|| directInboundBatch.readableBytes() >= DIRECT_INBOUND_BATCH_BYTES) {
							channel.writeInbound(directInboundBatch);
							directInboundBatch = null;
							directInboundBatchFrames = 0;
						}
					} else {
						channel.writeInbound(Unpooled.wrappedBuffer(bytes));
					}
				}
			}
			if (socket.availableFrames() > 0) {
				if (frames >= frameLimit) {
					frameLimitHit = true;
					break;
				}
				if (EagRuntime.steadyTimeMillis() - pumpStarted >= timeBudgetMillis) {
					budgetHit = true;
					break;
				}
			}
		}
		if (directInboundBatch != null) {
			channel.writeInbound(directInboundBatch);
		}
		int queuedAtEnd = socket.availableFrames();
		if (perfDebug && frames == 0) {
			transportEmptyPumps++;
		}
		channel.runPendingTasks();
		drainOutbound();
		if (perfDebug) {
			long pumpFinished = EagRuntime.steadyTimeMillis();
			transportPumpMaxMillis = Math.max(transportPumpMaxMillis, pumpFinished - pumpStarted);
			transportPumpMaxFrames = Math.max(transportPumpMaxFrames, frames);
			transportQueueEndMax = Math.max(transportQueueEndMax, queuedAtEnd);
			if (budgetHit) {
				transportPumpBudgetHits++;
			}
			if (frameLimitHit) {
				transportPumpFrameLimitHits++;
			}
			transportBufferedAmountMax = Math.max(transportBufferedAmountMax, socket.getBufferedAmount());
				logTransportWindow(pumpFinished);
			}
		if (socket.getState() != EnumEaglerConnectionState.CONNECTED && socket.availableFrames() == 0) {
			handleTransportClosed();
		}
	}

	/**
	 * Some unpatched modern Bungee EaglerXServer builds acknowledge the Eagler
	 * finish-login packet and then fail their PostLogin transition. They emit the
	 * old login-state disconnect (packet id 0 + JSON component) even though the
	 * connection has already entered configuration state, where id 0 means a cookie
	 * request. Preserve the server's actual error instead of reporting a misleading
	 * cookie identifier decoder failure. This is deliberately limited to the first
	 * direct-Eagler Minecraft frame and to a JSON-shaped payload.
	 */
	private static Component readPostHandshakeLoginDisconnect(byte[] bytes) {
		FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
		try {
			if (input.readVarInt() != 0) {
				return null;
			}
			String json = input.readUtf(262144);
			if (input.isReadable()) {
				return null;
			}
			String trimmed = json.trim();
			if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[' && trimmed.charAt(0) != '"')) {
				return null;
			}
			return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, LenientJsonParser.parse(trimmed))
				.result().orElse(Component.literal(trimmed));
		} catch (RuntimeException ignored) {
			return null;
		} finally {
			input.release();
		}
	}

	private void handleTransportClosed() {
		String detail = socketCloseDetail(socket);
		if (detail != null && connection.isConnected()) {
			connection.disconnect(directTCP
					? Component.translatableWithFallback("multiplayer.connection.directTcpClosed", "Direct TCP connection closed: %s", detail)
					: eaglerFrames
						? Component.translatableWithFallback("multiplayer.connection.eaglerWebSocketClosed", "Eagler WebSocket disconnected: %s", detail)
						: Component.translatableWithFallback("multiplayer.connection.relayWebSocketClosed", "Relay WebSocket disconnected: %s", detail));
		} else {
			close();
		}
	}

	private void logTransportWindow(long now) {
		if (now - transportWindowStarted < TRANSPORT_WINDOW_MILLIS) {
			return;
		}
		long currentGapMillis = transportLastInboundMillis < 0L ? -1L
				: Math.max(0L, now - transportLastInboundMillis);
		LOGGER.info("[EagPerfClient] transport type={} recv={}/{}B sent={}/{}B queueMax={} queueEndMax={} pumps/empty/backlog={}/{}/{} pumpMax={}ms/{}frames limits budget/frame={}/{} arrivalGap current/max={}/{}ms gaps>100/500/1000={}/{}/{} bufferedOutMax={}B",
				directTCP ? "direct-tcp" : eaglerFrames ? "eaglerx" : "relay", transportInboundFrames, transportInboundBytes,
				transportOutboundFrames, transportOutboundBytes, transportQueueMax, transportQueueEndMax,
				transportPumps, transportEmptyPumps, transportBacklogPumps, transportPumpMaxMillis,
				transportPumpMaxFrames, transportPumpBudgetHits, transportPumpFrameLimitHits,
				currentGapMillis, transportArrivalGapMaxMillis,
				transportArrivalGapsOver100Millis, transportArrivalGapsOver500Millis,
				transportArrivalGapsOver1000Millis, transportBufferedAmountMax);
		transportWindowStarted = now;
		transportInboundFrames = transportInboundBytes = 0L;
		transportOutboundFrames = transportOutboundBytes = 0L;
		transportPumps = transportEmptyPumps = transportPumpMaxMillis = 0L;
		transportPumpBudgetHits = transportPumpFrameLimitHits = transportBacklogPumps = 0L;
		transportArrivalGapMaxMillis = 0L;
		transportArrivalGapsOver100Millis = transportArrivalGapsOver500Millis = 0L;
		transportArrivalGapsOver1000Millis = 0L;
		transportQueueMax = 0;
		transportQueueEndMax = 0;
		transportPumpMaxFrames = 0;
		transportBufferedAmountMax = 0;
	}

	private static String socketCloseDetail(IWebSocketClient socket) {
		String reason = socket.getCloseReason();
		int code = socket.getCloseCode();
		if (reason != null && !reason.isBlank()) {
			return code > 0 ? reason + " (code " + code + ")" : reason;
		}
		return switch (code) {
		case 1000 -> "closed normally without a server reason (code 1000)";
		case 1001 -> "server or relay went away (code 1001)";
		case 1006 -> "connection dropped without a WebSocket close frame (code 1006)";
		case 1008 -> "relay rejected the connection by policy (code 1008)";
		case 1011 -> "relay or target connection failed (code 1011)";
		default -> code > 0 ? "closed without a server reason (code " + code + ")" : null;
		};
	}

	private void drainOutbound() {
		if (!channel.isOpen()) {
			return;
		}
		channel.flushOutbound();
		Object msg;
		ByteBuf relayAggregate = null;
		try {
			while ((msg = channel.readOutbound()) != null) {
				try {
				Object unpacked = HiddenByteBuf.unpack(msg);
				if (!(unpacked instanceof ByteBuf buf)) {
					throw new IllegalStateException("Unexpected relay pipeline output: " + unpacked);
				}
				ByteBuf bytes = buf.duplicate();
				if (eaglerFrames) {
					while (bytes.isReadable()) {
						int bodyLength = VarInt.read(bytes);
						if (bodyLength <= 0 || bodyLength > bytes.readableBytes()) {
							throw new IllegalStateException("Invalid outbound Eagler packet length " + bodyLength);
						}
						byte[] body = new byte[bodyLength];
						bytes.readBytes(body);
						socket.send(body);
						if (EaglerClientPerf.isEnabled()) {
							transportOutboundFrames++;
							transportOutboundBytes += body.length;
						}
					}
				} else if (bytes.isReadable()) {
					// A relay WebSocket is an unframed TCP byte stream. Coalesce everything Netty
					// produced in this pump into one WebSocket message: byte order is identical,
					// no timer or gameplay latency is added, and high-frequency Durable Object
					// message/request overhead is reduced.
					if (relayAggregate == null) {
						relayAggregate = Unpooled.buffer(bytes.readableBytes());
					}
					relayAggregate.writeBytes(bytes);
				}
				} finally {
					ReferenceCountUtil.release(msg);
				}
			}
			if (relayAggregate != null && relayAggregate.isReadable()) {
				byte[] body = new byte[relayAggregate.readableBytes()];
				relayAggregate.readBytes(body);
				socket.send(body);
				if (EaglerClientPerf.isEnabled()) {
					transportOutboundFrames++;
					transportOutboundBytes += body.length;
				}
			}
		} finally {
			ReferenceCountUtil.release(relayAggregate);
		}
	}

	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		socket.clearFrames();
		socket.close();
		channel.finishAndReleaseAll();
	}
}

package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.minecraft.network.Connection;
import net.minecraft.network.HiddenByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.MemoryServerHandshakePacketListenerImpl;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;

/**
 * Carries vanilla packets between the client and an integrated-server Web Worker.
 *
 * <p>The single-thread / desktop path pairs a netty {@code LocalServerChannel} (server)
 * with a {@code LocalChannel} (client) on ONE shared {@code EventLoopGroupHolder.local()}
 * event loop, passing {@code ByteBuf}s in-JVM. That cannot span two Web Workers — each JS
 * thread has its own heap and event loop. So on each side we run the vanilla serialization
 * pipeline ({@code Connection.configureInMemoryPipeline}) inside an {@link EmbeddedChannel},
 * then shuttle the encoded packet bytes across the worker boundary as
 * {@link ServerWorkerProtocol#DATA_CHANNEL} batches:
 *
 * <ul>
 * <li><b>outbound</b>: the local {@code Connection} writes {@code Packet}s → the pipeline's
 *     {@code PacketEncoder} + {@code LocalFrameEncoder} turn each into a {@link HiddenByteBuf}
 *     landing in the {@code EmbeddedChannel} outbound queue → {@link #drainOutbound()} unwraps
 *     each to its raw packet bytes and batches them ({@code [u32 LE len][bytes]×K}) for a
 *     transferable {@code postMessage}.</li>
 * <li><b>inbound</b>: a batch arrives from the other side → {@link #writeInboundBatch(byte[])}
 *     decodes each frame and {@code writeInbound}s a wrapped {@code ByteBuf}; the pipeline's
 *     {@code LocalFrameDecoder} (a no-op for a plain {@code ByteBuf}) + {@code PacketDecoder}
 *     turn it back into a {@code Packet} delivered to the {@code Connection}'s
 *     {@code packet_handler}.</li>
 * </ul>
 *
 * <p><b>Why the bytes are already framed by the pipeline itself:</b> in the in-memory
 * ({@code local=true}) configuration each write is one discrete packet {@code ByteBuf}
 * wrapped by {@code LocalFrameEncoder} (message-boundary framing, NOT a length prefix), so
 * every drained outbound message is exactly one complete packet. Our {@link ServerWorkerProtocol}
 * length prefix only frames packets WITHIN one cross-worker batch. Compression/encryption are
 * bypassed exactly as they are on the vanilla in-memory path (trusted, same-origin transport).
 *
 * <p>The local owner bridge is flagged
 * {@link Connection#setForcedMemoryConnection(boolean) forced-memory} so its 15s
 * keep-alive timeout stays disabled during cooperative spawn generation. Relayed LAN
 * guests are marked separately because their packet failures must not crash the server.
 */
public class WorkerPacketBridge {

	private static final Logger logger = LogManager.getLogger("WorkerPacketBridge");
	private static final int MAX_RAW_DRAIN_BYTES = 1024 * 1024;

	private final EmbeddedChannel channel;
	private final Connection connection;

	private WorkerPacketBridge(final EmbeddedChannel channel, final Connection connection) {
		this.channel = channel;
		this.connection = connection;
	}

   /** The vanilla {@code Connection} to hand to the client (as {@code pendingConnection})
    *  or to register server-side. */
	public Connection getConnection() {
		return connection;
	}

	/**
	 * [CLIENT, worker mode] mirror of {@link Connection#connectToLocalServer(java.net.SocketAddress)}
	 * over an {@link EmbeddedChannel} instead of a real {@code LocalChannel}. The returned
	 * bridge's {@link #getConnection()} is used exactly like the LocalChannel connection —
	 * {@code initiateServerboundPlayConnection} + {@code ServerboundHelloPacket} — but its
	 * bytes cross to the worker via {@link #drainOutbound()} / {@link #writeInboundBatch(byte[])}.
	 */
	public static WorkerPacketBridge createClientSide() {
		final Connection connection = new Connection(PacketFlow.CLIENTBOUND);
		connection.setForcedMemoryConnection(true);
		EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(final Channel channel) {
				ChannelPipeline pipeline = channel.pipeline();
				Connection.configureInMemoryPipeline(pipeline, PacketFlow.CLIENTBOUND);
				connection.configurePacketHandler(pipeline);
			}
		});
		logger.info("Created client-side worker packet bridge (EmbeddedChannel, forced-memory)");
		return new WorkerPacketBridge(ch, connection);
	}

	/**
	 * [WORKER] mirror of {@link net.minecraft.server.network.ServerConnectionListener#startMemoryChannel()}'s
	 * per-accept channel init, over an {@link EmbeddedChannel}: a SERVERBOUND {@code Connection}
	 * with the {@code MemoryServerHandshakePacketListenerImpl}, registered into the server's
	 * connection list so {@code ServerConnectionListener.tick()} ticks it. This is the worker
	 * end of the bridge; it is created once the {@code IntegratedServer} exists.
	 */
	public static WorkerPacketBridge createServerSide(final MinecraftServer server) {
		final Connection connection = new Connection(PacketFlow.SERVERBOUND);
		connection.setForcedMemoryConnection(true);
		connection.setListenerForServerboundHandshake(new MemoryServerHandshakePacketListenerImpl(server, connection));
		// register so the server ticks this connection (same list startMemoryChannel adds to)
		server.getConnection().getConnections().add(connection);
		EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(final Channel channel) {
				ChannelPipeline pipeline = channel.pipeline();
				Connection.configureInMemoryPipeline(pipeline, PacketFlow.SERVERBOUND);
				connection.configurePacketHandler(pipeline);
			}
		});
		logger.info("Created server-side worker packet bridge (EmbeddedChannel, forced-memory)");
		return new WorkerPacketBridge(ch, connection);
	}

	/**
	 * Server end of a relay-backed LAN peer. Unlike the trusted local player bridge this uses
	 * Minecraft's normal VarInt TCP framing and normal handshake listener. The relay transports
	 * opaque byte chunks, so compression/protocol transitions remain entirely inside vanilla's
	 * pipeline.
	 */
   public static WorkerPacketBridge createRemoteServerSide(final MinecraftServer server) {
      final Connection connection = new Connection(PacketFlow.SERVERBOUND);
      connection.setRelayedLANConnection(true);
      connection.setListenerForServerboundHandshake(new ServerHandshakePacketListenerImpl(server, connection));
		server.getConnection().getConnections().add(connection);
		EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(final Channel channel) {
				ChannelPipeline pipeline = channel.pipeline();
				Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, false, null);
				connection.configurePacketHandler(pipeline);
			}
		});
		logger.info("Created remote LAN worker packet bridge (normal TCP serialization, trusted LAN policy)");
		return new WorkerPacketBridge(ch, connection);
	}

	/**
	 * Drain all pending outbound packet bytes into one transferable batch, or {@code null}
	 * if the pipeline produced nothing this pass. Each outbound message is one complete
	 * packet (message-boundary framing of the in-memory pipeline); we unwrap the
	 * {@link HiddenByteBuf}, copy the readable bytes (the buffer is released here — the copy
	 * is what crosses the boundary), and length-frame them with {@link ServerWorkerProtocol}.
	 */
	public byte[] drainOutbound() {
		// Worker-mode teardown: once the bridged Connection is disconnected (Minecraft.disconnect
		// -> Connection.disconnect -> channel.close()) this EmbeddedChannel is closed. The exit
		// wait loop keeps pumping the bridge until the worker's STOP ack arrives, so flushing a
		// closed channel (or the pipeline writing to it) raises ClosedChannelException — EXPECTED
		// on a clean quit, but it was propagating out of runTick and crashing the client. Skip a
		// closed bridge; the client is leaving, so any un-drained outbound is irrelevant.
		if (!channel.isOpen()) {
			return null;
		}
		try {
			// flush queued writes + run any tasks the pipeline scheduled onto the embedded loop
			channel.flushOutbound();
			List<byte[]> frames = null;
			Object msg;
			while ((msg = channel.readOutbound()) != null) {
				try {
					Object unpacked = HiddenByteBuf.unpack(msg);
					if (unpacked instanceof ByteBuf) {
						ByteBuf buf = (ByteBuf) unpacked;
						byte[] arr = new byte[buf.readableBytes()];
						buf.getBytes(buf.readerIndex(), arr);
						if (frames == null) {
							frames = new ArrayList<>();
						}
						frames.add(arr);
					} else {
						logger.warn("Dropped non-ByteBuf outbound message from the bridge pipeline: {}",
								msg != null ? msg.getClass().getName() : "null");
					}
				} finally {
					ReferenceCountUtil.release(msg);
				}
			}
			if (frames == null) {
				return null;
			}
			return ServerWorkerProtocol.encodeBatch(frames);
		} catch (Throwable t) {
			// channel closed mid-drain during teardown — expected, not a crash
			if (isChannelClosed(t)) {
				return null;
			}
			throw t;
		}
	}

	/**
	 * Drain outbound network bytes without the worker-batch envelope. Used for LAN peers because
	 * the browser relay is a transparent TCP byte stream and WebSocket message boundaries carry no
	 * packet meaning.
	 */
	public byte[] drainOutboundRaw() {
		if (!channel.isOpen()) {
			return null;
		}
		try {
			channel.flushOutbound();
			List<byte[]> chunks = null;
			int total = 0;
			Object msg;
			// Bound each worker -> page -> WebSocket burst. A fresh LAN join can queue
			// tens of MiB of chunks in one server tick; flattening all of it into one
			// JS ArrayBuffer made Chrome retain huge temporary copies and could trip
			// Cloudflare/browser buffers before backpressure had a chance to engage.
			while (total < MAX_RAW_DRAIN_BYTES && (msg = channel.readOutbound()) != null) {
				try {
					Object unpacked = HiddenByteBuf.unpack(msg);
					if (unpacked instanceof ByteBuf) {
						ByteBuf buf = (ByteBuf) unpacked;
						byte[] arr = new byte[buf.readableBytes()];
						buf.getBytes(buf.readerIndex(), arr);
						if (arr.length != 0) {
							if (chunks == null) {
								chunks = new ArrayList<>();
							}
							chunks.add(arr);
							total += arr.length;
						}
					}
				} finally {
					ReferenceCountUtil.release(msg);
				}
			}
			if (chunks == null) {
				return null;
			}
			byte[] result = new byte[total];
			int offset = 0;
			for (int i = 0, l = chunks.size(); i < l; ++i) {
				byte[] chunk = chunks.get(i);
				System.arraycopy(chunk, 0, result, offset, chunk.length);
				offset += chunk.length;
			}
			return result;
		} catch (Throwable t) {
			if (isChannelClosed(t)) {
				return null;
			}
			throw t;
		}
	}

	/** Feed an arbitrary TCP byte chunk into a normal-serialization relay bridge. */
	public int writeInboundRaw(final byte[] bytes) {
		if (!channel.isOpen() || bytes == null || bytes.length == 0) {
			return 0;
		}
		try {
			channel.writeInbound(Unpooled.wrappedBuffer(bytes));
			channel.runPendingTasks();
			return bytes.length;
		} catch (Throwable t) {
			if (isChannelClosed(t)) {
				return 0;
			}
			throw t;
		}
	}

	public boolean isOpen() {
		return channel.isOpen() && connection.isConnected();
	}

	/**
	 * Feed one inbound batch (from the other side of the boundary) into the pipeline. Each
	 * framed packet is wrapped and {@code writeInbound}ed; the in-memory {@code LocalFrameDecoder}
	 * passes a plain {@code ByteBuf} straight to the {@code PacketDecoder}. Returns the number
	 * of frames delivered (diagnostics).
	 */
	public int writeInboundBatch(final byte[] batch) {
		// Teardown guard (see drainOutbound): after the bridged Connection disconnects this channel
		// is closed, but the other side may still send a final batch (e.g. the worker's server
		// removing players while it stops). Feeding a closed channel — or the pipeline writing a
		// reply on it — raises ClosedChannelException, which was crashing the client's exit. Drop
		// inbound for a closed bridge; the client is disconnecting.
		if (!channel.isOpen()) {
			return 0;
		}
		try {
			int frames = ServerWorkerProtocol.decodeBatch(batch, (packetBytes) -> {
				channel.writeInbound(Unpooled.wrappedBuffer(packetBytes));
			});
			// run any tasks the inbound handlers scheduled (e.g. deferred packet processing)
			channel.runPendingTasks();
			return frames;
		} catch (Throwable t) {
			// channel closed mid-write during teardown — expected, not a crash
			if (isChannelClosed(t)) {
				return 0;
			}
			throw t;
		}
	}

	/**
	 * Feed ONE already-decoded packet frame into the pipeline WITHOUT running pending tasks. Used by
	 * the metered inbound pump (framerate-spike fix): a burst of chunk packets from the fast native
	 * worldgen is spread across frames instead of being deserialized all in one frame on the render
	 * thread. The packet is handled synchronously (same-thread pipeline), so its deserialize cost is
	 * charged to the caller's per-frame time budget. Returns 1 if delivered, 0 if the bridge is torn
	 * down. Call {@link #runInboundTasks()} after each frame so deferred listener work is charged to
	 * that same budget rather than accumulating into one unmetered burst.
	 */
	public int writeInboundFrame(final byte[] frame) {
		if (!channel.isOpen()) {
			return 0;
		}
		try {
			channel.writeInbound(Unpooled.wrappedBuffer(frame));
			return 1;
		} catch (Throwable t) {
			if (isChannelClosed(t)) {
				return 0;
			}
			throw t;
		}
	}

	/** Run tasks the inbound handlers scheduled (deferred packet processing); pairs with each
	 *  {@link #writeInboundFrame} call. No-op on a torn-down bridge. */
	public void runInboundTasks() {
		if (!channel.isOpen()) {
			return;
		}
		try {
			channel.runPendingTasks();
		} catch (Throwable t) {
			if (!isChannelClosed(t)) {
				throw t;
			}
		}
	}

	/** True if the throwable (or any cause) is a netty channel-closed error — EXPECTED when the
	 *  bridge tears down on a clean quit, so it must not crash the client. */
	private static boolean isChannelClosed(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof java.nio.channels.ClosedChannelException) {
				return true;
			}
			if (c.getCause() == c) {
				break;
			}
		}
		return false;
	}

	/** Tear the bridge down (world unload / worker stop). */
	public void close() {
		try {
			channel.finishAndReleaseAll();
		} catch (Throwable t) {
			logger.error("Error closing worker packet bridge", t);
		}
	}

}

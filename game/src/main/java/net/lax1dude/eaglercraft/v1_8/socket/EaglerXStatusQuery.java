package net.lax1dude.eaglercraft.v1_8.socket;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.util.LenientJsonParser;

/**
 * Nonblocking implementation of the Eagler MOTD query protocol.
 */
public final class EaglerXStatusQuery {

	private static final long TIMEOUT_MILLIS = 22000L;
	private static final long ATTEMPT_TIMEOUT_MILLIS = 5000L;
	private static final long RETRY_DELAY_MILLIS = 350L;
	// Some Eagler endpoints send the 64x64 RGBA icon well after the MOTD frame.
	// The 1.8 client keeps the query open, so a two-second close here caused a
	// correct Arch MOTD to retain Minecraft's gray fallback image.
	private static final long ICON_TIMEOUT_MILLIS = 10000L;
	private static final long CACHE_TTL_MILLIS = 30L * 60L * 1000L;
	private static final int MAX_CACHE_ENTRIES = 64;
	// Arch's two public WebSocket names can independently return transient 521s.
	// Cycle over both names three times before declaring its MOTD unavailable.
	private static final int MAX_ATTEMPTS = 6;
	private static final int ICON_BYTES = 64 * 64 * 4;
	private static final byte[] PNG_SIGNATURE = new byte[] {
			(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
	};
	private static final Map<String, CachedStatus> STATUS_CACHE = new LinkedHashMap<String, CachedStatus>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CachedStatus> eldest) {
			return size() > MAX_CACHE_ENTRIES;
		}
	};
	private final String uri;
	private final List<String> candidates;
	private int candidateIndex;
	private IWebSocketClient socket;
	private final ServerData data;
	private final Runnable onPong;
	private final Runnable onPersistentDataChange;
	private final long started = Util.getMillis();
	private long attemptStarted;
	private long nextRetryAt;
	private int attempts;
	private boolean requestSent;
	private long requestStarted = -1L;
	private boolean statusReceived;
	private boolean awaitingIcon;
	private long iconStarted;
	private boolean done;

	public EaglerXStatusQuery(String uri, ServerData data, Runnable onPong, Runnable onPersistentDataChange) {
		this.uri = uri;
		this.candidates = AddressResolver.eaglerXConnectionCandidates(uri);
		this.data = data;
		this.onPong = onPong;
		this.onPersistentDataChange = onPersistentDataChange;
		data.motd = Component.translatable("multiplayer.status.pinging");
		data.playerList = List.of();
		openAttempt();
	}

	public boolean tick() {
		if (done) {
			return true;
		}
		long now = Util.getMillis();
		if (now - started > TIMEOUT_MILLIS) {
			failFinal();
			return true;
		}
		if (socket == null) {
			if (now >= nextRetryAt) {
				openAttempt();
			}
			return done;
		}
		if (!statusReceived && now - attemptStarted > ATTEMPT_TIMEOUT_MILLIS) {
			retryOrFail();
			return done;
		}
		if (awaitingIcon && now - iconStarted > ICON_TIMEOUT_MILLIS) {
			close();
			return true;
		}
		EnumEaglerConnectionState state = socket.getState();
		if (state == EnumEaglerConnectionState.CONNECTED && !requestSent) {
			requestStarted = EagRuntime.steadyTimeMillis();
			socket.send("Accept: MOTD");
			requestSent = true;
		}

		// Drain frames before interpreting CLOSED/FAILED. EaglerX MOTD endpoints
		// such as Arch send the JSON and icon and immediately close with 1006. The
		// browser can deliver all message events and the close event between two
		// game ticks, leaving valid frames queued on an already-closed socket.
		IWebSocketFrame frame;
		while ((frame = socket.getNextFrame()) != null) {
			if (frame.isString() && !statusReceived) {
				try {
					awaitingIcon = accept(frame.getString(), frame.getTimestamp());
					statusReceived = true;
					if (!awaitingIcon) {
						close();
						return true;
					}
					iconStarted = Util.getMillis();
				} catch (RuntimeException ex) {
					retryOrFail();
					return done;
				}
			} else if (!frame.isString() && awaitingIcon) {
				byte[] rgba = frame.getByteArray();
				if (rgba != null && rgba.length == ICON_BYTES) {
					try {
						data.setIconBytes(encodeIcon(rgba));
						cacheCurrent();
						onPersistentDataChange.run();
					} catch (IOException | RuntimeException ex) {
						data.setIconBytes(null);
					}
				}
				close();
				return true;
			}
		}
		if (state != EnumEaglerConnectionState.CONNECTING && state != EnumEaglerConnectionState.CONNECTED) {
			if (statusReceived) {
				close();
			} else {
				retryOrFail();
			}
			return done;
		}
		return false;
	}

	public void close() {
		if (!done) {
			done = true;
			if (socket != null) {
				socket.clearFrames();
				socket.close();
			}
		}
	}

	private void openAttempt() {
		attempts++;
		attemptStarted = Util.getMillis();
		requestSent = false;
		requestStarted = -1L;
		statusReceived = false;
		awaitingIcon = false;
		String candidate = candidates.get(candidateIndex % candidates.size());
		candidateIndex++;
		socket = PlatformNetworking.openWebSocket(candidate);
		if (socket == null) {
			retryOrFail();
		}
	}

	private void retryOrFail() {
		if (socket != null) {
			socket.clearFrames();
			socket.close();
			socket = null;
		}
		if (attempts < MAX_ATTEMPTS && Util.getMillis() - started + RETRY_DELAY_MILLIS < TIMEOUT_MILLIS) {
			nextRetryAt = Util.getMillis() + RETRY_DELAY_MILLIS;
		} else {
			failFinal();
		}
	}

	private boolean accept(String json, long responseReceived) {
		JsonObject root = LenientJsonParser.parse(json).getAsJsonObject();
		JsonObject payload = root.has("data") && root.get("data").isJsonObject()
			? root.getAsJsonObject("data")
			: root;
		JsonArray motd = payload.has("motd") && payload.get("motd").isJsonArray()
			? payload.getAsJsonArray("motd")
			: new JsonArray();
		StringBuilder description = new StringBuilder();
		for (JsonElement line : motd) {
			if (description.length() != 0) {
				description.append('\n');
			}
			description.append(line.getAsString());
		}
		data.motd = Component.literal(description.toString());
		int online = intOr(payload, "online", 0);
		int max = intOr(payload, "max", 0);
		List<NameAndId> sample = new ArrayList<>();
		if (payload.has("players") && payload.get("players").isJsonArray()) {
			for (JsonElement player : payload.getAsJsonArray("players")) {
				if (player.isJsonPrimitive()) {
					sample.add(NameAndId.createOffline(player.getAsString()));
				}
			}
		}
		data.players = new ServerStatus.Players(max, online, sample);
		data.status = ServerStatusPinger.formatPlayerCount(online, max);
		List<Component> playerList = new ArrayList<>(sample.size());
		for (NameAndId profile : sample) {
			playerList.add(Component.literal(profile.name()));
		}
		data.playerList = playerList;
		String version = stringOr(root, "vers", "EaglerX");
		data.version = Component.literal(version);
		data.protocol = SharedConstants.getCurrentVersion().protocolVersion();
		// Status-row latency is the MOTD request/response time, not WebSocket/TLS
		// setup, endpoint retry, or the delay before the render thread drains this
		// frame. Gameplay keepalive latency is measured independently by the server.
		data.ping = requestStarted >= 0L
			? Math.max(1L, responseReceived - requestStarted)
			: Math.max(1L, Util.getMillis() - attemptStarted);
		cacheCurrent();
		onPong.run();
		return payload.has("icon") && payload.get("icon").isJsonPrimitive() && payload.get("icon").getAsBoolean();
	}

	private static byte[] encodeIcon(byte[] rgba) throws IOException {
		ByteArrayOutputStream compressed = new ByteArrayOutputStream(rgba.length + 64);
		try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
			for (int y = 0; y < 64; ++y) {
				deflater.write(0);
				deflater.write(rgba, y * 256, 256);
			}
		}

		ByteArrayOutputStream png = new ByteArrayOutputStream(compressed.size() + 80);
		try (DataOutputStream output = new DataOutputStream(png)) {
			output.write(PNG_SIGNATURE);
			ByteArrayOutputStream headerBytes = new ByteArrayOutputStream(13);
			try (DataOutputStream header = new DataOutputStream(headerBytes)) {
				header.writeInt(64);
				header.writeInt(64);
				header.writeByte(8);
				header.writeByte(6);
				header.writeByte(0);
				header.writeByte(0);
				header.writeByte(0);
			}
			writePngChunk(output, "IHDR", headerBytes.toByteArray());
			writePngChunk(output, "IDAT", compressed.toByteArray());
			writePngChunk(output, "IEND", new byte[0]);
		}
		return png.toByteArray();
	}

	private static void writePngChunk(DataOutputStream output, String type, byte[] data) throws IOException {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		output.writeInt(data.length);
		output.write(typeBytes);
		output.write(data);
		output.writeInt((int)crc.getValue());
	}

	private void failFinal() {
		CachedStatus cached;
		synchronized (STATUS_CACHE) {
			cached = STATUS_CACHE.get(uri);
			if (cached != null && Util.getMillis() - cached.savedAt > CACHE_TTL_MILLIS) {
				STATUS_CACHE.remove(uri);
				cached = null;
			}
		}
		if (cached != null) {
			cached.apply(data);
			data.motd = data.motd.copy().append(Component.translatableWithFallback("multiplayer.status.cachedUnavailable",
					"\nCached status; direct WebSocket unavailable").withStyle(ChatFormatting.YELLOW));
			data.setState(ServerData.State.SUCCESSFUL);
		} else {
			data.motd = Component.translatableWithFallback("multiplayer.status.unavailable",
					"Direct WebSocket status unavailable").withColor(-65536);
			data.status = CommonComponents.EMPTY;
			data.setState(ServerData.State.UNREACHABLE);
		}
		// Reuse the completion callback to refresh the row immediately. The caller
		// preserves the explicit UNREACHABLE state instead of deriving it from protocol.
		onPong.run();
		close();
	}

	private void cacheCurrent() {
		synchronized (STATUS_CACHE) {
			STATUS_CACHE.put(uri, new CachedStatus(data));
		}
	}

	private static final class CachedStatus {
		private final long savedAt = Util.getMillis();
		private final Component motd;
		private final Component status;
		private final Component version;
		private final ServerStatus.Players players;
		private final List<Component> playerList;
		private final long ping;
		private final int protocol;
		private final byte[] icon;

		private CachedStatus(ServerData data) {
			this.motd = data.motd;
			this.status = data.status;
			this.version = data.version;
			this.players = data.players;
			this.playerList = List.copyOf(data.playerList);
			this.ping = data.ping;
			this.protocol = data.protocol;
			this.icon = data.getIconBytes() == null ? null : Arrays.copyOf(data.getIconBytes(), data.getIconBytes().length);
		}

		private void apply(ServerData data) {
			data.motd = this.motd;
			data.status = this.status;
			data.version = this.version;
			data.players = this.players;
			data.playerList = this.playerList;
			data.ping = this.ping;
			data.protocol = this.protocol;
			if (this.icon != null) {
				data.setIconBytes(Arrays.copyOf(this.icon, this.icon.length));
			}
		}
	}

	private static int intOr(JsonObject object, String key, int fallback) {
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : fallback;
	}

	private static String stringOr(JsonObject object, String key, String fallback) {
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
	}
}

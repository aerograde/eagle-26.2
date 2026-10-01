package net.lax1dude.eaglercraft.v1_8.socket;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;
import net.lax1dude.eaglercraft.v1_8.profile.EaglerProfile;
import net.minecraft.SharedConstants;

/**
 * Minimal direct EaglerX v3-v5 handshake. Authentication extensions are left to
 * a future login UI; public/offline-mode servers use AUTH_METHOD_NONE.
 */
public final class EaglerXHandshake {
	private static final long CLOSED_FRAME_GRACE_MILLIS = 100L;

	private static final int CLIENT_VERSION = 0x01;
	private static final int SERVER_VERSION = 0x02;
	private static final int VERSION_MISMATCH = 0x03;
	private static final int CLIENT_REQUEST_LOGIN = 0x04;
	private static final int SERVER_ALLOW_LOGIN = 0x05;
	private static final int SERVER_DENY_LOGIN = 0x06;
	private static final int CLIENT_PROFILE_DATA = 0x07;
	private static final int CLIENT_FINISH_LOGIN = 0x08;
	private static final int SERVER_FINISH_LOGIN = 0x09;
	private static final int SERVER_REDIRECT = 0x0A;
	private static final int SERVER_ERROR = 0xFF;

	private EaglerXHandshake() {
	}

	public record Result(String username, UUID uuid, int handshakeVersion) {
	}

	public static Result perform(IWebSocketClient socket, int timeoutMillis, String requestedUsername) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		String username = sanitizeASCII(requestedUsername, 16, "Player");
		sendClientVersion(socket, username);

		Reader version = new Reader(awaitBinary(socket, deadline, "server version"));
		int packet = version.u8();
		if (packet == VERSION_MISMATCH) {
			throw new IllegalStateException(readVersionMismatch(version));
		}
		if (packet == SERVER_ERROR) {
			throw new IllegalStateException(readServerError(version, false));
		}
		if (packet != SERVER_VERSION) {
			throw new IllegalStateException("Unexpected Eagler handshake packet " + packet);
		}

		int handshakeVersion = version.u16();
		int gameProtocol = version.u16();
		if (handshakeVersion < 3 || handshakeVersion > 5) {
			throw new IllegalStateException("Server selected unsupported Eagler handshake v" + handshakeVersion);
		}
		if (gameProtocol != SharedConstants.getCurrentVersion().protocolVersion()) {
			throw new IllegalStateException(
				"Server selected Minecraft protocol " + gameProtocol + ", client requires "
					+ SharedConstants.getCurrentVersion().protocolVersion()
			);
		}
		version.ascii(version.u8()); // plugin brand
		version.ascii(version.u8()); // plugin version
		int authType = version.u8();
		version.bytes(version.u16()); // auth salt
		boolean nicknameSelection = handshakeVersion < 5 || !version.hasRemaining() || version.bool();
		if (authType != 0) {
			throw new IllegalStateException("This server requires Eagler authentication method " + authType);
		}

		sendLoginRequest(socket, handshakeVersion, nicknameSelection ? username : "");
		Reader allow = new Reader(awaitBinary(socket, deadline, "login response"));
		packet = allow.u8();
		if (packet == SERVER_DENY_LOGIN) {
			int length = handshakeVersion == 3 ? allow.u16() : allow.u8();
			throw new IllegalStateException(allow.utf8(length));
		}
		if (packet == SERVER_ERROR) {
			throw new IllegalStateException(readServerError(allow, handshakeVersion == 3));
		}
		if (packet == SERVER_REDIRECT) {
			throw new IllegalStateException("Server redirected login to " + allow.utf8(allow.u16()));
		}
		if (packet != SERVER_ALLOW_LOGIN) {
			throw new IllegalStateException("Unexpected Eagler login packet " + packet);
		}

		String acceptedUsername = allow.ascii(allow.u8());
		UUID uuid = new UUID(allow.i64(), allow.i64());
		sendSelectedProfile(socket, handshakeVersion);
		socket.send(new byte[]{(byte) CLIENT_FINISH_LOGIN});

		Reader finished = new Reader(awaitBinary(socket, deadline, "login completion"));
		packet = finished.u8();
		if (packet == SERVER_ERROR) {
			throw new IllegalStateException(readServerError(finished, handshakeVersion == 3));
		}
		if (packet != SERVER_FINISH_LOGIN) {
			throw new IllegalStateException("Unexpected Eagler login completion packet " + packet);
		}
		return new Result(acceptedUsername.isEmpty() ? username : acceptedUsername, uuid, handshakeVersion);
	}

	private static void sendClientVersion(IWebSocketClient socket, String username) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(96);
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeByte(CLIENT_VERSION);
			out.writeByte(2);
			out.writeShort(3);
			out.writeShort(3);
			out.writeShort(4);
			out.writeShort(5);
			out.writeShort(1);
			out.writeShort(SharedConstants.getCurrentVersion().protocolVersion());
			writeASCII8(out, "Eaglercraft 26.2");
			writeASCII8(out, "26.2");
			out.writeBoolean(false);
			writeASCII8(out, username);
			socket.send(bytes.toByteArray());
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void sendLoginRequest(IWebSocketClient socket, int version, String username) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeByte(CLIENT_REQUEST_LOGIN);
			writeASCII8(out, username);
			writeASCII8(out, "default");
			out.writeByte(0); // password
			if (version >= 4) {
				out.writeBoolean(false); // cookies disabled
				out.writeByte(0);
			}
			if (version >= 5) {
				out.writeByte(0); // standard capability varint
				out.writeByte(0); // extended capability count
			}
			socket.send(bytes.toByteArray());
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void sendSelectedProfile(IWebSocketClient socket, int version) {
		byte[] skin = EaglerProfile.getSkinPacket(version);
		byte[] cape = EaglerProfile.getCapePacket();
		if (version == 3) {
			socket.send(profilePacket("skin_v1", skin, false));
			socket.send(profilePacket("cape_v1", cape, false));
		} else {
			try {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream(48);
				DataOutputStream out = new DataOutputStream(bytes);
				out.writeByte(CLIENT_PROFILE_DATA);
				out.writeByte(2);
				writeProfileEntry(out, "skin_v2", skin);
				writeProfileEntry(out, "cape_v1", cape);
				socket.send(bytes.toByteArray());
			} catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

	private static byte[] profilePacket(String type, byte[] data, boolean bundled) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeByte(CLIENT_PROFILE_DATA);
			if (bundled) {
				out.writeByte(1);
			}
			writeProfileEntry(out, type, data);
			return bytes.toByteArray();
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void writeProfileEntry(DataOutputStream out, String type, byte[] data) throws IOException {
		writeASCII8(out, type);
		out.writeShort(data.length);
		out.write(data);
	}

	private static byte[] awaitBinary(IWebSocketClient socket, long deadline, String stage) {
		long closedAt = -1L;
		while (System.currentTimeMillis() < deadline) {
			IWebSocketFrame frame = socket.getNextFrame();
			if (frame != null) {
				if (!frame.isString()) {
					byte[] data = frame.getByteArray();
					if (data != null && data.length != 0) {
						return data;
					}
				}
			} else if (socket.getState() != EnumEaglerConnectionState.CONNECTED) {
				long now = System.currentTimeMillis();
				if (closedAt < 0L) {
					closedAt = now;
				}
				if (now - closedAt >= CLOSED_FRAME_GRACE_MILLIS) {
					throw new IllegalStateException("Connection closed during Eagler " + stage + closeDetail(socket));
				}
				EagUtils.sleep(1);
			} else {
				closedAt = -1L;
				EagUtils.sleep(1);
			}
		}
		throw new IllegalStateException("Timed out waiting for Eagler " + stage);
	}

	private static String closeDetail(IWebSocketClient socket) {
		String reason = socket.getCloseReason();
		int code = socket.getCloseCode();
		if (reason != null && !reason.isBlank()) {
			return code > 0 ? ": " + reason + " (code " + code + ")" : ": " + reason;
		}
		return code > 0 ? ": closed with code " + code : "";
	}

	private static String readVersionMismatch(Reader reader) {
		int protocols = reader.u16();
		StringBuilder message = new StringBuilder("Incompatible Eagler server (handshake");
		for (int i = 0; i < protocols; ++i) {
			message.append(i == 0 ? " v" : ", v").append(reader.u16());
		}
		int games = reader.u16();
		message.append("; Minecraft");
		for (int i = 0; i < games; ++i) {
			message.append(i == 0 ? " " : ", ").append(reader.u16());
		}
		if (reader.hasRemaining()) {
			message.append("): ").append(reader.utf8(reader.u8()));
		} else {
			message.append(')');
		}
		return message.toString();
	}

	private static String readServerError(Reader reader, boolean v3) {
		int code = reader.u8();
		int length = v3 ? reader.u16() : reader.u8();
		return "Eagler server rejected login (" + code + "): " + reader.utf8(length);
	}

	private static String sanitizeASCII(String value, int maxLength, String fallback) {
		StringBuilder result = new StringBuilder(maxLength);
		if (value != null) {
			for (int i = 0; i < value.length() && result.length() < maxLength; ++i) {
				char c = value.charAt(i);
				if (c >= 32 && c <= 126) {
					result.append(c);
				}
			}
		}
		return result.isEmpty() ? fallback : result.toString();
	}

	private static void writeASCII8(DataOutputStream out, String value) throws IOException {
		byte[] bytes = sanitizeASCII(value, 255, "").getBytes(StandardCharsets.US_ASCII);
		out.writeByte(bytes.length);
		out.write(bytes);
	}

	private static final class Reader {
		private final byte[] data;
		private int index;

		private Reader(byte[] data) {
			this.data = data;
		}

		private boolean hasRemaining() {
			return index < data.length;
		}

		private int u8() {
			require(1);
			return data[index++] & 0xFF;
		}

		private int u16() {
			return (u8() << 8) | u8();
		}

		private long i64() {
			return ((long)u8() << 56) | ((long)u8() << 48) | ((long)u8() << 40) | ((long)u8() << 32)
				| ((long)u8() << 24) | ((long)u8() << 16) | ((long)u8() << 8) | u8();
		}

		private boolean bool() {
			return u8() != 0;
		}

		private byte[] bytes(int length) {
			require(length);
			byte[] result = new byte[length];
			System.arraycopy(data, index, result, 0, length);
			index += length;
			return result;
		}

		private String ascii(int length) {
			return new String(bytes(length), StandardCharsets.US_ASCII);
		}

		private String utf8(int length) {
			return new String(bytes(length), StandardCharsets.UTF_8);
		}

		private void require(int length) {
			if (length < 0 || index + length > data.length) {
				throw new IllegalStateException("Truncated Eagler handshake packet");
			}
		}
	}
}

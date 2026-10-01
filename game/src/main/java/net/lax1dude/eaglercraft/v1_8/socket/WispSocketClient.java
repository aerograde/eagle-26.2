package net.lax1dude.eaglercraft.v1_8.socket;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.AbstractWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;

/**
 * A single TCP stream over the Wisp v1 protocol used by Wispcraft.
 * Specification: MercuryWorkshop/wisp-protocol, v1/protocol.md (ading2210,
 * CC-BY-4.0). Minecraft framing, encryption and compression remain in Netty.
 * Each connection owns its relay socket, so closing a status ping cannot close
 * the game connection and no global WebSocket or fetch interception is needed.
 */
public final class WispSocketClient extends AbstractWebSocketClient {
    private static final int MAX_PAYLOAD = 65536;
    private static final int MAX_PENDING_BYTES = 4 * 1024 * 1024;
    private final IWebSocketClient relay;
    private final byte[] connectPacket;
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private int pendingBytes;
    private long credits;
    private boolean ready;
    private boolean closed;
    private String failure;

    public WispSocketClient(String uri, IWebSocketClient relay, String host, int port) {
        super(uri);
        if (relay == null || host == null || host.isBlank() || host.length() > 253
                || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid Wisp destination");
        this.relay = relay;
        byte[] hostname = host.getBytes(StandardCharsets.UTF_8);
        connectPacket = packet(1, hostname.length + 3);
        connectPacket[5] = 1; // TCP
        connectPacket[6] = (byte) port;
        connectPacket[7] = (byte) (port >>> 8);
        System.arraycopy(hostname, 0, connectPacket, 8, hostname.length);
    }

    private static byte[] packet(int type, int length) {
        byte[] result = new byte[5 + length];
        result[0] = (byte) type;
        result[1] = 1; // one stream per owned socket
        return result;
    }

    private static long uint32(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
                | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
    }

    private void fail(String reason) {
        failure = reason;
        close();
    }

    /** Stop at one payload so the existing packet pump still owns its CPU budget. */
    private void pump() {
        if (closed || super.availableFrames() != 0) return;
        try {
            for (int i = 0; i < 64; i++) {
                IWebSocketFrame frame = relay.getNextFrame();
                if (frame == null) break;
                if (frame.isString()) { fail("Wisp relay sent a text frame"); return; }
                byte[] bytes = frame.getByteArray();
                if (bytes.length < 5) { fail("Truncated Wisp packet"); return; }
                int type = bytes[0] & 255;
                long stream = uint32(bytes, 1);
                if (!ready) {
                    if (type != 3 || stream != 0 || bytes.length != 9) {
                        fail("Expected Wisp v1 greeting; check the relay type and URL"); return;
                    }
                    credits = uint32(bytes, 5);
                    relay.send(connectPacket);
                    ready = true;
                    flush();
                } else if (stream == 1 && type == 2) {
                    if (bytes.length > 5) {
                        addRecievedFrame(new BinaryFrame(Arrays.copyOfRange(bytes, 5, bytes.length), frame.getTimestamp()));
                        return;
                    }
                } else if (stream == 1 && type == 3 && bytes.length == 9) {
                    credits = uint32(bytes, 5); // absolute window, not an increment
                    flush();
                } else if ((stream == 0 || stream == 1) && type == 4 && bytes.length == 6) {
                    int reason = bytes[5] & 255;
                    if (reason == 2) close();
                    else fail(closeReason(reason));
                    return;
                } else {
                    fail("Unexpected Wisp packet type " + type + " on stream " + stream); return;
                }
            }
            if (relay.isClosed()) {
                String reason = relay.getCloseReason();
                fail(reason == null || reason.isBlank() ? "Wisp relay connection closed" : "Wisp relay: " + reason);
            }
        } catch (RuntimeException error) {
            fail("Wisp transport failed: " + error.getMessage());
        }
    }

    private static String closeReason(int reason) {
        return switch (reason) {
            case 0x41 -> "Wisp relay rejected the destination";
            case 0x42 -> "Wisp destination host could not be reached";
            case 0x43 -> "Wisp destination connection timed out";
            case 0x44 -> "Minecraft server refused the Wisp connection";
            case 0x47 -> "Wisp TCP transfer timed out";
            case 0x48 -> "Wisp relay blocks this destination";
            case 0x49 -> "Wisp relay connection limit reached";
            default -> "Wisp stream closed (reason " + reason + ")";
        };
    }

    private void flush() {
        while (!closed && ready && credits > 0 && !pending.isEmpty()) {
            byte[] bytes = pending.removeFirst();
            pendingBytes -= bytes.length;
            --credits;
            relay.send(bytes);
        }
    }

    @Override public boolean connectBlocking(int timeoutMS) {
        long deadline = EagRuntime.steadyTimeMillis() + timeoutMS;
        while (!closed && !ready) {
            pump();
            if (ready || closed) break;
            if (EagRuntime.steadyTimeMillis() >= deadline) { fail("Wisp greeting timed out"); break; }
            EagUtils.sleep(10);
        }
        return ready && !closed;
    }

    @Override public EnumEaglerConnectionState getState() {
        pump();
        return failure != null ? EnumEaglerConnectionState.FAILED : closed ? EnumEaglerConnectionState.CLOSED
                : ready ? EnumEaglerConnectionState.CONNECTED : EnumEaglerConnectionState.CONNECTING;
    }
    @Override public boolean isOpen() { return getState() == EnumEaglerConnectionState.CONNECTED; }
    @Override public boolean isClosed() { pump(); return closed; }
    @Override public int getCloseCode() { return failure != null ? 1006 : closed ? 1000 : 0; }
    @Override public String getCloseReason() { return failure; }
    @Override public int getBufferedAmount() { return (int) Math.min(Integer.MAX_VALUE, (long) pendingBytes + relay.getBufferedAmount()); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        pendingBytes = 0;
        clearFrames();
        try {
            if (ready && relay.isOpen()) {
                byte[] end = packet(4, 1);
                end[5] = 2;
                relay.send(end);
            }
        } finally { relay.close(); }
    }

    @Override public void send(String text) { throw new UnsupportedOperationException("Wisp TCP requires binary data"); }
    @Override public void send(byte[] bytes) {
        if (closed || bytes == null || bytes.length == 0) return;
        // Bound only waiting data, never drop packets or keep growing a dead stream.
        long needed = (long) bytes.length + 5L * ((bytes.length + (long) MAX_PAYLOAD - 1) / MAX_PAYLOAD);
        if (needed + pendingBytes > MAX_PENDING_BYTES) { fail("Wisp send queue exceeded 4 MiB while waiting for relay capacity"); return; }
        try {
            for (int offset = 0; offset < bytes.length; offset += MAX_PAYLOAD) {
                int length = Math.min(MAX_PAYLOAD, bytes.length - offset);
                byte[] frame = packet(2, length);
                System.arraycopy(bytes, offset, frame, 5, length);
                pending.addLast(frame);
                pendingBytes += frame.length;
            }
            flush();
        } catch (RuntimeException error) { fail("Wisp send failed: " + error.getMessage()); }
    }

    @Override public int availableFrames() { pump(); return super.availableFrames(); }
    @Override public int availableBinaryFrames() { pump(); return super.availableBinaryFrames(); }
    @Override public IWebSocketFrame getNextFrame() { pump(); return super.getNextFrame(); }
    @Override public IWebSocketFrame getNextBinaryFrame() { pump(); return super.getNextBinaryFrame(); }
    @Override public List<IWebSocketFrame> getNextFrames() { pump(); return super.getNextFrames(); }
    @Override public List<IWebSocketFrame> getNextBinaryFrames() { pump(); return super.getNextBinaryFrames(); }

    private record BinaryFrame(byte[] bytes, long timestamp) implements IWebSocketFrame {
        @Override public boolean isString() { return false; }
        @Override public String getString() { throw new UnsupportedOperationException("Binary TCP data"); }
        @Override public byte[] getByteArray() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public int getLength() { return bytes.length; }
        @Override public long getTimestamp() { return timestamp; }
    }
}

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.util.ArrayList;
import java.util.List;

import org.teavm.jso.typedarrays.Int8Array;

import net.lax1dude.eaglercraft.v1_8.internal.AbstractWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformWebRTC;

/**
 * Presents the relay-free direct-connect data channel as the byte-stream socket
 * expected by the 26.2 Netty bridge. The URI {@code eagler-direct:...} carries
 * no signaling endpoint; the WebRTC session is created by the platform's direct
 * connect calls (offer/answer codes pasted by the users) and this client only
 * attaches to it.
 */
public final class DirectLANWebSocketClient extends AbstractWebSocketClient {

	public static final String URI_PREFIX = "eagler-direct:";
	private static final int DATA_FRAGMENT_BYTES = 60 * 1024;
	private static final int MAX_PUMP_PACKETS = 16;
	private static final int MAX_PUMP_BYTES = 1024 * 1024;

	private boolean connecting = true;
	private boolean connected;
	private boolean failed;
	private int closeCode;
	private String closeReason;

	public DirectLANWebSocketClient(String uri) {
		super(uri);
		if(uri == null || !uri.startsWith(URI_PREFIX)) {
			throw new IllegalArgumentException("Invalid direct connect URI");
		}
	}

	@Override
	public boolean connectBlocking(int timeoutMillis) {
		long deadline = net.lax1dude.eaglercraft.v1_8.EagRuntime.steadyTimeMillis() + Math.max(1000, timeoutMillis);
		while(net.lax1dude.eaglercraft.v1_8.EagRuntime.steadyTimeMillis() < deadline) {
			PlatformWebRTC.runScheduledTasks();
			if(PlatformWebRTC.directGuestChannelOpen()) {
				connecting = false;
				connected = true;
				return true;
			}
			if(PlatformWebRTC.directGuestLinkDead()) {
				return fail("The direct connect session was closed");
			}
			net.lax1dude.eaglercraft.v1_8.EagUtils.sleep(10);
		}
		return fail("Timed out waiting for the direct connect data channel");
	}

	private boolean fail(String reason) {
		connecting = false;
		connected = false;
		failed = true;
		closeCode = 1006;
		closeReason = reason;
		return false;
	}

	private void refill() {
		if(super.availableFrames() != 0) return;
		List<byte[]> packets = PlatformWebRTC.directGuestReadPackets(MAX_PUMP_PACKETS, MAX_PUMP_BYTES);
		if(packets != null) {
			for(byte[] packet : packets) {
				addRecievedFrame(new TeaVMWebSocketFrame(Int8Array.fromJavaArray(packet).getBuffer()));
			}
		}
	}

	private void updateClosedState() {
		if(PlatformWebRTC.directGuestLinkDead()) {
			if(connected || connecting) {
				connected = false;
				connecting = false;
				closeCode = 1006;
				closeReason = "Direct connect data channel disconnected";
			}
		}
	}

	@Override
	public int availableFrames() {
		long available = (long)super.availableFrames() + PlatformWebRTC.directGuestPendingPacketCount();
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
		PlatformWebRTC.directGuestClose();
	}

	@Override public void send(String str) { throw new UnsupportedOperationException("Direct connect is binary only"); }

	@Override
	public void send(byte[] bytes) {
		if(!connected || bytes == null) {
			return;
		}
		for(int offset = 0; offset < bytes.length; offset += DATA_FRAGMENT_BYTES) {
			int length = Math.min(DATA_FRAGMENT_BYTES, bytes.length - offset);
			if(offset == 0 && length == bytes.length) {
				PlatformWebRTC.directGuestSendPacket(bytes);
			}else {
				byte[] fragment = new byte[length];
				System.arraycopy(bytes, offset, fragment, 0, length);
				PlatformWebRTC.directGuestSendPacket(fragment);
			}
		}
	}
}

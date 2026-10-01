package net.lax1dude.eaglercraft.v1_8.internal;

import java.io.InputStream;
import java.util.List;

public class AbstractWebSocketClientQueueTest {

	private static final class Frame implements IWebSocketFrame {
		private final String id;
		private final boolean string;

		private Frame(String id, boolean string) {
			this.id = id;
			this.string = string;
		}

		@Override
		public boolean isString() {
			return string;
		}

		@Override
		public String getString() {
			return string ? id : null;
		}

		@Override
		public byte[] getByteArray() {
			return string ? null : new byte[] { (byte)id.charAt(0) };
		}

		@Override
		public InputStream getInputStream() {
			return null;
		}

		@Override
		public int getLength() {
			return 1;
		}

		@Override
		public long getTimestamp() {
			return 0L;
		}
	}

	private static final class Client extends AbstractWebSocketClient {
		private Client() {
			super("test");
		}

		private void add(Frame frame) {
			addRecievedFrame(frame);
		}

		@Override
		public EnumEaglerConnectionState getState() {
			return EnumEaglerConnectionState.CONNECTED;
		}

		@Override
		public boolean connectBlocking(int timeoutMS) {
			return true;
		}

		@Override
		public boolean isOpen() {
			return true;
		}

		@Override
		public boolean isClosed() {
			return false;
		}

		@Override
		public void close() {
		}

		@Override
		public void send(String str) {
		}

		@Override
		public void send(byte[] bytes) {
		}
	}

	private static void require(boolean value, String message) {
		if(!value) {
			throw new AssertionError(message);
		}
	}

	private static void requireFrame(IWebSocketFrame frame, String id) {
		require(frame != null && id.equals(frame.getString()), "expected frame " + id);
	}

	private static void requireIds(List<IWebSocketFrame> frames, String... ids) {
		require(frames != null && frames.size() == ids.length, "unexpected frame count");
		for(int i = 0; i < ids.length; ++i) {
			String actual = frames.get(i).isString() ? frames.get(i).getString()
					: Character.toString((char)frames.get(i).getByteArray()[0]);
			require(ids[i].equals(actual), "unexpected frame at " + i);
		}
	}

	public static void main(String[] args) {
		Client client = new Client();
		client.add(new Frame("a", true));
		client.add(new Frame("b", false));
		client.add(new Frame("c", true));
		client.add(new Frame("d", false));
		require(client.availableFrames() == 4, "total count");
		require(client.availableStringFrames() == 2, "string count");
		require(client.availableBinaryFrames() == 2, "binary count");
		requireFrame(client.getNextStringFrame(), "a");
		requireIds(client.getNextBinaryFrames(), "b", "d");
		requireFrame(client.getNextFrame(), "c");
		require(client.availableFrames() == 0 && client.getNextFrame() == null, "empty queue");

		client.setEnableStringFrames(false);
		client.add(new Frame("e", true));
		client.add(new Frame("f", false));
		requireIds(client.getNextFrames(), "f");
		client.setEnableStringFrames(true);
		client.setEnableBinaryFrames(false);
		client.add(new Frame("g", false));
		client.add(new Frame("h", true));
		requireIds(client.getNextStringFrames(), "h");

		client.setEnableBinaryFrames(true);
		client.add(new Frame("i", true));
		client.add(new Frame("j", false));
		client.add(new Frame("k", true));
		client.clearStringFrames();
		requireIds(client.getNextFrames(), "j");
		client.add(new Frame("l", true));
		client.add(new Frame("m", false));
		client.clearBinaryFrames();
		requireIds(client.getNextFrames(), "l");
		client.add(new Frame("n", true));
		client.clearFrames();
		require(client.availableFrames() == 0, "clear all");
	}
}

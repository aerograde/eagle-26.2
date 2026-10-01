package net.lax1dude.eaglercraft.v1_8.teavmcompat.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Real java.nio.channels.Channels stream&harr;channel adapters for the web target
 * (see java.nio.channels.TChannels, which delegates here). These are pure byte
 * copies over java.io streams — no OS channel is involved — so unlike raw
 * sockets/FileChannel they work fine in the browser runtime. Minecraft's
 * TextureUtil.readResource / NativeImage wrap resource InputStreams with
 * Channels.newChannel to fill a direct ByteBuffer, so this is on the texture and
 * font load paths.
 *
 * Lives in the {@code support} package (NOT the {@code teavmcompat.java} mapped
 * hierarchy) so the injector leaves it intact and it may use inner classes.
 */
public final class ByteChannelAdapters {

	private static final int TRANSFER_SIZE = 8192;

	private ByteChannelAdapters() {
	}

	public static ReadableByteChannel readableChannel(final InputStream in) {
		return new InputStreamReadableByteChannel(in);
	}

	public static WritableByteChannel writableChannel(final OutputStream out) {
		return new OutputStreamWritableByteChannel(out);
	}

	public static InputStream inputStream(final ReadableByteChannel ch) {
		return new ReadableByteChannelInputStream(ch);
	}

	public static OutputStream outputStream(final WritableByteChannel ch) {
		return new WritableByteChannelOutputStream(ch);
	}

	private static final class InputStreamReadableByteChannel implements ReadableByteChannel {

		private final InputStream in;
		private boolean open = true;
		private byte[] transfer = new byte[0];

		InputStreamReadableByteChannel(final InputStream in) {
			this.in = in;
		}

		@Override
		public int read(final ByteBuffer dst) throws IOException {
			if (!open) {
				throw new ClosedChannelException();
			}
			int want = dst.remaining();
			if (want == 0) {
				return 0;
			}
			int chunk = Math.min(want, TRANSFER_SIZE);
			if (transfer.length < chunk) {
				transfer = new byte[chunk];
			}
			// One underlying read per call; the caller loops until we return -1
			// (this is legal partial-read ReadableByteChannel behaviour).
			int n = in.read(transfer, 0, chunk);
			if (n > 0) {
				dst.put(transfer, 0, n);
			}
			return n;
		}

		@Override
		public boolean isOpen() {
			return open;
		}

		@Override
		public void close() throws IOException {
			if (open) {
				open = false;
				in.close();
			}
		}
	}

	private static final class OutputStreamWritableByteChannel implements WritableByteChannel {

		private final OutputStream out;
		private boolean open = true;
		private byte[] transfer = new byte[0];

		OutputStreamWritableByteChannel(final OutputStream out) {
			this.out = out;
		}

		@Override
		public int write(final ByteBuffer src) throws IOException {
			if (!open) {
				throw new ClosedChannelException();
			}
			int len = src.remaining();
			if (len == 0) {
				return 0;
			}
			int chunk = Math.min(len, TRANSFER_SIZE);
			if (transfer.length < chunk) {
				transfer = new byte[chunk];
			}
			int written = 0;
			while (written < len) {
				int step = Math.min(len - written, chunk);
				src.get(transfer, 0, step);
				out.write(transfer, 0, step);
				written += step;
			}
			return written;
		}

		@Override
		public boolean isOpen() {
			return open;
		}

		@Override
		public void close() throws IOException {
			if (open) {
				open = false;
				out.close();
			}
		}
	}

	private static final class ReadableByteChannelInputStream extends InputStream {

		private final ReadableByteChannel ch;
		private final byte[] one = new byte[1];

		ReadableByteChannelInputStream(final ReadableByteChannel ch) {
			this.ch = ch;
		}

		@Override
		public int read() throws IOException {
			int n = read(one, 0, 1);
			return n == 1 ? (one[0] & 0xFF) : -1;
		}

		@Override
		public int read(final byte[] b, final int off, final int len) throws IOException {
			if (len == 0) {
				return 0;
			}
			return ch.read(ByteBuffer.wrap(b, off, len));
		}

		@Override
		public void close() throws IOException {
			ch.close();
		}
	}

	private static final class WritableByteChannelOutputStream extends OutputStream {

		private final WritableByteChannel ch;
		private final byte[] one = new byte[1];

		WritableByteChannelOutputStream(final WritableByteChannel ch) {
			this.ch = ch;
		}

		@Override
		public void write(final int b) throws IOException {
			one[0] = (byte) b;
			write(one, 0, 1);
		}

		@Override
		public void write(final byte[] b, final int off, final int len) throws IOException {
			ByteBuffer bb = ByteBuffer.wrap(b, off, len);
			while (bb.hasRemaining()) {
				ch.write(bb);
			}
		}

		@Override
		public void close() throws IOException {
			ch.close();
		}
	}
}

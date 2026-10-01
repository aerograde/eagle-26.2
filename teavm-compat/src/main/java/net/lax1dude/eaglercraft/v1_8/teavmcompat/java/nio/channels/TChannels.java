package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.support.ByteChannelAdapters;

/**
 * java.nio.channels.Channels — the stream&harr;channel factory. TeaVM's classlib
 * ships the channel interfaces but no Channels factory, so this facade provides
 * one. The adapters are REAL (pure byte copies over java.io streams via
 * {@link ByteChannelAdapters}) because no OS channel is involved — Minecraft's
 * TextureUtil.readResource / NativeImage wrap resource streams with
 * newChannel() to fill direct ByteBuffers, so textures and fonts depend on them.
 * Only socket/file <em>opening</em> is impossible in the browser, not these
 * in-memory adapters.
 */
public final class TChannels {

	private TChannels() {
	}

	public static ReadableByteChannel newChannel(InputStream in) {
		return ByteChannelAdapters.readableChannel(in);
	}

	public static WritableByteChannel newChannel(OutputStream out) {
		return ByteChannelAdapters.writableChannel(out);
	}

	public static InputStream newInputStream(ReadableByteChannel ch) {
		return ByteChannelAdapters.inputStream(ch);
	}

	public static OutputStream newOutputStream(WritableByteChannel ch) {
		return ByteChannelAdapters.outputStream(ch);
	}

	public static Writer newWriter(WritableByteChannel ch, Charset charset) {
		return new OutputStreamWriter(ByteChannelAdapters.outputStream(ch), charset);
	}

}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;

/**
 * REPLACE-mode donor for {@link java.util.zip.GZIPOutputStream#flush()}.
 *
 * <p>TeaVM 0.13's {@code TGZIPOutputStream.flush()} unconditionally deflates with
 * {@code Z_SYNC_FLUSH}. On empty input the underlying JZlib deflater returns
 * {@code Z_BUF_ERROR} (-5), and {@code TDeflater.deflate} throws
 * {@code RuntimeException("Error: -5")} for any return code other than
 * {@code Z_OK}/{@code Z_STREAM_END}. So <em>every</em> close of a GZIP stream
 * (DataOutputStream/BufferedOutputStream close -&gt; flush) crashed — which broke
 * {@code NbtIo.writeCompressed} everywhere (EaglerProfile.save, singleplayer
 * level.dat / player-data world saves).</p>
 *
 * <p>The real JDK's {@code GZIPOutputStream} inherits {@code DeflaterOutputStream.flush()}
 * with {@code syncFlush = false} (the default), which does NOT deflate — it only
 * flushes the downstream. Any bytes still buffered in the deflater are emitted by
 * {@code finish()} (called from {@code close()}) via {@code Z_FINISH}, which always
 * makes progress and never returns {@code Z_BUF_ERROR}. This donor restores that
 * behaviour: only the concrete {@code flush()} is copied over the target's method
 * (constructors are not copied for REPLACE donors), so the GZIP header/finish/CRC
 * logic is untouched. {@code out} resolves to the inherited
 * {@code java.io.FilterOutputStream.out} field of the real GZIPOutputStream.</p>
 */
public class GZIPOutputStreamInject extends DeflaterOutputStream {

	// present only so javac can resolve the inherited `out` field; not copied.
	public GZIPOutputStreamInject(OutputStream out) {
		super(out);
	}

	public void flush() throws IOException {
		out.flush();
	}

}

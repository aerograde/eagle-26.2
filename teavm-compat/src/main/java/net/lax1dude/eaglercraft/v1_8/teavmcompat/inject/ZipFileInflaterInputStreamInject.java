package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * REPLACE-mode donor for TeaVM 0.13's
 * {@code java.util.zip.ZipFile.ZipInflaterInputStream}.
 *
 * <p>ZIP entries contain raw DEFLATE streams. An {@link Inflater} constructed
 * with {@code nowrap=true} requires one dummy input byte after the bounded
 * compressed entry. TeaVM's ZIP stream stops exactly at the central-directory
 * compressed size and reports EOF instead, so JZlib can emit all bytes while
 * never reaching {@code Z_STREAM_END}; metadata readers then fail with an
 * {@link EOFException}. This matches the JDK ZipFile stream: inject one zero
 * byte at the physical end, and treat a second fill as truncation.</p>
 *
 * <p>Like the JDK {@code ZipFile} stream, this does not reject an entry merely
 * because its central-directory CRC or uncompressed size is inaccurate. Some
 * protected resource packs deliberately use inconsistent central metadata.
 * Whole-file SHA-1 and download-size validation remain at the resource-pack
 * download boundary, while malformed/truncated DEFLATE still fails here.</p>
 */
public class ZipFileInflaterInputStreamInject extends InflaterInputStream {

	// Existing fields on TeaVM's target class. They are declarations for javac;
	// the injector binds accesses to the target fields and does not duplicate them.
	private long bytesRead;

	private boolean eaglerDummyByteSupplied;

	// Constructor body is not copied by REPLACE injection.
	public ZipFileInflaterInputStreamInject(InputStream in, Inflater inflater, int size) {
		super(in, inflater, size);
	}

	@Override
	protected void fill() throws IOException {
		if (eaglerDummyByteSupplied) {
			throw new EOFException("Unexpected end of DEFLATE input stream");
		}
		len = in.read(buf, 0, buf.length);
		if (len == -1) {
			buf[0] = 0;
			len = 1;
			eaglerDummyByteSupplied = true;
		}
		inf.setInput(buf, 0, len);
	}

	@Override
	public int read(byte[] buffer, int off, int length) throws IOException {
		int read = super.read(buffer, off, length);
		if (read >= 0) {
			bytesRead += read;
		}
		return read;
	}
}

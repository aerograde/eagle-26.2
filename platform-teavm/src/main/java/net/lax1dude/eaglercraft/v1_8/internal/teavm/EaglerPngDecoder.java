/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Phase 3.3b: pure-Java PNG decoder backing the org.lwjgl.stb.STBImage linkage
 * stub (vanilla NativeImage.read decodes every texture through
 * stbi_load_from_memory; the browser has no stb natives, and the upstream
 * canvas-decode path is async + premultiply-lossy, so a synchronous exact
 * decoder is the faithful replacement).
 *
 * Supports: bit depths 1/2/4/8/16, color types 0 (gray), 2 (RGB), 3 (palette),
 * 4 (gray+alpha), 6 (RGBA), tRNS transparency, and both non-interlaced and
 * Adam7-interlaced images. 16-bit samples are truncated to their high byte,
 * matching stbi 8-bit loading. Output is 8-bit, converted to the requested
 * channel count with stb's luminance formula.
 */
public class EaglerPngDecoder {

	public static class Result {
		public int width;
		public int height;
		public int sourceChannels;
		public byte[] pixels; // width*height*outChannels, 8-bit
	}

	private static final long PNG_SIG = 0x89504E470D0A1A0AL;
	private static final int[] ADAM7_START_X = { 0, 4, 0, 2, 0, 1, 0 };
	private static final int[] ADAM7_START_Y = { 0, 0, 4, 0, 2, 0, 1 };
	private static final int[] ADAM7_STEP_X = { 8, 8, 4, 4, 2, 2, 1 };
	private static final int[] ADAM7_STEP_Y = { 8, 8, 8, 4, 4, 2, 2 };

	public static Result decode(byte[] data, int off, int len, int reqComp) throws IOException {
		if (len < 8 || readLong(data, off) != PNG_SIG) {
			throw new IOException("not a PNG file");
		}
		int pos = off + 8;
		int end = off + len;

		int width = 0, height = 0, bitDepth = 0, colorType = 0, interlace = 0;
		byte[] palette = null;
		byte[] trns = null;
		byte[] idat = new byte[0];
		int idatLen = 0;
		boolean sawIHDR = false, sawIEND = false;

		while (pos + 8 <= end && !sawIEND) {
			int chunkLen = readInt(data, pos);
			int type = readInt(data, pos + 4);
			int dataAt = pos + 8;
			if (chunkLen < 0 || dataAt + chunkLen + 4 > end) {
				throw new IOException("corrupt PNG chunk");
			}
			switch (type) {
				case 0x49484452: { // IHDR
					width = readInt(data, dataAt);
					height = readInt(data, dataAt + 4);
					bitDepth = data[dataAt + 8] & 0xFF;
					colorType = data[dataAt + 9] & 0xFF;
					interlace = data[dataAt + 12] & 0xFF;
					sawIHDR = true;
					break;
				}
				case 0x504C5445: { // PLTE
					palette = new byte[chunkLen];
					System.arraycopy(data, dataAt, palette, 0, chunkLen);
					break;
				}
				case 0x74524E53: { // tRNS
					trns = new byte[chunkLen];
					System.arraycopy(data, dataAt, trns, 0, chunkLen);
					break;
				}
				case 0x49444154: { // IDAT
					if (idat.length < idatLen + chunkLen) {
						byte[] grow = new byte[Math.max(idatLen + chunkLen, idat.length * 2 + 64)];
						System.arraycopy(idat, 0, grow, 0, idatLen);
						idat = grow;
					}
					System.arraycopy(data, dataAt, idat, idatLen, chunkLen);
					idatLen += chunkLen;
					break;
				}
				case 0x49454E44: { // IEND
					sawIEND = true;
					break;
				}
				default:
					break; // ancillary chunk, skip
			}
			pos = dataAt + chunkLen + 4; // skip CRC
		}

		if (!sawIHDR || width <= 0 || height <= 0) {
			throw new IOException("PNG missing IHDR");
		}
		if (interlace != 0 && interlace != 1) {
			throw new IOException("bad PNG interlace method " + interlace);
		}
		int channels = channelsOf(colorType);
		if (channels == 0) {
			throw new IOException("bad PNG color type " + colorType);
		}
		if (bitDepth != 1 && bitDepth != 2 && bitDepth != 4 && bitDepth != 8 && bitDepth != 16) {
			throw new IOException("bad PNG bit depth " + bitDepth);
		}

		int bitsPerPixel = channels * bitDepth;
		int rowBytes = packedRowBytes(width, bitsPerPixel);
		int rawLength = interlace == 0 ? checkedLength((long) (rowBytes + 1) * height)
				: adam7RawLength(width, height, bitsPerPixel);
		byte[] raw = new byte[rawLength];
		inflateImageData(idat, idatLen, raw);

		// Expand to an 8-bit RGBA working buffer. Adam7 filters each reduced pass
		// independently, so decode a pass at a time and scatter its pixels into the
		// final image. This keeps the normal vanilla path unchanged and synchronous.
		byte[] rgba = new byte[checkedLength((long) width * height * 4)];
		int filterBpp = Math.max(1, (bitsPerPixel + 7) / 8);
		if (interlace == 0) {
			unfilter(raw, height, rowBytes, filterBpp);
			expand(raw, rgba, width, height, rowBytes, bitDepth, colorType, palette, trns);
		} else {
			decodeAdam7(raw, rgba, width, height, bitsPerPixel, bitDepth, colorType, palette, trns,
					filterBpp);
		}

		int outComp = reqComp == 0 ? channelsForOutput(colorType) : reqComp;
		Result r = new Result();
		r.width = width;
		r.height = height;
		r.sourceChannels = channelsForOutput(colorType);
		r.pixels = convert(rgba, width * height, outComp);
		return r;
	}

	/**
	 * Decode the PNG zlib wrapper with the same integrity boundary as stb_image.
	 * stb validates CMF/FLG and rejects preset dictionaries, but intentionally does
	 * not consume or verify the trailing Adler-32. The browser path must therefore
	 * use raw DEFLATE after validating the two-byte wrapper, while retaining exact
	 * decoded-length and stream-completion checks here.
	 */
	private static void inflateImageData(byte[] idat, int idatLen, byte[] raw) throws IOException {
		if (idatLen < 6) {
			throw new IOException("corrupt PNG zlib stream");
		}
		int cmf = idat[0] & 0xFF;
		int flg = idat[1] & 0xFF;
		if (((cmf << 8) | flg) % 31 != 0 || (cmf & 0x0F) != 8 || (cmf >>> 4) > 7 || (flg & 0x20) != 0) {
			throw new IOException("corrupt PNG zlib header");
		}

		// Skip CMF/FLG and the Adler-32 trailer. stb_image deliberately ignores
		// Adler-32, so only the raw DEFLATE stream is supplied to Inflater.
		int deflateLength = idatLen - 6;
		if (deflateLength <= 0) {
			throw new IOException("truncated PNG image data");
		}
		// TeaVM's JZlib raw inflater needs one dummy byte after a complete DEFLATE
		// stream to report Z_STREAM_END for streams whose final block ends exactly
		// at input EOF. Keep it separate from the ignored Adler-32 trailer, then
		// require it to remain unconsumed so it cannot mask a truncated stream.
		byte[] deflate = new byte[deflateLength + 1];
		System.arraycopy(idat, 2, deflate, 0, deflateLength);
		Inflater inf = new Inflater(true);
		try {
			inf.setInput(deflate, 0, deflate.length);
			int got = 0;
			while (got < raw.length && !inf.finished()) {
				int n = inf.inflate(raw, got, raw.length - got);
				if (n > 0) {
					got += n;
					continue;
				}
				if (inf.finished()) {
					break;
				}
				if (inf.needsInput()) {
					throw new IOException("truncated PNG image data (" + got + "/" + raw.length + ")");
				}
				if (inf.needsDictionary()) {
					throw new IOException("PNG image data requires a preset dictionary");
				}
				throw new IOException("corrupt PNG image data");
			}

			// A full output buffer is not proof that the DEFLATE stream ended: probe
			// one extra byte so overlong rows cannot be silently accepted.
			if (got == raw.length && !inf.finished()) {
				byte[] extra = new byte[1];
				int n = inf.inflate(extra, 0, 1);
				if (n > 0) {
					throw new IOException("PNG image data exceeds expected length");
				}
				if (!inf.finished()) {
					if (inf.needsInput()) {
						throw new IOException("truncated PNG image data (" + got + "/" + raw.length + ")");
					}
					throw new IOException("corrupt PNG image data");
				}
			}
			if (got < raw.length) {
				throw new IOException("truncated PNG image data (" + got + "/" + raw.length + ")");
			}
			if (!inf.finished()) {
				throw new IOException("corrupt PNG image data");
			}
			if (inf.getRemaining() != 1) {
				throw new IOException("corrupt PNG image data boundary");
			}
		} catch (DataFormatException e) {
			throw new IOException("corrupt PNG image data", e);
		} finally {
			inf.end();
		}
	}

	private static void decodeAdam7(byte[] raw, byte[] rgba, int width, int height, int bitsPerPixel,
			int bitDepth, int colorType, byte[] palette, byte[] trns, int filterBpp) throws IOException {
		int rawAt = 0;
		for (int pass = 0; pass < 7; ++pass) {
			int passWidth = passSize(width, ADAM7_START_X[pass], ADAM7_STEP_X[pass]);
			int passHeight = passSize(height, ADAM7_START_Y[pass], ADAM7_STEP_Y[pass]);
			if (passWidth == 0 || passHeight == 0) {
				continue;
			}
			int passRowBytes = packedRowBytes(passWidth, bitsPerPixel);
			int passLength = checkedLength((long) (passRowBytes + 1) * passHeight);
			if (rawAt > raw.length - passLength) {
				throw new IOException("truncated Adam7 PNG image data");
			}
			byte[] passRaw = new byte[passLength];
			System.arraycopy(raw, rawAt, passRaw, 0, passLength);
			rawAt += passLength;
			unfilter(passRaw, passHeight, passRowBytes, filterBpp);
			byte[] passRgba = new byte[checkedLength((long) passWidth * passHeight * 4)];
			expand(passRaw, passRgba, passWidth, passHeight, passRowBytes, bitDepth, colorType, palette,
					trns);
			for (int passY = 0, dstY = ADAM7_START_Y[pass]; passY < passHeight;
					++passY, dstY += ADAM7_STEP_Y[pass]) {
				for (int passX = 0, dstX = ADAM7_START_X[pass]; passX < passWidth;
						++passX, dstX += ADAM7_STEP_X[pass]) {
					int src = (passY * passWidth + passX) * 4;
					int dst = (dstY * width + dstX) * 4;
					rgba[dst] = passRgba[src];
					rgba[dst + 1] = passRgba[src + 1];
					rgba[dst + 2] = passRgba[src + 2];
					rgba[dst + 3] = passRgba[src + 3];
				}
			}
		}
		if (rawAt != raw.length) {
			throw new IOException("bad Adam7 PNG image data length");
		}
	}

	private static int adam7RawLength(int width, int height, int bitsPerPixel) throws IOException {
		long total = 0L;
		for (int pass = 0; pass < 7; ++pass) {
			int passWidth = passSize(width, ADAM7_START_X[pass], ADAM7_STEP_X[pass]);
			int passHeight = passSize(height, ADAM7_START_Y[pass], ADAM7_STEP_Y[pass]);
			if (passWidth != 0 && passHeight != 0) {
				total += (long) (packedRowBytes(passWidth, bitsPerPixel) + 1) * passHeight;
				if (total > Integer.MAX_VALUE) {
					throw new IOException("PNG image data is too large");
				}
			}
		}
		return (int) total;
	}

	private static int passSize(int size, int start, int step) {
		return size <= start ? 0 : (size - start + step - 1) / step;
	}

	private static int packedRowBytes(int width, int bitsPerPixel) throws IOException {
		return checkedLength(((long) width * bitsPerPixel + 7L) / 8L);
	}

	private static int checkedLength(long length) throws IOException {
		if (length < 0L || length > Integer.MAX_VALUE) {
			throw new IOException("PNG image data is too large");
		}
		return (int) length;
	}

	private static int channelsOf(int colorType) {
		switch (colorType) {
			case 0: return 1;
			case 2: return 3;
			case 3: return 1;
			case 4: return 2;
			case 6: return 4;
			default: return 0;
		}
	}

	/** stb reports palette as 3/4-channel; gray as 1/2; rgb(a) as-is. */
	private static int channelsForOutput(int colorType) {
		switch (colorType) {
			case 0: return 1;
			case 2: return 3;
			case 3: return 3;
			case 4: return 2;
			case 6: return 4;
			default: return 0;
		}
	}

	private static void unfilter(byte[] raw, int height, int rowBytes, int bpp) throws IOException {
		int stride = rowBytes + 1;
		for (int y = 0; y < height; ++y) {
			int rowAt = y * stride + 1;
			int filter = raw[rowAt - 1] & 0xFF;
			int prevAt = rowAt - stride;
			switch (filter) {
				case 0:
					break;
				case 1: // Sub
					for (int x = bpp; x < rowBytes; ++x) {
						raw[rowAt + x] += raw[rowAt + x - bpp];
					}
					break;
				case 2: // Up
					if (y > 0) {
						for (int x = 0; x < rowBytes; ++x) {
							raw[rowAt + x] += raw[prevAt + x];
						}
					}
					break;
				case 3: // Average
					for (int x = 0; x < rowBytes; ++x) {
						int a = x >= bpp ? (raw[rowAt + x - bpp] & 0xFF) : 0;
						int b = y > 0 ? (raw[prevAt + x] & 0xFF) : 0;
						raw[rowAt + x] += (byte) ((a + b) >>> 1);
					}
					break;
				case 4: // Paeth
					for (int x = 0; x < rowBytes; ++x) {
						int a = x >= bpp ? (raw[rowAt + x - bpp] & 0xFF) : 0;
						int b = y > 0 ? (raw[prevAt + x] & 0xFF) : 0;
						int c = (x >= bpp && y > 0) ? (raw[prevAt + x - bpp] & 0xFF) : 0;
						int p = a + b - c;
						int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
						int pred = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
						raw[rowAt + x] += (byte) pred;
					}
					break;
				default:
					throw new IOException("bad PNG filter " + filter);
			}
		}
	}

	private static void expand(byte[] raw, byte[] rgba, int width, int height, int rowBytes,
			int bitDepth, int colorType, byte[] palette, byte[] trns) throws IOException {
		int stride = rowBytes + 1;
		int trnsGray = -1, trnsR = -1, trnsG = -1, trnsB = -1;
		if (trns != null) {
			if (colorType == 0 && trns.length >= 2) {
				trnsGray = ((trns[0] & 0xFF) << 8) | (trns[1] & 0xFF);
			} else if (colorType == 2 && trns.length >= 6) {
				trnsR = ((trns[0] & 0xFF) << 8) | (trns[1] & 0xFF);
				trnsG = ((trns[2] & 0xFF) << 8) | (trns[3] & 0xFF);
				trnsB = ((trns[4] & 0xFF) << 8) | (trns[5] & 0xFF);
			}
		}
		for (int y = 0; y < height; ++y) {
			int rowAt = y * stride + 1;
			int out = y * width * 4;
			switch (colorType) {
				case 0: { // grayscale
					for (int x = 0; x < width; ++x) {
						int v16, v8;
						if (bitDepth == 16) {
							v16 = ((raw[rowAt + x * 2] & 0xFF) << 8) | (raw[rowAt + x * 2 + 1] & 0xFF);
							v8 = v16 >>> 8;
						} else {
							int s = sample(raw, rowAt, x, bitDepth);
							v16 = s;
							v8 = scaleTo8(s, bitDepth);
						}
						int o = out + x * 4;
						rgba[o] = rgba[o + 1] = rgba[o + 2] = (byte) v8;
						rgba[o + 3] = (byte) ((trnsGray >= 0 && v16 == trnsGray) ? 0 : 255);
					}
					break;
				}
				case 2: { // RGB
					int bytesPer = bitDepth == 16 ? 6 : 3;
					for (int x = 0; x < width; ++x) {
						int at = rowAt + x * bytesPer;
						int r, g, b, r16, g16, b16;
						if (bitDepth == 16) {
							r16 = ((raw[at] & 0xFF) << 8) | (raw[at + 1] & 0xFF);
							g16 = ((raw[at + 2] & 0xFF) << 8) | (raw[at + 3] & 0xFF);
							b16 = ((raw[at + 4] & 0xFF) << 8) | (raw[at + 5] & 0xFF);
							r = r16 >>> 8; g = g16 >>> 8; b = b16 >>> 8;
						} else {
							r = r16 = raw[at] & 0xFF;
							g = g16 = raw[at + 1] & 0xFF;
							b = b16 = raw[at + 2] & 0xFF;
						}
						int o = out + x * 4;
						rgba[o] = (byte) r;
						rgba[o + 1] = (byte) g;
						rgba[o + 2] = (byte) b;
						rgba[o + 3] = (byte) ((trnsR >= 0 && r16 == trnsR && g16 == trnsG && b16 == trnsB) ? 0 : 255);
					}
					break;
				}
				case 3: { // palette
					if (palette == null) {
						throw new IOException("paletted PNG without PLTE");
					}
					for (int x = 0; x < width; ++x) {
						int idx = sample(raw, rowAt, x, bitDepth);
						int p = idx * 3;
						int o = out + x * 4;
						if (p + 2 < palette.length) {
							rgba[o] = palette[p];
							rgba[o + 1] = palette[p + 1];
							rgba[o + 2] = palette[p + 2];
						}
						rgba[o + 3] = (byte) ((trns != null && idx < trns.length) ? (trns[idx] & 0xFF) : 255);
					}
					break;
				}
				case 4: { // gray + alpha
					int bytesPer = bitDepth == 16 ? 4 : 2;
					for (int x = 0; x < width; ++x) {
						int at = rowAt + x * bytesPer;
						int v, a;
						if (bitDepth == 16) {
							v = raw[at] & 0xFF;
							a = raw[at + 2] & 0xFF;
						} else {
							v = raw[at] & 0xFF;
							a = raw[at + 1] & 0xFF;
						}
						int o = out + x * 4;
						rgba[o] = rgba[o + 1] = rgba[o + 2] = (byte) v;
						rgba[o + 3] = (byte) a;
					}
					break;
				}
				case 6: { // RGBA
					int bytesPer = bitDepth == 16 ? 8 : 4;
					for (int x = 0; x < width; ++x) {
						int at = rowAt + x * bytesPer;
						int o = out + x * 4;
						if (bitDepth == 16) {
							rgba[o] = raw[at];
							rgba[o + 1] = raw[at + 2];
							rgba[o + 2] = raw[at + 4];
							rgba[o + 3] = raw[at + 6];
						} else {
							rgba[o] = raw[at];
							rgba[o + 1] = raw[at + 1];
							rgba[o + 2] = raw[at + 2];
							rgba[o + 3] = raw[at + 3];
						}
					}
					break;
				}
				default:
					throw new IOException("bad PNG color type " + colorType);
			}
		}
	}

	private static int sample(byte[] raw, int rowAt, int x, int bitDepth) {
		switch (bitDepth) {
			case 8:
				return raw[rowAt + x] & 0xFF;
			case 4: {
				int b = raw[rowAt + (x >> 1)] & 0xFF;
				return (x & 1) == 0 ? (b >>> 4) : (b & 0xF);
			}
			case 2: {
				int b = raw[rowAt + (x >> 2)] & 0xFF;
				return (b >>> (6 - ((x & 3) << 1))) & 0x3;
			}
			case 1: {
				int b = raw[rowAt + (x >> 3)] & 0xFF;
				return (b >>> (7 - (x & 7))) & 0x1;
			}
			default:
				return raw[rowAt + x * 2] & 0xFF; // 16-bit handled by callers
		}
	}

	private static int scaleTo8(int v, int bitDepth) {
		switch (bitDepth) {
			case 1: return v * 255;
			case 2: return v * 85;
			case 4: return v * 17;
			default: return v;
		}
	}

	private static byte[] convert(byte[] rgba, int pixels, int outComp) throws IOException {
		if (outComp == 4) {
			return rgba;
		}
		byte[] out = new byte[pixels * outComp];
		for (int i = 0; i < pixels; ++i) {
			int s = i * 4;
			int d = i * outComp;
			int r = rgba[s] & 0xFF, g = rgba[s + 1] & 0xFF, b = rgba[s + 2] & 0xFF;
			switch (outComp) {
				case 1:
					out[d] = (byte) ((r * 77 + g * 150 + b * 29) >>> 8); // stb compute_y
					break;
				case 2:
					out[d] = (byte) ((r * 77 + g * 150 + b * 29) >>> 8);
					out[d + 1] = rgba[s + 3];
					break;
				case 3:
					out[d] = rgba[s];
					out[d + 1] = rgba[s + 1];
					out[d + 2] = rgba[s + 2];
					break;
				default:
					throw new IOException("bad requested channel count " + outComp);
			}
		}
		return out;
	}

	private static int readInt(byte[] d, int at) {
		return ((d[at] & 0xFF) << 24) | ((d[at + 1] & 0xFF) << 16) | ((d[at + 2] & 0xFF) << 8) | (d[at + 3] & 0xFF);
	}

	private static long readLong(byte[] d, int at) {
		return ((long) readInt(d, at) << 32) | (readInt(d, at + 4) & 0xFFFFFFFFL);
	}
}

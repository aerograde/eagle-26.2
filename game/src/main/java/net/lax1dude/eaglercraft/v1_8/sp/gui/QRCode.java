package net.lax1dude.eaglercraft.v1_8.sp.gui;

/**
 * Minimal QR code encoder for the direct connect share screen. Byte-mode
 * payloads only, versions 1..10, ECC levels L/M/Q/H with automatic version
 * selection. Structure follows the public-domain reference implementation by
 * Project Nayuki (QR Code generator library) and the ZXing encoder; the
 * Reed-Solomon and mask-penalty tables are the standard QR specification.
 */
public final class QRCode {

	public static final int ECC_L = 0;
	public static final int ECC_M = 1;
	public static final int ECC_Q = 2;
	public static final int ECC_H = 3;

	/** Reed-Solomon ecc codewords per block, indexed [ecc][version]. */
	private static final int[][] ECC_CODEWORDS_PER_BLOCK = {
			{ -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20 },
			{ -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30 },
			{ -1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28 },
			{ -1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24 }
	};

	/** Reed-Solomon block count, indexed [ecc][version]. */
	private static final int[][] NUM_ERROR_CORRECTION_BLOCKS = {
			{ -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4 },
			{ -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5 },
			{ -1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8 },
			{ -1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11 }
	};

	private static final byte[] ECC_FORMAT_BITS = { 1, 0, 3, 7 };

	private QRCode() {
	}

	/**
	 * Encode text as a QR matrix with ECC level M and the smallest supporting
	 * version. {@code result.length} is the module count (width == height).
	 */
	public static boolean[][] encode(String text) {
		return encode(text, ECC_M);
	}

	/**
	 * Encode text as a QR matrix with the given ECC level. Throws
	 * {@link IllegalArgumentException} when the text does not fit any supported
	 * version (1..10).
	 */
	public static boolean[][] encode(String text, int ecc) {
		byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		if(data.length == 0) throw new IllegalArgumentException("Empty QR payload");
		for(int version = 1; version <= 10; ++version) {
			int dataCapacityBits = getNumDataCodewords(version, ecc) * 8;
			int countBits = version < 10 ? 8 : 16;
			int neededBits = 4 + countBits + data.length * 8;
			if(neededBits <= dataCapacityBits) {
				return encodeLowest(data, version, ecc);
			}
		}
		throw new IllegalArgumentException("QR payload too long (" + data.length + " bytes)");
	}

	private static boolean[][] encodeLowest(byte[] data, int version, int ecc) {
		BitBuffer bb = new BitBuffer();
		bb.appendBits(4, 4); // byte mode
		bb.appendBits(data.length, version < 10 ? 8 : 16);
		for(int i = 0; i < data.length; ++i) {
			bb.appendBits(data[i] & 0xFF, 8);
		}
		int capacity = getNumDataCodewords(version, ecc) * 8;
		// terminator + pad to byte, then pad codewords
		bb.appendBits(0, Math.min(4, capacity - bb.bitLength));
		bb.appendBits(0, (8 - bb.bitLength % 8) % 8);
		for(int pad = 0xEC; bb.bitLength < capacity; pad ^= 0xEC ^ 0x11) {
			bb.appendBits(pad, 8);
		}
		byte[] dataCodewords = bb.toBytes();

		byte[] allCodewords = addEccAndInterleave(dataCodewords, version, ecc);
		return buildMatrix(version, ecc, allCodewords);
	}

	private static final class BitBuffer {
		private java.util.ArrayList<Boolean> bits = new java.util.ArrayList<>();
		private int bitLength;

		void appendBits(int value, int length) {
			for(int i = length - 1; i >= 0; --i) {
				bits.add(Boolean.valueOf(((value >>> i) & 1) != 0));
				++bitLength;
			}
		}

		byte[] toBytes() {
			byte[] out = new byte[bitLength / 8];
			for(int i = 0; i < bitLength; ++i) {
				if(bits.get(i).booleanValue()) {
					out[i >>> 3] |= (byte) (0x80 >>> (i & 7));
				}
			}
			return out;
		}
	}

	private static int getNumDataCodewords(int version, int ecc) {
		return getNumRawDataModules(version) / 8
				- ECC_CODEWORDS_PER_BLOCK[ecc][version] * NUM_ERROR_CORRECTION_BLOCKS[ecc][version];
	}

	private static int getNumRawDataModules(int version) {
		int result = (16 * version + 128) * version + 64;
		if(version >= 2) {
			int numAlign = version / 7 + 2;
			result -= (25 * numAlign - 10) * numAlign - 55;
			if(version >= 7) result -= 36;
		}
		return result;
	}

	private static byte[] addEccAndInterleave(byte[] data, int version, int ecc) {
		int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc][version];
		int blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc][version];
		int rawCodewords = getNumRawDataModules(version) / 8;
		int numShortBlocks = numBlocks - rawCodewords % numBlocks;
		int shortBlockLen = rawCodewords / numBlocks;

		byte[][] blocks = new byte[numBlocks][];
		for(int i = 0, k = 0; i < numBlocks; ++i) {
			int dataLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
			byte[] block = new byte[dataLen];
			System.arraycopy(data, k, block, 0, dataLen);
			byte[] eccBytes = reedSolomonComputeRemainder(block, blockEccLen);
			byte[] full = new byte[dataLen + blockEccLen];
			System.arraycopy(block, 0, full, 0, dataLen);
			System.arraycopy(eccBytes, 0, full, dataLen, blockEccLen);
			blocks[i] = full;
			k += dataLen;
		}

		byte[] result = new byte[rawCodewords];
		int idx = 0;
		for(int i = 0; i < blocks[0].length; ++i) {
			for(int j = 0; j < blocks.length; ++j) {
				// skip the padding byte of short blocks in the last column
				if(i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
					result[idx++] = blocks[j][i];
				}
			}
		}
		return result;
	}

	private static byte[] reedSolomonComputeRemainder(byte[] data, int degree) {
		java.util.ArrayList<Byte> gen = new java.util.ArrayList<>();
		gen.add(Byte.valueOf((byte) 1));
		byte root = 1;
		for(int i = 0; i < degree; ++i) {
			gen.add(Byte.valueOf((byte) 0));
			for(int j = gen.size() - 1; j > 0; --j) {
				int a = gen.get(j - 1) & 0xFF;
				int b = multiply(root, gen.get(j) & 0xFF);
				gen.set(j, Byte.valueOf((byte) (a ^ b)));
			}
			root = (byte) multiply(root, 2);
		}
		byte[] result = new byte[degree];
		for(int i = 0; i < data.length; ++i) {
			int factor = (data[i] & 0xFF) ^ (result[0] & 0xFF);
			System.arraycopy(result, 1, result, 0, result.length - 1);
			result[result.length - 1] = 0;
			for(int j = 0; j < degree; ++j) {
				int sub = multiply(gen.get(j) & 0xFF, factor);
				result[j] = (byte) ((result[j] & 0xFF) ^ sub);
			}
		}
		return result;
	}

	private static int multiply(int x, int y) {
		int z = 0;
		for(int i = 7; i >= 0; --i) {
			z = (z << 1) ^ ((z >>> 7) * 0x11D);
			z ^= ((y >>> i) & 1) * x;
		}
		return z & 0xFF;
	}

	private static boolean[][] buildMatrix(int version, int ecc, byte[] codewords) {
		int size = version * 4 + 17;
		boolean[][] modules = new boolean[size][size];
		boolean[][] isFunction = new boolean[size][size];

		drawFunctionPatterns(modules, isFunction, version, ecc);
		drawCodewords(modules, isFunction, codewords);

		int bestMask = 0;
		long bestPenalty = Long.MAX_VALUE;
		for(int mask = 0; mask < 8; ++mask) {
			applyMask(modules, isFunction, mask);
			drawFormatBits(modules, isFunction, ecc, mask);
			long penalty = maskPenalty(modules);
			if(penalty < bestPenalty) {
				bestPenalty = penalty;
				bestMask = mask;
			}
			applyMask(modules, isFunction, mask); // undo
		}
		applyMask(modules, isFunction, bestMask);
		drawFormatBits(modules, isFunction, ecc, bestMask);
		return modules;
	}

	private static void setFunctionModule(boolean[][] modules, boolean[][] isFunction, int x, int y, boolean isDark) {
		modules[y][x] = isDark;
		isFunction[y][x] = true;
	}

	private static void drawFunctionPatterns(boolean[][] modules, boolean[][] isFunction, int version, int ecc) {
		int size = modules.length;
		for(int i = 0; i < size; ++i) {
			setFunctionModule(modules, isFunction, 6, i, i % 2 == 0);
			setFunctionModule(modules, isFunction, i, 6, i % 2 == 0);
		}
		drawFinderPattern(modules, isFunction, 3, 3);
		drawFinderPattern(modules, isFunction, size - 4, 3);
		drawFinderPattern(modules, isFunction, 3, size - 4);

		int[] alignPos = getAlignmentPatternPositions(version);
		int numAlign = alignPos.length;
		for(int i = 0; i < numAlign; ++i) {
			for(int j = 0; j < numAlign; ++j) {
				if((i == 0 && j == 0) || (i == 0 && j == numAlign - 1) || (i == numAlign - 1 && j == 0)) continue;
				drawAlignmentPattern(modules, isFunction, alignPos[i], alignPos[j]);
			}
		}
		drawFormatBits(modules, isFunction, ecc, 0); // reserve with dummy mask
		drawVersionInfo(modules, isFunction, version);
	}

	private static int[] getAlignmentPatternPositions(int version) {
		if(version == 1) return new int[0];
		int numAlign = version / 7 + 2;
		int step = (version == 32) ? 26 : (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
		int[] result = new int[numAlign];
		result[0] = 6;
		for(int i = numAlign - 1, pos = version * 4 + 10; i >= 1; --i, pos -= step) {
			result[i] = pos;
		}
		return result;
	}

	private static void drawFinderPattern(boolean[][] modules, boolean[][] isFunction, int x, int y) {
		for(int dy = -4; dy <= 4; ++dy) {
			for(int dx = -4; dx <= 4; ++dx) {
				int xx = x + dx, yy = y + dy;
				if(xx >= 0 && xx < modules.length && yy >= 0 && yy < modules.length) {
					int dist = Math.max(Math.abs(dx), Math.abs(dy));
					setFunctionModule(modules, isFunction, xx, yy, dist != 2 && dist != 4);
				}
			}
		}
	}

	private static void drawAlignmentPattern(boolean[][] modules, boolean[][] isFunction, int x, int y) {
		for(int dy = -2; dy <= 2; ++dy) {
			for(int dx = -2; dx <= 2; ++dx) {
				setFunctionModule(modules, isFunction, x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
			}
		}
	}

	private static void drawFormatBits(boolean[][] modules, boolean[][] isFunction, int ecc, int mask) {
		int size = modules.length;
		int data = ECC_FORMAT_BITS[ecc] << 3 | mask;
		int rem = data;
		for(int i = 0; i < 10; ++i) {
			rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
		}
		int bits = ((data << 10) | rem) ^ 0x5412;

		for(int i = 0; i <= 5; ++i) setFunctionModule(modules, isFunction, 8, i, getBit(bits, i));
		setFunctionModule(modules, isFunction, 8, 7, getBit(bits, 6));
		setFunctionModule(modules, isFunction, 8, 8, getBit(bits, 7));
		setFunctionModule(modules, isFunction, 7, 8, getBit(bits, 8));
		for(int i = 9; i < 15; ++i) setFunctionModule(modules, isFunction, 14 - i, 8, getBit(bits, i));

		for(int i = 0; i < 8; ++i) setFunctionModule(modules, isFunction, size - 1 - i, 8, getBit(bits, i));
		for(int i = 8; i < 15; ++i) setFunctionModule(modules, isFunction, 8, size - 15 + i, getBit(bits, i));
		setFunctionModule(modules, isFunction, 8, size - 8, true); // dark module
	}

	private static void drawVersionInfo(boolean[][] modules, boolean[][] isFunction, int version) {
		if(version < 7) return;
		int size = modules.length;
		int rem = version;
		for(int i = 0; i < 12; ++i) {
			rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
		}
		int bits = version << 12 | rem;
		for(int i = 0; i < 18; ++i) {
			boolean bit = getBit(bits, i);
			int a = size - 11 + i % 3;
			int b = i / 3;
			setFunctionModule(modules, isFunction, a, b, bit);
			setFunctionModule(modules, isFunction, b, a, bit);
		}
	}

	private static boolean getBit(int x, int i) {
		return ((x >>> i) & 1) != 0;
	}

	private static void drawCodewords(boolean[][] modules, boolean[][] isFunction, byte[] data) {
		int size = modules.length;
		int bitIndex = 0;
		int dataLen = data.length;
		for(int right = size - 1; right >= 1; right -= 2) {
			if(right == 6) right = 5;
			for(int vert = 0; vert < size; ++vert) {
				for(int j = 0; j < 2; ++j) {
					int x = right - j;
					boolean upward = ((right + 1) & 2) == 0;
					int y = upward ? size - 1 - vert : vert;
					if(!isFunction[y][x] && bitIndex < dataLen * 8) {
						modules[y][x] = getBit(data[bitIndex >>> 3] & 0xFF, 7 - (bitIndex & 7));
						++bitIndex;
					}
				}
			}
		}
	}

	private static void applyMask(boolean[][] modules, boolean[][] isFunction, int mask) {
		int size = modules.length;
		for(int y = 0; y < size; ++y) {
			for(int x = 0; x < size; ++x) {
				boolean invert;
				switch(mask) {
				case 0: invert = (x + y) % 2 == 0; break;
				case 1: invert = y % 2 == 0; break;
				case 2: invert = x % 3 == 0; break;
				case 3: invert = (x + y) % 3 == 0; break;
				case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
				case 5: invert = x * y % 2 + x * y % 3 == 0; break;
				case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
				default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
				}
				if(!isFunction[y][x] && invert) {
					modules[y][x] = !modules[y][x];
				}
			}
		}
	}

	private static long maskPenalty(boolean[][] modules) {
		int size = modules.length;
		long result = 0;

		// N1: runs of same color in rows and columns
		for(int y = 0; y < size; ++y) {
			boolean color = modules[y][0];
			int run = 1;
			for(int x = 1; x < size; ++x) {
				if(modules[y][x] == color) {
					if(++run == 5) result += 3;
					else if(run > 5) ++result;
				}else {
					color = modules[y][x];
					run = 1;
				}
			}
		}
		for(int x = 0; x < size; ++x) {
			boolean color = modules[0][x];
			int run = 1;
			for(int y = 1; y < size; ++y) {
				if(modules[y][x] == color) {
					if(++run == 5) result += 3;
					else if(run > 5) ++result;
				}else {
					color = modules[y][x];
					run = 1;
				}
			}
		}

		// N2: 2x2 blocks of same color
		for(int y = 0; y < size - 1; ++y) {
			for(int x = 0; x < size - 1; ++x) {
				boolean c = modules[y][x];
				if(c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
					result += 3;
				}
			}
		}

		// N3: finder-like patterns
		for(int y = 0; y < size; ++y) {
			for(int x = 0; x < size - 10; ++x) {
				boolean p = modules[y][x] && !modules[y][x + 1] && modules[y][x + 2]
						&& modules[y][x + 3] && modules[y][x + 4] && !modules[y][x + 5]
						&& modules[y][x + 6] && !modules[y][x + 7] && !modules[y][x + 8]
						&& !modules[y][x + 9] && !modules[y][x + 10];
				if(p) result += 40;
			}
		}
		for(int x = 0; x < size; ++x) {
			for(int y = 0; y < size - 10; ++y) {
				boolean p = modules[y][x] && !modules[y + 1][x] && modules[y + 2][x]
						&& modules[y + 3][x] && modules[y + 4][x] && !modules[y + 5][x]
						&& modules[y + 6][x] && !modules[y + 7][x] && !modules[y + 8][x]
						&& !modules[y + 9][x] && !modules[y + 10][x];
				if(p) result += 40;
			}
		}

		// N4: dark/light balance
		int dark = 0;
		for(int y = 0; y < size; ++y) {
			for(int x = 0; x < size; ++x) {
				if(modules[y][x]) ++dark;
			}
		}
		int total = size * size;
		int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
		result += Math.max(0, k) * 10L;
		return result;
	}
}

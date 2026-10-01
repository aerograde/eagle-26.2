package net.lax1dude.eaglercraft.v1_8.teavmcompat.support.crypto;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Synchronous primitives required by the Minecraft protocol in browser builds.
 * WebCrypto cannot be used here because packet ciphers are synchronous and it
 * does not expose RSAES-PKCS1-v1_5 encryption.
 */
public final class MinecraftCrypto {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final BigInteger TWO = BigInteger.valueOf(2L);

	private MinecraftCrypto() {
	}

	public static byte[] randomBytes(int length) {
		byte[] result = new byte[length];
		RANDOM.nextBytes(result);
		return result;
	}

	public static byte[] rsaEncryptX509(byte[] x509Key, byte[] input) {
		DerReader outer = new DerReader(x509Key).readConstructed(0x30);
		outer.readConstructed(0x30);
		DerReader bits = outer.readValue(0x03);
		if (bits.readByte() != 0) {
			throw new IllegalArgumentException("unsupported RSA public-key bit padding");
		}
		DerReader rsa = bits.readConstructed(0x30);
		BigInteger modulus = rsa.readInteger();
		BigInteger exponent = rsa.readInteger();
		int blockSize = (modulus.bitLength() + 7) >>> 3;
		if (input.length > blockSize - 11) {
			throw new IllegalArgumentException("RSA plaintext is too large");
		}

		byte[] encoded = new byte[blockSize];
		encoded[0] = 0;
		encoded[1] = 2;
		int paddingEnd = blockSize - input.length - 1;
		byte[] random = randomBytes(paddingEnd - 2);
		for (int i = 0; i < random.length; ++i) {
			while (random[i] == 0) {
				random[i] = randomBytes(1)[0];
			}
			encoded[i + 2] = random[i];
		}
		encoded[paddingEnd] = 0;
		System.arraycopy(input, 0, encoded, paddingEnd + 1, input.length);

		BigInteger message = new BigInteger(1, encoded);
		if (message.compareTo(TWO) < 0 || message.compareTo(modulus) >= 0) {
			throw new IllegalArgumentException("invalid RSA encoded message");
		}
		return unsignedFixed(message.modPow(exponent, modulus), blockSize);
	}

	private static byte[] unsignedFixed(BigInteger value, int length) {
		byte[] raw = value.toByteArray();
		int source = raw.length > 1 && raw[0] == 0 ? 1 : 0;
		int count = raw.length - source;
		if (count > length) {
			throw new IllegalArgumentException("RSA result exceeds modulus size");
		}
		byte[] result = new byte[length];
		System.arraycopy(raw, source, result, length - count, count);
		return result;
	}

	public static final class AesCfb8 {

		private final int[] roundKeys;
		private final byte[] feedback = new byte[16];
		private final boolean encrypt;

		public AesCfb8(byte[] key, byte[] iv, boolean encrypt) {
			if (key.length != 16 || iv.length != 16) {
				throw new IllegalArgumentException("Minecraft AES requires a 128-bit key and IV");
			}
			this.roundKeys = expandKey(key);
			System.arraycopy(iv, 0, feedback, 0, 16);
			this.encrypt = encrypt;
		}

		public int update(byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset) {
			if (inputOffset < 0 || inputLength < 0 || inputOffset + inputLength > input.length
					|| outputOffset < 0 || outputOffset + inputLength > output.length) {
				throw new IndexOutOfBoundsException();
			}
			byte[] block = new byte[16];
			for (int i = 0; i < inputLength; ++i) {
				encryptBlock(feedback, block, roundKeys);
				byte source = input[inputOffset + i];
				byte transformed = (byte)(source ^ block[0]);
				output[outputOffset + i] = transformed;
				System.arraycopy(feedback, 1, feedback, 0, 15);
				feedback[15] = encrypt ? transformed : source;
			}
			return inputLength;
		}
	}

	private static final int[] SBOX = {
		0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
		0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
		0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
		0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
		0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
		0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
		0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
		0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
		0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
		0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
		0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
		0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
		0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
		0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
		0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
		0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
	};
	private static final int[] RCON = { 0, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36 };

	private static int[] expandKey(byte[] key) {
		int[] words = new int[44];
		for (int i = 0; i < 4; ++i) {
			int p = i << 2;
			words[i] = ((key[p] & 255) << 24) | ((key[p + 1] & 255) << 16)
					| ((key[p + 2] & 255) << 8) | (key[p + 3] & 255);
		}
		for (int i = 4; i < 44; ++i) {
			int temp = words[i - 1];
			if ((i & 3) == 0) {
				temp = subWord(Integer.rotateLeft(temp, 8)) ^ (RCON[i >>> 2] << 24);
			}
			words[i] = words[i - 4] ^ temp;
		}
		return words;
	}

	private static int subWord(int word) {
		return (SBOX[(word >>> 24) & 255] << 24) | (SBOX[(word >>> 16) & 255] << 16)
				| (SBOX[(word >>> 8) & 255] << 8) | SBOX[word & 255];
	}

	private static void encryptBlock(byte[] input, byte[] output, int[] keys) {
		byte[] state = input.clone();
		addRoundKey(state, keys, 0);
		for (int round = 1; round < 10; ++round) {
			subBytes(state);
			shiftRows(state);
			mixColumns(state);
			addRoundKey(state, keys, round);
		}
		subBytes(state);
		shiftRows(state);
		addRoundKey(state, keys, 10);
		System.arraycopy(state, 0, output, 0, 16);
	}

	private static void addRoundKey(byte[] state, int[] keys, int round) {
		for (int column = 0; column < 4; ++column) {
			int word = keys[(round << 2) + column];
			int p = column << 2;
			state[p] ^= (byte)(word >>> 24);
			state[p + 1] ^= (byte)(word >>> 16);
			state[p + 2] ^= (byte)(word >>> 8);
			state[p + 3] ^= (byte)word;
		}
	}

	private static void subBytes(byte[] state) {
		for (int i = 0; i < 16; ++i) {
			state[i] = (byte)SBOX[state[i] & 255];
		}
	}

	private static void shiftRows(byte[] state) {
		byte[] old = state.clone();
		for (int row = 0; row < 4; ++row) {
			for (int column = 0; column < 4; ++column) {
				state[(column << 2) + row] = old[(((column + row) & 3) << 2) + row];
			}
		}
	}

	private static void mixColumns(byte[] state) {
		for (int column = 0; column < 4; ++column) {
			int p = column << 2;
			int a = state[p] & 255;
			int b = state[p + 1] & 255;
			int c = state[p + 2] & 255;
			int d = state[p + 3] & 255;
			state[p] = (byte)(mul2(a) ^ mul2(b) ^ b ^ c ^ d);
			state[p + 1] = (byte)(a ^ mul2(b) ^ mul2(c) ^ c ^ d);
			state[p + 2] = (byte)(a ^ b ^ mul2(c) ^ mul2(d) ^ d);
			state[p + 3] = (byte)(mul2(a) ^ a ^ b ^ c ^ mul2(d));
		}
	}

	private static int mul2(int value) {
		return ((value << 1) ^ ((value & 0x80) != 0 ? 0x11b : 0)) & 255;
	}

	private static final class DerReader {
		private final byte[] data;
		private int position;
		private final int end;

		private DerReader(byte[] data) {
			this(data, 0, data.length);
		}

		private DerReader(byte[] data, int position, int end) {
			this.data = data;
			this.position = position;
			this.end = end;
		}

		private int readByte() {
			if (position >= end) {
				throw new IllegalArgumentException("truncated DER value");
			}
			return data[position++] & 255;
		}

		private DerReader readConstructed(int tag) {
			return readValue(tag);
		}

		private DerReader readValue(int tag) {
			if (readByte() != tag) {
				throw new IllegalArgumentException("unexpected DER tag");
			}
			int length = readLength();
			if (length < 0 || position + length > end) {
				throw new IllegalArgumentException("invalid DER length");
			}
			DerReader value = new DerReader(data, position, position + length);
			position += length;
			return value;
		}

		private int readLength() {
			int first = readByte();
			if ((first & 0x80) == 0) {
				return first;
			}
			int bytes = first & 0x7f;
			if (bytes == 0 || bytes > 4) {
				throw new IllegalArgumentException("unsupported DER length");
			}
			int length = 0;
			for (int i = 0; i < bytes; ++i) {
				length = (length << 8) | readByte();
			}
			return length;
		}

		private BigInteger readInteger() {
			DerReader integer = readValue(0x02);
			byte[] value = new byte[integer.end - integer.position];
			System.arraycopy(data, integer.position, value, 0, value.length);
			BigInteger result = new BigInteger(value);
			if (result.signum() <= 0) {
				throw new IllegalArgumentException("RSA integer must be positive");
			}
			return result;
		}
	}
}

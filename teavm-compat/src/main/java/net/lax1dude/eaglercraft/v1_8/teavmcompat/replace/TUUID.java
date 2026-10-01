package net.lax1dude.eaglercraft.v1_8.teavmcompat.replace;

import java.util.Random;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.support.crypto.MD5Digest;

/**
 * Whole-class replacement for java.util.UUID (wired by the mapClass rule in
 * META-INF/teavm.properties). teavm-classlib 0.13's TUUID is string-backed and
 * has no (long,long) constructor / bit accessors, which MC 26.2 relies on
 * everywhere (codecs, player ids, DFU). This is a faithful bit-based port of
 * the classic JDK implementation; randomUUID() uses java.util.Random, which is
 * acceptable in the sandboxed browser client (no security boundary crosses a
 * UUID here).
 */
public final class TUUID implements Comparable<TUUID> {

	private final long mostSigBits;
	private final long leastSigBits;

	private static Random random;

	public TUUID(long mostSigBits, long leastSigBits) {
		this.mostSigBits = mostSigBits;
		this.leastSigBits = leastSigBits;
	}

	private TUUID(byte[] data) {
		long msb = 0L;
		long lsb = 0L;
		for (int i = 0; i < 8; ++i) {
			msb = (msb << 8) | (data[i] & 0xFFL);
		}
		for (int i = 8; i < 16; ++i) {
			lsb = (lsb << 8) | (data[i] & 0xFFL);
		}
		this.mostSigBits = msb;
		this.leastSigBits = lsb;
	}

	public static TUUID randomUUID() {
		if (random == null) {
			random = new Random();
		}
		byte[] data = new byte[16];
		random.nextBytes(data);
		data[6] = (byte) ((data[6] & 0x0F) | 0x40); // version 4
		data[8] = (byte) ((data[8] & 0x3F) | 0x80); // IETF variant
		return new TUUID(data);
	}

	public static TUUID nameUUIDFromBytes(byte[] name) {
		MD5Digest md5 = new MD5Digest();
		md5.update(name, 0, name.length);
		byte[] data = new byte[16];
		md5.doFinal(data, 0);
		data[6] = (byte) ((data[6] & 0x0F) | 0x30); // version 3 (name-based)
		data[8] = (byte) ((data[8] & 0x3F) | 0x80); // IETF variant
		return new TUUID(data);
	}

	public static TUUID fromString(String name) {
		String[] components = name.split("-");
		if (components.length != 5) {
			throw new IllegalArgumentException("Invalid UUID string: " + name);
		}
		// components are at most 12 hex digits (48 bits), parseLong(_,16) is safe
		long mostSigBits = Long.parseLong(components[0], 16);
		mostSigBits <<= 16;
		mostSigBits |= Long.parseLong(components[1], 16);
		mostSigBits <<= 16;
		mostSigBits |= Long.parseLong(components[2], 16);
		long leastSigBits = Long.parseLong(components[3], 16);
		leastSigBits <<= 48;
		leastSigBits |= Long.parseLong(components[4], 16);
		return new TUUID(mostSigBits, leastSigBits);
	}

	public long getMostSignificantBits() {
		return mostSigBits;
	}

	public long getLeastSignificantBits() {
		return leastSigBits;
	}

	public int version() {
		return (int) ((mostSigBits >> 12) & 0x0FL);
	}

	public int variant() {
		return (int) ((leastSigBits >>> (64L - (leastSigBits >>> 62))) & (leastSigBits >> 63));
	}

	public long timestamp() {
		if (version() != 1) {
			throw new UnsupportedOperationException("Not a time-based UUID");
		}
		return (mostSigBits & 0x0FFFL) << 48 | ((mostSigBits >> 16) & 0x0FFFFL) << 32 | mostSigBits >>> 32;
	}

	public int clockSequence() {
		if (version() != 1) {
			throw new UnsupportedOperationException("Not a time-based UUID");
		}
		return (int) ((leastSigBits & 0x3FFF000000000000L) >>> 48);
	}

	public long node() {
		if (version() != 1) {
			throw new UnsupportedOperationException("Not a time-based UUID");
		}
		return leastSigBits & 0x0000FFFFFFFFFFFFL;
	}

	@Override
	public String toString() {
		return digits(mostSigBits >> 32, 8) + "-" + digits(mostSigBits >> 16, 4) + "-" + digits(mostSigBits, 4) + "-"
				+ digits(leastSigBits >> 48, 4) + "-" + digits(leastSigBits, 12);
	}

	private static String digits(long val, int digits) {
		long hi = 1L << (digits * 4);
		return Long.toHexString(hi | (val & (hi - 1))).substring(1);
	}

	@Override
	public int hashCode() {
		long hilo = mostSigBits ^ leastSigBits;
		return ((int) (hilo >> 32)) ^ (int) hilo;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof TUUID)) {
			return false;
		}
		TUUID other = (TUUID) obj;
		return mostSigBits == other.mostSigBits && leastSigBits == other.leastSigBits;
	}

	@Override
	public int compareTo(TUUID val) {
		if (mostSigBits < val.mostSigBits) {
			return -1;
		}
		if (mostSigBits > val.mostSigBits) {
			return 1;
		}
		if (leastSigBits < val.leastSigBits) {
			return -1;
		}
		if (leastSigBits > val.leastSigBits) {
			return 1;
		}
		return 0;
	}

}

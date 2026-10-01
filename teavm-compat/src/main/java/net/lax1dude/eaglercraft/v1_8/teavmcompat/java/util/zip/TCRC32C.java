package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.zip;

import java.util.zip.Checksum;

/**
 * java.util.zip.CRC32C for the browser runtime — a REAL implementation.
 *
 * <p>MC 26.2 uses CRC32C via net.minecraft.util.HashOps to hash registry data
 * during the client configuration phase (ClientboundFinishConfigurationPacket),
 * so a throwing stub crashes singleplayer right after world gen completes. This
 * is the standard reflected CRC-32C (Castagnoli, reversed polynomial
 * 0x82F63B78), byte-for-byte identical to the JDK's java.util.zip.CRC32C.</p>
 */
public class TCRC32C implements Checksum {

	private static final int[] TABLE = new int[256];

	static {
		for (int i = 0; i < 256; i++) {
			int crc = i;
			for (int j = 0; j < 8; j++) {
				if ((crc & 1) != 0) {
					crc = (crc >>> 1) ^ 0x82F63B78;
				} else {
					crc >>>= 1;
				}
			}
			TABLE[i] = crc;
		}
	}

	private int crc = 0xFFFFFFFF;

	public TCRC32C() {
	}

	@Override
	public void update(int b) {
		crc = (crc >>> 8) ^ TABLE[(crc ^ b) & 0xFF];
	}

	@Override
	public void update(byte[] b, int off, int len) {
		if (b == null) {
			throw new NullPointerException();
		}
		if (off < 0 || len < 0 || off > b.length - len) {
			throw new ArrayIndexOutOfBoundsException();
		}
		int c = crc;
		for (int i = off, end = off + len; i < end; i++) {
			c = (c >>> 8) ^ TABLE[(c ^ b[i]) & 0xFF];
		}
		crc = c;
	}

	@Override
	public long getValue() {
		return (~crc) & 0xFFFFFFFFL;
	}

	@Override
	public void reset() {
		crc = 0xFFFFFFFF;
	}
}

/*
 * Copyright (c) 2024 lax1dude. All Rights Reserved.
 *
 * Eaglercraft 1.8-compatible cape profile packet encoding.
 */
package net.lax1dude.eaglercraft.v1_8.profile;

public final class CapePackets {

	public static final int PACKET_MY_CAPE_PRESET = 0x01;
	public static final int PACKET_MY_CAPE_CUSTOM = 0x02;

	private static final int CUSTOM_CAPE_RGB_BYTES = 23 * 17 * 3;

	private CapePackets() {
	}

	public static byte[] writeMyCapePreset(int capeId) {
		return new byte[] { (byte) PACKET_MY_CAPE_PRESET, (byte) (capeId >>> 24), (byte) (capeId >>> 16),
				(byte) (capeId >>> 8), (byte) capeId };
	}

	public static byte[] writeMyCapeCustom(byte[] rgb) {
		if(rgb == null || rgb.length != CUSTOM_CAPE_RGB_BYTES) {
			throw new IllegalArgumentException("Custom cape must contain exactly 1173 RGB bytes");
		}
		byte[] packet = new byte[1 + CUSTOM_CAPE_RGB_BYTES];
		packet[0] = (byte) PACKET_MY_CAPE_CUSTOM;
		System.arraycopy(rgb, 0, packet, 1, CUSTOM_CAPE_RGB_BYTES);
		return packet;
	}
}

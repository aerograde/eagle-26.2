/*
 * Copyright (c) 2022-2023 lax1dude, ayunami2000. All Rights Reserved.
 *
 * This file keeps the Eaglercraft 1.8 profile packet wire format so skins
 * selected by the 26.2 profile screen can be sent to EaglerX servers.
 */
package net.lax1dude.eaglercraft.v1_8.profile;

public final class SkinPackets {

	public static final int PACKET_MY_SKIN_PRESET = 0x01;
	public static final int PACKET_MY_SKIN_CUSTOM = 0x02;

	private static final int CUSTOM_SKIN_RGBA_BYTES = 64 * 64 * 4;
	private static final int CUSTOM_SKIN_V4_BYTES = 64 * 64 * 3;

	private SkinPackets() {
	}

	public static byte[] writeMySkinPreset(int skinId) {
		return new byte[] { (byte) PACKET_MY_SKIN_PRESET, (byte) (skinId >>> 24), (byte) (skinId >>> 16),
				(byte) (skinId >>> 8), (byte) skinId };
	}

	public static byte[] writeMySkinCustomV3(int modelId, byte[] rgba) {
		checkCustomSkin(rgba);
		byte[] packet = new byte[2 + CUSTOM_SKIN_RGBA_BYTES];
		packet[0] = (byte) PACKET_MY_SKIN_CUSTOM;
		packet[1] = (byte) modelId;
		System.arraycopy(rgba, 0, packet, 2, CUSTOM_SKIN_RGBA_BYTES);
		return packet;
	}

	/**
	 * Eagler handshake v4/v5 stores each pixel in three bytes. The first two
	 * preserve green and blue; the last byte preserves alpha's upper seven bits
	 * and red's upper bit, exactly matching the official 1.8 workspace codec.
	 */
	public static byte[] writeMySkinCustomV4(int modelId, byte[] rgba) {
		checkCustomSkin(rgba);
		byte[] packet = new byte[2 + CUSTOM_SKIN_V4_BYTES];
		packet[0] = (byte) PACKET_MY_SKIN_CUSTOM;
		packet[1] = (byte) modelId;
		for(int i = 0; i < 4096; ++i) {
			int source = i << 2;
			int destination = i * 3 + 2;
			packet[destination] = rgba[source + 1];
			packet[destination + 1] = rgba[source + 2];
			packet[destination + 2] = (byte)(((rgba[source + 3] & 0xFF) >>> 1) | (rgba[source] & 0x80));
		}
		return packet;
	}

	private static void checkCustomSkin(byte[] rgba) {
		if(rgba == null || rgba.length != CUSTOM_SKIN_RGBA_BYTES) {
			throw new IllegalArgumentException("Custom skin must contain exactly 16384 RGBA bytes");
		}
	}
}

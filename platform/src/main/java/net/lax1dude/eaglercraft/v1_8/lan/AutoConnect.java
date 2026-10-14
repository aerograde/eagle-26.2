/*
 * Copyright (c) 2022-2024 lax1dude, ayunami2000. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.
 *
 */

package net.lax1dude.eaglercraft.v1_8.lan;

/**
 * Serverless automatic LAN connection, desktop/unknown-runtime view.
 *
 * <p>The web client runs the AutoConnect plan in
 * {@code target_teavm_wasm_gc/src/web/autoconnect.js} and reads it through the
 * bridge of the same name in {@code :platform-teavm} (which wins on the web
 * target). This stub keeps the signatures identical so the game layer never has
 * to branch on the platform: off the web target every method reports "not
 * available" and the manual join-code flow stays the only path.
 */
public final class AutoConnect {

	/** Version of the JS module the web bridge speaks to (kept in step with it). */
	public static final int VERSION = 4;

	private AutoConnect() {
	}

	public static boolean isInstalled() {
		return false;
	}

	public static String getState() {
		return "disabled";
	}

	public static String getPhase() {
		return null;
	}

	public static String getStatus() {
		return "Auto-connect is not available on this platform";
	}

	public static boolean isFallback() {
		return false;
	}

	public static String getCode() {
		return null;
	}

	public static boolean isCodeMode() {
		return false;
	}

	public static String getSelfName() {
		return null;
	}

	public static void start(String name) {
	}

	public static void stop() {
	}

	public static String newCode() {
		return null;
	}

	public static boolean enterCode(String code) {
		return false;
	}

	public static String getPeersJson() {
		return "[]";
	}

	public static String getSnapshotJson() {
		return "{\"version\":" + VERSION + ",\"state\":\"disabled\",\"peers\":[]}";
	}

	public static String pollEventsJson() {
		return "[]";
	}

	public static boolean sendText(String text) {
		return false;
	}

	public static void announceAtBoot() {
	}
}

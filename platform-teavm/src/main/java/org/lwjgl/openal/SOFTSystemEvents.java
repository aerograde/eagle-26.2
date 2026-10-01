package org.lwjgl.openal;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
// Audio: alcOpenDevice returns 0 so Library.init throws IllegalStateException into
// SoundEngine.loadLibrary's catch (game continues silently, TODO(3.5) WebAudio).

public final class SOFTSystemEvents {

	private SOFTSystemEvents() {
	}

	public static boolean alcEventControlSOFT(int[] events, boolean enable) {
		return false;
	}

	public static void alcEventCallbackSOFT(SOFTSystemEventProcI callback, long userParam) {
	}

	public static int alcEventIsSupportedSOFT(int eventType, int deviceType) {
		return 0;
	}
}

package org.lwjgl.openal;

import java.util.List;

// Eagler 26.2: WebAudio-backed OpenAL (see WebAudioAL).
public final class ALUtil {

	private ALUtil() {
	}

	public static List<String> getStringList(long device, int token) {
		// ALC_ALL_DEVICES_SPECIFIER (4115): the single WebAudio device
		return token == 4115 ? List.of(ALC10.DEVICE_NAME) : List.of();
	}
}

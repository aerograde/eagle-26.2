package org.lwjgl.openal;

// Eagler 26.2: WebAudio-backed OpenAL (see WebAudioAL).
public final class ALC11 {

	private ALC11() {
	}

	public static int alcGetInteger(long device, int token) {
		// ALC_CONNECTED (787): the WebAudio "device" can never disconnect
		return token == 787 ? 1 : ALC10.alcGetInteger(device, token);
	}
}

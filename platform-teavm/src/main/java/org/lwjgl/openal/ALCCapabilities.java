package org.lwjgl.openal;

// Eagler 26.2: WebAudio-backed OpenAL (see WebAudioAL). Library.init requires
// OpenALC11; HRTF stays off (WebAudio's PannerNode HRTF is per-source, not ALC).
public class ALCCapabilities {

	public final boolean OpenALC11 = true;
	public final boolean ALC_SOFT_HRTF = false;

	ALCCapabilities() {
	}
}

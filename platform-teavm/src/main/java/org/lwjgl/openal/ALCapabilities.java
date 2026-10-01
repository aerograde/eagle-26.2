package org.lwjgl.openal;

// Eagler 26.2: WebAudio-backed OpenAL (see WebAudioAL). Library.init requires both
// extensions; the backend implements the per-source linear distance model natively
// (PannerNode distanceModel = "linear").
public class ALCapabilities {

	public final boolean AL_EXT_source_distance_model = true;
	public final boolean AL_EXT_LINEAR_DISTANCE = true;

	ALCapabilities() {
	}
}

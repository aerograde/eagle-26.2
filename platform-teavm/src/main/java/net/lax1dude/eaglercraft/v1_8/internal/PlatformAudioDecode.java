package net.lax1dude.eaglercraft.v1_8.internal;

import java.util.function.IntConsumer;

/**
 * Web impl of the audio decode seam: routes to the WebAudio engine's browser-native
 * decodeAudioData path (Opus-in-Ogg for music). Shadows the {@code :platform} stub by FQN
 * on the web classpath.
 */
public final class PlatformAudioDecode {

	private PlatformAudioDecode() {
	}

	public static void decodeToAlBufferAsync(final byte[] encoded, final IntConsumer onDone, final Runnable onErr) {
		org.lwjgl.openal.WebAudioAL.decodeAndRegister(encoded, onDone, onErr);
	}
}

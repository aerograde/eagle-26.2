package net.lax1dude.eaglercraft.v1_8.internal;

import java.util.function.IntConsumer;

/**
 * Web-only audio decode seam. The browser decodes encoded audio (Opus/Vorbis) natively
 * via {@code AudioContext.decodeAudioData} and the result is registered as an AL buffer;
 * {@code onDone} receives that buffer id (async). Desktop decodes with JOrbis directly in
 * {@code SoundBufferLibrary} and never calls this — the stub exists only so {@code :game}
 * compiles against the platform contract. The real impl lives in {@code :platform-teavm}
 * and shadows this by FQN on the web classpath (same mechanism as the other platform
 * native stubs).
 */
public final class PlatformAudioDecode {

	private PlatformAudioDecode() {
	}

	public static void decodeToAlBufferAsync(final byte[] encoded, final IntConsumer onDone, final Runnable onErr) {
		throw new UnsupportedOperationException("PlatformAudioDecode is web-only");
	}
}

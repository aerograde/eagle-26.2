package com.mojang.text2speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eagler 26.2 web facade for com.mojang.text2speech.Narrator.
 *
 * The real text2speech library probes native OS text-to-speech backends
 * (Windows SAPI / macOS NSSpeechSynthesizer / Linux speech-dispatcher via JNI),
 * none of which exist in the browser. On TeaVM its getNarrator() throws
 * Narrator$InitializeException("Unsupported platform TeaVM"), logs
 * "Error while loading the narrator", and falls back to a no-op — an error every
 * boot for a feature that can never work on the web.
 *
 * This facade skips the probe AND the error: getNarrator() returns a silent
 * no-op EMPTY narrator directly, and active() is false so GameNarrator never
 * attempts narration (the onboarding shows "Narrator: Not Available", as it
 * should). The real jar is excluded from the web target (see
 * target_teavm/build.gradle.kts: exclude com.mojang:text2speech). API mirrors the
 * real interface exactly (say/clear/active/destroy/getNarrator/EMPTY/LOGGER).
 */
public interface Narrator {

	Logger LOGGER = LoggerFactory.getLogger("Narrator");

	Narrator EMPTY = new Narrator() {
		@Override
		public void say(final String message, final boolean interrupt, final float volume) {
		}

		@Override
		public void clear() {
		}

		@Override
		public boolean active() {
			return false;
		}

		@Override
		public void destroy() {
		}
	};

	void say(String message, boolean interrupt, float volume);

	void clear();

	default boolean active() {
		return false;
	}

	void destroy();

	static Narrator getNarrator() {
		return EMPTY;
	}

}

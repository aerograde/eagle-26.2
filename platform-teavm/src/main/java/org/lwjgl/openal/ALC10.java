package org.lwjgl.openal;

import java.nio.IntBuffer;

// Eagler 26.2: WebAudio-backed OpenAL (see WebAudioAL). The ALC layer fakes a single
// always-connected device ("WebAudio", handle 1) whose context is the AudioContext.
public final class ALC10 {

	static final long DEVICE_HANDLE = 1L;
	static final long CONTEXT_HANDLE = 1L;
	static final String DEVICE_NAME = "WebAudio";

	private ALC10() {
	}

	public static long alcOpenDevice(CharSequence deviceSpecifier) {
		return WebAudioAL.ensureContext() ? DEVICE_HANDLE : 0L;
	}

	public static boolean alcCloseDevice(long device) {
		return true;
	}

	public static long alcCreateContext(long device, IntBuffer attrList) {
		return WebAudioAL.ensureContext() ? CONTEXT_HANDLE : 0L;
	}

	public static boolean alcMakeContextCurrent(long context) {
		return context == CONTEXT_HANDLE;
	}

	public static void alcDestroyContext(long context) {
		WebAudioAL.destroyContext();
	}

	public static int alcGetInteger(long device, int token) {
		switch (token) {
			case 4098: // ALC_ATTRIBUTES_SIZE
				return 3;
			case 6548: // ALC_NUM_HRTF_SPECIFIERS_SOFT
				return 0;
			case 787: // ALC_CONNECTED
				return 1;
			default:
				return 0;
		}
	}

	public static void alcGetIntegerv(long device, int token, IntBuffer dest) {
		if (token == 4099 && dest.remaining() >= 3) { // ALC_ALL_ATTRIBUTES
			int base = dest.position();
			dest.put(base, 4112);    // ALC_MONO_SOURCES
			dest.put(base + 1, 32);  // Library.getChannelCount -> 32 channels
			dest.put(base + 2, 0);
		}
	}

	public static String alcGetString(long device, int token) {
		switch (token) {
			case 4101: // ALC_DEVICE_SPECIFIER
			case 4114: // ALC_DEFAULT_ALL_DEVICES_SPECIFIER
			case 4115: // ALC_ALL_DEVICES_SPECIFIER
				return DEVICE_NAME;
			default:
				return null;
		}
	}

	public static boolean alcIsExtensionPresent(long device, CharSequence extName) {
		// advertise device enumeration (so the sound-device UI lists "WebAudio");
		// ALC_EXT_disconnect / SOFT events stay unsupported -> polling tracker
		return "ALC_ENUMERATE_ALL_EXT".contentEquals(extName);
	}

	public static int alcGetError(long device) {
		return 0;
	}
}

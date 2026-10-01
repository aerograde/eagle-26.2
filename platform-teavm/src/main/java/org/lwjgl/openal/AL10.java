package org.lwjgl.openal;

import java.nio.ByteBuffer;

// Eagler 26.2: WebAudio-backed OpenAL (TODO 3.5 done — was a silent linkage stub).
// The blaze3d audio layer's whole AL surface delegates to WebAudioAL, which maps
// buffers/sources/listener onto the browser's AudioContext graph.
public final class AL10 {

	private AL10() {
	}

	public static void alGenSources(int[] sources) {
		WebAudioAL.genSources(sources);
	}

	public static void alDeleteSources(int[] sources) {
		WebAudioAL.deleteSources(sources);
	}

	public static void alSourceStop(int source) {
		WebAudioAL.sourceStop(source);
	}

	public static void alSourcePlay(int source) {
		WebAudioAL.sourcePlay(source);
	}

	public static void alSourcePause(int source) {
		WebAudioAL.sourcePause(source);
	}

	public static int alGetSourcei(int source, int param) {
		return WebAudioAL.getSourcei(source, param);
	}

	public static void alSourcei(int source, int param, int value) {
		WebAudioAL.sourcei(source, param, value);
	}

	public static void alSourcef(int source, int param, float value) {
		WebAudioAL.sourcef(source, param, value);
	}

	public static void alSourcefv(int source, int param, float[] values) {
		WebAudioAL.sourcefv(source, param, values);
	}

	public static void alSource3f(int source, int param, float v1, float v2, float v3) {
		WebAudioAL.source3f(source, param, v1, v2, v3);
	}

	public static void alSourceQueueBuffers(int source, int[] buffers) {
		WebAudioAL.sourceQueueBuffers(source, buffers);
	}

	public static void alSourceQueueBuffers(int source, int buffer) {
		WebAudioAL.sourceQueueBuffers(source, new int[]{buffer});
	}

	public static int alSourceUnqueueBuffers(int source) {
		int[] one = new int[1];
		WebAudioAL.sourceUnqueueBuffers(source, one);
		return one[0];
	}

	public static void alSourceUnqueueBuffers(int source, int[] buffers) {
		WebAudioAL.sourceUnqueueBuffers(source, buffers);
	}

	public static void alGenBuffers(int[] buffers) {
		WebAudioAL.genBuffers(buffers);
	}

	public static void alDeleteBuffers(int[] buffers) {
		WebAudioAL.deleteBuffers(buffers);
	}

	public static void alDeleteBuffers(int buffer) {
		WebAudioAL.deleteBuffers(new int[]{buffer});
	}

	public static void alBufferData(int buffer, int format, ByteBuffer data, int frequency) {
		WebAudioAL.bufferData(buffer, format, data, frequency);
	}

	public static int alGetError() {
		return 0;
	}

	public static String alGetString(int param) {
		return "WebAudio";
	}

	public static void alEnable(int capability) {
		// AL_SOURCE_DISTANCE_MODEL (512): per-source models are native to this backend
	}

	public static void alDistanceModel(int distanceModel) {
	}

	public static void alListenerf(int param, float value) {
	}

	public static void alListener3f(int param, float v1, float v2, float v3) {
		WebAudioAL.listener3f(param, v1, v2, v3);
	}

	public static void alListenerfv(int param, float[] values) {
		WebAudioAL.listenerfv(param, values);
	}
}

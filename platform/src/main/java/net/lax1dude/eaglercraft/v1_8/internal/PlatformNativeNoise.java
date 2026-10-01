package net.lax1dude.eaglercraft.v1_8.internal;

/**
 * DESKTOP stub of the native worldgen-noise seam. On desktop the JVM runs Minecraft's
 * {@code NormalNoise} math directly at full native speed, so there is nothing to accelerate:
 * {@code available()} returns {@code false} and shared game code ({@code NormalNoise}) keeps
 * using the pure-Java path, never calling the other methods. The real, web-only impl lives in
 * {@code :platform-teavm} and shadows this by FQN on the web classpath (same mechanism as
 * {@link PlatformAudioDecode}); this stub exists only so {@code :game} compiles against the
 * platform contract.
 */
public final class PlatformNativeNoise {

	private PlatformNativeNoise() {
	}

	public static boolean available() {
		return false;
	}

	public static int allocSlot() {
		throw new UnsupportedOperationException("PlatformNativeNoise is web-only");
	}

	public static void writeHeader(int slot, double valueFactor, double inputFactor, int firstCount, int secondCount) {
		throw new UnsupportedOperationException("PlatformNativeNoise is web-only");
	}

	public static void writeOctave(int slot, int k, String perm256, double xo, double yo, double zo,
			double amplitude, double freqFactor, double valueFactor) {
		throw new UnsupportedOperationException("PlatformNativeNoise is web-only");
	}

	public static double noiseSlot(int slot, double x, double y, double z) {
		throw new UnsupportedOperationException("PlatformNativeNoise is web-only");
	}

	public static int maxBatchPoints() {
		return 0;
	}

	public static void noiseBatch(int slot, double[] packedCoordinates, double[] output, int count) {
		throw new UnsupportedOperationException("PlatformNativeNoise is web-only");
	}

}

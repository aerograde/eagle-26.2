package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.sound.sampled;

/**
 * javax.sound.sampled.AudioFormat — link-only value holder. The browser has no
 * javax.sound audio line; real playback is WebAudio elsewhere. This exists so
 * code that constructs and queries an AudioFormat descriptor links. The stored
 * fields are returned faithfully; getFrameSize() is derived exactly as the JDK.
 */
public class TAudioFormat {

	private final TAudioFormat$Encoding encoding;
	private final float sampleRate;
	private final int sampleSizeInBits;
	private final int channels;
	private final int frameSize;
	private final float frameRate;
	private final boolean bigEndian;

	public TAudioFormat(float sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian) {
		this.encoding = signed ? TAudioFormat$Encoding.PCM_SIGNED : TAudioFormat$Encoding.PCM_UNSIGNED;
		this.sampleRate = sampleRate;
		this.sampleSizeInBits = sampleSizeInBits;
		this.channels = channels;
		this.bigEndian = bigEndian;
		this.frameRate = sampleRate;
		if (sampleSizeInBits != AudioSystemNotSpecified && channels != AudioSystemNotSpecified) {
			this.frameSize = ((sampleSizeInBits + 7) / 8) * channels;
		} else {
			this.frameSize = AudioSystemNotSpecified;
		}
	}

	public TAudioFormat(TAudioFormat$Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
			int frameSize, float frameRate, boolean bigEndian) {
		this.encoding = encoding;
		this.sampleRate = sampleRate;
		this.sampleSizeInBits = sampleSizeInBits;
		this.channels = channels;
		this.frameSize = frameSize;
		this.frameRate = frameRate;
		this.bigEndian = bigEndian;
	}

	public TAudioFormat$Encoding getEncoding() {
		return encoding;
	}

	public float getSampleRate() {
		return sampleRate;
	}

	public int getSampleSizeInBits() {
		return sampleSizeInBits;
	}

	public int getChannels() {
		return channels;
	}

	public int getFrameSize() {
		return frameSize;
	}

	public float getFrameRate() {
		return frameRate;
	}

	public boolean isBigEndian() {
		return bigEndian;
	}

	/** Mirrors javax.sound.sampled.AudioSystem.NOT_SPECIFIED (-1). */
	private static final int AudioSystemNotSpecified = -1;
}

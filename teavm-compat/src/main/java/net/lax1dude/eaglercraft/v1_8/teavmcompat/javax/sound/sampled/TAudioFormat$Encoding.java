package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.sound.sampled;

/**
 * javax.sound.sampled.AudioFormat$Encoding — link-only nested type. The '$' in
 * this top-level class's name is intentional so its binary name matches what
 * the package mapping expects
 * (net...teavmcompat.javax.sound.sampled.TAudioFormat$Encoding →
 * javax.sound.sampled.AudioFormat$Encoding). Identity is carried by the name
 * string, exactly like the real class.
 */
public class TAudioFormat$Encoding {

	public static final TAudioFormat$Encoding PCM_SIGNED = new TAudioFormat$Encoding("PCM_SIGNED");
	public static final TAudioFormat$Encoding PCM_UNSIGNED = new TAudioFormat$Encoding("PCM_UNSIGNED");
	public static final TAudioFormat$Encoding PCM_FLOAT = new TAudioFormat$Encoding("PCM_FLOAT");
	public static final TAudioFormat$Encoding ULAW = new TAudioFormat$Encoding("ULAW");
	public static final TAudioFormat$Encoding ALAW = new TAudioFormat$Encoding("ALAW");

	private final String name;

	public TAudioFormat$Encoding(String name) {
		this.name = name;
	}

	@Override
	public final boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof TAudioFormat$Encoding)) {
			return false;
		}
		TAudioFormat$Encoding other = (TAudioFormat$Encoding) obj;
		return name == null ? other.name == null : name.equals(other.name);
	}

	@Override
	public final int hashCode() {
		return name == null ? 0 : name.hashCode();
	}

	@Override
	public final String toString() {
		return name;
	}
}

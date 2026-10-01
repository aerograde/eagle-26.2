package net.lax1dude.eaglercraft.v1_8.teavmcompat.jdk.jfr;

/**
 * jdk.jfr.Configuration — link-only stub. There are no recording
 * configurations in the browser runtime, so lookups return null and metadata
 * getters return empty values.
 */
public final class TConfiguration {

	private TConfiguration() {
	}

	public String getName() {
		return "";
	}

	public String getLabel() {
		return "";
	}

	public String getDescription() {
		return "";
	}

	public String getContents() {
		return "";
	}

	public static TConfiguration getConfiguration(String name) {
		return null;
	}

	public static TConfiguration create(java.io.Reader reader) {
		throw new UnsupportedOperationException("no JFR configurations in the browser runtime");
	}
}

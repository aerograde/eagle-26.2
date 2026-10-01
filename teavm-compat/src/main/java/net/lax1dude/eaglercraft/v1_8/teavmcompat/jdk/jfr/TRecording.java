package net.lax1dude.eaglercraft.v1_8.teavmcompat.jdk.jfr;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * jdk.jfr.Recording — link-only stub implementing Closeable. Every method is
 * inert: nothing is recorded in the browser runtime. Present so profiler code
 * that constructs and configures a Recording links and no-ops cleanly.
 */
public final class TRecording implements Closeable {

	public TRecording() {
	}

	public TRecording(TConfiguration configuration) {
	}

	public void start() {
	}

	public boolean stop() {
		return false;
	}

	@Override
	public void close() {
	}

	public void setName(String name) {
	}

	public void setDuration(Duration duration) {
	}

	public void setMaxSize(long maxSize) {
	}

	public void setMaxAge(Duration maxAge) {
	}

	public void setToDisk(boolean disk) {
	}

	public void setDumpOnExit(boolean dumpOnExit) {
	}

	public String getName() {
		return "";
	}

	public long getId() {
		return 0L;
	}

	public Path getDestination() {
		return null;
	}

	public void setDestination(Path destination) throws IOException {
	}

	public TEventSettings enable(Class<? extends TEvent> eventClass) {
		return new TEventSettings();
	}

	public TEventSettings enable(String name) {
		return new TEventSettings();
	}
}

package org.lwjgl.util.tinyfd;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only). MessageBox.error
// pipes here on fatal boot errors; log to stderr instead of a native dialog
// (the eagler crash overlay is the real UI).
public final class TinyFileDialogs {

	private TinyFileDialogs() {
	}

	public static int tinyfd_messageBox(CharSequence title, CharSequence message, CharSequence dialogType, CharSequence iconType,
			int defaultButton) {
		System.err.println("[tinyfd stub] " + title + ": " + message);
		return defaultButton;
	}
}

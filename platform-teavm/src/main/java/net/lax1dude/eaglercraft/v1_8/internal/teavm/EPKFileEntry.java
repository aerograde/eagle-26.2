/*
 * Copyright (c) 2022-2026 lax1dude / Eagler 26.2.
 */
package net.lax1dude.eaglercraft.v1_8.internal.teavm;

/**
 * One resolved EPK URL and its optional mount prefix.
 *
 * <p>This is deliberately independent of {@link ClientMain}. The dedicated
 * integrated-server worker needs to fetch the same EPK but must not make the
 * browser UI/crash-screen entry class reachable in its precise TeaVM graph.</p>
 */
public final class EPKFileEntry {

	public final String url;
	public final String path;

	public EPKFileEntry(String url, String path) {
		this.url = url;
		this.path = path;
	}
}

package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Donor for java.io.File (see plugin.JdkMethodInjector). TeaVM 0.13's TFile
 * lacks toPath(); reached from client Main (LevelStorageSource / pack paths).
 * Delegates to Paths.get on the (virtual) default filesystem.
 */
public final class FileInject {

	/** placeholder: the target already implements getPath (never copied) */
	public String getPath() {
		return null;
	}

	public Path toPath() {
		return Paths.get(getPath());
	}

	public boolean canExecute() {
		return false;
	}

}

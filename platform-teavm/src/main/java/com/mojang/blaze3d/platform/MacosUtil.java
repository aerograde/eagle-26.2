package com.mojang.blaze3d.platform;

import java.io.InputStream;

import net.minecraft.server.packs.resources.IoSupplier;

/**
 * Eagler 26.2 Phase 3.3b same-FQN SHADOW: the desktop version's method bodies
 * reference ca.weblite.objc + com.sun.jna + GLFWNativeCocoa, which are not on the
 * web classpath (objc is compileOnly even on desktop). TeaVM reach analysis does
 * not prune the {@code if (MacosUtil.IS_MACOS)} branches in Window/GlBackend, so
 * the class is replaced wholesale. The browser is never macOS-native: IS_MACOS is
 * hard false and every entry point is a no-op.
 */
public class MacosUtil {

	public static final boolean IS_MACOS = false;

	public static void exitNativeFullscreen(final Window window) {
	}

	public static void clearResizableBit(final Window window) {
	}

	public static void loadIcon(final IoSupplier<InputStream> icon) {
	}

	public static void setWindowColorSpaceForOpenGLBecauseGLFWDoesnt(final long window) {
	}
}

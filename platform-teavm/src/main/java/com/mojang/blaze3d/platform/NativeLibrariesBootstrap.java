package com.mojang.blaze3d.platform;

/**
 * Eagler 26.2 Phase 3.3b same-FQN SHADOW: the desktop version extracts/loads the
 * LWJGL, Vulkan, GL, AL, STB, tinyfd, freetype, shaderc, spvc and vma native
 * libraries (org.lwjgl.system.Library/Configuration/VK/GL/...), none of which
 * exist in the browser. Shadowing the whole class severs that entire loader tree
 * from TeaVM reachability; the web "natives" are the org.lwjgl.* linkage stubs in
 * this module. Called unconditionally from net.minecraft.client.main.Main.
 */
public class NativeLibrariesBootstrap {

	public static void loadLibraries() {
		// no native libraries in the browser; org.lwjgl.* stubs are pure Java
	}
}

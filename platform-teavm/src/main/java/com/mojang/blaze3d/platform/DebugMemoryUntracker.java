package com.mojang.blaze3d.platform;

/**
 * Eagler 26.2 Phase 3.3b same-FQN SHADOW: the desktop version reflectively
 * resolves org.lwjgl.system.MemoryManage$DebugAllocator / MemoryUtil$LazyInit in
 * its clinit and RETHROWS on failure — under TeaVM (no reflection, MethodHandles
 * stubbed to throw) that would kill NativeImage.close(), which calls untrack()
 * on the live texture path. The web build has no LWJGL debug allocator; untrack
 * is a no-op.
 */
public class DebugMemoryUntracker {

	public static void untrack(final long address) {
	}
}

package net.lax1dude.eaglercraft.v1_8.touch_gui;

import java.util.HashMap;
import java.util.Map;

import net.lax1dude.eaglercraft.v1_8.internal.EnumTouchEvent;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformInput;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Native browser touch controls adapted from the official EaglercraftX 1.8
 * control layout to the 26.2 input and extracted-GUI pipelines. Touches remain
 * identified for their full lifetime, so a look finger cannot become an action
 * button merely by crossing a control while dragging.
 */
public final class TouchControls26 {

	private enum Control {
		UP, DOWN, LEFT, RIGHT, JUMP, SNEAK, SPRINT, ATTACK, USE, INVENTORY, PAUSE,
		CHAT, DEBUG, FULLSCREEN, HOTBAR, LOOK, SCREEN
	}

	private static final Map<Integer, Control> controls = new HashMap<>();
	private static final Map<Integer, Integer> lastX = new HashMap<>();
	private static final Map<Integer, Integer> lastY = new HashMap<>();
	private static boolean lastScreenOpen;
	private static boolean lastEnabled = true;
	private static boolean up;
	private static boolean down;
	private static boolean left;
	private static boolean right;
	private static boolean jump;
	private static boolean sneak;
	private static boolean sprint;
	private static boolean attack;
	private static boolean use;

	private TouchControls26() {
	}

	public static boolean isEnabled() {
		return PlatformInput.isTouchClient() && PlatformInput.isTouchControlsEnabled();
	}

	public static void update(final Minecraft minecraft) {
		boolean enabled = isEnabled();
		if (enabled != lastEnabled) {
			controls.clear();
			lastX.clear();
			lastY.clear();
			releaseWorldControls(minecraft);
			lastEnabled = enabled;
		}
		updateNativeChatKeyboardShortcut(minecraft, enabled);
		updateNativeFullscreenShortcut(minecraft, enabled);
		boolean screenOpen = minecraft.gui.screen() != null || minecraft.gui.overlay() != null;
		if (screenOpen != lastScreenOpen) {
			controls.clear();
			lastX.clear();
			lastY.clear();
			releaseWorldControls(minecraft);
			lastScreenOpen = screenOpen;
		}

		while (PlatformInput.touchNext()) {
			EnumTouchEvent type = PlatformInput.touchGetEventType();
			int count = PlatformInput.touchGetEventTouchPointCount();
			for (int i = 0; i < count; ++i) {
				int uid = PlatformInput.touchGetEventTouchPointUID(i);
				int x = PlatformInput.touchGetEventTouchX(i);
				int y = PlatformInput.touchGetEventTouchY(i);
				if (type == EnumTouchEvent.TOUCHSTART) {
					Control control = null;
					if (enabled) {
						control = hitTestToolbar(minecraft, x, y);
						if (control == null) {
							control = screenOpen ? Control.SCREEN : hitTestWorld(minecraft, x, y);
						}
					} else if (screenOpen) {
						control = Control.SCREEN;
					}
					if (control != null) {
						controls.put(Integer.valueOf(uid), control);
						lastX.put(Integer.valueOf(uid), Integer.valueOf(x));
						lastY.put(Integer.valueOf(uid), Integer.valueOf(y));
						onStart(minecraft, control, x, y);
					}
				} else if (type == EnumTouchEvent.TOUCHMOVE) {
					Control control = controls.get(Integer.valueOf(uid));
					if (control != null) {
						onMove(minecraft, uid, control, x, y);
					}
				} else if (type == EnumTouchEvent.TOUCHEND) {
					Control control = controls.remove(Integer.valueOf(uid));
					lastX.remove(Integer.valueOf(uid));
					lastY.remove(Integer.valueOf(uid));
					if (control == Control.SCREEN) {
						minecraft.mouseHandler.eaglerTouchPointer(x, toTopY(y), false);
					}
				}
			}
		}

		if (enabled && !screenOpen) {
			applyWorldControls(minecraft);
		} else {
			releaseWorldControls(minecraft);
		}
	}

	private static void updateNativeChatKeyboardShortcut(final Minecraft minecraft, final boolean enabled) {
		if (!enabled || minecraft.level == null || minecraft.gui.screen() instanceof ChatScreen) {
			PlatformInput.touchSetOpenKeyboardShortcutZone(0, 0, 0, 0);
			return;
		}
		int guiWidth = Math.max(1, minecraft.getWindow().getGuiScaledWidth());
		int guiHeight = Math.max(1, minecraft.getWindow().getGuiScaledHeight());
		int physicalWidth = PlatformInput.getWindowWidth();
		int physicalHeight = PlatformInput.getWindowHeight();
		int x = (guiWidth - 96) * physicalWidth / guiWidth;
		int y = physicalHeight - (4 + 24) * physicalHeight / guiHeight;
		int w = Math.max(1, 28 * physicalWidth / guiWidth);
		int h = Math.max(1, 24 * physicalHeight / guiHeight);
		PlatformInput.touchSetOpenKeyboardShortcutZone(x, y, w, h);
	}

	private static void updateNativeFullscreenShortcut(final Minecraft minecraft, final boolean enabled) {
		if (!enabled) {
			PlatformInput.touchSetFullscreenShortcutZone(0, 0, 0, 0);
			return;
		}
		int guiWidth = Math.max(1, minecraft.getWindow().getGuiScaledWidth());
		int guiHeight = Math.max(1, minecraft.getWindow().getGuiScaledHeight());
		int physicalWidth = PlatformInput.getWindowWidth();
		int physicalHeight = PlatformInput.getWindowHeight();
		int x = (guiWidth - 32) * physicalWidth / guiWidth;
		int y = physicalHeight - (4 + 24) * physicalHeight / guiHeight;
		int w = Math.max(1, 28 * physicalWidth / guiWidth);
		int h = Math.max(1, 24 * physicalHeight / guiHeight);
		PlatformInput.touchSetFullscreenShortcutZone(x, y, w, h);
	}

	private static void onStart(final Minecraft minecraft, final Control control, final int x, final int y) {
		switch (control) {
		case SCREEN:
			minecraft.mouseHandler.eaglerTouchPointer(x, toTopY(y), true);
			break;
		case INVENTORY:
			minecraft.options.keyInventory.eaglerTouchClick();
			break;
		case PAUSE:
			minecraft.pauseGame(false);
			break;
		case CHAT:
			if (minecraft.gui.screen() instanceof ChatScreen) {
				PlatformInput.touchCloseDeviceKeyboard();
				minecraft.gui.setScreen(null);
			} else if (minecraft.player != null) {
				minecraft.gui.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
			}
			break;
		case DEBUG:
			minecraft.debugEntries.toggleDebugOverlay();
			break;
		case FULLSCREEN:
			// The platform touch listener performs this synchronously on TOUCHEND so
			// Android's transient user activation is still valid.
			break;
		case HOTBAR:
			if (minecraft.player != null) {
				int guiX = toGuiX(minecraft, x);
				int left = minecraft.getWindow().getGuiScaledWidth() / 2 - 90;
				int slot = Math.max(0, Math.min(8, (guiX - left) / 20));
				minecraft.player.getInventory().setSelectedSlot(slot);
			}
			break;
		default:
			break;
		}
	}

	private static void onMove(final Minecraft minecraft, final int uid, final Control control, final int x, final int y) {
		Integer oldX = lastX.put(Integer.valueOf(uid), Integer.valueOf(x));
		Integer oldY = lastY.put(Integer.valueOf(uid), Integer.valueOf(y));
		if (oldX == null || oldY == null) {
			return;
		}
		if (control == Control.LOOK) {
			minecraft.mouseHandler.eaglerTouchLookDelta(x - oldX.intValue(), oldY.intValue() - y);
		} else if (control == Control.SCREEN) {
			minecraft.mouseHandler.eaglerTouchPointer(x, toTopY(y), null);
		}
	}

	private static void applyWorldControls(final Minecraft minecraft) {
		boolean nextUp = has(Control.UP);
		boolean nextDown = has(Control.DOWN);
		boolean nextLeft = has(Control.LEFT);
		boolean nextRight = has(Control.RIGHT);
		boolean nextJump = has(Control.JUMP);
		boolean nextSneak = has(Control.SNEAK);
		boolean nextSprint = has(Control.SPRINT);
		boolean nextAttack = has(Control.ATTACK);
		boolean nextUse = has(Control.USE);
		set(minecraft.options.keyUp, nextUp, up);
		set(minecraft.options.keyDown, nextDown, down);
		set(minecraft.options.keyLeft, nextLeft, left);
		set(minecraft.options.keyRight, nextRight, right);
		set(minecraft.options.keyJump, nextJump, jump);
		set(minecraft.options.keyShift, nextSneak, sneak);
		set(minecraft.options.keySprint, nextSprint, sprint);
		set(minecraft.options.keyAttack, nextAttack, attack);
		set(minecraft.options.keyUse, nextUse, use);
		if (nextAttack && !attack) minecraft.options.keyAttack.eaglerTouchClick();
		if (nextUse && !use) minecraft.options.keyUse.eaglerTouchClick();
		up = nextUp;
		down = nextDown;
		left = nextLeft;
		right = nextRight;
		jump = nextJump;
		sneak = nextSneak;
		sprint = nextSprint;
		attack = nextAttack;
		use = nextUse;
	}

	private static void releaseWorldControls(final Minecraft minecraft) {
		set(minecraft.options.keyUp, false, up);
		set(minecraft.options.keyDown, false, down);
		set(minecraft.options.keyLeft, false, left);
		set(minecraft.options.keyRight, false, right);
		set(minecraft.options.keyJump, false, jump);
		set(minecraft.options.keyShift, false, sneak);
		set(minecraft.options.keySprint, false, sprint);
		set(minecraft.options.keyAttack, false, attack);
		set(minecraft.options.keyUse, false, use);
		up = down = left = right = jump = sneak = sprint = attack = use = false;
	}

	private static void set(final KeyMapping mapping, final boolean value, final boolean oldValue) {
		if (value != oldValue) {
			mapping.setDown(value);
		}
	}

	private static boolean has(final Control control) {
		return controls.containsValue(control);
	}

	private static Control hitTestToolbar(final Minecraft minecraft, final int physicalX, final int physicalY) {
		int x = toGuiX(minecraft, physicalX);
		int y = toGuiY(minecraft, physicalY);
		int width = minecraft.getWindow().getGuiScaledWidth();
		if (inside(x, y, width - 32, 4, 28, 24)) return Control.FULLSCREEN;
		if (minecraft.level != null) {
			if (inside(x, y, width - 64, 4, 28, 24)) return Control.DEBUG;
			if (inside(x, y, width - 96, 4, 28, 24)) return Control.CHAT;
			if (minecraft.gui.screen() == null && inside(x, y, width - 134, 4, 34, 24)) return Control.INVENTORY;
		}
		return null;
	}

	private static Control hitTestWorld(final Minecraft minecraft, final int physicalX, final int physicalY) {
		int x = toGuiX(minecraft, physicalX);
		int y = toGuiY(minecraft, physicalY);
		int width = minecraft.getWindow().getGuiScaledWidth();
		int height = minecraft.getWindow().getGuiScaledHeight();
		if (inside(x, y, 42, height - 88, 32, 32)) return Control.UP;
		if (inside(x, y, 42, height - 38, 32, 32)) return Control.DOWN;
		if (inside(x, y, 14, height - 63, 32, 32)) return Control.LEFT;
		if (inside(x, y, 70, height - 63, 32, 32)) return Control.RIGHT;
		if (inside(x, y, width - 52, height - 66, 38, 38)) return Control.JUMP;
		if (inside(x, y, width - 98, height - 42, 32, 32)) return Control.SNEAK;
		if (inside(x, y, 108, height - 43, 34, 30)) return Control.SPRINT;
		if (inside(x, y, width - 52, height - 116, 38, 38)) return Control.ATTACK;
		if (inside(x, y, width - 103, height - 96, 38, 38)) return Control.USE;
		if (inside(x, y, width / 2 - 16, 8, 32, 26)) return Control.PAUSE;
		if (inside(x, y, width / 2 - 92, height - 25, 184, 25)) return Control.HOTBAR;
		return Control.LOOK;
	}

	private static boolean inside(int x, int y, int left, int top, int width, int height) {
		return x >= left && x < left + width && y >= top && y < top + height;
	}

	private static int toGuiX(final Minecraft minecraft, final int physicalX) {
		return physicalX * minecraft.getWindow().getGuiScaledWidth() / Math.max(1, PlatformInput.getWindowWidth());
	}

	private static int toGuiY(final Minecraft minecraft, final int physicalY) {
		return toTopY(physicalY) * minecraft.getWindow().getGuiScaledHeight() / Math.max(1, PlatformInput.getWindowHeight());
	}

	private static int toTopY(final int physicalY) {
		return PlatformInput.getWindowHeight() - physicalY - 1;
	}

	public static void extractRenderState(final Minecraft minecraft, final GuiGraphicsExtractor graphics) {
		if (!isEnabled()) {
			return;
		}
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		graphics.nextStratum();
		button(graphics, minecraft, width - 32, 4, 28, 24, "FS", false);
		if (minecraft.level != null) {
			button(graphics, minecraft, width - 64, 4, 28, 24, "F3", false);
			button(graphics, minecraft, width - 96, 4, 28, 24, "T", false);
			if (minecraft.gui.screen() == null && minecraft.gui.overlay() == null) {
				button(graphics, minecraft, 42, height - 88, 32, 32, "W", has(Control.UP));
				button(graphics, minecraft, 42, height - 38, 32, 32, "S", has(Control.DOWN));
				button(graphics, minecraft, 14, height - 63, 32, 32, "A", has(Control.LEFT));
				button(graphics, minecraft, 70, height - 63, 32, 32, "D", has(Control.RIGHT));
				button(graphics, minecraft, width - 52, height - 66, 38, 38, "JMP", has(Control.JUMP));
				button(graphics, minecraft, width - 98, height - 42, 32, 32, "SNK", has(Control.SNEAK));
				button(graphics, minecraft, 108, height - 43, 34, 30, "RUN", has(Control.SPRINT));
				button(graphics, minecraft, width - 52, height - 116, 38, 38, "ATK", has(Control.ATTACK));
				button(graphics, minecraft, width - 103, height - 96, 38, 38, "USE", has(Control.USE));
				button(graphics, minecraft, width - 134, 4, 34, 24, "INV", false);
				button(graphics, minecraft, width / 2 - 16, 8, 32, 26, "ESC", false);
			}
		}
	}

	private static void button(final GuiGraphicsExtractor graphics, final Minecraft minecraft,
			final int x, final int y, final int width, final int height, final String label, final boolean pressed) {
		graphics.fill(x, y, x + width, y + height, pressed ? 0xA0606060 : 0x70303030);
		graphics.outline(x, y, width, height, pressed ? 0xE0FFFFFF : 0xA0FFFFFF);
		graphics.centeredText(minecraft.font, label, x + width / 2, y + (height - 8) / 2, 0xFFFFFFFF);
	}
}

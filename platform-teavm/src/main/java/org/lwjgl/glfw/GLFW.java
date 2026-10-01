package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.lwjgl.PointerBuffer;

import net.lax1dude.eaglercraft.v1_8.internal.PlatformApplication;
import net.lax1dude.eaglercraft.v1_8.internal.KeyboardConstants;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformInput;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;

/**
 * Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees
 * this module). There is no GLFW in the browser: the canvas is the "window"
 * (fake handle 1L), sizes come straight from the game canvas element, input is
 * pumped by PlatformInput through the hosted seam (MouseHandler/KeyboardHandler
 * eaglerPumpEvents), and presentation is requestAnimationFrame in
 * WebGL2Surface.present(). Everything else is a benign no-op with the return
 * value the callers' code paths expect (see the per-method notes).
 *
 * Window resize: the framebuffer/window size callbacks registered by
 * com.mojang.blaze3d.platform.Window are stored here and re-fired by
 * {@link #eaglerPumpSizeCallbacks()} (called from WebGL2Surface.present after
 * PlatformInput.update resizes the canvas), which keeps Minecraft's
 * windowSurfaceNeedsReconfiguring machinery working in the browser.
 */
public final class GLFW {

	public static final long EAGLER_WINDOW_HANDLE = 1L;

	private static GLFWErrorCallbackI errorCallback = null;

	private static GLFWFramebufferSizeCallbackI framebufferSizeCallback = null;
	private static GLFWWindowSizeCallbackI windowSizeCallback = null;
	private static GLFWWindowPosCallbackI windowPosCallback = null;
	private static GLFWWindowFocusCallbackI windowFocusCallback = null;
	private static GLFWCursorEnterCallbackI cursorEnterCallback = null;
	private static GLFWWindowIconifyCallbackI windowIconifyCallback = null;
	private static GLFWWindowCloseCallbackI windowCloseCallback = null;
	private static GLFWMonitorCallbackI monitorCallback = null;

	private static int lastFiredWidth = -1;
	private static int lastFiredHeight = -1;
	private static boolean lastFiredFocus = true;

	private GLFW() {
	}

	private static int canvasWidth() {
		return PlatformRuntime.canvas != null ? Math.max(1, PlatformRuntime.canvas.getWidth()) : 854;
	}

	private static int canvasHeight() {
		return PlatformRuntime.canvas != null ? Math.max(1, PlatformRuntime.canvas.getHeight()) : 480;
	}

	/** Fires the stored size callbacks if the canvas size changed since last pump. */
	public static void eaglerPumpSizeCallbacks() {
		int w = canvasWidth();
		int h = canvasHeight();
		if (w != lastFiredWidth || h != lastFiredHeight) {
			lastFiredWidth = w;
			lastFiredHeight = h;
			if (windowSizeCallback != null) {
				windowSizeCallback.invoke(EAGLER_WINDOW_HANDLE, w, h);
			}
			if (framebufferSizeCallback != null) {
				framebufferSizeCallback.invoke(EAGLER_WINDOW_HANDLE, w, h);
			}
		}
		boolean focused = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.getWindowFocused();
		if (focused != lastFiredFocus) {
			lastFiredFocus = focused;
			if (windowFocusCallback != null) {
				windowFocusCallback.invoke(EAGLER_WINDOW_HANDLE, focused);
			}
		}
	}

	static void eaglerClearWindowCallbacks() {
		framebufferSizeCallback = null;
		windowSizeCallback = null;
		windowPosCallback = null;
		windowFocusCallback = null;
		cursorEnterCallback = null;
		windowIconifyCallback = null;
		windowCloseCallback = null;
	}

	// ==== init / platform ====

	public static boolean glfwInit() {
		return true;
	}

	public static void glfwTerminate() {
	}

	public static void glfwInitHint(final int hint, final int value) {
	}

	public static boolean glfwPlatformSupported(final int platform) {
		// GLX._initGlfw probes X11/Wayland; report neither so no init hints apply
		return platform == 393221; // GLFW_PLATFORM_NULL
	}

	public static int glfwGetPlatform() {
		// "null" platform: Window.setIcon and friends skip their native paths
		return 393221;
	}

	public static double glfwGetTime() {
		return PlatformRuntime.steadyTimeMillisTeaVM() / 1000.0;
	}

	public static int glfwGetError(final PointerBuffer description) {
		return 0;
	}

	public static void glfwPollEvents() {
		// DOM events queue asynchronously; the hosted pump drains PlatformInput
	}

	// ==== window lifecycle ====

	public static void glfwDefaultWindowHints() {
	}

	public static void glfwWindowHint(final int hint, final int value) {
	}

	public static long glfwCreateWindow(final int width, final int height, final CharSequence title, final long monitor, final long share) {
		lastFiredWidth = -1; // force one size-callback pump after creation
		lastFiredHeight = -1;
		return EAGLER_WINDOW_HANDLE;
	}

	public static void glfwDestroyWindow(final long window) {
	}

	public static void glfwShowWindow(final long window) {
	}

	public static void glfwSetWindowTitle(final long window, final CharSequence title) {
	}

	public static void glfwSetWindowSizeLimits(final long window, final int minWidth, final int minHeight, final int maxWidth, final int maxHeight) {
	}

	public static void glfwSetWindowIcon(final long window, final GLFWImage.Buffer images) {
	}

	public static boolean glfwWindowShouldClose(final long window) {
		return false;
	}

	public static void glfwGetWindowPos(final long window, final int[] xpos, final int[] ypos) {
		if (xpos != null && xpos.length > 0) {
			xpos[0] = 0;
		}
		if (ypos != null && ypos.length > 0) {
			ypos[0] = 0;
		}
	}

	public static void glfwGetFramebufferSize(final long window, final int[] width, final int[] height) {
		if (width != null && width.length > 0) {
			width[0] = canvasWidth();
		}
		if (height != null && height.length > 0) {
			height[0] = canvasHeight();
		}
	}

	// ==== monitors / video modes ====

	public static long glfwGetPrimaryMonitor() {
		return 0L;
	}

	public static PointerBuffer glfwGetMonitors() {
		return null; // MonitorManager null-guards; no monitors in the browser
	}

	public static long glfwGetWindowMonitor(final long window) {
		return 0L; // always "windowed"
	}

	public static void glfwSetWindowMonitor(final long window, final long monitor, final int x, final int y, final int width, final int height,
			final int refreshRate) {
	}

	public static GLFWVidMode glfwGetVideoMode(final long monitor) {
		return new GLFWVidMode(canvasWidth(), canvasHeight(), 60);
	}

	public static GLFWVidMode.Buffer glfwGetVideoModes(final long monitor) {
		return null;
	}

	public static void glfwGetMonitorPos(final long monitor, final int[] xpos, final int[] ypos) {
		if (xpos != null && xpos.length > 0) {
			xpos[0] = 0;
		}
		if (ypos != null && ypos.length > 0) {
			ypos[0] = 0;
		}
	}

	public static String glfwGetMonitorName(final long monitor) {
		return "Browser Canvas";
	}

	// ==== input queries ====

	public static int glfwGetKey(final long window, final int key) {
		// Poll the same held-key state used by the hosted event pump. Minecraft also
		// polls modifiers directly, so delivering key callbacks alone is insufficient.
		int eaglerKey = KeyboardConstants.getEaglerKeyFromGLFW(key);
		return eaglerKey != KeyboardConstants.KEY_NONE && PlatformInput.keyboardIsKeyDown(eaglerKey)
				? 1 : 0; // GLFW_PRESS / GLFW_RELEASE
	}

	public static String glfwGetKeyName(final int key, final int scancode) {
		// Desktop GLFW returns the keyboard-layout character for printable keys and null for the
		// rest (space, enter, arrows, F-keys, modifiers, keypad). InputConstants.KEYSYM uses that
		// name when non-null and otherwise falls back to Component.translatable("key.keyboard.*").
		// en_us.json only ships names for the NON-printable keys, so the old unconditional null
		// made printable keys (W/A/S/D, digits) render their raw "key.keyboard.w" translation key.
		// wasm-gc-only so the JS build stays byte-identical.
		if (org.teavm.classlib.PlatformDetector.isWebAssemblyGC()) {
			if (key >= 65 && key <= 90) { // GLFW_KEY_A..Z -> lowercase 'a'..'z' (KEYSYM upper-cases)
				return String.valueOf((char) (key + 32));
			}
			if (key >= 48 && key <= 57) { // GLFW_KEY_0..9
				return String.valueOf((char) key);
			}
			switch (key) {
				case 39: return "'";  // GLFW_KEY_APOSTROPHE
				case 44: return ",";  // GLFW_KEY_COMMA
				case 45: return "-";  // GLFW_KEY_MINUS
				case 46: return ".";  // GLFW_KEY_PERIOD
				case 47: return "/";  // GLFW_KEY_SLASH
				case 59: return ";";  // GLFW_KEY_SEMICOLON
				case 61: return "=";  // GLFW_KEY_EQUAL
				case 91: return "[";  // GLFW_KEY_LEFT_BRACKET
				case 92: return "\\"; // GLFW_KEY_BACKSLASH
				case 93: return "]";  // GLFW_KEY_RIGHT_BRACKET
				case 96: return "`";  // GLFW_KEY_GRAVE_ACCENT
				default: break;
			}
		}
		return null; // non-printable keys (and the JS build): callers fall back to key.keyboard.* names
	}

	public static void glfwSetCursorPos(final long window, final double xpos, final double ypos) {
	}

	public static void glfwSetInputMode(final long window, final int mode, final int value) {
	}

	public static int glfwGetInputMode(final long window, final int mode) {
		if (mode == 208897) { // GLFW_CURSOR
			return 212993; // GLFW_CURSOR_NORMAL
		}
		return 0; // e.g. GLFW_IME (208903): off
	}

	public static boolean glfwRawMouseMotionSupported() {
		return false;
	}

	public static void glfwSetPreeditCursorRectangle(final long window, final int x, final int y, final int width, final int height) {
	}

	public static long glfwCreateStandardCursor(final int shape) {
		return 0L; // CursorType.select becomes a no-op; CSS cursors are TODO(3.4)
	}

	public static void glfwSetCursor(final long window, final long cursor) {
	}

	public static String glfwGetClipboardString(final long window) {
		return PlatformApplication.getClipboard();
	}

	public static void glfwSetClipboardString(final long window, final ByteBuffer string) {
		int start = string.position();
		int end = start;
		while(end < string.limit() && string.get(end) != 0) {
			++end;
		}
		byte[] data = new byte[end - start];
		for(int i = 0; i < data.length; ++i) {
			data[i] = string.get(start + i);
		}
		PlatformApplication.setClipboard(new String(data, StandardCharsets.UTF_8));
	}

	// ==== callbacks ====

	public static GLFWErrorCallback glfwSetErrorCallback(final GLFWErrorCallbackI callback) {
		GLFWErrorCallbackI previous = errorCallback;
		errorCallback = callback;
		return previous instanceof GLFWErrorCallback cb ? cb : null;
	}

	public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(final long window, final GLFWFramebufferSizeCallbackI callback) {
		framebufferSizeCallback = callback;
		return null;
	}

	public static GLFWWindowPosCallback glfwSetWindowPosCallback(final long window, final GLFWWindowPosCallbackI callback) {
		windowPosCallback = callback;
		return null;
	}

	public static GLFWWindowSizeCallback glfwSetWindowSizeCallback(final long window, final GLFWWindowSizeCallbackI callback) {
		windowSizeCallback = callback;
		return null;
	}

	public static GLFWWindowFocusCallback glfwSetWindowFocusCallback(final long window, final GLFWWindowFocusCallbackI callback) {
		windowFocusCallback = callback;
		return null;
	}

	public static GLFWCursorEnterCallback glfwSetCursorEnterCallback(final long window, final GLFWCursorEnterCallbackI callback) {
		cursorEnterCallback = callback;
		return null;
	}

	public static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(final long window, final GLFWWindowIconifyCallbackI callback) {
		windowIconifyCallback = callback;
		return null;
	}

	public static GLFWWindowCloseCallback glfwSetWindowCloseCallback(final long window, final GLFWWindowCloseCallbackI callback) {
		windowCloseCallback = callback;
		return null;
	}

	public static GLFWMonitorCallback glfwSetMonitorCallback(final GLFWMonitorCallbackI callback) {
		monitorCallback = callback;
		return null;
	}

	public static GLFWKeyCallback glfwSetKeyCallback(final long window, final GLFWKeyCallbackI callback) {
		return null; // hosted input never registers these (setup() gated), linkage only
	}

	public static GLFWCharCallback glfwSetCharCallback(final long window, final GLFWCharCallbackI callback) {
		return null;
	}

	public static GLFWPreeditCallback glfwSetPreeditCallback(final long window, final GLFWPreeditCallbackI callback) {
		return null;
	}

	public static GLFWIMEStatusCallback glfwSetIMEStatusCallback(final long window, final GLFWIMEStatusCallbackI callback) {
		return null;
	}

	public static GLFWCursorPosCallback glfwSetCursorPosCallback(final long window, final GLFWCursorPosCallbackI callback) {
		return null;
	}

	public static GLFWMouseButtonCallback glfwSetMouseButtonCallback(final long window, final GLFWMouseButtonCallbackI callback) {
		return null;
	}

	public static GLFWScrollCallback glfwSetScrollCallback(final long window, final GLFWScrollCallbackI callback) {
		return null;
	}

	public static GLFWDropCallback glfwSetDropCallback(final long window, final GLFWDropCallbackI callback) {
		return null;
	}
}

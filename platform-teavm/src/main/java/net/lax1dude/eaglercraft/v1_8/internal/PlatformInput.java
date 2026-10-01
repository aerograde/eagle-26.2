/*
 * Copyright (c) 2022-2025 lax1dude, ayunami2000. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 */

package net.lax1dude.eaglercraft.v1_8.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.TimerHandler;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.css.CSSStyleDeclaration;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.InputEvent;
import org.teavm.jso.dom.events.KeyboardEvent;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.events.Touch;
import org.teavm.jso.dom.events.TouchEvent;
import org.teavm.jso.dom.events.WheelEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.dom.html.HTMLFormElement;
import org.teavm.jso.dom.html.HTMLInputElement;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.LegacyKeycodeTranslator;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMUtils;

/**
 * Phase 3.2b: the real browser DOM input backend.
 *
 * Ported from EaglercraftX-1.8-workspace-master src/teavm PlatformInput (~2410
 * lines): DOM keyboard/mouse/wheel listeners, event queues, pointer lock,
 * fullscreen + keyboard-lock, vsync via requestAnimationFrame, window/DPI
 * bookkeeping. It feeds the SAME event contract the desktop backend
 * (:platform-lwjgl) fills, so the game's Phase-2c input seam
 * (KeyboardHandler/MouseHandler.eaglerPumpEvents) works unmodified.
 *
 * 26.2 UPGRADE (the new work vs upstream): upstream only produced eagler
 * keycodes; the 26.2 game consumes GLFW-style codes through the host* fields
 * added to the :platform event classes. On every keyboard event we now also
 * fill:
 *   - hostKey      = GLFW key constant  (DOM which/code -> eagler key ->
 *                    {@link KeyboardConstants#getGLFWKeyFromEagler(int)})
 *   - hostScancode = 0 (browsers expose no GLFW hardware scancode; the game
 *                    only reads scancode for debug when key()==-1, which never
 *                    happens here because the seam skips hostKey==0 events)
 *   - hostMods     = GLFW GLFW_MOD_* bitmask built from the DOM modifier flags
 * and on every mouse event: hostMods (GLFW bitmask) + hostScrollX (GLFW-signed
 * horizontal wheel) + the raw float wheel (mouseGetEventDWheelF). This mirrors
 * exactly how {@code :platform-lwjgl initHooksHosted} fills them from the GLFW
 * callbacks.
 *
 * DEVIATIONS from upstream (dependencies not yet copied this phase; each a TODO):
 *   - touch and the on-screen keyboard use the native TeaVM DOM interfaces
 *     directly instead of the old SortedTouchEvent/OffsetTouch wrappers
 *   - gamepad*: inert (net.lax1dude...Gamepad JSO wrapper not copied) -- TODO(3.x)
 *   - visual viewport: reported equal to the window (VisualViewport not copied)
 *   - contextLost(): false (PlatformRuntime.webgl not wired) -- TODO(3.3)
 *   - update(): keeps rAF/fps-limit + canvas resize, drops WebGLBackBuffer /
 *     PlatformScreenRecord / PlatformOpenGL.finish (classes not copied) -- TODO(3.3)
 *   - press-any-key / mobile launch screens dropped (EarlyLoadScreen not copied)
	 *   - fullscreen and pointer lock remain browser-native DOM operations
 */
public class PlatformInput {

	private static Window win = null;
	private static HTMLElement parent = null;
	private static HTMLCanvasElement canvas = null;

	private static EventListener<?> contextmenu = null;
	private static EventListener<?> mousedown = null;
	private static EventListener<?> mouseup = null;
	private static EventListener<?> mousemove = null;
	private static EventListener<?> mouseenter = null;
	private static EventListener<?> mouseleave = null;
	private static EventListener<?> keydown = null;
	private static EventListener<?> keyup = null;
	private static EventListener<?> wheel = null;
	private static EventListener<?> touchstart = null;
	private static EventListener<?> touchmove = null;
	private static EventListener<?> touchend = null;
	private static EventListener<?> touchcancel = null;
	private static EventListener<?> touchKeyboardOpenZoneTouchStart = null;
	private static EventListener<?> touchKeyboardOpenZoneTouchMove = null;
	private static EventListener<?> touchKeyboardOpenZoneTouchEnd = null;
	private static EventListener<?> focus = null;
	private static EventListener<?> blur = null;
	private static EventListener<?> pointerlock = null;
	private static EventListener<?> pointerlockerr = null;
	private static EventListener<?> fullscreen = null;

	private static Map<String,LegacyKeycodeTranslator.LegacyKeycode> keyCodeTranslatorMap = null;

	// GLFW modifier bitmask (matches org.lwjgl.glfw.GLFW GLFW_MOD_*); the 26.2 game
	// reads these off KeyEvent/MouseButtonInfo. Lock-key bits (CAPS/NUM) are omitted
	// to match the desktop hosted path (GLFW_LOCK_KEY_MODS is not enabled there).
	private static final int GLFW_MOD_SHIFT = 0x0001;
	private static final int GLFW_MOD_CONTROL = 0x0002;
	private static final int GLFW_MOD_ALT = 0x0004;
	private static final int GLFW_MOD_SUPER = 0x0008;
	// A 64-event ring holds only 32 key presses. A single long browser frame while
	// worldgen or shaders are busy could therefore drop the beginning of a command
	// even when the user/harness typed at a normal pace. This remains a small,
	// bounded queue while covering several seconds of real keyboard input.
	private static final int MAX_KEY_EVENTS = 256;

	private static final int EVENT_KEY_DOWN = 0;
	private static final int EVENT_KEY_UP = 1;
	private static final int EVENT_KEY_REPEAT = 2;

	private static class VKeyEvent {

		private final int eagKey;
		private final char keyChar;
		private final int type;
		// 26.2 hosted mode: lossless host (GLFW) data alongside the eagler code
		private final int hostKey;
		private final int hostScancode;
		private final int hostMods;

		private VKeyEvent(int eagKey, char keyChar, int type, int hostKey, int hostScancode, int hostMods) {
			this.eagKey = eagKey;
			this.keyChar = keyChar;
			this.type = type;
			this.hostKey = hostKey;
			this.hostScancode = hostScancode;
			this.hostMods = hostMods;
		}

	}

	private static final int EVENT_MOUSE_DOWN = 0;
	private static final int EVENT_MOUSE_UP = 1;
	private static final int EVENT_MOUSE_MOVE = 2;
	private static final int EVENT_MOUSE_WHEEL = 3;
	private static final int MAX_MOUSE_EVENTS = 64;

	private static class VMouseEvent {

		private final int posX;
		private final int posY;
		private final int sourceWidth;
		private final int sourceHeight;
		private final int button;
		private final float wheel;
		private final int type;
		// 26.2 hosted mode: modifiers + horizontal scroll from the host event
		private final int hostMods;
		private final float hostScrollX;

		private VMouseEvent(int posX, int posY, int button, float wheel, int type, int hostMods, float hostScrollX) {
			this(posX, posY, button, wheel, type, hostMods, hostScrollX, windowWidth, windowHeight);
		}

		private VMouseEvent(int posX, int posY, int button, float wheel, int type, int hostMods,
				float hostScrollX, int sourceWidth, int sourceHeight) {
			this.posX = posX;
			this.posY = posY;
			this.sourceWidth = Math.max(1, sourceWidth);
			this.sourceHeight = Math.max(1, sourceHeight);
			this.button = button;
			this.wheel = wheel;
			this.type = type;
			this.hostMods = hostMods;
			this.hostScrollX = hostScrollX;
		}

	}

	private static final List<VMouseEvent> mouseEvents = new LinkedList<>();
	private static final List<VKeyEvent> keyEvents = new LinkedList<>();
	private static final List<VTouchEvent> touchEvents = new LinkedList<>();
	private static final Map<Integer, Integer> touchIdentifiers = new HashMap<>();
	private static final List<String> pastedStrings = new LinkedList<>();
	private static int nextTouchUid;
	private static VTouchEvent currentTouchEvent;
	private static List<VTouchPoint> currentTouchState = Collections.emptyList();
	private static HTMLElement touchKeyboardOpenZone;
	private static HTMLFormElement touchKeyboardForm;
	private static HTMLInputElement touchKeyboardField;
	private static int touchOpenZoneX;
	private static int touchOpenZoneY;
	private static int touchOpenZoneW;
	private static int touchOpenZoneH;
	private static int touchKeyboardShortcutX;
	private static int touchKeyboardShortcutY;
	private static int touchKeyboardShortcutW;
	private static int touchKeyboardShortcutH;
	private static int touchFullscreenShortcutX;
	private static int touchFullscreenShortcutY;
	private static int touchFullscreenShortcutW;
	private static int touchFullscreenShortcutH;
	private static double lastTouchBeforeInputMs;
	private static boolean touchKeyboardComposing;
	private static boolean touchKeyboardCompositionCommitted;
	private static String lastTouchCompositionCommit;
	private static double lastTouchCompositionCommitMs;

	private static class VTouchPoint {
		private final int x;
		private final int y;
		private final int sourceWidth;
		private final int sourceHeight;
		private final float radius;
		private final float force;
		private final int uid;

		private VTouchPoint(int x, int y, float radius, float force, int uid) {
			this(x, y, radius, force, uid, windowWidth, windowHeight);
		}

		private VTouchPoint(int x, int y, float radius, float force, int uid, int sourceWidth, int sourceHeight) {
			this.x = x;
			this.y = y;
			this.sourceWidth = Math.max(1, sourceWidth);
			this.sourceHeight = Math.max(1, sourceHeight);
			this.radius = radius;
			this.force = force;
			this.uid = uid;
		}
	}

	private static class VTouchEvent {
		private final EnumTouchEvent type;
		private final List<VTouchPoint> eventTouches;

		private VTouchEvent(EnumTouchEvent type, List<VTouchPoint> eventTouches) {
			this.type = type;
			this.eventTouches = eventTouches;
		}
	}

	private static int mouseX = 0;
	private static int mouseY = 0;
	private static double mouseDX = 0.0D;
	private static double mouseDY = 0.0D;
	private static double mouseDWheel = 0.0D;
	private static boolean enableRepeatEvents = true;
	private static boolean isWindowFocused = true;
	private static boolean isMouseOverWindow = true;
	public static boolean unpressCTRL = false;

	private static int windowWidth = 1;
	private static int windowHeight = 1;
	private static float windowDPI = 1.0f;
	private static int lastWasResizedWindowWidth = -2;
	private static int lastWasResizedWindowHeight = -2;
	private static float lastWasResizedWindowDPI = -2.0f;
	private static int lastWasResizedVisualViewportW = -2;
	private static int lastWasResizedVisualViewportH = -2;

	private static VMouseEvent currentEvent = null;
	private static VKeyEvent currentEventK = null;
	private static boolean[] buttonStates = new boolean[8];
	private static boolean[] keyStates = new boolean[256];
	private static int[] functionKeyLatches = new int[256];

	private static int functionKeyModifier = KeyboardConstants.KEY_F;

	public static boolean isLikelyMobileBrowser = false;
	private static boolean touchControlsEnabled = true;

	// Can't support webkit vendor prefix since there's no document.pointerLockElement
	private static final int POINTER_LOCK_NONE = 0;
	private static final int POINTER_LOCK_CORE = 1;
	private static final int POINTER_LOCK_MOZ = 2;
	private static int pointerLockSupported = POINTER_LOCK_NONE;
	private static long mouseUngrabTimer = 0l;
	private static long mouseGrabTimer = 0l;
	private static int mouseUngrabTimeout = -1;
	private static boolean pointerLockFlag = false;
	private static boolean pointerLockRequested = false;
	private static boolean pointerLockWaiting = false;
	private static boolean unexpectedPointerLockLoss = false;
	private static boolean remoteDesktopMouseMode = false;
	private static TrustedEscapeHandler trustedEscapeHandler = null;

	@FunctionalInterface
	public interface TrustedEscapeHandler {
		void handleEscape(int hostMods);
	}

	private static final int FULLSCREEN_NONE = 0;
	private static final int FULLSCREEN_CORE = 1;
	private static final int FULLSCREEN_WEBKIT = 2;
	private static final int FULLSCREEN_MOZ = 3;
	private static int fullscreenSupported = FULLSCREEN_NONE;

	private static JSObject fullscreenQuery = null;

	public static boolean keyboardLockSupported = false;
	public static boolean lockKeys = false;

	static boolean vsync = true;
	static boolean vsyncSupport = false;

	private static int vsyncTimeout = -1;
	private static boolean vsyncPrearmed = false;
	private static boolean vsyncReady = false;
	private static AsyncCallback<Void> vsyncWaiter = null;

	/**
	 * 26.2 hosted mode: attach the browser DOM input hooks. The {@code window} arg
	 * is the game's GLFW handle on desktop; in the browser there is no GLFW window,
	 * so it is ignored and the listeners bind to the runtime-owned canvas/window
	 * ({@link PlatformRuntime#win}/{@link PlatformRuntime#canvas}). Keyboard events
	 * bind to the window, mouse/wheel to the canvas -- the same targets upstream used.
	 */
	public static void initHooksHosted(long window) {
		win = PlatformRuntime.win;
		parent = PlatformRuntime.parent;
		canvas = PlatformRuntime.canvas;
		if(win == null) win = Window.current();
		if(canvas == null) {
			PlatformRuntime.logger.error("PlatformInput.initHooksHosted: no canvas available, input will not work");
			return;
		}

		canvas.getStyle().setProperty("cursor", "default");
		canvas.getStyle().setProperty("touch-action", "none");
		installTouchKeyboardOpenZone();

		// seed window metrics so mouse-Y flipping is sane before the first update()
		double r = getDevicePixelRatio(win);
		if(r < 0.01) r = 1.0;
		windowDPI = (float)r;
		windowWidth = canvas.getWidth();
		windowHeight = canvas.getHeight();
		if(windowWidth < 1) windowWidth = 1;
		if(windowHeight < 1) windowHeight = 1;
		lastWasResizedWindowWidth = -2;
		lastWasResizedWindowHeight = -2;
		lastWasResizedWindowDPI = -2.0f;
		lastWasResizedVisualViewportW = -2;
		lastWasResizedVisualViewportH = -2;

		PlatformRuntime.logger.info("Loading keyboard layout data");

		LegacyKeycodeTranslator keycodeTranslator = new LegacyKeycodeTranslator();
		if(checkKeyboardLayoutSupported()) {
			try {
				iterateKeyboardLayout(keycodeTranslator::addBrowserLayoutMapping);
			}catch(Throwable t) {
				PlatformRuntime.logger.error("Caught exception querying keyboard layout from browser, using the default layout instead");
				PlatformRuntime.logger.error(t);
			}
			int cnt = keycodeTranslator.getRemappedKeyCount();
			if(cnt > 0) {
				PlatformRuntime.logger.info("KeyboardLayoutMap remapped {} keys from their default codes", cnt);
			}
		}
		keyCodeTranslatorMap = keycodeTranslator.buildLayoutTable();

		parent.addEventListener("contextmenu", contextmenu = new EventListener<MouseEvent>() {
			@Override
			public void handleEvent(MouseEvent evt) {
				evt.preventDefault();
				evt.stopPropagation();
			}
		});
		canvas.addEventListener("mousedown", mousedown = new EventListener<MouseEvent>() {
			@Override
			public void handleEvent(MouseEvent evt) {
				evt.preventDefault();
				evt.stopPropagation();
				handleWindowFocus();
				if(tryGrabCursorHook()) return;
				int b = evt.getButton();
				b = b == 1 ? 2 : (b == 2 ? 1 : b);
				if(b >= 0 && b < buttonStates.length) buttonStates[b] = true;
				int sourceWidth = Math.max(1, canvas.getWidth());
				int sourceHeight = Math.max(1, canvas.getHeight());
				int eventX = (int)getCanvasMouseX(evt, canvas, sourceWidth);
				int eventY = sourceHeight - (int)getCanvasMouseY(evt, canvas, sourceHeight) - 1;
				int hostMods = glfwMods(evt.getShiftKey(), evt.getCtrlKey(), evt.getAltKey(), evt.getMetaKey());
				queueMouseEvent(new VMouseEvent(eventX, eventY, b, 0.0f, EVENT_MOUSE_DOWN, hostMods, 0.0f,
						sourceWidth, sourceHeight));
			}
		});
		canvas.addEventListener("mouseup", mouseup = new EventListener<MouseEvent>() {
			@Override
			public void handleEvent(MouseEvent evt) {
				evt.preventDefault();
				evt.stopPropagation();
				int b = evt.getButton();
				b = b == 1 ? 2 : (b == 2 ? 1 : b);
				if(b >= 0 && b < buttonStates.length) buttonStates[b] = false;
				int sourceWidth = Math.max(1, canvas.getWidth());
				int sourceHeight = Math.max(1, canvas.getHeight());
				int eventX = (int)getCanvasMouseX(evt, canvas, sourceWidth);
				int eventY = sourceHeight - (int)getCanvasMouseY(evt, canvas, sourceHeight) - 1;
				int hostMods = glfwMods(evt.getShiftKey(), evt.getCtrlKey(), evt.getAltKey(), evt.getMetaKey());
				queueMouseEvent(new VMouseEvent(eventX, eventY, b, 0.0f, EVENT_MOUSE_UP, hostMods, 0.0f,
						sourceWidth, sourceHeight));
			}
		});
		canvas.addEventListener("mousemove", mousemove = new EventListener<MouseEvent>() {
			@Override
			public void handleEvent(MouseEvent evt) {
				evt.preventDefault();
				evt.stopPropagation();
				int sourceWidth = Math.max(1, canvas.getWidth());
				int sourceHeight = Math.max(1, canvas.getHeight());
				int eventX = (int)getCanvasMouseX(evt, canvas, sourceWidth);
				int eventY = sourceHeight - (int)getCanvasMouseY(evt, canvas, sourceHeight) - 1;
				int nextMouseX = scaleCoordinate(eventX, sourceWidth, windowWidth);
				int eventTopY = sourceHeight - eventY - 1;
				int nextMouseY = windowHeight - scaleCoordinate(eventTopY, sourceHeight, windowHeight) - 1;
				if(pointerLockFlag) {
					if(remoteDesktopMouseMode) {
						// Remote desktop clients frequently synthesize broken relative movement
						// while Pointer Lock is active. Use the stable absolute cursor instead.
						// Alt temporarily suspends look so the cursor can be recentered after it
						// reaches an edge of the browser window.
						if(!evt.getAltKey()) {
							int dx = nextMouseX - mouseX;
							int dy = nextMouseY - mouseY;
							int rejectX = Math.max(48, windowWidth / 3);
							int rejectY = Math.max(48, windowHeight / 3);
							if(Math.abs(dx) <= rejectX && Math.abs(dy) <= rejectY) {
								mouseDX += dx;
								mouseDY += dy;
							}
						}
						canvas.getStyle().setProperty("cursor", evt.getAltKey() ? "default" : "none");
					}else {
						mouseDX += evt.getMovementX();
						mouseDY += -evt.getMovementY();
					}
				}
				mouseX = nextMouseX;
				mouseY = nextMouseY;
				if(!pointerLockFlag) {
					queueMouseEvent(new VMouseEvent(eventX, eventY, -1, 0.0f, EVENT_MOUSE_MOVE, 0, 0.0f,
							sourceWidth, sourceHeight));
				}
			}
		});
		canvas.addEventListener("mouseenter", mouseenter = new EventListener<MouseEvent>() {
			@Override
			public void handleEvent(MouseEvent evt) {
				isMouseOverWindow = true;
			}
		});
		canvas.addEventListener("mouseleave", mouseleave = new EventListener<MouseEvent>() {
			@Override
			public void handleEvent(MouseEvent evt) {
				isMouseOverWindow = false;
			}
		});
		canvas.addEventListener("wheel", wheel = new EventListener<WheelEvent>() {
			@Override
			public void handleEvent(WheelEvent evt) {
				evt.preventDefault();
				evt.stopPropagation();
				// DOM wheel values are pixels/lines/pages, while GLFW gives Minecraft
				// logical wheel steps. Passing Chrome's common 100-pixel notch through as
				// 100 steps made one movement fling a menu to its end. Preserve fractional
				// trackpad motion, normalize a conventional mouse notch to about one step,
				// and cap pathological synthetic/device bursts. Browser signs are inverted
				// from GLFW (down = +deltaY), hence the leading minus.
				float delta = WheelDeltaNormalizer.normalize(-evt.getDeltaY(), evt.getDeltaMode(), getLegacyWheelDelta(evt, false));
				float deltaX = WheelDeltaNormalizer.normalize(-evt.getDeltaX(), evt.getDeltaMode(), getLegacyWheelDelta(evt, true));
				mouseDWheel += delta;
				int sourceWidth = Math.max(1, canvas.getWidth());
				int sourceHeight = Math.max(1, canvas.getHeight());
				int eventX = (int)getCanvasMouseX(evt, canvas, sourceWidth);
				int eventY = sourceHeight - (int)getCanvasMouseY(evt, canvas, sourceHeight) - 1;
				queueMouseEvent(new VMouseEvent(eventX, eventY, -1, delta, EVENT_MOUSE_WHEEL, 0, deltaX,
						sourceWidth, sourceHeight));
			}
		});
		canvas.addEventListener("touchstart", touchstart = new EventListener<TouchEvent>() {
			@Override
			public void handleEvent(TouchEvent evt) {
				handleTouchEvent(evt, EnumTouchEvent.TOUCHSTART, true, false);
			}
		});
		canvas.addEventListener("touchmove", touchmove = new EventListener<TouchEvent>() {
			@Override
			public void handleEvent(TouchEvent evt) {
				handleTouchEvent(evt, EnumTouchEvent.TOUCHMOVE, false, false);
			}
		});
		canvas.addEventListener("touchend", touchend = new EventListener<TouchEvent>() {
			@Override
			public void handleEvent(TouchEvent evt) {
				handleTouchEvent(evt, EnumTouchEvent.TOUCHEND, false, true);
			}
		});
		canvas.addEventListener("touchcancel", touchcancel = new EventListener<TouchEvent>() {
			@Override
			public void handleEvent(TouchEvent evt) {
				handleTouchEvent(evt, EnumTouchEvent.TOUCHEND, false, true);
			}
		});
		win.addEventListener("keydown", keydown = new EventListener<KeyboardEvent>() {
			@Override
			public void handleEvent(KeyboardEvent evt) {
				// Let printable keys reach the hidden editable element. beforeinput is
				// the single committed-text stream for both Latin and IME input.
				if(touchKeyboardField != null && touchKeyboardField == evt.getTarget()
						&& isTextProducingKeyboardEvent(evt)) {
					return;
				}
				evt.preventDefault();
				evt.stopPropagation();
				if(isDevToolsCombo(evt)) return; // Ctrl+Shift+I/J/C + F12: let the browser open DevTools, don't consume as a game key
				if(!enableRepeatEvents && evt.isRepeat()) return;
				LegacyKeycodeTranslator.LegacyKeycode keyCode = null;
				if(keyCodeTranslatorMap != null && hasCodeVar(evt)) {
					keyCode = keyCodeTranslatorMap.get(evt.getCode());
				}
				int w;
				int loc;
				if(keyCode != null) {
					w = keyCode.keyCode;
					loc = keyCode.location;
				}else {
					w = getWhich(evt);
					loc = getLocationSafe(evt);
				}
				int physicalEag = KeyboardConstants.getEaglerKeyFromBrowser(w, loc);
				if(physicalEag == KeyboardConstants.KEY_F11) {
					// Fullscreen requires the transient activation of this native keydown.
					// Deferring through the game event queue lets Chrome reject the request.
					if(!evt.isRepeat()) {
						toggleFullscreen();
					}
					return;
				}
				if(physicalEag == KeyboardConstants.KEY_ESCAPE && !evt.isRepeat()
						&& trustedEscapeHandler != null) {
					// Closing an in-game screen requests Pointer Lock again. Dispatch
					// Escape synchronously while this native keydown still carries
					// transient user activation, otherwise Chrome rejects the queued
					// request and leaves the cursor visible over the game.
					keyStates[physicalEag] = true;
					trustedEscapeHandler.handleEscape(glfwMods(evt.isShiftKey(), evt.isCtrlKey(),
							evt.isAltKey(), evt.isMetaKey()));
					return;
				}
				int eag = physicalEag;
				boolean functionMapped = false;
				if(physicalEag > 0 && physicalEag < functionKeyLatches.length) {
					int latched = functionKeyLatches[physicalEag];
					if(latched != 0) {
						eag = latched;
						functionMapped = true;
					}else if(!evt.isRepeat() && isFunctionKeyModifierDown()) {
						int mapped = getFunctionKeyForNumber(physicalEag);
						if(mapped != 0) {
							functionKeyLatches[physicalEag] = mapped;
							eag = mapped;
							functionMapped = true;
						}
					}
				}
				if(eag != 0) {
					keyStates[eag] = true;
				}
				String s = getCharOrNull(evt);
				int l = s.length();
				char c;
				// GLFW's character callback does not emit text for ordinary
				// Ctrl/Alt/Meta shortcuts. Browser keydown exposes evt.key even
				// for those combinations, so forwarding it made Ctrl+A both
				// select and type "a" and could re-enter edit-box responders.
				boolean shortcutModifier = (evt.isCtrlKey() || evt.isAltKey() || evt.isMetaKey())
						&& !isAltGraph(evt);
				if(functionMapped || shortcutModifier) {
					c = '\0';
				}else if(l == 1) {
					c = s.charAt(0);
				}else if(l == 0) {
					c = keyToAsciiLegacy(w, evt.isShiftKey());
				}else if(s.equals("Unidentified")) {
					return;
				}else {
					c = '\0';
				}
				int hostKey = KeyboardConstants.getGLFWKeyFromEagler(eag);
				int hostMods = glfwMods(evt.isShiftKey(), evt.isCtrlKey(), evt.isAltKey(), evt.isMetaKey());
				int hostScancode = 0; // browser has no GLFW hardware scancode; see class javadoc
				int type = evt.isRepeat() ? EVENT_KEY_REPEAT : EVENT_KEY_DOWN;
				synchronized(keyEvents) {
					keyEvents.add(new VKeyEvent(eag, c, type, hostKey, hostScancode, hostMods));
					if(keyEvents.size() > MAX_KEY_EVENTS) {
						keyEvents.remove(0);
					}
				}
			}
		});
		win.addEventListener("keyup", keyup = new EventListener<KeyboardEvent>() {
			@Override
			public void handleEvent(KeyboardEvent evt) {
				evt.preventDefault();
				evt.stopPropagation();
				LegacyKeycodeTranslator.LegacyKeycode keyCode = null;
				if(keyCodeTranslatorMap != null && hasCodeVar(evt)) {
					keyCode = keyCodeTranslatorMap.get(evt.getCode());
				}
				int w;
				int loc;
				if(keyCode != null) {
					w = keyCode.keyCode;
					loc = keyCode.location;
				}else {
					w = getWhich(evt);
					loc = getLocationSafe(evt);
				}
				int physicalEag = KeyboardConstants.getEaglerKeyFromBrowser(w, loc);
				if(physicalEag == KeyboardConstants.KEY_F11) {
					return;
				}
				int eag = physicalEag;
				boolean functionMapped = false;
				if(physicalEag > 0 && physicalEag < functionKeyLatches.length) {
					int latched = functionKeyLatches[physicalEag];
					if(latched != 0) {
						functionKeyLatches[physicalEag] = 0;
						eag = latched;
						functionMapped = true;
					}
				}
				if(physicalEag != 0) {
					keyStates[physicalEag] = false;
				}
				if(eag != 0) {
					keyStates[eag] = false;
				}
				String s = getCharOrNull(evt);
				int l = s.length();
				char c;
				if(functionMapped) {
					c = '\0';
				}else if(l == 1) {
					c = s.charAt(0);
				}else if(l == 0) {
					c = keyToAsciiLegacy(w, evt.isShiftKey());
				}else if(s.equals("Unidentified")) {
					return;
				}else {
					c = '\0';
				}
				int hostKey = KeyboardConstants.getGLFWKeyFromEagler(eag);
				int hostMods = glfwMods(evt.isShiftKey(), evt.isCtrlKey(), evt.isAltKey(), evt.isMetaKey());
				synchronized(keyEvents) {
					keyEvents.add(new VKeyEvent(eag, c, EVENT_KEY_UP, hostKey, 0, hostMods));
					if(keyEvents.size() > MAX_KEY_EVENTS) {
						keyEvents.remove(0);
					}
				}
			}
		});
		win.addEventListener("blur", blur = new EventListener<Event>() {
			@Override
			public void handleEvent(Event evt) {
				isWindowFocused = false;
				for(int i = 0; i < buttonStates.length; ++i) {
					buttonStates[i] = false;
				}
				for(int i = 0; i < keyStates.length; ++i) {
					keyStates[i] = false;
					functionKeyLatches[i] = 0;
				}
			}
		});
		win.addEventListener("focus", focus = new EventListener<Event>() {
			@Override
			public void handleEvent(Event evt) {
				isWindowFocused = true;
			}
		});

		try {
			pointerLockSupported = getSupportedPointerLock(win.getDocument());
		}catch(Throwable t) {
			pointerLockSupported = POINTER_LOCK_NONE;
		}
		if(pointerLockSupported != POINTER_LOCK_NONE) {
			win.getDocument().addEventListener(pointerLockSupported == POINTER_LOCK_MOZ ? "mozpointerlockchange" : "pointerlockchange", pointerlock = new EventListener<Event>() {
				@Override
				public void handleEvent(Event evt) {
					reconcilePointerLockState();
					Window.setTimeout(new TimerHandler() {
						@Override
						public void onTimer() {
							reconcilePointerLockState();
						}
					}, 60);
					mouseDX = 0.0D;
					mouseDY = 0.0D;
				}
			});
			win.getDocument().addEventListener(pointerLockSupported == POINTER_LOCK_MOZ ? "mozpointerlockerror" : "pointerlockerror", pointerlockerr = new EventListener<Event>() {
				@Override
				public void handleEvent(Event evt) {
					reconcilePointerLockState();
				}
			});
			if(pointerLockSupported == POINTER_LOCK_MOZ) {
				PlatformRuntime.logger.info("Using moz- vendor prefix for pointer lock");
			}
		}else {
			PlatformRuntime.logger.error("Pointer lock is not supported on this browser");
		}

		isLikelyMobileBrowser = detectTouchClient();

		try {
			fullscreenSupported = getSupportedFullScreen(win.getDocument());
		}catch(Throwable t) {
			fullscreenSupported = FULLSCREEN_NONE;
		}
		if(fullscreenSupported != FULLSCREEN_NONE) {
			fullscreenQuery = fullscreenMediaQuery();
			if(fullscreenSupported == FULLSCREEN_CORE && (keyboardLockSupported = checkKeyboardLockSupported())) {
				TeaVMUtils.addEventListener(fullscreenQuery, "change", fullscreen = new EventListener<Event>() {
					@Override
					public void handleEvent(Event evt) {
						if (!mediaQueryMatches(evt)) {
							unlockKeys();
							lockKeys = false;
						}
					}
				});
			}
			if(fullscreenSupported == FULLSCREEN_WEBKIT) {
				PlatformRuntime.logger.info("Using webkit- vendor prefix for fullscreen");
			}else if(fullscreenSupported == FULLSCREEN_MOZ) {
				PlatformRuntime.logger.info("Using moz- vendor prefix for fullscreen");
			}
		}else {
			PlatformRuntime.logger.error("Fullscreen is not supported on this browser");
		}

		vsyncTimeout = -1;
		vsyncSupport = false;
		try {
			asyncRequestAnimationFrame();
			vsyncSupport = true;
		}catch(Throwable t) {
			PlatformRuntime.logger.error("VSync is not supported on this browser!");
		}
	}

	private static int glfwMods(boolean shift, boolean ctrl, boolean alt, boolean meta) {
		int mods = 0;
		if(shift) mods |= GLFW_MOD_SHIFT;
		if(ctrl) mods |= GLFW_MOD_CONTROL;
		if(alt) mods |= GLFW_MOD_ALT;
		if(meta) mods |= GLFW_MOD_SUPER;
		return mods;
	}

	private static int getFunctionKeyForNumber(int key) {
		if(key >= KeyboardConstants.KEY_1 && key <= KeyboardConstants.KEY_9) {
			return key - KeyboardConstants.KEY_1 + KeyboardConstants.KEY_F1;
		}
		return key == KeyboardConstants.KEY_0 ? KeyboardConstants.KEY_F10 : 0;
	}

	private static boolean isFunctionKeyModifierDown() {
		return functionKeyModifier > 0 && functionKeyModifier < keyStates.length && keyStates[functionKeyModifier];
	}

	private static void queueMouseEvent(VMouseEvent event) {
		synchronized(mouseEvents) {
			int last = mouseEvents.size() - 1;
			if(event.type == EVENT_MOUSE_MOVE && last >= 0 && mouseEvents.get(last).type == EVENT_MOUSE_MOVE) {
				mouseEvents.set(last, event);
				return;
			}
			mouseEvents.add(event);
			if(mouseEvents.size() > MAX_MOUSE_EVENTS) {
				for(int i = 0; i < mouseEvents.size(); ++i) {
					if(mouseEvents.get(i).type == EVENT_MOUSE_MOVE) {
						mouseEvents.remove(i);
						break;
					}
				}
			}
		}
	}

	private static void handleTouchEvent(TouchEvent evt, EnumTouchEvent type, boolean createIds, boolean releaseIds) {
		evt.preventDefault();
		evt.stopPropagation();
		handleWindowFocus();
		isLikelyMobileBrowser = true;
		List<VTouchPoint> changed = convertTouches(evt.getChangedTouches(), createIds);
		currentTouchState = convertTouches(evt.getTargetTouches(), false);
		if(type == EnumTouchEvent.TOUCHSTART && touchKeyboardShortcutW > 0 && touchKeyboardShortcutH > 0) {
			for(int i = 0; i < changed.size(); ++i) {
				VTouchPoint point = changed.get(i);
				if(point.x >= touchKeyboardShortcutX && point.x < touchKeyboardShortcutX + touchKeyboardShortcutW
						&& point.y >= touchKeyboardShortcutY && point.y < touchKeyboardShortcutY + touchKeyboardShortcutH) {
					// This must happen inside the browser's native touch event. Deferring focus
					// until the queued game event reaches TouchControls26 loses Android's
					// transient user activation and Chrome refuses to show the soft keyboard.
					openTouchKeyboard();
					break;
				}
			}
		}
		if(type == EnumTouchEvent.TOUCHEND && touchFullscreenShortcutW > 0 && touchFullscreenShortcutH > 0) {
			for(int i = 0; i < changed.size(); ++i) {
				VTouchPoint point = changed.get(i);
				if(point.x >= touchFullscreenShortcutX
						&& point.x < touchFullscreenShortcutX + touchFullscreenShortcutW
						&& point.y >= touchFullscreenShortcutY
						&& point.y < touchFullscreenShortcutY + touchFullscreenShortcutH) {
					// Element fullscreen consumes transient user activation. Running this in
					// the queued game tick is too late on Android Chrome.
					toggleFullscreen();
					break;
				}
			}
		}
		List<VTouchPoint> eventPoints = type == EnumTouchEvent.TOUCHMOVE ? currentTouchState : changed;
		touchEvents.add(new VTouchEvent(type, eventPoints));
		while(touchEvents.size() > 128) {
			touchEvents.remove(0);
		}
		if(releaseIds) {
			for(int i = 0; i < evt.getChangedTouches().getLength(); ++i) {
				touchIdentifiers.remove(Integer.valueOf(evt.getChangedTouches().get(i).getIdentifier()));
			}
		}
	}

	private static void installTouchKeyboardOpenZone() {
		if(touchKeyboardOpenZone != null || parent == null || win == null) {
			return;
		}
		touchKeyboardOpenZone = win.getDocument().createElement("div");
		CSSStyleDeclaration style = touchKeyboardOpenZone.getStyle();
		style.setProperty("display", "none");
		style.setProperty("position", "absolute");
		style.setProperty("background-color", "transparent");
		style.setProperty("top", "0px");
		style.setProperty("left", "0px");
		style.setProperty("width", "0px");
		style.setProperty("height", "0px");
		style.setProperty("z-index", "100");
		style.setProperty("touch-action", "none");
		style.setProperty("-webkit-touch-callout", "none");
		style.setProperty("-webkit-tap-highlight-color", "rgba(255,255,255,0)");
		touchKeyboardOpenZone.addEventListener("touchstart",
				touchKeyboardOpenZoneTouchStart = new EventListener<TouchEvent>() {
			@Override
			public void handleEvent(TouchEvent evt) {
				evt.preventDefault();
				evt.stopPropagation();
				// Android's soft keyboard must be focused in the trusted touchstart.
				openTouchKeyboard();
			}
		});
		touchKeyboardOpenZone.addEventListener("touchmove",
				touchKeyboardOpenZoneTouchMove = new EventListener<TouchEvent>() {
			@Override
			public void handleEvent(TouchEvent evt) {
				evt.preventDefault();
				evt.stopPropagation();
			}
		});
		touchKeyboardOpenZone.addEventListener("touchend",
				touchKeyboardOpenZoneTouchEnd = new EventListener<TouchEvent>() {
			@Override
			public void handleEvent(TouchEvent evt) {
				evt.preventDefault();
				evt.stopPropagation();
			}
		});
		parent.appendChild(touchKeyboardOpenZone);
	}

	private static void openTouchKeyboard() {
		if(touchKeyboardField != null) {
			focusTouchKeyboard(touchKeyboardField);
			touchKeyboardField.setValue(" ");
			setSelectionRange(touchKeyboardField, 1, 1);
			return;
		}
		if(parent == null || win == null) {
			return;
		}
		touchKeyboardForm = (HTMLFormElement)win.getDocument().createElement("form");
		touchKeyboardForm.setAttribute("autocomplete", "off");
		CSSStyleDeclaration style = touchKeyboardForm.getStyle();
		style.setProperty("position", "fixed");
		style.setProperty("left", "1px");
		style.setProperty("bottom", "1px");
		style.setProperty("width", "2px");
		style.setProperty("height", "2px");
		style.setProperty("overflow", "hidden");
		style.setProperty("opacity", "0.01");
		style.setProperty("pointer-events", "none");
		style.setProperty("z-index", "2147483647");
		touchKeyboardForm.addEventListener("submit", new EventListener<Event>() {
			@Override
			public void handleEvent(Event evt) {
				evt.preventDefault();
				evt.stopPropagation();
				fireTouchKey(KeyboardConstants.KEY_RETURN, '\n');
				touchCloseDeviceKeyboard();
			}
		});
		touchKeyboardField = (HTMLInputElement)win.getDocument().createElement("input");
		touchKeyboardField.setType("text");
		touchKeyboardField.setAttribute("autocomplete", "off");
		touchKeyboardField.setAttribute("autocorrect", "off");
		touchKeyboardField.setAttribute("autocapitalize", "none");
		touchKeyboardField.setAttribute("spellcheck", "false");
		touchKeyboardField.setAttribute("inputmode", "text");
		touchKeyboardField.setAttribute("enterkeyhint", "send");
		touchKeyboardField.setAttribute("data-eagler-text-input", "true");
		touchKeyboardField.setValue(" ");
		style = touchKeyboardField.getStyle();
		style.setProperty("position", "fixed");
		style.setProperty("left", "0px");
		style.setProperty("bottom", "0px");
		style.setProperty("width", "2px");
		style.setProperty("height", "2px");
		style.setProperty("font-size", "16px");
		style.setProperty("opacity", "0.01");
		touchKeyboardField.addEventListener("beforeinput", new EventListener<InputEvent>() {
			@Override
			public void handleEvent(InputEvent evt) {
				if(touchKeyboardField == null || touchKeyboardField != evt.getTarget()) {
					return;
				}
				String type = evt.getInputType();
				// Interim IME edits belong to the browser. Cancelling or forwarding
				// each insertCompositionText value breaks CJK composition and repeats
				// partially composed text.
				if("insertCompositionText".equals(type) || isInputEventComposing(evt)) {
					touchKeyboardComposing = true;
					return;
				}
				lastTouchBeforeInputMs = getEventTimeStamp(evt);
				evt.preventDefault();
				evt.stopPropagation();
				if("insertParagraph".equals(type) || "insertLineBreak".equals(type)) {
					fireTouchKey(KeyboardConstants.KEY_RETURN, '\n');
					touchCloseDeviceKeyboard();
				} else if(type != null && type.startsWith("delete") && type.indexOf("Forward") >= 0) {
					fireTouchKey(KeyboardConstants.KEY_DELETE, '\0');
				} else if(type != null && type.startsWith("delete")) {
					fireTouchKey(KeyboardConstants.KEY_BACK, '\0');
				} else if("insertFromPaste".equals(type) || "insertFromPasteAsQuotation".equals(type)
						|| "insertFromDrop".equals(type) || "insertFromYank".equals(type)) {
					String pasted = evt.getData();
					if(pasted != null && !pasted.isEmpty()) {
						pastedStrings.add(pasted);
						while(pastedStrings.size() > 64) pastedStrings.remove(0);
					}
				} else if("insertFromComposition".equals(type)) {
					String committed = evt.getData();
					if(committed != null && !committed.isEmpty()) {
						if(!committed.equals(lastTouchCompositionCommit)
								|| Math.abs(lastTouchBeforeInputMs - lastTouchCompositionCommitMs) >= 32.0) {
							fireTouchText(committed);
						}
						lastTouchCompositionCommit = committed;
						lastTouchCompositionCommitMs = lastTouchBeforeInputMs;
						touchKeyboardCompositionCommitted = true;
					}
				} else if("insertText".equals(type) || "insertReplacementText".equals(type)) {
					String committed = evt.getData();
					// Some Chromium builds emit the same final text once as
					// insertFromComposition and again as insertText.
					if(committed != null && committed.equals(lastTouchCompositionCommit)
							&& Math.abs(lastTouchBeforeInputMs - lastTouchCompositionCommitMs) < 32.0) {
						lastTouchCompositionCommit = null;
					} else {
						fireTouchText(committed);
					}
				}
			}
		});
		touchKeyboardField.addEventListener("compositionstart", new EventListener<Event>() {
			@Override
			public void handleEvent(Event evt) {
				touchKeyboardComposing = true;
				touchKeyboardCompositionCommitted = false;
			}
		});
		touchKeyboardField.addEventListener("compositionend", new EventListener<Event>() {
			@Override
			public void handleEvent(Event evt) {
				String committed = getCompositionEventData(evt);
				if(!touchKeyboardCompositionCommitted && committed != null && !committed.isEmpty()) {
					fireTouchText(committed);
					lastTouchCompositionCommit = committed;
					lastTouchCompositionCommitMs = getEventTimeStamp(evt);
				}
				touchKeyboardComposing = false;
				touchKeyboardCompositionCommitted = false;
				if(touchKeyboardField != null) {
					touchKeyboardField.setValue(" ");
					setSelectionRange(touchKeyboardField, 1, 1);
				}
			}
		});
		touchKeyboardField.addEventListener("input", new EventListener<Event>() {
			@Override
			public void handleEvent(Event evt) {
				if(touchKeyboardField == null || touchKeyboardField != evt.getTarget()) {
					return;
				}
				if(touchKeyboardComposing) {
					return;
				}
				// Legacy fallback for browsers which ignore cancelling beforeinput.
				if(Math.abs(getEventTimeStamp(evt) - lastTouchBeforeInputMs) >= 12.0) {
					String value = touchKeyboardField.getValue();
					if(value.isEmpty()) {
						fireTouchKey(KeyboardConstants.KEY_BACK, '\0');
					} else if(value.length() > 1) {
						fireTouchText(value.substring(1));
					}
				}
				touchKeyboardField.setValue(" ");
				setSelectionRange(touchKeyboardField, 1, 1);
			}
		});
		touchKeyboardForm.appendChild(touchKeyboardField);
		parent.appendChild(touchKeyboardForm);
		focusTouchKeyboard(touchKeyboardField);
		setSelectionRange(touchKeyboardField, 1, 1);
	}

	@JSBody(params = { "input" }, script = "try{input.blur();}catch(e){}"
			+ "try{input.focus({preventScroll:true});}catch(e){input.focus();}")
	private static native void focusTouchKeyboard(HTMLInputElement input);

	private static void fireTouchText(String text) {
		if(text == null) return;
		for(int i = 0; i < text.length(); ++i) {
			char c = text.charAt(i);
			int browserKey = asciiUpperToKeyLegacy(Character.toUpperCase(c));
			fireTouchKey(KeyboardConstants.getEaglerKeyFromBrowser(browserKey, 0), c);
		}
	}

	private static void fireTouchKey(int eagKey, char character) {
		keyboardFireEvent(EnumFireKeyboardEvent.KEY_DOWN, eagKey, character);
		keyboardFireEvent(EnumFireKeyboardEvent.KEY_UP, eagKey, character);
	}

	@JSBody(params = { "evt" }, script = "return (typeof evt.timeStamp === 'number') ? evt.timeStamp : 0;")
	private static native double getEventTimeStamp(Event evt);

	@JSBody(params = { "evt" }, script = "return !!evt.isComposing;")
	private static native boolean isInputEventComposing(InputEvent evt);

	@JSBody(params = { "evt" }, script = "return (typeof evt.data === 'string') ? evt.data : '';")
	private static native String getCompositionEventData(Event evt);

	@JSBody(params = { "evt" }, script = "if(evt.isComposing || evt.keyCode === 229) return true;"
			+ "if(evt.ctrlKey || evt.metaKey || (evt.altKey && !(evt.getModifierState && evt.getModifierState('AltGraph')))) return false;"
			+ "return typeof evt.key === 'string' && evt.key.length === 1;")
	private static native boolean isTextProducingKeyboardEvent(KeyboardEvent evt);

	@JSBody(params = { "input", "start", "end" }, script = "input.setSelectionRange(start,end);")
	private static native void setSelectionRange(HTMLInputElement input, int start, int end);

	private static List<VTouchPoint> convertTouches(org.teavm.jso.core.JSArrayReader<Touch> touches, boolean createIds) {
		int count = touches.getLength();
		List<VTouchPoint> result = new ArrayList<>(count);
		for(int i = 0; i < count; ++i) {
			Touch touch = touches.get(i);
			int identifier = touch.getIdentifier();
			Integer mapped = touchIdentifiers.get(Integer.valueOf(identifier));
			if(mapped == null && createIds) {
				mapped = Integer.valueOf(nextTouchUid++);
				touchIdentifiers.put(Integer.valueOf(identifier), mapped);
			}
			if(mapped == null) {
				continue;
			}
			double x = getCanvasTouchX(touch, canvas);
			double y = getCanvasTouchY(touch, canvas);
			double radiusScale = getCanvasTouchScale(canvas);
			float radius = (float)(Math.max(1.0, (touch.getRadiusX() + touch.getRadiusY()) * 0.5) * radiusScale);
			result.add(new VTouchPoint((int)x, windowHeight - (int)y - 1, radius,
					(float)touch.getForce(), mapped.intValue()));
		}
		result.sort(Comparator.comparingInt(point -> point.uid));
		return result;
	}

	private static void handleWindowFocus() {
		if(!isWindowFocused) {
			PlatformRuntime.logger.warn("Detected mouse input while the window was not focused, setting the window focused so the client doesn't pause");
			isWindowFocused = true;
		}
		isMouseOverWindow = true;
	}

	@JSFunctor
	private static interface KeyboardLayoutIterator extends JSObject {
		void call(String key, String val);
	}

	@JSFunctor
	private static interface KeyboardLayoutDone extends JSObject {
		void call();
	}

	@JSBody(params = { "cb", "cbDone" }, script = "return navigator.keyboard.getLayoutMap()"
			+ ".then(function(layoutMap) { if(layoutMap && layoutMap.forEach) layoutMap.forEach(cb); cbDone(); })"
			+ ".catch(function() { cbDone(); });")
	private static native void iterateKeyboardLayout0(KeyboardLayoutIterator cb, KeyboardLayoutDone cbDone);

	@Async
	private static native void iterateKeyboardLayout(KeyboardLayoutIterator cb);

	private static void iterateKeyboardLayout(KeyboardLayoutIterator cb, final AsyncCallback<Void> complete) {
		iterateKeyboardLayout0(cb, () -> complete.complete(null));
	}

	@JSBody(params = { }, script = "return !!(navigator.keyboard && navigator.keyboard.getLayoutMap);")
	private static native boolean checkKeyboardLayoutSupported();

	@JSBody(params = { "doc" }, script = "return (typeof doc.exitPointerLock === \"function\") ? 1"
			+ ": ((typeof doc.mozExitPointerLock === \"function\") ? 2 : -1);")
	private static native int getSupportedPointerLock(HTMLDocument doc);

	@JSBody(params = { "doc" }, script = "return (typeof doc.exitFullscreen === \"function\") ? 1"
			+ ": ((typeof doc.webkitExitFullscreen === \"function\") ? 2"
			+ ": ((typeof doc.mozExitFullscreen === \"function\") ? 3 : -1));")
	private static native int getSupportedFullScreen(HTMLDocument doc);

	@JSBody(params = { "evt" }, script = "return (typeof evt.code === \"string\");")
	private static native boolean hasCodeVar(KeyboardEvent evt);

	@JSBody(params = { "win" }, script = "return (typeof win.devicePixelRatio === \"number\") ? win.devicePixelRatio : 1.0;")
	private static native double getDevicePixelRatio(Window win);

	// Pointer coordinates must use the canvas's real CSS-to-backing-store transform.
	// Multiplying offsetX/Y by devicePixelRatio is not equivalent on Windows when
	// Chromium zoom, per-monitor DPI, or a mid-session DPI transition changes the
	// relationship between CSS pixels and the canvas backing store.
	@JSBody(params = { "m", "c", "backingWidth" }, script = "var r=c.getBoundingClientRect();"
			+ "if(r && r.width > 0 && backingWidth > 0 && typeof m.clientX === \"number\")"
			+ " return (m.clientX-r.left)*backingWidth/r.width;"
			+ "var cssWidth=(r&&r.width>0)?r.width:(c.clientWidth||backingWidth);"
			+ "return (typeof m.offsetX === \"number\"&&cssWidth>0) ? m.offsetX*backingWidth/cssWidth : 0;")
	private static native double getCanvasMouseX(MouseEvent m, HTMLCanvasElement c, int backingWidth);

	@JSBody(params = { "m", "c", "backingHeight" }, script = "var r=c.getBoundingClientRect();"
			+ "if(r && r.height > 0 && backingHeight > 0 && typeof m.clientY === \"number\")"
			+ " return (m.clientY-r.top)*backingHeight/r.height;"
			+ "var cssHeight=(r&&r.height>0)?r.height:(c.clientHeight||backingHeight);"
			+ "return (typeof m.offsetY === \"number\"&&cssHeight>0) ? m.offsetY*backingHeight/cssHeight : 0;")
	private static native double getCanvasMouseY(MouseEvent m, HTMLCanvasElement c, int backingHeight);

	@JSBody(params = { "evt", "horizontal" }, script = "var v=horizontal?evt.wheelDeltaX:"
			+ "((typeof evt.wheelDeltaY==='number')?evt.wheelDeltaY:evt.wheelDelta);"
			+ "return (typeof v==='number'&&isFinite(v))?v:0;")
	private static native double getLegacyWheelDelta(WheelEvent evt, boolean horizontal);

	@JSBody(params = { "t", "c" }, script = "var r=c.getBoundingClientRect();"
			+ "if(r && r.width > 0 && c.width > 0) return (t.clientX-r.left)*c.width/r.width;"
			+ "return t.clientX||0;")
	private static native double getCanvasTouchX(Touch t, HTMLCanvasElement c);

	@JSBody(params = { "t", "c" }, script = "var r=c.getBoundingClientRect();"
			+ "if(r && r.height > 0 && c.height > 0) return (t.clientY-r.top)*c.height/r.height;"
			+ "return t.clientY||0;")
	private static native double getCanvasTouchY(Touch t, HTMLCanvasElement c);

	@JSBody(params = { "c" }, script = "var r=c.getBoundingClientRect();"
			+ "if(r && r.width > 0 && r.height > 0) return ((c.width/r.width)+(c.height/r.height))*0.5;"
			+ "return 1;")
	private static native double getCanvasTouchScale(HTMLCanvasElement c);

	@JSBody(params = { }, script = "var s=location.search||'';"
			+ "if(/(?:[?&])touch=1(?:&|$)/.test(s)) return true;"
			+ "if(/(?:[?&])touch=0(?:&|$)/.test(s)) return false;"
			+ "return !!((navigator.maxTouchPoints||0)>0 && (!window.matchMedia || matchMedia('(pointer:coarse)').matches));")
	private static native boolean detectTouchClient();

	@JSBody(params = { "e" }, script = "return (typeof e.which === \"number\") ? e.which : ((typeof e.keyCode === \"number\") ? e.keyCode : 0);")
	private static native int getWhich(KeyboardEvent e);

	// Browser DevTools shortcuts (F12, Ctrl+Shift+I/J/C): the game must NOT swallow these
	// so the user can open DevTools while the canvas has focus.
	private static boolean isDevToolsCombo(KeyboardEvent evt) {
		int w = getWhich(evt);
		if(w == 123) return true; // F12
		return evt.isCtrlKey() && evt.isShiftKey() && (w == 73 || w == 74 || w == 67); // I / J / C
	}

	@JSBody(params = { "e" }, script = "return (typeof e.location === \"number\") ? e.location : 0;")
	private static native int getLocationSafe(KeyboardEvent e);

	@JSBody(params = { "evt" }, script = "return (typeof evt.key === \"string\") ? evt.key : \"\";")
	private static native String getCharOrNull(KeyboardEvent evt);

	@JSBody(params = { "evt" }, script = "return typeof evt.getModifierState === \"function\" && evt.getModifierState(\"AltGraph\");")
	private static native boolean isAltGraph(KeyboardEvent evt);

	// ==== 26.2 hosted-mode host (GLFW) event getters ====

	public static int keyboardGetEventHostKey() {
		return currentEventK == null ? 0 : currentEventK.hostKey;
	}

	public static int keyboardGetEventHostScancode() {
		return currentEventK == null ? 0 : currentEventK.hostScancode;
	}

	public static int keyboardGetEventHostMods() {
		return currentEventK == null ? 0 : currentEventK.hostMods;
	}

	public static int mouseGetEventHostMods() {
		return currentEvent == null ? 0 : currentEvent.hostMods;
	}

	public static float mouseGetEventDWheelF() {
		return (currentEvent != null && currentEvent.type == EVENT_MOUSE_WHEEL) ? currentEvent.wheel : 0.0f;
	}

	public static float mouseGetEventHostScrollX() {
		return currentEvent == null ? 0.0f : currentEvent.hostScrollX;
	}

	public static int getWindowWidth() {
		return windowWidth;
	}

	public static int getWindowHeight() {
		return windowHeight;
	}

	// Visual viewport is reported equal to the window (VisualViewport not copied this phase)
	public static int getVisualViewportX() {
		return 0;
	}

	public static int getVisualViewportY() {
		return 0;
	}

	public static int getVisualViewportW() {
		return windowWidth;
	}

	public static int getVisualViewportH() {
		return windowHeight;
	}

	public static boolean getWindowFocused() {
		// Pointer lock may remain observable briefly after a tab/window blur. It must
		// not override the browser's focus/visibility signals or local singleplayer
		// keeps ticking in the background instead of pausing.
		return isWindowFocused && getVisibilityState(win.getDocument());
	}

	/** True only while the browser document is visible; focus is tracked separately. */
	public static boolean isPageVisible() {
		return getVisibilityState(win.getDocument());
	}

	public static boolean isCloseRequested() {
		return false;
	}

	public static void setVSync(boolean enable) {
		if(vsync != enable) {
			syncTimer = 0.0;
		}
		vsync = enable;
	}

	@JSBody(params = { "doc" }, script = "return (typeof doc.visibilityState !== \"string\") || (doc.visibilityState === \"visible\");")
	private static native boolean getVisibilityState(JSObject doc);

	public static void update() {
		update(0);
	}

	private static double syncTimer = 0.0;
	private static int lastFpsLimit = 0;

	public static void update(int fpsLimit) {
		int previousWidth = windowWidth;
		int previousHeight = windowHeight;
		double r = getDevicePixelRatio(win);
		if(r < 0.01) r = 1.0;
		windowDPI = (float)r;
		int w = parent.getClientWidth();
		int h = parent.getClientHeight();
		int w2 = windowWidth = (int)(w * r);
		int h2 = windowHeight = (int)(h * r);
		if(windowWidth < 1) windowWidth = w2 = 1;
		if(windowHeight < 1) windowHeight = h2 = 1;
		if(canvas.getWidth() != w2) {
			canvas.setWidth(w2);
		}
		if(canvas.getHeight() != h2) {
			canvas.setHeight(h2);
		}
		if(previousWidth > 0 && previousHeight > 0
				&& (previousWidth != windowWidth || previousHeight != windowHeight)) {
			mouseX = scaleCoordinate(mouseX, previousWidth, windowWidth);
			int previousTopY = previousHeight - mouseY - 1;
			int currentTopY = scaleCoordinate(previousTopY, previousHeight, windowHeight);
			mouseY = windowHeight - currentTopY - 1;
			mouseDX = 0.0D;
			mouseDY = 0.0D;
		}
		// TODO(3.3): WebGLBackBuffer.flipBuffer / PlatformScreenRecord.captureFrameHook /
		//            PlatformOpenGL.ctx.finish (classes not copied yet)
		if(getVisibilityState(win.getDocument())) {
			boolean yieldedForVsync = vsyncSupport && vsync;
			if(yieldedForVsync) {
				asyncRequestAnimationFrame();
			}
			paceFrameLimit(fpsLimit, yieldedForVsync);
		}else {
			syncTimer = 0.0;
			PlatformRuntime.sleep(50);
		}
	}

	private static void paceFrameLimit(int fpsLimit, boolean alreadyYielded) {
		if(fpsLimit != lastFpsLimit) {
			lastFpsLimit = fpsLimit;
			syncTimer = 0.0;
		}
		if(!BrowserFramePacing.isLimited(fpsLimit)) {
			syncTimer = 0.0;
			if(!alreadyYielded) {
				PlatformRuntime.swapDelayTeaVM();
			}
			return;
		}

		double frameMillis = BrowserFramePacing.frameMillis(fpsLimit);
		double millis = PlatformRuntime.steadyTimeMillisTeaVM();
		if(syncTimer == 0.0) {
			syncTimer = BrowserFramePacing.initialDeadline(millis, frameMillis);
			if(!alreadyYielded) {
				PlatformRuntime.swapDelayTeaVM();
			}
			return;
		}

		int remaining = (int)(syncTimer - millis);
		if(remaining > 0) {
			if(!PlatformRuntime.useDelayOnSwap && PlatformRuntime.immediateContinueSupport) {
				PlatformRuntime.immediateContinue();
				millis = PlatformRuntime.steadyTimeMillisTeaVM();
				remaining = (int)(syncTimer - millis);
				if(remaining > 0) {
					PlatformRuntime.sleep(remaining);
				}
			}else {
				PlatformRuntime.sleep(remaining);
			}
		}else if(!alreadyYielded) {
			PlatformRuntime.swapDelayTeaVM();
		}
		millis = PlatformRuntime.steadyTimeMillisTeaVM();
		syncTimer = BrowserFramePacing.nextDeadline(syncTimer, millis, frameMillis);
	}

	@Async
	private static native void asyncRequestAnimationFrame();

	private static void asyncRequestAnimationFrame(AsyncCallback<Void> cb) {
		if(vsyncWaiter != null) {
			cb.error(new IllegalStateException("Already waiting for vsync!"));
			return;
		}
		if(vsyncReady) {
			vsyncReady = false;
			armRequestAnimationFrame();
			cb.complete(null);
			return;
		}
		vsyncWaiter = cb;
		armRequestAnimationFrame();
	}

	private static void armRequestAnimationFrame() {
		if(vsyncPrearmed) {
			return;
		}
		vsyncPrearmed = true;
		final boolean[] completed = new boolean[] { false };
		Window.requestAnimationFrame((d) -> {
			if(!completed[0]) {
				completed[0] = true;
				completeRequestAnimationFrame();
			}
		});
		vsyncTimeout = Window.setTimeout(() -> {
			if(!completed[0]) {
				completed[0] = true;
				completeRequestAnimationFrame();
			}
		}, 50);
	}

	private static void completeRequestAnimationFrame() {
		if(vsyncTimeout != -1) {
			Window.clearTimeout(vsyncTimeout);
			vsyncTimeout = -1;
		}
		vsyncPrearmed = false;
		AsyncCallback<Void> waiter = vsyncWaiter;
		vsyncWaiter = null;
		if(waiter != null) {
			// Register before resuming TeaVM. If rendering takes longer than one
			// refresh interval, the browser can resume at the first available vsync
			// instead of waiting an additional interval after present().
			armRequestAnimationFrame();
			waiter.complete(null);
		}else {
			vsyncReady = true;
		}
	}

	public static boolean isVSyncSupported() {
		return vsyncSupport;
	}

	public static boolean wasResized() {
		if (windowWidth != lastWasResizedWindowWidth || windowHeight != lastWasResizedWindowHeight
				|| windowDPI != lastWasResizedWindowDPI) {
			lastWasResizedWindowWidth = windowWidth;
			lastWasResizedWindowHeight = windowHeight;
			lastWasResizedWindowDPI = windowDPI;
			return true;
		}else {
			return false;
		}
	}

	public static boolean wasVisualViewportResized() {
		if (windowWidth != lastWasResizedVisualViewportW || windowHeight != lastWasResizedVisualViewportH) {
			lastWasResizedVisualViewportW = windowWidth;
			lastWasResizedVisualViewportH = windowHeight;
			return true;
		}else {
			return false;
		}
	}

	public static boolean keyboardNext() {
		synchronized(keyEvents) {
			if(unpressCTRL) { //un-press ctrl after copy/paste permission
				keyEvents.clear();
				currentEventK = null;
				keyStates[KeyboardConstants.KEY_LCONTROL] = false;
				keyStates[KeyboardConstants.KEY_RCONTROL] = false;
				keyStates[KeyboardConstants.KEY_RETURN] = false;
				keyStates[KeyboardConstants.KEY_LBRACKET] = false;
				keyStates[KeyboardConstants.KEY_BACKSLASH] = false;
				unpressCTRL = false;
				return false;
			}
			currentEventK = null;
			return !keyEvents.isEmpty() && (currentEventK = keyEvents.remove(0)) != null;
		}
	}

	public static void keyboardFireEvent(EnumFireKeyboardEvent eventType, int eagKey, char keyChar) {
		int hostKey = KeyboardConstants.getGLFWKeyFromEagler(eagKey);
		synchronized(keyEvents) {
			switch(eventType) {
			case KEY_DOWN:
				keyEvents.add(new VKeyEvent(eagKey, keyChar, EVENT_KEY_DOWN, hostKey, 0, 0));
				break;
			case KEY_UP:
				keyEvents.add(new VKeyEvent(eagKey, '\0', EVENT_KEY_UP, hostKey, 0, 0));
				break;
			case KEY_REPEAT:
				keyEvents.add(new VKeyEvent(eagKey, keyChar, EVENT_KEY_REPEAT, hostKey, 0, 0));
				break;
			default:
				throw new UnsupportedOperationException();
			}
			if(keyEvents.size() > MAX_KEY_EVENTS) {
				keyEvents.remove(0);
			}
		}
	}

	public static boolean keyboardGetEventKeyState() {
		return currentEventK == null ? false : (currentEventK.type != EVENT_KEY_UP);
	}

	public static int keyboardGetEventKey() {
		return currentEventK == null ? -1 : currentEventK.eagKey;
	}

	private static char keyToAsciiLegacy(int whichIn, boolean shiftUp) {
		switch(whichIn) {
		case 188: whichIn = 44; break;
		case 109: whichIn = 45; break;
		case 190: whichIn = 46; break;
		case 191: whichIn = 47; break;
		case 192: whichIn = 96; break;
		case 220: whichIn = 92; break;
		case 222: whichIn = 39; break;
		case 221: whichIn = 93; break;
		case 219: whichIn = 91; break;
		case 173: whichIn = 45; break;
		case 187: whichIn = 61; break;
		case 186: whichIn = 59; break;
		case 189: whichIn = 45; break;
		default: break;
		}
		if(shiftUp) {
			switch(whichIn) {
			case 96: return '~';
			case 49: return '!';
			case 50: return '@';
			case 51: return '#';
			case 52: return '$';
			case 53: return '%';
			case 54: return '^';
			case 55: return '&';
			case 56: return '*';
			case 57: return '(';
			case 48: return ')';
			case 45: return '_';
			case 61: return '+';
			case 91: return '{';
			case 93: return '}';
			case 92: return '|';
			case 59: return ':';
			case 39: return '\"';
			case 44: return '<';
			case 46: return '>';
			case 47: return '?';
			default: return (char)whichIn;
			}
		}else {
			if(whichIn >= 65 && whichIn <= 90) {
				return (char)(whichIn + 32);
			}else {
				return (char)whichIn;
			}
		}
	}

	private static int asciiUpperToKeyLegacy(char charIn) {
		switch(charIn) {
		case '\n': return 17;
		case '~': case '`': return 192;
		case '!': return 49;
		case '@': return 50;
		case '#': return 51;
		case '$': return 52;
		case '%': return 53;
		case '^': return 54;
		case '&': return 55;
		case '*': return 56;
		case '(': return 57;
		case ')': return 48;
		case '_': case '-': return 189;
		case '+': return 187;
		case '{': case '[': return 219;
		case '}': case ']': return 221;
		case '|': case '\\': return 220;
		case ':': case ';': return 186;
		case '"': case '\'': return 222;
		case '<': case ',': return 188;
		case '>': case '.': return 190;
		case '?': case '/': return 191;
		default: return (int)charIn;
		}
	}

	public static char keyboardGetEventCharacter() {
		return currentEventK == null ? '\0' : currentEventK.keyChar;
	}

	public static boolean keyboardIsKeyDown(int key) {
		if(unpressCTRL) { //un-press ctrl after copy/paste permission
			keyStates[KeyboardConstants.KEY_RETURN] = false;
			keyStates[KeyboardConstants.KEY_LCONTROL] = false;
			keyStates[KeyboardConstants.KEY_RCONTROL] = false;
			keyStates[KeyboardConstants.KEY_LBRACKET] = false;
			keyStates[KeyboardConstants.KEY_BACKSLASH] = false;
		}
		return key < 0 || key >= keyStates.length ? false : keyStates[key];
	}

	public static boolean keyboardIsRepeatEvent() {
		return currentEventK == null ? false : (currentEventK.type == EVENT_KEY_REPEAT);
	}

	public static void keyboardEnableRepeatEvents(boolean b) {
		enableRepeatEvents = b;
	}

	public static boolean keyboardAreKeysLocked() {
		return lockKeys;
	}

	public static boolean mouseNext() {
		currentEvent = null;
		synchronized(mouseEvents) {
			return !mouseEvents.isEmpty() && (currentEvent = mouseEvents.remove(0)) != null;
		}
	}

	public static void mouseFireMoveEvent(EnumFireMouseEvent eventType, int posX, int posY) {
		if(eventType == EnumFireMouseEvent.MOUSE_MOVE) {
			queueMouseEvent(new VMouseEvent(posX, posY, -1, 0.0f, EVENT_MOUSE_MOVE, 0, 0.0f));
		}else {
			throw new UnsupportedOperationException();
		}
	}

	public static void mouseFireButtonEvent(EnumFireMouseEvent eventType, int posX, int posY, int button) {
		switch(eventType) {
		case MOUSE_DOWN:
			queueMouseEvent(new VMouseEvent(posX, posY, button, 0.0f, EVENT_MOUSE_DOWN, 0, 0.0f));
			break;
		case MOUSE_UP:
			queueMouseEvent(new VMouseEvent(posX, posY, button, 0.0f, EVENT_MOUSE_UP, 0, 0.0f));
			break;
		default:
			throw new UnsupportedOperationException();
		}
	}

	public static void mouseFireWheelEvent(EnumFireMouseEvent eventType, int posX, int posY, float wheel) {
		if(eventType == EnumFireMouseEvent.MOUSE_WHEEL) {
			queueMouseEvent(new VMouseEvent(posX, posY, -1, wheel, EVENT_MOUSE_WHEEL, 0, 0.0f));
		}else {
			throw new UnsupportedOperationException();
		}
	}

	public static boolean mouseGetEventButtonState() {
		return currentEvent == null ? false : (currentEvent.type == EVENT_MOUSE_DOWN);
	}

	public static int mouseGetEventButton() {
		if(currentEvent == null || (currentEvent.type == EVENT_MOUSE_MOVE) || (currentEvent.type == EVENT_MOUSE_WHEEL)) return -1;
		return currentEvent.button;
	}

	public static int mouseGetEventX() {
		return currentEvent == null ? -1
				: scaleCoordinate(currentEvent.posX, currentEvent.sourceWidth, windowWidth);
	}

	public static int mouseGetEventY() {
		if(currentEvent == null) return -1;
		int sourceTopY = currentEvent.sourceHeight - currentEvent.posY - 1;
		int currentTopY = scaleCoordinate(sourceTopY, currentEvent.sourceHeight, windowHeight);
		return windowHeight - currentTopY - 1;
	}

	private static int scaleCoordinate(int value, int sourceSize, int targetSize) {
		if(targetSize <= 1) return 0;
		if(sourceSize <= 1) return Math.max(0, Math.min(targetSize - 1, value));
		long scaled = ((long)value * (long)targetSize) / (long)sourceSize;
		return (int)Math.max(0L, Math.min((long)targetSize - 1L, scaled));
	}

	public static int mouseGetEventDWheel() {
		return (currentEvent != null && currentEvent.type == EVENT_MOUSE_WHEEL) ? fixWheel(currentEvent.wheel) : 0;
	}

	private static int fixWheel(float val) {
		return (val > 0.0f ? 1 : (val < 0.0f ? -1 : 0));
	}

	public static int mouseGetX() {
		return mouseX;
	}

	public static int mouseGetY() {
		return mouseY;
	}

	public static boolean mouseIsButtonDown(int i) {
		return (i < 0 || i >= buttonStates.length) ? false : buttonStates[i];
	}

	public static int mouseGetDWheel() {
		int ret = (int)mouseDWheel;
		mouseDWheel -= ret;
		return ret;
	}

	public static void mouseSetGrabbed(boolean grab) {
		// Touch look already supplies relative deltas. Requesting Pointer Lock on a
		// coarse-pointer phone adds no input capability and makes Android Chrome show
		// an unavoidable browser security notice over the game.
		if(isLikelyMobileBrowser && touchControlsEnabled) {
			pointerLockFlag = false;
			pointerLockRequested = false;
			pointerLockWaiting = false;
			mouseDX = 0.0D;
			mouseDY = 0.0D;
			return;
		}
		if(remoteDesktopMouseMode) {
			if(isPointerLockedImpl()) {
				callExitPointerLock(win.getDocument());
			}
			pointerLockFlag = grab;
			pointerLockRequested = false;
			pointerLockWaiting = false;
			mouseDX = 0.0D;
			mouseDY = 0.0D;
			canvas.getStyle().setProperty("cursor", grab ? "none" : "default");
			return;
		}
		if(pointerLockSupported == POINTER_LOCK_NONE) {
			return;
		}
		long t = PlatformRuntime.steadyTimeMillis();
		pointerLockRequested = grab;
		if(!grab) {
			unexpectedPointerLockLoss = false;
		}
		mouseGrabTimer = t;
		if(grab) {
			// The browser owns the actual grabbed state. Do not report a successful
			// grab until pointerLockElement confirms it; requests made after a queued
			// game callback may be rejected for lacking transient user activation.
			pointerLockFlag = isPointerLockedImpl();
			pointerLockWaiting = true;
			callRequestPointerLock(canvas);
			if(mouseUngrabTimeout != -1) Window.clearTimeout(mouseUngrabTimeout);
			mouseUngrabTimeout = -1;
			if(t - mouseUngrabTimer < 3000l) {
				mouseUngrabTimeout = Window.setTimeout(new TimerHandler() {
					@Override
					public void onTimer() {
						if(pointerLockRequested && !isPointerLockedImpl()) {
							callRequestPointerLock(canvas);
						}
					}
				}, 3100 - (int)(t - mouseUngrabTimer));
			}
		}else {
			pointerLockFlag = false;
			pointerLockWaiting = false;
			if(mouseUngrabTimeout != -1) Window.clearTimeout(mouseUngrabTimeout);
			mouseUngrabTimeout = -1;
			if(isPointerLockedImpl()) {
				callExitPointerLock(win.getDocument());
			}
		}
		mouseDX = 0.0D;
		mouseDY = 0.0D;
	}

	private static boolean tryGrabCursorHook() {
		if(remoteDesktopMouseMode) {
			return false;
		}
		if(pointerLockSupported == POINTER_LOCK_NONE) {
			return false;
		}
		if(pointerLockRequested && !isPointerLockedImpl()) {
			// A trusted click is the reliable retry boundary. A stale pending request
			// must not suppress it after Escape or a denied asynchronous request.
			pointerLockWaiting = false;
			pointerLockFlag = false;
			mouseSetGrabbed(true);
			return true;
		}
		return false;
	}

	private static void reconcilePointerLockState() {
		boolean wasGrabbed = pointerLockFlag;
		boolean grab = isPointerLockedImpl();
		pointerLockWaiting = false;
		if(grab) {
			if(!pointerLockRequested) {
				pointerLockFlag = false;
				callExitPointerLock(win.getDocument());
			}else {
				pointerLockFlag = true;
			}
		}else {
			if(pointerLockFlag) {
				mouseUngrabTimer = PlatformRuntime.steadyTimeMillis();
			}
			if(wasGrabbed && pointerLockRequested) {
				// Chrome can consume Escape exclusively to release Pointer Lock,
				// so no keyboard event reaches Minecraft. Preserve the 1.8 client
				// behavior by exposing this edge to the game loop, which opens the
				// pause screen instead of requiring a second Escape press.
				unexpectedPointerLockLoss = true;
			}
			pointerLockFlag = false;
		}
	}

	public static boolean consumeUnexpectedPointerLockLoss() {
		boolean ret = unexpectedPointerLockLoss;
		unexpectedPointerLockLoss = false;
		return ret;
	}

	public static void setTrustedEscapeHandler(TrustedEscapeHandler handler) {
		trustedEscapeHandler = handler;
	}

	private static void callRequestPointerLock(HTMLElement el) {
		switch(pointerLockSupported) {
		case POINTER_LOCK_CORE:
			try {
				requestPointerLockCore(el);
			}catch(Throwable t) {
			}
			break;
		case POINTER_LOCK_MOZ:
			try {
				mozRequestPointerLock(el);
			}catch(Throwable t) {
			}
			break;
		default:
			PlatformRuntime.logger.warn("Failed to request pointer lock, it is not supported!");
			break;
		}
	}

	@JSBody(params = { "el" }, script = "try {"
			+ "var doc = el && el.ownerDocument;"
			+ "var fail = function(){ try { if(doc) doc.dispatchEvent(new Event('pointerlockerror')); } catch(x) {} };"
			+ "if(!el || !doc || !el.isConnected) { fail(); return; }"
			+ "var p = el.requestPointerLock();"
			+ "if(p && typeof p.catch === 'function') p.catch(fail);"
			+ "} catch(e) { try { var d = el && el.ownerDocument; if(d) d.dispatchEvent(new Event('pointerlockerror')); } catch(x) {} }")
	private static native void requestPointerLockCore(HTMLElement el);

	@JSBody(params = { "el" }, script = "el.mozRequestPointerLock();")
	private static native void mozRequestPointerLock(HTMLElement el);

	private static void callExitPointerLock(HTMLDocument doc) {
		switch(pointerLockSupported) {
		case POINTER_LOCK_CORE:
			try {
				doc.exitPointerLock();
			}catch(Throwable t) {
			}
			break;
		case POINTER_LOCK_MOZ:
			try {
				mozExitPointerLock(doc);
			}catch(Throwable t) {
			}
			break;
		default:
			PlatformRuntime.logger.warn("Failed to exit pointer lock, it is not supported!");
			break;
		}
	}

	@JSBody(params = { "doc" }, script = "doc.mozExitPointerLock();")
	private static native void mozExitPointerLock(HTMLDocument el);

	public static boolean mouseGrabSupported() {
		return pointerLockSupported != POINTER_LOCK_NONE;
	}

	public static boolean isMouseGrabbed() {
		return pointerLockFlag;
	}

	public static void setRemoteDesktopMouseMode(boolean enabled) {
		remoteDesktopMouseMode = enabled;
		pointerLockRequested = false;
		pointerLockWaiting = false;
		mouseDX = 0.0D;
		mouseDY = 0.0D;
		if(enabled && win != null && canvas != null && isPointerLockedImpl()) {
			callExitPointerLock(win.getDocument());
		}
		if(canvas != null && !pointerLockFlag) {
			canvas.getStyle().setProperty("cursor", "default");
		}
	}

	public static boolean isRemoteDesktopMouseMode() {
		return remoteDesktopMouseMode;
	}

	public static boolean isPointerLocked() {
		if(pointerLockWaiting) return true; // workaround for chrome bug
		return isPointerLockedImpl();
	}

	private static boolean isPointerLockedImpl() {
		switch(pointerLockSupported) {
		case POINTER_LOCK_CORE:
			return isPointerLocked0(win.getDocument(), canvas);
		case POINTER_LOCK_MOZ:
			return isMozPointerLocked0(win.getDocument(), canvas);
		default:
			return false;
		}
	}

	@JSBody(params = { "doc", "canvasEl" }, script = "return doc.pointerLockElement === canvasEl;")
	private static native boolean isPointerLocked0(HTMLDocument doc, HTMLCanvasElement canvasEl);

	@JSBody(params = { "doc", "canvasEl" }, script = "return doc.mozPointerLockElement === canvasEl;")
	private static native boolean isMozPointerLocked0(HTMLDocument doc, HTMLCanvasElement canvasEl);

	public static int mouseGetDX() {
		int ret = (int)mouseDX;
		mouseDX = 0.0D;
		return ret;
	}

	public static int mouseGetDY() {
		int ret = (int)mouseDY;
		mouseDY = 0.0D;
		return ret;
	}

	public static void mouseSetCursorPosition(int x, int y) {
		// obsolete (browser cannot warp the OS cursor)
	}

	public static boolean mouseIsInsideWindow() {
		return isMouseOverWindow;
	}

	public static boolean contextLost() {
		// TODO(3.3): PlatformRuntime.webgl.isContextLost() once the WebGL2 context is wired
		return false;
	}

	public static void setFunctionKeyModifier(int key) {
		functionKeyModifier = key;
	}

	public static void removeEventHandlers() {
		trustedEscapeHandler = null;
		unexpectedPointerLockLoss = false;
		if(contextmenu != null) {
			parent.removeEventListener("contextmenu", contextmenu);
			contextmenu = null;
		}
		if(mousedown != null) {
			canvas.removeEventListener("mousedown", mousedown);
			mousedown = null;
		}
		if(mouseup != null) {
			canvas.removeEventListener("mouseup", mouseup);
			mouseup = null;
		}
		if(mousemove != null) {
			canvas.removeEventListener("mousemove", mousemove);
			mousemove = null;
		}
		if(mouseenter != null) {
			canvas.removeEventListener("mouseenter", mouseenter);
			mouseenter = null;
		}
		if(mouseleave != null) {
			canvas.removeEventListener("mouseleave", mouseleave);
			mouseleave = null;
		}
		if(touchstart != null) {
			canvas.removeEventListener("touchstart", touchstart);
			touchstart = null;
		}
		if(touchmove != null) {
			canvas.removeEventListener("touchmove", touchmove);
			touchmove = null;
		}
		if(touchend != null) {
			canvas.removeEventListener("touchend", touchend);
			touchend = null;
		}
		if(touchcancel != null) {
			canvas.removeEventListener("touchcancel", touchcancel);
			touchcancel = null;
		}
		if(keydown != null) {
			win.removeEventListener("keydown", keydown);
			keydown = null;
		}
		if(keyup != null) {
			win.removeEventListener("keyup", keyup);
			keyup = null;
		}
		if(focus != null) {
			win.removeEventListener("focus", focus);
			focus = null;
		}
		if(blur != null) {
			win.removeEventListener("blur", blur);
			blur = null;
		}
		if(wheel != null) {
			canvas.removeEventListener("wheel", wheel);
			wheel = null;
		}
		if(pointerlock != null) {
			win.getDocument().removeEventListener("pointerlockchange", pointerlock);
			pointerlock = null;
		}
		if(pointerlockerr != null) {
			win.getDocument().removeEventListener("pointerlockerror", pointerlockerr);
			pointerlockerr = null;
		}
		if(fullscreen != null) {
			TeaVMUtils.removeEventListener(fullscreenQuery, "change", fullscreen);
			fullscreen = null;
		}
		if(mouseUngrabTimeout != -1) {
			Window.clearTimeout(mouseUngrabTimeout);
			mouseUngrabTimeout = -1;
		}
		touchCloseDeviceKeyboard();
		if(touchKeyboardOpenZone != null) {
			if(touchKeyboardOpenZoneTouchStart != null) {
				touchKeyboardOpenZone.removeEventListener("touchstart", touchKeyboardOpenZoneTouchStart);
			}
			if(touchKeyboardOpenZoneTouchMove != null) {
				touchKeyboardOpenZone.removeEventListener("touchmove", touchKeyboardOpenZoneTouchMove);
			}
			if(touchKeyboardOpenZoneTouchEnd != null) {
				touchKeyboardOpenZone.removeEventListener("touchend", touchKeyboardOpenZoneTouchEnd);
			}
			if(parent != null) parent.removeChild(touchKeyboardOpenZone);
			touchKeyboardOpenZone = null;
			touchKeyboardOpenZoneTouchStart = null;
			touchKeyboardOpenZoneTouchMove = null;
			touchKeyboardOpenZoneTouchEnd = null;
		}
		try {
			callExitPointerLock(win.getDocument());
		}catch(Throwable t) {
		}
	}

	public static void clearEventBuffers() {
		synchronized(mouseEvents) {
			mouseEvents.clear();
		}
		synchronized(keyEvents) {
			keyEvents.clear();
		}
	}

	@JSBody(params = {}, script = "return window.matchMedia(\"(display-mode: fullscreen)\");")
	private static native JSObject fullscreenMediaQuery();

	@JSBody(params = { "mediaQuery" }, script = "return mediaQuery.matches;")
	private static native boolean mediaQueryMatches(JSObject mediaQuery);

	public static boolean supportsFullscreen() {
		return fullscreenSupported != FULLSCREEN_NONE;
	}

	public static void toggleFullscreen() {
		if(fullscreenSupported == FULLSCREEN_NONE) return;
		if (isFullscreen()) {
			if (keyboardLockSupported) {
				unlockKeys();
				lockKeys = false;
			}
			callExitFullscreen(win.getDocument());
		} else {
			if (keyboardLockSupported) {
				lockKeys();
				lockKeys = true;
			}
			// Keep the hidden mobile keyboard form inside the fullscreen subtree. A
			// canvas-only fullscreen element makes Chrome exclude sibling DOM input.
			callRequestFullscreen(parent != null ? parent : canvas);
		}
	}

	public static boolean isFullscreen() {
		return fullscreenSupported != FULLSCREEN_NONE
				&& (hasFullscreenElement(win.getDocument()) || mediaQueryMatches(fullscreenQuery));
	}

	@JSBody(params = { "doc" }, script = "return !!(doc.fullscreenElement || doc.webkitFullscreenElement || doc.mozFullScreenElement);")
	private static native boolean hasFullscreenElement(HTMLDocument doc);

	@JSBody(params = { }, script = "navigator.keyboard.lock();")
	private static native void lockKeys();

	@JSBody(params = { }, script = "navigator.keyboard.unlock();")
	private static native void unlockKeys();

	@JSBody(params = { }, script = "return !!(navigator.keyboard && navigator.keyboard.lock);")
	private static native boolean checkKeyboardLockSupported();

	private static void callRequestFullscreen(HTMLElement el) {
		switch(fullscreenSupported) {
		case FULLSCREEN_CORE:
			try {
				requestFullscreen(el);
			}catch(Throwable t) {
			}
			break;
		case FULLSCREEN_WEBKIT:
			try {
				webkitRequestFullscreen(el);
			}catch(Throwable t) {
			}
			break;
		case FULLSCREEN_MOZ:
			try {
				mozRequestFullscreen(el);
			}catch(Throwable t) {
			}
			break;
		default:
			PlatformRuntime.logger.warn("Failed to request fullscreen, it is not supported!");
			break;
		}
	}

	@JSBody(params = { "el" }, script = "el.requestFullscreen();")
	private static native void requestFullscreen(HTMLElement element);

	@JSBody(params = { "el" }, script = "el.webkitRequestFullscreen();")
	private static native void webkitRequestFullscreen(HTMLElement element);

	@JSBody(params = { "el" }, script = "el.mozRequestFullScreen();")
	private static native void mozRequestFullscreen(HTMLElement element);

	private static void callExitFullscreen(HTMLDocument doc) {
		switch(fullscreenSupported) {
		case FULLSCREEN_CORE:
			try {
				exitFullscreen(doc);
			}catch(Throwable t) {
			}
			break;
		case FULLSCREEN_WEBKIT:
			try {
				webkitExitFullscreen(doc);
			}catch(Throwable t) {
			}
			break;
		case FULLSCREEN_MOZ:
			try {
				mozCancelFullscreen(doc);
			}catch(Throwable t) {
			}
			break;
		default:
			PlatformRuntime.logger.warn("Failed to exit fullscreen, it is not supported!");
			break;
		}
	}

	@JSBody(params = { "doc" }, script = "doc.exitFullscreen();")
	private	static native void exitFullscreen(HTMLDocument doc);

	@JSBody(params = { "doc" }, script = "doc.webkitExitFullscreen();")
	private	static native void webkitExitFullscreen(HTMLDocument doc);

	@JSBody(params = { "doc" }, script = "doc.mozCancelFullscreen();")
	private	static native void mozCancelFullscreen(HTMLDocument doc);

	public static void showCursor(EnumCursorType cursor) {
		if(canvas == null) return;
		switch(cursor) {
		case DEFAULT:
		default:
			canvas.getStyle().setProperty("cursor", "default");
			break;
		case HAND:
			canvas.getStyle().setProperty("cursor", "pointer");
			break;
		case TEXT:
			canvas.getStyle().setProperty("cursor", "text");
			break;
		}
	}

	public static boolean touchNext() {
		currentTouchEvent = touchEvents.isEmpty() ? null : touchEvents.remove(0);
		return currentTouchEvent != null;
	}

	public static EnumTouchEvent touchGetEventType() {
		return currentTouchEvent == null ? null : currentTouchEvent.type;
	}

	public static int touchGetEventTouchPointCount() {
		return currentTouchEvent == null ? 0 : currentTouchEvent.eventTouches.size();
	}

	public static int touchGetEventTouchX(int pointId) {
		if(currentTouchEvent == null) return -1;
		VTouchPoint point = currentTouchEvent.eventTouches.get(pointId);
		return scaleCoordinate(point.x, point.sourceWidth, windowWidth);
	}

	public static int touchGetEventTouchY(int pointId) {
		if(currentTouchEvent == null) return -1;
		VTouchPoint point = currentTouchEvent.eventTouches.get(pointId);
		int sourceTopY = point.sourceHeight - point.y - 1;
		int currentTopY = scaleCoordinate(sourceTopY, point.sourceHeight, windowHeight);
		return windowHeight - currentTopY - 1;
	}

	public static float touchGetEventTouchRadiusMixed(int pointId) {
		return currentTouchEvent == null ? 1.0f : currentTouchEvent.eventTouches.get(pointId).radius;
	}

	public static float touchGetEventTouchForce(int pointId) {
		return currentTouchEvent == null ? 0.0f : currentTouchEvent.eventTouches.get(pointId).force;
	}

	public static int touchGetEventTouchPointUID(int pointId) {
		return currentTouchEvent == null ? -1 : currentTouchEvent.eventTouches.get(pointId).uid;
	}

	public static int touchPointCount() {
		return currentTouchState.size();
	}

	public static int touchPointX(int pointId) {
		VTouchPoint point = currentTouchState.get(pointId);
		return scaleCoordinate(point.x, point.sourceWidth, windowWidth);
	}

	public static int touchPointY(int pointId) {
		VTouchPoint point = currentTouchState.get(pointId);
		int sourceTopY = point.sourceHeight - point.y - 1;
		int currentTopY = scaleCoordinate(sourceTopY, point.sourceHeight, windowHeight);
		return windowHeight - currentTopY - 1;
	}

	public static float touchRadiusMixed(int pointId) {
		return currentTouchState.get(pointId).radius;
	}

	public static float touchForce(int pointId) {
		return currentTouchState.get(pointId).force;
	}

	public static int touchPointUID(int pointId) {
		return currentTouchState.get(pointId).uid;
	}

	public static String touchGetPastedString() {
		return pastedStrings.isEmpty() ? null : pastedStrings.remove(0);
	}

	public static void touchSetOpenKeyboardZone(int x, int y, int w, int h) {
		if(touchKeyboardOpenZone == null) return;
		CSSStyleDeclaration style = touchKeyboardOpenZone.getStyle();
		if(w > 0 && h > 0) {
			int xx = (int)(x / windowDPI);
			int yy = (int)((windowHeight - y - h) / windowDPI);
			int ww = Math.max(1, (int)(w / windowDPI));
			int hh = Math.max(1, (int)(h / windowDPI));
			if(xx != touchOpenZoneX || yy != touchOpenZoneY || ww != touchOpenZoneW || hh != touchOpenZoneH) {
				style.setProperty("display", "block");
				style.setProperty("left", xx + "px");
				style.setProperty("top", yy + "px");
				style.setProperty("width", ww + "px");
				style.setProperty("height", hh + "px");
				touchOpenZoneX = xx;
				touchOpenZoneY = yy;
				touchOpenZoneW = ww;
				touchOpenZoneH = hh;
			}
		} else {
			style.setProperty("display", "none");
			style.setProperty("width", "0px");
			style.setProperty("height", "0px");
			touchOpenZoneX = touchOpenZoneY = touchOpenZoneW = touchOpenZoneH = 0;
		}
	}

	public static void touchSetOpenKeyboardShortcutZone(int x, int y, int w, int h) {
		if(w > 0 && h > 0) {
			touchKeyboardShortcutX = x;
			touchKeyboardShortcutY = y;
			touchKeyboardShortcutW = w;
			touchKeyboardShortcutH = h;
		} else {
			touchKeyboardShortcutX = touchKeyboardShortcutY = 0;
			touchKeyboardShortcutW = touchKeyboardShortcutH = 0;
		}
	}

	public static void touchSetFullscreenShortcutZone(int x, int y, int w, int h) {
		if(w > 0 && h > 0) {
			touchFullscreenShortcutX = x;
			touchFullscreenShortcutY = y;
			touchFullscreenShortcutW = w;
			touchFullscreenShortcutH = h;
		} else {
			touchFullscreenShortcutX = touchFullscreenShortcutY = 0;
			touchFullscreenShortcutW = touchFullscreenShortcutH = 0;
		}
	}

	public static void touchCloseDeviceKeyboard() {
		if(touchKeyboardField != null) {
			dismissTouchKeyboard(win.getDocument(), touchKeyboardField);
		}
		if(touchKeyboardForm != null && touchKeyboardField != null) {
			touchKeyboardForm.removeChild(touchKeyboardField);
		}
		if(parent != null && touchKeyboardForm != null) {
			parent.removeChild(touchKeyboardForm);
		}
		touchKeyboardField = null;
		touchKeyboardForm = null;
		touchKeyboardComposing = false;
		touchKeyboardCompositionCommitted = false;
		lastTouchCompositionCommit = null;
	}

	/**
	 * Give hosted browser IMEs a real editable target whenever Minecraft focuses
	 * a text widget. Mobile still obtains the initial focus from touchstart.
	 */
	public static void setTextInputActive(boolean active) {
		if(active) {
			openTouchKeyboard();
		} else {
			touchCloseDeviceKeyboard();
		}
	}

	@JSBody(params = { "doc", "input" }, script = "try{input.blur();}catch(e){}"
			+ "try{var a=doc.activeElement;if(a&&a!==doc.body&&typeof a.blur==='function')a.blur();}catch(e){}"
			+ "try{if(navigator.virtualKeyboard&&navigator.virtualKeyboard.hide)navigator.virtualKeyboard.hide();}catch(e){}")
	private static native void dismissTouchKeyboard(HTMLDocument doc, HTMLInputElement input);

	public static boolean touchIsDeviceKeyboardOpenMAYBE() {
		return touchKeyboardField != null && isActiveElement(win.getDocument(), touchKeyboardField);
	}

	@JSBody(params = { "doc", "element" }, script = "return doc.activeElement === element;")
	private static native boolean isActiveElement(HTMLDocument doc, HTMLElement element);

	public static boolean isTouchClient() {
		return isLikelyMobileBrowser;
	}

	public static void setTouchControlsEnabled(boolean enabled) {
		touchControlsEnabled = enabled;
	}

	public static boolean isTouchControlsEnabled() {
		return touchControlsEnabled;
	}

	// ==== gamepad: inert this phase (net.lax1dude...Gamepad JSO wrapper not copied) TODO(3.x) ====

	public static int gamepadGetValidDeviceCount() {
		return 0;
	}

	public static String gamepadGetDeviceName(int deviceId) {
		return null;
	}

	public static void gamepadSetSelectedDevice(int deviceId) {
	}

	public static void gamepadUpdate() {
	}

	public static boolean gamepadIsValid() {
		return false;
	}

	public static String gamepadGetName() {
		return null;
	}

	public static boolean gamepadGetButtonState(int button) {
		return false;
	}

	public static float gamepadGetAxis(int axis) {
		return 0.0f;
	}

	public static float getDPI() {
		return windowDPI;
	}

}

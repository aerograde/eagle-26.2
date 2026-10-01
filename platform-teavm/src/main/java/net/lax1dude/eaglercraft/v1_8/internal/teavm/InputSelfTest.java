/*
 * Copyright (c) 2025 lax1dude. All Rights Reserved.
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

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

import net.lax1dude.eaglercraft.v1_8.internal.KeyboardConstants;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformInput;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;

/**
 * Phase 3.2b headless self-test for the browser input backend. Opt-in only
 * (eaglercraftXOpts.inputSelfTest === true); {@link ClientMain} guards the call
 * so this is dead code otherwise.
 *
 * It attaches {@link PlatformInput} to the runtime canvas/window, synthesises
 * plain and mapped keys plus a mouse-move burst onto the targets the
 * listeners bind to, pumps the eagler event queues exactly as the game's
 * Phase-2c seam (KeyboardHandler/MouseHandler.eaglerPumpEvents) does, and checks
 * the 26.2 host fields, key latches, text suppression, and mouse edge order.
 */
public class InputSelfTest {

	@JSBody(params = { "msg" }, script = "console.log(msg);")
	private static native void log(String msg);

	@JSBody(params = { "target", "type", "code", "key" }, script = "target.dispatchEvent(new KeyboardEvent(type, "
			+ "{ code: code, key: key, bubbles: true, cancelable: true }));")
	private static native void dispatchKey(JSObject target, String type, String code, String key);

	@JSBody(params = { "target" }, script = ""
			+ "for(var i = 0; i < 96; ++i) target.dispatchEvent(new MouseEvent('mousemove', "
			+ "{ clientX: 100 + i, clientY: 80 + i, bubbles: true, cancelable: true }));"
			+ "target.dispatchEvent(new MouseEvent('mousedown', "
			+ "{ button: 0, clientX: 43, clientY: 31, bubbles: true, cancelable: true }));"
			+ "for(var i = 0; i < 96; ++i) target.dispatchEvent(new MouseEvent('mousemove', "
			+ "{ clientX: 220 + i, clientY: 160 + i, bubbles: true, cancelable: true }));"
			+ "target.dispatchEvent(new MouseEvent('mouseup', "
			+ "{ button: 0, clientX: 47, clientY: 35, bubbles: true, cancelable: true }));"
			+ "target.dispatchEvent(new WheelEvent('wheel', "
			+ "{ deltaY: -120, deltaX: 40, clientX: 51, clientY: 39, bubbles: true, cancelable: true }));")
	private static native void dispatchMouseBurst(JSObject target);

	public static void run() {
		log("eagler-input-selftest: begin");

		PlatformInput.clearEventBuffers();
		// attach the real DOM hooks (GLFW handle arg is ignored in the browser)
		PlatformInput.initHooksHosted(0L);

		JSObject winTarget = PlatformRuntime.win;
		JSObject canvasTarget = PlatformRuntime.canvas;
		if(winTarget == null || canvasTarget == null) {
			log("eagler-input-selftest: FAIL no canvas/window target");
			return;
		}

		// Plain keys keep their normal text and host codes.
		dispatchKey(winTarget, "keydown", "KeyW", "w");
		dispatchKey(winTarget, "keyup", "KeyW", "w");
		dispatchKey(winTarget, "keydown", "KeyF", "f");
		dispatchKey(winTarget, "keyup", "KeyF", "f");
		dispatchKey(winTarget, "keydown", "Digit0", "0");
		dispatchKey(winTarget, "keyup", "Digit0", "0");

		int plainW = 0;
		int plainF = 0;
		int plain0 = 0;
		while(PlatformInput.keyboardNext()) {
			boolean pressed = PlatformInput.keyboardGetEventKeyState();
			int key = PlatformInput.keyboardGetEventKey();
			char typed = PlatformInput.keyboardGetEventCharacter();
			if(pressed && key == KeyboardConstants.KEY_W && typed == 'w') ++plainW;
			if(pressed && key == KeyboardConstants.KEY_F && typed == 'f') ++plainF;
			if(pressed && key == KeyboardConstants.KEY_0 && typed == '0') ++plain0;
		}

		// The digit keeps its mapped key until its own release.
		dispatchKey(winTarget, "keydown", "KeyF", "f");
		dispatchKey(winTarget, "keydown", "Digit3", "3");
		int f3Down = 0;
		int f3DownNul = 0;
		while(PlatformInput.keyboardNext()) {
			if(PlatformInput.keyboardGetEventKeyState()
					&& PlatformInput.keyboardGetEventKey() == KeyboardConstants.KEY_F3) {
				++f3Down;
				if(PlatformInput.keyboardGetEventCharacter() == '\0') ++f3DownNul;
			}
		}
		boolean f3Held = PlatformInput.keyboardIsKeyDown(KeyboardConstants.KEY_F3);
		dispatchKey(winTarget, "keyup", "KeyF", "f");
		while(PlatformInput.keyboardNext()) {
		}
		boolean fReleasedFirst = !PlatformInput.keyboardIsKeyDown(KeyboardConstants.KEY_F)
				&& PlatformInput.keyboardIsKeyDown(KeyboardConstants.KEY_F3);
		dispatchKey(winTarget, "keyup", "Digit3", "3");
		int f3Up = 0;
		int f3UpNul = 0;
		while(PlatformInput.keyboardNext()) {
			if(!PlatformInput.keyboardGetEventKeyState()
					&& PlatformInput.keyboardGetEventKey() == KeyboardConstants.KEY_F3) {
				++f3Up;
				if(PlatformInput.keyboardGetEventCharacter() == '\0') ++f3UpNul;
			}
		}
		boolean f3Released = !PlatformInput.keyboardIsKeyDown(KeyboardConstants.KEY_F3);

		dispatchKey(winTarget, "keydown", "KeyF", "f");
		dispatchKey(winTarget, "keydown", "Digit0", "0");
		dispatchKey(winTarget, "keyup", "KeyF", "f");
		dispatchKey(winTarget, "keyup", "Digit0", "0");
		int f10Down = 0;
		int f10Up = 0;
		int f10Nul = 0;
		while(PlatformInput.keyboardNext()) {
			if(PlatformInput.keyboardGetEventKey() == KeyboardConstants.KEY_F10) {
				if(PlatformInput.keyboardGetEventKeyState()) ++f10Down;
				else ++f10Up;
				if(PlatformInput.keyboardGetEventCharacter() == '\0') ++f10Nul;
			}
		}

		// High-rate moves coalesce without evicting button or wheel edges.
		dispatchMouseBurst(canvasTarget);
		int btnDown = 0;
		int btnUp = 0;
		int move = 0;
		int wheel = 0;
		int stage = 0;
		int lastMoveX = Integer.MIN_VALUE;
		boolean buttonCoordinatesFresh = true;
		while(PlatformInput.mouseNext()) {
			int button = PlatformInput.mouseGetEventButton();
			if(button >= 0) {
				if(PlatformInput.mouseGetEventButtonState()) {
					++btnDown;
					if(stage != 1) stage = -100;
					else stage = 2;
				}else {
					++btnUp;
					if(stage != 3) stage = -100;
					else stage = 4;
				}
				buttonCoordinatesFresh &= PlatformInput.mouseGetEventX() != lastMoveX;
			}else {
				float wheelY = PlatformInput.mouseGetEventDWheelF();
				if(wheelY != 0.0f) {
					++wheel;
					if(stage != 4) stage = -100;
					else stage = 5;
				}else {
					++move;
					lastMoveX = PlatformInput.mouseGetEventX();
					if(stage == 0) stage = 1;
					else if(stage == 2) stage = 3;
					else stage = -100;
				}
			}
		}

		if(plainW == 1 && plainF == 1 && plain0 == 1 && f3Down == 1 && f3DownNul == 1 && f3Held
				&& fReleasedFirst && f3Up == 1 && f3UpNul == 1 && f3Released
				&& f10Down == 1 && f10Up == 1 && f10Nul == 2
				&& btnDown == 1 && btnUp == 1 && move == 2 && wheel == 1 && stage == 5
				&& buttonCoordinatesFresh) {
			log("eagler-input-selftest: PASS key+mouse parity");
		}else {
			log("eagler-input-selftest: FAIL plain=" + plainW + "/" + plainF + "/" + plain0
					+ " f3=" + f3Down + "/" + f3DownNul + "/" + f3Held + "/" + fReleasedFirst
					+ "/" + f3Up + "/" + f3UpNul + "/" + f3Released
					+ " f10=" + f10Down + "/" + f10Up + "/" + f10Nul + " mouse=" + btnDown + "/" + btnUp
					+ "/" + move + "/" + wheel + " stage=" + stage + " coords=" + buttonCoordinatesFresh);
		}
	}

}

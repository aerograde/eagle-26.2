/*
 * Copyright (c) 2022-2025 lax1dude. All Rights Reserved.
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

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.ArrayBufferView;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Int16Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Uint8Array;

/**
 * Upstream: EaglercraftX-1.8-workspace-master src/teavm TeaVMUtils.
 *
 * 26.2 adaptation (docs/phase3-web-toolchain-map.md, HIGH-RISK UPGRADE item):
 * upstream's zero-copy array (un)wrap helpers were declared with the
 * {@code @InjectedBy}/{@code @GeneratedBy} JS-backend generator SPI
 * ({@code TeaVMUtilsUnwrapGenerator}). That SPI changed between TeaVM 0.9 and
 * 0.13, so the generator machinery is NOT copied. The (un)wrap methods are
 * reimplemented against the stock TeaVM 0.13 JSO typed-array API
 * ({@link Int8Array#fromJavaArray}/{@code copyToJavaArray} etc.), the same
 * pattern the 3.1 buffer copies already use (see EaglerArrayIntBuffer,
 * PlatformApplication, PlatformAssets). Semantics: {@code unwrap*} returns a
 * JS view over the Java array's backing store, {@code wrap*} copies a JS typed
 * array back into a fresh Java array.
 *
 * Touch-sorting + classes.js locator + runtime-deobfuscator helpers are dropped
 * here (input = Phase 3.2b, deobfuscator = DROP per the map's REFERENCE/DROP
 * verdicts).
 */
public class TeaVMUtils {

	@JSBody(params = { "url" }, script = "URL.revokeObjectURL(url);")
	public static native void freeDataURL(String url);

	@JSBody(params = { "buf", "mime" }, script = "return URL.createObjectURL(new Blob([buf], {type: mime}));")
	public static native String getDataURL(ArrayBuffer buf, String mime);

	@JSBody(params = { "blob" }, script = "return URL.createObjectURL(blob);")
	public static native String getDataURL(JSObject blob);

	@JSBody(params = { "obj", "name", "handler" }, script = "obj.addEventListener(name, handler);")
	public static native void addEventListener(JSObject obj, String name, JSObject handler);

	@JSBody(params = { "obj", "name", "handler" }, script = "obj.removeEventListener(name, handler);")
	public static native void removeEventListener(JSObject obj, String name, JSObject handler);

	@JSBody(params = {}, script = "return (new Error()).stack;")
	public static native String dumpJSStackTrace();

	// ===== zero-copy-ish (un)wrap, reimplemented on TeaVM 0.13 JSO =====

	public static Int8Array unwrapByteArray(byte[] buf) {
		return Int8Array.fromJavaArray(buf);
	}

	public static ArrayBuffer unwrapArrayBuffer(byte[] buf) {
		return Int8Array.fromJavaArray(buf).getBuffer();
	}

	public static ArrayBufferView unwrapArrayBufferView(byte[] buf) {
		return Int8Array.fromJavaArray(buf);
	}

	public static byte[] wrapByteArray(Int8Array buf) {
		return buf.copyToJavaArray();
	}

	public static byte[] wrapByteArrayBuffer(ArrayBuffer buf) {
		return Int8Array.create(buf).copyToJavaArray();
	}

	public static byte[] wrapByteArrayBufferView(ArrayBufferView buf) {
		return Int8Array.create(buf.getBuffer(), buf.getByteOffset(), buf.getLength()).copyToJavaArray();
	}

	public static Uint8Array unwrapUnsignedByteArray(byte[] buf) {
		Int8Array a = Int8Array.fromJavaArray(buf);
		return Uint8Array.create(a.getBuffer(), a.getByteOffset(), a.getLength());
	}

	public static byte[] wrapUnsignedByteArray(Uint8Array buf) {
		return Int8Array.create(buf.getBuffer(), buf.getByteOffset(), buf.getLength()).copyToJavaArray();
	}

	public static Int32Array unwrapIntArray(int[] buf) {
		return Int32Array.fromJavaArray(buf);
	}

	public static ArrayBuffer unwrapArrayBuffer(int[] buf) {
		return Int32Array.fromJavaArray(buf).getBuffer();
	}

	public static int[] wrapIntArray(Int32Array buf) {
		return buf.copyToJavaArray();
	}

	public static Float32Array unwrapFloatArray(float[] buf) {
		return Float32Array.fromJavaArray(buf);
	}

	public static ArrayBuffer unwrapArrayBuffer(float[] buf) {
		return Float32Array.fromJavaArray(buf).getBuffer();
	}

	public static float[] wrapFloatArray(Float32Array buf) {
		return buf.copyToJavaArray();
	}

	public static Int16Array unwrapShortArray(short[] buf) {
		return Int16Array.fromJavaArray(buf);
	}

	public static short[] wrapShortArray(Int16Array buf) {
		return buf.copyToJavaArray();
	}

	@Async
	public static native void sleepSetTimeout(int millis);

	private static void sleepSetTimeout(int millis, AsyncCallback<Void> cb) {
		Window.setTimeout(() -> cb.complete(null), millis);
	}

	@JSBody(params = { "obj" }, script = "console.log(obj);")
	public static native void objDump(JSObject obj);

	@JSBody(params = { "obj" }, script = "return \"\" + obj;")
	public static native String safeToString(JSObject obj);

	@JSBody(params = { "obj" }, script = "return (!!obj && (typeof obj.message === \"string\")) ? obj.message : (\"\" + obj);")
	public static native String safeErrorMsgToString(JSObject obj);

	@JSBody(params = { "obj" }, script = "return !!obj;")
	public static native boolean isTruthy(JSObject object);

	@JSBody(params = { "obj" }, script = "return !obj;")
	public static native boolean isNotTruthy(JSObject object);

	@JSBody(params = { "obj" }, script = "return obj === undefined;")
	public static native boolean isUndefined(JSObject object);

	public static <T extends JSObject> T ensureDefined(T valIn) {
		return isUndefined((JSObject) valIn) ? null : valIn;
	}

	@JSBody(params = { "obj" }, script = "return obj.stack||null;")
	public static native String getStackSafe(JSObject object);

}

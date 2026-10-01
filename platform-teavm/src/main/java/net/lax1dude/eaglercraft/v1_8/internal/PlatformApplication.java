/*
 * Copyright (c) 2022-2024 lax1dude. All Rights Reserved.
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

// 26.2 adaptations (Phase 3.1, see report):
//  - PlatformRuntime.logger -> local logger (stub PlatformRuntime has no logger field)
//  - PlatformRuntime.parent/doc/win -> parentEl()/docEl()/winEl() helpers, TODO(3.2)
//  - eagler Base64 -> java.util.Base64 (shared Base64 not in :platform yet), TODO(3.2)
//  - TeaVMUtils.wrapByteArrayBuffer/isNotTruthy -> 0.13 copyToJavaArray / local JSBody
//  - saveScreenshot() downloads an explicit framebuffer readback
//  - DebugConsoleWindow calls stubbed, TODO(3.2)

package net.lax1dude.eaglercraft.v1_8.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Storage;
import org.teavm.jso.browser.TimerHandler;
import org.teavm.jso.browser.Window;
import org.teavm.jso.canvas.CanvasRenderingContext2D;
import org.teavm.jso.dom.css.CSSStyleDeclaration;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.dom.html.HTMLInputElement;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.DebugConsoleWindow;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMBlobURLHandle;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMBlobURLManager;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

public class PlatformApplication {

	// TODO(3.2): upstream uses PlatformRuntime.logger; PlatformRuntime is a later increment
	private static final Logger logger = LogManager.getLogger("PlatformApplication");

	// TODO(3.2): upstream uses PlatformRuntime.parent/doc/win set up by ClientMain;
	// fall back to the live document until PlatformRuntime is ported
	private static HTMLElement parentEl() {
		return Window.current().getDocument().getBody();
	}

	private static HTMLDocument docEl() {
		return Window.current().getDocument();
	}

	private static Window winEl() {
		return Window.current();
	}

	// TODO(3.2): local copy of TeaVMUtils.isNotTruthy (TeaVMUtils is a later increment)
	@JSBody(params = { "obj" }, script = "return !obj;")
	private static native boolean isNotTruthy(JSObject object);

	public static void openLink(String url) {
		if(url.indexOf(':') == -1) {
			url = "http://" + url;
		}
		URI parsedURL;
		try {
			parsedURL = new URI(url);
		}catch(URISyntaxException ex) {
			logger.error("Refusing to open invalid URL: {}", url);
			return;
		}
		try {
			Window.current().open(parsedURL.toString(), "_blank", "noopener,noreferrer");
		}catch(Throwable t) {
			logger.error("Exception opening link!");
		}
	}

	public static void setClipboard(String text) {
		boolean b = false;
		try {
			b = setClipboard0(text);
		}catch(Throwable t) {
			logger.error("Exception setting clipboard data");
		}
		if(!b) {
			try {
				Window.prompt("Here is the text you're trying to copy:", text);
			}catch(Throwable t2) {
			}
		}
	}

	public static String getClipboard() {
		String ret = null;
		try {
			ret = getClipboard0();
		}catch(Throwable t) {
			logger.error("Exception getting clipboard data");
		}
		if(ret == null) {
			try {
				ret = Window.prompt("Please enter the text to paste:");
			}catch(Throwable t2) {
			}
		}
		return ret != null ? ret : "";
	}

	@JSFunctor
	private static interface StupidFunctionResolveString extends JSObject {
		void resolveStr(String s);
	}

	@Async
	private static native String getClipboard0();

	private static void getClipboard0(final AsyncCallback<String> cb) {
		final long start = PlatformRuntime.steadyTimeMillis();
		getClipboard1(new StupidFunctionResolveString() {
			@Override
			public void resolveStr(String s) {
				if(PlatformRuntime.steadyTimeMillis() - start > 500l) {
					// TODO(3.2): PlatformInput.unpressCTRL = true; (field not in stub PlatformInput)
				}
				cb.complete(s);
			}
		});
	}

	@JSBody(params = { "cb" }, script = "if(!navigator.clipboard) { cb(null); } else if (!navigator.clipboard.readText) cb(null); else navigator.clipboard.readText().then(function(s) { cb((typeof s === \"string\") ? s : null); }, function(err) { cb(null); });")
	private static native void getClipboard1(StupidFunctionResolveString cb);

	@JSBody(params = { "str" }, script = "if(navigator.clipboard) { navigator.clipboard.writeText(str); return true; } else { return false; }")
	private static native boolean setClipboard0(String str);

	public static void setLocalStorage(String name, byte[] data) {
		setLocalStorage(name, data, true);
	}

	public static void setLocalStorage(String name, byte[] data, boolean hooks) {
		IClientConfigAdapter adapter = PlatformRuntime.getClientConfigAdapter();
		String eagName = adapter.getLocalStorageNamespace() + "." + name;
		// TODO(3.2): upstream uses eagler Base64.encodeBase64String
		String b64 = data != null ? Base64.getEncoder().encodeToString(data) : null;
		try {
			Storage s = Window.current().getLocalStorage();
			if(s != null) {
				if(b64 != null) {
					s.setItem(eagName, b64);
				}else {
					s.removeItem(eagName);
				}
			}
		}catch(Throwable t) {
		}
		if(hooks) {
			adapter.getHooks().callLocalStorageSavedHook(name, b64);
		}
	}

	public static byte[] getLocalStorage(String name) {
		return getLocalStorage(name, true);
	}

	public static byte[] getLocalStorage(String name, boolean hooks) {
		IClientConfigAdapter adapter = PlatformRuntime.getClientConfigAdapter();
		String eagName = adapter.getLocalStorageNamespace() + "." + name;
		byte[] hooked = null;
		if(hooks) {
			String hookedStr = adapter.getHooks().callLocalStorageLoadHook(eagName);
			if(hookedStr != null) {
				try {
					// TODO(3.2): upstream uses eagler Base64.decodeBase64
					hooked = Base64.getMimeDecoder().decode(hookedStr);
				}catch(Throwable t) {
					logger.error("Invalid Base64 recieved from local storage hook!");
					hooked = null;
				}
			}
		}
		if(hooked == null) {
			try {
				Storage s = Window.current().getLocalStorage();
				if(s != null) {
					String str = s.getItem(eagName);
					if(str != null) {
						return Base64.getMimeDecoder().decode(str);
					}else {
						return null;
					}
				}else {
					return null;
				}
			}catch(Throwable t) {
				return null;
			}
		}else {
			return hooked;
		}
	}

	private static final DateFormat dateFormatSS = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");

	public static String saveScreenshot() {
		return null;
	}

	public static String saveScreenshot(int width, int height, byte[] rgba) {
		if(width <= 0 || height <= 0 || rgba == null || rgba.length != width * height * 4) {
			return null;
		}
		String name = "screenshot_" + dateFormatSS.format(new Date()).toString() + ".png";
		if(!downloadScreenshot(Int8Array.fromJavaArray(rgba), width, height, name, parentEl())) {
			logger.error("Failed to download screenshot: {}", name);
			return null;
		}
		return name;
	}

	@JSBody(params = { "rgba", "width", "height", "name", "parentElement" }, script =
			"try { var cvs = document.createElement(\"canvas\"); cvs.width = width; cvs.height = height;" +
			"var ctx = cvs.getContext(\"2d\"); if(!ctx) return false;" +
			"var img = ctx.createImageData(width, height);" +
			"img.data.set(new Uint8Array(rgba.buffer, rgba.byteOffset, rgba.byteLength));" +
			"ctx.putImageData(img, 0, 0); var url = cvs.toDataURL(\"image/png\");" +
			"if(typeof url !== \"string\" || url.indexOf(\"data:image/png\") !== 0) return false;" +
			"var el = document.createElement(\"a\"); el.style.display = \"none\";" +
			"el.href = url; el.download = name; parentElement.appendChild(el); el.click();" +
			"parentElement.removeChild(el); return true; } catch(e) { return false; }")
	private static native boolean downloadScreenshot(Int8Array rgba, int width, int height, String name, HTMLElement parentElement);

	public static void showPopup(final String msg) {
		Window.setTimeout(new TimerHandler() {
			@Override
			public void onTimer() {
				Window.alert(msg);
			}
		}, 1);
	}

	@JSFunctor
	private static interface FileChooserCallback extends JSObject {
		void accept(String name, ArrayBuffer buffer);
	}

	@JSFunctor
	private static interface FileChooserProgressCallback extends JSObject {
		void accept(String name, double loaded, double total);
	}

	private static class FileChooserCallbackImpl implements FileChooserCallback {

		private static final FileChooserCallbackImpl instance = new FileChooserCallbackImpl();

		@Override
		public void accept(String name, ArrayBuffer buffer) {
			fileChooserIsReading = false;
			fileChooserHasResult = true;
			if(name == null) {
				fileChooserResultObject = null;
			}else {
				// 0.13 JSO: copyToJavaArray replaces TeaVMUtils.wrapByteArrayBuffer
				fileChooserResultObject = new FileChooserResult(name, new Int8Array(buffer).copyToJavaArray());
			}
		}

	}

	private static class FileChooserProgressCallbackImpl implements FileChooserProgressCallback {

		private static final FileChooserProgressCallbackImpl instance = new FileChooserProgressCallbackImpl();

		@Override
		public void accept(String name, double loaded, double total) {
			fileChooserIsReading = true;
			fileChooserReadingName = name == null ? "" : name;
			fileChooserBytesRead = Math.max(0L, (long)loaded);
			fileChooserBytesTotal = Math.max(0L, (long)total);
		}

	}

	private static final int FILE_CHOOSER_IMPL_CORE = 0;
	private static final int FILE_CHOOSER_IMPL_LEGACY = 1;
	private static int fileChooserImpl = -1;
	private static boolean fileChooserHasResult = false;
	private static FileChooserResult fileChooserResultObject = null;
	private static boolean fileChooserIsReading = false;
	private static String fileChooserReadingName = "";
	private static long fileChooserBytesRead = 0L;
	private static long fileChooserBytesTotal = 0L;
	private static HTMLInputElement fileChooserElement = null;
	private static HTMLElement fileChooserMobileElement = null;

	@JSBody(params = { "inputElement", "callback", "progress" }, script =
			"if(inputElement.files.length > 0) {"
			+ "var eag = function(value, reader){"
			+ "progress(value.name, 0, value.size || 0);"
			+ "reader.addEventListener(\"progress\",function(evt){ progress(value.name, evt.loaded || 0, evt.total || value.size || 0); });"
			+ "reader.addEventListener(\"load\",function(){ progress(value.name, value.size || 0, value.size || 0); callback(value.name, reader.result); });"
			+ "reader.addEventListener(\"error\",function(){ callback(null, null); });"
			+ "reader.readAsArrayBuffer(value);"
			+ "}; eag(inputElement.files[0], new FileReader()); } else callback(null, null);")
	private static native void getFileChooserResultNew(HTMLInputElement inputElement, FileChooserCallback callback,
			FileChooserProgressCallback progress);

	@JSBody(params = { "inputElement", "callback", "progress" }, script =
			"if(inputElement.files.length > 0) {"
			+ "var eag = function(value, reader){"
			+ "progress(value.name, 0, value.size || 0);"
			+ "reader.addEventListener(\"progress\",function(evt){ progress(value.name, evt.loaded || 0, evt.total || value.size || 0); });"
			+ "reader.addEventListener(\"load\",function(){ progress(value.name, value.size || 0, value.size || 0); callback(value.name, reader.result); });"
			+ "reader.addEventListener(\"error\",function(evt){ callback(null, null); });"
			+ "reader.readAsArrayBuffer(value);"
			+ "}; eag(inputElement.files[0], new FileReader()); } else callback(null, null);")
	private static native void getFileChooserResultLegacy(HTMLInputElement inputElement, FileChooserCallback callback,
			FileChooserProgressCallback progress);

	private static void getFileChooserResult(HTMLInputElement inputElement, FileChooserCallback callback) {
		if(fileChooserImpl == -1) {
			fileChooserImpl = getFileChooserImpl();
			if(fileChooserImpl == FILE_CHOOSER_IMPL_LEGACY) {
				logger.info("Note: using legacy FileReader implementation because File.prototype.arrayBuffer() is not supported");
			}
		}
		switch(fileChooserImpl) {
		case FILE_CHOOSER_IMPL_CORE:
			getFileChooserResultNew(inputElement, callback, FileChooserProgressCallbackImpl.instance);
			break;
		case FILE_CHOOSER_IMPL_LEGACY:
			getFileChooserResultLegacy(inputElement, callback, FileChooserProgressCallbackImpl.instance);
			break;
		default:
			throw new UnsupportedOperationException();
		}
	}

	@JSBody(params = { }, script = "return (typeof File.prototype.arrayBuffer === \"function\") ? 0 : 1;")
	private static native int getFileChooserImpl();

	@JSBody(params = { "inputElement", "value" }, script = "inputElement.accept = value;")
	private static native void setAcceptSelection(HTMLInputElement inputElement, String value);

	@JSBody(params = { "inputElement", "enable" }, script = "inputElement.multiple = enable;")
	private static native void setMultipleSelection(HTMLInputElement inputElement, boolean enable);

	public static void displayFileChooser(String mime, String ext) {
		clearFileChooserResult();
		final HTMLDocument doc = docEl();
		final boolean isLikelyMobileBrowser = PlatformInput.isTouchClient();
		if(isLikelyMobileBrowser) {
			final HTMLElement element = fileChooserMobileElement = doc.createElement("div");
			element.getClassList().add("_eaglercraftX_mobile_file_chooser_popup");
			CSSStyleDeclaration decl = element.getStyle();
			decl.setProperty("position", "absolute");
			decl.setProperty("background-color", "white");
			decl.setProperty("font-family", "sans-serif");
			decl.setProperty("top", "10%");
			decl.setProperty("left", "10%");
			decl.setProperty("right", "10%");
			decl.setProperty("border", "5px double black");
			decl.setProperty("padding", "15px");
			decl.setProperty("text-align", "left");
			decl.setProperty("font-size", "20px");
			decl.setProperty("user-select", "none");
			decl.setProperty("z-index", "150");
			final HTMLElement fileChooserTitle = doc.createElement("h3");
			fileChooserTitle.appendChild(doc.createTextNode("File Chooser"));
			element.appendChild(fileChooserTitle);
			final HTMLElement inputElementContainer = doc.createElement("p");
			final HTMLInputElement inputElement = fileChooserElement = (HTMLInputElement) doc.createElement("input");
			inputElement.setType("file");
			if(mime == null) {
				setAcceptSelection(inputElement, "." + ext);
			}else {
				setAcceptSelection(inputElement, mime);
			}
			setMultipleSelection(inputElement, false);
			inputElementContainer.appendChild(inputElement);
			element.appendChild(inputElementContainer);
			final HTMLElement fileChooserButtons = doc.createElement("p");
			final HTMLElement fileChooserButtonCancel = doc.createElement("button");
			fileChooserButtonCancel.setAttribute("type", "button");
			fileChooserButtonCancel.getClassList().add("_eaglercraftX_mobile_file_chooser_btn_cancel");
			fileChooserButtonCancel.getStyle().setProperty("font-size", "1.0em");
			fileChooserButtonCancel.addEventListener("click", new EventListener<Event>() {
				@Override
				public void handleEvent(Event evt) {
					evt.preventDefault();
					evt.stopPropagation();
					if(fileChooserMobileElement == element) {
						parentEl().removeChild(element);
						fileChooserMobileElement = null;
						fileChooserElement = null;
						FileChooserCallbackImpl.instance.accept(null, null);
					}
				}
			});
			fileChooserButtonCancel.appendChild(doc.createTextNode("Cancel"));
			fileChooserButtons.appendChild(fileChooserButtonCancel);
			fileChooserButtons.appendChild(doc.createTextNode(" "));
			final HTMLElement fileChooserButtonDone = doc.createElement("button");
			fileChooserButtonDone.setAttribute("type", "button");
			fileChooserButtonDone.getClassList().add("_eaglercraftX_mobile_file_chooser_btn_done");
			fileChooserButtonDone.getStyle().setProperty("font-size", "1.0em");
			fileChooserButtonDone.getStyle().setProperty("font-weight", "bold");
			fileChooserButtonDone.addEventListener("click", new EventListener<Event>() {
				@Override
				public void handleEvent(Event evt) {
					evt.preventDefault();
					evt.stopPropagation();
					if(fileChooserMobileElement == element) {
						getFileChooserResult(inputElement, FileChooserCallbackImpl.instance);
						parentEl().removeChild(element);
						fileChooserMobileElement = null;
						fileChooserElement = null;
					}
				}
			});
			fileChooserButtonDone.appendChild(doc.createTextNode("Done"));
			fileChooserButtons.appendChild(fileChooserButtonDone);
			element.appendChild(fileChooserButtons);
			parentEl().appendChild(element);
		}else {
			final HTMLInputElement inputElement = fileChooserElement = (HTMLInputElement) doc.createElement("input");
			inputElement.setType("file");
			CSSStyleDeclaration decl = inputElement.getStyle();
			decl.setProperty("position", "absolute");
			decl.setProperty("left", "0px");
			decl.setProperty("top", "0px");
			decl.setProperty("z-index", "-100");
			if(mime == null) {
				setAcceptSelection(inputElement, "." + ext);
			}else {
				setAcceptSelection(inputElement, mime);
			}
			setMultipleSelection(inputElement, false);
			inputElement.addEventListener("change", new EventListener<Event>() {
				@Override
				public void handleEvent(Event evt) {
					if(fileChooserElement == inputElement) {
						getFileChooserResult(inputElement, FileChooserCallbackImpl.instance);
						parentEl().removeChild(inputElement);
						fileChooserElement = null;
					}
				}
			});
			parentEl().appendChild(inputElement);
			// Keep this inside the button event's user-activation window. Delaying the
			// click causes current Chromium builds to silently block the file picker.
			inputElement.click();
		}
	}

	public static boolean fileChooserHasResult() {
		return fileChooserHasResult;
	}

	public static FileChooserResult getFileChooserResult() {
		fileChooserHasResult = false;
		FileChooserResult res = fileChooserResultObject;
		fileChooserResultObject = null;
		return res;
	}

	public static boolean fileChooserIsReading() {
		return fileChooserIsReading;
	}

	public static String fileChooserReadingName() {
		return fileChooserReadingName;
	}

	public static long fileChooserBytesRead() {
		return fileChooserBytesRead;
	}

	public static long fileChooserBytesTotal() {
		return fileChooserBytesTotal;
	}

	private static final String faviconURLString = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAAFiUAABYlAUlSJPAAAAR/SURBVEhLtZXZK3ZRFMYPcqXc+gv413DHxVuGIpIhkciQWaRccCNjSCkligwXSOZ5nmfv9zvn2e8+58V753sudmuvvdZ61l5r7XOc8H+GS/D19aUNkPz5+aktQH5/f//4+LBKZKuRkpUtQjCUYG5gD2T38vLy/PwsDfL9/f3Dw8PT05M0b29vnKLhCKCBT4L4gvBLBIei4//4+Hh1dUVEQutUuLu7E83FxQUGnKLBWKfQaA3S+AREVxaEOD8/Pzk50XpzcyMDcH19zdZG3N3d3dzc3Nvb01aX5pQUpQGGQJxcQpfNysoKhUIdHR1o1tbWbInYAgxIPDMzMy8vLzc3FxqOdMoRqwJK8G8ALUYIhHMiSEhIwI6CyIb0qQzC4eGhsXCc1tZWnZIEKzdQJQSXgKxfX18RCM3Z5eWlcfVAxKOjo+Pj49PTU88lTOk2NjbMsePc3t6SAfcgFdszOyMuAdeBg0CQi2lhYUHOeOLDCisN8FzcPFZXV3t7ezHY3t5GQ+6it+2xMASsKhEEWKsmRLRBBUpPvpJ/TpFKFBwKYAiITmicsbYhdHfJAltqhUCVsCQhwslmeXmZxiBQT9c0Ar9E2O3v72sYSE0N1yQArkKy0kBMXLqlZqIZHR3t6empqqqSDcBdhXEJSJ/bUc3q6uq+vj629GB9fR1WsLW1NTs7u7S0RN2locMjIyOEm5ubQ7+4uJienk4/+vv77Y1hwhLBEKhwWHitdVFfX9/Y2Gg2HuLi4owUAysrK8yCG97rh0+ApP5Q2ZycHFlPTExUVFRIBvn5+WhKSkp2dnaMKhptbW2426GgQ/rwuAQCZ1hwFayLiork9hMFBQV1dXVmE0BLS4vqw3QFB8kn4IAxoGPkYpxi4FeDmpqas7Mz4pClAgqGwD48rjY2NmacYqC0tJQ1KSlJWyE5OZkpUKkBAxZVIntAoZh04+Q48fHxPNGBgYHExMT29naj9cBodnZ2mo3jlJWVMeW2OGQck4B1amqqoaGhqamJjx2lGxwcpL0mUgR8fJhsWqJtSkoKU2SbHHUDpkhPBujd8xuQG6PJRM/Pz09PT7O1NNnZ2Tw3fgZkXVhYKCUlUhBATP+hCVyKZGky17RV0g04laayslJ6hlVeFHB4eFhKaogGd0LxtmTgE+hbhKDnPjMzgw8E3qGL2tpaBWpubjYqj2BoaEj6rq4uNATRZ0ZwCbiL6gXEzINk5vCBQJ9rMD4+rkA8QNK036uDg4Py8vLu7m680KjIBNR3zBDoWQM1g98snyB+VSoRW8C/UwR81/SvhgNj9JOTkwwVERUdRBEI0BAdLRVERkhLS8vIyEDQlrsTPTU1lVFhKxARvZgUlFLbegCf4BvIsbi4mIg4E5EogIHhiKCMtU0WUFiVy06j5fAJIDdSBDQw+PegDfBRcbOPwH4F9LuFWIIQdQNKwWqzIE0aoFUaBsw+SQuFw0uNtC9A+F4i3QNrbg3IDn+SAsHh+wYiEpeyBEMLv/cAO6KzAijxxB+Y4wisBhssJUhjEbPJf4Nw+B+JXqLW3bw+wQAAAABJRU5ErkJggg==";

	public static String faviconURLTeaVM() {
		return faviconURLString.substring(0);
	}

	@JSBody(params = { "doc", "str" }, script = "doc.write(str);doc.close();")
	private static native void documentWrite(HTMLDocument doc, String str);

	public static void openCreditsPopup(String text) {
		Window currentWin = winEl();

		int w = (int)(850 * PlatformInput.getDPI());
		int h = (int)(700 * PlatformInput.getDPI());

		int x = (currentWin.getScreen().getWidth() - w) / 2;
		int y = (currentWin.getScreen().getHeight() - h) / 2;

		Window newWin = Window.current().open("", "_blank", "top=" + y + ",left=" + x + ",width=" + w + ",height=" + h + ",menubar=0,status=0,titlebar=0,toolbar=0");
		if(newWin == null || isNotTruthy(newWin)) {
			Window.alert("ERROR: Popup blocked!\n\nPlease make sure you have popups enabled for this site!");
			return;
		}

		newWin.focus();
		documentWrite(newWin.getDocument(), "<!DOCTYPE html><html><head><meta charset=\"UTF-8\" />"
				+ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" /><title>EaglercraftX 1.8 Credits</title>"
				+ "<link type=\"image/png\" rel=\"shortcut icon\" href=\"" + PlatformApplication.faviconURLTeaVM() + "\" />"
						+ "</head><body><pre style=\"font:15px Consolas,monospace;\">" + text + "</pre></body></html>");
	}

	public static void clearFileChooserResult() {
		fileChooserHasResult = false;
		fileChooserResultObject = null;
		fileChooserIsReading = false;
		fileChooserReadingName = "";
		fileChooserBytesRead = 0L;
		fileChooserBytesTotal = 0L;
		if(fileChooserMobileElement != null) {
			parentEl().removeChild(fileChooserMobileElement);
			fileChooserMobileElement = null;
			fileChooserElement = null;
		}else if(fileChooserElement != null) {
			parentEl().removeChild(fileChooserElement);
			fileChooserElement = null;
		}
	}

	@JSFunctor
	static interface DownloadBytesBlobURLRevoke extends JSObject {
		void call();
	}

	@JSBody(params = { "name", "url", "revokeFunc", "parentElement" }, script =
			"var vigg = function(el){" +
			"el.style.position = \"absolute\";" +
			"el.style.left = \"0px\";" +
			"el.style.top = \"0px\";" +
			"el.style.zIndex = \"-100\";" +
			"el.style.color = \"transparent\";" +
			"el.innerText = \"Download File\";" +
			"el.href = url;" +
			"el.target = \"_blank\";" +
			"el.download = name;" +
			"parentElement.appendChild(el);" +
			"setTimeout(function() { el.click();" +
			"setTimeout(function() { parentElement.removeChild(el); }, 50);" +
			"setTimeout(function() { revokeFunc(); }, 60000);" +
			"}, 50);" +
			"}; vigg(document.createElement(\"a\"));")
	private static native void downloadBytesImpl(String str, String url, DownloadBytesBlobURLRevoke revokeFunc, HTMLElement parentElement);

	public static void downloadFileWithName(String str, byte[] dat) {
		TeaVMBlobURLHandle blobHandle = TeaVMBlobURLManager.registerNewURLByte(dat, "application/octet-stream");
		downloadBytesImpl(str, blobHandle.toExternalForm(), blobHandle::release, parentEl());
	}

	public static void downloadFileWithNameTeaVM(String str, ArrayBuffer dat) {
		TeaVMBlobURLHandle blobHandle = TeaVMBlobURLManager.registerNewURLArrayBuffer(dat, "application/octet-stream");
		downloadBytesImpl(str, blobHandle.toExternalForm(), blobHandle::release, parentEl());
	}

	static void downloadURLWithNameTeaVM(String str, String url, DownloadBytesBlobURLRevoke revoker) {
		downloadBytesImpl(str, url, revoker, parentEl());
	}

	public static void showDebugConsole() {
		DebugConsoleWindow.showDebugConsole();
	}

	public static void addLogMessage(String text, boolean err) {
		DebugConsoleWindow.addLogMessage(text, err);
	}

	public static boolean isShowingDebugConsole() {
		return DebugConsoleWindow.isShowingDebugConsole();
	}

	@JSBody(params = { "str" }, script = "window.minecraftServer = str;")
	public static native void setMCServerWindowGlobal(String str);

}

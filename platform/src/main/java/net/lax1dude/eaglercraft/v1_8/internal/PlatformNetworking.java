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

package net.lax1dude.eaglercraft.v1_8.internal;

import java.util.Locale;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;

public class PlatformNetworking {

	/**
	 * Browser mixed-content policy permits insecure WebSockets from non-HTTPS
	 * pages.  Loopback endpoints are also a browser-approved local exception on
	 * secure pages, which is useful for a local relay fixture.  Keep this check
	 * in the shared networking seam so relay editing and the actual socket open
	 * use the same policy.
	 */
	public static boolean isInsecureWebSocketAllowed(String socketURI) {
		String value = socketURI == null ? null : socketURI.trim();
		if (value == null || !value.regionMatches(true, 0, "ws://", 0, 5)) {
			return true;
		}
		return !EagRuntime.requireSSL() || isLoopbackWebSocketHost(value);
	}

	private static boolean isLoopbackWebSocketHost(String socketURI) {
		int start = 5;
		int end = socketURI.length();
		for (int i = start; i < end; ++i) {
			char c = socketURI.charAt(i);
			if (c == '/' || c == '?' || c == '#') {
				end = i;
				break;
			}
		}
		String authority = socketURI.substring(start, end);
		if (authority.isEmpty() || authority.indexOf('@') >= 0) {
			return false;
		}
		String host;
		if (authority.charAt(0) == '[') {
			int close = authority.indexOf(']');
			if (close <= 1) {
				return false;
			}
			host = authority.substring(1, close);
		} else {
			int colon = authority.lastIndexOf(':');
			host = colon > 0 && authority.indexOf(':') == colon
				? authority.substring(0, colon) : authority;
		}
		host = host.toLowerCase(Locale.ROOT);
		if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("::1")) {
			return true;
		}
		if (!host.startsWith("127.")) {
			return false;
		}
		String[] octets = host.substring(4).split("\\.", -1);
		if (octets.length != 3) {
			return false;
		}
		for (String octet : octets) {
			if (octet.isEmpty()) {
				return false;
			}
			int value = 0;
			for (int i = 0; i < octet.length(); ++i) {
				char c = octet.charAt(i);
				if (c < '0' || c > '9') {
					return false;
				}
				value = value * 10 + c - '0';
				if (value > 255) {
					return false;
				}
			}
		}
		return true;
	}

	public static native IWebSocketClient openWebSocket(String socketURI);

	public static native IWebSocketClient openWebSocketUnsafe(String socketURI);

	public static native IWebSocketClient openWebSocketImpl(String socketURI);

	public static native boolean isWispcraftLoaded();

	public static native String getWispcraftWispURL();

	public static native void openWispcraftSettings();

	public static native String getWispcraftAccountProfile();

	public static native String joinWispcraftServer(String uuid, String digest);

	public static native boolean isIsolatedWebApp();

	public static native String getIsolatedWebAppBundleURL();

	public static native boolean supportsDirectTCP();

	public static native IWebSocketClient openDirectTCP(String host, int port);

}

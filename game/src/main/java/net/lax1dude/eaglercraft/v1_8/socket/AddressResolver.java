/*
 * Copyright (c) 2022-2023 lax1dude, ayunami2000. All Rights Reserved.
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

package net.lax1dude.eaglercraft.v1_8.socket;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Copied from the 1.8 workspace; 26.2's ServerAddress wraps guava
 * HostAndPort instead of holding the raw URI, so the URI-splitting half
 * lives as {@link #stripWebSocketScheme}/{@link #defaultWebSocketPort}
 * helpers consumed by ServerAddress.parseString/isValidAddress.
 */
public class AddressResolver {

	public static String resolveURI(ServerData input) {
		return resolveURI(input.ip);
	}

	public static String resolveURI(String input) {
		if (isWispURI(input)) return input;
		String lc = input.toLowerCase();
		if(!lc.startsWith("ws://") && !lc.startsWith("wss://")) {
			if(EagRuntime.requireSSL()) {
				input = "wss://" + input;
			}else {
				input = "ws://" + input;
			}
		}
		return input;
	}

	/**
	 * @return host[:port] with the ws:// or wss:// scheme and any path
	 *         component removed, or null if the input has no such scheme
	 */
	public static String stripWebSocketScheme(String input) {
		String lc = input.toLowerCase();
		if(lc.startsWith("ws://")) {
			input = input.substring(5);
		}else if(lc.startsWith("wss://")) {
			input = input.substring(6);
		}else {
			return null;
		}
		int i = input.indexOf('/');
		if(i != -1) {
			input = input.substring(0, i);
		}
		return input;
	}

	public static int defaultWebSocketPort() {
		return EagRuntime.requireSSL() ? 443 : 80;
	}

	public static boolean isWispURI(String uri) {
		return uri != null && uri.startsWith("eagler-wisp:");
	}

	public static String buildWispURI(String relay, String target) {
		return "eagler-wisp:" + percentEncode(relay.trim()) + "?target=" + percentEncode(target.trim());
	}

	public static String extractWispEndpoint(String uri) {
		if (!isWispURI(uri)) throw new IllegalArgumentException("Expected Wisp transport URI");
		int query = uri.indexOf('?');
		return percentDecode(uri.substring("eagler-wisp:".length(), query < 0 ? uri.length() : query));
	}

	public static String buildProxyURI(String relay, String target, String token) {
		String uri = directRelayEndpoint(relay);
		char separator = uri.indexOf('?') == -1 ? '?' : '&';
		uri = uri + separator + "target=" + percentEncode(target.trim());
		if (token != null && !token.isBlank()) {
			uri += "&token=" + percentEncode(token.trim());
		}
		return uri;
	}

	public static String buildEaglerXURI(String address) {
		String uri = address == null ? "" : address.trim();
		String lower = uri.toLowerCase();
		if(lower.startsWith("wss://")) {
			return "wss://" + uri.substring(6);
		}
		if(lower.startsWith("ws://")) {
			return "ws://" + uri.substring(5);
		}
		return "wss://" + uri;
	}

	/** Build an explicit direct EaglerX WebSocket URI for the server editor. */
	public static String buildEaglerXURI(String address, boolean secure) {
		String uri = address == null ? "" : address.trim();
		String lower = uri.toLowerCase();
		if(lower.startsWith("wss://")) {
			uri = uri.substring(6);
		}else if(lower.startsWith("ws://")) {
			uri = uri.substring(5);
		}
		return (secure ? "wss://" : "ws://") + uri;
	}

	/**
	 * Return equivalent public EaglerX endpoints in failover order.  ArchMC has
	 * historically published several names for the same listener.  Its short
	 * {@code arch.mc} name is currently split across Cloudflare origins, so one
	 * edge can return HTTP 521 while the legacy name is healthy.  The 1.8 client
	 * appears healthy in that situation because it retains the last MOTD; a new
	 * 26.2 connection must actually retry a live endpoint.
	 */
	public static List<String> eaglerXConnectionCandidates(String address) {
		String primary = buildEaglerXURI(address);
		String host = webSocketHost(primary);
		if (!"arch.mc".equals(host) && !"mc.arch.lol".equals(host)) {
			return Collections.singletonList(primary);
		}
		String suffix = webSocketSuffix(primary);
		ArrayList<String> candidates = new ArrayList<>(2);
		candidates.add(primary);
		String alternate = "arch.mc".equals(host) ? "mc.arch.lol" : "arch.mc";
		candidates.add((primary.toLowerCase(Locale.ROOT).startsWith("ws://") ? "ws://" : "wss://") + alternate + suffix);
		return candidates;
	}

	/** True when an address is already an Eagler WebSocket rather than a Java target. */
	public static boolean looksLikeEaglerXAddress(String address) {
		if (address == null) {
			return false;
		}
		String lower = address.trim().toLowerCase(Locale.ROOT);
		return (lower.startsWith("ws://") || lower.startsWith("wss://"))
			&& queryValue(lower, "target") == null
			&& !lower.contains("/minecraft");
	}

	private static String webSocketHost(String uri) {
		int scheme = uri.indexOf("://");
		int start = scheme < 0 ? 0 : scheme + 3;
		int end = uri.length();
		for (int i = start; i < uri.length(); ++i) {
			char c = uri.charAt(i);
			if (c == '/' || c == '?' || c == '#') {
				end = i;
				break;
			}
		}
		String authority = uri.substring(start, end).toLowerCase(Locale.ROOT);
		int port = authority.lastIndexOf(':');
		return port > 0 ? authority.substring(0, port) : authority;
	}

	private static String webSocketSuffix(String uri) {
		int scheme = uri.indexOf("://");
		int start = scheme < 0 ? 0 : scheme + 3;
		for (int i = start; i < uri.length(); ++i) {
			char c = uri.charAt(i);
			if (c == '/' || c == '?' || c == '#') {
				return uri.substring(i);
			}
		}
		return "";
	}

	public static String stripEaglerXScheme(String address) {
		String value = address == null ? "" : address.trim();
		String lower = value.toLowerCase();
		if(lower.startsWith("wss://")) {
			return value.substring(6);
		}
		if(lower.startsWith("ws://")) {
			return value.substring(5);
		}
		return value;
	}

	private static String directRelayEndpoint(String relay) {
		String uri = resolveURI(relay.trim());
		int query = uri.indexOf('?');
		if(query >= 0) {
			uri = uri.substring(0, query);
		}
		int scheme = uri.indexOf("://");
		int slash = scheme >= 0 ? uri.indexOf('/', scheme + 3) : -1;
		if(slash == -1) {
			return uri + "/minecraft";
		}
		while(uri.length() > slash + 1 && uri.endsWith("/")) {
			uri = uri.substring(0, uri.length() - 1);
		}
		return uri.length() == slash + 1 ? uri + "minecraft" : uri;
	}

	public static String buildLANHostURI(String relay, String token) {
		String uri = relayEndpoint(relay, "/lan/host");
		if(token != null && !token.isBlank()) {
			uri += "?token=" + percentEncode(token.trim());
		}
		return uri;
	}

	public static String buildLegacyLANHostURI(String relay) {
		return "eagler-p2p-host:" + resolveURI(relay.trim());
	}

	public static String buildLANJoinURI(String relay, String code) {
		return buildLANJoinURI(relay, code, "");
	}

	public static String buildLANJoinURI(String relay, String code, String token) {
		String uri = relayEndpoint(relay, "/lan/join") + "?code=" + percentEncode(code.trim().toUpperCase(Locale.ROOT));
		if(token != null && !token.isBlank()) {
			uri += "&token=" + percentEncode(token.trim());
		}
		return uri;
	}

	public static String buildLegacyLANJoinURI(String relay, String code) {
		return "eagler-p2p-lan:" + resolveURI(relay.trim()) + "#"
				+ code.trim().toUpperCase(Locale.ROOT);
	}

	/** True when the address names the in-browser Eagler 1.8 WebRTC transport. */
	public static boolean isLegacyLANJoinURI(String uri) {
		return uri != null && uri.toLowerCase(Locale.ROOT).startsWith("eagler-p2p-lan:");
	}

	/**
	 * Lightweight edit-box validation. The connection path still performs full
	 * host/port parsing, but browser character responders must not invoke
	 * Guava/IDN substitutions on every key event.
	 */
	public static boolean isValidTargetSyntax(String value) {
		if(value == null) {
			return false;
		}
		String target = value.trim();
		if(target.isEmpty() || target.length() > 255) {
			return false;
		}
		for(int i = 0, length = target.length(); i < length; ++i) {
			if(Character.isWhitespace(target.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static String relayEndpoint(String relay, String path) {
		String uri = resolveURI(relay.trim());
		int query = uri.indexOf('?');
		if(query >= 0) {
			uri = uri.substring(0, query);
		}
		int scheme = uri.indexOf("://");
		int slash = scheme >= 0 ? uri.indexOf('/', scheme + 3) : -1;
		if(slash >= 0) {
			uri = uri.substring(0, slash);
		}
		return uri + path;
	}

	public static String extractTarget(String uri, String fallback) {
		String value = queryValue(uri, "target");
		return value == null || value.isBlank() ? fallback : percentDecode(value);
	}

	public static String extractRelay(String uri) {
		if (isWispURI(uri)) return extractWispEndpoint(uri);
		int query = uri.indexOf('?');
		return query == -1 ? uri : uri.substring(0, query);
	}

	public static String extractToken(String uri) {
		String value = queryValue(uri, "token");
		return value == null ? "" : percentDecode(value);
	}

	public static String extractLANCode(String uri) {
		if(isLegacyLANJoinURI(uri)) {
			int fragment = uri.lastIndexOf('#');
			return fragment >= 0 && fragment + 1 < uri.length()
					? uri.substring(fragment + 1).trim().toUpperCase(Locale.ROOT) : "";
		}
		String value = queryValue(uri, "code");
		return value == null ? "" : percentDecode(value).trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * Retarget an active direct-relay URI after a protocol transfer. Returns
	 * {@code null} for ordinary addresses so desktop/direct connections retain
	 * vanilla transfer behavior.
	 */
	public static String retargetProxyURI(String uri, String target) {
		if(uri == null) {
			return null;
		}
		if (isWispURI(uri)) return buildWispURI(extractWispEndpoint(uri), target);
		String lc = uri.toLowerCase();
		if((!lc.startsWith("ws://") && !lc.startsWith("wss://"))
				|| queryValue(uri, "target") == null) {
			return null;
		}
		return buildProxyURI(extractRelay(uri), target, extractToken(uri));
	}

	private static String queryValue(String uri, String key) {
		int query = uri.indexOf('?');
		if (query == -1) {
			return null;
		}
		String[] parts = uri.substring(query + 1).split("&");
		for (String part : parts) {
			int equals = part.indexOf('=');
			if (equals != -1 && part.substring(0, equals).equals(key)) {
				return part.substring(equals + 1);
			}
		}
		return null;
	}

	private static String percentEncode(String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		StringBuilder out = new StringBuilder(bytes.length);
		for (byte raw : bytes) {
			int b = raw & 255;
			if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9'
					|| b == '-' || b == '_' || b == '.' || b == '~') {
				out.append((char)b);
			} else {
				out.append('%');
				out.append(Character.toUpperCase(Character.forDigit(b >>> 4, 16)));
				out.append(Character.toUpperCase(Character.forDigit(b & 15, 16)));
			}
		}
		return out.toString();
	}

	private static String percentDecode(String value) {
		byte[] bytes = new byte[value.length()];
		int count = 0;
		for (int i = 0; i < value.length();) {
			char c = value.charAt(i);
			if (c == '%' && i + 2 < value.length()) {
				int high = Character.digit(value.charAt(i + 1), 16);
				int low = Character.digit(value.charAt(i + 2), 16);
				if (high >= 0 && low >= 0) {
					bytes[count++] = (byte)((high << 4) | low);
					i += 3;
					continue;
				}
			}
			byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
			for (byte b : encoded) {
				bytes[count++] = b;
			}
			++i;
		}
		return new String(bytes, 0, count, StandardCharsets.UTF_8);
	}

}

package net.lax1dude.eaglercraft.v1_8.sp.relay;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.socket.AddressResolver;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Persistent ordered relay list shared by multiplayer and browser LAN hosting.
 */
public final class RelaySettings {

	private static final String STORAGE_KEY = "relay.list.v3";
	private static final String PREVIOUS_STORAGE_KEY = "relay.list.v2";
	private static final String LEGACY_STORAGE_KEY = "relay.uri";
	private static final String LEGACY_MULTIPLAYER_RELAY =
			"wss://eagler-minecraft-relay.u2471966200.workers.dev";
	private static final String DEFAULT_MULTIPLAYER_RELAY =
			"wss://oxer-26-2-relay-c5f9ad499e09.herokuapp.com";
	private static final String DEFAULT_SINGLEPLAYER_RELAY = "wss://relay.deev.is";
	private static final List<Profile> profiles = new ArrayList<>();
	private static boolean loaded;
	private static Profile wispcraftProfile;

	private RelaySettings() {
	}

	public enum Capability {
		AUTO("Auto"), SINGLEPLAYER("Singleplayer"), MULTIPLAYER("Multiplayer"), BOTH("Both"), WISP("Wisp (Wispcraft)"), UNKNOWN("Checking");

		private final String label;

		Capability(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public boolean supportsSingleplayer() {
			return this == SINGLEPLAYER || this == BOTH;
		}

		public boolean supportsMultiplayer() {
			return this == MULTIPLAYER || this == BOTH || this == WISP;
		}
	}

	public static final class Profile {
		public final String name;
		public final String address;
		public final boolean privateRelay;
		public final String token;
		public final Capability configuredCapability;
		private Capability detectedCapability;

		public Profile(String name, String address, boolean privateRelay, String token) {
			this(name, address, privateRelay, token, Capability.AUTO, probeCapability(address));
		}

		public Profile(String name, String address, boolean privateRelay, String token,
				Capability configuredCapability) {
			this(name, address, privateRelay, token, configuredCapability, probeCapability(address));
		}

		private Profile(String name, String address, boolean privateRelay, String token,
				Capability configuredCapability, Capability detectedCapability) {
			this.name = cleanName(name);
			this.address = configuredCapability == Capability.WISP ? normalizeWispAddress(address) : normalizeAddress(address);
			this.privateRelay = privateRelay && configuredCapability != Capability.WISP;
			this.token = this.privateRelay && token != null ? token.trim() : "";
			this.configuredCapability = configuredCapability == null ? Capability.AUTO : configuredCapability;
			this.detectedCapability = detectedCapability == null ? Capability.UNKNOWN : detectedCapability;
		}

		public String connectionURI(String target) {
			if(this.configuredCapability == Capability.WISP) {
				if(this != RelaySettings.wispcraft()) {
					throw new IllegalStateException("WISP routes must use the current Wispcraft script endpoint");
				}
				return AddressResolver.buildWispURI(address, target);
			}
			return AddressResolver.buildProxyURI(address, target, privateRelay ? token : "");
		}

		public String displayName() {
			return name.isEmpty() ? address : name;
		}

		public Capability capability() {
			return effectiveCapability(configuredCapability, detectedCapability);
		}

		public Capability detectedCapability() {
			return detectedCapability;
		}
	}

	public static List<Profile> all() {
		ensureLoaded();
		return Collections.unmodifiableList(profiles);
	}

	public static boolean isEmpty() {
		ensureLoaded();
		return profiles.isEmpty();
	}

	/** Session-only selection: never write the script's endpoint into saved relays. */
	public static Profile wispcraft() {
		if(!PlatformNetworking.isWispcraftLoaded()) return null;
		String address = PlatformNetworking.getWispcraftWispURL();
		if(!isValidAddress(address)) return null;
		String normalized = normalizeWispAddress(address);
		if(wispcraftProfile == null || !wispcraftProfile.address.equals(normalized)) {
			wispcraftProfile = new Profile("Wispcraft", normalized, false, "", Capability.WISP);
		}
		return wispcraftProfile;
	}

	public static Profile primary() {
		return configuredPrimary();
	}

	private static Profile configuredPrimary() {
		ensureLoaded();
		for(Profile profile : profiles) {
			if(profile.configuredCapability != Capability.WISP
					&& profile.capability().supportsMultiplayer() && isUsable(profile)) return profile;
		}
		return null;
	}

	public static Profile primaryLAN() {
		ensureLoaded();
		for(Profile profile : profiles) {
			if(supportsLANTransport(profile) && isUsable(profile)) return profile;
		}
		return null;
	}

	public static void add(Profile profile) {
		ensureLoaded();
		profiles.add(profile);
		save();
	}

	public static void replace(int index, Profile profile) {
		ensureLoaded();
		profiles.set(index, profile);
		save();
	}

	public static void remove(int index) {
		ensureLoaded();
		if(index >= 0 && index < profiles.size()) {
			profiles.remove(index);
			save();
		}
	}

	public static void move(int index, int delta) {
		ensureLoaded();
		int target = index + delta;
		if(index >= 0 && index < profiles.size() && target >= 0 && target < profiles.size()) {
			Collections.swap(profiles, index, target);
			save();
		}
	}

	public static void setPrimary(int index) {
		ensureLoaded();
		if(index > 0 && index < profiles.size()) {
			Profile profile = profiles.remove(index);
			profiles.add(0, profile);
			save();
		}
	}

	public static List<String> directCandidates(String target) {
		ensureLoaded();
		List<String> result = new ArrayList<>(profiles.size());
		for(Profile profile : profiles) {
			if(profile.configuredCapability != Capability.WISP
					&& profile.capability().supportsMultiplayer() && isUsable(profile)) {
				result.add(profile.connectionURI(target));
			}
		}
		return result;
	}

	/** Build a WISP route only from the endpoint currently supplied by the loaded script. */
	public static List<String> wispCandidates(String target) {
		Profile profile = wispcraft();
		if(profile == null || target == null || target.isBlank()) {
			return Collections.emptyList();
		}
		return Collections.singletonList(profile.connectionURI(target));
	}

	public static List<String> lanJoinCandidates(String code) {
		ensureLoaded();
		List<String> result = new ArrayList<>(profiles.size());
		for(Profile profile : profiles) {
			if(supportsLANTransport(profile) && isUsable(profile)) {
				result.add(usesOpaqueLANTransport(profile)
						? AddressResolver.buildLANJoinURI(profile.address, code,
								profile.privateRelay ? profile.token : "")
						: AddressResolver.buildLegacyLANJoinURI(profile.address, code));
			}
		}
		return result;
	}

	public static List<String> lanHostCandidates() {
		ensureLoaded();
		List<String> result = new ArrayList<>(profiles.size());
		for(Profile profile : profiles) {
			if(supportsLANTransport(profile) && isUsable(profile)) {
				result.add(usesOpaqueLANTransport(profile)
						? AddressResolver.buildLANHostURI(profile.address,
								profile.privateRelay ? profile.token : "")
						: AddressResolver.buildLegacyLANHostURI(profile.address));
			}
		}
		return result;
	}

	/**
	 * Dedicated Eagler P2P signaling relays use the legacy WebRTC packets. Dual
	 * Minecraft/LAN relays expose the opaque /lan/host and /lan/join transport.
	 * Transport selection is independent from list order, so relay.deev.is stays
	 * first and the next compatible relay is tried only after it fails.
	 */
	private static boolean usesOpaqueLANTransport(Profile profile) {
		Capability known = probeCapability(profile.address);
		return known == Capability.BOTH || (known == Capability.UNKNOWN
				&& (profile.configuredCapability == Capability.BOTH
						|| profile.detectedCapability() == Capability.BOTH));
	}

	private static boolean supportsLANTransport(Profile profile) {
		return supportsLANCapability(profile.configuredCapability, profile.detectedCapability());
	}

	public static List<String> connectionCandidates(String currentUri, String fallbackTarget) {
		return connectionCandidates(currentUri, fallbackTarget, null);
	}

	/** Resolve candidates for an explicit saved mode; WISP never falls back to RELAY. */
	public static List<String> connectionCandidates(String currentUri, String fallbackTarget,
			ServerData.ConnectionMode mode) {
		LinkedHashSet<String> ordered = new LinkedHashSet<>();
		// A saved server entry retains the target or LAN code, not authority to keep
		// using a relay profile after the user removes it. Always rebuild transport
		// URIs from the currently configured profiles so delete/edit takes effect
		// immediately and stale tokens cannot remain active through old entries.
		String lanCode = AddressResolver.extractLANCode(currentUri);
		if(!lanCode.isEmpty()) {
			if(mode == ServerData.ConnectionMode.WISP) {
				return Collections.emptyList();
			}
			ordered.addAll(lanJoinCandidates(lanCode));
		}else {
			String target = AddressResolver.extractTarget(currentUri, fallbackTarget);
			if(target != null && !target.isBlank()) {
				if(mode == ServerData.ConnectionMode.WISP
						|| mode == null && AddressResolver.isWispURI(currentUri)) {
					ordered.addAll(wispCandidates(target));
				}else if(AddressResolver.isWispURI(currentUri)) {
					// A WISP URI is never valid evidence for ordinary RELAY mode.
					return Collections.emptyList();
				}else {
					ordered.addAll(directCandidates(target));
				}
			}
		}
		return new ArrayList<>(ordered);
	}

	/**
	 * Validate the persisted relay syntax without applying the current page's
	 * browser security policy. A ws:// profile must survive a later reload from
	 * an HTTPS page so it can be repaired or used again from HTTP/offline mode.
	 */
	public static boolean isValidAddressSyntax(String address) {
		if(address == null) {
			return false;
		}
		String value = address.trim();
		String lower = value.toLowerCase(java.util.Locale.ROOT);
		int authorityStart;
		if(lower.startsWith("wss://")) {
			authorityStart = 6;
		}else if(lower.startsWith("ws://")) {
			authorityStart = 5;
		}else {
			return false;
		}
		if(authorityStart == value.length() || value.length() > 2048) {
			return false;
		}
		int authorityEnd = value.length();
		for(int i = authorityStart; i < value.length(); ++i) {
			char c = value.charAt(i);
			if(Character.isWhitespace(c) || Character.isISOControl(c)) {
				return false;
			}
			if(c == '/' || c == '?' || c == '#') {
				authorityEnd = i;
				break;
			}
		}
		String authority = value.substring(authorityStart, authorityEnd);
		if(authority.isEmpty() || authority.indexOf('@') >= 0) {
			return false;
		}
		String port = null;
		if(authority.charAt(0) == '[') {
			int bracket = authority.indexOf(']');
			if(bracket <= 1) {
				return false;
			}
			if(bracket + 1 < authority.length()) {
				if(authority.charAt(bracket + 1) != ':') {
					return false;
				}
				port = authority.substring(bracket + 2);
			}
		}else {
			int colon = authority.lastIndexOf(':');
			if(colon >= 0) {
				if(authority.indexOf(':') != colon || colon == 0) {
					return false;
				}
				port = authority.substring(colon + 1);
				authority = authority.substring(0, colon);
			}
			if(authority.isEmpty()) {
				return false;
			}
		}
		if(port != null) {
			if(port.isEmpty() || port.length() > 5) {
				return false;
			}
			int portNumber = 0;
			for(int i = 0; i < port.length(); ++i) {
				char c = port.charAt(i);
				if(c < '0' || c > '9') {
					return false;
				}
				portNumber = portNumber * 10 + c - '0';
			}
			if(portNumber < 1 || portNumber > 65535) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Validate a relay for immediate use in this runtime. Explicit ws:// is
	 * rejected on HTTPS pages unless its authority is loopback, matching the
	 * browser's mixed-content boundary; HTTP, file/offline and desktop contexts
	 * keep the user's selected insecure scheme available.
	 */
	public static boolean isValidAddress(String address) {
		String normalized = address == null ? null : address.trim();
		return isValidAddressSyntax(normalized)
			&& PlatformNetworking.isInsecureWebSocketAllowed(normalized);
	}

	public static boolean isMixedContentBlocked(String address) {
		String normalized = address == null ? null : address.trim();
		return isValidAddressSyntax(normalized)
			&& normalized.regionMatches(true, 0, "ws://", 0, 5)
			&& !PlatformNetworking.isInsecureWebSocketAllowed(normalized);
	}

	/**
	 * Export the saved relay list in the same JSON representation used by local
	 * storage.  The explicit scheme is part of each address and is never
	 * inferred from the current page protocol.
	 */
	public static String exportProfiles() {
		ensureLoaded();
		return serializeProfiles().toString();
	}

	/**
	 * Import a previously exported relay list atomically.  Syntax validation is
	 * deliberately independent of the current browser mixed-content policy so a
	 * ws:// entry can be repaired later from an HTTP, file, or local context.
	 */
	public static boolean importProfiles(String serialized) {
		ensureLoaded();
		if(serialized == null) {
			return false;
		}
		try {
			JSONArray array = new JSONArray(serialized);
			List<Profile> imported = new ArrayList<>(array.length());
			for(int i = 0; i < array.length(); ++i) {
				JSONObject object = array.getJSONObject(i);
				String address = object.optString("address", "");
				if(!isValidAddressSyntax(address)) {
					return false;
				}
				Capability configured = parseCapability(object.optString("capability", "auto"), Capability.AUTO);
				Capability detected = object.has("detected")
						? parseCapability(object.optString("detected", "unknown"), probeCapability(address))
						: probeCapability(address);
				imported.add(new Profile(object.optString("name", ""), address,
						object.optBoolean("private", false), object.optString("token", ""), configured, detected));
			}
			profiles.clear();
			profiles.addAll(imported);
			save();
			return true;
		}catch(Throwable ignored) {
			return false;
		}
	}

	private static boolean isUsable(Profile profile) {
		return profile != null && isValidAddress(profile.address);
	}

	private static void ensureLoaded() {
		if(loaded) {
			return;
		}
		loaded = true;
		byte[] stored = EagRuntime.getStorage(STORAGE_KEY);
		byte[] previous = stored == null ? EagRuntime.getStorage(PREVIOUS_STORAGE_KEY) : null;
		boolean migrating = stored == null && previous != null;
		if(stored == null) stored = previous;
		byte[] legacy = null;
		if(stored != null) {
			try {
				JSONArray array = new JSONArray(new String(stored, StandardCharsets.UTF_8));
				for(int i = 0; i < array.length(); ++i) {
					JSONObject object = array.getJSONObject(i);
					String address = object.optString("address", "");
					if(isValidAddressSyntax(address)) {
						Capability configured = parseCapability(object.optString("capability", "auto"), Capability.AUTO);
						Capability detected = parseCapability(object.optString("detected", "unknown"), probeCapability(address));
						profiles.add(new Profile(object.optString("name", ""), address,
								object.optBoolean("private", false), object.optString("token", ""), configured, detected));
					}
				}
			}catch(Throwable ignored) {
				profiles.clear();
			}
		}
		if(profiles.isEmpty()) {
			legacy = EagRuntime.getStorage(LEGACY_STORAGE_KEY);
			if(legacy != null) {
				String address = new String(legacy, StandardCharsets.UTF_8).trim();
				if(isValidAddressSyntax(address)) {
					profiles.add(new Profile("Primary relay", address, false, ""));
					save();
				}
			}
		}
		boolean replacedLegacyDefault = false;
		for(int i = 0; i < profiles.size(); ++i) {
			Profile profile = profiles.get(i);
			if(sameEndpoint(profile.address, LEGACY_MULTIPLAYER_RELAY)) {
				String name = "Cloudflare public relay".equals(profile.name)
						? "Heroku public relay" : profile.name;
				Capability detected = profile.detectedCapability() == Capability.UNKNOWN
						? Capability.BOTH : profile.detectedCapability();
				String replacement = AddressResolver.buildEaglerXURI(DEFAULT_MULTIPLAYER_RELAY,
						!profile.address.trim().regionMatches(true, 0, "ws://", 0, 5));
				profiles.set(i, new Profile(name, replacement, profile.privateRelay,
						profile.token, profile.configuredCapability, detected));
				replacedLegacyDefault = true;
			}
		}
		// Ship a working WSS relay only for a genuinely new browser profile. An
		// explicitly saved empty list means the user removed every relay and must
		// stay empty on the next launch.
		if(profiles.isEmpty() && stored == null && legacy == null) {
			profiles.add(new Profile("Eagler P2P relay", DEFAULT_SINGLEPLAYER_RELAY, false, "",
					Capability.AUTO, Capability.SINGLEPLAYER));
			profiles.add(new Profile("Heroku public relay", DEFAULT_MULTIPLAYER_RELAY, false, "",
					Capability.AUTO, Capability.BOTH));
			save();
		}else if(migrating && !profiles.isEmpty()) {
			boolean hasDedicatedP2P = false;
			for(Profile profile : profiles) {
				hasDedicatedP2P |= probeCapability(profile.address) == Capability.SINGLEPLAYER;
			}
			if(!hasDedicatedP2P) {
				profiles.add(0, new Profile("Eagler P2P relay", DEFAULT_SINGLEPLAYER_RELAY, false, "",
						Capability.AUTO, Capability.SINGLEPLAYER));
			}
			save();
		}else if(replacedLegacyDefault) {
			save();
		}
	}

	private static void save() {
		JSONArray array = serializeProfiles();
		EagRuntime.setStorage(STORAGE_KEY, array.toString().getBytes(StandardCharsets.UTF_8));
		Profile primary = configuredPrimary();
		EagRuntime.setStorage(LEGACY_STORAGE_KEY,
				(primary == null ? "" : primary.address).getBytes(StandardCharsets.UTF_8));
	}

	private static JSONArray serializeProfiles() {
		JSONArray array = new JSONArray();
		for(Profile profile : profiles) {
			JSONObject object = new JSONObject();
			object.put("name", profile.name);
			object.put("address", profile.address);
			object.put("private", profile.privateRelay);
			object.put("capability", profile.configuredCapability.name().toLowerCase(java.util.Locale.ROOT));
			object.put("detected", profile.detectedCapability().name().toLowerCase(java.util.Locale.ROOT));
			if(profile.privateRelay && !profile.token.isEmpty()) {
				object.put("token", profile.token);
			}
			array.put(object);
		}
		return array;
	}

	private static String cleanName(String name) {
		return name == null ? "" : name.trim();
	}

	private static String normalizeWispAddress(String address) {
		String value = address == null ? "" : address.trim();
		int query = value.indexOf('?');
		String path = query < 0 ? value : value.substring(0, query);
		return (path.endsWith("/") ? path : path + "/") + (query < 0 ? "" : value.substring(query));
	}

	private static String normalizeAddress(String address) {
		if(address == null) {
			return "";
		}
		String value = address.trim();
		while(value.endsWith("/") && value.length() > 6) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	/**
	 * Resolve the capability of a built-in relay endpoint without opening a
	 * socket.  This is intentionally a deterministic endpoint probe: relay
	 * settings are loaded on the UI thread, so a network probe here would make
	 * opening the settings screen depend on remote latency.  The Profile
	 * constructors and storage importer call this method, and the focused test
	 * exercises those calls directly.
	 */
	static Capability probeCapability(String address) {
		String normalized = normalizeAddress(address).toLowerCase(java.util.Locale.ROOT);
		String endpoint = AddressResolver.stripEaglerXScheme(normalized);
		if(endpoint.equals(AddressResolver.stripEaglerXScheme(DEFAULT_MULTIPLAYER_RELAY.toLowerCase(java.util.Locale.ROOT)))
				|| endpoint.equals(AddressResolver.stripEaglerXScheme(LEGACY_MULTIPLAYER_RELAY.toLowerCase(java.util.Locale.ROOT)))) {
			return Capability.BOTH;
		}
		if(normalized.contains("relay.deev.is") || normalized.contains("relay.lax1dude.net")
				|| normalized.contains("relay.shhnowisnottheti.me")) return Capability.SINGLEPLAYER;
		return Capability.UNKNOWN;
	}

	private static boolean sameEndpoint(String first, String second) {
		return AddressResolver.stripEaglerXScheme(normalizeAddress(first))
				.equalsIgnoreCase(AddressResolver.stripEaglerXScheme(normalizeAddress(second)));
	}

	private static Capability parseCapability(String value, Capability fallback) {
		try {
			return Capability.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
		}catch(Throwable ignored) {
			return fallback;
		}
	}

	static Capability effectiveCapability(Capability configured, Capability detected) {
		if(configured != Capability.AUTO && configured != Capability.UNKNOWN) {
			return configured;
		}
		return detected == Capability.UNKNOWN ? Capability.MULTIPLAYER : detected;
	}

	static boolean supportsLANCapability(Capability configured, Capability detected) {
		return effectiveCapability(configured, detected).supportsSingleplayer();
	}
}

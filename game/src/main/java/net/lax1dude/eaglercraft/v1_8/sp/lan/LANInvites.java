package net.lax1dude.eaglercraft.v1_8.sp.lan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.lax1dude.eaglercraft.v1_8.lan.AutoConnect;
import net.lax1dude.eaglercraft.v1_8.lan.LANDiscovery;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.SingleplayerServerController26;
import net.lax1dude.eaglercraft.v1_8.sp.gui.EaglerAutoConnectCodeScreen;
import net.lax1dude.eaglercraft.v1_8.sp.gui.EaglerDirectConnectHostScreen;
import net.lax1dude.eaglercraft.v1_8.sp.gui.EaglerDirectConnectJoinScreen;
import net.lax1dude.eaglercraft.v1_8.sp.gui.EaglerLANInviteScreen;
import net.lax1dude.eaglercraft.v1_8.sp.gui.EaglerLANPeersScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Game-side half of automatic same-network multiplayer.
 *
 * <p>The browser module ({@code lan-discovery.js}) finds the other players on
 * this network and keeps a direct WebRTC data channel open to each of them
 * before the client has finished loading. That would be useless on its own: a
 * data channel is not a Minecraft connection. This class is what turns it into
 * gameplay - it reads the discovered peers, lets a host invite one of them from
 * the "online" list, shows the invitee an in-game notification they can accept
 * or decline, and hands the accepted invite to the existing direct-connect
 * transport, so no join code is ever copied by hand.
 *
 * <p>The transport itself is untouched: an invite carries the ordinary
 * direct-connect offer code, and the guest's answer code travels back over the
 * same data channel instead of through a chat window. Accepting an invite is
 * therefore exactly the manual flow with the two copies the player used to make
 * removed.
 *
 * <p>Every entry point is a no-op when the page has no discovery service (a
 * plain static host, the desktop build), so the manual join-code path stays the
 * only path there.
 */
public final class LANInvites {

	/** Version of the invite envelope this class speaks. */
	public static final int VERSION = 1;

	private static final int MAX_INVITE_BACKLOG = 8;
	private static final int INVITE_EXPIRY_TICKS = 1200;
	private static final int MAX_TICK_FAILURES = 60;

	/**
	 * Peer-id prefix for a player the serverless AutoConnect module found. The
	 * two peer sources share one list, and the prefix routes a message back to
	 * the module that owns that peer.
	 */
	private static final String AUTO_PREFIX = "ac:";

	private static boolean disabled;
	private static int failures;
	private static boolean autoConnectPrompted;

	private static final Logger logger = LogManager.getLogger("LANInvites");

	public static final class Peer {

		public final String id;
		public final String name;
		public final String state;
		public final String addr;
		public final int rttMs;

		Peer(String id, String name, String state, String addr, int rttMs) {
			this.id = id;
			this.name = name;
			this.state = state;
			this.addr = addr;
			this.rttMs = rttMs;
		}

		/** True once the direct data channel to this peer is usable. */
		public boolean isOpen() {
			return "open".equals(state);
		}

		public String displayName() {
			return name == null || name.isEmpty() ? id : name;
		}
	}

	/** One invite waiting for the player to accept or decline. */
	public static final class Invite {

		public final String peerId;
		public final String host;
		public final String code;
		public final String world;
		int ageTicks;

		Invite(String peerId, String host, String code, String world) {
			this.peerId = peerId;
			this.host = host;
			this.code = code;
			this.world = world;
		}

		public String hostName() {
			return host == null || host.isEmpty() ? "A player" : host;
		}

		public String worldName() {
			return world == null || world.isEmpty() ? null : world;
		}
	}

	private static List<Peer> peers = Collections.emptyList();
	private static final LinkedList<Invite> backlog = new LinkedList<Invite>();
	private static String notice;
	private static int noticeTicks;

	private LANInvites() {
	}

	/** True when this page can find peers at all, with or without a server. */
	public static boolean isAvailable() {
		return AutoConnect.isInstalled()
				|| (LANDiscovery.isInstalled() && LANDiscovery.isServiceAvailable());
	}

	/** Peers discovered on this network, most recent presence list. */
	public static List<Peer> peers() {
		return peers;
	}

	/** Open channels, i.e. peers an invite can actually reach. */
	public static int openPeerCount() {
		int count = 0;
		for(Peer peer : peers) {
			if(peer.isOpen()) {
				++count;
			}
		}
		return count;
	}

	/** One line of feedback for the host screen, or null when nothing happened. */
	public static String getNotice() {
		return notice;
	}

	/** Invite the player has not answered yet, or null. */
	public static Invite pendingInvite() {
		return backlog.peekFirst();
	}

	public static String getStatus() {
		return LANDiscovery.getStatus();
	}

	/**
	 * Called once per client frame. Discovers nothing by itself - it only drains
	 * what the module already found and reacts to it, so it is safe to call on
	 * every platform and in every game state.
	 *
	 * <p>Failure is contained here on purpose: this runs inside the client's
	 * main loop, and automatic discovery is an optional path over the manual
	 * join codes. A bug or a hostile payload in that path must degrade to "no
	 * automatic play" rather than take the world down.
	 */
	public static void tick() {
		if(disabled) {
			return;
		}
		try {
			tickInternal();
		}catch(Throwable t) {
			if(++failures <= 3) {
				System.err.println("[LANInvites] discovery tick failed: " + t);
			}
			if(failures > MAX_TICK_FAILURES) {
				disabled = true;
				System.err.println("[LANInvites] automatic LAN invites disabled after repeated failures");
			}
		}
	}

	private static void tickInternal() {
		if(noticeTicks > 0 && --noticeTicks == 0) {
			notice = null;
		}
		boolean discovery = LANDiscovery.isInstalled() && LANDiscovery.isServiceAvailable();
		boolean autoConnect = AutoConnect.isInstalled();
		if(!discovery && !autoConnect) {
			// Parked: neither peer source is live, so the manual flow is all there is.
			if(!peers.isEmpty()) {
				peers = Collections.emptyList();
			}
			if(!backlog.isEmpty()) {
				backlog.clear();
			}
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if(minecraft == null) {
			return;
		}
		if(autoConnect) {
			ensureAutoConnectStarted();
		}
		refreshPeers();
		if(discovery) {
			drainEvents(minecraft);
		}
		if(autoConnect) {
			drainAutoConnectEvents(minecraft);
		}
		ageInvites();
		presentInvite(minecraft);
		presentAutoConnectPrompt(minecraft);
	}

	/**
	 * The plan's first tier is meant to need no clicks, and the module now starts
	 * itself the moment the page opens (see {@code bootAutoStart} in
	 * autoconnect.js), so discovery is already running - the reader pool, the
	 * address scan and the column sweep are done before a screen is even open.
	 * What is left for this call is the one thing the page cannot know at load: the
	 * player's name, which the payload carries. The module adopts it and keeps
	 * discovering, and the call is free to repeat.
	 */
	private static void ensureAutoConnectStarted() {
		AutoConnect.start(localName());
	}

	/**
	 * The plan's fallback prompt: once the 20-second budget has passed with no
	 * peer connected, offer the 6-character code - but only where a screen can be
	 * shown without interrupting something, the rule the invite dialog follows.
	 */
	private static void presentAutoConnectPrompt(Minecraft minecraft) {
		if(autoConnectPrompted || !AutoConnect.isInstalled() || !AutoConnect.isFallback()) {
			return;
		}
		if(!peers.isEmpty() || !backlog.isEmpty()) {
			return;
		}
		Screen current = minecraft.gui.screen();
		// Only where the player has shown they are looking for a peer. The module
		// discovers from page load and runs its code fallback from the moment this
		// class names the player, so keeping the title screen here would greet
		// somebody who is still reading their options with a code screen.
		if(current instanceof EaglerLANPeersScreen || current instanceof EaglerDirectConnectHostScreen) {
			autoConnectPrompted = true;
			minecraft.gui.setScreen(new EaglerAutoConnectCodeScreen(current));
		}
	}

	/**
	 * Reads the peers the module found. Parsing is defensive: a malformed event
	 * costs one refresh, never an exception in the client loop.
	 */
	private static void refreshPeers() {
		List<Peer> next = new ArrayList<Peer>();
		if(LANDiscovery.isInstalled() && LANDiscovery.isServiceAvailable()) {
			try {
				JsonElement root = JsonParser.parseString(LANDiscovery.getPeersJson());
				if(root.isJsonArray()) {
					for(JsonElement element : root.getAsJsonArray()) {
						if(!element.isJsonObject()) {
							continue;
						}
						JsonObject object = element.getAsJsonObject();
						String id = string(object, "id");
						if(id == null || id.isEmpty()) {
							continue;
						}
						next.add(new Peer(id, string(object, "name"), stringOr(object, "state", "found"),
								string(object, "addr"), intOr(object, "rttMs", -1)));
					}
				}
			}catch(Throwable t) {
				// A malformed presence list costs the peers it carried, not the tick.
			}
		}
		if(AutoConnect.isInstalled()) {
			try {
				JsonElement root = JsonParser.parseString(AutoConnect.getPeersJson());
				if(root.isJsonArray()) {
					for(JsonElement element : root.getAsJsonArray()) {
						if(!element.isJsonObject()) {
							continue;
						}
						JsonObject object = element.getAsJsonObject();
						String id = string(object, "id");
						if(id == null || id.isEmpty()) {
							continue;
						}
						int port = intOr(object, "port", -1);
						String address = string(object, "address");
						String autoState = stringOr(object, "state", "found");
						next.add(new Peer(AUTO_PREFIX + id, "Player " + id,
								"connected".equals(autoState) ? "open" : "found",
								address == null ? null : address + (port > 0 ? ":" + port : ""), -1));
					}
				}
			}catch(Throwable t) {
				// Same rule for the AutoConnect list.
			}
		}
		peers = next;
	}

	private static void drainEvents(Minecraft minecraft) {
		String raw = LANDiscovery.pollEventsJson();
		if(raw == null || raw.isEmpty()) {
			return;
		}
		JsonElement root;
		try {
			root = JsonParser.parseString(raw);
		}catch(Throwable t) {
			return;
		}
		if(!root.isJsonArray()) {
			return;
		}
		for(JsonElement element : root.getAsJsonArray()) {
			if(!element.isJsonObject()) {
				continue;
			}
			try {
				handleEvent(minecraft, element.getAsJsonObject());
			}catch(Throwable t) {
				// An unreadable event must never break the client loop.
			}
		}
	}

	/**
	 * Drains the serverless module's events. The transport is the same as the
	 * discovery service's: an invite envelope over the peer data channel, answered
	 * with the ordinary direct-connect answer code.
	 */
	private static void drainAutoConnectEvents(Minecraft minecraft) {
		String raw = AutoConnect.pollEventsJson();
		if(raw == null || raw.isEmpty()) {
			return;
		}
		JsonElement root;
		try {
			root = JsonParser.parseString(raw);
		}catch(Throwable t) {
			return;
		}
		if(!root.isJsonArray()) {
			return;
		}
		for(JsonElement element : root.getAsJsonArray()) {
			if(!element.isJsonObject()) {
				continue;
			}
			try {
				handleAutoConnectEvent(minecraft, element.getAsJsonObject());
			}catch(Throwable t) {
				// An unreadable event must never break the client loop.
			}
		}
	}

	private static void handleAutoConnectEvent(Minecraft minecraft, JsonObject event) {
		String type = string(event, "type");
		if(type == null) {
			return;
		}
		if("message".equals(type)) {
			JsonElement data = event.get("data");
			if(data == null || !data.isJsonPrimitive()) {
				return;
			}
			JsonElement parsed;
			try {
				parsed = JsonParser.parseString(data.getAsString());
			}catch(Throwable t) {
				return;
			}
			if(!parsed.isJsonObject()) {
				return;
			}
			// One channel per peer, so the peer id only has to route the reply.
			handleMessage(minecraft, AUTO_PREFIX + "peer", null, parsed.getAsJsonObject());
			return;
		}
		if("fallback".equals(type)) {
			setNotice(Component.translatableWithFallback("autoconnect.notice.fallback",
					"No player was found automatically - press Auto-Connect Code"), 200);
			logger.info("Auto-connect: no peer found automatically, the code fallback is available");
		}else if("connected".equals(type)) {
			setNotice(Component.translatableWithFallback("autoconnect.notice.connected",
					"Auto-connect linked to a player"), 200);
			logger.info("Auto-connect: linked to a player");
		}else if("peer-found".equals(type)) {
			logger.info("Auto-connect: found a player on this network");
		}else if("failed".equals(type)) {
			String reason = string(event, "reason");
			logger.info("Auto-connect stopped: {}", reason == null ? "unknown" : reason);
		}
	}

	private static void handleEvent(Minecraft minecraft, JsonObject event) {
		if(!"message".equals(string(event, "type"))) {
			return;
		}
		JsonObject peer = event.has("peer") && event.get("peer").isJsonObject()
				? event.getAsJsonObject("peer") : null;
		String peerId = peer == null ? null : string(peer, "id");
		String peerName = peer == null ? null : string(peer, "name");
		JsonElement messageElement = event.get("message");
		if(messageElement == null || !messageElement.isJsonObject()) {
			return;
		}
		handleMessage(minecraft, peerId, peerName, messageElement.getAsJsonObject());
	}

	private static void handleMessage(Minecraft minecraft, String peerId, String peerName, JsonObject message) {
		String kind = string(message, "t");
		if(kind == null) {
			return;
		}
		if("invite".equals(kind)) {
			receiveInvite(minecraft, peerId, peerName, message);
		}else if("invite-answer".equals(kind)) {
			receiveAnswer(minecraft, peerId, peerName, message);
		}else if("invite-declined".equals(kind)) {
			setNotice(Component.translatableWithFallback("lan.notice.declined", "%s declined your invite",
					fallbackName(peerName)), 120);
		}
	}

	private static void receiveInvite(Minecraft minecraft, String peerId, String peerName, JsonObject message) {
		JsonElement payloadElement = message.get("payload");
		JsonObject payload;
		if(payloadElement != null && payloadElement.isJsonObject()) {
			payload = payloadElement.getAsJsonObject();
		}else if(payloadElement != null && payloadElement.isJsonPrimitive()) {
			// Tolerate a payload that arrived as a JSON string.
			try {
				JsonElement parsed = JsonParser.parseString(payloadElement.getAsString());
				payload = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
			}catch(Throwable t) {
				payload = null;
			}
		}else {
			payload = null;
		}
		if(payload == null) {
			return;
		}
		String code = string(payload, "code");
		if(code == null || code.isEmpty()) {
			return;
		}
		String host = string(payload, "host");
		if(host == null || host.isEmpty()) {
			host = peerName;
		}
		for(Iterator<Invite> it = backlog.iterator(); it.hasNext();) {
			Invite existing = it.next();
			if(equals(existing.peerId, peerId) && equals(existing.code, code)) {
				return;
			}
		}
		while(backlog.size() >= MAX_INVITE_BACKLOG) {
			backlog.removeLast();
		}
		backlog.addFirst(new Invite(peerId, host, code, string(payload, "world")));
		setNotice(Component.translatableWithFallback("lan.notice.invited", "%s invited you to their world",
				fallbackName(host)), 200);
		logger.info("Invite received from {} - waiting for the player to accept or decline", fallbackName(host));
	}

	private static void receiveAnswer(Minecraft minecraft, String peerId, String peerName, JsonObject message) {
		String answer = string(message, "answer");
		if(answer == null || answer.isEmpty()) {
			return;
		}
		if(!SingleplayerServerController26.isDirectConnectHost()
				&& SingleplayerServerController26.getLANRelayCode() == null) {
			// Not hosting: an answer has nothing to attach to.
			return;
		}
		try {
			SingleplayerServerController26.directHostCompleteAnswer(answer);
			setNotice(Component.translatableWithFallback("lan.notice.connecting", "%s is connecting to your world",
					fallbackName(peerName)), 200);
			logger.info("{} accepted the invite, completing the direct connection", fallbackName(peerName));
		}catch(Throwable t) {
			String reason = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
			setNotice(Component.literal(reason), 200);
		}
	}

	private static void ageInvites() {
		for(Iterator<Invite> it = backlog.iterator(); it.hasNext();) {
			Invite invite = it.next();
			if(++invite.ageTicks > INVITE_EXPIRY_TICKS) {
				it.remove();
			}
		}
	}

	/**
	 * Shows the invite dialog as soon as it can be shown without destroying
	 * something the player is doing (a container screen, a text box). Otherwise
	 * the invite simply waits in the backlog until the game returns to a state
	 * that can carry it, but only within the expiry window.
	 */
	private static void presentInvite(Minecraft minecraft) {
		Invite invite = backlog.peekFirst();
		if(invite == null) {
			return;
		}
		Screen current = minecraft.gui.screen();
		if(current instanceof EaglerLANInviteScreen || current instanceof EaglerDirectConnectJoinScreen) {
			return;
		}
		if(current == null || current instanceof TitleScreen || current instanceof EaglerDirectConnectHostScreen) {
			minecraft.gui.setScreen(new EaglerLANInviteScreen(current, invite));
		}
	}

	/**
	 * Host side: offer this world to one discovered peer. The offer code is the
	 * ordinary direct-connect invite code the room already mints, so an accepted
	 * invite reuses the existing transport end to end.
	 */
	public static boolean sendInvite(Peer peer, String offerCode) {
		if(peer == null || offerCode == null || offerCode.isEmpty()) {
			return false;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("v", VERSION);
		payload.addProperty("code", offerCode);
		String self = localName();
		if(self != null && !self.isEmpty()) {
			payload.addProperty("host", self);
		}
		boolean sent = peer.id.startsWith(AUTO_PREFIX)
				? AutoConnect.sendText(payload.toString())
				: LANDiscovery.invite(peer.id, payload.toString());
		setNotice(Component.translatableWithFallback(sent ? "lan.notice.sent" : "lan.notice.sendFailed",
				sent ? "Invite sent to %s" : "Could not invite %s", peer.displayName()), 120);
		logger.info(sent ? "Invite sent to {}" : "Could not send an invite to {}", peer.displayName());
		return sent;
	}

	/** Guest side: hand our answer code back to the host over the same channel. */
	public static boolean sendAnswer(String peerId, String answerCode) {
		if(peerId == null || answerCode == null || answerCode.isEmpty()) {
			return false;
		}
		JsonObject envelope = new JsonObject();
		envelope.addProperty("t", "invite-answer");
		envelope.addProperty("v", VERSION);
		envelope.addProperty("answer", answerCode);
		if(peerId.startsWith(AUTO_PREFIX)) {
			return AutoConnect.sendText(envelope.toString());
		}
		return LANDiscovery.sendText(peerId, envelope.toString());
	}

	/** Guest side: "no thanks", so the host is not left waiting. */
	public static void declineInvite(Invite invite) {
		if(invite == null) {
			return;
		}
		backlog.remove(invite);
		if(invite.peerId != null && !invite.peerId.isEmpty()) {
			JsonObject envelope = new JsonObject();
		envelope.addProperty("t", "invite-declined");
		envelope.addProperty("v", VERSION);
		if(invite.peerId != null && invite.peerId.startsWith(AUTO_PREFIX)) {
			AutoConnect.sendText(envelope.toString());
		}else{
			LANDiscovery.sendText(invite.peerId, envelope.toString());
		}
		}
	}

	/**
	 * Guest side: accept. Runs the ordinary direct-connect join against the
	 * offered code, with the answer code returned automatically instead of shown
	 * for copy/paste.
	 */
	public static void acceptInvite(Invite invite, Screen returnTo) {
		Minecraft minecraft = Minecraft.getInstance();
		if(minecraft == null || invite == null) {
			return;
		}
		backlog.remove(invite);
		ServerData target = new ServerData("LAN World", "", ServerData.Type.OTHER);
		minecraft.gui.setScreen(new EaglerDirectConnectJoinScreen(returnTo, target, accepted -> {
			if(accepted) {
				ConnectScreen.startConnecting(returnTo, minecraft, ServerAddress.parseString(target.ip), target, false,
						null);
			}else {
				minecraft.gui.setScreen(returnTo);
			}
		}, invite.code, invite.peerId));
	}

	/** Debug/status snapshot of the module, exposed for the LAN screens. */
	public static String describeService() {
		if(AutoConnect.isInstalled()) {
			String phase = AutoConnect.getPhase();
			return (phase == null ? "" : phase + " - ") + AutoConnect.getStatus();
		}
		if(!LANDiscovery.isInstalled()) {
			return "Automatic LAN discovery is not available on this page";
		}
		return LANDiscovery.getStatus();
	}

	public static String selfName() {
		return localName();
	}

	/** The name other players see, from whichever peer source is live. */
	private static String localName() {
		String name = LANDiscovery.getSelfName();
		if(name == null || name.isEmpty()) {
			name = AutoConnect.getSelfName();
		}
		return name;
	}

	private static void setNotice(Component message, int ticks) {
		notice = message.getString();
		noticeTicks = ticks;
	}

	private static String fallbackName(String name) {
		return name == null || name.isEmpty() ? "A player" : name;
	}

	private static boolean equals(String a, String b) {
		return a == null ? b == null : a.equals(b);
	}

	private static String string(JsonObject object, String key) {
		if(object == null || !object.has(key)) {
			return null;
		}
		JsonElement element = object.get(key);
		if(element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return null;
		}
		try {
			return element.getAsString();
		}catch(Throwable t) {
			return null;
		}
	}

	private static String stringOr(JsonObject object, String key, String fallback) {
		String value = string(object, key);
		return value == null || value.isEmpty() ? fallback : value;
	}

	private static int intOr(JsonObject object, String key, int fallback) {
		if(object == null || !object.has(key)) {
			return fallback;
		}
		try {
			JsonElement element = object.get(key);
			return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
		}catch(Throwable t) {
			return fallback;
		}
	}
}

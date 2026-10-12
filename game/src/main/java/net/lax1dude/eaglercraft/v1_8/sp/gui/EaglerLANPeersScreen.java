package net.lax1dude.eaglercraft.v1_8.sp.gui;

import java.util.List;

import net.lax1dude.eaglercraft.v1_8.sp.SingleplayerServerController26;
import net.lax1dude.eaglercraft.v1_8.sp.lan.LANInvites;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The "online" list: every other player the client found on this network while
 * the world was being published.
 *
 * <p>One row per peer, one button per row. Pressing it offers this world to
 * that player through the data channel that is already open to them, and the
 * offer carries the room's ordinary direct-connect invite code - so the guest
 * never sees a code, and the host never pastes one.
 *
 * <p>Rows are a fixed pool of widgets that are re-labelled every tick, because
 * the presence list changes while the screen is open and rebuilding widgets
 * under the player's cursor is worse than updating them in place.
 */
public class EaglerLANPeersScreen extends Screen {

	private static final int ROW_COUNT = 6;
	private static final int ROW_HEIGHT = 22;

	private final Screen parent;
	private final int gameMode;
	private final boolean allowCommands;

	private final Button[] inviteButtons = new Button[ROW_COUNT];
	private final LANInvites.Peer[] rowPeers = new LANInvites.Peer[ROW_COUNT];
	private String notice;
	private int rowTop;

	public EaglerLANPeersScreen(Screen parent, int gameMode, boolean allowCommands) {
		super(Component.translatableWithFallback("lan.peers.title", "Players On This Network"));
		this.parent = parent;
		this.gameMode = gameMode;
		this.allowCommands = allowCommands;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		this.rowTop = this.height / 2 - 54;
		for(int i = 0; i < ROW_COUNT; ++i) {
			final int row = i;
			this.inviteButtons[i] = this.addRenderableWidget(Button.builder(
					Component.translatableWithFallback("lan.peers.invite", "Invite"), button -> this.invite(row))
					.bounds(centerX + 52, this.rowTop + i * ROW_HEIGHT, 100, 20).build());
			this.inviteButtons[i].visible = false;
			this.inviteButtons[i].active = false;
		}
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
				.bounds(centerX - 152, this.height / 2 + 96, 304, 20).build());
		this.refreshRows();
	}

	private void refreshRows() {
		List<LANInvites.Peer> peers = LANInvites.peers();
		for(int i = 0; i < ROW_COUNT; ++i) {
			Button button = this.inviteButtons[i];
			if(button == null) {
				continue;
			}
			if(i < peers.size()) {
				LANInvites.Peer peer = peers.get(i);
				this.rowPeers[i] = peer;
				button.visible = true;
				button.active = peer.isOpen();
				button.setMessage(peer.isOpen()
						? Component.translatableWithFallback("lan.peers.invite", "Invite")
						: Component.translatableWithFallback("lan.peers.connecting", "Connecting..."));
			}else {
				this.rowPeers[i] = null;
				button.visible = false;
				button.active = false;
			}
		}
	}

	private void invite(int row) {
		LANInvites.Peer peer = row >= 0 && row < ROW_COUNT ? this.rowPeers[row] : null;
		if(peer == null) {
			return;
		}
		String code = SingleplayerServerController26.getLANRelayCode();
		if(code == null || code.isBlank()) {
			// The room keeps exactly one offer ready; mint the next one on demand.
			try {
				if(SingleplayerServerController26.isDirectConnectHost()) {
					SingleplayerServerController26.directHostNewInviteCode();
				}else {
					SingleplayerServerController26.publishLANRelay(java.util.Collections.singletonList("eagler-direct:"),
							this.gameMode, this.allowCommands);
				}
				code = SingleplayerServerController26.getLANRelayCode();
			}catch(Throwable t) {
				this.notice = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
				return;
			}
		}
		if(code == null || code.isBlank()) {
			this.notice = "The world is not sharing yet";
			return;
		}
		LANInvites.sendInvite(peer, code);
	}

	@Override
	public void tick() {
		this.refreshRows();
		this.notice = LANInvites.getNotice();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.extractMenuBackground(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int centerX = this.width / 2;
		graphics.centeredText(this.font, this.title, centerX, this.height / 2 - 90, -1);
		graphics.centeredText(this.font, Component.literal(LANInvites.describeService()), centerX,
				this.height / 2 - 76, 0xFF8FD18F);
		List<LANInvites.Peer> peers = LANInvites.peers();
		if(peers.isEmpty()) {
			graphics.centeredText(this.font, Component.translatableWithFallback("lan.peers.none",
					"No players found on this network yet"), centerX, this.height / 2 - 44, 0xFFAAAAAA);
			graphics.centeredText(this.font, Component.translatableWithFallback("lan.peers.noneHint",
					"Anyone who opens this page on the same network appears here"), centerX, this.height / 2 - 30,
					0xFFAAAAAA);
		}
		for(int i = 0; i < ROW_COUNT && i < peers.size(); ++i) {
			LANInvites.Peer peer = peers.get(i);
			int y = this.rowTop + i * ROW_HEIGHT + 6;
			int colour = peer.isOpen() ? 0xFFFFFFFF : 0xFFAAAAAA;
			graphics.text(this.font, Component.literal(peer.displayName()), centerX - 152, y, colour, false);
			String detail = peer.isOpen()
					? (peer.rttMs >= 0 ? peer.rttMs + " ms" : "connected")
					: peer.state;
			graphics.text(this.font, Component.literal(detail), centerX - 60, y, 0xFF8FD18F, false);
		}
		if(this.notice != null) {
			graphics.centeredText(this.font, Component.literal(this.notice), centerX, this.height / 2 + 78,
					0xFF8FD18F);
		}
		graphics.centeredText(this.font, Component.translatableWithFallback("lan.peers.hint",
				"Inviting sends the world to that player - they accept it in game"), centerX,
				this.height / 2 + 122, 0xFFAAAAAA);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}
}

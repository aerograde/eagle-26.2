package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.lax1dude.eaglercraft.v1_8.sp.lan.LANInvites;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The in-game notification a player receives when someone on their network
 * invites them to a world.
 *
 * <p>It is a plain modal screen rather than a toast because the answer is a
 * decision: accepting starts the direct-connect flow against the code that came
 * with the invite, declining tells the host so the room is not left waiting for
 * a guest that is never coming. Both answers travel over the same data channel
 * the invite arrived on.
 */
public class EaglerLANInviteScreen extends Screen {

	private final Screen parent;
	private final LANInvites.Invite invite;
	private boolean handled;

	public EaglerLANInviteScreen(Screen parent, LANInvites.Invite invite) {
		super(Component.translatableWithFallback("lan.invite.title", "World Invite"));
		this.parent = parent;
		this.invite = invite;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int buttonY = this.height / 2 + 32;
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("lan.invite.accept", "Accept"),
				button -> this.accept()).bounds(centerX - 102, buttonY, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("lan.invite.decline", "Decline"),
				button -> this.decline()).bounds(centerX + 2, buttonY, 100, 20).build());
	}

	private void accept() {
		if(this.handled) {
			return;
		}
		this.handled = true;
		// The join screen returns here if the channel fails, so a failed invite
		// never traps the player.
		LANInvites.acceptInvite(this.invite, this.parent);
	}

	private void decline() {
		if(this.handled) {
			return;
		}
		this.handled = true;
		LANInvites.declineInvite(this.invite);
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void onClose() {
		this.decline();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.extractMenuBackground(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int centerX = this.width / 2;
		graphics.centeredText(this.font, this.title, centerX, this.height / 2 - 60, -1);
		graphics.centeredText(this.font, Component.translatableWithFallback("lan.invite.body",
				"%s invited you to their world", this.invite.hostName()), centerX, this.height / 2 - 36,
				0xFFAAAAAA);
		String world = this.invite.worldName();
		if(world != null) {
			graphics.centeredText(this.font, Component.translatableWithFallback("lan.invite.world", "World: %s",
					world), centerX, this.height / 2 - 22, 0xFF55FFFF);
		}
		graphics.centeredText(this.font, Component.translatableWithFallback("lan.invite.hint",
				"Accepting connects you over this network - no code to copy"), centerX, this.height / 2 + 4,
				0xFF8FD18F);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}
}

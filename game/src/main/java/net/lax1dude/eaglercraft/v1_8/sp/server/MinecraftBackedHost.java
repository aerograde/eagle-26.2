package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.nio.file.Path;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.DataFixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ModCheck;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.world.level.ChunkPos;

/**
 * {@link EaglerIntegratedServerHost} backed by the real client {@link Minecraft} — the
 * DESKTOP hosted-mode + single-thread web-mode adapter. Every method forwards to exactly
 * the {@code this.minecraft.X} expression {@code IntegratedServer} used before the seam-b
 * decouple, so these paths keep their byte-identical behaviour (same values, same call
 * order, same client-thread {@code execute(...)} scheduling for the toasts).
 */
public final class MinecraftBackedHost implements EaglerIntegratedServerHost {

	private final Minecraft minecraft;

	public MinecraftBackedHost(final Minecraft minecraft) {
		this.minecraft = minecraft;
	}

	@Override
	public java.net.Proxy getProxy() {
		return minecraft.getProxy();
	}

	@Override
	public DataFixer getFixerUpper() {
		return minecraft.getFixerUpper();
	}

	@Override
	public GameProfile getGameProfile() {
		return minecraft.getGameProfile();
	}

	@Override
	public boolean isDemo() {
		return minecraft.isDemo();
	}

	@Override
	public boolean isPauseRequested() {
		return minecraft.isPaused();
	}

	@Override
	public int getRenderDistance() {
		return minecraft.options.renderDistance().get();
	}

	@Override
	public int getSimulationDistance() {
		return minecraft.options.simulationDistance().get();
	}

	@Override
	public double getEntityDistanceScaling() {
		return minecraft.options.entityDistanceScaling().get();
	}

	@Override
	public LocalSampleLogger getTickTimeLogger() {
		return minecraft.getDebugOverlay().getTickTimeLogger();
	}

	@Override
	public Path getServerDirectory() {
		return minecraft.gameDirectory.toPath();
	}

	@Override
	public String getLaunchedVersion() {
		return minecraft.getLaunchedVersion();
	}

	@Override
	public boolean useNativeTransport() {
		return minecraft.options.useNativeTransport();
	}

	@Override
	public boolean forceSynchronousWrites() {
		return minecraft.options.syncWrites;
	}

	@Override
	public ModCheck getModdedStatus() {
		return Minecraft.checkModStatus();
	}

	@Override
	public void sendLowDiskSpaceWarning() {
		minecraft.sendLowDiskSpaceWarning();
	}

	@Override
	public void onChunkLoadFailure(final ChunkPos pos) {
		minecraft.execute(() -> SystemToast.onChunkLoadFailure(minecraft, pos));
	}

	@Override
	public void onChunkSaveFailure(final ChunkPos pos) {
		minecraft.execute(() -> SystemToast.onChunkSaveFailure(minecraft, pos));
	}

	@Override
	public NameAndId getLocalPlayerNameAndId() {
		LocalPlayer player = minecraft.player;
		return player == null ? null : player.nameAndId();
	}

	@Override
	public void applyLocalPlayerPermissions(final LevelBasedPermissionSet permissions) {
		LocalPlayer player = minecraft.player;
		if (player != null) {
			player.setPermissions(permissions);
			player.refreshChatAbilities();
		}
	}

	@Override
	public void prepareForMultiplayer() {
		minecraft.prepareForMultiplayer();
	}

	@Override
	public void prepareClientKeyPair() {
		minecraft.getConnection().prepareKeyPair();
	}

}

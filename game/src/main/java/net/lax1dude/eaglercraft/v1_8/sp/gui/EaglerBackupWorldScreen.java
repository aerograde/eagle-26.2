package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSWorldStorage;
import net.lax1dude.eaglercraft.v1_8.sp.server.export.WorldConverterEPK26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

/**
 * 26.2 port of upstream sp/gui/GuiScreenBackupWorldSelection — the World Backup
 * Menu opened by the Backup button on the world list (replacing 26.2's top-level
 * Re-Create, which lives in here like upstream): Re-Create / Duplicate / seed
 * display / Export EPK / Convert to Vanilla (deferred) / Clear Player Data.
 */
public class EaglerBackupWorldScreen extends Screen {

	private static final Logger logger = LogManager.getLogger("EaglerBackupWorldScreen");

	private final Screen parent;
	private final String levelId;
	private final Runnable recreateAction;

	public EaglerBackupWorldScreen(Screen parent, String levelId, Runnable recreateAction) {
		super(Component.translatableWithFallback("singleplayer.backup.title", "World Backup Menu: '%s'", levelId));
		this.parent = parent;
		this.levelId = levelId;
		this.recreateAction = recreateAction;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100;
		int y = this.height / 5;

		addCenteredLine(this.title, y - 35);

		this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("singleplayer.backup.recreate", "Re-Create World"),
				btn -> recreateAction.run()).bounds(x, y + 5, 200, 20).build());
		this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("singleplayer.backup.duplicate", "Duplicate World"),
				btn -> duplicateWorld()).bounds(x, y + 30, 200, 20).build());

		Long seed = EaglerVFSWorldStorage.readWorldSeed(levelId);
		if(seed != null) {
			addCenteredLine(Component.literal(
					Component.translatableWithFallback("singleplayer.backup.seed", "Seed:").getString()
							+ " " + seed), y + 62);
		}

		this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("singleplayer.backup.export", "Export EPK File"),
				btn -> exportWorld()).bounds(x, y + 80, 200, 20).build());
		Button convert = Button.builder(
				Component.translatableWithFallback("singleplayer.backup.vanilla", "Convert to Vanilla"),
				btn -> {}).bounds(x, y + 105, 200, 20).build();
		convert.active = false;
		this.addRenderableWidget(convert);
		this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("singleplayer.backup.clearPlayerData", "Clear Player Data"),
				btn -> confirmClearPlayerData()).bounds(x, y + 136, 200, 20).build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), btn -> this.onClose())
				.bounds(x, this.height / 4 + 155, 200, 20).build());
	}

	private void addCenteredLine(Component text, int y) {
		int w = this.font.width(text);
		this.addRenderableWidget(new StringWidget(this.width / 2 - w / 2, y, w, 9, text, this.font));
	}

	private void duplicateWorld() {
		EaglerLocalProgressScreen progress = new EaglerLocalProgressScreen(
				Component.translatableWithFallback("singleplayer.busy.duplicating", "Duplicating world"));
		this.minecraft.gui.setScreen(progress);
		Thread thread = new Thread(() -> {
			try {
				EaglerVFSWorldStorage.duplicateWorld(levelId, progress);
			}catch(Throwable t) {
				logger.error("Failed to duplicate world {}", levelId);
				logger.error(t);
			}
			this.minecraft.execute(() ->
					this.minecraft.gui.setScreen(new SelectWorldScreen(new TitleScreen())));
		}, "EaglerWorldDuplicate");
		thread.setDaemon(true);
		thread.start();
	}

	private void exportWorld() {
		EaglerLocalProgressScreen progress = new EaglerLocalProgressScreen(
				Component.translatableWithFallback("singleplayer.busy.exporting.1", "Exporting world as EPK"));
		this.minecraft.gui.setScreen(progress);
		Minecraft mc = this.minecraft;
		Thread thread = new Thread(() -> {
			try {
				byte[] epkBytes = WorldConverterEPK26.exportWorld(levelId,
						WorldConverterEPK26.getExportOwner(), progress);
				EagRuntime.downloadFileWithName(levelId + ".epk", epkBytes);
				mc.execute(() -> {
					mc.gui.toastManager().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_BACKUP,
							Component.translatable("selectWorld.edit.backupCreated", levelId),
							Component.translatable("selectWorld.edit.backupSize",
									Mth.ceil(epkBytes.length / 1048576.0))));
					mc.gui.setScreen(new SelectWorldScreen(new TitleScreen()));
				});
			}catch(Throwable t) {
				logger.error("Failed to export world {} as EPK", levelId);
				logger.error(t);
				mc.execute(() -> {
					mc.gui.toastManager().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_BACKUP,
							Component.translatable("selectWorld.edit.backupFailed"),
							Component.literal(t.getMessage() == null ? t.toString() : t.getMessage())));
					mc.gui.setScreen(new SelectWorldScreen(new TitleScreen()));
				});
			}
		}, "EaglerWorldExport");
		thread.setDaemon(true);
		thread.start();
	}

	private void confirmClearPlayerData() {
		this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if(confirmed) {
				try {
					EaglerVFSWorldStorage.clearPlayerData(levelId);
				}catch(Throwable t) {
					logger.error("Failed to clear player data for {}", levelId);
					logger.error(t);
				}
			}
			this.minecraft.gui.setScreen(this);
		},
				Component.translatableWithFallback("singleplayer.backup.clearPlayerData.warning1", "Are you sure you want to delete all player data?"),
				Component.translatableWithFallback("singleplayer.backup.clearPlayerData.warning2",
						"This removes every player's position, inventory, stats, and advancements in '%s'", levelId)));
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

}

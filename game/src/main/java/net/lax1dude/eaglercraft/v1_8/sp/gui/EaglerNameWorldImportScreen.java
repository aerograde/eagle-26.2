package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.FileChooserResult;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSWorldStorage;
import net.lax1dude.eaglercraft.v1_8.sp.server.export.WorldConverterEPK26;
import net.lax1dude.eaglercraft.v1_8.sp.server.export.WorldConverterMCA26;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * 26.2 upgrade of upstream sp/gui/GuiScreenNameWorldImport: names a world
 * picked in the platform file chooser before importing it into the VFS.
 * The name field pre-fills from the EPK world-name HEAD entry (or the file
 * name); OK dedupes the folder with EaglerVFSWorldStorage.findAvailableWorldName
 * and runs the matching converter on a background thread behind an
 * EaglerLocalProgressScreen, then returns to the world list.
 */
public class EaglerNameWorldImportScreen extends Screen {

	public static final int FORMAT_EPK = 0;
	public static final int FORMAT_MCA = 1;

	private static final Logger logger = LogManager.getLogger("EaglerNameWorldImportScreen");

	private static final Component TITLE = Component.translatableWithFallback("singleplayer.import.title", "Import World");
	private static final Component ENTER_NAME = Component.translatableWithFallback("singleplayer.import.enterName", "Enter world name:");
	private static final Component CONTINUE = Component.translatableWithFallback("singleplayer.import.continue", "Continue");
	private static final Component IMPORTING_EPK = Component.translatableWithFallback("singleplayer.busy.importing.1", "Importing world from EPK");
	private static final Component IMPORTING_MCA = Component.translatableWithFallback("singleplayer.busy.importing.2", "Importing vanilla world");
	private static final Component IMPORT_FAILED = Component.translatableWithFallback("singleplayer.import.failed", "Failed to import world!");

	private final Screen parent;
	private final FileChooserResult world;
	private final int format;
	private String name;


	private EditBox nameField;
	private Button continueButton;

	public EaglerNameWorldImportScreen(Screen parent, FileChooserResult world, int format) {
		super(TITLE);
		this.parent = parent;
		this.world = world;
		this.format = format;
		String n = world.fileName;
		if(n.length() > 4 && (n.endsWith(".epk") || n.endsWith(".zip"))) {
			n = n.substring(0, n.length() - 4);
		}
		if(format == FORMAT_EPK) {
			String epkName = WorldConverterEPK26.readWorldName(world.fileData);
			if(epkName != null && !epkName.trim().isEmpty()) {
				n = epkName.trim();
			}
		}
		this.name = n;
	}

	@Override
	protected void init() {
		int titleW = this.font.width(this.title);
		this.addRenderableWidget(new StringWidget(this.width / 2 - titleW / 2, this.height / 4 - 44,
				titleW, 9, this.title, this.font));

		int x = this.width / 2 - 100;
		int y = this.height / 4;
		this.addRenderableWidget(new StringWidget(x, y - 14, 200, 9, ENTER_NAME, this.font));
		this.nameField = this.addRenderableWidget(new EditBox(this.font, x, y, 200, 20, ENTER_NAME));
		this.nameField.setMaxLength(128);
		this.nameField.setValue(name);
		this.nameField.setResponder(str -> {
			this.name = str;
			if(this.continueButton != null) {
				this.continueButton.active = !str.trim().isEmpty();
			}
		});
		this.continueButton = this.addRenderableWidget(Button.builder(CONTINUE, btn -> this.startImport())
				.bounds(x, y + 40, 200, 20).build());
		this.continueButton.active = !this.name.trim().isEmpty();
		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), btn -> this.onClose())
				.bounds(x, y + 64, 200, 20).build());
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
	}

	@Override
	protected void setInitialFocus() {
		if(this.nameField != null) {
			this.setInitialFocus(this.nameField);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if(this.nameField != null && this.nameField.isFocused() && event.isConfirmation()
				&& this.continueButton != null && this.continueButton.active) {
			this.startImport();
			return true;
		}
		return super.keyPressed(event);
	}

	private void startImport() {
		final String displayName = this.name.trim();
		if(displayName.isEmpty()) {
			return;
		}
		final Minecraft mc = this.minecraft;
		final byte[] fileData = this.world.fileData;
		final int fmt = this.format;
		final EaglerLocalProgressScreen progressScreen = new EaglerLocalProgressScreen(
				fmt == FORMAT_EPK ? IMPORTING_EPK : IMPORTING_MCA);
		mc.setScreenAndShow(progressScreen);
		Thread importThread = new Thread(() -> {
			String folderName = EaglerVFSWorldStorage.findAvailableWorldName(displayName);
			try {
				if(fmt == FORMAT_EPK) {
					WorldConverterEPK26.importWorld(fileData, folderName, displayName, progressScreen);
				}else {
					WorldConverterMCA26.importWorld(fileData, folderName, displayName, progressScreen);
				}
				mc.execute(() -> mc.gui.setScreen(new SelectWorldScreen(new TitleScreen())));
			}catch(Throwable t) {
				logger.error("Failed to import world \"{}\"", folderName);
				logger.error(t);
				try {
					// purge the partial blobs so the folder name frees up again
					EaglerVFSWorldStorage.deleteWorld(folderName);
				}catch(Throwable t2) {
					logger.error("Failed to clean up partially imported world \"{}\"", folderName);
					logger.error(t2);
				}
				String message = t.getMessage();
				final Component messageComponent = Component.literal(message == null ? t.toString() : message);
				mc.execute(() -> mc.gui.setScreen(new AlertScreen(
						() -> mc.gui.setScreen(new SelectWorldScreen(new TitleScreen())),
						IMPORT_FAILED, messageComponent)));
			}
		}, "EaglerWorldImport");
		importThread.setDaemon(true);
		importThread.start();
	}

	@Override
	public void onClose() {
		EagRuntime.clearFileChooserResult();
		this.minecraft.gui.setScreen(this.parent);
	}

}

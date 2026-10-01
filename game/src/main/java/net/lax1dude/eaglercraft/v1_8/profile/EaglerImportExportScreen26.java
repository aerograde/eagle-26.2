package net.lax1dude.eaglercraft.v1_8.profile;

import java.io.IOException;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.FileChooserResult;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 26.2 port of upstream profile/GuiScreenImportExportProfile: the two-button
 * chooser behind the "Import/Export" link on the edit-profile and edit-cape
 * screens. Export packs the profile + settings storage into an .epk download
 * immediately; Import opens the platform file chooser for an .epk and loads
 * it through ProfileImporter, then refreshes the edit-profile screen it
 * returns to. Upstream's per-category yes/no option screens are collapsed
 * into these one-click actions because the 26.2 port only backs up the
 * profile ("p") and settings ("g") storage keys.
 */
public class EaglerImportExportScreen26 extends Screen {

	private final Screen back;
	private boolean waitingForFile = false;

	public EaglerImportExportScreen26(Screen back) {
		super(Component.translatableWithFallback("settingsBackup.importExport.title", "What do you wanna do?"));
		this.back = back;
	}

	@Override
	protected void init() {
		this.addRenderableWidget(new StringWidget(this.width / 2 - this.font.width(this.title) / 2, this.height / 4,
				this.font.width(this.title), 9, this.title, this.font));

		this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("settingsBackup.importExport.import", "Import Profile and Settings..."),
				btn -> {
					waitingForFile = true;
					EagRuntime.displayFileChooser(null, "epk");
				}).bounds(this.width / 2 - 100, this.height / 4 + 40, 200, 20).build());

		this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("settingsBackup.importExport.export", "Export Profile and Settings..."),
				btn -> {
					try {
						ProfileExporter.exportProfileAndSettings();
						this.minecraft.gui.setScreen(back);
					}catch(IOException ex) {
						EagRuntime.debugPrintStackTrace(ex);
						EagRuntime.showPopup(Component.translatableWithFallback("settingsBackup.importExport.exportFailed",
								"Export Failed!\nCould not compile EPK").getString());
					}
				}).bounds(this.width / 2 - 100, this.height / 4 + 65, 200, 20).build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
				btn -> this.minecraft.gui.setScreen(back))
				.bounds(this.width / 2 - 100, this.height / 4 + 130, 200, 20).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.extractMenuBackground(graphics);
	}

	@Override
	public void tick() {
		if(waitingForFile && EagRuntime.fileChooserHasResult()) {
			waitingForFile = false;
			FileChooserResult result = EagRuntime.getFileChooserResult();
			if(result != null) {
				try {
					ProfileImporter.importProfileAndSettings(result.fileData);
					if(back instanceof EaglerProfileScreen26 profileScreen) {
						profileScreen.refreshProfileState();
					}
					this.minecraft.gui.setScreen(back);
				}catch(IOException ex) {
					EagRuntime.debugPrintStackTrace(ex);
					EagRuntime.showPopup(Component.translatableWithFallback("settingsBackup.importExport.importFailed",
							"Import Failed!\nCould not load EPK: %s", ex.getMessage()).getString());
				}
			}
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(back);
	}

}

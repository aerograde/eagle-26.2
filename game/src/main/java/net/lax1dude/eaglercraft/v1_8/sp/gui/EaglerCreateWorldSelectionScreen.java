package net.lax1dude.eaglercraft.v1_8.sp.gui;

import java.util.Locale;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.FileChooserResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

public class EaglerCreateWorldSelectionScreen extends Screen {

	private static final Component CREATE = Component.translatableWithFallback("singleplayer.create.create", "Create New World");
	private static final Component IMPORT_EPK = Component.translatableWithFallback("singleplayer.create.import.epk", "Import EPK World");
	private static final Component IMPORT_MCA = Component.translatableWithFallback("singleplayer.create.import.vanilla", "Import Vanilla World");
	private static final Component JOIN_LAN = Component.translatableWithFallback("singleplayer.create.joinLan", "Join LAN World");
	private static final Component INVALID_FILE = Component.translatableWithFallback("singleplayer.import.invalidFile", "Invalid world file");

	private final Screen parent;
	private final Runnable postCreateReturn;
	private FocusableTextWidget titleWidget;
	private int pendingImportFormat = -1;

	public EaglerCreateWorldSelectionScreen(Screen parent, Runnable postCreateReturn) {
		super(Component.translatableWithFallback("singleplayer.create.title", "What would you like to do?"));
		this.parent = parent;
		this.postCreateReturn = postCreateReturn;
	}

	@Override
	protected void init() {
		this.titleWidget = this.addRenderableWidget(
				FocusableTextWidget.builder(this.title, this.font, 12).textWidth(this.font.width(this.title)).build());

		int x = this.width / 2 - 100;
		int y = this.height / 4 + 24;
		this.addRenderableWidget(Button.builder(CREATE, btn -> {
				net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerRuntime.prewarm();
				CreateWorldScreen.openFresh(Minecraft.getInstance(), this.postCreateReturn);
			})
				.bounds(x, y, 200, 20).build());
			this.addRenderableWidget(Button.builder(IMPORT_EPK, btn -> {
				this.pendingImportFormat = EaglerNameWorldImportScreen.FORMAT_EPK;
				EagRuntime.displayFileChooser(null, "epk");
			}).bounds(x, y + 24, 200, 20).build());
			this.addRenderableWidget(Button.builder(IMPORT_MCA, btn -> {
				this.pendingImportFormat = EaglerNameWorldImportScreen.FORMAT_MCA;
				EagRuntime.displayFileChooser(null, "zip");
			}).bounds(x, y + 48, 200, 20).build());
		this.addRenderableWidget(Button.builder(JOIN_LAN, btn -> this.openLANJoin())
				.bounds(x, y + 72, 200, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), btn -> this.onClose())
				.bounds(x, y + 120, 200, 20).build());
		this.repositionElements();
	}

	private void openLANJoin() {
		ServerData target = new ServerData("LAN World", "", ServerData.Type.OTHER);
		this.minecraft.gui.setScreen(new DirectJoinServerScreen(this, accepted -> {
			if(accepted) {
				ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(target.ip), target, false, null);
			}else {
				this.minecraft.gui.setScreen(this);
			}
		}, target, true));
	}

	@Override
	protected void repositionElements() {
		if(titleWidget != null) {
			titleWidget.setPosition(this.width / 2 - titleWidget.getWidth() / 2, this.height / 4 - 20);
		}
	}

	@Override
	public void tick() {
		if(EagRuntime.fileChooserHasResult()) {
			FileChooserResult result = EagRuntime.getFileChooserResult();
			int format = this.pendingImportFormat;
			this.pendingImportFormat = -1;
			if(result != null && format != -1) {
				String expectedExtension = format == EaglerNameWorldImportScreen.FORMAT_EPK ? ".epk" : ".zip";
				if(result.fileName == null || !result.fileName.toLowerCase(Locale.ROOT).endsWith(expectedExtension)) {
					Component message = Component.translatableWithFallback("singleplayer.import.expectedFile",
							"Please select a %s file.", expectedExtension);
					this.minecraft.gui.setScreen(new AlertScreen(
							() -> this.minecraft.gui.setScreen(this), INVALID_FILE, message));
					return;
				}
				this.minecraft.gui.setScreen(new EaglerNameWorldImportScreen(this, result, format));
			}
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

}

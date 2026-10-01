package net.lax1dude.eaglercraft.v1_8.profile;

import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.FileChooserResult;
import net.lax1dude.eaglercraft.v1_8.opengl.ImageData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 26.2 port of upstream profile/GuiScreenEditCape on the extraction-based
 * widget GUI model, laid out like the edit-profile screen: dirt background,
 * centered "Edit Cape" title, underlined "Import/Export" link top-left,
 * bordered dark preview box on the left showing the selected cape, and a
 * right column with the "Player Cape" label, the cape dropdown (custom capes
 * first, then the presets from DefaultCapes), "Add Cape"/"Clear List"
 * buttons, and Done. "Add Cape" accepts 32x32 or 64x32 cape PNGs, converted
 * to the upstream 1173-byte 23x17 RGB blob with SkinConverter. Done/Esc
 * writes the selection into EaglerProfile (saved when the profile screen
 * saves, like upstream) and returns to the edit-profile screen.
 */
public class EaglerCapeScreen26 extends Screen {

	private static final Component CAPE_LABEL = Component.translatableWithFallback("editCape.playerCape", "Player Cape");
	private static final Component ADD_CAPE = Component.translatableWithFallback("editCape.addCape", "Add Cape");
	private static final Component CLEAR_CAPES = Component.translatableWithFallback("editCape.clearCape", "Clear List");

	private final EaglerProfileScreen26 parent;

	private EaglerDropdownWidget26 capeDropdown;

	protected int selectedSlot;

	public EaglerCapeScreen26(EaglerProfileScreen26 parent) {
		super(Component.translatableWithFallback("editCape.title", "Edit Cape"));
		this.parent = parent;
		this.selectedSlot = EaglerProfile.presetCapeId == -1 ? EaglerProfile.customCapeId
				: (EaglerProfile.presetCapeId + EaglerProfile.customCapes.size());
		if(this.selectedSlot < 0 || this.selectedSlot >= totalSlots()) {
			this.selectedSlot = EaglerProfile.customCapes.size();
		}
	}

	private int totalSlots() {
		return EaglerProfile.customCapes.size() + DefaultCapes.defaultCapesMap.length;
	}

	private Identifier selectedTexture() {
		int numCustom = EaglerProfile.customCapes.size();
		if(selectedSlot >= totalSlots()) {
			selectedSlot = 0;
		}
		if(selectedSlot >= 0 && selectedSlot < numCustom) {
			return EaglerProfile.customCapes.get(selectedSlot).getResource();
		}else {
			return DefaultCapes.getCapeFromId(selectedSlot - numCustom).location;
		}
	}

	private List<String> buildOptions() {
		List<String> names = new ArrayList<>();
		for(int i = 0, l = EaglerProfile.customCapes.size(); i < l; ++i) {
			names.add(EaglerProfile.customCapes.get(i).name);
		}
		for(int i = 0, l = DefaultCapes.defaultCapesMap.length; i < l; ++i) {
			DefaultCapes cape = DefaultCapes.defaultCapesMap[i];
			names.add(Component.translatableWithFallback("eagler.cape." + cape.id, cape.name).getString());
		}
		return names;
	}

	@Override
	protected void init() {
		this.addRenderableWidget(new StringWidget(this.width / 2 - this.font.width(this.title) / 2, 15,
				this.font.width(this.title), 9, this.title, this.font));

		if(!EagRuntime.getConfiguration().isDemo()) {
			Component importExportText = Component.translatableWithFallback("editProfile.importExport", "Import/Export")
					.withStyle(ChatFormatting.UNDERLINE).withStyle(s -> s.withColor(0xCCCCCC));
			this.addRenderableWidget(new PlainTextButton(5, 5, this.font.width(importExportText), 10,
					importExportText, btn -> {
						safeProfile();
						this.minecraft.gui.setScreen(new EaglerImportExportScreen26(parent));
					}, this.font));
		}

		int boxX = this.width / 2 - 120;
		int boxY = this.height / 6 + 8;
		this.addRenderableWidget(new EaglerBorderBoxWidget26(boxX, boxY, 80, 130));
		// upstream previews the cape on the rotatable player model (parent screen's skin)
		this.addRenderableWidget(new EaglerCapedSkinWidget26(76, 126, this.minecraft.getEntityModels(),
				parent::previewSkin, this::selectedTexture)).setPosition(boxX + 2, boxY + 2);

		int x = this.width / 2 - 20;
		int y = this.height / 6;

		this.addRenderableWidget(new StringWidget(x, y + 36, 140, 9,
				CAPE_LABEL.copy().withStyle(s -> s.withColor(0xA0A0A0)), this.font));

		this.addRenderableWidget(Button.builder(ADD_CAPE, btn -> EagRuntime.displayFileChooser("image/png", "png"))
				.bounds(x - 1, y + 80, 71, 20).build());
		this.addRenderableWidget(Button.builder(CLEAR_CAPES, btn -> {
			EaglerProfile.clearCustomCapes();
			selectedSlot = 0;
			safeProfile();
			this.rebuildWidgets();
		}).bounds(x + 70, y + 80, 72, 20).build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), btn -> this.onDone())
				.bounds(this.width / 2 - 100, EaglerProfileScreen26.doneButtonY(this.height), 200, 20).build());

		// added last so the open overlay list renders above every other widget
		this.capeDropdown = this.addRenderableWidget(new EaglerDropdownWidget26(x, y + 52, this.font,
				buildOptions(), selectedSlot, this.height - 10, idx -> selectedSlot = idx));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// upstream edit cape screen uses the classic tiled dirt background
		this.extractMenuBackground(graphics);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if(capeDropdown != null && capeDropdown.isOpen()) {
			if(capeDropdown.mouseClicked(event, doubleClick)) {
				this.setFocused(capeDropdown);
				if(event.button() == 0) {
					this.setDragging(true);
				}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if(capeDropdown != null && capeDropdown.isOpen()) {
			return capeDropdown.mouseScrolled(x, y, scrollX, scrollY);
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void tick() {
		if(EagRuntime.fileChooserHasResult()) {
			FileChooserResult result = EagRuntime.getFileChooserResult();
			if(result != null) {
				handleNewCape(result);
			}
		}
	}

	private void handleNewCape(FileChooserResult result) {
		ImageData loadedCape = ImageData.loadImageFile(result.fileData, ImageData.getMimeFromType(result.fileName));
		if(loadedCape == null) {
			EagRuntime.showPopup(Component.translatableWithFallback("editCape.cape.invalidFormat",
					"The selected file '%s' is not a supported format!", result.fileName).getString());
			return;
		}
		if((loadedCape.width == 32 || loadedCape.width == 64) && loadedCape.height == 32) {
			byte[] resized = new byte[1173];
			SkinConverter.convertCape32x32RGBAto23x17RGB(loadedCape, resized);
			int k = EaglerProfile.addCustomCape(result.fileName, resized);
			if(k != -1) {
				selectedSlot = k;
				safeProfile();
				this.rebuildWidgets();
			}
		}else {
			EagRuntime.showPopup(Component.translatableWithFallback("editCape.cape.invalidSize",
					"The selected image '%s' is not the right size!\nEaglercraft only supports 32x32 or 64x32 capes",
					result.fileName).getString());
		}
	}

	protected void safeProfile() {
		int customLen = EaglerProfile.customCapes.size();
		if(selectedSlot >= 0 && selectedSlot < customLen) {
			EaglerProfile.presetCapeId = -1;
			EaglerProfile.customCapeId = selectedSlot;
		}else {
			EaglerProfile.presetCapeId = selectedSlot - customLen;
			EaglerProfile.customCapeId = -1;
		}
	}

	private void onDone() {
		safeProfile();
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public void onClose() {
		onDone();
	}

}

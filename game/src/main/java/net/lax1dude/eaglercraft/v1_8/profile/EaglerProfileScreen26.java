package net.lax1dude.eaglercraft.v1_8.profile;

import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.FileChooserResult;
import net.lax1dude.eaglercraft.v1_8.opengl.ImageData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * 26.2 upgrade of upstream profile/GuiScreenEditProfile at 1.8.8 layout
 * fidelity, rebuilt on 26.2's extraction-based widget GUI model: dirt tile
 * background, centered "Edit Profile" title, underlined "Import/Export" link
 * top-left, bordered dark preview box on the left with an underlined "Capes"
 * link above it, and a right column with the username field, the skin
 * dropdown (closed box + arrow button expanding to a scrollable overlay list
 * of custom skins first then the 24 defaults), "Add Skin"/"Clear List"
 * buttons, and a Done button tracking the content column. Keeps the upstream
 * control flow: username sanitize (16 chars, [A-Za-z0-9_], min 3 on save),
 * Add Skin via the platform file chooser (64x64, or 64x32 converted with
 * SkinConverter) with a Steve-vs-Alex model chooser for new skins, and Done =
 * sanitize + EaglerProfile.save() + optional default-username warning.
 */
public class EaglerProfileScreen26 extends Screen {

	private static final Component USERNAME_LABEL = Component.translatableWithFallback("editProfile.username", "Username");
	private static final Component SKIN_LABEL = Component.translatableWithFallback("editProfile.playerSkin", "Player Skin");
	private static final Component ADD_SKIN = Component.translatableWithFallback("editProfile.addSkin", "Add Skin");
	private static final Component CLEAR_SKINS = Component.translatableWithFallback("editProfile.clearSkin", "Clear List");
	private static final Component MODEL_QUESTION = Component.translatableWithFallback("editProfile.steveOrAlex", "What type of model is your skin for?");
	private static final Component MODEL_STEVE = Component.translatableWithFallback("editProfile.modelSteve", "Steve (Classic)");
	private static final Component MODEL_ALEX = Component.translatableWithFallback("editProfile.modelAlex", "Alex (Slim)");

	private final Screen parent;

	private EditBox usernameField;
	private String usernameValue;
	private EaglerDropdownWidget26 skinDropdown;
	private PlainTextButton capesButton;
	private Button modelButton;
	private Button steveChoiceButton;
	private Button alexChoiceButton;

	protected int selectedSlot;
	private boolean newSkinWaitSteveOrAlex = false;

	public EaglerProfileScreen26(Screen parent) {
		super(Component.translatableWithFallback("editProfile.title", "Edit Profile"));
		this.parent = parent;
		EaglerProfile.readIfNeeded();
		this.usernameValue = EaglerProfile.getName();
		this.selectedSlot = EaglerProfile.presetSkinId == -1 ? EaglerProfile.customSkinId
				: (EaglerProfile.presetSkinId + EaglerProfile.customSkins.size());
		if(this.selectedSlot < 0 || this.selectedSlot >= totalSlots()) {
			this.selectedSlot = EaglerProfile.customSkins.size();
		}
	}

	/**
	 * Re-syncs the widget state from EaglerProfile after an external change
	 * (profile .epk import) and rebuilds the layout.
	 */
	void refreshProfileState() {
		this.usernameValue = EaglerProfile.getName();
		this.selectedSlot = EaglerProfile.presetSkinId == -1 ? EaglerProfile.customSkinId
				: (EaglerProfile.presetSkinId + EaglerProfile.customSkins.size());
		if(this.selectedSlot < 0 || this.selectedSlot >= totalSlots()) {
			this.selectedSlot = EaglerProfile.customSkins.size();
		}
		this.newSkinWaitSteveOrAlex = false;
		if(this.minecraft != null) {
			this.rebuildWidgets();
		}
	}

	private int totalSlots() {
		return EaglerProfile.customSkins.size() + DefaultSkins.defaultSkinsMap.length;
	}

	private Identifier selectedTexture() {
		int numCustom = EaglerProfile.customSkins.size();
		if(selectedSlot >= 0 && selectedSlot < numCustom) {
			return EaglerProfile.customSkins.get(selectedSlot).getResource();
		}else {
			return DefaultSkins.getSkinFromId(selectedSlot - numCustom).location;
		}
	}

	private SkinModel selectedModel() {
		int numCustom = EaglerProfile.customSkins.size();
		if(selectedSlot >= 0 && selectedSlot < numCustom) {
			return EaglerProfile.customSkins.get(selectedSlot).model;
		}else {
			return DefaultSkins.getSkinFromId(selectedSlot - numCustom).model;
		}
	}

	private List<String> buildOptions() {
		List<String> names = new ArrayList<>();
		for(int i = 0, l = EaglerProfile.customSkins.size(); i < l; ++i) {
			names.add(EaglerProfile.customSkins.get(i).name);
		}
		for(int i = 0, l = DefaultSkins.defaultSkinsMap.length; i < l; ++i) {
			DefaultSkins skin = DefaultSkins.defaultSkinsMap[i];
			names.add(Component.translatableWithFallback("eagler.skin." + skin.id, skin.name).getString());
		}
		return names;
	}

	private static PlayerSkin makePreviewSkin(Identifier texture, PlayerModelType model) {
		return PlayerSkin.insecure(new ClientAsset.ResourceTexture(texture, texture), null, null, model);
	}

	PlayerSkin previewSkin() {
		if(selectedSlot >= totalSlots()) {
			selectedSlot = 0;
		}
		return makePreviewSkin(selectedTexture(), selectedModel().getPlayerModelType());
	}

	/** Done tracks the content column, clamped at least 24px above the bottom edge */
	static int doneButtonY(int screenHeight) {
		return Math.min(screenHeight / 6 + 168, screenHeight - 44);
	}

	@Override
	protected void init() {
		if(newSkinWaitSteveOrAlex && selectedSlot >= 0 && selectedSlot < EaglerProfile.customSkins.size()) {
			initModelChooser();
		}else {
			newSkinWaitSteveOrAlex = false;
			initMain();
		}
	}

	private void initMain() {
		this.steveChoiceButton = null;
		this.alexChoiceButton = null;
		this.addRenderableWidget(new StringWidget(this.width / 2 - this.font.width(this.title) / 2, 15,
				this.font.width(this.title), 9, this.title, this.font));

		if(!EagRuntime.getConfiguration().isDemo()) {
			Component importExportText = Component.translatableWithFallback("editProfile.importExport", "Import/Export")
					.withStyle(ChatFormatting.UNDERLINE).withStyle(s -> s.withColor(0xCCCCCC));
			this.addRenderableWidget(new PlainTextButton(5, 5, this.font.width(importExportText), 10,
					importExportText, btn -> {
						safeProfile();
						EaglerProfile.save();
						this.minecraft.gui.setScreen(new EaglerImportExportScreen26(this));
					}, this.font));
		}

		int boxX = this.width / 2 - 120;
		int boxY = this.height / 6 + 8;

		Component capesText = Component.translatableWithFallback("editProfile.capes", "Capes")
				.withStyle(ChatFormatting.UNDERLINE).withStyle(s -> s.withColor(0xA0A0A0));
		this.capesButton = this.addRenderableWidget(new PlainTextButton(boxX + 2, boxY - 11,
				this.font.width(capesText), 10, capesText, btn -> {
					safeProfile();
					this.minecraft.gui.setScreen(new EaglerCapeScreen26(this));
				}, this.font));
		updateCapesButtonVisibility();

		this.addRenderableWidget(new EaglerBorderBoxWidget26(boxX, boxY, 80, 130));
		this.addRenderableWidget(new EaglerCapedSkinWidget26(76, 126, this.minecraft.getEntityModels(),
				this::previewSkin, EaglerProfile::getActiveCapeResourceLocation)).setPosition(boxX + 2, boxY + 2);

		int x = this.width / 2 - 20;
		int y = this.height / 6;

		this.addRenderableWidget(new StringWidget(x, y + 8, 140, 9,
				USERNAME_LABEL.copy().withStyle(s -> s.withColor(0xA0A0A0)), this.font));
		this.usernameField = this.addRenderableWidget(new EditBox(this.font, x, y + 24, 140, 20, USERNAME_LABEL));
		this.usernameField.setMaxLength(16);
		this.usernameField.setValue(usernameValue);
		this.usernameField.setResponder(str -> {
			String sanitized = str.replaceAll("[^A-Za-z0-9]", "_");
			if(!sanitized.equals(str)) {
				this.usernameField.setValue(sanitized);
			}else {
				this.usernameValue = sanitized;
			}
		});

		// The 1.8 editor could reopen model selection for an existing custom skin.
		// Keep that choice explicit while the main preview remains drag-rotatable.
		this.modelButton = this.addRenderableWidget(Button.builder(Component.empty(), btn -> {
			if(selectedSlot >= 0 && selectedSlot < EaglerProfile.customSkins.size()) {
				newSkinWaitSteveOrAlex = true;
				this.rebuildWidgets();
			}
		}).bounds(x, y + 46, 140, 18).build());
		updateModelButton();

		this.addRenderableWidget(new StringWidget(x, y + 66, 140, 9,
				SKIN_LABEL.copy().withStyle(s -> s.withColor(0xA0A0A0)), this.font));

		this.addRenderableWidget(Button.builder(ADD_SKIN, btn -> EagRuntime.displayFileChooser("image/png", "png"))
				.bounds(x - 1, y + 110, 71, 20).build());
		this.addRenderableWidget(Button.builder(CLEAR_SKINS, btn -> {
			EaglerProfile.clearCustomSkins();
			selectedSlot = 0;
			safeProfile();
			EaglerProfile.save();
			this.rebuildWidgets();
		}).bounds(x + 70, y + 110, 72, 20).build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), btn -> this.onDone())
				.bounds(this.width / 2 - 100, doneButtonY(this.height), 200, 20).build());

		// added last so the open overlay list renders above every other widget
		this.skinDropdown = this.addRenderableWidget(new EaglerDropdownWidget26(x, y + 82, this.font,
				buildOptions(), selectedSlot, this.height - 10, idx -> {
					selectedSlot = idx;
					updateCapesButtonVisibility();
					updateModelButton();
				}));
	}

	private void updateCapesButtonVisibility() {
		if(capesButton != null) {
			SkinModel model = selectedModel();
			capesButton.visible = model == SkinModel.STEVE || model == SkinModel.ALEX;
		}
	}

	private void updateModelButton() {
		if(modelButton != null) {
			modelButton.visible = selectedSlot >= 0 && selectedSlot < EaglerProfile.customSkins.size();
			modelButton.setMessage(Component.translatableWithFallback("editProfile.model", "Model: %s",
					selectedModel() == SkinModel.ALEX ? MODEL_ALEX : MODEL_STEVE));
		}
	}

	static int modelPreviewHeight(int screenHeight) {
		return Math.max(24, Math.min(120, screenHeight - 76));
	}

	static int modelChooserY(int screenHeight) {
		return Math.max(28, Math.min(screenHeight / 4, screenHeight - modelPreviewHeight(screenHeight) - 28));
	}

	private SkinModel modelChooserPreview() {
		if(steveChoiceButton != null && steveChoiceButton.isHovered()) return SkinModel.STEVE;
		if(alexChoiceButton != null && alexChoiceButton.isHovered()) return SkinModel.ALEX;
		if(steveChoiceButton != null && steveChoiceButton.isFocused()) return SkinModel.STEVE;
		if(alexChoiceButton != null && alexChoiceButton.isFocused()) return SkinModel.ALEX;
		return selectedModel();
	}

	private void initModelChooser() {
		this.skinDropdown = null;
		this.capesButton = null;
		this.usernameField = null;
		this.modelButton = null;
		CustomSkin newSkin = EaglerProfile.customSkins.get(selectedSlot);
		Identifier texture = newSkin.getResource();

		FocusableTextWidget question = this.addRenderableWidget(FocusableTextWidget
				.builder(MODEL_QUESTION, this.font, 12).textWidth(Math.min(this.font.width(MODEL_QUESTION), this.width - 20)).build());
		question.setPosition(this.width / 2 - question.getWidth() / 2, Math.max(4, modelChooserY(this.height) - question.getHeight() - 8));

		int y = modelChooserY(this.height);
		int previewHeight = modelPreviewHeight(this.height);
		// GuiSkinRenderer reuses one PIP texture for all skin states in a frame.
		// Two equal-size previews alias that texture and both show the last (Alex)
		// render. One preview follows the hovered/focused choice without that alias.
		this.addRenderableWidget(new EaglerCapedSkinWidget26(70, previewHeight, this.minecraft.getEntityModels(),
				() -> makePreviewSkin(texture, modelChooserPreview().getPlayerModelType()),
				EaglerProfile::getActiveCapeResourceLocation)).setPosition(this.width / 2 - 35, y);

		this.steveChoiceButton = this.addRenderableWidget(Button.builder(MODEL_STEVE, btn -> selectNewSkinModel(SkinModel.STEVE))
				.bounds(this.width / 2 - 100, y + previewHeight + 4, 90, 20).build());
		this.alexChoiceButton = this.addRenderableWidget(Button.builder(MODEL_ALEX, btn -> selectNewSkinModel(SkinModel.ALEX))
				.bounds(this.width / 2 + 10, y + previewHeight + 4, 90, 20).build());
	}

	private void selectNewSkinModel(SkinModel model) {
		if(selectedSlot >= 0 && selectedSlot < EaglerProfile.customSkins.size()) {
			EaglerProfile.customSkins.get(selectedSlot).model = model;
			safeProfile();
			EaglerProfile.save();
		}
		newSkinWaitSteveOrAlex = false;
		this.rebuildWidgets();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// upstream edit profile screen uses the classic tiled dirt background
		this.extractMenuBackground(graphics);
	}

	/**
	 * While the dropdown overlay is open it swallows every click, mirroring
	 * upstream's actionPerformed being gated on !dropDownOpen.
	 */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Defensive: a throwing skin-widget/button handler must not crash the whole
		// click dispatch (Matej hit repeated "mouseClicked event handler" crashes on
		// this screen once audio started). Log the real cause and swallow so the UI
		// stays usable; the printed stack lets us root-cause the specific handler.
		try {
			if(skinDropdown != null && skinDropdown.isOpen()) {
				if(skinDropdown.mouseClicked(event, doubleClick)) {
					this.setFocused(skinDropdown);
					if(event.button() == 0) {
						this.setDragging(true);
					}
				}
				return true;
			}
			return super.mouseClicked(event, doubleClick);
		} catch (Throwable t) {
			net.lax1dude.eaglercraft.v1_8.EagRuntime.debugPrintStackTraceToSTDERR(t);
			return true;
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if(skinDropdown != null && skinDropdown.isOpen()) {
			return skinDropdown.mouseScrolled(x, y, scrollX, scrollY);
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void tick() {
		if(EagRuntime.fileChooserHasResult()) {
			FileChooserResult result = EagRuntime.getFileChooserResult();
			if(result != null && !newSkinWaitSteveOrAlex) {
				handleNewSkin(result);
			}
		}
	}

	private void handleNewSkin(FileChooserResult result) {
		ImageData loadedSkin = ImageData.loadImageFile(result.fileData, ImageData.getMimeFromType(result.fileName));
		if(loadedSkin == null) {
			EagRuntime.showPopup(Component.translatableWithFallback("editProfile.skin.invalidFormat",
					"The selected file '%s' is not a supported format!", result.fileName).getString());
			return;
		}
		boolean isLegacy = loadedSkin.width == 64 && loadedSkin.height == 32;
		boolean isModern = loadedSkin.width == 64 && loadedSkin.height == 64;
		if(isLegacy) {
			ImageData newSkin = new ImageData(64, 64, true);
			SkinConverter.convert64x32to64x64(loadedSkin, newSkin);
			loadedSkin = newSkin;
			isModern = true;
		}
		if(!isModern) {
			EagRuntime.showPopup(Component.translatableWithFallback("editProfile.skin.invalidSize",
					"The selected image '%s' is not the right size!\nEaglercraft only supports 64x32 or 64x64 skins",
					result.fileName).getString());
			return;
		}
		byte[] rawSkin = new byte[16384];
		for(int i = 0, j, k; i < 4096; ++i) {
			j = i << 2;
			k = loadedSkin.pixels[i];
			rawSkin[j] = (byte)(k >>> 24);
			rawSkin[j + 1] = (byte)(k >>> 16);
			rawSkin[j + 2] = (byte)(k >>> 8);
			rawSkin[j + 3] = (byte)(k & 0xFF);
		}
		for(int y = 20; y < 32; ++y) {
			for(int x = 16; x < 40; ++x) {
				rawSkin[(y << 8) | (x << 2)] = (byte)0xff;
			}
		}
		int k = EaglerProfile.addCustomSkin(result.fileName, rawSkin);
		if(k != -1) {
			selectedSlot = k;
			newSkinWaitSteveOrAlex = true;
			safeProfile();
			EaglerProfile.save();
			this.rebuildWidgets();
		}
	}

	protected void safeProfile() {
		int customLen = EaglerProfile.customSkins.size();
		if(selectedSlot >= 0 && selectedSlot < customLen) {
			EaglerProfile.presetSkinId = -1;
			EaglerProfile.customSkinId = selectedSlot;
		}else {
			EaglerProfile.presetSkinId = selectedSlot - customLen;
			EaglerProfile.customSkinId = -1;
		}
		String name = usernameValue == null ? "" : usernameValue.trim().replaceAll("[^A-Za-z0-9]", "_");
		while(name.length() < 3) {
			name = name + "_";
		}
		if(name.length() > 16) {
			name = name.substring(0, 16);
		}
		EaglerProfile.setName(name);
	}

	private void onDone() {
		// Log failures and try to leave the profile screen.
		try {
			safeProfile();
			EaglerProfile.save();
			if(EaglerProfile.isDefaultUsername(EaglerProfile.getName())
					&& !EaglerProfile.isHideDefaultUsernameWarning()) {
				this.minecraft.gui.setScreen(new EaglerDefaultUsernameNoteScreen(this, parent));
				return;
			}
			this.minecraft.gui.setScreen(parent);
		}catch(Throwable t) {
			System.err.println("eagler: profile Done handler failed; closing to parent. Root cause:");
			EagRuntime.debugPrintStackTraceToSTDERR(t);
			try {
				this.minecraft.gui.setScreen(parent);
			}catch(Throwable t2) {
				System.err.println("eagler: fallback setScreen(parent) also failed:");
				EagRuntime.debugPrintStackTraceToSTDERR(t2);
			}
		}
	}

	@Override
	public void onClose() {
		if(newSkinWaitSteveOrAlex) {
			selectNewSkinModel(selectedModel());
			return;
		}
		onDone();
	}

}

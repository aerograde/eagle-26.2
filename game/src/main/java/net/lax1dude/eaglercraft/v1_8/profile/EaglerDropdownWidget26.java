package net.lax1dude.eaglercraft.v1_8.profile;

import java.util.List;
import java.util.function.IntConsumer;

import com.mojang.blaze3d.platform.cursor.CursorTypes;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 26.2 port of the hand-rolled skin/cape dropdown from upstream
 * GuiScreenEditProfile (drawScreen lines ~149-198 and mouseClicked lines
 * ~488-519): a closed 140x22 box showing the selected entry plus a 20px
 * arrow button, which expands into a scrollable overlay list of all entries
 * that renders above every other widget (via nextStratum) and swallows all
 * clicks while open. Colors, row metrics, scrollbar math and the scroll-wheel
 * step of 3 are kept identical to upstream. The owning screen must add this
 * widget last, give it the list bottom limit, and route mouse events to it
 * first while it is open (upstream gated actionPerformed on !dropDownOpen).
 */
public class EaglerDropdownWidget26 extends AbstractWidget {

	public static final int DROPDOWN_WIDTH = 140;
	public static final int DROPDOWN_HEIGHT = 22;

	private static final Identifier EAGLER_GUI = Identifier.parse("eagler:gui/eagler_gui.png");

	private final Font font;
	private final List<String> options;
	private final IntConsumer onSelect;

	private int selectedSlot;
	private boolean dropDownOpen = false;
	private int slotsVisible = 0;
	private int scrollPos = -1;
	private int listHeight = 0;
	private boolean draggingScrollbar = false;
	private int listBottomLimit;

	public EaglerDropdownWidget26(int x, int y, Font font, List<String> options, int selectedSlot,
			int listBottomLimit, IntConsumer onSelect) {
		super(x, y, DROPDOWN_WIDTH, DROPDOWN_HEIGHT, Component.empty());
		this.font = font;
		this.options = options;
		this.selectedSlot = selectedSlot;
		this.listBottomLimit = listBottomLimit;
		this.onSelect = onSelect;
	}

	public boolean isOpen() {
		return dropDownOpen;
	}

	public int getSelectedSlot() {
		return selectedSlot;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int x = this.getX();
		int y = this.getY();

		// closed state box: gray border, black text area, black arrow button
		graphics.fill(x, y, x + DROPDOWN_WIDTH, y + DROPDOWN_HEIGHT, 0xFFA0A0A0);
		graphics.fill(x + 1, y + 1, x + DROPDOWN_WIDTH - 21, y + DROPDOWN_HEIGHT - 1, 0xFF000000);
		graphics.fill(x + DROPDOWN_WIDTH - 20, y + 1, x + DROPDOWN_WIDTH - 1, y + DROPDOWN_HEIGHT - 1, 0xFF000000);
		graphics.blit(RenderPipelines.GUI_TEXTURED, EAGLER_GUI, x + DROPDOWN_WIDTH - 18, y + 3, 0.0F, 0.0F, 16, 16,
				256, 256);
		if(selectedSlot >= 0 && selectedSlot < options.size()) {
			// clip long names to the text area (box minus arrow button minus padding)
			graphics.text(font, clipText(options.get(selectedSlot), DROPDOWN_WIDTH - 21 - 5 - 2), x + 5, y + 7,
					0xFFE0E0E0);
		}

		if(isMouseOverArrowButton(mouseX, mouseY)) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}

		// upstream recomputes the open list geometry every frame
		int listY = y + DROPDOWN_HEIGHT - 1;
		int avail = listBottomLimit - listY;
		slotsVisible = avail / 10;
		if(slotsVisible > options.size()) {
			slotsVisible = options.size();
		}
		if(slotsVisible < 1) {
			slotsVisible = 1;
		}
		listHeight = slotsVisible * 10 + 7;
		if(scrollPos == -1) {
			scrollPos = selectedSlot - 2;
		}
		clampScroll();

		if(dropDownOpen) {
			// draw the expanded list above every other widget on the screen
			graphics.nextStratum();
			graphics.fill(x, listY, x + DROPDOWN_WIDTH, listY + listHeight, 0xFFA0A0A0);
			graphics.fill(x + 1, listY + 1, x + DROPDOWN_WIDTH - 1, listY + listHeight - 1, 0xFF000000);
			for(int i = 0; i < slotsVisible; ++i) {
				if(i + scrollPos < options.size()) {
					if(selectedSlot == i + scrollPos) {
						graphics.fill(x + 1, listY + i * 10 + 4, x + DROPDOWN_WIDTH - 1, listY + i * 10 + 14,
								0x77FFFFFF);
					}else if(mouseX >= x && mouseX < (x + DROPDOWN_WIDTH - 10) && mouseY >= (listY + i * 10 + 5)
							&& mouseY < (listY + i * 10 + 15)) {
						graphics.fill(x + 1, listY + i * 10 + 4, x + DROPDOWN_WIDTH - 1, listY + i * 10 + 14,
								0x55FFFFFF);
						graphics.requestCursor(CursorTypes.POINTING_HAND);
					}
					// clip long names to the row (list minus scrollbar column minus padding)
					graphics.text(font, clipText(options.get(i + scrollPos), DROPDOWN_WIDTH - 10 - 5 - 2), x + 5,
							listY + 5 + i * 10, 0xFFE0E0E0);
				}
			}
			if(options.size() > 0) {
				int scrollerSize = listHeight * slotsVisible / options.size();
				int scrollerPos = listHeight * scrollPos / options.size();
				graphics.fill(x + DROPDOWN_WIDTH - 4, listY + scrollerPos + 1, x + DROPDOWN_WIDTH - 1,
						listY + scrollerPos + scrollerSize, 0xFF888888);
			}
		}
	}

	/** truncate to maxWidth px, appending "..." when the width allows, plain clip otherwise */
	private String clipText(String text, int maxWidth) {
		if(font.width(text) <= maxWidth) {
			return text;
		}
		int dots = font.width("...");
		if(dots < maxWidth) {
			return font.plainSubstrByWidth(text, maxWidth - dots) + "...";
		}
		return font.plainSubstrByWidth(text, maxWidth);
	}

	private void clampScroll() {
		if(scrollPos > (options.size() - slotsVisible)) {
			scrollPos = (options.size() - slotsVisible);
		}
		if(scrollPos < 0) {
			scrollPos = 0;
		}
	}

	private boolean isMouseOverArrowButton(double mx, double my) {
		return mx >= this.getX() + DROPDOWN_WIDTH - 20 && mx < this.getX() + DROPDOWN_WIDTH && my >= this.getY()
				&& my < this.getY() + DROPDOWN_HEIGHT;
	}

	/**
	 * While open, the owning screen routes every click here and this method
	 * always consumes it, mirroring the upstream mouseClicked flow.
	 */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if(!this.isActive()) {
			return false;
		}
		if(event.button() != 0) {
			return dropDownOpen;
		}
		int mx = (int) event.x();
		int my = (int) event.y();
		if(isMouseOverArrowButton(mx, my)) {
			dropDownOpen = !dropDownOpen;
			draggingScrollbar = false;
			playDownSound(net.minecraft.client.Minecraft.getInstance().getSoundManager());
			return true;
		}
		if(!dropDownOpen) {
			return false;
		}
		int x = this.getX();
		int listY = this.getY() + DROPDOWN_HEIGHT - 1;
		// scrollbar column
		if(mx >= (x + DROPDOWN_WIDTH - 10) && mx < (x + DROPDOWN_WIDTH) && my >= listY && my < (listY + listHeight)) {
			draggingScrollbar = true;
			dragScrollbar(my);
			return true;
		}
		// list rows
		if(mx >= x && mx < (x + DROPDOWN_WIDTH - 10) && my >= listY && my < (listY + listHeight)) {
			for(int i = 0; i < slotsVisible; ++i) {
				if(i + scrollPos < options.size()) {
					if(my >= (listY + i * 10 + 5) && my < (listY + i * 10 + 15)) {
						selectedSlot = i + scrollPos;
						dropDownOpen = false;
						draggingScrollbar = false;
						playDownSound(net.minecraft.client.Minecraft.getInstance().getSoundManager());
						if(onSelect != null) {
							onSelect.accept(selectedSlot);
						}
						return true;
					}
				}
			}
			return true;
		}
		// inside the closed box row: swallow without closing (upstream behavior)
		if(mx >= x && mx < (x + DROPDOWN_WIDTH) && my >= this.getY() && my < listY) {
			return true;
		}
		// anywhere else: close the list and swallow the click
		dropDownOpen = false;
		draggingScrollbar = false;
		return true;
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		if(dropDownOpen && draggingScrollbar) {
			dragScrollbar((int) event.y());
		}
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		draggingScrollbar = false;
	}

	private void dragScrollbar(int my) {
		if(options.size() > 0 && listHeight > 0) {
			int listY = this.getY() + DROPDOWN_HEIGHT - 1;
			int scrollerSize = listHeight * slotsVisible / options.size();
			scrollPos = (my - listY - (scrollerSize / 2)) * options.size() / listHeight;
			clampScroll();
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if(dropDownOpen) {
			if(scrollY < 0.0D) {
				scrollPos += 3;
			}else if(scrollY > 0.0D) {
				scrollPos -= 3;
			}
			clampScroll();
			return true;
		}
		return false;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}

}

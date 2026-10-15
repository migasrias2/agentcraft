package dev.agentcraft.client.gallery;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.monitor.LocalImages;
import dev.agentcraft.client.monitor.MediaViewScreen;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * The big view of a painting from the gallery folder: as large as the window allows, with its name, file
 * and position; Left / Right browse (an item an agent handed you opens in the media view), O (or the
 * button) opens the original in Preview. Same panel look as the media view.
 */
public final class GalleryViewScreen extends Screen {
	private static final int MAX_W = 1000;
	private static final int BTN_H = 20;
	private final List<GalleryItem> items;
	private int index;
	private String flash = "";
	private long flashUntil;
	private final int[][] buttons = new int[3][];

	GalleryViewScreen(List<GalleryItem> items, int index) {
		super(Component.literal("Gallery"));
		this.items = List.copyOf(items);
		this.index = Math.max(0, Math.min(index, items.size() - 1));
	}

	/** Open the big view for {@code item} (a show_media item gets the media view), browsing {@code all}. */
	static Screen forItem(List<GalleryItem> all, GalleryItem item) {
		if (item.isMedia()) {
			return new MediaViewScreen(item.clipId());
		}
		return new GalleryViewScreen(all, Math.max(0, all.indexOf(item)));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// no dimming: the room stays visible around the panel
	}

	@Override
	public void removed() {
		LocalImages.clear();
	}

	private GalleryItem current() {
		return items.get(index);
	}

	private void step(int delta) {
		int next = Math.max(0, Math.min(items.size() - 1, index + delta));
		if (next == index) {
			return;
		}
		index = next;
		if (current().isMedia()) {
			minecraft.gui.setScreen(new MediaViewScreen(current().clipId()));
		}
	}

	private void openInPreview() {
		GalleryItem it = current();
		if (it.file() == null) {
			return;
		}
		flash("Opening " + it.title() + " in Preview…");
		OpenFile.inViewer(it.file()).thenAccept(ok -> minecraft.execute(() -> flash(ok ? "Opened in Preview" : "Could not open it")));
	}

	private void flash(String text) {
		flash = text;
		flashUntil = System.currentTimeMillis() + 3500;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.isEscape()) {
			onClose();
			return true;
		}
		switch (e.input()) {
			case InputConstants.KEY_LEFT -> step(-1);
			case InputConstants.KEY_RIGHT -> step(1);
			case InputConstants.KEY_O -> openInPreview();
			default -> {
				return super.keyPressed(e);
			}
		}
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (int i = 0; i < buttons.length; i++) {
				int[] r = buttons[i];
				if (r != null && e.x() >= r[0] && e.x() < r[0] + r[2] && e.y() >= r[1] && e.y() < r[1] + BTN_H) {
					switch (i) {
						case 0 -> step(-1);
						case 1 -> step(1);
						default -> openInPreview();
					}
					return true;
				}
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		GalleryItem item = current();
		LocalImages.State st = item.file() == null ? null : LocalImages.get(item.file().toString());
		LocalImages.Shown shown = st == null ? null : st.shown();
		Font font = this.font;
		Kit.Padding pp = Kit.padding("panel_paper");
		int ink = UiStyle.color("paper.text");
		int muted = UiStyle.color("paper.muted");

		int panelW = Math.min(MAX_W, this.width - 24);
		int innerW = panelW - pp.left() - pp.right();
		int headH = 14;
		int footH = 14 + BTN_H + 6;
		int maxImgH = this.height - 24 - pp.top() - pp.bottom() - headH - footH - 4;
		int imgW;
		int imgH;
		if (shown != null) {
			double s = Math.min(Math.min(innerW / (double) shown.w(), maxImgH / (double) shown.h()), 2.0);
			imgW = Math.max(40, (int) (shown.w() * s));
			imgH = Math.max(30, (int) (shown.h() * s));
		} else {
			imgW = innerW;
			imgH = 80;
		}
		int bodyW = Math.max(imgW, Math.min(innerW, 420));
		int w = bodyW + pp.left() + pp.right();
		int h = pp.top() + headH + imgH + 4 + footH + pp.bottom();
		int x0 = (this.width - w) / 2;
		int y0 = (this.height - h) / 2;
		Panels.panel(g, x0, y0, w, h);
		int ix = x0 + pp.left();
		int y = y0 + pp.top();

		Panels.text(g, font, TextUtil.ellipsize(font, "Gallery", bodyW / 2), ix, y + 2, ink);
		String right = (index + 1) + " / " + items.size() + " · " + UiBits.ago(item.time());
		Panels.text(g, font, right, ix + bodyW - font.width(right), y + 2, muted);
		y += headH;

		int imgX = ix + (bodyW - imgW) / 2;
		Panels.inset(g, imgX - 2, y - 2, imgW + 4, imgH + 4);
		if (shown != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, shown.texture(), imgX, y, 0, 0, imgW, imgH, shown.w(), shown.h(), shown.w(), shown.h());
		} else {
			String l1 = st != null && st.error() != null ? st.error() : "Loading…";
			Panels.text(g, font, l1, imgX + (imgW - font.width(l1)) / 2, y + (imgH - 8) / 2, ink);
		}
		y += imgH + 4;

		Panels.text(g, font, TextUtil.ellipsize(font, item.title(), bodyW), ix, y + 2, ink);
		String where = item.file() == null ? "" : item.file().getFileName().toString();
		Panels.text(g, font, TextUtil.ellipsize(font, where, bodyW), ix, y + 12, muted);
		y += 24;

		int bx = ix;
		bx = button(g, 0, "< Prev", bx, y, 56, mouseX, mouseY, index == 0) + 4;
		bx = button(g, 1, "Next >", bx, y, 56, mouseX, mouseY, index >= items.size() - 1) + 4;
		int ow = 130;
		button(g, 2, "Open in Preview [O]", ix + bodyW - ow, y, ow, mouseX, mouseY, item.file() == null);
		long flashLeft = flashUntil - System.currentTimeMillis();
		String hint = flashLeft > 0 ? flash : "Left / Right browse · Esc close";
		Panels.text(g, font, TextUtil.ellipsize(font, hint, Math.max(40, bodyW - (bx - ix) - ow - 12)), bx + 4, y + 6, flashLeft > 0 ? ink : muted);
	}

	private int button(GuiGraphicsExtractor g, int slot, String label, int x, int y, int w, int mx, int my, boolean disabled) {
		buttons[slot] = disabled ? null : new int[] {x, y, w};
		boolean hover = !disabled && mx >= x && mx < x + w && my >= y && my < y + BTN_H;
		Panels.button(g, this.font, label, x, y, w, slot == 2, hover, disabled);
		return x + w;
	}
}

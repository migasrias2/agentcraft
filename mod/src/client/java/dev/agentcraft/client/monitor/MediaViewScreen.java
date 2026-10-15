package dev.agentcraft.client.monitor;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.foreman.Foreman;
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
import org.jspecify.annotations.Nullable;

/**
 * The enlarged media screen: right-click the Screening Room's big screen to see what agents handed
 * you (images, videos) as large as the window allows, with the agent, caption, kind and age. Left /
 * right browse the gallery, Space plays or pauses a video (the world screen follows), O (or the
 * button) asks the Foreman to open the original file in the OS default player, where videos play
 * with sound. Shootable as {@code dev.screen {open:"media"}}.
 */
public final class MediaViewScreen extends Screen {
	private static final int MAX_W = 1000;
	private static final int BTN_H = 20;
	private @Nullable String selectedId;
	private String flash = "";
	private long flashUntil;
	private final int[][] buttons = new int[4][];

	/** @param startId the item to start on, or null for the newest. */
	public MediaViewScreen(@Nullable String startId) {
		super(Component.literal("Media"));
		this.selectedId = startId;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// no dimming: the studio stays visible around the panel
	}

	private int index(List<MediaStore.Clip> items) {
		if (selectedId != null) {
			for (int i = 0; i < items.size(); i++) {
				if (items.get(i).meta.id().equals(selectedId)) {
					return i;
				}
			}
		}
		return items.size() - 1;
	}

	private void step(int delta) {
		List<MediaStore.Clip> items = MediaStore.items();
		if (items.isEmpty()) {
			return;
		}
		int i = Math.max(0, Math.min(items.size() - 1, index(items) + delta));
		selectedId = items.get(i).meta.id();
		items.get(i).restart(System.currentTimeMillis());
	}

	private MediaStore.@Nullable Clip current() {
		List<MediaStore.Clip> items = MediaStore.items();
		return items.isEmpty() ? null : items.get(index(items));
	}

	private void togglePause() {
		MediaStore.Clip c = current();
		if (c != null && c.isVideo()) {
			long now = System.currentTimeMillis();
			c.setPaused(!c.paused, now);
		}
	}

	private void openInPlayer() {
		MediaStore.Clip c = current();
		if (c == null) {
			return;
		}
		flash("Asking the Foreman to open " + c.meta.name() + "…");
		try {
			Foreman.openMedia(c.meta.id()).whenComplete((ack, err) -> {
				if (err != null) {
					flash("Could not reach the Foreman: " + TextUtil.firstLine(String.valueOf(err.getMessage())));
				} else if (!ack.ok()) {
					flash("Could not open it: " + (ack.error() == null ? "the Foreman said no" : ack.error()));
				} else {
					flash("Opened in your player");
				}
			});
		} catch (RuntimeException e) {
			flash("The Foreman is not connected");
		}
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
			case InputConstants.KEY_HOME -> {
				selectedId = null;
				step(-MediaStore.MAX_ITEMS);
			}
			case InputConstants.KEY_END -> {
				selectedId = null;
			}
			case InputConstants.KEY_SPACE -> togglePause();
			case InputConstants.KEY_O -> openInPlayer();
			default -> {
				return super.keyPressed(e);
			}
		}
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) { // 26.3: SDL numbering, left = 1
			for (int i = 0; i < buttons.length; i++) {
				int[] r = buttons[i];
				if (r != null && e.x() >= r[0] && e.x() < r[0] + r[2] && e.y() >= r[1] && e.y() < r[1] + BTN_H) {
					switch (i) {
						case 0 -> step(-1);
						case 1 -> step(1);
						case 2 -> togglePause();
						default -> openInPlayer();
					}
					return true;
				}
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		long now = System.currentTimeMillis();
		List<MediaStore.Clip> items = MediaStore.items();
		MediaStore.Clip clip = items.isEmpty() ? null : items.get(index(items));
		MediaStore.Shown shown = clip == null ? null : MediaStore.frameFor(clip, now);
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
			double s = Math.min(innerW / (double) shown.w(), maxImgH / (double) shown.h());
			s = Math.min(s, 2.0);
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

		// header: who, then position in the gallery
		String who = clip == null ? "Screening Room" : UiBits.agentName(clip.meta.agentId());
		Panels.text(g, font, TextUtil.ellipsize(font, who, bodyW / 2), ix, y + 2, clip == null ? ink : UiStyle.agentOnLight(clip.meta.agentId()));
		String right = clip == null ? "empty" : (index(items) + 1) + " / " + items.size() + " · " + MediaScreenDraw.right(now, clip);
		Panels.text(g, font, right, ix + bodyW - font.width(right), y + 2, muted);
		y += headH;

		// the item (or the empty / loading state)
		int imgX = ix + (bodyW - imgW) / 2;
		Panels.inset(g, imgX - 2, y - 2, imgW + 4, imgH + 4);
		if (shown != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, shown.texture(), imgX, y, 0, 0, imgW, imgH, shown.w(), shown.h(), shown.w(), shown.h());
		} else {
			String l1 = clip == null ? MediaScreenDraw.EMPTY_1 : clip.failed() ? "This one can't be shown here" : "Loading…";
			String l2 = clip == null ? MediaScreenDraw.EMPTY_2 : clip.failed() ? "Press O to open the original in your player." : "";
			int cy = y + (imgH - 20) / 2;
			Panels.text(g, font, l1, imgX + (imgW - font.width(l1)) / 2, cy, ink);
			String t2 = TextUtil.ellipsize(font, l2, imgW - 8);
			Panels.text(g, font, t2, imgX + (imgW - font.width(t2)) / 2, cy + 11, muted);
		}
		y += imgH + 4;

		// caption line
		if (clip != null) {
			String cap = MediaScreenDraw.what(clip);
			Panels.text(g, font, TextUtil.ellipsize(font, cap, bodyW), ix, y + 2, ink);
			if (clip.meta.caption() != null && !clip.meta.caption().isBlank()) {
				Panels.text(g, font, TextUtil.ellipsize(font, clip.meta.name(), bodyW), ix, y + 12, muted);
			}
		}
		y += 24;

		// buttons
		boolean video = clip != null && clip.isVideo();
		int by = y;
		int bx = ix;
		bx = button(g, 0, "< Prev", bx, by, 56, mouseX, mouseY, clip == null || index(items) == 0) + 4;
		bx = button(g, 1, "Next >", bx, by, 56, mouseX, mouseY, clip == null || index(items) >= items.size() - 1) + 4;
		bx = button(g, 2, clip != null && clip.paused ? "Play [Space]" : "Pause [Space]", bx, by, 92, mouseX, mouseY, !video) + 4;
		int ow = 120;
		button(g, 3, "Open in player [O]", ix + bodyW - ow, by, ow, mouseX, mouseY, clip == null);
		long flashLeft = flashUntil - now;
		String hint = flashLeft > 0 ? flash : "Left / Right browse · Esc close";
		Panels.text(g, font, TextUtil.ellipsize(font, hint, Math.max(40, bodyW - (bx - ix) - ow - 12)), bx + 4, by + 6, flashLeft > 0 ? ink : muted);
	}

	private int button(GuiGraphicsExtractor g, int slot, String label, int x, int y, int w, int mx, int my, boolean disabled) {
		buttons[slot] = disabled ? null : new int[] {x, y, w};
		boolean hover = !disabled && mx >= x && mx < x + w && my >= y && my < y + BTN_H;
		Panels.button(g, this.font, label, x, y, w, slot == 3, hover, disabled);
		return x + w;
	}
}

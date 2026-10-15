package dev.agentcraft.client.library;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.diff.ReviewKit;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Doc;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Reads a document an agent handed you ({@code doc.upsert}), on a warm sheet of paper: title,
 * "from <agent>", the file name, the agent's note as a quote, then the markdown rendered by
 * {@link MdLayout}. Opening it tells the Foreman it was read ({@code doc.read}) and turns a page.
 *
 * <pre>
 * keys: wheel / Up Down / j k / PgUp PgDn / Space / Home End scroll · c copy markdown · Esc close
 * </pre>
 */
public final class DocScreen extends Screen {
	private final String docId;
	private @Nullable Doc doc;
	private @Nullable MdLayout body;
	private List<FormattedCharSequence> noteLines = List.of();
	private float scroll;
	private float scrollTarget;
	private long lastNanos;
	private boolean dragThumb;
	private double dragOffset;
	private boolean opened;
	private @Nullable String flash;
	private long flashUntil;

	private int px;
	private int py;
	private int pw;
	private int ph;
	private int ix;
	private int iy;
	private int iw;
	private int ih;
	private int sheetY;
	private int sheetH;
	private int textX;
	private int textW;
	private int textY;
	private int textH;
	private int noteY;

	public DocScreen(String docId) {
		super(Component.literal("Document"));
		this.docId = docId;
	}

	/** Open a handed-over document by id (client thread); false when it is unknown. */
	public static boolean open(String docId) {
		ForemanState st = Foreman.state();
		if (st == null || st.doc(docId) == null) {
			return false;
		}
		Minecraft.getInstance().gui.setScreen(new DocScreen(docId));
		return true;
	}

	/** Newest handed-over document (any agent), or null. */
	public static @Nullable Doc newest() {
		ForemanState st = Foreman.state();
		if (st == null) {
			return null;
		}
		List<Doc> all = st.docs();
		return all.isEmpty() ? null : all.get(all.size() - 1);
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		ForemanState st = Foreman.state();
		doc = st == null ? null : st.doc(docId);
		int margin = width >= 900 ? 16 : width >= 560 ? 10 : 6;
		pw = Math.min(width - 2 * margin, 760);
		ph = Math.min(height - 2 * Math.max(6, margin - 2), 640);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		ix = px + 8;
		iy = py + 8;
		iw = pw - 16;
		ih = ph - 17;
		sheetY = iy + 18;
		sheetH = ih - 18 - 17;
		textW = Math.min(iw - 36, 600);
		textX = ix + (iw - 8 - textW) / 2;
		noteLines = List.of();
		int y = sheetY + 36;
		Doc d = doc;
		if (d != null && d.note() != null && !d.note().isBlank()) {
			List<FormattedCharSequence> lines = TextUtil.wrap(font, d.note().strip(), textW - 10);
			noteLines = lines.size() > 4 ? lines.subList(0, 4) : lines;
			noteY = y;
			y += noteLines.size() * 10 + 8;
		}
		textY = y;
		textH = Math.max(20, sheetY + sheetH - 6 - textY);
		buildBody();
		if (!opened) {
			opened = true;
			onOpened();
		}
	}

	private void buildBody() {
		Doc d = doc;
		if (d == null) {
			body = null;
			return;
		}
		List<Markdown.Block> blocks = Markdown.parse(d.markdown());
		if (!blocks.isEmpty() && blocks.get(0).type == Markdown.Type.H1 && norm(Markdown.plain(blocks.get(0).text)).equals(norm(d.title()))) {
			blocks = blocks.subList(1, blocks.size());
		}
		body = MdLayout.build(font, blocks, textW, Set.of());
		scroll = scrollTarget = Math.min(scroll, maxScroll());
	}

	private static String norm(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9#]+", " ").trim();
	}

	/** Page-turn sound, local read state and the doc.read message (never throws). */
	private void onOpened() {
		Doc d = doc;
		if (d == null) {
			return;
		}
		try {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1.0f, 0.7f));
		} catch (Throwable t) {
			AgentCraft.LOGGER.debug("page-turn sound failed", t);
		}
		if (d.read()) {
			return;
		}
		try {
			Foreman.state().markDocRead(d.id());
			if (Foreman.connected()) {
				Foreman.readDoc(d.id()).exceptionally(t -> {
					AgentCraft.LOGGER.warn("doc.read failed: {}", t.toString());
					return null;
				});
			}
		} catch (Throwable t) {
			AgentCraft.LOGGER.warn("doc.read failed", t);
		}
	}

	private int maxScroll() {
		return body == null ? 0 : Math.max(0, body.height - textH);
	}

	// ------------------------------------------------------------------ frame

	private void animate() {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		float k = 1 - (float) Math.exp(-dt * 16);
		scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll()));
		scroll += (scrollTarget - scroll) * k;
		if (Math.abs(scrollTarget - scroll) < 0.25f) {
			scroll = scrollTarget;
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (ReviewKit.blurBehind) {
			extractBlurredBackground(g);
		}
		g.fill(0, 0, width, height, UiStyle.withAlpha(UiStyle.WALNUT, 0x7A));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		animate();
		Panels.panel(g, px, py, pw, ph);
		Panels.sprite(g, Kit.HEADER, ix, iy, iw, 14);
		Panels.sprite(g, Kit.icon("memory"), ix + 4, iy + 1, 12, 12);
		ReviewKit.bold(g, font, "Handed over to you", ix + 20, iy + 3, ReviewKit.ink());
		Doc d = doc;
		if (d == null) {
			String m = "This document is gone (the Foreman restarted?).";
			g.text(font, m, ix + (iw - font.width(m)) / 2, sheetY + sheetH / 2, ReviewKit.muted(), false);
			super.extractRenderState(g, mx, my, a);
			return;
		}
		drawSheet(g, d, mx, my);
		drawFooter(g);
		super.extractRenderState(g, mx, my, a);
	}

	private void drawSheet(GuiGraphicsExtractor g, Doc d, int mx, int my) {
		int hi = UiStyle.color("palette.ui.panel_hi");
		g.fill(ix, sheetY, ix + iw, sheetY + sheetH, hi);
		ReviewKit.outline(g, ix, sheetY, iw, sheetH, UiStyle.color("palette.ui.panel_edge"));
		g.fill(ix + 1, sheetY + sheetH, ix + iw, sheetY + sheetH + 1, UiStyle.withAlpha(UiStyle.color("palette.ui.paper_deep"), 0xA0));
		int tx = textX;
		int ty = sheetY + 8;
		ReviewKit.bold(g, font, TextUtil.ellipsize(font, d.title(), textW), tx, ty, ReviewKit.ink());
		int my0 = ty + 13;
		int ax = tx;
		ReviewKit.face(g, font, d.agentId(), ax, my0 - 1, 8);
		ax += 11;
		String from = "from " + ReviewKit.agentName(d.agentId());
		g.text(font, from, ax, my0, ReviewKit.agentInk(d.agentId()), false);
		ax += font.width(from) + 4;
		String meta = (d.name().isEmpty() ? "" : d.name() + " · ") + ReviewKit.ago(d.at());
		g.text(font, TextUtil.ellipsize(font, meta, tx + textW - ax), ax, my0, ReviewKit.muted(), false);
		Panels.divider(g, tx, my0 + 11, textW + 6);
		if (!noteLines.isEmpty()) {
			int nh = noteLines.size() * 10;
			g.fill(tx, noteY, tx + 2, noteY + nh, UiStyle.CLAY);
			int ny = noteY;
			for (FormattedCharSequence line : noteLines) {
				g.text(font, line, tx + 8, ny, ReviewKit.muted(), false);
				ny += 10;
			}
		}
		if (body == null) {
			return;
		}
		float sy = Math.round(scroll * 2) / 2f;
		int base = (int) Math.floor(sy);
		g.enableScissor(tx - 4, textY, ix + iw - 8, textY + textH);
		g.pose().pushMatrix();
		g.pose().translate(0, -(sy - base));
		body.draw(g, font, tx, textY, base, textH, id -> null);
		g.pose().popMatrix();
		g.disableScissor();
		if (scroll > 0.5f) {
			g.fillGradient(tx - 4, textY, ix + iw - 9, textY + 7, hi, UiStyle.withAlpha(hi, 0));
		}
		if (scroll < maxScroll() - 0.5f) {
			g.fillGradient(tx - 4, textY + textH - 9, ix + iw - 9, textY + textH, UiStyle.withAlpha(hi, 0), hi);
		}
		if (body.height > textH) {
			int sbx = ix + iw - 8;
			Panels.sprite(g, Kit.SCROLL_TRACK, sbx, textY, 6, textH);
			int thumbH = thumbH();
			int thy = textY + Math.round((textH - thumbH) * (scroll / Math.max(1, maxScroll())));
			boolean hov = mx >= sbx && mx < sbx + 6 && my >= textY && my < textY + textH;
			Panels.sprite(g, hov || dragThumb ? Kit.SCROLL_THUMB_HOVER : Kit.SCROLL_THUMB, sbx, thy, 6, thumbH);
		}
	}

	private int thumbH() {
		return body == null ? 10 : Math.max(10, textH * textH / Math.max(1, body.height));
	}

	private void drawFooter(GuiGraphicsExtractor g) {
		int y = iy + ih - 12;
		long now = System.currentTimeMillis();
		boolean flashing = flash != null && now < flashUntil;
		if (flashing) {
			g.text(font, flash, ix + iw - font.width(flash), y + 2, UiStyle.color("paper.add_fg"), false);
		}
		String[][] hints = {{"j k", "scroll"}, {"PgUp PgDn", "page"}, {"c", "copy"}, {"Esc", "close"}};
		int x = ix;
		for (String[] h : hints) {
			x += ReviewKit.hint(g, font, h[0], h[1], x, y) + 10;
		}
	}

	// ------------------------------------------------------------------ input

	private void scrollBy(float px) {
		scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget + px));
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.isEscape()) {
			onClose();
			return true;
		}
		boolean shift = e.hasShiftDown();
		int page = Math.max(MdLayout.LINE_H, textH - 2 * MdLayout.LINE_H);
		switch (e.input()) {
			case InputConstants.KEY_DOWN, InputConstants.KEY_J -> scrollBy(MdLayout.LINE_H * (shift ? 5 : 1));
			case InputConstants.KEY_UP, InputConstants.KEY_K -> scrollBy(-MdLayout.LINE_H * (shift ? 5 : 1));
			case InputConstants.KEY_PAGEDOWN -> scrollBy(page);
			case InputConstants.KEY_PAGEUP -> scrollBy(-page);
			case InputConstants.KEY_SPACE -> scrollBy(shift ? -page : page);
			case InputConstants.KEY_HOME -> scrollTarget = 0;
			case InputConstants.KEY_END -> scrollTarget = maxScroll();
			case InputConstants.KEY_C -> copy();
			default -> {
				return super.keyPressed(e);
			}
		}
		return true;
	}

	private void copy() {
		Doc d = doc;
		if (d == null) {
			return;
		}
		minecraft.keyboardHandler.setClipboard(d.markdown());
		flash = "Copied as markdown";
		flashUntil = System.currentTimeMillis() + 2500;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == InputConstants.MOUSE_BUTTON_LEFT && body != null && body.height > textH) {
			int sbx = ix + iw - 8;
			double mx = e.x();
			double my = e.y();
			if (mx >= sbx && mx < sbx + 6 && my >= textY && my < textY + textH) {
				int thumbH = thumbH();
				float thy = textY + (textH - thumbH) * (scroll / Math.max(1, maxScroll()));
				if (my >= thy && my < thy + thumbH) {
					dragThumb = true;
					dragOffset = my - thy;
				} else {
					scrollTarget = (float) ((my - textY - thumbH / 2.0) / Math.max(1, textH - thumbH)) * maxScroll();
				}
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		dragThumb = false;
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragThumb && body != null) {
			float f = (float) ((e.y() - dragOffset - textY) / Math.max(1, textH - thumbH()));
			scrollTarget = scroll = Math.max(0, Math.min(maxScroll(), f * maxScroll()));
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		scrollBy((float) (-sy * MdLayout.LINE_H * 3));
		return true;
	}

	// ------------------------------------------------------------------ dev / QA

	JsonObject stateJson() {
		JsonObject o = new JsonObject();
		o.addProperty("docId", docId);
		o.addProperty("scroll", scroll);
		o.addProperty("bodyHeight", body == null ? 0 : body.height);
		o.addProperty("viewHeight", textH);
		return o;
	}
}

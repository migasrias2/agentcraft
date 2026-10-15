package dev.agentcraft.client.content;

import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.ContentRef;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.monitor.LocalImages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

/**
 * References: the videos the agents studied frame by frame. Pick one to flip through its contact sheets here
 * (4 x 4 grids of consecutive frames with timestamps), read its breakdown (cuts per minute, shot length, scene
 * list) and transcript, or send the sheets or a single frame to the Screening Room's big screen.
 */
final class ReferencesTab implements Tab {
	private enum View {
		SHEETS("Sheets"), BREAKDOWN("Breakdown"), TRANSCRIPT("Transcript");

		final String label;

		View(String label) {
			this.label = label;
		}
	}

	private static final int ROW_H = 28;
	private static final int MAX_READ = 60_000;

	private final Canvas.Scroll listScroll = new Canvas.Scroll();
	private final Canvas.Scroll textScroll = new Canvas.Scroll();
	private final MarkdownLines md = new MarkdownLines();
	private final Map<String, List<String>> sheetCache = new HashMap<>();
	private final Map<String, String> textCache = new HashMap<>();
	private View view = View.SHEETS;
	private int sheet;
	private int frame;
	private @Nullable String shownRef;

	@Override
	public void render(Canvas c, int x, int y, int w, int h) {
		ForemanState st = Foreman.state();
		List<ContentRef> refs = st == null ? List.of() : st.contentRefs();
		if (refs.isEmpty()) {
			c.card(x, y, w, h, Canvas.PANEL);
			c.textC("No reference studied yet", x + w / 2, y + Math.max(16, h / 2 - 24), Canvas.TEXT);
			int ty = y + Math.max(16, h / 2 - 24) + 18;
			for (String l : dev.agentcraft.client.ui.TextUtil.wrapPlain(c.font, "Reel studies a video frame by frame before copying what works: "
				+ "ask @reel study https://youtube.com/watch?v=... and its contact sheets show up here.", Math.min(w - 40, 380))) {
				c.textC(l, x + w / 2, ty, Canvas.MUTED);
				ty += 11;
			}
			return;
		}
		ContentRef sel = refs.getFirst();
		for (ContentRef r : refs) {
			if (r.id().equals(ContentState.refId())) {
				sel = r;
			}
		}
		if (!sel.id().equals(shownRef)) {
			shownRef = sel.id();
			sheet = 0;
			frame = 0;
			textScroll.off = 0;
		}
		int listW = Math.max(170, Math.min(250, w * 33 / 100));
		list(c, refs, sel, x, y, listW, h);
		detail(c, sel, x + listW + 10, y, w - listW - 10, h);
	}

	private void list(Canvas c, List<ContentRef> refs, ContentRef sel, int x, int y, int w, int h) {
		c.card(x, y, w, h, Canvas.PANEL);
		c.text("Studied videos", x + 10, y + 8, Canvas.TEXT);
		c.textR(String.valueOf(refs.size()), x + w - 10, y + 8, Canvas.FAINT);
		int vy = y + 22;
		int vh = h - 23;
		c.fill(x + 1, vy - 1, w - 2, 1, Canvas.RULE);
		c.g.enableScissor(x + 1, vy, x + w - 1, vy + vh);
		int ry = vy - listScroll.off;
		for (ContentRef r : refs) {
			boolean isSel = r.id().equals(sel.id());
			if (ry + ROW_H >= vy && ry <= vy + vh) {
				if (isSel) {
					c.fill(x + 4, ry, w - 8, ROW_H - 2, UiMix.alpha(Canvas.ACCENT, 38));
					c.fill(x + 4, ry, 2, ROW_H - 2, Canvas.ACCENT);
				} else if (c.hover(x + 4, ry, w - 8, ROW_H - 2) && c.hover(x, vy, w, vh)) {
					c.fill(x + 4, ry, w - 8, ROW_H - 2, Canvas.PANEL_HI);
				}
				c.fit(r.title() != null ? r.title() : r.source(), x + 12, ry + 5, w - 24, isSel ? Canvas.TEXT : UiMix.mix(Canvas.TEXT, Canvas.MUTED, 0.25f));
				String meta = (r.channel() != null ? r.channel() + "  -  " : "") + (r.views() != null ? ContentState.compact(r.views()) + " views  -  " : "")
					+ r.frameCount() + " frames";
				c.fit(meta, x + 12, ry + 16, w - 24, Canvas.FAINT);
				String id = r.id();
				c.hitIn(x, vy, w, vh, x + 4, ry, w - 8, ROW_H - 2, () -> ContentState.selectRef(id));
			}
			ry += ROW_H;
		}
		c.g.disableScissor();
		listScroll.max = Math.max(0, refs.size() * ROW_H - vh + 2);
		listScroll.clamp();
		c.scrollArea(x, vy, w, vh, listScroll);
	}

	private void detail(Canvas c, ContentRef r, int x, int y, int w, int h) {
		c.card(x, y, w, h, Canvas.PANEL);
		int tx = x + 12;
		int tw = w - 24;
		c.fit(r.title() != null ? r.title() : r.source(), tx, y + 8, tw, Canvas.TEXT);
		StringBuilder meta = new StringBuilder();
		append(meta, r.channel());
		append(meta, r.durationSec() == null ? null : ContentState.duration(r.durationSec()));
		append(meta, r.views() == null ? null : ContentState.compact(r.views()) + " views");
		append(meta, r.likes() == null ? null : ContentState.compact(r.likes()) + " likes");
		append(meta, r.uploadDate());
		append(meta, r.studiedBy() == null ? null : "studied by " + UiBits.agentName(r.studiedBy()) + " " + UiBits.ago(r.at()));
		c.fit(meta.toString(), tx, y + 20, tw, Canvas.MUTED);
		c.fit(r.frameCount() + " frames  -  " + r.sheetCount() + " contact sheets  -  " + (r.hasTranscript() ? "transcript" : "no transcript") + "  -  " + r.source(),
			tx, y + 31, tw, Canvas.FAINT);
		// view switch
		int bx = tx;
		for (View v : View.values()) {
			int bw = c.font.width(v.label) + 16;
			bx = c.button(v.label, bx, y + 44, bw, 18, v == view ? Canvas.Kind.PRIMARY : Canvas.Kind.SECONDARY, true, () -> {
				view = v;
				textScroll.off = 0;
			}) + 4;
		}
		int by = y + 66;
		int bh = h - 66 - 30;
		switch (view) {
			case SHEETS -> sheets(c, r, tx, by, tw, bh);
			case BREAKDOWN -> text(c, r, "breakdown.md", true, tx, by, tw, bh);
			case TRANSCRIPT -> text(c, r, "transcript.txt", false, tx, by, tw, bh);
		}
		bottomBar(c, r, tx, y + h - 26, tw);
	}

	private static void append(StringBuilder b, @Nullable String s) {
		if (s != null && !s.isBlank()) {
			b.append(b.isEmpty() ? "" : "  -  ").append(s);
		}
	}

	// ------------------------------------------------------------------ sheets

	private List<String> sheetFiles(ContentRef r) {
		return sheetCache.computeIfAbsent(r.id() + "|" + r.sheetCount(), k -> {
			List<String> out = new ArrayList<>();
			if (r.dir() != null) {
				try (Stream<Path> s = Files.list(Path.of(r.dir(), "sheets"))) {
					s.filter(p -> p.getFileName().toString().matches("(?i).*\\.(jpe?g|png)")).sorted().forEach(p -> out.add(p.toString()));
				} catch (IOException | RuntimeException e) {
					// nothing on disk (yet): the empty state says so
				}
			}
			return out;
		});
	}

	private void sheets(Canvas c, ContentRef r, int x, int y, int w, int h) {
		List<String> files = sheetFiles(r);
		c.fill(x, y, w, h, 0xFF08080A);
		if (files.isEmpty()) {
			c.text("No contact sheets on disk for this video.", x + 10, y + 10, Canvas.MUTED);
			return;
		}
		sheet = Math.max(0, Math.min(sheet, files.size() - 1));
		LocalImages.State st = LocalImages.get(files.get(sheet));
		LocalImages.Shown img = st.shown();
		if (img == null) {
			c.text(st.loading() ? "loading..." : st.error() != null ? st.error() : "", x + 10, y + 10, Canvas.MUTED);
		} else {
			double scale = Math.min((w - 4) / (double) img.w(), (h - 4) / (double) img.h());
			int iw = Math.max(1, (int) (img.w() * scale));
			int ih = Math.max(1, (int) (img.h() * scale));
			c.g.blit(RenderPipelines.GUI_TEXTURED, img.texture(), x + (w - iw) / 2, y + (h - ih) / 2, 0, 0, iw, ih, img.w(), img.h(), img.w(), img.h());
		}
		String label = "sheet " + (sheet + 1) + " / " + files.size();
		c.textR(label, x + w - 6, y + h - 12, Canvas.MUTED);
	}

	private void text(Canvas c, ContentRef r, String file, boolean markdown, int x, int y, int w, int h) {
		c.fill(x, y, w, h, 0xFF101014);
		String body = r.dir() == null ? "" : textCache.computeIfAbsent(r.dir() + "/" + file + "|" + r.at(), k -> read(Path.of(r.dir(), file)));
		if (body.isBlank()) {
			c.text("Nothing in " + file + " yet.", x + 10, y + 10, Canvas.MUTED);
			return;
		}
		var lines = md.get(c.font, r.id() + file + body.length(), markdown ? body : body.replace("\n", "\n\n"), w - 20);
		c.g.enableScissor(x, y, x + w, y + h);
		int ly = y + 8 - textScroll.off;
		for (var l : lines) {
			if (ly + 10 >= y && ly <= y + h) {
				c.text(l.text(), x + 10 + l.indent(), ly, l.color());
			}
			ly += l.text().isEmpty() ? 6 : 11;
		}
		c.g.disableScissor();
		textScroll.max = Math.max(0, ly + textScroll.off - y - h + 8);
		textScroll.clamp();
		c.scrollArea(x, y, w, h, textScroll);
	}

	private static String read(Path p) {
		try {
			byte[] b = Files.readAllBytes(p);
			return new String(b, 0, Math.min(b.length, MAX_READ), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			return "";
		}
	}

	// ------------------------------------------------------------------ bottom bar

	private void bottomBar(Canvas c, ContentRef r, int x, int y, int w) {
		ContentActions act = c.actions;
		String id = r.id();
		int bx = x;
		if (view == View.SHEETS) {
			int n = sheetFiles(r).size();
			bx = c.button("<", bx, y, 22, 20, Canvas.Kind.SECONDARY, sheet > 0, () -> sheet--) + 4;
			bx = c.button(">", bx, y, 22, 20, Canvas.Kind.SECONDARY, sheet + 1 < n, () -> sheet++) + 10;
		}
		bx = c.button("Sheets on the big screen", bx, y, 132, 20, Canvas.Kind.PRIMARY, true, () -> act.openRef(id, null, "Shown in the Screening Room")) + 10;
		bx = c.button("-", bx, y, 20, 20, Canvas.Kind.SECONDARY, frame > 0, () -> frame--) + 2;
		c.textC("frame " + frame, bx + 28, y + 6, Canvas.MUTED);
		bx += 58;
		bx = c.button("+", bx, y, 20, 20, Canvas.Kind.SECONDARY, frame + 1 < Math.max(1, r.frameCount()), () -> frame++) + 4;
		c.button("Show frame", bx, y, 70, 20, Canvas.Kind.SECONDARY, r.frameCount() > 0, () -> act.openRef(id, frame, "Frame " + frame + " shown in the Screening Room"));
	}
}

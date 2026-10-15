package dev.agentcraft.client.content;

import dev.agentcraft.client.console.ConsoleFeature;
import dev.agentcraft.client.content.ContentState.Column;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.ContentChannel;
import dev.agentcraft.client.foreman.Protocol.ContentComment;
import dev.agentcraft.client.foreman.Protocol.ContentVideo;
import dev.agentcraft.client.foreman.Protocol.PostMortem;
import dev.agentcraft.client.hud.UiBits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Pipeline: every video of the channel in sections (Ideas, In production, Review, Scheduled, Published) on the
 * left; the picked one on the right with its hook, pitch, script, references, comments and post-mortems, and
 * what you can do to it (approve, reject, comment, approve the cut at a time, publish now, unschedule, retry,
 * open the cut). A comment wakes the agent that owns the video's stage; a reject reason goes to Iris.
 */
final class PipelineTab implements Tab {
	private static final int ROW_H = 18;
	private static final int HEAD_H = 20;

	private final Canvas.Scroll listScroll = new Canvas.Scroll();
	private final Canvas.Scroll detailScroll = new Canvas.Scroll();
	private final DarkField comment = new DarkField("Comment (wakes the agent) - or the reason when you reject", 2000, 3);
	private final DarkField time = new DarkField("Publish at: empty = next free slot, or 2026-10-20 18:00, 18:00, +2d", 40, 1);
	private @Nullable String shownVideo;

	@Override
	public void render(Canvas c, int x, int y, int w, int h) {
		ContentChannel ch = ContentState.channel();
		if (ch == null) {
			Empty.noChannel(c, x, y, w, h);
			return;
		}
		int listW = Math.max(190, Math.min(300, w * 4 / 10));
		ContentVideo sel = selected(ch);
		if (sel != null && !sel.id().equals(shownVideo)) {
			shownVideo = sel.id();
			comment.clear();
			time.clear();
			detailScroll.off = 0;
		}
		list(c, ch, sel, x, y, listW, h);
		detail(c, ch, sel, x + listW + 10, y, w - listW - 10, h);
	}

	private @Nullable ContentVideo selected(ContentChannel ch) {
		ForemanState st = Foreman.state();
		ContentVideo v = ContentState.videoId() == null || st == null ? null : st.contentVideo(ContentState.videoId());
		if (v != null && v.channelId().equals(ch.id()) && ContentState.column(v) != null) {
			return v;
		}
		for (Column col : Column.values()) {
			List<ContentVideo> in = ContentState.inColumn(ch.id(), col);
			if (!in.isEmpty() && (col == Column.IDEAS || col == Column.REVIEW)) {
				return in.getFirst();
			}
		}
		for (Column col : Column.values()) {
			List<ContentVideo> in = ContentState.inColumn(ch.id(), col);
			if (!in.isEmpty()) {
				return in.getFirst();
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ list

	private void list(Canvas c, ContentChannel ch, @Nullable ContentVideo sel, int x, int y, int w, int h) {
		c.card(x, y, w, h, Canvas.PANEL);
		int waiting = ContentState.waitingForYou(ch.id());
		String head = waiting == 0 ? "Nothing waits for you" : waiting + (waiting == 1 ? " waits" : " wait") + " for you";
		c.text(head, x + 10, y + 8, waiting == 0 ? Canvas.MUTED : Canvas.ACCENT);
		int vx = x + 1;
		int vy = y + 24;
		int vw = w - 2;
		int vh = h - 25;
		c.fill(x + 1, vy - 1, w - 2, 1, Canvas.RULE);
		c.g.enableScissor(vx, vy, vx + vw, vy + vh);
		int cy = vy - listScroll.off;
		for (Column col : Column.values()) {
			List<ContentVideo> in = ContentState.inColumn(ch.id(), col);
			if (in.isEmpty() && col != Column.IDEAS) {
				continue;
			}
			c.text(col.label.toUpperCase(java.util.Locale.ROOT), x + 10, cy + 8, Canvas.FAINT);
			c.textR(String.valueOf(in.size()), x + w - 12, cy + 8, Canvas.FAINT);
			cy += HEAD_H;
			if (in.isEmpty()) {
				c.text("Ask Iris for ideas: @iris pitch me 3 videos", x + 10, cy + 3, Canvas.FAINT);
				cy += ROW_H;
			}
			for (ContentVideo v : in) {
				row(c, v, sel != null && v.id().equals(sel.id()), x + 4, cy, w - 8, vx, vy, vw, vh);
				cy += ROW_H;
			}
			cy += 6;
		}
		int rejected = ContentState.countRejected(ch.id());
		if (rejected > 0) {
			c.text(rejected + " rejected (hidden)", x + 10, cy + 2, Canvas.FAINT);
			cy += ROW_H;
		}
		c.g.disableScissor();
		listScroll.max = Math.max(0, cy + listScroll.off - vy - vh + 4);
		listScroll.clamp();
		c.scrollArea(x, vy, w, vh, listScroll);
	}

	private void row(Canvas c, ContentVideo v, boolean selected, int x, int y, int w, int vx, int vy, int vw, int vh) {
		if (y + ROW_H < vy || y > vy + vh) {
			return;
		}
		boolean hv = c.hover(x, y, w, ROW_H) && c.hover(vx, vy, vw, vh);
		if (selected) {
			c.fill(x, y, w, ROW_H - 1, UiMix.alpha(Canvas.ACCENT, 38));
			c.fill(x, y, 2, ROW_H - 1, Canvas.ACCENT);
		} else if (hv) {
			c.fill(x, y, w, ROW_H - 1, Canvas.PANEL_HI);
		}
		int sc = ContentState.statusColor(v.status());
		c.fill(x + 8, y + 7, 4, 4, sc);
		String meta = switch (v.status()) {
			case "scheduled", "publishing", "failed" -> v.scheduledAt() == null ? ContentState.statusLabel(v.status()) : ContentState.day(v.scheduledAt());
			case "published" -> viewsOf(v);
			case "producing", "approved" -> ContentState.statusLabel(v.status());
			default -> v.isShort() ? "short" : "long";
		};
		int mw = c.font.width(meta);
		c.textR(meta, x + w - 8, y + 5, v.status().equals("failed") ? Canvas.WARN : Canvas.MUTED);
		c.fit(v.title(), x + 18, y + 5, w - 18 - mw - 16, selected ? Canvas.TEXT : UiMix.mix(Canvas.TEXT, Canvas.MUTED, 0.25f));
		c.hitIn(vx, vy, vw, vh, x, y, w, ROW_H, () -> ContentState.selectVideo(v.id()));
	}

	private static String viewsOf(ContentVideo v) {
		ForemanState st = Foreman.state();
		var s = st == null || v.youtubeVideoId() == null ? null : st.contentStat(v.youtubeVideoId());
		return s == null ? "published" : ContentState.compact(s.views()) + " views";
	}

	// ------------------------------------------------------------------ detail

	private void detail(Canvas c, ContentChannel ch, @Nullable ContentVideo v, int x, int y, int w, int h) {
		c.card(x, y, w, h, Canvas.PANEL);
		if (v == null) {
			c.text("Pick a video on the left", x + 12, y + 12, Canvas.MUTED);
			c.paragraph("Iris pitches ideas, you approve them, Reel makes the video, you approve the cut, Cleo publishes it, Vega learns from the numbers.",
				x + 12, y + 28, w - 24, Canvas.FAINT, 0);
			return;
		}
		int barH = actionBarHeight(v);
		int vx = x + 1;
		int vy = y + 1;
		int vw = w - 2;
		int vh = h - barH - 2;
		c.g.enableScissor(vx, vy, vx + vw, vy + vh);
		int cy = vy + 10 - detailScroll.off;
		int tx = x + 12;
		int tw = w - 24;
		cy = c.paragraph(v.title(), tx, cy, tw, Canvas.TEXT, 3) + 3;
		int px = tx;
		px += c.pill(ContentState.statusLabel(v.status()), px, cy, ContentState.statusColor(v.status())) + 6;
		String who = v.authorId() == null ? "" : " by " + UiBits.agentName(v.authorId());
		c.text((v.isShort() ? "Short" : "Long video") + who, px, cy + 3, Canvas.MUTED);
		cy += 19;
		String when = whenLine(v);
		if (!when.isEmpty()) {
			c.text(when, tx, cy, Canvas.MUTED);
			cy += 12;
		}
		if (v.error() != null && !v.error().isBlank()) {
			cy = c.paragraph("error: " + v.error(), tx, cy, tw, Canvas.WARN, 4) + 2;
		}
		cy = section(c, "HOOK", v.hook(), tx, cy, tw, 3);
		cy = section(c, "PITCH", v.pitch(), tx, cy, tw, 8);
		cy = section(c, "SCRIPT" + (v.script() == null ? "" : "  " + v.script().length() + " chars"), v.script(), tx, cy, tw, 6);
		cy = refs(c, v, tx, cy, tw, vx, vy, vw, vh);
		cy = files(c, v, tx, cy, tw);
		cy = postMortems(c, v, tx, cy, tw);
		cy = comments(c, v, tx, cy, tw);
		c.g.disableScissor();
		detailScroll.max = Math.max(0, cy + detailScroll.off - vy - vh + 8);
		detailScroll.clamp();
		c.scrollArea(x, vy, w, vh, detailScroll);
		actionBar(c, ch, v, x + 10, y + h - barH, w - 20, barH);
	}

	private static String whenLine(ContentVideo v) {
		if (v.status().equals("published") && v.publishedAt() != null) {
			return "Published " + ContentState.dayTime(v.publishedAt());
		}
		if (v.scheduledAt() != null && !v.status().equals("published")) {
			return (v.status().equals("failed") ? "Was due " : "Goes out ") + ContentState.dayTime(v.scheduledAt());
		}
		return "";
	}

	private static int section(Canvas c, String label, @Nullable String text, int x, int y, int w, int maxLines) {
		if (text == null || text.isBlank()) {
			return y;
		}
		c.text(label, x, y, Canvas.FAINT);
		return c.paragraph(text.strip(), x, y + 11, w, UiMix.mix(Canvas.TEXT, Canvas.MUTED, 0.2f), maxLines) + 6;
	}

	private static int refs(Canvas c, ContentVideo v, int x, int y, int w, int vx, int vy, int vw, int vh) {
		if (v.refIds().isEmpty()) {
			return y;
		}
		c.text("REFERENCES", x, y, Canvas.FAINT);
		int cx = x;
		int cy = y + 11;
		for (String id : v.refIds()) {
			var r = Foreman.state() == null ? null : Foreman.state().contentRefs().stream().filter(q -> q.id().equals(id)).findFirst().orElse(null);
			String label = r != null && r.title() != null ? id + " " + r.title() : id;
			label = dev.agentcraft.client.ui.TextUtil.ellipsize(c.font, label, 150);
			int cw = c.font.width(label) + 12;
			if (cx + cw > x + w) {
				cx = x;
				cy += 16;
			}
			boolean hv = c.hover(cx, cy, cw, 13);
			c.fill(cx, cy, cw, 13, hv ? Canvas.PANEL_HI : 0xFF1A1A20);
			c.outline(cx, cy, cw, 13, Canvas.RULE);
			c.text(label, cx + 6, cy + 3, Canvas.TEXT);
			c.hitIn(vx, vy, vw, vh, cx, cy, cw, 13, () -> {
				ContentState.selectRef(id);
				ContentState.setTab(ContentState.Tab.REFERENCES);
			});
			cx += cw + 4;
		}
		return cy + 22;
	}

	private static int files(Canvas c, ContentVideo v, int x, int y, int w) {
		if (v.filePath() == null && v.thumbnailPath() == null && v.url() == null) {
			return y;
		}
		c.text("FILES", x, y, Canvas.FAINT);
		y += 11;
		if (v.filePath() != null) {
			c.fit("cut: " + Path.of(v.filePath()).getFileName(), x, y, w, Canvas.MUTED);
			y += 11;
		}
		if (v.thumbnailPath() != null) {
			c.fit("thumbnail: " + Path.of(v.thumbnailPath()).getFileName(), x, y, w, Canvas.MUTED);
			y += 11;
		}
		if (v.url() != null) {
			c.fit(v.url(), x, y, w, Canvas.MUTED);
			y += 11;
		}
		return y + 6;
	}

	private static int postMortems(Canvas c, ContentVideo v, int x, int y, int w) {
		for (PostMortem pm : v.postMortems()) {
			c.text("POST-MORTEM " + pm.horizon().toUpperCase(java.util.Locale.ROOT) + " by Vega", x, y, Canvas.FAINT);
			y = c.paragraph(pm.text().strip(), x, y + 11, w, UiMix.mix(Canvas.TEXT, Canvas.MUTED, 0.2f), 10) + 6;
		}
		return y;
	}

	private static int comments(Canvas c, ContentVideo v, int x, int y, int w) {
		if (v.comments().isEmpty()) {
			return y;
		}
		c.text("COMMENTS", x, y, Canvas.FAINT);
		y += 12;
		for (ContentComment cm : v.comments().subList(Math.max(0, v.comments().size() - 12), v.comments().size())) {
			boolean you = UiBits.isUser(cm.by());
			c.text(UiBits.agentName(cm.by()) + "  " + UiBits.ago(cm.at()), x, y, you ? Canvas.ACCENT : Canvas.MUTED);
			y = c.paragraph(cm.text().strip(), x, y + 10, w, Canvas.TEXT, 6) + 5;
		}
		return y;
	}

	// ------------------------------------------------------------------ actions

	private static int actionBarHeight(ContentVideo v) {
		return 8 + (needsTime(v) ? 26 : 0) + 20 + 4 + 20 + (hasStatusButtons(v) ? 24 : 0) + 8;
	}

	private static boolean needsTime(ContentVideo v) {
		return v.status().equals("review") || v.status().equals("scheduled");
	}

	private @Nullable Long parsedTime(Canvas c) {
		if (time.isBlank()) {
			return null;
		}
		Long at = ContentState.parseTime(time.value(), System.currentTimeMillis());
		if (at == null) {
			c.actions.flash("Time not understood. Use 2026-10-20 18:00, 18:00, +3h or +2d.", true);
			return Long.MIN_VALUE;
		}
		return at;
	}

	private static boolean hasStatusButtons(ContentVideo v) {
		return switch (v.status()) {
			case "idea", "approved", "review", "scheduled", "failed", "published" -> true;
			default -> false;
		};
	}

	private void actionBar(Canvas c, ContentChannel ch, ContentVideo v, int x, int y, int w, int h) {
		c.fill(x - 10, y, w + 20, 1, Canvas.RULE);
		int cy = y + 8;
		if (needsTime(v)) {
			c.field(time, x, cy, w);
			cy += 26;
		}
		int ch1 = c.field(comment, x, cy, w);
		cy += Math.max(20, ch1) + 4;
		String id = v.id();
		ContentActions act = c.actions;
		int bx = x;
		switch (v.status()) {
			case "idea" -> {
				bx = c.button("Approve", bx, cy, 66, 20, Canvas.Kind.PRIMARY, true, () -> act.video(id, "approve", null, null, "Approved: Reel takes it", null)) + 4;
				bx = reject(c, id, bx, cy) + 4;
			}
			case "approved" -> bx = reject(c, id, bx, cy) + 4;
			case "review" -> {
				bx = c.button("Approve cut", bx, cy, 78, 20, Canvas.Kind.PRIMARY, true, () -> {
					Long at = parsedTime(c);
					if (at == null || at != Long.MIN_VALUE) {
						act.video(id, "approve_cut", null, at, "Cut approved: Cleo publishes it" + (at == null ? " at the next free slot" : ""), time::clear);
					}
				}) + 4;
				bx = reject(c, id, bx, cy) + 4;
				bx = openCut(c, v, bx, cy);
			}
			case "scheduled" -> {
				bx = openCut(c, v, bx, cy);
				bx = c.button(act.armed("publish_now") ? "Sure? Publish now" : "Publish now", bx, cy, 92, 20, Canvas.Kind.PRIMARY, true,
					() -> act.confirmed("publish_now", () -> act.video(id, "publish_now", null, null, "Cleo is publishing", null))) + 4;
				bx = c.button("Reschedule", bx, cy, 68, 20, Canvas.Kind.SECONDARY, !time.isBlank(), () -> {
					Long at = parsedTime(c);
					if (at != null && at != Long.MIN_VALUE) {
						act.video(id, "schedule", null, at, "Moved to " + ContentState.dayTime(at), time::clear);
					}
				}) + 4;
				bx = c.button("Unschedule", bx, cy, 66, 20, Canvas.Kind.SECONDARY, true, () -> act.video(id, "unschedule", null, null, "Back to review", null)) + 4;
			}
			case "failed" -> {
				bx = c.button("Retry", bx, cy, 50, 20, Canvas.Kind.PRIMARY, true, () -> act.video(id, "retry", null, null, "Retrying now", null)) + 4;
				bx = openCut(c, v, bx, cy);
			}
			case "published" -> {
				bx = c.button("Open on YouTube", bx, cy, 100, 20, Canvas.Kind.SECONDARY, v.url() != null, () -> OsOpen.open(v.url(), act)) + 4;
				bx = openCut(c, v, bx, cy);
			}
			default -> {
			}
		}
		if (bx != x) { // the status buttons get their own row; Comment and Talk go below them
			cy += 24;
			bx = x;
		}
		String owner = ContentState.ownerOf(v);
		int rest = x + w - bx;
		int talkW = Math.min(88, Math.max(60, rest / 2));
		int sendW = Math.max(52, rest - talkW - 4);
		bx = c.button("Comment", bx, cy, Math.min(sendW, 80), 20, Canvas.Kind.SECONDARY, !comment.isBlank(),
			() -> act.video(id, "comment", comment.value().strip(), null, "Sent to " + UiBits.agentName(owner), comment::clear));
		c.button("Talk to " + UiBits.agentName(owner), x + w - talkW, cy, talkW, 20, Canvas.Kind.GHOST, true, () -> ConsoleFeature.open("@" + owner + " ", false));
	}

	private int reject(Canvas c, String id, int bx, int cy) {
		ContentActions act = c.actions;
		return c.button(act.armed("reject:" + id) ? "Sure? Reject" : "Reject", bx, cy, 62, 20, Canvas.Kind.DANGER, true, () -> act.confirmed("reject:" + id,
			() -> act.video(id, "reject", comment.isBlank() ? null : comment.value().strip(), null, "Rejected", comment::clear)));
	}

	/** Watch the cut here in game (silent flipbook, "Open in player" for sound), or open it in the OS player. */
	private static int openCut(Canvas c, ContentVideo v, int bx, int cy) {
		boolean ok = v.filePath() != null && Files.exists(Path.of(v.filePath()));
		bx = c.button("Watch", bx, cy, 50, 20, Canvas.Kind.SECONDARY, ok, () -> c.actions.watch(v.id())) + 4;
		return c.button("Open cut", bx, cy, 56, 20, Canvas.Kind.GHOST, ok, () -> OsOpen.open(v.filePath(), c.actions)) + 4;
	}
}

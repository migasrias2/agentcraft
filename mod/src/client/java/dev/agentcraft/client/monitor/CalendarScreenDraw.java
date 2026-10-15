package dev.agentcraft.client.monitor;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.SocialPost;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.social.SocialCalendar;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.WorldUi;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * The Social Room's wall calendar (a monitor panel bound to {@code calendar}): this week, Monday to
 * Sunday in columns, today highlighted, one chip per post (time, platform badge, status word, first
 * words) coloured by status; a header with the week and the counts ("3 drafts · 2 approved · 1 failed").
 * Posting chips pulse amber. Drawn every frame from {@link MonitorRenderer} (the chips are cheap and
 * the pulse animates); right-click opens {@code SocialCalendarScreen}.
 */
final class CalendarScreenDraw {
	private static final float Z_COLUMN = DisplayDraw.Z_STEP;
	private static final float Z_CHIP = 2 * DisplayDraw.Z_STEP;
	private static final float Z_MARK = 3 * DisplayDraw.Z_STEP;
	private static final float Z_MARK_INNER = 4 * DisplayDraw.Z_STEP;
	private static final float Z_TEXT = 6 * DisplayDraw.Z_STEP;
	private static final float CHIP_H = 25f;
	private static final float CHIP_GAP = 3f;
	private static final int WHITE = 0xFFFFFFFF;

	private record Run(String text, float x, float y, int color) {
	}

	private CalendarScreenDraw() {
	}

	static void draw(long nowMs, MonitorScreen m, PoseStack ps, SubmitNodeCollector c, Font font, int light) {
		ScreenStyle st = m.style;
		ForemanState fs = Foreman.state();
		List<Run> runs = new ArrayList<>();
		DisplayDraw.Rects r = m.dyn;
		float x0 = m.bx0 + 6;
		float x1 = m.bx1 - 6;
		float y0 = m.by0 + 4;
		float y1 = m.by1 - 4;
		if (fs == null || !fs.hasData()) {
			runs.add(centred(font, "Social calendar", m, (m.by0 + m.by1) / 2f - 10, st.text()));
			runs.add(centred(font, "waiting for the Foreman", m, (m.by0 + m.by1) / 2f + 2, st.muted()));
			emit(ps, c, r, runs, light);
			return;
		}
		List<SocialPost> all = fs.socialPosts();
		LocalDate today = SocialCalendar.today();
		LocalDate monday = SocialCalendar.weekStart(today);
		float pulse = (float) (0.5 + 0.5 * Math.sin(nowMs / 330.0));

		// ---- header: title left, counts right
		runs.add(new Run("Social calendar · week of " + SocialCalendar.shortDay(monday), x0, y0 + 1, st.text()));
		List<SocialCalendar.Part> parts = SocialCalendar.counts(all, monday);
		float cw = 0;
		String sep = " · ";
		for (int i = 0; i < parts.size(); i++) {
			cw += font.width(parts.get(i).text()) + (i > 0 ? font.width(sep) : 0);
		}
		float cx = x1 - cw;
		if (parts.isEmpty()) {
			String none = SocialCalendar.countsLine(all, monday);
			runs.add(new Run(none, x1 - font.width(none), y0 + 1, st.muted()));
		}
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				runs.add(new Run(sep, cx, y0 + 1, st.muted()));
				cx += font.width(sep);
			}
			SocialCalendar.Part p = parts.get(i);
			runs.add(new Run(p.text(), cx, y0 + 1, SocialCalendar.statusColor(p.status())));
			cx += font.width(p.text());
		}
		r.add(x0, y0 + 12, x1, y0 + 13, Z_COLUMN, st.rule(), light);

		// ---- columns
		float headY = y0 + 16;
		float bodyTop = headY + 14;
		float bodyBottom = y1 - 12;
		float colW = (x1 - x0) / 7f;
		int weekPosts = 0;
		for (int d = 0; d < 7; d++) {
			LocalDate day = monday.plusDays(d);
			boolean isToday = day.equals(today);
			float cx0 = x0 + d * colW;
			float cx1 = cx0 + colW - 2;
			int colBg = DisplayDraw.mix(st.bgBottom(), st.text(), isToday ? 0.12f : 0.04f);
			r.add(cx0, headY, cx1, bodyBottom, Z_COLUMN, colBg, light);
			if (isToday) {
				r.add(cx0, headY, cx1, headY + 2, Z_MARK, st.attention(), light);
			}
			String head = day.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH).toUpperCase(java.util.Locale.ENGLISH)
				+ " " + day.getDayOfMonth();
			int headColor = isToday ? st.attention() : d >= 5 ? st.muted() : st.text();
			runs.add(new Run(head, cx0 + (colW - 2 - font.width(head)) / 2f, headY + 4.5f, headColor));
			List<SocialPost> list = SocialCalendar.onDay(all, day, false);
			weekPosts += list.size();
			float cy = bodyTop + 2;
			float chipW = colW - 2 - 6;
			for (int i = 0; i < list.size(); i++) {
				boolean last = i == list.size() - 1;
				boolean fits = cy + CHIP_H <= bodyBottom && (last || cy + 2 * CHIP_H + CHIP_GAP <= bodyBottom);
				if (!fits) {
					int more = list.size() - i;
					runs.add(new Run("+" + more + " more", cx0 + 5, Math.min(cy, bodyBottom - 10), st.muted()));
					break;
				}
				chip(r, runs, font, st, list.get(i), cx0 + 3, cy, chipW, colBg, pulse, light);
				cy += CHIP_H + CHIP_GAP;
			}
		}

		// ---- footer: posts without a time, and the hint
		int unscheduled = SocialCalendar.unscheduled(all).size();
		float fy = y1 - 8;
		if (unscheduled > 0) {
			runs.add(new Run("no time yet: " + unscheduled + (unscheduled == 1 ? " draft" : " drafts"), x0, fy, SocialCalendar.statusColor("draft")));
		} else if (weekPosts == 0) {
			runs.add(new Run("nothing planned this week · ask Nova: @nova plan my week", x0, fy, st.muted()));
		}
		String hint = "right-click: open the calendar";
		runs.add(new Run(hint, x1 - font.width(hint), fy, st.muted()));
		emit(ps, c, r, runs, light);
	}

	private static void chip(DisplayDraw.Rects r, List<Run> runs, Font font, ScreenStyle st, SocialPost p, float x, float y, float w, int colBg,
		float pulse, int light) {
		int sc = SocialCalendar.statusColor(p.status());
		boolean posting = p.status().equals("posting");
		boolean posted = p.status().equals("posted");
		float tint = posting ? 0.16f + 0.16f * pulse : posted ? 0.10f : 0.20f;
		r.add(x, y, x + w, y + CHIP_H, Z_CHIP, DisplayDraw.mix(colBg, sc, tint), light);
		int stripe = posting ? DisplayDraw.mix(sc, WHITE, 0.35f * pulse) : sc;
		r.add(x, y, x + 2, y + CHIP_H, Z_MARK, stripe, light);
		// platform badge: X = black with a light rim, LinkedIn = blue
		float bx = x + 5;
		float by = y + 3;
		if (p.isX()) {
			r.add(bx, by, bx + 13, by + 11, Z_MARK, 0xFFEDE5D7, light);
			r.add(bx + 1, by + 1, bx + 12, by + 10, Z_MARK_INNER, SocialCalendar.platformColor("x"), light);
		} else {
			r.add(bx, by, bx + 13, by + 11, Z_MARK, SocialCalendar.platformColor("linkedin"), light);
		}
		String badge = SocialCalendar.badge(p.platform());
		runs.add(new Run(badge, bx + (13 - font.width(badge)) / 2f, by + 1.5f, WHITE));
		int text = posted ? st.muted() : st.text();
		String time = p.scheduledAt() == null ? "--:--" : SocialCalendar.time(p.scheduledAt());
		runs.add(new Run(time, x + 21, y + 4, text));
		String word = switch (p.status()) {
			case "posted" -> UiBits.CHECK + " posted";
			case "failed" -> UiBits.CROSS + " failed";
			default -> SocialCalendar.statusLabel(p.status());
		};
		float ww = font.width(word);
		if (x + 21 + font.width(time) + 6 + ww <= x + w - 3) {
			runs.add(new Run(word, x + w - 3 - ww, y + 4, sc));
		}
		runs.add(new Run(TextUtil.ellipsize(font, SocialCalendar.oneLine(p.text()), (int) (w - 9)), x + 6, y + 14.5f, posted ? st.muted() : st.text()));
	}

	private static Run centred(Font font, String s, MonitorScreen m, float y, int color) {
		return new Run(s, (m.bx0 + m.bx1 - font.width(s)) / 2f, y, color);
	}

	private static void emit(PoseStack ps, SubmitNodeCollector c, DisplayDraw.Rects r, List<Run> runs, int light) {
		r.submit(ps, c);
		ps.pushPose();
		ps.translate(0, 0, Z_TEXT);
		for (Run t : runs) {
			WorldUi.submitText(ps, c, t.text(), t.x(), t.y(), t.color(), light);
		}
		ps.popPose();
	}
}

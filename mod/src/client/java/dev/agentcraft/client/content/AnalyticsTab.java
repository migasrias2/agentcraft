package dev.agentcraft.client.content;

import dev.agentcraft.client.content.ContentState.Row;
import dev.agentcraft.client.content.ContentState.Sort;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.ContentChannel;
import dev.agentcraft.client.foreman.Protocol.ContentChannelStats;
import dev.agentcraft.client.foreman.Protocol.ContentStat;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Analytics: the numbers a channel owner checks first. Big KPIs of the channel (subscribers, views, watch time, subs gained,
 * average view %), a 28-day views chart, the retention curve and views-over-time of the picked video, and a
 * sortable table of every published video. When the channel is not connected (no OAuth) the public numbers still
 * show and a banner says how to connect.
 */
final class AnalyticsTab implements Tab {
	private static final int ACCENT = Canvas.ACCENT;
	private static final int GAP = 8;
	private static final int ROW_H = 16;

	private final Canvas.Scroll tableScroll = new Canvas.Scroll();

	@Override
	public void render(Canvas c, int x, int y, int w, int h) {
		ContentChannel ch = ContentState.channel();
		if (ch == null) {
			Empty.noChannel(c, x, y, w, h);
			return;
		}
		ForemanState st = Foreman.state();
		ContentChannelStats cs = st == null ? null : st.contentChannelStats(ch.id());
		List<Row> rows = ContentState.rows(ch.id());
		Row sel = ContentState.selectedRow(rows);
		int cy = y;
		if (!ch.connected()) {
			cy += banner(c, x, cy, w) + GAP;
		}
		boolean tight = h < 280;
		int kpiH = tight ? 44 : 56;
		kpis(c, ch, cs, rows, x, cy, w, kpiH);
		cy += kpiH + GAP;
		int rest = y + h - cy;
		int chartsH = Math.max(88, Math.min(150, rest * 44 / 100));
		charts(c, cs, sel, x, cy, w, chartsH);
		cy += chartsH + GAP;
		table(c, rows, sel, x, cy, w, y + h - cy);
	}

	// ------------------------------------------------------------------ banner + KPIs

	private int banner(Canvas c, int x, int y, int w) {
		String body = "Vega reads YouTube Studio in your Chrome (stay logged in there) for watch time, CTR, stayed-to-watch and "
			+ "subscribers gained. Press Sync now to read it. Until then you see the public numbers (views, likes, comments).";
		int lines = c.lines(body, w - 28);
		int h = 12 + 12 + lines * 11;
		c.fill(x, y, w, h, UiMix.mix(Canvas.PANEL, ACCENT, 0.10f));
		c.fill(x, y, 3, h, ACCENT);
		c.text("Analytics not connected", x + 12, y + 7, Canvas.TEXT);
		c.paragraph(body, x + 12, y + 19, w - 28, Canvas.MUTED, 0);
		return h;
	}

	private void kpis(Canvas c, ContentChannel ch, @Nullable ContentChannelStats cs, List<Row> rows, int x, int y, int w, int h) {
		boolean on = ch.connected();
		Double avg = ContentState.avgViewPct(rows);
		String[] labels = {"Subscribers", "Views, 28 days", "Watch time, 28 days", "Subs gained, 28 days", "Avg view %"};
		String[] values = {
			cs == null ? "--" : ContentState.compact(cs.subscribers()),
			cs == null ? "--" : ContentState.compact(cs.views28d()),
			cs == null || !on ? "--" : ContentState.hours(cs.watchMinutes28d()),
			cs == null || !on || cs.subsGained28d() == null ? "--" : (cs.subsGained28d() > 0 ? "+" : "") + ContentState.compact(cs.subsGained28d()),
			!on && avg == null ? "--" : ContentState.percent(avg)};
		String[] notes = {
			cs == null || cs.totalViews() == null ? "" : ContentState.compact(cs.totalViews()) + " views in total",
			cs == null || cs.views28d() == null ? "" : ContentState.compact(cs.views28d() / 28) + " a day",
			on ? "" : "needs analytics",
			on ? "" : "needs analytics",
			avg == null ? (on ? "no data yet" : "needs analytics") : "weighted by views"};
		int cw = (w - 4 * 6) / 5;
		for (int i = 0; i < 5; i++) {
			int cx = x + i * (cw + 6);
			c.card(cx, y, cw, h, Canvas.PANEL);
			c.fit(labels[i], cx + 9, y + 6, cw - 14, Canvas.MUTED);
			boolean none = values[i].equals("--");
			float scale = h >= 52 ? 2.0f : 1.6f;
			if (c.font.width(values[i]) * scale > cw - 16) {
				scale = 1.5f;
			}
			c.big(values[i], cx + 9, y + 18, scale, none ? Canvas.FAINT : Canvas.TEXT);
			if (!notes[i].isEmpty() && h >= 52) {
				c.fit(notes[i], cx + 9, y + h - 12, cw - 14, Canvas.FAINT);
			}
		}
	}

	// ------------------------------------------------------------------ charts

	private void charts(Canvas c, @Nullable ContentChannelStats cs, @Nullable Row sel, int x, int y, int w, int h) {
		int w1 = w * 50 / 100 - 4;
		int w2 = (w - w1 - 2 * GAP) / 2;
		int w3 = w - w1 - w2 - 2 * GAP;
		daily(c, cs, x, y, w1, h);
		retention(c, sel, x + w1 + GAP, y, w2, h);
		history(c, sel, x + w1 + w2 + 2 * GAP, y, w3, h);
	}

	private void daily(Canvas c, @Nullable ContentChannelStats cs, int x, int y, int w, int h) {
		c.card(x, y, w, h, Canvas.PANEL);
		long[] v = ContentState.dailyViews(cs);
		long total = 0;
		long peak = 0;
		for (long d : v) {
			total += d;
			peak = Math.max(peak, d);
		}
		c.text("Views, last 28 days", x + 9, y + 7, Canvas.TEXT);
		if (cs == null || cs.daily().isEmpty()) {
			c.text("No daily numbers yet. They arrive with the next sync.", x + 9, y + h / 2 - 4, Canvas.FAINT);
			return;
		}
		c.textR(ContentState.compact(total) + " total  -  peak " + ContentState.compact(peak), x + w - 9, y + 7, Canvas.MUTED);
		int ph = h - 40;
		int hov = ChartDraw.columns(c, x + 6, y + 24, w - 12, ph, v, ACCENT);
		String tip = ChartDraw.dayTip(cs, v, hov);
		c.text(tip == null ? "hover a column for the day" : tip, x + 9, y + h - 12, tip == null ? Canvas.FAINT : Canvas.TEXT);
	}

	private void retention(Canvas c, @Nullable Row sel, int x, int y, int w, int h) {
		c.card(x, y, w, h, Canvas.PANEL);
		c.text("Retention", x + 9, y + 7, Canvas.TEXT);
		ContentStat st = sel == null ? null : sel.stat();
		if (sel != null) {
			c.fit(sel.video().title(), x + 9 + c.font.width("Retention") + 8, y + 7, w - 28 - c.font.width("Retention"), Canvas.FAINT);
		}
		if (st == null || st.retention().size() < 2) {
			String msg = sel == null ? "Pick a video below." : "Needs connected analytics.";
			c.text(msg, x + 9, y + h / 2 - 4, Canvas.FAINT);
			return;
		}
		int hov = ChartDraw.retention(c, x + 6, y + 24, w - 12, h - 44, st.retention(), ACCENT);
		int n = st.retention().size();
		String tip = hov < 0 ? "hover the curve" : Math.round(100.0 * hov / (n - 1)) + "% in: " + ContentState.pct(st.retention().get(hov)) + " still watching";
		c.text(tip, x + 9, y + h - 12, hov < 0 ? Canvas.FAINT : Canvas.TEXT);
	}

	private void history(Canvas c, @Nullable Row sel, int x, int y, int w, int h) {
		c.card(x, y, w, h, Canvas.PANEL);
		c.text("Views over time", x + 9, y + 7, Canvas.TEXT);
		ForemanState st = Foreman.state();
		List<ContentStat> hist = st == null || sel == null || sel.video().youtubeVideoId() == null ? List.of() : st.contentStatHistory(sel.video().youtubeVideoId());
		if (hist.size() < 2) {
			c.text(sel == null ? "Pick a video below." : "Needs two syncs.", x + 9, y + h / 2 - 4, Canvas.FAINT);
			return;
		}
		double[] v = new double[hist.size()];
		for (int i = 0; i < v.length; i++) {
			v[i] = hist.get(i).views();
		}
		int hov = ChartDraw.line(c, x + 6, y + 24, w - 12, h - 44, v, 0, false, ACCENT);
		String tip = hov < 0 ? "hover the line" : ContentState.compact(hist.get(hov).views()) + " views, " + dev.agentcraft.client.hud.UiBits.ago(hist.get(hov).at());
		c.fit(tip, x + 9, y + h - 12, w - 18, hov < 0 ? Canvas.FAINT : Canvas.TEXT);
	}

	// ------------------------------------------------------------------ table

	private void table(Canvas c, List<Row> rows, @Nullable Row sel, int x, int y, int w, int h) {
		if (h < 30) {
			return;
		}
		c.card(x, y, w, h, Canvas.PANEL);
		int inner = w - 18;
		int[] cols = columns(inner);
		int hx = x + 9;
		c.text("Video", hx, y + 6, Canvas.FAINT);
		header(c, "Published", hx + cols[1], y + 6, Sort.NEWEST);
		header(c, "Views", hx + cols[2], y + 6, Sort.VIEWS);
		header(c, "Avg view %", hx + cols[3], y + 6, Sort.RETENTION);
		header(c, "CTR", hx + cols[4], y + 6, Sort.CTR);
		c.text("Stayed", hx + cols[5], y + 6, Canvas.FAINT);
		c.text("Change", hx + cols[6], y + 6, Canvas.FAINT);
		c.fill(x + 1, y + 18, w - 2, 1, Canvas.RULE);
		int vy = y + 19;
		int vh = h - 20;
		if (rows.isEmpty()) {
			c.text("No published videos yet.", x + 9, vy + 8, Canvas.MUTED);
			c.text("Their numbers appear here after Cleo publishes the first one.", x + 9, vy + 20, Canvas.FAINT);
			return;
		}
		c.g.enableScissor(x + 1, vy, x + w - 1, vy + vh);
		int ry = vy - tableScroll.off;
		for (Row r : rows) {
			row(c, r, sel != null && r.video().id().equals(sel.video().id()), hx, ry, inner, cols, x + 1, vy, w - 2, vh);
			ry += ROW_H;
		}
		c.g.disableScissor();
		tableScroll.max = Math.max(0, rows.size() * ROW_H - vh + 2);
		tableScroll.clamp();
		c.scrollArea(x, vy, w, vh, tableScroll);
	}

	/** Column x offsets within the table: title, published, views, avg view %, CTR, likes, change. */
	private static int[] columns(int inner) {
		int[] widths = {52, 54, 68, 48, 48, 50};
		int sum = 0;
		for (int wd : widths) {
			sum += wd;
		}
		int titleW = Math.max(90, inner - sum - 8);
		int[] out = new int[7];
		int cx = titleW + 8;
		for (int i = 0; i < 6; i++) {
			out[i + 1] = cx;
			cx += widths[i];
		}
		return out;
	}

	private void header(Canvas c, String label, int x, int y, Sort sort) {
		boolean active = ContentState.sort() == sort;
		int w = c.font.width(label) + (active ? 8 : 0);
		c.text(label + (active ? " v" : ""), x, y, active ? ACCENT : Canvas.FAINT);
		c.hit(x - 2, y - 3, w + 4, 14, () -> ContentState.setSort(sort));
	}

	private void row(Canvas c, Row r, boolean selected, int x, int y, int w, int[] cols, int vx, int vy, int vw, int vh) {
		if (y + ROW_H < vy || y > vy + vh) {
			return;
		}
		if (selected) {
			c.fill(vx, y, vw, ROW_H, UiMix.alpha(ACCENT, 34));
			c.fill(vx, y, 2, ROW_H, ACCENT);
		} else if (c.hover(vx, y, vw, ROW_H) && c.hover(vx, vy, vw, vh)) {
			c.fill(vx, y, vw, ROW_H, Canvas.PANEL_HI);
		}
		ContentStat s = r.stat();
		int ty = y + 4;
		int primary = selected ? Canvas.TEXT : UiMix.mix(Canvas.TEXT, Canvas.MUTED, 0.2f);
		Long when = r.video().publishedAt() != null ? r.video().publishedAt() : r.video().updatedAt();
		c.fit(r.video().title(), x, ty, cols[1] - 8, primary);
		c.text(ContentState.day(when), x + cols[1], ty, Canvas.MUTED);
		c.text(s == null ? "--" : ContentState.compact(s.views()), x + cols[2], ty, s == null ? Canvas.FAINT : Canvas.TEXT);
		c.text(s == null ? "--" : ContentState.percent(s.avgViewPct()), x + cols[3], ty, s == null || s.avgViewPct() == null ? Canvas.FAINT : primary);
		c.text(s == null ? "--" : ContentState.pct1(s.ctr()), x + cols[4], ty, s == null || s.ctr() == null ? Canvas.FAINT : primary);
		c.text(s == null ? "--" : ContentState.percent(s.stayedToWatchPct()), x + cols[5], ty, s == null || s.stayedToWatchPct() == null ? Canvas.FAINT : primary);
		String delta = s == null ? "--" : ContentState.signed(r.deltaViews());
		c.text(delta, x + cols[6], ty, r.deltaViews() > 0 ? Canvas.OK : r.deltaViews() < 0 ? Canvas.WARN : Canvas.FAINT);
		c.hitIn(vx, vy, vw, vh, vx, y, vw, ROW_H, () -> ContentState.selectAnalyticsVideo(r.video().id()));
	}
}

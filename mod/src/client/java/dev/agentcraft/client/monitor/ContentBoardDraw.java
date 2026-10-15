package dev.agentcraft.client.monitor;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.client.content.ContentState;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.ContentChannel;
import dev.agentcraft.client.foreman.Protocol.ContentChannelStats;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.WorldUi;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * The Content Room's wall analytics board (a monitor panel bound to {@code content}): the selected channel's
 * views of the last 28 days as a hero number over a column chart, subscribers and watch time beside it, and the
 * pipeline in one line (ideas, in production, review, scheduled) coloured like the Studio. Right-click opens the
 * Content Studio. Drawn every frame from {@link MonitorRenderer}.
 */
final class ContentBoardDraw {
	private static final float Z_BAR = DisplayDraw.Z_STEP;
	private static final float Z_TEXT = 6 * DisplayDraw.Z_STEP;
	private static final int EMBER = 0xFFE4572E;

	private record Run(String text, float x, float y, int color, float scale) {
	}

	private ContentBoardDraw() {
	}

	static void draw(MonitorScreen m, PoseStack ps, SubmitNodeCollector c, Font font, int light) {
		ScreenStyle st = m.style;
		ForemanState fs = Foreman.state();
		List<Run> runs = new ArrayList<>();
		DisplayDraw.Rects r = m.dyn;
		float x0 = m.bx0 + 8;
		float x1 = m.bx1 - 8;
		float y0 = m.by0 + 6;
		float y1 = m.by1 - 6;
		ContentChannel ch = fs == null || !fs.hasData() ? null : ContentState.channel();
		if (ch == null) {
			String title = fs == null || !fs.hasData() ? "Content Room" : "No channel yet";
			String sub = fs == null || !fs.hasData() ? "waiting for the Foreman" : "right-click: open the Studio and add a YouTube channel";
			runs.add(new Run(title, (m.bx0 + m.bx1 - font.width(title)) / 2f, (m.by0 + m.by1) / 2f - 10, st.text(), 1f));
			runs.add(new Run(sub, (m.bx0 + m.bx1 - font.width(sub)) / 2f, (m.by0 + m.by1) / 2f + 2, st.muted(), 1f));
			emit(ps, c, r, runs, light);
			return;
		}
		ContentChannelStats cs = fs.contentChannelStats(ch.id());
		// ---- header
		runs.add(new Run(TextUtil.ellipsize(font, "Content - " + ch.name(), (int) (x1 - x0) / 2), x0, y0, st.text(), 1f));
		int waiting = ContentState.waitingForYou(ch.id());
		String right = waiting > 0 ? waiting + " waiting for you" : ch.connected() ? "analytics connected" : "public numbers only";
		runs.add(new Run(right, x1 - font.width(right), y0, waiting > 0 ? EMBER : st.muted(), 1f));
		r.add(x0, y0 + 11, x1, y0 + 12, Z_BAR, st.rule(), light);
		// ---- hero: views in 28 days
		float hy = y0 + 17;
		runs.add(new Run("VIEWS, LAST 28 DAYS", x0, hy, st.muted(), 1f));
		String views = cs == null ? "--" : ContentState.compact(cs.views28d());
		runs.add(new Run(views, x0, hy + 10, cs == null ? st.muted() : st.text(), 3f));
		// ---- side numbers
		float sx = x0 + Math.max(110, (x1 - x0) * 0.42f);
		runs.add(new Run("SUBSCRIBERS", sx, hy, st.muted(), 1f));
		runs.add(new Run(cs == null ? "--" : ContentState.compact(cs.subscribers()), sx, hy + 10, st.text(), 2f));
		float wx = sx + Math.max(70, (x1 - sx) * 0.5f);
		runs.add(new Run("WATCH TIME", wx, hy, st.muted(), 1f));
		runs.add(new Run(cs == null || !ch.connected() ? "--" : ContentState.hours(cs.watchMinutes28d()), wx, hy + 10, st.text(), 2f));
		// ---- 28 day columns
		long[] v = ContentState.dailyViews(cs);
		long peak = 1;
		for (long d : v) {
			peak = Math.max(peak, d);
		}
		float cy1 = y1 - 18;
		float cy0 = hy + 42;
		float slot = (x1 - x0) / v.length;
		for (int i = 0; i < v.length; i++) {
			float bh = v[i] <= 0 ? 1f : Math.max(1.5f, (cy1 - cy0) * v[i] / peak);
			r.add(x0 + i * slot + 0.5f, cy1 - bh, x0 + (i + 1) * slot - 0.5f, cy1, Z_BAR, v[i] <= 0 ? st.rule() : EMBER, light);
		}
		// ---- pipeline line
		float fy = y1 - 9;
		float fx = x0;
		String[] labels = {"ideas", "in production", "review", "scheduled"};
		ContentState.Column[] cols = {ContentState.Column.IDEAS, ContentState.Column.PRODUCTION, ContentState.Column.REVIEW, ContentState.Column.SCHEDULED};
		String[] status = {"idea", "producing", "review", "scheduled"};
		for (int i = 0; i < cols.length; i++) {
			int n = ContentState.inColumn(ch.id(), cols[i]).size();
			String s = n + " " + labels[i];
			runs.add(new Run(s, fx, fy, n == 0 ? st.muted() : ContentState.statusColor(status[i]), 1f));
			fx += font.width(s) + 10;
		}
		String hint = "right-click: open the Studio";
		if (fx + font.width(hint) < x1) {
			runs.add(new Run(hint, x1 - font.width(hint), fy, st.muted(), 1f));
		}
		emit(ps, c, r, runs, light);
	}

	private static void emit(PoseStack ps, SubmitNodeCollector c, DisplayDraw.Rects r, List<Run> runs, int light) {
		r.submit(ps, c);
		ps.pushPose();
		ps.translate(0, 0, Z_TEXT);
		for (Run t : runs) {
			if (t.scale() == 1f) {
				WorldUi.submitText(ps, c, t.text(), t.x(), t.y(), t.color(), light);
			} else {
				ps.pushPose();
				ps.translate(t.x(), t.y(), 0);
				ps.scale(t.scale(), t.scale(), 1f);
				WorldUi.submitText(ps, c, t.text(), 0, 0, t.color(), light);
				ps.popPose();
			}
		}
		ps.popPose();
	}
}

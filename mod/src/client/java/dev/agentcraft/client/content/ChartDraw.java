package dev.agentcraft.client.content;

import dev.agentcraft.client.foreman.Protocol.ContentChannelStats;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The Analytics tab's charts, drawn with plain fills: columns for the 28-day views, a line with a soft fill for
 * a video's views over time, and the retention curve (21 points, 0..100 % of the video). All of them take a
 * rectangle, show a few faint grid lines with labels, and return the index under the mouse (or -1) so the
 * caller can print a tooltip line.
 */
final class ChartDraw {
	private static final int LABEL_W = 30;

	private ChartDraw() {
	}

	/** A "nice" axis maximum at or above {@code v}: 1, 2, 2.5, 5 x 10^n. */
	static double niceMax(double v) {
		if (v <= 0) {
			return 1;
		}
		double mag = Math.pow(10, Math.floor(Math.log10(v)));
		double f = v / mag;
		double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
		return nice * mag;
	}

	/** Faint horizontal grid lines with value labels at 0, 50 and 100 % of {@code max}; the plot area is to the right of the labels. */
	private static void grid(Canvas c, int x, int y, int w, int h, double max, boolean percent) {
		for (int i = 0; i <= 2; i++) {
			int gy = y + h - Math.round(h * i / 2f);
			c.fill(x + LABEL_W, Math.min(gy, y + h - 1), w - LABEL_W, 1, i == 0 ? Canvas.RULE : UiMix.alpha(Canvas.RULE, 120));
			double v = max * i / 2.0;
			String label = percent ? Math.round(v * 100) + "%" : ContentState.compact(Math.round(v));
			c.textR(label, x + LABEL_W - 5, gy - 4 + (i == 0 ? -2 : 0), Canvas.FAINT);
		}
	}

	/** Columns; {@code labels} (same length, may hold "") feed the tooltip. Returns the hovered column or -1. */
	static int columns(Canvas c, int x, int y, int w, int h, long[] values, int accent) {
		long peak = 0;
		for (long v : values) {
			peak = Math.max(peak, v);
		}
		double max = niceMax(peak);
		grid(c, x, y, w, h, max, false);
		int px = x + LABEL_W;
		int pw = w - LABEL_W;
		int n = values.length;
		int slot = Math.max(2, pw / n);
		int gap = slot >= 6 ? 2 : 1;
		int hovered = -1;
		int x0 = px + (pw - slot * n) / 2;
		for (int i = 0; i < n; i++) {
			int bx = x0 + i * slot;
			int bh = values[i] <= 0 ? 0 : Math.max(1, (int) Math.round(h * values[i] / max));
			boolean hv = c.hover(bx, y, slot, h);
			if (hv) {
				hovered = i;
				c.fill(bx, y, slot, h, UiMix.alpha(Canvas.TEXT, 14));
			}
			if (bh > 0) {
				c.fill(bx + gap / 2, y + h - bh, slot - gap, bh, hv ? UiMix.mix(accent, 0xFFFFFFFF, 0.25f) : accent);
			}
		}
		return hovered;
	}

	/**
	 * A line chart over {@code n} evenly spaced points with a faint fill under it; {@code max} <= 0 = fit. Returns the hovered
	 * point index or -1. Needs at least 2 points to draw a line (one point draws a dot).
	 */
	static int line(Canvas c, int x, int y, int w, int h, double[] values, double max, boolean percent, int accent) {
		double top = max > 0 ? max : niceMax(maxOf(values));
		grid(c, x, y, w, h, top, percent);
		int px = x + LABEL_W + 4;
		int pw = w - LABEL_W - 8;
		int n = values.length;
		if (n == 0) {
			return -1;
		}
		int[] py = new int[n];
		int[] pxs = new int[n];
		for (int i = 0; i < n; i++) {
			pxs[i] = n == 1 ? px + pw / 2 : px + Math.round(pw * (float) i / (n - 1));
			double v = Math.max(0, Math.min(top, values[i]));
			py[i] = y + h - Math.round((float) (h * v / top));
		}
		int fillColor = UiMix.alpha(accent, 40);
		for (int i = 0; i + 1 < n; i++) {
			int xa = pxs[i];
			int xb = pxs[i + 1];
			for (int sx = xa; sx <= xb; sx++) {
				float t = xb == xa ? 0 : (sx - xa) / (float) (xb - xa);
				int sy = Math.round(py[i] + (py[i + 1] - py[i]) * t);
				c.fill(sx, sy + 1, 1, y + h - sy - 1, fillColor);
			}
		}
		for (int i = 0; i + 1 < n; i++) {
			int xa = pxs[i];
			int xb = pxs[i + 1];
			int prev = py[i];
			for (int sx = xa; sx <= xb; sx++) {
				float t = xb == xa ? 0 : (sx - xa) / (float) (xb - xa);
				int sy = Math.round(py[i] + (py[i + 1] - py[i]) * t);
				int lo = Math.min(prev, sy);
				int hi = Math.max(prev, sy);
				c.fill(sx, lo, 1, hi - lo + 1, accent);
				c.fill(sx, lo + 1, 1, hi - lo + 1, accent);
				prev = sy;
			}
		}
		int hovered = -1;
		int best = Integer.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			int d = Math.abs(pxs[i] - c.mx);
			if (c.hover(x + LABEL_W, y, w - LABEL_W, h) && d < best) {
				best = d;
				hovered = i;
			}
		}
		for (int i = 0; i < n; i++) {
			if (n <= 24 || i == hovered || i == n - 1) {
				boolean hv = i == hovered;
				int r = hv ? 3 : 2;
				c.fill(pxs[i] - r / 2, py[i] - r / 2, r, r, hv ? 0xFFFFFFFF : accent);
			}
		}
		if (hovered >= 0) {
			c.fill(pxs[hovered], y, 1, h, UiMix.alpha(Canvas.TEXT, 40));
		}
		return hovered;
	}

	/** The retention curve: 21 points (0, 5 .. 100 % of the video) of the audience still watching, 0..1. */
	static int retention(Canvas c, int x, int y, int w, int h, List<Double> points, int accent) {
		double[] v = new double[points.size()];
		double peak = 0;
		for (int i = 0; i < v.length; i++) {
			v[i] = points.get(i);
			peak = Math.max(peak, v[i]);
		}
		double top = peak <= 1.0 ? 1.0 : niceMax(peak);
		int hov = line(c, x, y, w, h, v, top, true, accent);
		// x axis: start, middle, end of the video
		int px = x + LABEL_W + 4;
		int pw = w - LABEL_W - 8;
		c.text("0%", px - 2, y + h + 3, Canvas.FAINT);
		c.textC("50%", px + pw / 2, y + h + 3, Canvas.FAINT);
		c.textR("100%", px + pw + 2, y + h + 3, Canvas.FAINT);
		return hov;
	}

	static double maxOf(double[] v) {
		double m = 0;
		for (double d : v) {
			m = Math.max(m, d);
		}
		return m;
	}

	/** Tooltip text for a hovered column of the 28-day chart. */
	static @Nullable String dayTip(@Nullable ContentChannelStats cs, long[] values, int i) {
		if (i < 0 || i >= values.length) {
			return null;
		}
		String d = ContentState.dailyLabel(cs, i);
		return (d.isEmpty() ? "day " + (i + 1) : d) + ": " + values[i] + " views";
	}
}

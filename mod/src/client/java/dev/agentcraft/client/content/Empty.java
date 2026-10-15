package dev.agentcraft.client.content;

/** The shared empty states of the tabs. */
final class Empty {
	private Empty() {
	}

	/** No channel yet: say how to add one. */
	static void noChannel(Canvas c, int x, int y, int w, int h) {
		c.card(x, y, w, h, Canvas.PANEL);
		int cx = x + w / 2;
		int cy = y + Math.max(20, h / 2 - 44);
		c.textC("No channel yet", cx, cy, Canvas.TEXT);
		int tw = Math.min(w - 40, 360);
		int ty = cy + 18;
		for (String line : dev.agentcraft.client.ui.TextUtil.wrapPlain(c.font, "Add a YouTube channel with + Channel above (name, URL, niche and a short brief on voice and audience), "
			+ "or tell Iris: @iris set up my channel. Iris pitches the videos, Reel makes them, Cleo publishes, Vega reads the numbers.", tw)) {
			c.textC(line, cx, ty, Canvas.MUTED);
			ty += 11;
		}
	}
}

package dev.agentcraft.client.monitor;

import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The enlarged monitor: right-click a desk monitor to see the agent's latest live screen frame (a
 * screenshot from a browser / Roblox Studio / Blender tool, see {@link ScreenFrames}) as big as the
 * window allows, with its source and age, and the last log lines underneath. Without a frame it says
 * so. Live: it follows new frames and log lines while open; the world keeps running behind it.
 * Shootable as {@code dev.screen {open:"monitor"}}.
 */
public final class MonitorViewScreen extends Screen {
	private static final int LOG_LINES = 3;
	private static final int MAX_W = 1000;
	private final String agentId;

	public MonitorViewScreen(String agentId) {
		super(Component.literal("Monitor"));
		this.agentId = agentId;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// no dimming: the studio stays visible around the panel
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		ForemanState st = Foreman.state();
		Agent ag = st == null ? null : st.agent(agentId);
		long now = System.currentTimeMillis();
		ScreenFrames.Frame f = ScreenFrames.latest(agentId);
		boolean fresh = f != null && f.ageMs(now) < ScreenFrames.FRESH_MS;
		Font font = this.font;
		Kit.Padding pp = Kit.padding("panel_paper");
		int ink = UiStyle.color("paper.text");
		int muted = UiStyle.color("paper.muted");

		int panelW = Math.min(MAX_W, this.width - 24);
		int innerW = panelW - pp.left() - pp.right();
		int headH = 14;
		int logH = LOG_LINES * 10 + 6;
		int maxImgH = this.height - 24 - pp.top() - pp.bottom() - headH - logH - 4;
		int imgW;
		int imgH;
		if (f != null) {
			double s = Math.min(innerW / (double) f.bigW(), maxImgH / (double) f.bigH());
			imgW = Math.max(40, (int) (f.bigW() * s));
			imgH = Math.max(30, (int) (f.bigH() * s));
		} else {
			imgW = innerW;
			imgH = 70;
		}
		int bodyW = Math.max(imgW, Math.min(innerW, 320));
		int w = bodyW + pp.left() + pp.right();
		int h = pp.top() + headH + imgH + 4 + logH + pp.bottom();
		int x0 = (this.width - w) / 2;
		int y0 = (this.height - h) / 2;
		Panels.panel(g, x0, y0, w, h);
		int ix = x0 + pp.left();
		int y = y0 + pp.top();

		// header: name, then source + age on the right
		String name = ag != null ? ag.name() : agentId;
		Panels.text(g, font, TextUtil.ellipsize(font, name, bodyW / 2), ix, y + 2, UiStyle.agentOnLight(agentId));
		String right;
		int rightColor = muted;
		if (f == null) {
			right = "no live screen yet";
		} else if (f.live(now)) {
			right = "LIVE · " + f.label() + " · " + age(f.ageMs(now));
			rightColor = ink;
		} else {
			right = f.label() + " · " + age(f.ageMs(now)) + " ago" + (fresh ? "" : " (stale)");
		}
		Panels.text(g, font, right, ix + bodyW - font.width(right), y + 2, rightColor);
		y += headH;

		// image (or the empty state)
		int imgX = ix + (bodyW - imgW) / 2;
		Panels.inset(g, imgX - 2, y - 2, imgW + 4, imgH + 4);
		if (f != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, f.big(), imgX, y, 0, 0, imgW, imgH, f.bigW(), f.bigH(), f.bigW(), f.bigH());
		} else {
			String l1 = "Nothing to show yet.";
			String l2 = "When this agent uses a browser, Roblox Studio or Blender tool,";
			String l3 = "its screenshots appear here and on the desk monitor.";
			int cy = y + (imgH - 30) / 2;
			Panels.text(g, font, l1, imgX + (imgW - font.width(l1)) / 2, cy, ink);
			Panels.text(g, font, TextUtil.ellipsize(font, l2, imgW - 8), imgX + 4, cy + 11, muted);
			Panels.text(g, font, TextUtil.ellipsize(font, l3, imgW - 8), imgX + 4, cy + 21, muted);
		}
		y += imgH + 4;

		// log tail
		List<LogEntry> logs = st == null ? List.of() : st.logs(agentId);
		int shown = 0;
		for (int i = logs.size() - 1; i >= 0 && shown < LOG_LINES; i--, shown++) {
			LogEntry e = logs.get(i);
			String line = TextUtil.ellipsize(font, e.kind().name().toLowerCase() + "  " + TextUtil.firstLine(e.text()), bodyW);
			Panels.text(g, font, line, ix, y + 3 + (LOG_LINES - 1 - shown) * 10, shown == 0 ? ink : muted);
		}
		Panels.text(g, font, "Esc to close", x0 + w - pp.right() - font.width("Esc to close"), y0 + h - pp.bottom() - 9, muted);
	}

	private static String age(long ms) {
		long s = ms / 1000;
		return s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s";
	}
}

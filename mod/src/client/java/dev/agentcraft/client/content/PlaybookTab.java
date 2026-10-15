package dev.agentcraft.client.content;

import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.ContentChannel;
import dev.agentcraft.client.foreman.Protocol.ContentVideo;
import dev.agentcraft.client.foreman.Protocol.PostMortem;
import dev.agentcraft.client.console.ConsoleFeature;
import java.util.ArrayList;
import java.util.List;

/**
 * Playbook: what works and what does not for this channel, in the numbers Vega and Iris keep (markdown), with
 * the latest post-mortems under it. Iris and Reel read it before every pitch and cut; to change it, talk to Iris
 * or Vega.
 */
final class PlaybookTab implements Tab {
	private final Canvas.Scroll scroll = new Canvas.Scroll();
	private final MarkdownLines md = new MarkdownLines();

	@Override
	public void render(Canvas c, int x, int y, int w, int h) {
		ContentChannel ch = ContentState.channel();
		if (ch == null) {
			Empty.noChannel(c, x, y, w, h);
			return;
		}
		c.card(x, y, w, h, Canvas.PANEL);
		c.text("Playbook of " + ch.name(), x + 12, y + 9, Canvas.TEXT);
		int talk = c.font.width("Talk to Iris") + 16;
		c.button("Talk to Vega", x + w - 12 - 92, y + 5, 92, 18, Canvas.Kind.SECONDARY, true, () -> ConsoleFeature.open("@vega ", false));
		c.button("Talk to Iris", x + w - 12 - 92 - 4 - talk, y + 5, talk, 18, Canvas.Kind.SECONDARY, true, () -> ConsoleFeature.open("@iris ", false));
		int vy = y + 28;
		int vh = h - 29;
		c.fill(x + 1, vy - 1, w - 2, 1, Canvas.RULE);
		String playbook = ch.playbook() == null ? "" : ch.playbook();
		List<PostMortem> pms = new ArrayList<>();
		List<String> pmTitles = new ArrayList<>();
		ForemanState st = Foreman.state();
		if (st != null) {
			for (ContentVideo v : st.contentVideos(ch.id())) {
				for (PostMortem pm : v.postMortems()) {
					pms.add(pm);
					pmTitles.add(v.title());
				}
			}
		}
		StringBuilder body = new StringBuilder(playbook);
		if (!pms.isEmpty()) {
			body.append("\n\n# Latest post-mortems\n");
			for (int i = pms.size() - 1; i >= Math.max(0, pms.size() - 6); i--) {
				body.append("\n## ").append(pmTitles.get(i)).append(" (").append(pms.get(i).horizon()).append(")\n").append(pms.get(i).text()).append('\n');
			}
		}
		if (body.toString().isBlank()) {
			c.text("No playbook yet.", x + 14, vy + 12, Canvas.MUTED);
			c.paragraph("Vega writes it after the first post-mortems (48 hours and 7 days after each publish). Iris and Reel read it before every pitch and cut.",
				x + 14, vy + 26, w - 28, Canvas.FAINT, 0);
			return;
		}
		var lines = md.get(c.font, ch.id() + "|" + body.length() + "|" + body.hashCode(), body.toString(), w - 36);
		c.g.enableScissor(x + 1, vy, x + w - 1, vy + vh);
		int ly = vy + 10 - scroll.off;
		for (var l : lines) {
			if (ly + 10 >= vy && ly <= vy + vh) {
				c.text(l.text(), x + 14 + l.indent(), ly, l.color());
			}
			ly += l.text().isEmpty() ? 6 : 11;
		}
		c.g.disableScissor();
		scroll.max = Math.max(0, ly + scroll.off - vy - vh + 10);
		scroll.clamp();
		c.scrollArea(x, vy, w, vh, scroll);
	}
}

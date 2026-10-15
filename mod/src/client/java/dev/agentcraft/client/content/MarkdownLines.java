package dev.agentcraft.client.content;

import dev.agentcraft.client.ui.TextUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;

/**
 * Markdown (headings, bullets, plain paragraphs) as wrapped, coloured lines for the Studio: playbook, breakdown
 * and transcript. Wrapping is cached by the caller's key and width, so a long file is only laid out when its
 * text or the available width changes.
 */
final class MarkdownLines {
	/** One drawn line: its text, colour and indent in pixels. */
	record Line(String text, int color, int indent) {
	}

	private String key = "";
	private int width = -1;
	private List<Line> lines = List.of();

	/** The wrapped lines of {@code md} for {@code w} pixels; {@code cacheKey} must change when the text does. */
	List<Line> get(Font font, String cacheKey, String md, int w) {
		if (w == width && cacheKey.equals(key)) {
			return lines;
		}
		List<Line> out = new ArrayList<>();
		for (String raw : md.split("\\R", -1)) {
			String s = raw.stripTrailing().replace("**", "").replace("`", "");
			if (s.isBlank()) {
				if (!out.isEmpty() && !out.getLast().text().isEmpty()) {
					out.add(new Line("", Canvas.TEXT, 0));
				}
				continue;
			}
			int color = UiMix.mix(Canvas.TEXT, Canvas.MUTED, 0.2f);
			int indent = 0;
			if (s.startsWith("#")) {
				int level = 0;
				while (level < s.length() && s.charAt(level) == '#') {
					level++;
				}
				s = s.substring(level).strip();
				color = level <= 1 ? Canvas.ACCENT : Canvas.TEXT;
				if (!out.isEmpty() && !out.getLast().text().isEmpty()) {
					out.add(new Line("", Canvas.TEXT, 0));
				}
			} else if (s.stripLeading().startsWith("- ") || s.stripLeading().startsWith("* ")) {
				int lead = s.length() - s.stripLeading().length();
				s = "- " + s.stripLeading().substring(2);
				indent = Math.min(24, lead * 3);
			}
			boolean bullet = s.startsWith("- ");
			for (String l : TextUtil.wrapPlain(font, s, Math.max(40, w - indent - (bullet ? 8 : 0)))) {
				out.add(new Line(l, color, indent + (bullet && !l.startsWith("- ") ? 8 : 0)));
			}
		}
		key = cacheKey;
		width = w;
		lines = List.copyOf(out);
		return lines;
	}
}

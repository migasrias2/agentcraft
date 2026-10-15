package dev.agentcraft.client.content;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.console.TextModel;
import dev.agentcraft.client.hud.UiBits;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;

/**
 * A dark text input for the Studio (comments, times, the "+ Channel" form): a {@link TextModel} drawn as a
 * flat box with light text, a placeholder, up to {@code maxLines} wrapped lines and a blinking caret.
 */
final class DarkField {
	private static final int LINE = 10;
	private static final int PAD = 5;

	final TextModel model;
	private final String placeholder;
	private final int maxLines;
	private int x;
	private int y;
	private int w;

	DarkField(String placeholder, int maxLength, int maxLines) {
		this.model = new TextModel(maxLength);
		this.placeholder = placeholder;
		this.maxLines = maxLines;
	}

	String value() {
		return model.value();
	}

	boolean isBlank() {
		return model.value().isBlank();
	}

	void clear() {
		model.clear();
	}

	private List<TextModel.VLine> layout(Font font, int width) {
		return model.layout(font, Math.max(20, width - 2 * PAD));
	}

	int draw(Canvas c, int x, int y, int w, boolean focused) {
		this.x = x;
		this.y = y;
		this.w = w;
		Font font = c.font;
		List<TextModel.VLine> lines = layout(font, w);
		int vis = Math.max(1, Math.min(maxLines, lines.size()));
		int h = 2 * PAD + vis * LINE - 1;
		c.fill(x, y, w, h, 0xFF101014);
		c.outline(x, y, w, h, focused ? Canvas.ACCENT : Canvas.RULE);
		String v = model.value();
		int caretLine = TextModel.lineOf(lines, model.cursor());
		int first = Math.max(0, Math.min(caretLine - vis + 1, lines.size() - vis));
		if (model.isEmpty()) {
			c.text(placeholder, x + PAD, y + PAD, Canvas.FAINT);
		}
		int caretX = x + PAD;
		int caretY = y + PAD;
		for (int i = 0; i < vis && first + i < lines.size(); i++) {
			TextModel.VLine l = lines.get(first + i);
			int ly = y + PAD + i * LINE;
			String seg = v.substring(l.start(), l.end());
			if (model.hasSelection()) {
				int a = Math.max(model.selStart(), l.start());
				int b = Math.min(model.selEnd(), l.end());
				if (a < b) {
					int sx = x + PAD + font.width(v.substring(l.start(), a));
					int ex = x + PAD + font.width(v.substring(l.start(), b));
					c.g.fill(sx, ly - 1, ex, ly + 9, UiMix.alpha(Canvas.ACCENT, 110));
				}
			}
			c.text(seg, x + PAD, ly, Canvas.TEXT);
			if (first + i == caretLine) {
				caretX = x + PAD + font.width(v.substring(l.start(), Math.min(model.cursor(), l.end())));
				caretY = ly;
			}
		}
		if (focused && UiBits.caretOn(model.editedAt())) {
			c.g.fill(caretX, caretY - 1, caretX + 1, caretY + 9, Canvas.TEXT);
		}
		if (lines.size() > vis) {
			String more = (first + vis) + "/" + lines.size();
			c.textR(more, x + w - 3, y + h - 10, Canvas.FAINT);
		}
		return h;
	}

	/** Moves the caret to the character under the mouse (the box drawn last frame). */
	void placeCaret(Canvas c, double mx, double my) {
		Font font = c.font;
		List<TextModel.VLine> lines = layout(font, w);
		int vis = Math.max(1, Math.min(maxLines, lines.size()));
		int caretLine = TextModel.lineOf(lines, model.cursor());
		int first = Math.max(0, Math.min(caretLine - vis + 1, lines.size() - vis));
		int row = Math.max(0, Math.min(vis - 1, ((int) my - y - PAD) / LINE));
		if (first + row >= lines.size()) {
			return;
		}
		TextModel.VLine l = lines.get(first + row);
		String v = model.value();
		int best = l.start();
		double bestD = Double.MAX_VALUE;
		for (int i = l.start(); i <= l.end(); i++) {
			double d = Math.abs(x + PAD + font.width(v.substring(l.start(), i)) - mx);
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		model.moveTo(best, false);
	}

	/** Typing keys; returns true when the key was used. Enter inserts a newline only in a multi-line field. */
	boolean key(Font font, KeyEvent e) {
		int k = e.key();
		if (TextKeys.isEnter(e)) {
			if (maxLines > 1) {
				model.insert("\n");
				return true;
			}
			return false;
		}
		if ((k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN) && maxLines > 1) {
			model.vertical(font, Math.max(20, w - 2 * PAD), k == InputConstants.KEY_UP ? -1 : 1, e.hasShiftDown());
			return true;
		}
		return TextKeys.handle(e, model);
	}
}

package dev.agentcraft.client.content;

import dev.agentcraft.client.ui.TextUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * One frame of drawing for the Content Studio: the dark palette, the graphics and font, the mouse, and the
 * click and scroll areas the tabs register while they draw. Cleared at the start of every frame by the screen,
 * so a tab only draws and registers (no retained widgets).
 */
final class Canvas {
	static final int BG = 0xFF0C0C0F;
	static final int PANEL = 0xFF16161A;
	static final int PANEL_HI = 0xFF1F1F25;
	static final int RULE = 0xFF2A2A31;
	static final int TEXT = 0xFFF5F5F7;
	static final int MUTED = 0xFF8E8E93;
	static final int FAINT = 0xFF55555D;
	/** The one accent colour: Iris' ember orange. */
	static final int ACCENT = 0xFFE4572E;
	static final int WARN = 0xFFFF6B5E;
	static final int OK = 0xFF5CC98B;

	enum Kind {
		PRIMARY, SECONDARY, GHOST, DANGER
	}

	record Hit(int x, int y, int w, int h, Runnable action) {
		boolean at(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** A scrolling region: the tab sets {@code max} while drawing, the screen moves {@code off} on wheel input. */
	static final class Scroll {
		int off;
		int max;

		void by(int delta) {
			off = Math.max(0, Math.min(max, off + delta));
		}

		void clamp() {
			off = Math.max(0, Math.min(max, off));
		}
	}

	private record ScrollArea(int x, int y, int w, int h, Scroll scroll) {
	}

	GuiGraphicsExtractor g;
	Font font;
	int mx;
	int my;
	final ContentActions actions;
	final List<Hit> hits = new ArrayList<>();
	private final List<ScrollArea> scrolls = new ArrayList<>();
	/** The field that gets the keys (null = none); survives frames. */
	@Nullable DarkField focus;

	Canvas(ContentActions actions) {
		this.actions = actions;
	}

	void begin(GuiGraphicsExtractor g, Font font, int mx, int my) {
		this.g = g;
		this.font = font;
		this.mx = mx;
		this.my = my;
		hits.clear();
		scrolls.clear();
	}

	// ------------------------------------------------------------------ input areas

	boolean hover(int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	void hit(int x, int y, int w, int h, Runnable action) {
		hits.add(new Hit(x, y, w, h, action));
	}

	/** A click area clipped to a viewport (scrolled content must not catch clicks outside its view). */
	void hitIn(int vx, int vy, int vw, int vh, int x, int y, int w, int h, Runnable action) {
		int x0 = Math.max(x, vx);
		int y0 = Math.max(y, vy);
		int x1 = Math.min(x + w, vx + vw);
		int y1 = Math.min(y + h, vy + vh);
		if (x1 > x0 && y1 > y0) {
			hit(x0, y0, x1 - x0, y1 - y0, action);
		}
	}

	void scrollArea(int x, int y, int w, int h, Scroll s) {
		scrolls.add(new ScrollArea(x, y, w, h, s));
	}

	boolean click(double x, double y) {
		for (int i = hits.size() - 1; i >= 0; i--) {
			if (hits.get(i).at(x, y)) {
				hits.get(i).action().run();
				return true;
			}
		}
		return false;
	}

	boolean scroll(double x, double y, double dy) {
		for (int i = scrolls.size() - 1; i >= 0; i--) {
			ScrollArea a = scrolls.get(i);
			if (x >= a.x() && x < a.x() + a.w() && y >= a.y() && y < a.y() + a.h()) {
				a.scroll().by((int) Math.round(-dy * 24));
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ drawing

	void fill(int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + h, color);
	}

	void outline(int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y, x + 1, y + h, color);
		g.fill(x + w - 1, y, x + w, y + h, color);
	}

	/** A card: a slightly lighter rectangle with a hairline. */
	void card(int x, int y, int w, int h, int color) {
		fill(x, y, w, h, color);
		outline(x, y, w, h, RULE);
	}

	void text(String s, int x, int y, int color) {
		g.text(font, s, x, y, color, false);
	}

	/** Text ending at {@code right}. */
	void textR(String s, int right, int y, int color) {
		g.text(font, s, right - font.width(s), y, color, false);
	}

	void textC(String s, int cx, int y, int color) {
		g.text(font, s, cx - font.width(s) / 2, y, color, false);
	}

	/** Text cut with an ellipsis to {@code w} pixels. */
	void fit(String s, int x, int y, int w, int color) {
		g.text(font, TextUtil.ellipsize(font, s, w), x, y, color, false);
	}

	/** Scaled text (hero numbers). Returns the drawn width in GUI pixels. */
	int big(String s, int x, int y, float scale, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font, s, 0, 0, color, false);
		g.pose().popMatrix();
		return Math.round(font.width(s) * scale);
	}

	/** Wrapped paragraph; returns the y below it. {@code maxLines} <= 0 = all. */
	int paragraph(String s, int x, int y, int w, int color, int maxLines) {
		int n = 0;
		for (String line : TextUtil.wrapPlain(font, s, w)) {
			if (maxLines > 0 && n == maxLines) {
				break;
			}
			g.text(font, line, x, y, color, false);
			y += 11;
			n++;
		}
		return y;
	}

	int lines(String s, int w) {
		return TextUtil.wrapPlain(font, s, w).size();
	}

	/** A button; returns its right edge (x + w). A disabled one draws dimmed and registers nothing. */
	int button(String label, int x, int y, int w, int h, Kind kind, boolean enabled, Runnable action) {
		boolean hv = enabled && hover(x, y, w, h);
		int bg = switch (kind) {
			case PRIMARY -> hv ? 0xFFF06A42 : ACCENT;
			case DANGER -> hv ? 0xFF3A1D1B : 0xFF2A1715;
			case GHOST -> hv ? PANEL_HI : 0;
			default -> hv ? 0xFF2B2B33 : PANEL_HI;
		};
		if (bg != 0) {
			fill(x, y, w, h, bg);
		}
		if (kind == Kind.GHOST || kind == Kind.SECONDARY) {
			outline(x, y, w, h, RULE);
		}
		int fg = !enabled ? FAINT : kind == Kind.PRIMARY ? 0xFFFFFFFF : kind == Kind.DANGER ? WARN : TEXT;
		String l = TextUtil.ellipsize(font, label, w - 8);
		g.text(font, l, x + (w - font.width(l)) / 2, y + (h - 8) / 2, fg, false);
		if (enabled) {
			hit(x, y, w, h, action);
		}
		return x + w;
	}

	/** A small status pill with a coloured dot. Returns its width. */
	int pill(String s, int x, int y, int color) {
		int w = font.width(s) + 16;
		fill(x, y, w, 13, UiMix.mix(PANEL_HI, color, 0.18f));
		fill(x + 5, y + 5, 3, 3, color);
		text(s, x + 11, y + 3, UiMix.mix(TEXT, color, 0.35f));
		return w;
	}

	/** A text field; clicking it focuses it. Returns its height. */
	int field(DarkField f, int x, int y, int w) {
		int h = f.draw(this, x, y, w, f == focus);
		hit(x, y, w, h, () -> {
			focus = f;
			f.placeCaret(this, mx, my);
		});
		return h;
	}
}

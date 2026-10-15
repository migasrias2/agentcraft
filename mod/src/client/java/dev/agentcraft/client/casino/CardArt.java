package dev.agentcraft.client.casino;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing for the blackjack table: rounded rectangles, discs, playing cards and chips, all from plain
 * fills so nothing depends on the font having suit glyphs.
 */
final class CardArt {
	static final int CARD_FACE = 0xFFF8F4EA;
	static final int CARD_EDGE = 0xFFCFC6B3;
	static final int RED = 0xFFB3261E;
	static final int BLACK = 0xFF1F1E1D;

	private static final String[] HEART = {".##.##.", "#######", "#######", "#######", ".#####.", "..###..", "...#..."};
	private static final String[] DIAMOND = {"...#...", "..###..", ".#####.", "#######", ".#####.", "..###..", "...#..."};
	private static final String[] SPADE = {"...#...", "..###..", ".#####.", "#######", "#######", "...#...", "..###.."};
	private static final String[] CLUB = {"..###..", ".#####.", "..###..", "##.#.##", "#######", "##.#.##", "...#..."};

	static String[] bitmap(Blackjack.Suit suit) {
		return switch (suit) {
			case HEARTS -> HEART;
			case DIAMONDS -> DIAMOND;
			case SPADES -> SPADE;
			case CLUBS -> CLUB;
		};
	}

	private CardArt() {
	}

	/** A filled rectangle with rounded corners of radius {@code r}. */
	static void rrect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		int rad = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		g.fill(x, y + rad, x + w, y + h - rad, color);
		for (int i = 0; i < rad; i++) {
			double dy = rad - i - 0.5;
			int inset = rad - (int) Math.round(Math.sqrt(rad * rad - dy * dy));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
	}

	/** A filled disc. */
	static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int dx = (int) Math.round(Math.sqrt(Math.max(0, r * r + r - dy * dy)));
			g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
		}
	}

	/** A suit symbol of 7 x 7 cells, each {@code scale} GUI pixels. */
	static void suit(GuiGraphicsExtractor g, Blackjack.Suit suit, int x, int y, int scale, int color) {
		String[] rows = switch (suit) {
			case HEARTS -> HEART;
			case DIAMONDS -> DIAMOND;
			case SPADES -> SPADE;
			case CLUBS -> CLUB;
		};
		for (int row = 0; row < rows.length; row++) {
			String line = rows[row];
			int col = 0;
			while (col < line.length()) {
				if (line.charAt(col) != '#') {
					col++;
					continue;
				}
				int end = col;
				while (end < line.length() && line.charAt(end) == '#') {
					end++;
				}
				g.fill(x + col * scale, y + row * scale, x + end * scale, y + (row + 1) * scale, color);
				col = end;
			}
		}
	}

	/** One card, face up or face down, {@code w} x {@code h} GUI pixels. */
	static void card(GuiGraphicsExtractor g, Font font, Blackjack.Card card, boolean faceDown, int x, int y, int w, int h) {
		rrect(g, x + 1, y + 2, w, h, 3, 0x55000000);
		if (faceDown) {
			rrect(g, x, y, w, h, 3, CARD_FACE);
			rrect(g, x + 2, y + 2, w - 4, h - 4, 2, 0xFF9B3A28);
			for (int yy = y + 5; yy < y + h - 5; yy += 4) {
				for (int xx = x + 5 + ((yy - y) / 4 % 2) * 2; xx < x + w - 5; xx += 4) {
					g.fill(xx, yy, xx + 2, yy + 2, 0xFFC9724F);
				}
			}
			return;
		}
		rrect(g, x, y, w, h, 3, CARD_EDGE);
		rrect(g, x + 1, y + 1, w - 2, h - 2, 3, CARD_FACE);
		int ink = card.suit().red() ? RED : BLACK;
		String label = card.label();
		g.text(font, label, x + 4, y + 4, ink, false);
		suit(g, card.suit(), x + 4, y + 14, 1, ink);
		int big = w >= 36 ? 3 : 2;
		suit(g, card.suit(), x + (w - 7 * big) / 2, y + (h - 7 * big) / 2 + 2, big, ink);
		g.text(font, label, x + w - 4 - font.width(label), y + h - 12, ink, false);
	}

	/** A small stack of chips; the count and colours grow with the bet. */
	static void chips(GuiGraphicsExtractor g, int x, int baseY, int bet) {
		int n = Math.max(1, Math.min(6, 1 + bet / 50));
		int[] colors = {0xFFD97757, 0xFFF4EFE6, 0xFF2FA3A0, 0xFFC9A227};
		for (int i = 0; i < n; i++) {
			int y = baseY - 3 - i * 3;
			int c = colors[(bet >= 100 ? i + 1 : bet >= 50 ? i : i + 2) % colors.length];
			g.fill(x - 8, y, x + 9, y + 3, 0xFF1F1E1D);
			g.fill(x - 7, y, x + 8, y + 2, c);
			g.fill(x - 7, y + 1, x - 3, y + 2, 0xFFF4EFE6);
			g.fill(x + 4, y + 1, x + 8, y + 2, 0xFFF4EFE6);
		}
	}
}

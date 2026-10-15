package dev.agentcraft.client.casino;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The blackjack table GUI (see {@link Blackjack} for the rules). Click the buttons or use the keys:
 * H hit, S stand, D double, P split, Space deal / next hand, B change bet, R rebuy, Esc leave (a hand in
 * progress just waits for you). The optional big view; the default is playing at the table in the world
 * ({@link TableView}); the session ({@link CasinoSession}) paces the dealer.
 */
public final class BlackjackScreen extends Screen {
	private static final int FELT = 0xFF1E5B3A;
	private static final int FELT_DARK = 0xFF15422B;
	private static final int CREAM = UiStyle.CREAM;
	private static final int MUTED = 0xFFA9C2AE;
	private static final int BRASS = UiStyle.BRASS;

	private record Btn(String id, String label, int x, int y, int w, boolean primary) {
	}

	private final Blackjack game;
	private final List<Btn> buttons = new ArrayList<>();

	public BlackjackScreen(Blackjack game) {
		super(Component.literal("Blackjack"));
		this.game = game;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, this.width, this.height, 0x66000000);
	}

	@Override
	public void removed() {
		BlackjackSave.save(game);
		super.removed();
	}

	// ------------------------------------------------------------------ input

	private void act(String id) {
		CasinoSession.act(game, id);
	}

	private void space() {
		switch (game.phase()) {
			case BETTING -> act("deal");
			case DONE -> act(game.balance() >= Blackjack.MIN_BET ? "again" : "rebuy");
			default -> {
			}
		}
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.isEscape()) {
			onClose();
			return true;
		}
		switch (e.input()) {
			case InputConstants.KEY_H -> act("hit");
			case InputConstants.KEY_S -> act("stand");
			case InputConstants.KEY_D -> act("double");
			case InputConstants.KEY_P -> act("split");
			case InputConstants.KEY_B -> act("change");
			case InputConstants.KEY_R -> act("rebuy");
			case InputConstants.KEY_SPACE, InputConstants.KEY_RETURN -> space();
			default -> {
				return super.keyPressed(e);
			}
		}
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) { // 26.3: SDL numbering, left = 1
			for (Btn b : buttons) {
				if (e.x() >= b.x && e.x() < b.x + b.w && e.y() >= b.y && e.y() < b.y + 20) {
					act(b.id);
					return true;
				}
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	// ------------------------------------------------------------------ drawing

	private static String chips(int n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Font font = this.font;
		buttons.clear();
		int w = Math.min(this.width - 16, 480);
		int h = Math.min(this.height - 16, 270);
		int x0 = (this.width - w) / 2;
		int y0 = (this.height - h) / 2;
		CardArt.rrect(g, x0, y0, w, h, 5, UiStyle.WALNUT);
		CardArt.rrect(g, x0 + 1, y0 + 1, w - 2, h - 2, 5, BRASS);
		CardArt.rrect(g, x0 + 2, y0 + 2, w - 4, h - 4, 4, UiStyle.WALNUT);
		int fx = x0 + 5;
		int fy = y0 + 5;
		int fw = w - 10;
		int fh = h - 10;
		CardArt.rrect(g, fx, fy, fw, fh, 4, FELT_DARK);
		CardArt.rrect(g, fx + 2, fy + 2, fw - 4, fh - 4, 3, FELT);

		int cardH = Math.max(30, Math.min(52, (fh - 118) / 2));
		int cardW = cardH * 36 / 50;
		int cx = fx + fw / 2;

		// title and chips
		Panels.text(g, font, "BLACKJACK", fx + 10, fy + 7, BRASS);
		String pocket = "Chips " + chips(game.balance());
		Panels.text(g, font, pocket, fx + fw - 10 - font.width(pocket), fy + 7, CREAM);

		// dealer
		boolean showDealer = !game.dealerCards().isEmpty();
		String dealerLabel = "Dealer" + (showDealer ? "  " + game.dealerShownValue() + (game.dealerBusted() ? " (bust)" : "") : "");
		g.centeredText(font, dealerLabel, cx, fy + 20, MUTED);
		int cardsY = fy + 32;
		if (showDealer) {
			List<Blackjack.Card> dc = game.dealerCards();
			drawRow(g, font, dc, cx, cardsY, cardW, cardH, fw * 9 / 10, game.holeRevealed() ? -1 : 1);
		} else {
			drawEmptySlots(g, cx, cardsY, cardW, cardH);
		}
		String says = game.says();
		if (!says.isEmpty()) {
			g.centeredText(font, "\"" + says + "\"", cx, cardsY + cardH + 5, CREAM);
		}

		// result banner
		int bannerY = cardsY + cardH + 18;
		String banner = game.banner();
		if (!banner.isEmpty()) {
			int bw = font.width(banner) + 24;
			int tone = game.lastNet() > 0 ? 0xFF2D6A45 : game.lastNet() < 0 ? 0xFF7A2E2A : 0xFF3A3A35;
			CardArt.rrect(g, cx - bw / 2, bannerY, bw, 15, 4, UiStyle.WALNUT);
			CardArt.rrect(g, cx - bw / 2 + 1, bannerY + 1, bw - 2, 13, 3, tone);
			g.centeredText(font, banner, cx, bannerY + 4, game.lastNet() > 0 ? BRASS : CREAM);
		}

		// player hands
		int labelY = bannerY + 20;
		int handsY = labelY + 12;
		List<Blackjack.Hand> hands = game.hands();
		if (hands.isEmpty()) {
			drawEmptySlots(g, cx, handsY, cardW, cardH);
			String bet = game.bet() > 0 ? "Bet " + chips(game.bet()) : "Place your bet";
			g.centeredText(font, bet, cx, labelY, game.bet() > 0 ? CREAM : MUTED);
			if (game.bet() > 0) {
				CardArt.chips(g, fx + 30, handsY + cardH, game.bet());
			}
		} else {
			int n = hands.size();
			for (int i = 0; i < n; i++) {
				int hx = n == 1 ? cx : fx + fw * (i == 0 ? 27 : 73) / 100;
				Blackjack.Hand hand = hands.get(i);
				boolean active = game.phase() == Blackjack.Phase.PLAYER && i == game.activeIndex();
				String label = handLabel(hand) + "   Bet " + chips(hand.bet());
				int lw = font.width(label);
				g.centeredText(font, label, hx, labelY, active ? CREAM : MUTED);
				if (active) {
					g.fill(hx - lw / 2, labelY + 10, hx + lw / 2, labelY + 11, BRASS);
				}
				drawRow(g, font, hand.cards(), hx, handsY, cardW, cardH, (n == 1 ? fw * 9 / 10 : fw * 44 / 100), -1);
			}
			CardArt.chips(g, fx + 22, handsY + cardH, hands.get(0).bet());
		}

		// buttons and stats
		layoutButtons(font, fx, fy + fh - 36, fw);
		for (Btn b : buttons) {
			boolean hover = mouseX >= b.x && mouseX < b.x + b.w && mouseY >= b.y && mouseY < b.y + 20;
			Panels.button(g, font, b.label, b.x, b.y, b.w, b.primary, hover, false);
		}
		Blackjack.Stats st = game.stats();
		String foot = "Hands " + st.hands + "  Won " + st.won + "  Lost " + st.lost + "  Best +" + chips(st.biggestWin) + "  Shoe " + game.cardsLeft()
			+ (game.shuffledNow() ? " (new)" : "");
		g.centeredText(font, foot, cx, fy + fh - 12, MUTED);
		if (game.canRebuy()) {
			g.centeredText(font, "You're out of chips", cx, fy + fh - 48, BRASS);
		}
	}

	private static String handLabel(Blackjack.Hand hand) {
		if (hand.blackjack()) {
			return "Blackjack";
		}
		int v = hand.value();
		if (v > 21) {
			return v + " (bust)";
		}
		return (hand.soft() ? "Soft " : "") + v + (hand.doubled() ? "  x2" : "");
	}

	/** A centred row of overlapping cards; {@code hideIndex} >= 0 draws that card face down. */
	private void drawRow(GuiGraphicsExtractor g, Font font, List<Blackjack.Card> cards, int cx, int y, int cw, int ch, int maxW, int hideIndex) {
		int n = cards.size();
		int step = n <= 1 ? cw : Math.min(cw + 4, (maxW - cw) / (n - 1));
		int total = cw + step * (n - 1);
		int x = cx - total / 2;
		for (int i = 0; i < n; i++) {
			CardArt.card(g, font, cards.get(i), i == hideIndex, x + i * step, y, cw, ch);
		}
	}

	private static void drawEmptySlots(GuiGraphicsExtractor g, int cx, int y, int cw, int ch) {
		for (int i = 0; i < 2; i++) {
			int x = cx - cw - 2 + i * (cw + 4);
			CardArt.rrect(g, x, y, cw, ch, 3, FELT_DARK);
			CardArt.rrect(g, x + 1, y + 1, cw - 2, ch - 2, 3, 0xFF1A5234);
		}
	}

	private void layoutButtons(Font font, int fx, int y, int fw) {
		List<Btn> row = new ArrayList<>();
		switch (game.phase()) {
			case BETTING -> {
				row.add(new Btn("bet10", "+10", 0, y, 0, false));
				row.add(new Btn("bet50", "+50", 0, y, 0, false));
				row.add(new Btn("bet100", "+100", 0, y, 0, false));
				row.add(new Btn("clear", "Clear", 0, y, 0, false));
				if (game.canDeal()) {
					row.add(new Btn("deal", "Deal [Space]", 0, y, 0, true));
				}
				if (game.canRebuy()) {
					row.add(new Btn("rebuy", "Rebuy 1000 [R]", 0, y, 0, true));
				}
			}
			case PLAYER -> {
				row.add(new Btn("hit", "Hit [H]", 0, y, 0, true));
				row.add(new Btn("stand", "Stand [S]", 0, y, 0, false));
				if (game.canDouble()) {
					row.add(new Btn("double", "Double [D]", 0, y, 0, false));
				}
				if (game.canSplit()) {
					row.add(new Btn("split", "Split [P]", 0, y, 0, false));
				}
			}
			case DONE -> {
				if (game.balance() >= Blackjack.MIN_BET) {
					row.add(new Btn("again", "Deal again [Space]", 0, y, 0, true));
					row.add(new Btn("change", "Change bet [B]", 0, y, 0, false));
				}
				if (game.canRebuy()) {
					row.add(new Btn("rebuy", "Rebuy 1000 [R]", 0, y, 0, true));
				}
			}
			default -> {
			}
		}
		int total = -4;
		int[] widths = new int[row.size()];
		for (int i = 0; i < row.size(); i++) {
			widths[i] = Math.max(44, font.width(row.get(i).label) + 18);
			total += widths[i] + 4;
		}
		int x = fx + (fw - total) / 2;
		for (int i = 0; i < row.size(); i++) {
			Btn b = row.get(i);
			buttons.add(new Btn(b.id, b.label, x, y, widths[i], b.primary));
			x += widths[i] + 4;
		}
	}
}

package dev.agentcraft.client.casino;

import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The strip above the hotbar while seated at the blackjack table: chips, bet, totals and the keys that work now. */
final class SeatHud implements HudElement {
	private static final int INK = 0xE61F1E1D;
	private static final int CREAM = 0xFFF4EFE6;
	private static final int MUTED = 0xFFB9B2A4;
	private static final int BRASS = 0xFFC9A227;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Blackjack game = CasinoFeature.peek();
		Minecraft mc = Minecraft.getInstance();
		if (!CasinoSession.seated() || game == null || mc.gui.screen() != null || mc.gui.hud.isHidden()) {
			return;
		}
		Font font = mc.font;
		String line1 = String.format(Locale.ROOT, "Chips %,d    Bet %,d", game.balance(), game.phase() == Blackjack.Phase.BETTING || game.hands().isEmpty()
			? game.bet() : game.hands().stream().mapToInt(Blackjack.Hand::bet).sum());
		if (!TableView.hudYou.isEmpty()) {
			line1 += "    You " + TableView.hudYou;
		}
		if (!TableView.hudDealer.isEmpty()) {
			line1 += "    Dealer " + TableView.hudDealer;
		}
		String banner = game.banner();
		String line2 = hints(game);
		int w = Math.min(g.guiWidth() - 20, Math.max(font.width(line1), font.width(line2)) + 28);
		int h = banner.isEmpty() ? 28 : 38;
		int x = (g.guiWidth() - w) / 2;
		int y = g.guiHeight() - 26 - h - 4;
		CardArt.rrect(g, x, y, w, h, 5, INK);
		CardArt.rrect(g, x + 1, y + 1, w - 2, h - 2, 4, 0xFF26221F);
		int ty = y + 6;
		if (!banner.isEmpty()) {
			g.centeredText(font, banner, g.guiWidth() / 2, ty, game.lastNet() > 0 ? BRASS : CREAM);
			ty += 10;
		}
		g.centeredText(font, line1, g.guiWidth() / 2, ty, CREAM);
		g.centeredText(font, line2, g.guiWidth() / 2, ty + 11, MUTED);
	}

	private static String k(KeyMapping m) {
		return m.getTranslatedKeyMessage().getString();
	}

	private static String hints(Blackjack game) {
		String leave = "  " + k(CasinoSession.LEAVE) + " leave  " + k(CasinoSession.BIG_VIEW) + " big view";
		return switch (game.phase()) {
			case BETTING -> k(CasinoSession.BET_DOWN) + k(CasinoSession.BET_UP) + " bet (Shift 50)  " + k(CasinoSession.CLEAR) + " clear  " + k(CasinoSession.DEAL)
				+ " deal" + (game.canRebuy() ? "  " + k(CasinoSession.REBUY) + " rebuy" : "") + leave;
			case PLAYER -> k(CasinoSession.HIT) + " hit  " + k(CasinoSession.STAND) + " stand"
				+ (game.canDouble() ? "  " + k(CasinoSession.DOUBLE) + " double" : "") + (game.canSplit() ? "  " + k(CasinoSession.SPLIT) + " split" : "") + leave;
			case DEALER -> "Dealer plays..." + leave;
			case DONE -> k(CasinoSession.DEAL) + " deal again  " + k(CasinoSession.BET_UP) + " change bet" + (game.canRebuy() ? "  " + k(CasinoSession.REBUY) + " rebuy" : "") + leave;
		};
	}
}

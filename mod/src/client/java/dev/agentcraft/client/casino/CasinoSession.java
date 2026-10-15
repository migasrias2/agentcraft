package dev.agentcraft.client.casino;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.ui.Guard;
import dev.agentcraft.hq.CasinoLayout;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Sitting at the blackjack table. Right-click the table or the dealer to sit down; while seated (and
 * within a few blocks of the table, with no screen open) the keys below play the game, and the HUD
 * strip ({@link SeatHud}) shows chips, bet and totals. Walking away or pressing the stand-up key leaves
 * the seat; the hand just waits. The same tick paces the dealer (one card every 0.45 s) wherever you are.
 */
public final class CasinoSession {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(AgentCraft.id("blackjack"));
	private static final int DEALER_TICKS = 9;
	private static final double LEAVE_DISTANCE = 5.0;

	static final KeyMapping DEAL = key("deal", InputConstants.KEY_RETURN);
	static final KeyMapping HIT = key("hit", InputConstants.KEY_H);
	static final KeyMapping STAND = key("stand", InputConstants.KEY_N); // J opens the decisions queue
	static final KeyMapping DOUBLE = key("double", InputConstants.KEY_K);
	static final KeyMapping SPLIT = key("split", InputConstants.KEY_U);
	static final KeyMapping BET_UP = key("bet_up", InputConstants.KEY_RBRACKET);
	static final KeyMapping BET_DOWN = key("bet_down", InputConstants.KEY_LBRACKET);
	static final KeyMapping CLEAR = key("clear", InputConstants.KEY_BACKSPACE);
	static final KeyMapping REBUY = key("rebuy", InputConstants.KEY_R);
	static final KeyMapping BIG_VIEW = key("big_view", InputConstants.KEY_V);
	static final KeyMapping LEAVE = key("leave", InputConstants.KEY_X);
	private static final KeyMapping[] ALL = {DEAL, HIT, STAND, DOUBLE, SPLIT, BET_UP, BET_DOWN, CLEAR, REBUY, BIG_VIEW, LEAVE};

	private static boolean seated;
	private static int dealerTicks;

	private CasinoSession() {
	}

	private static KeyMapping key(String name, int code) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.agentcraft.blackjack_" + name, code, CATEGORY));
	}

	static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("casino.tick", () -> tick(mc)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> seated = false);
	}

	public static boolean seated() {
		return seated;
	}

	static void sit() {
		seated = true;
	}

	static void standUp() {
		seated = false;
	}

	// ------------------------------------------------------------------ tick

	private static void tick(Minecraft mc) {
		Blackjack g = CasinoFeature.peek();
		if (g != null && g.phase() == Blackjack.Phase.DEALER) {
			if (++dealerTicks >= DEALER_TICKS) {
				dealerTicks = 0;
				g.dealerStep();
				if (g.phase() == Blackjack.Phase.DONE) {
					BlackjackSave.save(g);
				}
			}
		} else {
			dealerTicks = 0;
		}
		if (seated && (mc.player == null || mc.level == null
			|| mc.player.distanceToSqr(CasinoLayout.CENTER_X, CasinoLayout.SURFACE_Y - 0.5, -3.5) > LEAVE_DISTANCE * LEAVE_DISTANCE)) {
			seated = false;
		}
		if (!seated || mc.gui.screen() != null) {
			for (KeyMapping k : ALL) {
				while (k.consumeClick()) {
					// presses outside the seat are dropped, so they do not fire later
				}
			}
			return;
		}
		Blackjack game = CasinoFeature.game();
		boolean shift = mc.options.keyShift.isDown();
		while (HIT.consumeClick()) {
			act(game, "hit");
		}
		while (STAND.consumeClick()) {
			act(game, "stand");
		}
		while (DOUBLE.consumeClick()) {
			act(game, "double");
		}
		while (SPLIT.consumeClick()) {
			act(game, "split");
		}
		while (DEAL.consumeClick()) {
			act(game, "space");
		}
		while (BET_UP.consumeClick()) {
			act(game, shift ? "bet50" : "bet10");
		}
		while (BET_DOWN.consumeClick()) {
			act(game, shift ? "sub50" : "sub10");
		}
		while (CLEAR.consumeClick()) {
			act(game, "clear");
		}
		while (REBUY.consumeClick()) {
			act(game, "rebuy");
		}
		while (BIG_VIEW.consumeClick()) {
			mc.gui.setScreen(new BlackjackScreen(game));
		}
		while (LEAVE.consumeClick()) {
			seated = false;
		}
	}

	// ------------------------------------------------------------------ actions (shared with the big view)

	/** Applies one player action by id and saves when the round moved on. */
	static void act(Blackjack game, String id) {
		switch (id) {
			case "bet10" -> betEdit(game, 10);
			case "bet50" -> betEdit(game, 50);
			case "bet100" -> betEdit(game, 100);
			case "sub10" -> betEdit(game, -10);
			case "sub50" -> betEdit(game, -50);
			case "clear" -> {
				game.nextRound();
				game.clearBet();
			}
			case "deal" -> game.deal();
			case "hit" -> game.hit();
			case "stand" -> game.stand();
			case "double" -> game.doubleDown();
			case "split" -> game.split();
			case "again" -> {
				game.nextRound();
				game.deal();
			}
			case "change" -> game.nextRound();
			case "rebuy" -> game.rebuy();
			case "space" -> {
				switch (game.phase()) {
					case BETTING -> game.deal();
					case DONE -> act(game, game.balance() >= Blackjack.MIN_BET ? "again" : "rebuy");
					default -> {
					}
				}
			}
			default -> {
				return;
			}
		}
		if (game.phase() != Blackjack.Phase.PLAYER || id.equals("deal") || id.equals("again") || id.equals("space")) {
			BlackjackSave.save(game);
		}
	}

	private static void betEdit(Blackjack game, int delta) {
		game.nextRound();
		if (delta >= 0) {
			game.addBet(delta);
		} else {
			game.subBet(-delta);
		}
	}
}

package dev.agentcraft.client.casino;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.ui.Guard;
import dev.agentcraft.client.ui.GuardedHud;
import dev.agentcraft.hq.CasinoLayout;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;

/**
 * The Casino Lounge's blackjack: right-click the table or the dealer to sit down and play at the table
 * (cards are drawn on the felt by {@link TableView}; keys and HUD in {@link CasinoSession} / {@link SeatHud}).
 * Purely client-side and just for fun, with no Foreman involvement. The table and the dealer are recognised by position
 * (see {@link CasinoLayout}); sneak-right-click still places blocks as usual. Shootable as
 * {@code dev.screen {open:"blackjack"}}.
 */
public final class CasinoFeature {
	private static Blackjack game;

	private CasinoFeature() {
	}

	public static void init() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || player.isSecondaryUseActive()) {
				return InteractionResult.PASS;
			}
			BlockPos pos = hit.getBlockPos();
			if (!CasinoLayout.isTable(pos.getX(), pos.getY(), pos.getZ())) {
				return InteractionResult.PASS;
			}
			if (hand == InteractionHand.MAIN_HAND) {
				CasinoSession.sit();
			}
			return InteractionResult.FAIL;
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!level.isClientSide() || !isDealer(entity)) {
				return InteractionResult.PASS;
			}
			if (hand == InteractionHand.MAIN_HAND) {
				CasinoSession.sit();
			}
			return InteractionResult.FAIL;
		});
		CasinoSession.init();
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> Guard.run("casino.table", () -> TableView.render(ctx)));
		HudElementRegistry.addLast(AgentCraft.id("hud/blackjack"), GuardedHud.of("hud.blackjack", new SeatHud()));
		DevBridge.registerScreen("blackjack", mc -> new BlackjackScreen(game()));
	}

	private static boolean isDealer(Entity e) {
		return e.getType() == EntityTypes.VILLAGER && e.hasCustomName()
			&& CasinoLayout.DEALER_NAME.equals(e.getCustomName().getString()) && CasinoLayout.nearDealer(e.getX(), e.getY(), e.getZ());
	}

	/** The one game of this session: closing the screen mid-hand only pauses it. */
	static synchronized Blackjack game() {
		if (game == null) {
			BlackjackSave.Saved saved = BlackjackSave.load();
			game = new Blackjack(new Blackjack.Shoe(new java.util.Random()), saved.balance(), saved.stats());
		}
		return game;
	}

	/** The game if it has been started this session, else null (nothing is loaded just to look). */
	static synchronized Blackjack peek() {
		return game;
	}
}

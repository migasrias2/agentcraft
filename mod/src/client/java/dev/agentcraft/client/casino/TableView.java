package dev.agentcraft.client.casino;

import dev.agentcraft.hq.CasinoLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/**
 * The blackjack table as the player sees it in the world: cards lying on the felt, chips, hand totals,
 * the result line and the dealer's speech. It only draws the {@link Blackjack} state; everything that
 * moves is driven by timestamps taken when a change is first seen (cards fly from the shoe in deal order,
 * the hole card flips, chips drop and slide, old cards are swept to the tray), so the rules in
 * {@code Blackjack} know nothing about timing. See {@link WorldPaint} for the drawing primitives.
 */
final class TableView {
	private static final double FLIGHT = 0.35;
	private static final double STAGGER = 0.3;
	private static final double FLIP = 0.5;
	private static final double SWEEP = 0.5;
	private static final double CARD_W = 0.30;

	private static final double SURF = CasinoLayout.SURFACE_Y;
	private static final double CX = CasinoLayout.CENTER_X;
	private static final double DEALER_Z = -5.62;
	private static final double PLAYER_Z = -4.38;
	private static final double MID_Z = -5.0;
	private static final double SHOE_X = -30.5;
	private static final double SHOE_Z = -5.5;
	private static final double TRAY_X = -34.5;
	private static final double TRAY_Z = -5.5;
	private static final double RIM_Y = CasinoLayout.SURFACE_Y - 1.0 / 16;
	private static final int[] DENOMS = {100, 50, 10};
	private static final int[] CHIP_COLORS = {0xFF2B2A28, 0xFF2FA3A0, 0xFFD97757};

	private static final TableView INSTANCE = new TableView();
	/** What the HUD strip shows for the hands (the totals of the cards that have landed). */
	static volatile String hudYou = "";
	static volatile String hudDealer = "";

	private static final class Vis {
		String key;
		final Blackjack.Card card;
		final int hand;
		final int idx;
		final boolean hole;
		final double start;
		final float yawRest;
		final float spin;
		double x = SHOE_X;
		double z = SHOE_Z;
		double tx;
		double tz;
		double roll;
		double y = SURF;
		double flipAt = -1;
		boolean leaving;
		double leaveAt;

		Vis(String key, Blackjack.Card card, int hand, int idx, boolean hole, double start, int seed) {
			this.key = key;
			this.card = card;
			this.hand = hand;
			this.idx = idx;
			this.hole = hole;
			this.start = start;
			this.yawRest = (Math.floorMod(seed * 31 + idx * 7, 9) - 4) * 0.022f;
			this.spin = (Math.floorMod(seed * 17 + idx * 3, 5) - 2) * 0.5f;
		}

		boolean landed(double now) {
			return !leaving && now >= start + FLIGHT;
		}
	}

	private final Map<String, Vis> live = new LinkedHashMap<>();
	private final List<Vis> leaving = new ArrayList<>();
	private int lastRound = -1;
	private int lastHands;
	private double lastStart;
	private double lastFrame;

	private int resultRound = -1;
	private double resultAt = -1;
	private String resultText = "";
	private int resultNet;
	private boolean resultBlackjack;
	private int payout;
	private boolean particlesDone;

	private final List<List<Double>> chipTimes = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
	private final List<List<Double>> payoutTimes = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
	private boolean chipsGone;

	private Villager dealer;
	private double dealerYaw;
	private double nodAt = -10;
	private int dealerLookup;

	private TableView() {
	}

	/** Called every frame from the level render event. */
	static void render(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.player.distanceToSqr(CX, SURF, MID_Z) > 48 * 48) {
			return;
		}
		INSTANCE.draw(ctx, mc, CasinoFeature.game(), System.nanoTime() / 1e9);
	}

	// ------------------------------------------------------------------ easing

	private static double clamp01(double v) {
		return Math.max(0, Math.min(1, v));
	}

	private static double easeOutCubic(double t) {
		double u = 1 - t;
		return 1 - u * u * u;
	}

	private static double easeInCubic(double t) {
		return t * t * t;
	}

	private static double smooth(double t) {
		t = clamp01(t);
		return t * t * (3 - 2 * t);
	}

	private static double bounceOut(double t) {
		if (t < 1 / 2.75) {
			return 7.5625 * t * t;
		} else if (t < 2 / 2.75) {
			t -= 1.5 / 2.75;
			return 7.5625 * t * t + 0.75;
		} else if (t < 2.5 / 2.75) {
			t -= 2.25 / 2.75;
			return 7.5625 * t * t + 0.9375;
		}
		t -= 2.625 / 2.75;
		return 7.5625 * t * t + 0.984375;
	}

	// ------------------------------------------------------------------ state tracking

	private record Want(String key, Blackjack.Card card, int hand, int idx) {
	}

	private static String key(int hand, int idx) {
		return (hand < 0 ? "d" : "h" + hand + ":") + idx;
	}

	private void update(Blackjack g, double now, double dt) {
		int round = g.round();
		if (round != lastRound) {
			for (Vis v : live.values()) {
				sendAway(v, now);
			}
			live.clear();
			lastHands = 0;
			chipsGone = false;
			resultAt = -1;
			resultRound = -1;
			particlesDone = false;
			lastRound = round;
		}
		List<Blackjack.Hand> hands = g.hands();
		// a split moves the second card of the first hand to the new hand
		if (lastHands == 1 && hands.size() == 2) {
			Vis moved = live.remove(key(0, 1));
			if (moved != null) {
				moved.key = key(1, 0);
				live.put(moved.key, moved);
			}
		}
		lastHands = hands.size();
		// the wanted cards, in deal order (player, dealer, player, dealer)
		List<Want> wants = new ArrayList<>();
		List<Blackjack.Card> dealerCards = g.dealerCards();
		int max = Math.max(dealerCards.size(), hands.stream().mapToInt(h -> h.cards().size()).max().orElse(0));
		for (int i = 0; i < max; i++) {
			for (int h = 0; h < hands.size(); h++) {
				if (i < hands.get(h).cards().size()) {
					wants.add(new Want(key(h, i), hands.get(h).cards().get(i), h, i));
				}
			}
			if (i < dealerCards.size()) {
				wants.add(new Want(key(-1, i), dealerCards.get(i), -1, i));
			}
		}
		java.util.Set<String> wanted = new java.util.HashSet<>();
		for (Want w : wants) {
			wanted.add(w.key());
			if (!live.containsKey(w.key())) {
				double base = now + (leaving.isEmpty() ? 0 : 0.25);
				double start = Math.max(base, lastStart + STAGGER);
				lastStart = start;
				boolean hole = w.hand() < 0 && w.idx() == 1;
				live.put(w.key(), new Vis(w.key(), w.card(), w.hand(), w.idx(), hole, start, round));
			}
		}
		for (var it = live.values().iterator(); it.hasNext();) {
			Vis v = it.next();
			if (!wanted.contains(v.key)) {
				sendAway(v, now);
				it.remove();
			}
		}
		layout(hands.size(), dealerCards.size());
		Vis hole = live.get(key(-1, 1));
		if (hole != null && g.holeRevealed() && hole.flipAt < 0) {
			double landing = lastStart + FLIGHT;
			hole.flipAt = landing > now ? landing + 0.15 : now;
		}
		trackResult(g, now);
		syncChips(g, now);
		// dealer: nod when a card leaves the shoe
		for (Vis v : live.values()) {
			if (v.start <= now && v.start > nodAt) {
				nodAt = v.start;
			}
		}
	}

	private void sendAway(Vis v, double now) {
		v.leaving = true;
		v.leaveAt = now;
		leaving.add(v);
	}

	private void layout(int handCount, int dealerCount) {
		int[] counts = new int[2];
		for (Vis v : live.values()) {
			if (v.hand >= 0) {
				counts[Math.min(1, v.hand)] = Math.max(counts[Math.min(1, v.hand)], v.idx + 1);
			}
		}
		for (Vis v : live.values()) {
			int n = v.hand < 0 ? dealerCount : counts[Math.min(1, v.hand)];
			double cx = v.hand < 0 ? CX : handCount <= 1 ? CX : CX + (v.hand == 0 ? -0.75 : 0.75);
			double maxW = v.hand < 0 || handCount <= 1 ? 2.5 : 1.35;
			double step = n <= 1 ? 0 : Math.min(0.27, (maxW - CARD_W) / (n - 1));
			v.tx = cx + (v.idx - (n - 1) / 2.0) * step;
			v.tz = (v.hand < 0 ? DEALER_Z : PLAYER_Z) + (v.idx % 2 == 0 ? 0 : 0.012);
		}
	}

	private void trackResult(Blackjack g, double now) {
		if (g.phase() != Blackjack.Phase.DONE) {
			if (g.phase() == Blackjack.Phase.BETTING) {
				resultAt = -1;
				resultRound = -1;
				chipsGone = false;
			}
			return;
		}
		if (resultRound == g.round()) {
			return;
		}
		resultRound = g.round();
		double settled = lastStart + FLIGHT;
		Vis hole = live.get(key(-1, 1));
		if (hole != null && hole.flipAt >= 0) {
			settled = Math.max(settled, hole.flipAt + FLIP);
		}
		resultAt = Math.max(now, settled) + 0.2;
		resultText = g.banner();
		resultNet = g.lastNet();
		resultBlackjack = false;
		payout = 0;
		for (Blackjack.Hand h : g.hands()) {
			resultBlackjack |= h.outcome() == Blackjack.Outcome.BLACKJACK;
			payout += Math.max(0, h.returned() - h.bet());
		}
		particlesDone = false;
		fill(payoutTimes, payout, resultAt);
	}

	private static void fill(List<List<Double>> times, int value, double now) {
		int rem = value;
		for (int i = 0; i < DENOMS.length; i++) {
			int n = rem / DENOMS[i];
			rem -= n * DENOMS[i];
			n = Math.min(n, 10);
			List<Double> list = times.get(i);
			while (list.size() < n) {
				list.add(now);
			}
			while (list.size() > n) {
				list.remove(list.size() - 1);
			}
		}
	}

	private void syncChips(Blackjack g, double now) {
		int stake = 0;
		if (g.hands().isEmpty()) {
			stake = g.bet();
		}
		for (Blackjack.Hand h : g.hands()) {
			stake += h.bet();
		}
		if (chipsGone) {
			for (List<Double> l : chipTimes) {
				l.clear();
			}
			return;
		}
		fill(chipTimes, stake, now);
	}

	// ------------------------------------------------------------------ drawing

	private void draw(LevelRenderContext ctx, Minecraft mc, Blackjack g, double now) {
		double dt = lastFrame == 0 ? 0.016 : Math.min(0.1, now - lastFrame);
		lastFrame = now;
		update(g, now, dt);
		WorldPaint paint = new WorldPaint(ctx.poseStack(), ctx.submitNodeCollector(), ctx.levelState().cameraRenderState);
		paint.box(SHOE_X, RIM_Y + 0.002, SHOE_Z, 0.26, 0.15, 0.2, 0xFF1F1E1D, 0xFF3B2A20, 0xFF2A1E17);
		paint.box(SHOE_X, RIM_Y + 0.152, SHOE_Z - 0.04, 0.2, 0.012, 0.1, 0xFFF4EFE6, 0xFFF4EFE6, 0xFFCFC6B3);
		paint.box(TRAY_X, RIM_Y + 0.002, TRAY_Z, 0.26, 0.03, 0.36, 0xFF174A2F, 0xFF3B2A20, 0xFF2A1E17);
		drawCards(paint, g, now, dt);
		drawChips(paint, now);
		drawLabels(paint, g, now);
		drawResult(paint, now);
		dealerBehaviour(mc, now, dt, g, paint);
	}

	private void drawCards(WorldPaint paint, Blackjack g, double now, double dt) {
		double follow = 1 - Math.exp(-dt * 12);
		for (Vis v : live.values()) {
			if (now < v.start) {
				continue;
			}
			double p = (now - v.start) / FLIGHT;
			double lift = 0;
			float yaw = v.yawRest;
			double roll = v.hole ? Math.PI : 0;
			if (p < 1) {
				double e = easeOutCubic(p);
				v.x = SHOE_X + (v.tx - SHOE_X) * e;
				v.z = SHOE_Z + (v.tz - SHOE_Z) * e;
				lift = 0.22 * Math.sin(Math.PI * p);
				yaw = v.yawRest + (float) ((1 - e) * v.spin);
				roll = v.hole ? Math.PI : Math.PI * (1 - smooth((p - 0.1) / 0.7));
			} else {
				v.x += (v.tx - v.x) * follow;
				v.z += (v.tz - v.z) * follow;
				if (v.hole && v.flipAt >= 0 && now >= v.flipAt) {
					double q = (now - v.flipAt) / FLIP;
					roll = q >= 1 ? 0 : Math.PI * (1 - smooth(q));
					lift = 0.12 * Math.sin(Math.PI * clamp01(q));
				}
			}
			v.roll = roll;
			v.y = SURF + 0.006 + v.idx * 0.004 + lift + 0.15 * Math.abs(Math.sin(roll));
			paintCard(paint, v, v.x, v.y, v.z, yaw, roll, 1f);
		}
		for (var it = leaving.iterator(); it.hasNext();) {
			Vis v = it.next();
			double q = (now - v.leaveAt) / SWEEP;
			if (q >= 1) {
				it.remove();
				continue;
			}
			if (now < v.start) {
				continue;
			}
			double e = easeInCubic(clamp01(q));
			double x = v.x + (TRAY_X - v.x) * e;
			double z = v.z + (TRAY_Z - v.z) * e;
			double y = v.y + 0.08 * Math.sin(Math.PI * clamp01(q));
			paintCard(paint, v, x, y, z, v.yawRest + (float) (e * 0.6), v.roll, (float) (1 - 0.6 * e));
		}
	}

	private void paintCard(WorldPaint paint, Vis v, double x, double y, double z, float yaw, double roll, float scale) {
		boolean front = roll <= Math.PI / 2;
		float r = (float) (front ? roll : roll - Math.PI);
		paint.flat(x, y, z, yaw, r, WorldPaint.PIXEL * scale, () -> paint.card(v.card, front));
	}

	private void drawChips(WorldPaint paint, double now) {
		double resultT = resultAt < 0 ? -1 : now - resultAt;
		double zOff = 0;
		double hop = 0;
		boolean showBet = !chipsGone;
		boolean showPayout = false;
		double payZ = MID_Z;
		if (resultT >= 0) {
			if (resultNet < 0) {
				double q = clamp01((resultT - 0.5) / 0.6);
				zOff = -1.1 * easeInCubic(q);
				if (q >= 1) {
					chipsGone = true;
					showBet = false;
				}
			} else if (resultNet > 0) {
				showPayout = payout > 0;
				payZ = MID_Z - 1.1 * (1 - easeOutCubic(clamp01((resultT - 0.25) / 0.6)));
				double q = clamp01((resultT - 2.2) / 0.6);
				zOff = 1.4 * easeInCubic(q);
				payZ += 1.4 * easeInCubic(q);
				if (q >= 1) {
					chipsGone = true;
					showBet = false;
					showPayout = false;
				}
			} else {
				hop = 0.07 * Math.sin(Math.PI * clamp01((resultT - 0.2) / 0.35));
			}
		}
		if (showBet) {
			stacks(paint, chipTimes, CX - 0.2, MID_Z + zOff, now, hop);
		}
		if (showPayout) {
			stacks(paint, payoutTimes, CX + 0.45, payZ, now, 0);
		}
	}

	private void stacks(WorldPaint paint, List<List<Double>> times, double x0, double z, double now, double hop) {
		for (int i = 0; i < DENOMS.length; i++) {
			List<Double> list = times.get(i);
			for (int k = 0; k < list.size(); k++) {
				double age = now - list.get(k);
				double drop = age < 0 ? 0.5 : age < 0.5 ? 0.5 * (1 - bounceOut(age / 0.5)) : 0;
				if (age < 0) {
					continue;
				}
				paint.chip(x0 + i * 0.15, SURF + 0.011 + k * 0.022 + drop + hop, z, CHIP_COLORS[i], k == list.size() - 1);
			}
		}
	}

	private static String handLabel(List<Blackjack.Card> cards, boolean allowBlackjack) {
		int v = Blackjack.value(cards);
		if (allowBlackjack && cards.size() == 2 && v == 21) {
			return "Blackjack";
		}
		if (v > 21) {
			return "Bust " + v;
		}
		return (Blackjack.soft(cards) ? "Soft " : "") + v;
	}

	private void drawLabels(WorldPaint paint, Blackjack g, double now) {
		int handCount = g.hands().size();
		List<Blackjack.Card> dealerLanded = new ArrayList<>();
		List<List<Blackjack.Card>> handLanded = new ArrayList<>();
		for (int i = 0; i < Math.max(1, handCount); i++) {
			handLanded.add(new ArrayList<>());
		}
		List<Vis> sorted = new ArrayList<>(live.values());
		sorted.sort((a, b) -> Integer.compare(a.idx, b.idx));
		for (Vis v : sorted) {
			if (!v.landed(now)) {
				continue;
			}
			if (v.hand < 0) {
				if (!v.hole || (v.flipAt >= 0 && now >= v.flipAt + FLIP)) {
					dealerLanded.add(v.card);
				}
			} else if (v.hand < handLanded.size()) {
				handLanded.get(v.hand).add(v.card);
			}
		}
		String dealerText = dealerLanded.isEmpty() ? "" : handLabel(dealerLanded, true);
		StringBuilder you = new StringBuilder();
		for (int h = 0; h < handLanded.size(); h++) {
			List<Blackjack.Card> cards = handLanded.get(h);
			if (cards.isEmpty()) {
				continue;
			}
			String t = handLabel(cards, handCount <= 1);
			you.append(you.length() == 0 ? "" : " | ").append(t);
			double cx = handCount <= 1 ? CX : CX + (h == 0 ? -0.75 : 0.75);
			paint.label(t, cx, SURF + 0.05, PLAYER_Z + 0.42, 0.42f, 0xFFF4EFE6);
		}
		if (!dealerText.isEmpty()) {
			paint.label(dealerText, CX, SURF + 0.05, DEALER_Z - 0.34, 0.42f, 0xFFF4EFE6);
		}
		hudYou = you.toString();
		hudDealer = dealerText;
		if (g.bet() > 0 || !g.hands().isEmpty()) {
			int stake = g.hands().isEmpty() ? g.bet() : g.hands().stream().mapToInt(Blackjack.Hand::bet).sum();
			if (!chipsGone) {
				paint.label("Bet " + stake, CX - 0.2, SURF + 0.3, MID_Z, 0.36f, 0xFFE6C659);
			}
		}
		String says = g.says();
		if (!says.isEmpty()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && mc.player.distanceToSqr(CasinoLayout.DEALER_X + 0.5, 66, CasinoLayout.DEALER_Z + 0.5) < 100) {
				paint.label(says, CasinoLayout.DEALER_X + 0.5, 66 + 2.62, CasinoLayout.DEALER_Z + 0.5, 0.6f, 0xFFF4EFE6);
			}
		}
	}

	private void drawResult(WorldPaint paint, double now) {
		if (resultAt < 0 || resultText.isEmpty()) {
			return;
		}
		double t = now - resultAt;
		if (t < 0 || t > 2.9) {
			return;
		}
		if (!particlesDone) {
			particlesDone = true;
			burst();
		}
		double rise = easeOutCubic(clamp01(t / 1.2));
		double alpha = t < 0.15 ? t / 0.15 : t > 2.2 ? 1 - (t - 2.2) / 0.7 : 1;
		int a = (int) (clamp01(alpha) * 255);
		int base = resultNet > 0 ? 0xE6C659 : resultNet < 0 ? 0xF4A585 : 0xF4EFE6;
		paint.floating(resultText, CX, SURF + 0.28 + 0.45 * rise, PLAYER_Z, 0.8f, a << 24 | base);
	}

	private void burst() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || resultNet <= 0) {
			return;
		}
		java.util.Random rnd = new java.util.Random();
		for (int i = 0; i < (resultBlackjack ? 18 : 8); i++) {
			double x = CX + (rnd.nextDouble() - 0.5) * 1.2;
			double z = PLAYER_Z + (rnd.nextDouble() - 0.5) * 0.5;
			mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, x, SURF + 0.15 + rnd.nextDouble() * 0.35, z, 0, 0.02, 0);
			if (resultBlackjack && i % 3 == 0) {
				mc.level.addParticle(ParticleTypes.FIREWORK, x, SURF + 0.2, z, (rnd.nextDouble() - 0.5) * 0.04, 0.06, (rnd.nextDouble() - 0.5) * 0.04);
			}
		}
	}

	// ------------------------------------------------------------------ the dealer

	private void dealerBehaviour(Minecraft mc, double now, double dt, Blackjack g, WorldPaint paint) {
		if (dealer == null || dealer.isRemoved() || dealerLookup++ % 120 == 0) {
			AABB box = new AABB(CasinoLayout.DEALER_X - 1, 65, CasinoLayout.DEALER_Z - 1, CasinoLayout.DEALER_X + 2, 69, CasinoLayout.DEALER_Z + 2);
			List<Villager> found = mc.level.getEntitiesOfClass(Villager.class, box, v -> v.hasCustomName() && CasinoLayout.DEALER_NAME.equals(v.getCustomName().getString()));
			dealer = found.isEmpty() ? null : found.get(0);
		}
		if (dealer == null) {
			return;
		}
		double target = 0;
		double dx = mc.player.getX() - dealer.getX();
		double dz = mc.player.getZ() - dealer.getZ();
		if (dx * dx + dz * dz < 64) {
			target = Math.max(-65, Math.min(65, Math.toDegrees(Math.atan2(-dx, dz))));
		}
		dealerYaw += (target - dealerYaw) * (1 - Math.exp(-dt * 6));
		double nod = 0;
		double age = now - nodAt;
		if (age >= 0 && age < 0.3) {
			nod = 16 * Math.sin(Math.PI * age / 0.3);
		}
		float yaw = (float) dealerYaw;
		float pitch = (float) (nod + (g.phase() == Blackjack.Phase.PLAYER ? 4 : 0));
		dealer.setYBodyRot(0);
		dealer.yBodyRotO = 0;
		dealer.setYHeadRot(yaw);
		dealer.yHeadRotO = yaw;
		dealer.setXRot(pitch);
		dealer.xRotO = pitch;
	}
}

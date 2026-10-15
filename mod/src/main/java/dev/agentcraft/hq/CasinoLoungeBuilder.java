package dev.agentcraft.hq;

import static dev.agentcraft.hq.StudioHqBuilder.AIR;
import static dev.agentcraft.hq.StudioHqBuilder.CEIL_FULL;
import static dev.agentcraft.hq.StudioHqBuilder.CEIL_STAIRS;
import static dev.agentcraft.hq.StudioHqBuilder.DARK_PLANKS;
import static dev.agentcraft.hq.StudioHqBuilder.E;
import static dev.agentcraft.hq.StudioHqBuilder.FEET;
import static dev.agentcraft.hq.StudioHqBuilder.FLOOR;
import static dev.agentcraft.hq.StudioHqBuilder.FRIEZE;
import static dev.agentcraft.hq.StudioHqBuilder.GLASS_PANE;
import static dev.agentcraft.hq.StudioHqBuilder.HX;
import static dev.agentcraft.hq.StudioHqBuilder.HZN;
import static dev.agentcraft.hq.StudioHqBuilder.HZS;
import static dev.agentcraft.hq.StudioHqBuilder.LANTERN;
import static dev.agentcraft.hq.StudioHqBuilder.LANTERN_HANGING;
import static dev.agentcraft.hq.StudioHqBuilder.N;
import static dev.agentcraft.hq.StudioHqBuilder.PLASTER;
import static dev.agentcraft.hq.StudioHqBuilder.PLINTH;
import static dev.agentcraft.hq.StudioHqBuilder.RIDGE_Z;
import static dev.agentcraft.hq.StudioHqBuilder.ROOF_FULL;
import static dev.agentcraft.hq.StudioHqBuilder.ROOF_STAIRS;
import static dev.agentcraft.hq.StudioHqBuilder.ROOF_TRIM;
import static dev.agentcraft.hq.StudioHqBuilder.S;
import static dev.agentcraft.hq.StudioHqBuilder.SOFA;
import static dev.agentcraft.hq.StudioHqBuilder.TILE;
import static dev.agentcraft.hq.StudioHqBuilder.W;
import static dev.agentcraft.hq.StudioHqBuilder.WALL_TOP;
import static dev.agentcraft.hq.StudioHqBuilder.WALNUT;
import static dev.agentcraft.hq.StudioHqBuilder.WALNUT_TRIM;
import static dev.agentcraft.hq.StudioHqBuilder.beam;
import static dev.agentcraft.hq.StudioHqBuilder.post;
import static dev.agentcraft.hq.StudioHqBuilder.roofY;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The Casino Lounge: a warm, dim card room built onto the hall's west gable (the Screening Room's
 * counterpart), entered through a doorway at the hall's south-west corner (x -24, z 4..5, through the
 * library's archive wall).
 *
 * <pre>
 *   z=-10  north wall: back bar (walnut counter, bottles, shelves) behind the dealer
 *   z=-7   the dealer (a villager, no AI), facing south
 *   z=-6..-2  the blackjack table: a half-disc, green felt in a dark-oak rim, stools on the curved side
 *   z=3..5 a card nook (side table, two sofas) ... doorway at x=-24 (z 4..5)
 *   x=-41  west gable
 * </pre>
 *
 * Same material language and roof as the hall. The table and the dealer are found by position on the
 * client (see {@link CasinoLayout}); the game itself is client-only, purely for fun.
 */
final class CasinoLoungeBuilder {
	private static final int WX = CasinoLayout.WALL_X;
	/** First interior column next to the hall gable. */
	private static final int HALL_SIDE = -HX - 1;
	private static final int[] PILASTERS = {-28, -32, -36};
	private static final BlockState FELT = St.wool(DyeColor.GREEN);

	private CasinoLoungeBuilder() {
	}

	/** True for ground the room and its eaves cover, plus a margin (trees stay away). */
	static boolean nearRoom(int x, int z) {
		return x <= -HX + 1 && x >= WX - 3 && z >= HZN - 4 && z <= HZS + 4;
	}

	static void build(Plan p, Anchors.Builder a) {
		// clear whatever the landscape put here (flowers, hedge leaves, the ring path), the room is built on a clean slate
		p.fill(WX - 1, FLOOR, HZN - 1, HALL_SIDE, roofY(RIDGE_Z) + 3, HZS + 1, AIR);
		shell(p);
		roof(p);
		doorway(p);
		floor(p);
		table(p);
		backBar(p);
		cardNook(p);
		lighting(p);
		cameras(a);
	}

	// ------------------------------------------------------------------ shell

	private static void shell(Plan p) {
		for (int x = WX; x <= HALL_SIDE; x++) {
			boolean pil = x == WX || isPilaster(x);
			for (int z : new int[] {HZN, HZS}) {
				p.set(x, FLOOR, z, PLINTH);
				if (pil) {
					p.fill(x, FEET, z, x, WALL_TOP, z, post());
				} else {
					p.set(x, FEET, z, WALNUT_TRIM);
					p.fill(x, FEET + 1, z, x, FEET + 3, z, WALNUT);
					p.fill(x, FEET + 4, z, x, WALL_TOP, z, PLASTER);
					if (z == HZS && (x & 1) == 0) {
						// a few high slits on the south wall: a little daylight, a dim room
						p.set(x, FEET + 4, z, GLASS_PANE);
					}
				}
				p.set(x, FRIEZE, z, beam(Direction.Axis.X));
			}
		}
		// west gable: solid walnut and plaster, no view
		for (int z = HZN; z <= HZS; z++) {
			StudioHqBuilder.gableColumn(p, WX, z);
			if (z > HZN && z < HZS && z != RIDGE_Z && z != -6 && z != 2) {
				p.fill(WX, FEET + 3, z, WX, FEET + 3, z, WALNUT);
				p.fill(WX, FEET + 4, z, WX, WALL_TOP - 1, z, PLASTER);
			}
		}
		// tie beams on the pilasters, king posts (as in the hall)
		for (int x : PILASTERS) {
			p.fill(x, FRIEZE, HZN + 1, x, FRIEZE, HZS - 1, beam(Direction.Axis.Z));
			p.fill(x, FRIEZE + 1, RIDGE_Z, x, roofY(RIDGE_Z) - 2, RIDGE_Z, post());
			for (int i = 1; i <= 3; i++) {
				p.set(x, FRIEZE + 1 + (3 - i), RIDGE_Z - i, St.stairs(Blocks.DARK_OAK_STAIRS, S, true));
				p.set(x, FRIEZE + 1 + (3 - i), RIDGE_Z + i, St.stairs(Blocks.DARK_OAK_STAIRS, N, true));
			}
		}
	}

	private static boolean isPilaster(int x) {
		for (int px : PILASTERS) {
			if (px == x) {
				return true;
			}
		}
		return false;
	}

	private static void roof(Plan p) {
		for (int x = WX - 1; x <= HALL_SIDE; x++) {
			for (int z = HZN - 1; z <= HZS + 1; z++) {
				int y = roofY(z);
				if (z == RIDGE_Z) {
					p.set(x, y, z, ROOF_FULL);
					p.set(x, y + 1, z, St.slab(Blocks.DARK_OAK_SLAB, false));
				} else {
					boolean trim = x == WX - 1 || z == HZN - 1 || z == HZS + 1;
					p.set(x, y, z, St.stairs(trim ? ROOF_TRIM : ROOF_STAIRS, z < RIDGE_Z ? S : N, false));
				}
				if (x > WX && z > HZN && z < HZS) {
					p.set(x, y - 1, z, z == RIDGE_Z ? CEIL_FULL : St.stairs(CEIL_STAIRS, z < RIDGE_Z ? N : S, true));
				}
			}
		}
	}

	/**
	 * The doorway from the hall: the hall's west gable opened at z 4..5 (and the library's archive wall
	 * in front of it), a walnut lintel, a tiled threshold.
	 */
	private static void doorway(Plan p) {
		for (int z = 4; z <= 5; z++) {
			for (int x : new int[] {-HX, -HX + 1}) {
				p.fill(x, FEET, z, x, FEET + 2, z, AIR);
				p.set(x, FLOOR, z, TILE);
			}
			p.set(-HX, FEET + 3, z, beam(Direction.Axis.Z));
		}
		p.fill(-HX, FEET, 3, -HX, FEET + 2, 3, post());
	}

	private static void floor(Plan p) {
		for (int x = WX + 1; x < HALL_SIDE; x++) {
			for (int z = HZN + 1; z < HZS; z++) {
				p.set(x, FLOOR, z, DARK_PLANKS);
			}
		}
		// a red rug with a black border under the table and the stools
		BlockState red = St.carpet(DyeColor.RED);
		BlockState black = St.carpet(DyeColor.BLACK);
		for (int x = -39; x <= -27; x++) {
			for (int z = -8; z <= 1; z++) {
				boolean border = x == -39 || x == -27 || z == -8 || z == 1;
				p.set(x, FEET, z, border ? black : red);
			}
		}
		// tiled path from the door
		for (int x = HALL_SIDE; x >= -29; x--) {
			for (int z = 4; z <= 5; z++) {
				p.set(x, FLOOR, z, TILE);
			}
		}
	}

	// ------------------------------------------------------------------ the table

	/**
	 * A compact table: dark-oak slabs (top half) with a 3 x 2 green carpet felt, fence-post legs in the
	 * four corners. The cards are drawn on the felt by the client (see the blackjack renderer).
	 */
	private static void table(Plan p) {
		BlockState felt = St.carpet(DyeColor.GREEN);
		for (int x = CasinoLayout.TABLE_X0; x <= CasinoLayout.TABLE_X1; x++) {
			for (int z = CasinoLayout.TABLE_Z0; z <= CasinoLayout.TABLE_Z1; z++) {
				boolean corner = (x == CasinoLayout.TABLE_X0 || x == CasinoLayout.TABLE_X1) && (z == CasinoLayout.TABLE_Z0 || z == CasinoLayout.TABLE_Z1);
				if (corner) {
					p.set(x, FEET, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
					continue;
				}
				p.set(x, FEET, z, St.slab(Blocks.DARK_OAK_SLAB, true));
				if (CasinoLayout.feltCell(x, z)) {
					p.set(x, FEET + 1, z, felt);
				}
			}
		}
		// candles on the rim, either side of the dealer
		p.set(CasinoLayout.TABLE_X0, FEET + 1, CasinoLayout.TABLE_Z0 + 1, St.candle(Blocks.DYED_CANDLE.pick(DyeColor.RED), 2, true));
		// stools: a dark-oak post with a red cushion
		for (int[] s : CasinoLayout.STOOLS) {
			p.set(s[0], FEET, s[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
			p.set(s[0], FEET + 1, s[1], St.carpet(DyeColor.RED));
		}
	}

	// ------------------------------------------------------------------ the bar

	/** The back bar behind the dealer: walnut counter, bottles and candles, bookshelves above, lanterns. */
	private static void backBar(Plan p) {
		int zb = HZN + 1;
		for (int x = -39; x <= -27; x++) {
			p.set(x, FEET, zb, x % 4 == 0 ? WALNUT : WALNUT_TRIM);
			switch (Math.floorMod(x, 4)) {
				case 0 -> p.set(x, FEET + 1, zb, Blocks.BREWING_STAND.defaultBlockState());
				case 1 -> p.set(x, FEET + 1, zb, St.candle(Blocks.CANDLE, 3, true));
				case 2 -> p.set(x, FEET + 1, zb, St.of(Blocks.POTTED_FLOWERING_AZALEA));
				default -> p.set(x, FEET + 1, zb, Blocks.BREWING_STAND.defaultBlockState());
			}
			p.set(x, FEET + 2, zb, Blocks.CHISELED_BOOKSHELF.defaultBlockState()
				.setValue(HorizontalDirectionalBlock.FACING, S));
			p.set(x, FEET + 3, zb, Blocks.BOOKSHELF.defaultBlockState());
			p.set(x, FEET + 4, zb, St.slab(Blocks.DARK_OAK_SLAB, true));
		}
		for (int x : new int[] {-37, -33, -29}) {
			p.set(x, FEET + 1, zb, LANTERN);
		}
		StudioHqBuilder.floorLamp(p, -40, zb);
		StudioHqBuilder.floorLamp(p, -26, zb);
	}

	/** Seating either side of the table, a card nook in the south-west corner, a barrel and a lamp by the door. */
	private static void cardNook(Plan p) {
		for (int side : new int[] {-1, 1}) {
			int x = side < 0 ? -38 : -28;
			p.set(x, FEET, -6, St.stairs(SOFA, side < 0 ? E : W, false));
			p.set(x, FEET, -4, St.stairs(SOFA, side < 0 ? E : W, false));
			p.set(x, FEET, -5, St.slab(Blocks.DARK_OAK_SLAB, true));
			p.set(x, FEET + 1, -5, St.candle(Blocks.CANDLE, 2, true));
		}
		p.set(-38, FEET, 3, St.slab(Blocks.DARK_OAK_SLAB, true));
		p.set(-38, FEET + 1, 3, St.candle(Blocks.CANDLE, 2, true));
		p.set(-40, FEET, 3, St.stairs(SOFA, E, false));
		p.set(-36, FEET, 3, St.stairs(SOFA, W, false));
		p.set(-38, FEET, 5, St.stairs(SOFA, N, false));
		p.set(HALL_SIDE, FEET, 2, Blocks.BARREL.defaultBlockState());
		StudioHqBuilder.floorLamp(p, HALL_SIDE, 3);
		p.set(WX + 1, FEET, HZN + 1, St.of(Blocks.POTTED_FERN));
	}

	// ------------------------------------------------------------------ light

	private static void lighting(Plan p) {
		// three lanterns on chains over the table
		for (int x : new int[] {-34, -32}) {
			int z = -5;
			int ceiling = roofY(z) - 1;
			p.fill(x, FEET + 5, z, x, ceiling - 1, z, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
			p.set(x, FEET + 4, z, LANTERN_HANGING);
		}
		// hanging lanterns on the pilasters' tie beams
		for (int x : PILASTERS) {
			for (int z : new int[] {-6, 2}) {
				p.set(x, FRIEZE - 1, z, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
				p.set(x, FRIEZE - 2, z, LANTERN_HANGING);
			}
		}
		// dim fill light so it is cosy, not dark
		for (int x = WX + 3; x < HALL_SIDE; x += 4) {
			for (int z : new int[] {-7, -2, 3}) {
				p.setIfAir(x, FEET + 2, z, St.light(8));
			}
		}
	}

	private static void cameras(Anchors.Builder a) {
		double tx = CasinoLayout.CENTER_X;
		// from the door, over the stools, at the table and the dealer
		a.cameraLookAt("casino", -26.5, FEET + 2.3, 3.8, tx, FEET + 1.2, -5.0);
		// close, from a stool at the cards and the dealer
		a.cameraLookAt("casino_table", tx, FEET + 2.2, -2.2, tx, FEET + 1.2, -5.5);
	}

	// ------------------------------------------------------------------ the dealer

	/**
	 * Puts the dealer behind the table (server side, after the blocks are placed). A previous dealer
	 * (found by its tag) is removed first, so a rebuild never duplicates it.
	 */
	static void spawnDealer(ServerLevel level) {
		try {
			AABB zone = new AABB(WX, FLOOR, HZN, HALL_SIDE + 1, FLOOR + 12, HZS + 1);
			for (Villager old : level.getEntitiesOfClass(Villager.class, zone, v -> v.entityTags().contains(CasinoLayout.DEALER_TAG))) {
				old.discard();
			}
			Villager dealer = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			if (dealer == null) {
				AgentCraft.LOGGER.warn("Casino: could not create the dealer");
				return;
			}
			dealer.snapTo(CasinoLayout.DEALER_X + 0.5, FEET, CasinoLayout.DEALER_Z + 0.5, 0f, 0f);
			dealer.setYHeadRot(0f);
			dealer.setYBodyRot(0f);
			dealer.setNoAi(true);
			dealer.setSilent(true);
			dealer.setPermanentlyInvulnerable(true);
			dealer.setPersistenceRequired();
			dealer.setCustomName(Component.literal(CasinoLayout.DEALER_NAME));
			dealer.setCustomNameVisible(true);
			dealer.addTag(CasinoLayout.DEALER_TAG);
			var access = level.registryAccess();
			dealer.setVillagerData(dealer.getVillagerData().withProfession(access, VillagerProfession.CLERIC).withLevel(5));
			level.addFreshEntity(dealer);
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.warn("Casino: could not place the dealer", e);
		}
	}
}

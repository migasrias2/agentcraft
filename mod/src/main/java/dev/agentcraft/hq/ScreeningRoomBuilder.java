package dev.agentcraft.hq;

import static dev.agentcraft.hq.StudioHqBuilder.AIR;
import static dev.agentcraft.hq.StudioHqBuilder.CEIL_FULL;
import static dev.agentcraft.hq.StudioHqBuilder.CEIL_STAIRS;
import static dev.agentcraft.hq.StudioHqBuilder.DARK_PLANKS;
import static dev.agentcraft.hq.StudioHqBuilder.E;
import static dev.agentcraft.hq.StudioHqBuilder.FEET;
import static dev.agentcraft.hq.StudioHqBuilder.FLOOR;
import static dev.agentcraft.hq.StudioHqBuilder.FLOOR_WOOD;
import static dev.agentcraft.hq.StudioHqBuilder.FRIEZE;
import static dev.agentcraft.hq.StudioHqBuilder.GLASS_PANE;
import static dev.agentcraft.hq.StudioHqBuilder.GLOW_PANEL;
import static dev.agentcraft.hq.StudioHqBuilder.HZN;
import static dev.agentcraft.hq.StudioHqBuilder.HZS;
import static dev.agentcraft.hq.StudioHqBuilder.HX;
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

import dev.agentcraft.Cast;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Screening Room: a small cinema / control room built onto the hall's east gable, entered through
 * a doorway at the hall's south-east corner (x 24, z 4..5).
 *
 * <pre>
 *   z=-10  north wall: wall of agent screens, 3 x 2 monitors (one per cast agent, bound to the agent id)
 *   z=-9..5                               tiers of sofas facing east
 *   x=24 hall gable (door at z 4..5) ... x=28 step ... platform x 29..34 ... aisle steps x=35 ... x=40 BIG SCREEN
 *   z=6    south wall                                       (9 x 5 monitor bound to "media")
 * </pre>
 *
 * Same material language and roof as the hall (plaster, walnut, dark-oak frame, copper-tile roof,
 * pale-oak vault). Part of the {@link StudioHqBuilder} plan, so the room is rebuilt and kept with the
 * rest of the HQ.
 */
final class ScreeningRoomBuilder {
	/** East wall of the room (the hall's own east gable is x = 24). */
	static final int WX = 41;
	/** Binding of the big screen: the media gallery (see client MediaScreenDraw). */
	static final String MEDIA = "media";
	/** Last interior column in front of the east wall (the big screen). */
	private static final int SCREEN_X = WX - 1;
	private static final int[] PILASTERS = {28, 32, 36};

	private ScreeningRoomBuilder() {
	}

	/** True for ground the room and its eaves cover, plus a margin (trees stay away). */
	static boolean nearRoom(int x, int z) {
		return x >= HX - 1 && x <= WX + 3 && z >= HZN - 4 && z <= HZS + 4;
	}

	static void build(Plan p, Anchors.Builder a) {
		// clear whatever the landscape put here (flowers, hedge leaves), the room is built on a clean slate
		p.fill(HX + 1, FLOOR, HZN - 1, WX + 1, roofY(RIDGE_Z) + 3, HZS + 1, AIR);
		shell(p);
		roof(p);
		doorway(p);
		floor(p);
		bigScreen(p);
		agentWall(p);
		seating(p);
		lighting(p);
		furnishings(p);
		cameras(a);
	}

	private static BlockState monitor(Direction facing) {
		return ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, facing).setValue(MonitorBlock.LIT, true);
	}

	// ------------------------------------------------------------------ shell

	private static void shell(Plan p) {
		for (int x = HX + 1; x <= WX; x++) {
			boolean pil = x == WX || isPilaster(x);
			for (int z : new int[] {HZN, HZS}) {
				p.set(x, FLOOR, z, PLINTH);
				if (pil) {
					p.fill(x, FEET, z, x, WALL_TOP, z, post());
				} else {
					p.set(x, FEET, z, WALNUT_TRIM);
					p.fill(x, FEET + 1, z, x, WALL_TOP, z, PLASTER);
					if (z == HZS) {
						// two high slits per bay on the south wall: daylight, but no view that competes with the screens
						p.fill(x, FEET + 4, z, x, FEET + 4, z, GLASS_PANE);
					}
				}
				p.set(x, FRIEZE, z, beam(Direction.Axis.X));
			}
		}
		// east gable: solid behind the big screen
		for (int z = HZN; z <= HZS; z++) {
			StudioHqBuilder.gableColumn(p, WX, z);
			if (z > HZN && z < HZS && z != RIDGE_Z && z != -6 && z != 2) {
				p.fill(WX, FEET + 3, z, WX, WALL_TOP - 1, z, PLASTER);
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
			p.set(x, WALL_TOP, HZS - 1, St.stairs(Blocks.DARK_OAK_STAIRS, S, true));
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
		for (int x = HX + 1; x <= WX + 1; x++) {
			for (int z = HZN - 1; z <= HZS + 1; z++) {
				int y = roofY(z);
				if (z == RIDGE_Z) {
					p.set(x, y, z, ROOF_FULL);
					p.set(x, y + 1, z, St.slab(Blocks.DARK_OAK_SLAB, false));
				} else {
					boolean trim = x == WX + 1 || z == HZN - 1 || z == HZS + 1;
					p.set(x, y, z, St.stairs(trim ? ROOF_TRIM : ROOF_STAIRS, z < RIDGE_Z ? S : N, false));
				}
				if (x < WX && z > HZN && z < HZS) {
					p.set(x, y - 1, z, z == RIDGE_Z ? CEIL_FULL : St.stairs(CEIL_STAIRS, z < RIDGE_Z ? N : S, true));
				}
			}
		}
	}

	/** The doorway from the hall: the hall's east gable opened at z 4..5, a walnut lintel, a tiled threshold. */
	private static void doorway(Plan p) {
		for (int z = 4; z <= 5; z++) {
			p.fill(HX, FEET, z, HX, FEET + 2, z, AIR);
			p.set(HX, FEET + 3, z, beam(Direction.Axis.Z));
			p.set(HX, FLOOR, z, TILE);
		}
		p.fill(HX, FEET, 3, HX, FEET + 2, 3, post());
	}

	private static void floor(Plan p) {
		for (int x = HX + 1; x < WX; x++) {
			for (int z = HZN + 1; z < HZS; z++) {
				boolean border = x == WX - 1 || z == HZN + 1 || z == HZS - 1;
				p.set(x, FLOOR, z, border ? DARK_PLANKS : FLOOR_WOOD);
			}
		}
		// terracotta runner down the aisle (z -3..-1) and from the door
		for (int x = HX + 1; x < WX - 1; x++) {
			for (int z = -3; z <= -1; z++) {
				p.set(x, FLOOR, z, z == -2 ? TILE : DARK_PLANKS);
			}
		}
		for (int x = HX + 1; x <= 28; x++) {
			for (int z = 4; z <= 5; z++) {
				p.set(x, FLOOR, z, TILE);
			}
		}
	}

	// ------------------------------------------------------------------ screens

	/** 9 x 5 media screen on the east wall between walnut speaker towers, on a low stage. */
	private static void bigScreen(Plan p) {
		for (int z = -6; z <= 2; z++) {
			for (int y = FEET + 1; y <= FEET + 5; y++) {
				p.set(SCREEN_X, y, z, monitor(W));
				p.bind(SCREEN_X, y, z, MEDIA);
			}
			p.set(SCREEN_X, FEET, z, WALNUT_TRIM);
		}
		// speaker towers either side
		for (int z : new int[] {-8, -7, 3, 4}) {
			p.fill(SCREEN_X, FEET, z, SCREEN_X, FEET + 5, z, z == -8 || z == 4 ? WALNUT : WALNUT_TRIM);
		}
		// stage lip in front
		for (int z = -7; z <= 3; z++) {
			p.set(SCREEN_X - 1, FEET, z, St.slab(Blocks.DARK_OAK_SLAB, false));
		}
		StudioHqBuilder.floorLamp(p, SCREEN_X - 1, -8);
		StudioHqBuilder.floorLamp(p, SCREEN_X - 1, 4);
		// pelmet over the screen
		for (int z = -8; z <= 4; z++) {
			p.set(SCREEN_X, FEET + 6, z, St.slab(Blocks.DARK_OAK_SLAB, true));
		}
	}

	/** Wall of agent screens: one 3 x 2 monitor per cast agent on the north wall, walnut pilasters between. */
	private static void agentWall(Plan p) {
		List<String> ids = new ArrayList<>();
		for (String id : StudioHqBuilder.deskIds()) {
			if (Cast.ids().contains(id) && ids.size() < 6) {
				ids.add(id);
			}
		}
		int zw = HZN + 1;
		int[] colX = {29, 33, 37};
		// base ledge, pilasters and the band between the rows
		for (int x = HX + 3; x <= 39; x++) {
			p.set(x, FEET, zw, WALNUT_TRIM);
			p.set(x, FEET + 3, zw, WALNUT);
		}
		for (int x : new int[] {27, 31, 35, 39}) {
			p.fill(x, FEET + 1, zw, x, FEET + 5, zw, WALNUT);
		}
		for (int i = 0; i < ids.size(); i++) {
			int bx = colX[i % 3];
			int y0 = i < 3 ? FEET + 4 : FEET + 1;
			for (int x = bx - 1; x <= bx + 1; x++) {
				for (int y = y0; y <= y0 + 1; y++) {
					p.set(x, y, zw, monitor(S));
					p.bind(x, y, zw, ids.get(i));
				}
			}
		}
		// panelling where a cast slot is empty
		for (int i = ids.size(); i < 6; i++) {
			int bx = colX[i % 3];
			int y0 = i < 3 ? FEET + 4 : FEET + 1;
			p.fill(bx - 1, y0, zw, bx + 1, y0 + 1, zw, WALNUT);
		}
	}

	// ------------------------------------------------------------------ seating

	private static void seating(Plan p) {
		BlockState brown = St.carpet(DyeColor.BROWN);
		BlockState red = St.carpet(DyeColor.RED);
		// raised tier: platform x 29..34 (surface y 67), a slab riser along its front and back, steps at the aisle and the door
		for (int x = 29; x <= 34; x++) {
			for (int z = HZN + 2; z < HZS; z++) {
				p.set(x, FEET, z, x == 29 || x == 34 ? DARK_PLANKS : FLOOR_WOOD);
			}
		}
		for (int z = HZN + 2; z < HZS; z++) {
			boolean aisle = z >= -3 && z <= -1;
			boolean door = z >= 4;
			p.set(35, FEET, z, aisle ? St.stairs(Blocks.DARK_OAK_STAIRS, W, false) : St.slab(Blocks.DARK_OAK_SLAB, false));
			p.set(28, FEET, z, door ? St.stairs(Blocks.DARK_OAK_STAIRS, E, false) : St.slab(Blocks.DARK_OAK_SLAB, false));
		}
		// rows: A on the floor (x 36), B on the tier (x 32); two sofas each, a 3-wide aisle between them
		for (int[] row : new int[][] {{36, FEET}, {32, FEET + 1}}) {
			int x = row[0];
			int y = row[1];
			for (int z = -8; z <= 4; z++) {
				if (z >= -3 && z <= -1) {
					continue;
				}
				p.set(x, y, z, St.stairs(SOFA, W, false));
				p.set(x + 1, y, z, z % 2 == 0 ? red : brown);
				p.set(x + 2, y, z, brown);
			}
		}
		// a side table with a candle at the end of the front row
		p.set(36, FEET, HZS - 1, St.slab(Blocks.DARK_OAK_SLAB, true));
		p.set(36, FEET + 1, HZS - 1, St.candle(Blocks.CANDLE, 2, true));
	}

	// ------------------------------------------------------------------ light + dressing

	private static void lighting(Plan p) {
		for (int x : PILASTERS) {
			for (int z : new int[] {-5, 1}) {
				p.set(x, FRIEZE - 1, z, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
				p.set(x, FRIEZE - 2, z, LANTERN_HANGING);
			}
		}
		for (int x = HX + 2; x < WX; x += 4) {
			p.setIfAir(x, roofY(RIDGE_Z) - 1, RIDGE_Z, GLOW_PANEL);
			p.setIfAir(x, FRIEZE, HZN + 1, St.light(11));
			p.setIfAir(x, FRIEZE, HZS - 1, St.light(11));
		}
		for (int x = HX + 2; x < WX; x += 4) {
			for (int z : new int[] {-7, -2, 3}) {
				p.setIfAir(x, FEET + 2, z, St.light(9));
			}
		}
	}

	private static void furnishings(Plan p) {
		// a console with plants in the north-west corner, a floor lamp and a barrel by the door
		p.set(HX + 1, FEET, HZN + 1, St.slab(Blocks.DARK_OAK_SLAB, true));
		p.set(HX + 2, FEET, HZN + 1, St.slab(Blocks.DARK_OAK_SLAB, true));
		p.set(HX + 1, FEET + 1, HZN + 1, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		p.set(HX + 2, FEET + 1, HZN + 1, St.candle(Blocks.CANDLE, 3, true));
		StudioHqBuilder.floorLamp(p, HX + 1, 3);
		p.set(HX + 1, FEET, 2, Blocks.BARREL.defaultBlockState());
	}

	private static void cameras(Anchors.Builder a) {
		// from the back of the room, over the sofas, at the big screen
		a.cameraLookAt("screening", 27.5, FEET + 2.4, 3.8, 40.0, FEET + 3.0, -2.0);
		// close on the media screen
		a.cameraLookAt("media_screen", 33.0, FEET + 3.6, -2.0, 40.0, FEET + 3.4, -2.0);
		// the wall of agent screens
		a.cameraLookAt("agent_wall", 33.0, FEET + 2.6, 2.5, 33.0, FEET + 3.2, HZN + 1);
	}
}

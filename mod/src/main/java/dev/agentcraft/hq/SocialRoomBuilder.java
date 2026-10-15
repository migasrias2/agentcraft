package dev.agentcraft.hq;

import static dev.agentcraft.hq.StudioHqBuilder.AIR;
import static dev.agentcraft.hq.StudioHqBuilder.CEIL_FULL;
import static dev.agentcraft.hq.StudioHqBuilder.CEIL_STAIRS;
import static dev.agentcraft.hq.StudioHqBuilder.CHAIR_DX;
import static dev.agentcraft.hq.StudioHqBuilder.DARK_PLANKS;
import static dev.agentcraft.hq.StudioHqBuilder.E;
import static dev.agentcraft.hq.StudioHqBuilder.EAVE_Y;
import static dev.agentcraft.hq.StudioHqBuilder.FEET;
import static dev.agentcraft.hq.StudioHqBuilder.FLOOR;
import static dev.agentcraft.hq.StudioHqBuilder.FLOOR_WOOD;
import static dev.agentcraft.hq.StudioHqBuilder.FRIEZE;
import static dev.agentcraft.hq.StudioHqBuilder.GLASS_PANE;
import static dev.agentcraft.hq.StudioHqBuilder.GLOW_PANEL;
import static dev.agentcraft.hq.StudioHqBuilder.HZN;
import static dev.agentcraft.hq.StudioHqBuilder.LANTERN_HANGING;
import static dev.agentcraft.hq.StudioHqBuilder.PLASTER;
import static dev.agentcraft.hq.StudioHqBuilder.PLASTER_FRAME;
import static dev.agentcraft.hq.StudioHqBuilder.PLINTH;
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

import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Social Room: where Nova (plans posts) and Sable (publishes them) work, with the wall calendar the
 * user approves posts on. Built onto the hall's north wall, entered through a doorway in the middle of the
 * hall's north window (x -1..0, z -10..-11), so from the hall's window seat you look straight into it.
 *
 * <pre>
 *   z=-10  hall north wall (window seat in front)       door x -1..0
 *   z=-11  Social Room south wall: a glass wall to the hall, plaster sides
 *   z=-12..-23 interior, x -8..8:   couch + rug (you), coffee table, plants, banners on the side walls
 *   z=-23  north wall: Nova's desk (x -8..-6) | CALENDAR 9 x 5 monitor (x -4..4, bound "calendar") | Sable's desk (x 6..8)
 *   z=-24  north wall, z=-25 verge; side walls x = -9 / 9, ridge along z at x 0
 * </pre>
 *
 * Banners: X-black on the east wall, LinkedIn-blue on the west wall. Same material language and copper
 * roof as the hall; the roof is a gable running north-south whose south end butts into the hall's roof.
 */
final class SocialRoomBuilder {
	/** Binding of the wall calendar (see the client's CalendarScreenDraw). */
	static final String CALENDAR = "calendar";
	/** The two social agents, west to east: Nova (planner), Sable (publisher). */
	static final String[] DESK_IDS = {"nova", "sable"};
	/** Desk bay centres (x) of {@link #DESK_IDS}. */
	private static final int[] DESK_X = {-7, 7};
	/** Side walls at x = +-WX, roof eaves at x = +-EAVE_X. */
	private static final int WX = 9;
	private static final int EAVE_X = 10;
	/** South wall (shared with the hall's north side) and north wall. */
	private static final int ZS = HZN - 1;
	private static final int ZN = -24;
	/** Row of the desks and the calendar (in front of the north wall). */
	private static final int ZD = ZN + 1;
	/** Calendar panel: x -4..4, y FEET+1..FEET+5. */
	private static final int CAL_HALF = 4;

	private SocialRoomBuilder() {
	}

	/** True for ground the room and its eaves cover, plus a margin (trees stay away). */
	static boolean nearRoom(int x, int z) {
		return Math.abs(x) <= EAVE_X + 4 && z >= ZN - 6 && z <= ZS + 1;
	}

	/** Northernmost walkable row (for the agents' path-finding bounds). */
	static int minInteriorZ() {
		return ZN + 1;
	}

	/** Roof height at column x (eaves at +-EAVE_X, ridge at 0). */
	static int roofY(int x) {
		return EAVE_Y + EAVE_X - Math.abs(x);
	}

	static void build(Plan p, Anchors.Builder a) {
		// clear whatever the landscape put here (flowers, hedge leaves, the ring path): a clean slate
		p.fill(-EAVE_X, FLOOR, ZN - 1, EAVE_X, roofY(0) + 3, ZS - 1, AIR);
		p.fill(-EAVE_X, FLOOR, ZS, EAVE_X, WALL_TOP, ZS, AIR);
		shell(p);
		gables(p);
		roof(p);
		doorway(p);
		floor(p);
		calendarWall(p);
		desks(p, a);
		banners(p);
		seating(p);
		dressing(p);
		lighting(p);
		cameras(a);
	}

	private static BlockState monitor(Direction facing) {
		return ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, facing).setValue(MonitorBlock.LIT, true);
	}

	// ------------------------------------------------------------------ shell

	private static boolean sidePost(int z) {
		return z == ZS || z == ZN || z == -14 || z == -20;
	}

	private static void shell(Plan p) {
		// side walls: posts at the corners and bay ends, high windows between (the banners hang in front of one bay)
		for (int z = ZS; z >= ZN; z--) {
			for (int sx : new int[] {-WX, WX}) {
				p.set(sx, FLOOR, z, PLINTH);
				if (sidePost(z)) {
					p.fill(sx, FEET, z, sx, WALL_TOP, z, post());
				} else {
					p.set(sx, FEET, z, WALNUT_TRIM);
					p.fill(sx, FEET + 1, z, sx, FEET + 3, z, PLASTER);
					p.fill(sx, FEET + 4, z, sx, WALL_TOP, z, GLASS_PANE);
				}
				p.set(sx, FRIEZE, z, beam(Direction.Axis.Z));
			}
		}
		for (int x = -WX + 1; x <= WX - 1; x++) {
			boolean pil = Math.abs(x) == 5;
			// south wall: a glass wall to the hall in the middle, plaster and high windows at the sides
			p.set(x, FLOOR, ZS, PLINTH);
			if (pil) {
				p.fill(x, FEET, ZS, x, WALL_TOP, ZS, post());
			} else if (Math.abs(x) <= 3) {
				p.set(x, FEET, ZS, WALNUT_TRIM);
				p.fill(x, FEET + 1, ZS, x, WALL_TOP, ZS, GLASS_PANE);
			} else if (Math.abs(x) == 4) {
				p.set(x, FEET, ZS, WALNUT_TRIM);
				p.fill(x, FEET + 1, ZS, x, WALL_TOP, ZS, PLASTER);
			} else {
				p.set(x, FEET, ZS, WALNUT_TRIM);
				p.fill(x, FEET + 1, ZS, x, FEET + 3, ZS, PLASTER);
				p.fill(x, FEET + 4, ZS, x, WALL_TOP, ZS, GLASS_PANE);
			}
			p.set(x, FRIEZE, ZS, beam(Direction.Axis.X));
			// north wall: solid, the screens stand in front of it
			p.set(x, FLOOR, ZN, PLINTH);
			if (pil) {
				p.fill(x, FEET, ZN, x, WALL_TOP, ZN, post());
			} else {
				p.set(x, FEET, ZN, WALNUT_TRIM);
				p.fill(x, FEET + 1, ZN, x, WALL_TOP, ZN, PLASTER);
			}
			p.set(x, FRIEZE, ZN, beam(Direction.Axis.X));
		}
		// tie beams on the bay posts, king posts and struts (as in the hall)
		for (int z : new int[] {-14, -20}) {
			p.fill(-WX + 1, FRIEZE, z, WX - 1, FRIEZE, z, beam(Direction.Axis.X));
			p.fill(0, FRIEZE + 1, z, 0, roofY(0) - 2, z, post());
			for (int i = 1; i <= 3; i++) {
				p.set(-i, FRIEZE + 1 + (3 - i), z, St.stairs(Blocks.DARK_OAK_STAIRS, E, true));
				p.set(i, FRIEZE + 1 + (3 - i), z, St.stairs(Blocks.DARK_OAK_STAIRS, W, true));
			}
		}
	}

	/** The two gable walls (south against the hall's roof, north with the round window) up to under the roof. */
	private static void gables(Plan p) {
		for (int z : new int[] {ZS, ZN}) {
			for (int x = -WX; x <= WX; x++) {
				int top = roofY(x) - 1;
				for (int y = FRIEZE + 1; y <= top; y++) {
					p.set(x, y, z, PLASTER);
				}
				if (z != ZN) {
					continue;
				}
				double cy = FRIEZE + 4.5;
				for (int y = FRIEZE + 2; y <= top; y++) {
					double r = Math.sqrt((double) x * x + (y - cy) * (y - cy));
					if (r < 2.4) {
						p.set(x, y, z, GLASS_PANE);
					} else if (r < 3.2) {
						p.set(x, y, z, DARK_PLANKS);
					} else if (x == 0) {
						p.set(x, y, z, post());
					}
				}
			}
		}
	}

	private static void roof(Plan p) {
		for (int x = -EAVE_X; x <= EAVE_X; x++) {
			int y = roofY(x);
			for (int z = ZN - 1; z <= ZS; z++) {
				if (x == 0) {
					p.set(x, y, z, ROOF_FULL);
					p.set(x, y + 1, z, St.slab(Blocks.DARK_OAK_SLAB, false));
				} else {
					boolean trim = Math.abs(x) == EAVE_X || z == ZN - 1 || z == ZS;
					p.set(x, y, z, St.stairs(trim ? ROOF_TRIM : ROOF_STAIRS, x < 0 ? E : W, false));
				}
				if (Math.abs(x) < WX && z > ZN && z < ZS) {
					p.set(x, y - 1, z, x == 0 ? CEIL_FULL : St.stairs(CEIL_STAIRS, x < 0 ? W : E, true));
				}
			}
		}
	}

	/** The doorway through the hall's north wall and ours (x -1..0): jambs, a walnut lintel, a tiled threshold, the window seat opened. */
	private static void doorway(Plan p) {
		for (int z : new int[] {HZN, ZS}) {
			for (int x : new int[] {-1, 0}) {
				p.fill(x, FEET, z, x, FEET + 2, z, AIR);
				p.set(x, FEET + 3, z, beam(Direction.Axis.Z));
				p.set(x, FLOOR, z, TILE);
			}
			for (int x : new int[] {-2, 1}) {
				p.fill(x, FEET, z, x, WALL_TOP, z, post());
			}
		}
		for (int x : new int[] {-1, 0}) {
			// the hall's window seat makes way for the door
			p.set(x, FEET, HZN + 1, AIR);
			p.set(x, FLOOR, HZN + 1, TILE);
		}
	}

	private static void floor(Plan p) {
		for (int x = -WX + 1; x < WX; x++) {
			for (int z = ZN + 1; z < ZS; z++) {
				boolean border = Math.abs(x) == WX - 1 || z == ZN + 1 || z == ZS - 1;
				p.set(x, FLOOR, z, border ? DARK_PLANKS : FLOOR_WOOD);
			}
		}
		// tiled path from the door to the couch
		for (int z = ZS - 1; z >= -13; z--) {
			for (int x : new int[] {-1, 0}) {
				p.set(x, FLOOR, z, TILE);
			}
		}
		// rug under the couch: a black border (X) around a white field with a blue band (LinkedIn)
		BlockState black = St.carpet(DyeColor.BLACK);
		BlockState white = St.carpet(DyeColor.WHITE);
		BlockState blue = St.carpet(DyeColor.BLUE);
		for (int x = -4; x <= 4; x++) {
			for (int z = -19; z <= -14; z++) {
				boolean border = Math.abs(x) == 4 || z == -19 || z == -14;
				boolean band = !border && (Math.abs(x) == 3 || z == -18 || z == -15);
				p.set(x, FEET, z, border ? black : band ? blue : white);
			}
		}
	}

	// ------------------------------------------------------------------ screens

	/** The wall calendar: a 9 x 5 monitor bound to "calendar", walnut pilasters either side, a pelmet above. */
	private static void calendarWall(Plan p) {
		for (int x = -CAL_HALF; x <= CAL_HALF; x++) {
			p.set(x, FEET, ZD, WALNUT_TRIM);
			for (int y = FEET + 1; y <= FEET + 5; y++) {
				p.set(x, y, ZD, monitor(S));
				p.bind(x, y, ZD, CALENDAR);
			}
			p.set(x, FEET + 6, ZD, St.slab(Blocks.DARK_OAK_SLAB, true));
		}
		for (int sx : new int[] {-CAL_HALF - 1, CAL_HALF + 1}) {
			p.fill(sx, FEET, ZD, sx, FEET + 5, ZD, WALNUT);
			p.set(sx, FEET + 6, ZD, St.slab(Blocks.DARK_OAK_SLAB, true));
		}
	}

	/** Nova's and Sable's desks against the north wall, one on each side of the calendar. */
	private static void desks(Plan p, Anchors.Builder a) {
		BlockState desk = St.slab(Blocks.DARK_OAK_SLAB, true);
		for (int i = 0; i < DESK_IDS.length; i++) {
			int bx = DESK_X[i];
			String id = DESK_IDS[i];
			for (int x = bx - 1; x <= bx + 1; x++) {
				p.set(x, FEET, ZD, desk);
				for (int y = FEET + 1; y <= FEET + 2; y++) {
					p.set(x, y, ZD, monitor(S));
					p.bind(x, y, ZD, id);
				}
			}
			// status lamp set into the wall above the monitor
			p.set(bx, FEET + 3, ZN, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
			p.bind(bx, FEET + 3, ZN, "agent:" + id);
			p.set(bx - 1, FEET + 3, ZN, PLASTER_FRAME);
			p.set(bx + 1, FEET + 3, ZN, PLASTER_FRAME);
			// the chair is the desk spot (the agent sits on the seat block at desk_<id>); its right-hand cell stays free
			int cx = bx + CHAIR_DX;
			p.set(cx, FEET, ZD + 1, St.stairs(Blocks.DARK_OAK_STAIRS, S, false));
			a.put(AnchorNames.desk(id), cx + 0.5, FEET, ZD + 1.5, 180, 0);
			a.put("seat_" + id, cx + 0.5, FEET, ZD + 1.5, 180, 0);
			a.put(AnchorNames.monitor(id), bx + 0.5, FEET + 2.0, ZD + 0.25 + 0.002, 0, 0);
			// over the agent's shoulder, from the calendar side
			double camX = bx < 0 ? bx + 2.0 : bx - 3.5;
			a.cameraLookAt("desk_" + id, camX, FEET + 2.2, ZD + 3.8, bx + (bx < 0 ? 0.2 : 0.8), FEET + 1.8, ZD + 0.25);
		}
	}

	// ------------------------------------------------------------------ banners

	private static final String[] GLYPH_X = {"#...#", ".#.#.", "..#..", ".#.#.", "#...#"};
	private static final String[] GLYPH_IN = {"#....", ".....", "#.###", "#.#.#", "#.#.#"};

	/** Two 5 x 5 wool banners on the side walls (z -19..-15): X in white on black (east), "in" in white on blue (west). */
	private static void banners(Plan p) {
		banner(p, WX - 1, GLYPH_X, DyeColor.BLACK, true);
		banner(p, -WX + 1, GLYPH_IN, DyeColor.BLUE, false);
	}

	private static void banner(Plan p, int x, String[] glyph, DyeColor field, boolean east) {
		BlockState bg = St.wool(field);
		BlockState fg = St.wool(DyeColor.WHITE);
		for (int row = 0; row < 5; row++) {
			for (int col = 0; col < 5; col++) {
				// seen from the room: the east wall reads north -> south, the west wall south -> north
				int z = east ? -19 + col : -15 - col;
				p.set(x, FEET + 5 - row, z, glyph[row].charAt(col) == '#' ? fg : bg);
			}
		}
		// a walnut rail on top and a candle shelf under the banner
		for (int z = -19; z <= -15; z++) {
			p.set(x, FEET + 6, z, St.slab(Blocks.DARK_OAK_SLAB, true));
			p.set(x, FEET, z, z == -17 ? St.slab(Blocks.DARK_OAK_SLAB, true) : WALNUT_TRIM);
		}
	}

	// ------------------------------------------------------------------ seating + dressing

	private static void seating(Plan p) {
		// the user's couch, facing the calendar (its back to the hall), with side tables
		for (int x = -2; x <= 2; x++) {
			p.set(x, FEET, -15, St.stairs(SOFA, S, false));
		}
		for (int sx : new int[] {-3, 3}) {
			p.set(sx, FEET, -15, St.slab(Blocks.DARK_OAK_SLAB, true));
			p.set(sx, FEET + 1, -15, St.candle(Blocks.CANDLE, 2, true));
		}
		// coffee table
		for (int x = -1; x <= 1; x++) {
			p.set(x, FEET, -17, St.slab(Blocks.DARK_OAK_SLAB, true));
		}
		p.set(0, FEET + 1, -17, St.candle(Blocks.CANDLE, 3, true));
		StudioHqBuilder.floorLamp(p, -4, -15);
		StudioHqBuilder.floorLamp(p, 4, -15);
	}

	private static void dressing(Plan p) {
		for (int sx : new int[] {-WX + 1, WX - 1}) {
			Direction face = sx < 0 ? E : W;
			// the entrance bay: a shelf and a plant on each side
			p.set(sx, FEET, -13, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, face));
			p.set(sx, FEET + 1, -13, St.of(Blocks.POTTED_FLOWERING_AZALEA));
			p.set(sx, FEET, -12, Blocks.BARREL.defaultBlockState());
			p.set(sx, FEET + 1, -12, St.of(Blocks.POTTED_FERN));
			// the bay beside the desks: a shelf with a lantern
			p.set(sx, FEET, -21, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, face));
			p.set(sx, FEET + 1, -21, StudioHqBuilder.LANTERN);
		}
		// planters under the glass wall to the hall
		for (int sx : new int[] {-6, 6}) {
			p.set(sx, FEET, ZS + 1, Blocks.MOSS_BLOCK.defaultBlockState());
			p.set(sx, FEET + 1, ZS + 1, sx < 0 ? Blocks.FLOWERING_AZALEA.defaultBlockState() : Blocks.AZALEA.defaultBlockState());
		}
		// a moss and fern corner at each end of the calendar wall, behind the pilasters
		for (int sx : new int[] {-WX + 1, WX - 1}) {
			p.set(sx, FEET, ZD + 2, St.of(Blocks.POTTED_FERN));
		}
	}

	// ------------------------------------------------------------------ light

	private static void lighting(Plan p) {
		for (int z : new int[] {-14, -20}) {
			for (int x : new int[] {-3, 3}) {
				p.set(x, FRIEZE - 1, z, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
				p.set(x, FRIEZE - 2, z, LANTERN_HANGING);
			}
		}
		for (int z : new int[] {-13, -17, -21}) {
			p.set(0, roofY(0) - 1, z, GLOW_PANEL);
		}
		for (int x : new int[] {-6, -2, 2, 6}) {
			for (int z : new int[] {-13, -17, -21}) {
				p.setIfAir(x, FEET + 2, z, St.light(9));
			}
		}
	}

	private static void cameras(Anchors.Builder a) {
		// from the corner by the door, over the couch at the calendar and both desks
		a.cameraLookAt("social", WX - 1.6, FEET + 3.0, ZS - 0.8, -1.0, FEET + 2.4, ZN + 1.0);
		// close on the calendar, from the couch
		a.cameraLookAt("social_calendar", 0.5, FEET + 3.4, -16.0, 0.5, FEET + 3.4, ZD);
	}
}

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
 * The Content Room: where Iris (showrunner), Reel (producer), Vega (analyst) and Cleo (publisher) run the
 * YouTube channels, with the analytics board on the east wall and a lectern that opens the Content Studio.
 * Built onto the hall's north wall east of the Social Room (the Gallery's mirror image), entered through a
 * 3-wide doorway in the hall's north wall (x 20..22, z -10..-11). Same material language and copper roof as
 * the Gallery and the Social Room; see {@link ContentLayout} for the floor plan.
 */
final class ContentRoomBuilder {
	private static final int CX = ContentLayout.CX;
	private static final int WX0 = CX - ContentLayout.HALF_W;
	private static final int WX1 = CX + ContentLayout.HALF_W;
	private static final int EAVE_X = ContentLayout.EAVE;
	private static final int ZS = ContentLayout.ZS;
	private static final int ZN = ContentLayout.ZN;
	/** Row of the desks (in front of the north wall). */
	private static final int ZD = ZN + 1;
	/** Desk bay centres (x) of {@link ContentLayout#DESK_IDS}. */
	private static final int[] DESK_X = {CX - 6, CX - 2, CX + 2, CX + 6};
	/** The analytics board: a 9 x 5 monitor in front of the east wall, z -22..-14, y FEET+1..FEET+5. */
	private static final int BOARD_X = WX1 - 1;
	private static final int BOARD_Z0 = -22;
	private static final int BOARD_Z1 = -14;

	private ContentRoomBuilder() {
	}

	/** True for ground the room and its eaves cover, plus a margin (trees stay away). */
	static boolean nearRoom(int x, int z) {
		return x >= CX - EAVE_X - 4 && x <= CX + EAVE_X + 4 && z >= ZN - 6 && z <= ZS + 1;
	}

	/** Roof height at column x (eaves at CX +- EAVE, ridge at CX). */
	private static int roofY(int x) {
		return EAVE_Y + EAVE_X - Math.abs(x - CX);
	}

	static void build(Plan p, Anchors.Builder a) {
		// clean slate: the landscape's flowers, hedges and path ring are cleared
		p.fill(CX - EAVE_X, FLOOR, ZN - 1, CX + EAVE_X, roofY(CX) + 3, ZS - 1, AIR);
		p.fill(CX - EAVE_X, FLOOR, ZS, CX + EAVE_X, WALL_TOP, ZS, AIR);
		shell(p);
		gables(p);
		roof(p);
		doorway(p);
		floor(p);
		board(p);
		desks(p, a);
		furniture(p);
		banner(p);
		lighting(p);
		cameras(a);
	}

	private static BlockState monitor(Direction facing) {
		return ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, facing).setValue(MonitorBlock.LIT, true);
	}

	// ------------------------------------------------------------------ shell

	private static boolean sidePost(int z) {
		return z == ZS || z == ZN;
	}

	private static void wallBody(Plan p, int x, int z) {
		p.set(x, FLOOR, z, PLINTH);
		p.set(x, FEET, z, WALNUT_TRIM);
		p.set(x, FEET + 1, z, WALNUT);
		p.fill(x, FEET + 2, z, x, WALL_TOP, z, PLASTER);
	}

	private static void shell(Plan p) {
		for (int z = ZS; z >= ZN; z--) {
			for (int sx : new int[] {WX0, WX1}) {
				if (sidePost(z)) {
					p.set(sx, FLOOR, z, PLINTH);
					p.fill(sx, FEET, z, sx, WALL_TOP, z, post());
				} else {
					wallBody(p, sx, z);
				}
				p.set(sx, FRIEZE, z, beam(Direction.Axis.Z));
			}
		}
		for (int x = WX0 + 1; x <= WX1 - 1; x++) {
			for (int z : new int[] {ZS, ZN}) {
				wallBody(p, x, z);
				p.set(x, FRIEZE, z, beam(Direction.Axis.X));
			}
		}
		// tie beams over the room, with the king posts and braces as in the Gallery
		for (int z : new int[] {-15, -19}) {
			p.fill(WX0 + 1, FRIEZE, z, WX1 - 1, FRIEZE, z, beam(Direction.Axis.X));
			p.fill(CX, FRIEZE + 1, z, CX, roofY(CX) - 2, z, post());
			for (int i = 1; i <= 3; i++) {
				p.set(CX - i, FRIEZE + 1 + (3 - i), z, St.stairs(Blocks.DARK_OAK_STAIRS, E, true));
				p.set(CX + i, FRIEZE + 1 + (3 - i), z, St.stairs(Blocks.DARK_OAK_STAIRS, W, true));
			}
		}
	}

	/** The two gable walls up to under the roof; the north one with a round window. */
	private static void gables(Plan p) {
		for (int z : new int[] {ZS, ZN}) {
			for (int x = WX0; x <= WX1; x++) {
				int top = roofY(x) - 1;
				for (int y = FRIEZE + 1; y <= top; y++) {
					p.set(x, y, z, PLASTER);
				}
				if (z != ZN) {
					continue;
				}
				double cy = FRIEZE + 4.5;
				for (int y = FRIEZE + 2; y <= top; y++) {
					double r = Math.sqrt((double) (x - CX) * (x - CX) + (y - cy) * (y - cy));
					if (r < 2.4) {
						p.set(x, y, z, GLASS_PANE);
					} else if (r < 3.2) {
						p.set(x, y, z, DARK_PLANKS);
					} else if (x == CX) {
						p.set(x, y, z, post());
					}
				}
			}
		}
	}

	private static void roof(Plan p) {
		for (int x = CX - EAVE_X; x <= CX + EAVE_X; x++) {
			int y = roofY(x);
			for (int z = ZN - 1; z <= ZS; z++) {
				if (x == CX) {
					p.set(x, y, z, ROOF_FULL);
					p.set(x, y + 1, z, St.slab(Blocks.DARK_OAK_SLAB, false));
				} else {
					boolean trim = Math.abs(x - CX) == EAVE_X || z == ZN - 1 || z == ZS;
					p.set(x, y, z, St.stairs(trim ? ROOF_TRIM : ROOF_STAIRS, x < CX ? E : W, false));
				}
				if (Math.abs(x - CX) < ContentLayout.HALF_W && z > ZN && z < ZS) {
					p.set(x, y - 1, z, x == CX ? CEIL_FULL : St.stairs(CEIL_STAIRS, x < CX ? W : E, true));
				}
			}
		}
	}

	/** A 3-wide doorway through the hall's north wall and ours: jambs, a walnut lintel, a tiled threshold. */
	private static void doorway(Plan p) {
		for (int z : new int[] {HZN, ZS}) {
			for (int x = ContentLayout.DOOR_X0; x <= ContentLayout.DOOR_X1; x++) {
				p.fill(x, FEET, z, x, FEET + 2, z, AIR);
				p.set(x, FEET + 3, z, beam(Direction.Axis.X));
				p.set(x, FLOOR, z, TILE);
			}
			for (int x : new int[] {ContentLayout.DOOR_X0 - 1, ContentLayout.DOOR_X1 + 1}) {
				p.fill(x, FEET, z, x, WALL_TOP, z, post());
			}
		}
		// anything the hall put just inside its north wall (window seat) makes way for the threshold
		for (int x = ContentLayout.DOOR_X0; x <= ContentLayout.DOOR_X1; x++) {
			p.set(x, FEET, HZN + 1, AIR);
			p.set(x, FLOOR, HZN + 1, TILE);
		}
	}

	private static void floor(Plan p) {
		for (int x = WX0 + 1; x < WX1; x++) {
			for (int z = ZN + 1; z < ZS; z++) {
				boolean border = Math.abs(x - CX) == ContentLayout.HALF_W - 1 || z == ZN + 1 || z == ZS - 1;
				p.set(x, FLOOR, z, border ? DARK_PLANKS : FLOOR_WOOD);
			}
		}
		// tiled aisle from the door to the lectern
		for (int z = ZS - 1; z >= ContentLayout.LECTERN_Z; z--) {
			for (int x = ContentLayout.DOOR_X0; x <= ContentLayout.DOOR_X1; x++) {
				p.set(x, FLOOR, z, TILE);
			}
		}
		// a rug in front of the board: dark field with a red band
		BlockState black = St.carpet(DyeColor.BLACK);
		BlockState red = St.carpet(DyeColor.RED);
		for (int x = BOARD_X - 6; x <= BOARD_X - 2; x++) {
			for (int z = BOARD_Z0 + 1; z <= BOARD_Z1 - 1; z++) {
				boolean border = x == BOARD_X - 6 || x == BOARD_X - 2 || z == BOARD_Z0 + 1 || z == BOARD_Z1 - 1;
				p.set(x, FEET, z, border ? red : black);
			}
		}
	}

	// ------------------------------------------------------------------ screens + desks

	/** The analytics board: a 9 x 5 monitor on the east wall bound to "content", walnut pilasters at both ends. */
	private static void board(Plan p) {
		for (int z = BOARD_Z0; z <= BOARD_Z1; z++) {
			p.set(BOARD_X, FEET, z, WALNUT_TRIM);
			for (int y = FEET + 1; y <= FEET + 5; y++) {
				p.set(BOARD_X, y, z, monitor(W));
				p.bind(BOARD_X, y, z, ContentLayout.BOARD);
			}
		}
		for (int z : new int[] {BOARD_Z0 - 1, BOARD_Z1 + 1}) {
			p.fill(BOARD_X, FEET, z, BOARD_X, FEET + 5, z, WALNUT);
		}
	}

	/** Iris', Reel's, Vega's and Cleo's desks against the north wall, with a shelf between each pair. */
	private static void desks(Plan p, Anchors.Builder a) {
		BlockState desk = St.slab(Blocks.DARK_OAK_SLAB, true);
		for (int i = 0; i < DESK_X.length; i++) {
			int bx = DESK_X[i];
			String id = ContentLayout.DESK_IDS[i];
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
			// over the agent's right shoulder, past the next desk
			a.cameraLookAt("desk_" + id, bx + 2.0, FEET + 2.2, ZD + 3.8, bx + 0.2, FEET + 1.8, ZD + 0.25);
		}
		// walnut shelves between the bays (a lantern on top), a plant at the west end
		for (int x : new int[] {CX - 4, CX, CX + 4}) {
			p.set(x, FEET, ZD, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, S));
			p.set(x, FEET + 1, ZD, StudioHqBuilder.LANTERN);
		}
		p.set(WX0 + 1, FEET, ZD, St.of(Blocks.POTTED_FERN));
	}

	// ------------------------------------------------------------------ furniture + dressing

	private static void furniture(Plan p) {
		// the lectern: right-click opens the Content Studio (the client listens for a click on this cell)
		p.set(ContentLayout.LECTERN_X, FEET, ContentLayout.LECTERN_Z,
			Blocks.LECTERN.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, E));
		// sofas facing the board, with side tables
		for (int z = -20; z <= -16; z++) {
			p.set(BOARD_X - 3, FEET, z, St.stairs(SOFA, W, false));
		}
		for (int z : new int[] {-21, -15}) {
			p.set(BOARD_X - 3, FEET, z, St.slab(Blocks.DARK_OAK_SLAB, true));
			p.set(BOARD_X - 3, FEET + 1, z, St.candle(Blocks.CANDLE, 2, true));
		}
		StudioHqBuilder.floorLamp(p, WX0 + 1, -14);
		StudioHqBuilder.floorLamp(p, WX0 + 1, -22);
		p.set(WX0 + 1, FEET, ZS - 1, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		p.set(WX1 - 1, FEET, ZS - 1, St.of(Blocks.POTTED_FERN));
	}

	private static final String[] PLAY = {"..#....", "..###..", "..#####", "..###..", "..#...."};

	/** A 7 x 5 red wool play button on the west wall (z -21..-15), a candle shelf under it. */
	private static void banner(Plan p) {
		BlockState bg = St.wool(DyeColor.RED);
		BlockState fg = St.wool(DyeColor.WHITE);
		for (int row = 0; row < 5; row++) {
			for (int col = 0; col < 7; col++) {
				// seen from the room the west wall reads south -> north
				p.set(WX0 + 1, FEET + 5 - row, -15 - col, PLAY[row].charAt(col) == '#' ? fg : bg);
			}
		}
		for (int z = -21; z <= -15; z++) {
			p.set(WX0 + 1, FEET, z, z == -18 ? St.slab(Blocks.DARK_OAK_SLAB, true) : WALNUT_TRIM);
		}
		p.set(WX0 + 1, FEET + 1, -18, St.candle(Blocks.CANDLE, 3, true));
	}

	// ------------------------------------------------------------------ light

	private static void lighting(Plan p) {
		for (int z : new int[] {-15, -19}) {
			for (int x : new int[] {CX - 3, CX + 3}) {
				p.set(x, FRIEZE - 1, z, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
				p.set(x, FRIEZE - 2, z, LANTERN_HANGING);
			}
		}
		for (int z : new int[] {-13, -17, -21}) {
			p.set(CX, roofY(CX) - 1, z, GLOW_PANEL);
		}
		for (int x : new int[] {CX - 6, CX - 2, CX + 2}) {
			for (int z : new int[] {-13, -17, -21}) {
				p.setIfAir(x, FEET + 2, z, St.light(10));
			}
		}
	}

	private static void cameras(Anchors.Builder a) {
		// from the door corner across the room to the board and the desks
		a.cameraLookAt("content", CX - 6.5, FEET + 3.0, ZS - 0.8, CX + 3.0, FEET + 2.4, ZN + 3.0);
		// close on the analytics board, from the sofas
		a.cameraLookAt("content_board", BOARD_X - 7.0, FEET + 3.3, -18.0, BOARD_X + 0.5, FEET + 3.3, -18.0);
	}
}

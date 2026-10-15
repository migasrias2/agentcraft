package dev.agentcraft.hq;

import static dev.agentcraft.hq.StudioHqBuilder.AIR;
import static dev.agentcraft.hq.StudioHqBuilder.CEIL_FULL;
import static dev.agentcraft.hq.StudioHqBuilder.CEIL_STAIRS;
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

import dev.agentcraft.layout.Anchors;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Gallery: a quiet hall of framed paintings built onto the hall's north wall, west of the Social
 * Room, entered through a 3-wide doorway in the hall's north wall (x -22..-20, z -10..-11). Walls are
 * plain plaster above a walnut wainscot so the paintings (drawn by the client from real images, see
 * {@link GalleryLayout}) carry the room; a lectern in the middle turns the page when there are more
 * images than bays. Same material language and copper roof as the Social Room, whose N-S gable it copies.
 */
final class GalleryRoomBuilder {
	private static final int CX = GalleryLayout.CX;
	private static final int WX0 = CX - GalleryLayout.HALF_W;
	private static final int WX1 = CX + GalleryLayout.HALF_W;
	private static final int EAVE_X = GalleryLayout.EAVE;
	private static final int ZS = GalleryLayout.ZS;
	private static final int ZN = GalleryLayout.ZN;

	private GalleryRoomBuilder() {
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
		furniture(p);
		lighting(p);
		cameras(a);
	}

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
		// side walls: solid, posts at the corners and between the bays
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
		// south wall (against the hall) and north wall: solid between corner posts
		for (int x = WX0 + 1; x <= WX1 - 1; x++) {
			for (int z : new int[] {ZS, ZN}) {
				wallBody(p, x, z);
				p.set(x, FRIEZE, z, beam(Direction.Axis.X));
			}
		}
		// tie beams over the room, with the king posts and braces as in the Social Room
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
				if (Math.abs(x - CX) < GalleryLayout.HALF_W && z > ZN && z < ZS) {
					p.set(x, y - 1, z, x == CX ? CEIL_FULL : St.stairs(CEIL_STAIRS, x < CX ? W : E, true));
				}
			}
		}
	}

	/** A 3-wide doorway through the hall's north wall and ours: jambs, a walnut lintel, a tiled threshold. */
	private static void doorway(Plan p) {
		for (int z : new int[] {HZN, ZS}) {
			for (int x = GalleryLayout.DOOR_X0; x <= GalleryLayout.DOOR_X1; x++) {
				p.fill(x, FEET, z, x, FEET + 2, z, AIR);
				p.set(x, FEET + 3, z, beam(Direction.Axis.X));
				p.set(x, FLOOR, z, TILE);
			}
			for (int x : new int[] {GalleryLayout.DOOR_X0 - 1, GalleryLayout.DOOR_X1 + 1}) {
				p.fill(x, FEET, z, x, WALL_TOP, z, post());
			}
		}
		// anything the hall put just inside its north wall (window seat) makes way for the threshold
		for (int x = GalleryLayout.DOOR_X0; x <= GalleryLayout.DOOR_X1; x++) {
			p.set(x, FEET, HZN + 1, AIR);
			p.set(x, FLOOR, HZN + 1, TILE);
		}
	}

	private static void floor(Plan p) {
		for (int x = WX0 + 1; x < WX1; x++) {
			for (int z = ZN + 1; z < ZS; z++) {
				boolean border = Math.abs(x - CX) == GalleryLayout.HALF_W - 1 || z == ZN + 1 || z == ZS - 1;
				p.set(x, FLOOR, z, border ? DARK_PLANKS : FLOOR_WOOD);
			}
		}
		// tiled aisle from the door up to the lectern, and a red runner on it
		BlockState runner = St.carpet(DyeColor.RED);
		for (int z = ZS - 1; z >= ZN + 3; z--) {
			for (int x = GalleryLayout.DOOR_X0; x <= GalleryLayout.DOOR_X1; x++) {
				p.set(x, FLOOR, z, TILE);
			}
			p.set(CX, FEET, z, z == GalleryLayout.LECTERN_Z ? AIR : runner);
		}
	}

	private static void furniture(Plan p) {
		// the lectern turns the page (the client listens for a click on this cell)
		p.set(GalleryLayout.LECTERN_X, FEET, GalleryLayout.LECTERN_Z, Blocks.LECTERN.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, S));
		// two benches in the middle of the room, facing the side walls
		for (int z = -18; z <= -16; z++) {
			p.set(CX - 3, FEET, z, St.stairs(SOFA, W, false));
			p.set(CX + 3, FEET, z, St.stairs(SOFA, E, false));
		}
		// a bench under the north wall's middle painting, and potted plants in the corners
		for (int x = CX - 1; x <= CX + 1; x++) {
			p.set(x, FEET, ZN + 3, St.slab(Blocks.DARK_OAK_SLAB, true));
		}
		for (int sx : new int[] {WX0 + 1, WX1 - 1}) {
			p.set(sx, FEET, ZN + 1, St.of(Blocks.POTTED_FERN));
			p.set(sx, FEET, ZS - 1, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		}
		StudioHqBuilder.floorLamp(p, CX - 6, GalleryLayout.LECTERN_Z);
		StudioHqBuilder.floorLamp(p, CX + 6, GalleryLayout.LECTERN_Z);
	}

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
		for (int x : new int[] {CX - 6, CX - 2, CX + 2, CX + 6}) {
			for (int z : new int[] {-13, -17, -21}) {
				p.setIfAir(x, FEET + 2, z, St.light(10));
			}
		}
	}

	private static void cameras(Anchors.Builder a) {
		// from the door, down the aisle to the middle painting
		a.cameraLookAt("gallery", CX + 0.5, FEET + 2.4, ZS - 0.8, CX + 0.5, FEET + 3.0, ZN + 1.0);
		// from the east side, across the room to the west wall
		a.cameraLookAt("gallery_wall", GalleryLayout.centreX() + 5.5, FEET + 2.2, GalleryLayout.centreZ() + 4.0, CX - GalleryLayout.HALF_W + 1.0, FEET + 3.0,
			GalleryLayout.centreZ() - 1.5);
	}
}

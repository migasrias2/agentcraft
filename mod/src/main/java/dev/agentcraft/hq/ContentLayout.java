package dev.agentcraft.hq;

/**
 * Where things are in the Content Room (north-east annex of the HQ hall, the Gallery's mirror image), shared
 * by the server-side {@link ContentRoomBuilder} and the client (lectern click, wall board).
 *
 * <pre>
 *   z=-11   south wall: doorway x 20..22 (to the hall)
 *   z=-12..-23 interior, x 13..29:  lectern in the aisle (opens the Content Studio), sofas facing the board
 *   z=-23   north wall: four desks (Iris, Reel, Vega, Cleo) in bays x 14..16 / 18..20 / 22..24 / 26..28
 *   x=29    east wall: the analytics board, a 9 x 5 monitor (z -22..-14) bound to "content"
 *   x=13    west wall: a red play-button banner
 * </pre>
 */
public final class ContentLayout {
	/** Cell column of the ridge (centre line of the room). */
	public static final int CX = 21;
	/** Side walls at x = CX +- HALF_W, roof eaves at CX +- EAVE. */
	public static final int HALF_W = 9;
	public static final int EAVE = 10;
	/** South wall (next to the hall's north wall) and north wall. */
	public static final int ZS = StudioHqBuilder.HZN - 1;
	public static final int ZN = -24;
	/** Doorway cells (3 wide) in the south wall. */
	public static final int DOOR_X0 = CX - 1;
	public static final int DOOR_X1 = CX + 1;
	/** Lectern cell (opens the Content Studio screen). */
	public static final int LECTERN_X = CX;
	public static final int LECTERN_Z = -18;
	/** Standing height on the room's floor. */
	public static final int FLOOR_Y = StudioHqBuilder.FEET;
	/** Monitor binding of the wall analytics board. */
	public static final String BOARD = "content";
	/** The four agents, west to east: Iris (showrunner), Reel (producer), Vega (analyst), Cleo (publisher). */
	public static final String[] DESK_IDS = {"iris", "reel", "vega", "cleo"};

	private ContentLayout() {
	}

	/** True for the lectern's block position, whatever its state. */
	public static boolean isLectern(int x, int y, int z) {
		return x == LECTERN_X && z == LECTERN_Z && y == FLOOR_Y;
	}
}

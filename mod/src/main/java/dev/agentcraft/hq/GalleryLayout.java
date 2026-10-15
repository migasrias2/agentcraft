package dev.agentcraft.hq;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;

/**
 * Where things are in the Gallery (north-west annex of the HQ hall), shared by the server-side
 * {@link GalleryRoomBuilder} and the client's painting renderer and click handling. The paintings
 * themselves are drawn by the client from real images, so a bay is only a position on a wall: no
 * block or entity is registered for it.
 *
 * <pre>
 *   z=-11   south wall: doorway x -22..-20 (to the hall), two bays beside it
 *   z=-12..-23 interior, x -29..-13:  lectern + benches in the middle
 *   z=-24   north wall: three big bays;  west wall x=-30 and east wall x=-12: three bays each
 * </pre>
 */
public final class GalleryLayout {
	/** Cell column of the ridge (centre line of the room). */
	public static final int CX = -21;
	/** Side walls at x = CX +- HALF_W, roof eaves at CX +- EAVE. */
	public static final int HALF_W = 9;
	public static final int EAVE = 10;
	/** South wall (next to the hall's north wall) and north wall. */
	public static final int ZS = StudioHqBuilder.HZN - 1;
	public static final int ZN = -24;
	/** Doorway cells (3 wide) in the south wall, and the cell column of the door's centre. */
	public static final int DOOR_X0 = CX - 1;
	public static final int DOOR_X1 = CX + 1;
	/** Lectern cell (page turner). */
	public static final int LECTERN_X = CX;
	public static final int LECTERN_Z = -17;
	/** Standing height on the gallery floor. */
	public static final int FLOOR_Y = StudioHqBuilder.FEET;
	/** Height of the middle of every bay (the eye line). */
	public static final double EYE_Y = StudioHqBuilder.FEET + 3.1;
	/** Wall bays reach this far out of the paintings' own size, for clicking. */
	private static final double PAD_W = 0.5;
	private static final double PAD_BELOW = 1.0;
	private static final double PAD_ABOVE = 0.4;

	/**
	 * One place for a painting. {@code (x, y, z)} is the centre of the bay on the wall plane, {@code facing}
	 * points into the room (the way the painting looks), {@code maxW x maxH} is the largest painting
	 * (blocks) the bay holds; the nameplate hangs under it.
	 */
	public record Slot(int index, Direction facing, double x, double y, double z, double maxW, double maxH) {
		/** Offset of {@code (hx, hz)} along the bay's width (positive = to the viewer's right). */
		public double along(double hx, double hz) {
			return switch (facing) {
				case SOUTH -> hx - x;
				case NORTH -> x - hx;
				case EAST -> z - hz;
				default -> hz - z;
			};
		}

		/** True when a point on the wall (a ray hit) lies inside this bay. */
		public boolean contains(double hx, double hy, double hz) {
			double depth = switch (facing.getAxis()) {
				case X -> Math.abs(hx - x);
				default -> Math.abs(hz - z);
			};
			if (depth > 0.6) {
				return false;
			}
			return Math.abs(along(hx, hz)) <= maxW / 2 + PAD_W && hy >= y - maxH / 2 - PAD_BELOW && hy <= y + maxH / 2 + PAD_ABOVE;
		}
	}

	/** Bays in the order newest-first paintings fill them: the middle of the north wall, then around the room. */
	public static final List<Slot> SLOTS = slots();
	public static final int PAGE_SIZE = SLOTS.size();

	private GalleryLayout() {
	}

	private static List<Slot> slots() {
		List<Slot> s = new ArrayList<>();
		double north = ZN + 1.0;
		double west = CX - HALF_W + 1.0;
		double east = CX + HALF_W;
		double south = ZS;
		double mid = CX + 0.5;
		s.add(slot(s, Direction.SOUTH, mid, north, 4.4, 2.7));
		s.add(slot(s, Direction.SOUTH, mid - 5.6, north, 4.4, 2.7));
		s.add(slot(s, Direction.SOUTH, mid + 5.6, north, 4.4, 2.7));
		for (double z : new double[] {-17, -13, -21}) {
			s.add(slot(s, Direction.EAST, west, z, 3.3, 2.4));
		}
		for (double z : new double[] {-17, -13, -21}) {
			s.add(slot(s, Direction.WEST, east, z, 3.3, 2.4));
		}
		s.add(slot(s, Direction.NORTH, mid - 5.5, south, 3.3, 2.4));
		s.add(slot(s, Direction.NORTH, mid + 5.5, south, 3.3, 2.4));
		return List.copyOf(s);
	}

	private static Slot slot(List<Slot> so, Direction facing, double along, double plane, double w, double h) {
		boolean xAxis = facing.getAxis() == Direction.Axis.Z;
		return new Slot(so.size(), facing, xAxis ? along : plane, EYE_Y, xAxis ? plane : along, w, h);
	}

	/** True for the lectern's block position (the page turner), whatever its state. */
	public static boolean isLectern(int x, int y, int z) {
		return x == LECTERN_X && z == LECTERN_Z && y == FLOOR_Y;
	}

	/** The bay a hit point on a wall belongs to, or null. */
	public static Slot slotAt(double hx, double hy, double hz) {
		for (Slot s : SLOTS) {
			if (s.contains(hx, hy, hz)) {
				return s;
			}
		}
		return null;
	}

	/** Centre of the room (for distance checks). */
	public static double centreX() {
		return CX + 0.5;
	}

	public static double centreZ() {
		return (ZN + ZS) / 2.0;
	}
}

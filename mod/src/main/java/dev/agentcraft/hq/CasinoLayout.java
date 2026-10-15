package dev.agentcraft.hq;

import java.util.List;

/**
 * Where things are in the Casino Lounge (west wing of the HQ hall), shared by the server-side builder
 * and the client's blackjack interaction (which finds the table and the dealer by position only, so no
 * block or entity needs to be registered for it).
 */
public final class CasinoLayout {
	/** West wall of the lounge (the hall's own west gable is x = -24). */
	public static final int WALL_X = -41;
	/** Name of the dealer (custom name, shown above its head) and its entity tag. */
	public static final String DEALER_NAME = "Dealer";
	public static final String DEALER_TAG = "agentcraft_dealer";
	/** Block cell the dealer stands in, behind the straight edge of the table. */
	public static final int DEALER_X = -33;
	public static final int DEALER_Z = -7;
	/** The table: a rim of dark-oak slabs around a 3 x 2 felt (felt cells x -34..-32, z -6..-5); the dealer edge is to the north. */
	static final int TABLE_X0 = -35;
	static final int TABLE_X1 = -31;
	static final int TABLE_Z0 = -6;
	static final int TABLE_Z1 = -4;
	static final int FELT_X0 = -34;
	static final int FELT_X1 = -32;
	static final int FELT_Z0 = -6;
	static final int FELT_Z1 = -5;
	/** Felt rectangle in world coordinates (x0, z0, x1, z1) and the height of its surface, for the card renderer. */
	public static final double[] FELT = {FELT_X0, FELT_Z0, FELT_X1 + 1, FELT_Z1 + 1};
	public static final double SURFACE_Y = StudioHqBuilder.FEET + 1 + 1.0 / 16;
	/** Centre of the felt. */
	public static final double CENTER_X = FELT_X0 + 1.5;
	/** Player-side stools (x, z) in front of the table. */
	static final List<int[]> STOOLS = List.of(new int[] {-34, -3}, new int[] {-33, -3}, new int[] {-32, -3});

	private CasinoLayout() {
	}

	static boolean tableCell(int x, int z) {
		return x >= TABLE_X0 && x <= TABLE_X1 && z >= TABLE_Z0 && z <= TABLE_Z1;
	}

	static boolean feltCell(int x, int z) {
		return x >= FELT_X0 && x <= FELT_X1 && z >= FELT_Z0 && z <= FELT_Z1;
	}

	/** True when a block position belongs to the blackjack table (rim, felt and the air just above them). */
	public static boolean isTable(int x, int y, int z) {
		return y >= StudioHqBuilder.FEET && y <= StudioHqBuilder.FEET + 2 && tableCell(x, z);
	}

	/** True when an entity at this position is close enough to the dealer's spot to be the dealer. */
	public static boolean nearDealer(double x, double y, double z) {
		return Math.abs(x - (DEALER_X + 0.5)) < 2.5 && Math.abs(z - (DEALER_Z + 0.5)) < 2.5 && Math.abs(y - StudioHqBuilder.FEET) < 2.5;
	}
}

package dev.agentcraft.client.agents;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps one {@link ClientAgentEntity} per Foreman agent in the client level, in sync with the
 * state model and the anchor layout (client thread, every client tick):
 * <ul>
 *   <li>new agent: spawned standing at its target anchor (no walk-in from nowhere);</li>
 *   <li>station/active change: walks there along a {@link GridPathfinder} route (teleports if
 *       there is no route, e.g. the HQ was rebuilt around it);</li>
 *   <li>agent gone after a snapshot: removed;</li>
 *   <li>Foreman link down: agents stay where they are with a dimmed "Foreman offline" plate;</li>
 *   <li>layout republished ({@code /agentcraft hq}): everyone is placed at their new anchors.</li>
 * </ul>
 * Without a layout, agents stand in a row near the world spawn so they are still visible.
 *
 * <p>Phase 3: a station anchor with a seat block ({@link Seats}) is walked to via a free cell next
 * to the seat, then the agent steps in and sits; leaving a seat starts with standing up. An agent
 * that is {@code waiting_user} walks to the user spot by the podium, or, when you are inside the
 * HQ (not spectating), to a free spot about two blocks from you and waits there facing you
 * ({@link #userSpot}). The derived "waiting on you" status ({@link AgentView#awaitingUser}) comes
 * from the open decisions.
 */
public final class AgentManager {
	private static final AgentManager INSTANCE = new AgentManager();
	/** Teleport instead of walking when the route is longer than this (blocks). */
	private static final double MAX_WALK = 96;
	/** Ticks it takes to get up from a seat before walking off. */
	private static final int STAND_UP_TICKS = 8;
	/** A waiting agent re-approaches you once you moved this far from where it chose its spot (blocks). */
	private static final double FOLLOW_SLACK = 2.6;
	/** How far from you a waiting agent stands (blocks). */
	static final double USER_DISTANCE = 3.2;

	private final Map<String, ClientAgentEntity> entities = new LinkedHashMap<>();
	private final Map<Integer, ClientAgentEntity> byEntityId = new HashMap<>();
	private final StationAssigner assigner = new StationAssigner();
	private final Seats seats = new Seats();
	private final Map<String, UserSpot> userSpots = new HashMap<>();
	private final Map<String, String> awaiting = new HashMap<>();
	private final Map<String, Integer> awaitingCounts = new HashMap<>();
	private long awaitingRevision = -1;
	private @Nullable ClientLevel level;
	private long layoutRevision = -1;
	private int nextEntityId = -10_000;
	private int pathFailures;
	private long ticks;

	/** Where a waiting agent stands near the player, and where the player was when it was chosen. */
	private record UserSpot(Anchor spot, Vec3 playerAt) {
	}

	private AgentManager() {
	}

	public static AgentManager get() {
		return INSTANCE;
	}

	/** Live agent entities by agent id (client thread). */
	public Map<String, ClientAgentEntity> entities() {
		return Collections.unmodifiableMap(entities);
	}

	public @Nullable ClientAgentEntity entity(String agentId) {
		return entities.get(agentId);
	}

	public @Nullable ClientAgentEntity byEntityId(int id) {
		return byEntityId.get(id);
	}

	public int pathFailures() {
		return pathFailures;
	}

	/**
	 * A snapshot rebuilds the view: forget sticky slots so the assignment depends only on the state
	 * (Foreman order), not on the history of this session. Agents that change slot walk there.
	 */
	void onSnapshot() {
		assigner.clear();
		awaitingRevision = -1;
	}

	public int movingCount() {
		int n = 0;
		for (ClientAgentEntity e : entities.values()) {
			if (e.motion().walking()) {
				n++;
			}
		}
		return n;
	}

	/**
	 * The tick number agents stamp their advance with ({@link TickGate}). The entity ticks of a client tick
	 * run before {@link #tick} (END_CLIENT_TICK), which bumps it after {@link #catchUpEntityTicks}, so both
	 * read the same value within one client tick.
	 */
	long clock() {
		return ticks;
	}

	/**
	 * Advance every agent whose entity tick did not run this client tick: Entity Culling (tickCulling, on by
	 * default) and similar mods skip ticking entities out of view, which froze client-only agents mid-walk.
	 * Same filters as {@code ClientLevel.tickEntities}: not while paused, removed, a passenger or tick-frozen.
	 */
	private void catchUpEntityTicks(Minecraft mc, ClientLevel lvl) {
		if (mc.isPaused()) {
			return;
		}
		for (ClientAgentEntity e : new ArrayList<>(entities.values())) { // a ticker may add or remove agents
			if (e.isRemoved() || e.isPassenger() || e.level() != lvl || lvl.tickRateManager().isEntityFrozen(e)) {
				continue;
			}
			try {
				e.catchUp(ticks);
			} catch (Throwable ex) {
				AgentCraft.LOGGER.warn("agent catch-up tick failed", ex);
			}
		}
	}

	void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		if (lvl != level) {
			entities.clear(); // the old level and its entities are gone
			byEntityId.clear();
			assigner.clear();
			seats.clear();
			userSpots.clear();
			DocHandover.clear();
			level = lvl;
			layoutRevision = -1;
		}
		if (lvl == null) {
			return;
		}
		catchUpEntityTicks(mc, lvl);
		ticks++;
		ForemanState st = Foreman.state();
		if (st == null || !st.hasData()) {
			removeAll();
			return;
		}
		Anchors.Layout layout = Anchors.current();
		boolean relayout = layout.revision() != layoutRevision;
		layoutRevision = layout.revision();
		if (relayout) {
			seats.clear();
			userSpots.clear();
		}
		List<Agent> agents = new ArrayList<>(st.agents().values());
		Map<String, Anchor> targets = layout.isEmpty() ? fallbackTargets(agents, lvl) : assigner.assign(agents, layout);
		boolean stale = st.isStale();
		updateAwaiting(st);
		GridPathfinder pf = layout.isEmpty() ? null : new GridPathfinder(lvl, layout.bounds());
		Vec3 playerFeet = layout.isEmpty() ? null : playerInHq(mc, layout, pf);
		int waitingIndex = 0;
		int podiumIndex = 0;
		int waitingCount = 0;
		if (playerFeet != null) {
			for (Agent a : agents) {
				if (followsPlayer(st, a)) {
					waitingCount++;
				}
			}
		}

		Set<String> keep = new HashSet<>();
		for (Agent a : agents) {
			Anchor target = targets.get(a.id());
			if (target == null) {
				continue;
			}
			keep.add(a.id());
			ClientAgentEntity e = entities.get(a.id());
			if (e == null || e.isRemoved() || e.level() != lvl) {
				e = spawn(lvl, a, target);
				entities.put(a.id(), e);
				byEntityId.put(e.getId(), e);
				showRecentSay(st, e);
			} else if (!e.getSkin().equals(AgentSkins.get(a.id(), a.skin()))) {
				e.setSkin(AgentSkins.get(a.id(), a.skin()));
			}
			AgentView v = e.view();
			v.update(a, stale, awaiting.get(a.id()), awaitingCounts.getOrDefault(a.id(), 0));
			v.station = StationAssigner.stationKey(a);
			v.anchor = target.name();
			boolean offering = !stale && DocHandover.offering(st, a);
			if (playerFeet != null && !stale && followsPlayer(st, a)) {
				Anchor near = userSpot(a.id(), e, playerFeet, waitingIndex++, waitingCount, pf);
				if (near != null) {
					target = near;
				}
			} else {
				userSpots.remove(a.id());
				if (offering) {
					// you are out of reach (outside the HQ): wait at the Decision Podium instead
					List<Anchor> podium = StationAssigner.slots(layout, AnchorNames.USER);
					if (!podium.isEmpty()) {
						target = podium.get(Math.min(podiumIndex++, podium.size() - 1));
					}
				}
			}
			try {
				DocHandover.apply(st, e, v, offering);
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("hand-over update failed", t);
			}
			Seats.Seat seat = pf == null ? null : seats.at(lvl, target, ticks, pf);
			Anchor effective = seat != null ? seat.target() : target;
			if (relayout) {
				e.life().setSeat(seat);
				place(e, effective);
			} else if (!stale) {
				retarget(lvl, layout, e, effective, seat);
			}
		}
		for (var it = entities.entrySet().iterator(); it.hasNext();) {
			var en = it.next();
			if (!keep.contains(en.getKey())) {
				remove(lvl, en.getValue());
				byEntityId.remove(en.getValue().getId());
				it.remove();
			}
		}
	}

	private static boolean followsPlayer(ForemanState st, Agent a) {
		return a.state() == AgentState.WAITING_USER && a.isActive() && !a.isPaused() || DocHandover.offering(st, a);
	}

	/**
	 * agentId -> the first open decision that agent <b>owns</b>. Every open decision has exactly one
	 * owner, so the HQ shows one "!" per decision waiting on the user:
	 * <ul>
	 *   <li>a merge belongs to the worker whose task it merges (the lead files it, but it is the
	 *       worker's finished work that waits; "t4 awaiting your merge"), or to the agent that filed
	 *       it when the task has no known assignee;</li>
	 *   <li>a question or permission prompt belongs to the agent that asked, whatever task it is
	 *       about (a question about Juniper's task is Marlow's question, not Juniper's).</li>
	 * </ul>
	 * An agent's own questions/permissions come before the merges it owns.
	 */
	private void updateAwaiting(ForemanState st) {
		if (st.revision() == awaitingRevision) {
			return;
		}
		awaitingRevision = st.revision();
		awaiting.clear();
		awaitingCounts.clear();
		List<Protocol.Decision> open = st.openDecisions();
		for (int pass = 0; pass < 2; pass++) {
			for (Protocol.Decision d : open) {
				boolean merge = d.kind() == Protocol.DecisionKind.MERGE;
				if (merge != (pass == 1)) {
					continue;
				}
				String owner = owner(st, d);
				awaiting.putIfAbsent(owner, d.id());
				awaitingCounts.merge(owner, 1, Integer::sum);
			}
		}
	}

	/** The agent an open decision belongs to (see {@link #updateAwaiting}). */
	public static String owner(ForemanState st, Protocol.Decision d) {
		if (d.kind() == Protocol.DecisionKind.MERGE && d.taskId() != null) {
			Protocol.Task t = st.task(d.taskId());
			if (t != null && t.assignee() != null && st.agent(t.assignee()) != null) {
				return t.assignee();
			}
		}
		return d.agentId();
	}

	/** The player's feet on the HQ floor when they are inside the HQ and not spectating, else null. */
	private static @Nullable Vec3 playerInHq(Minecraft mc, Anchors.Layout layout, @Nullable GridPathfinder pf) {
		LocalPlayer p = mc.player;
		Anchors.Bounds b = layout.bounds();
		if (p == null || pf == null || b == null || p.isSpectator()) {
			return null;
		}
		// feet a hair below a block top (64.99999) belong to the block above
		BlockPos bp = BlockPos.containing(p.getX(), p.getY() + 0.05, p.getZ());
		if (!b.contains(bp.getX(), bp.getY(), bp.getZ()) && !b.contains(bp.getX(), bp.getY() - 2, bp.getZ())) {
			return null;
		}
		for (int dy = 0; dy <= 3; dy++) {
			double f = pf.floor(bp.getX(), bp.getY() - dy, bp.getZ());
			if (!Double.isNaN(f)) {
				return new Vec3(p.getX(), f, p.getZ());
			}
		}
		return null;
	}

	/**
	 * A free walkable spot about three blocks from the player, on the agent's side (several waiting
	 * agents fan out), facing the player. Three blocks is a conversation distance: the agent, its
	 * plate and its "!" fit on screen at eye level (at two blocks the plate filled the upper middle
	 * of the view and the "!" was cut off). Sticky until the player moves {@value #FOLLOW_SLACK}
	 * blocks away from where they were when it was chosen.
	 */
	private @Nullable Anchor userSpot(String agentId, ClientAgentEntity e, Vec3 player, int index, int count, GridPathfinder pf) {
		UserSpot prev = userSpots.get(agentId);
		if (prev != null && prev.playerAt().distanceTo(player) < FOLLOW_SLACK) {
			return prev.spot();
		}
		double base = Math.atan2(e.getZ() - player.z, e.getX() - player.x);
		if (e.position().distanceToSqr(player) < 0.25) {
			base = 0;
		}
		double spread = Math.toRadians(42);
		double fan = (index - (count - 1) / 2.0) * spread;
		double[] radii = {USER_DISTANCE, USER_DISTANCE + 0.5, USER_DISTANCE - 0.6};
		double[] offs = {0, 0.45, -0.45, 0.9, -0.9, 1.4, -1.4, 2.0, -2.0, Math.PI};
		for (double r : radii) {
			for (double o : offs) {
				double ang = base + fan + o;
				double x = player.x + Math.cos(ang) * r;
				double z = player.z + Math.sin(ang) * r;
				int bx = (int) Math.floor(x);
				int bz = (int) Math.floor(z);
				int by = (int) Math.floor(player.y + 0.01);
				for (int dy : new int[] {0, 1, -1}) {
					double f = pf.floor(bx, by + dy, bz);
					if (Double.isNaN(f)) {
						continue;
					}
					Vec3 at = new Vec3(x, f, z);
					if (!pf.clear(at, at) || taken(agentId, at)) {
						continue;
					}
					float yaw = (float) Math.toDegrees(Math.atan2(-(player.x - x), player.z - z));
					Anchor spot = new Anchor(AnchorNames.USER + "@player", x, f, z, yaw, 0);
					userSpots.put(agentId, new UserSpot(spot, player));
					return spot;
				}
			}
		}
		return null;
	}

	private boolean taken(String agentId, Vec3 at) {
		for (var en : userSpots.entrySet()) {
			if (!en.getKey().equals(agentId) && en.getValue().spot().pos().distanceToSqr(at) < 1.2 * 1.2) {
				return true;
			}
		}
		for (ClientAgentEntity o : entities.values()) {
			if (!o.agentId().equals(agentId) && !o.motion().walking() && o.position().distanceToSqr(at) < 0.9 * 0.9) {
				return true;
			}
		}
		return false;
	}

	/** A fresh agent shows what it said in the last few seconds (e.g. after a reconnect). */
	private static void showRecentSay(ForemanState st, ClientAgentEntity e) {
		Protocol.AgentSay say = st.lastSay(e.agentId());
		if (say == null) {
			return;
		}
		long ago = System.currentTimeMillis() - say.ts();
		if (ago >= 0 && ago < 8000) {
			e.life().bubble.showLate(say, e.life().age(), (int) (ago / 50));
		}
	}

	private ClientAgentEntity spawn(ClientLevel lvl, Agent a, Anchor target) {
		ClientAgentEntity e = new ClientAgentEntity(lvl, a.id(), AgentSkins.get(a.id(), a.skin()));
		// Negative ids never collide with server-assigned entity ids.
		e.setId(nextEntityId--);
		Seats.Seat seat = seats.at(lvl, target, ticks, new GridPathfinder(lvl, Anchors.current().bounds()));
		e.life().setSeat(seat);
		place(e, seat != null ? seat.target() : target);
		lvl.addEntity(e);
		AgentCraft.LOGGER.info("Agent {} appeared at {}", a.id(), target.name());
		return e;
	}

	private static void place(ClientAgentEntity e, Anchor target) {
		Vec3 p = e.motion().placeAt(target);
		e.snapTo(p, target.yaw());
	}

	private void retarget(ClientLevel lvl, Anchors.Layout layout, ClientAgentEntity e, Anchor target, Seats.@Nullable Seat seat) {
		Anchor current = e.motion().target();
		if (current != null && current.name().equals(target.name()) && current.pos().distanceToSqr(target.pos()) < 1e-4) {
			return;
		}
		GridPathfinder pf = new GridPathfinder(lvl, layout.bounds());
		AgentLife life = e.life();
		List<Vec3> route = new ArrayList<>();
		Vec3 start = e.position();
		int delay = 0;
		Seats.Seat from = life.seat();
		if (from != null && life.sitAmount() > 0f && !e.motion().walking()) {
			// get up first, then step out of the seat to its free side
			delay = STAND_UP_TICKS;
			if (from.approach() != null) {
				route.add(start);
				start = from.approach();
			}
		}
		Vec3 dest = seat != null && seat.approach() != null ? seat.approach() : target.pos();
		List<Vec3> path = start.distanceToSqr(dest) < 1e-6 ? List.of(start, dest) : pf.find(start, dest);
		if (path == null || length(path) > MAX_WALK) {
			pathFailures++;
			AgentCraft.LOGGER.info("Agent {}: no walkable route to {} ({}), teleporting", e.agentId(), target.name(),
				path == null ? "no path" : "too far");
			life.setSeat(seat);
			place(e, target);
			return;
		}
		route.addAll(path);
		if (seat != null && seat.approach() != null) {
			route.add(target.pos()); // the last step: onto the seat
		}
		life.setSeat(seat);
		e.motion().walkTo(target, route, delay);
	}

	private static double length(List<Vec3> route) {
		double d = 0;
		for (int i = 1; i < route.size(); i++) {
			d += route.get(i).distanceTo(route.get(i - 1));
		}
		return d;
	}

	/** Snap every agent to its target now (QA: no one mid-walk in a screenshot). */
	public int settle() {
		int n = 0;
		for (ClientAgentEntity e : entities.values()) {
			Anchor t = e.motion().target();
			if (t != null && e.motion().walking()) {
				place(e, t);
				n++;
			}
		}
		return n;
	}

	private void removeAll() {
		if (level != null) {
			for (ClientAgentEntity e : entities.values()) {
				remove(level, e);
			}
		}
		entities.clear();
		byEntityId.clear();
	}

	private static void remove(ClientLevel lvl, ClientAgentEntity e) {
		lvl.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
	}

	/** No layout yet: a row in front of the world spawn, facing it. */
	private static Map<String, Anchor> fallbackTargets(List<Agent> agents, ClientLevel lvl) {
		Map<String, Anchor> out = new LinkedHashMap<>();
		BlockPos spawn = lvl.getRespawnData().pos();
		int n = agents.size();
		for (int i = 0; i < n; i++) {
			double x = spawn.getX() + 0.5 + (i - (n - 1) / 2.0) * 1.4;
			out.put(agents.get(i).id(), new Anchor(AnchorNames.LOUNGE + "@spawn" + i, x, spawn.getY(), spawn.getZ() + 4.5, 180, 0));
		}
		return out;
	}
}

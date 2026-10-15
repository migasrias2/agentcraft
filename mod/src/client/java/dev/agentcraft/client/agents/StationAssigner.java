package dev.agentcraft.client.agents;

import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.Station;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Turns (agent, station) into a concrete anchor. {@code desk} -> {@code desk_<id>}; shared stations
 * spread their agents over slots {@code library}, {@code library_2}, ... Off-shift agents
 * ({@code active=false}) go to the lounge. Assignment is sticky (an agent keeps its slot while it
 * stays at that station; newcomers take the lowest free slot) and processed in Foreman order, so it
 * is deterministic. When a station has more agents than slots, extras stand in a row beside the
 * primary slot.
 */
public final class StationAssigner {
	private final Map<String, String> assigned = new HashMap<>();

	/** The station key an agent should be at: "desk", a shared station name, or "lounge". */
	public static String stationKey(Agent a) {
		if ((a.isSocial() || a.isContent()) && a.isActive()) {
			return "desk"; // Nova and Sable (Social room), Iris, Reel, Vega and Cleo (Content room) live at their desks, whatever the Foreman says
		}
		if (!a.isActive()) {
			return AnchorNames.LOUNGE;
		}
		Station s = a.station();
		return s == Station.UNKNOWN ? AnchorNames.LOUNGE : s.wire();
	}

	/** Assign every agent (in order) and return agentId -> anchor (absent when the layout has nothing usable). */
	public Map<String, Anchor> assign(List<Agent> agents, Anchors.Layout layout) {
		Map<String, Anchor> out = new LinkedHashMap<>();
		Map<String, List<Agent>> byStation = new LinkedHashMap<>();
		for (Agent a : agents) {
			String key = stationKey(a);
			if (key.equals("desk")) {
				Anchor desk = layout.get(AnchorNames.desk(a.id()));
				if (desk != null) {
					out.put(a.id(), desk);
					assigned.put(a.id(), desk.name());
					continue;
				}
				key = AnchorNames.LOUNGE; // no desk for this agent in the layout
			}
			byStation.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
		}
		for (var e : byStation.entrySet()) {
			String station = e.getKey();
			List<Anchor> slots = slots(layout, station);
			if (slots.isEmpty()) {
				slots = slots(layout, AnchorNames.LOUNGE);
			}
			if (slots.isEmpty()) {
				continue;
			}
			Set<String> taken = new HashSet<>();
			List<Agent> unplaced = new ArrayList<>();
			// keep sticky assignments that are still slots of this station
			for (Agent a : e.getValue()) {
				String prev = assigned.get(a.id());
				Anchor keep = null;
				if (prev != null && !taken.contains(prev)) {
					for (Anchor s : slots) {
						if (s.name().equals(prev)) {
							keep = s;
							break;
						}
					}
				}
				if (keep != null) {
					taken.add(keep.name());
					out.put(a.id(), keep);
				} else {
					unplaced.add(a);
				}
			}
			int overflow = 0;
			for (Agent a : unplaced) {
				Anchor pick = null;
				for (Anchor s : slots) {
					if (!taken.contains(s.name())) {
						pick = s;
						break;
					}
				}
				if (pick == null) {
					pick = overflowSpot(slots.getFirst(), ++overflow);
				}
				taken.add(pick.name());
				out.put(a.id(), pick);
				assigned.put(a.id(), pick.name());
			}
		}
		assigned.keySet().retainAll(out.keySet());
		return out;
	}

	/** {@code station}, {@code station_2}, ... as long as they exist in the layout. */
	public static List<Anchor> slots(Anchors.Layout layout, String station) {
		List<Anchor> out = new ArrayList<>();
		for (int n = 1; n <= 32; n++) {
			Anchor a = layout.get(AnchorNames.slot(station, n));
			if (a == null) {
				break;
			}
			out.add(a);
		}
		return out;
	}

	/** Extra agents line up sideways from the primary slot (alternating right/left, 1.1 blocks apart). */
	private static Anchor overflowSpot(Anchor primary, int k) {
		double side = ((k + 1) / 2) * 1.1 * (k % 2 == 1 ? 1 : -1);
		double rad = Math.toRadians(primary.yaw());
		// facing vector (-sin, cos); right-hand perpendicular (cos, sin)
		double px = Math.cos(rad) * side;
		double pz = Math.sin(rad) * side;
		return new Anchor(primary.name() + "~" + k, primary.x() + px, primary.y(), primary.z() + pz, primary.yaw(), primary.pitch());
	}

	public @Nullable String assignedSlot(String agentId) {
		return assigned.get(agentId);
	}

	public void clear() {
		assigned.clear();
	}
}

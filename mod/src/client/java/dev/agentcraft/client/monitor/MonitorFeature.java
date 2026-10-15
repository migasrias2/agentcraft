package dev.agentcraft.client.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.block.entity.MonitorBlockEntity;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.FeedItem;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import dev.agentcraft.client.content.ContentFeature;
import dev.agentcraft.client.content.ContentState;
import dev.agentcraft.client.social.SocialCalendar;
import dev.agentcraft.client.social.SocialCalendarScreen;
import dev.agentcraft.client.ui.Guard;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.client.world.StationInteractions;
import net.minecraft.client.Minecraft;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * Monitors: each desk monitor streams its agent's live log ({@code agent.log}) on the screen, see
 * {@link MonitorRenderer}. Binding = agent id; an empty binding resolves to the agent whose
 * {@code monitor_<id>} anchor lies on the panel, otherwise the screen shows the team activity feed
 * (binding {@code feed} does that explicitly).
 *
 * <p>While an agent has a fresh live screen frame (a screenshot from a browser / Studio / Blender
 * tool, see {@link ScreenFrames}) the monitor shows that image instead of the full log, with a
 * "LIVE" label and a two-row log strip when the panel is tall enough; it falls back to the log
 * when the frame goes stale. Right-click a monitor to open {@link MonitorViewScreen}, the big view.
 * A monitor bound to {@code media} (the Screening Room's big screen) shows the media gallery instead
 * ({@link MediaScreenDraw}, {@link MediaStore}); right-click opens {@link MediaViewScreen}. A monitor bound to {@code calendar}
 * (the Social Room's wall calendar) draws the week's posts ({@link CalendarScreenDraw}); right-click opens the calendar screen.
 * A monitor bound to {@code content} (the Content Room's analytics board) draws the channel's numbers ({@link ContentBoardDraw}); right-click opens the Content Studio.
 *
 * <p>Change tracking: a Foreman listener bumps a per-agent log / agent sequence number, and each
 * screen re-lays itself out only when the numbers it was built from change.
 *
 * <p>Dev: {@code dev.displays {look?: paper|dark|split, monitors?: true}} switches the screen look
 * live (split = alternate per monitor, for side-by-side comparison) and lists the laid-out screens.
 */
public final class MonitorFeature {
	public enum Look {
		PAPER, DARK, SPLIT
	}

	private static Look look = Look.DARK;
	private static final Map<String, Long> LOG_SEQ = new HashMap<>();
	private static final Map<String, Long> AGENT_SEQ = new HashMap<>();
	private static long epoch;
	private static final Map<BlockPos, MonitorScreen> SCREENS = new HashMap<>();
	private static final Map<BlockPos, String> RESOLVED = new HashMap<>();
	private static long resolvedLayoutRevision = -1;
	private static String resolvedLayoutName = "";

	private MonitorFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.MONITOR, ctx -> new MonitorRenderer());
		ScreenFrames.init();
		MediaStore.init();
		StationInteractions.onUse(ModBlocks.MONITOR, (player, pos, state, be) -> {
			if (mediaAt(pos)) {
				Minecraft.getInstance().gui.setScreen(new MediaViewScreen(null));
				return;
			}
			if (calendarAt(pos)) {
				Minecraft.getInstance().gui.setScreen(new SocialCalendarScreen());
				return;
			}
			if (boardAt(pos)) {
				ContentFeature.open();
				return;
			}
			String agent = agentAt(pos);
			if (agent != null) {
				Minecraft.getInstance().gui.setScreen(new MonitorViewScreen(agent));
			}
		});
		DevBridge.registerScreen("monitor", mc -> {
			String id = ScreenFrames.anyAgent();
			if (id == null) {
				throw new DevBridge.DevException("no live screen frame yet (send an agent.screen to the Foreman first)");
			}
			return new MonitorViewScreen(id);
		});
		DevBridge.registerScreen("media", mc -> new MediaViewScreen(null));
		DevBridge.registerScreen("calendar", mc -> new SocialCalendarScreen());
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onSnapshot(ForemanState st) {
				epoch++;
			}

			@Override
			public void onLog(String agentId, List<LogEntry> entries) {
				LOG_SEQ.merge(agentId, 1L, Long::sum);
			}

			@Override
			public void onAgent(@Nullable Agent previous, Agent agent) {
				AGENT_SEQ.merge(agent.id(), 1L, Long::sum);
				AGENT_SEQ.merge("feed", 1L, Long::sum);
			}

			@Override
			public void onFeed(FeedItem item) {
				LOG_SEQ.merge("feed", 1L, Long::sum);
			}

			@Override
			public void onGoal(@Nullable Goal previous, Goal goal) {
				AGENT_SEQ.merge("feed", 1L, Long::sum);
			}
		});
		// forget screens whose panel has not been drawn for a while (broken, unloaded, re-shaped)
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("monitor.sweep", () -> {
			if (mc.level == null || (mc.level.getGameTime() % 200) != 0) {
				return;
			}
			long now = System.nanoTime();
			Iterator<MonitorScreen> it = SCREENS.values().iterator();
			while (it.hasNext()) {
				if (now - it.next().lastUsedNanos > 30_000_000_000L) {
					it.remove();
				}
			}
		}));
		DevBridge.register("dev.displays", 10_000, "{look?: paper|dark|split, reset?: bool} -> monitor look, laid-out monitor screens, "
			+ "display CPU cost per frame since the last reset", (req, mc) -> {
			Fields f = Fields.of(req);
			String l = f.optStr("look", null);
			boolean reset = f.optBool("reset", false);
			return DevBridge.onClient(mc, () -> {
				if (l != null) {
					try {
						look = Look.valueOf(l.toUpperCase(Locale.ROOT));
					} catch (IllegalArgumentException e) {
						throw new DevBridge.DevException("look must be paper, dark or split (got " + l + ")");
					}
				}
				JsonObject o = new JsonObject();
				o.addProperty("look", look.name().toLowerCase(Locale.ROOT));
				JsonArray arr = new JsonArray();
				for (MonitorScreen m : SCREENS.values()) {
					JsonObject j = new JsonObject();
					j.addProperty("pos", m.origin.getX() + " " + m.origin.getY() + " " + m.origin.getZ());
					j.addProperty("agent", m.agentId);
					j.addProperty("mode", m.mode.name().toLowerCase(Locale.ROOT));
					j.addProperty("style", m.style == null ? "" : m.style.id());
					j.addProperty("size", m.panelW + "x" + m.panelH);
					j.addProperty("ppb", m.ppb);
					j.addProperty("rows", m.rows.size());
					j.addProperty("ageMs", (System.nanoTime() - m.lastUsedNanos) / 1_000_000L);
					arr.add(j);
				}
				o.add("monitors", arr);
				o.add("stats", DisplayStats.json());
				if (reset) {
					DisplayStats.reset();
				}
				return o;
			});
		});
	}

	/** The agent shown by the monitor panel that contains {@code pos}, or null (unknown block, feed monitor). */
	static @Nullable String agentAt(BlockPos pos) {
		for (MonitorScreen m : SCREENS.values()) {
			if (m.agentId.isEmpty() || "feed".equals(m.agentId) || MediaScreenDraw.BINDING.equals(m.agentId)
				|| SocialCalendar.BINDING.equals(m.agentId) || ContentState.BINDING.equals(m.agentId) || m.panelW <= 0) {
				continue;
			}
			if (onPanel(m.origin, m.facing.getCounterClockWise(), m.panelW, m.panelH, pos)) {
				return m.agentId;
			}
		}
		return null;
	}

	/** True when the monitor panel that contains {@code pos} is the Screening Room's media screen. */
	static boolean mediaAt(BlockPos pos) {
		for (MonitorScreen m : SCREENS.values()) {
			if (MediaScreenDraw.BINDING.equals(m.agentId) && m.panelW > 0
				&& onPanel(m.origin, m.facing.getCounterClockWise(), m.panelW, m.panelH, pos)) {
				return true;
			}
		}
		return false;
	}

	/** True when the monitor panel that contains {@code pos} is the Social Room's wall calendar. */
	static boolean calendarAt(BlockPos pos) {
		for (MonitorScreen m : SCREENS.values()) {
			if (SocialCalendar.BINDING.equals(m.agentId) && m.panelW > 0
				&& onPanel(m.origin, m.facing.getCounterClockWise(), m.panelW, m.panelH, pos)) {
				return true;
			}
		}
		return false;
	}

	/** True when the monitor panel that contains {@code pos} is the Content Room's wall analytics board. */
	static boolean boardAt(BlockPos pos) {
		for (MonitorScreen m : SCREENS.values()) {
			if (ContentState.BINDING.equals(m.agentId) && m.panelW > 0
				&& onPanel(m.origin, m.facing.getCounterClockWise(), m.panelW, m.panelH, pos)) {
				return true;
			}
		}
		return false;
	}

	static MonitorScreen screen(BlockPos origin) {
		return SCREENS.computeIfAbsent(origin.immutable(), MonitorScreen::new);
	}

	/** Log change counter for an agent (or "feed"): bumps on every append and on every snapshot. */
	public static long logSeq(String agentId) {
		return LOG_SEQ.getOrDefault(agentId, 0L) + (epoch << 32);
	}

	static long agentSeq(String agentId) {
		return AGENT_SEQ.getOrDefault(agentId, 0L) + (epoch << 32);
	}

	static ScreenStyle styleFor(BlockPos origin) {
		return switch (look) {
			case PAPER -> ScreenStyle.PAPER;
			case DARK -> ScreenStyle.DARK;
			case SPLIT -> ((origin.getX() + origin.getZ()) & 2) == 0 ? ScreenStyle.PAPER : ScreenStyle.DARK;
		};
	}

	public static Look look() {
		return look;
	}

	/**
	 * The agent a monitor shows: its binding, else the agent whose {@code monitor_<id>} anchor lies
	 * on this panel, else "feed" (the team activity feed).
	 */
	static String resolveAgent(MonitorBlockEntity be, String binding, Direction facing, int w, int h) {
		if (!binding.isEmpty()) {
			return binding;
		}
		Anchors.Layout layout = Anchors.current();
		if (layout.revision() != resolvedLayoutRevision || !layout.name().equals(resolvedLayoutName)) {
			RESOLVED.clear();
			resolvedLayoutRevision = layout.revision();
			resolvedLayoutName = layout.name();
		}
		BlockPos origin = be.getBlockPos();
		String cached = RESOLVED.get(origin);
		if (cached != null) {
			return cached;
		}
		String found = "feed";
		Direction right = facing.getCounterClockWise();
		for (Map.Entry<String, Anchor> e : layout.anchors().entrySet()) {
			if (!e.getKey().startsWith(AnchorNames.MONITOR_PREFIX)) {
				continue;
			}
			Anchor a = e.getValue();
			// surface-centre anchors may sit on the block boundary: also try a point nudged into the panel
			BlockPos p = BlockPos.containing(a.x(), a.y(), a.z());
			BlockPos q = BlockPos.containing(a.x() - facing.getStepX() * 0.3, a.y(), a.z() - facing.getStepZ() * 0.3);
			if (onPanel(origin, right, w, h, p) || onPanel(origin, right, w, h, q)) {
				found = e.getKey().substring(AnchorNames.MONITOR_PREFIX.length());
				break;
			}
		}
		RESOLVED.put(origin.immutable(), found);
		return found;
	}

	private static boolean onPanel(BlockPos origin, Direction right, int w, int h, BlockPos p) {
		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				if (origin.relative(right, i).above(j).equals(p)) {
					return true;
				}
			}
		}
		return false;
	}
}

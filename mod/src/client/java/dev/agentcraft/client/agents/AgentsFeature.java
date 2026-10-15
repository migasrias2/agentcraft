package dev.agentcraft.client.agents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.ui.Guard;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import dev.agentcraft.entity.ModEntities;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.gui.screens.Screen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Agents feature: client-side agent NPCs (architecture A, see mod/DEV.md). Owns entity rendering,
 * the per-tick sync with the Foreman state ({@link AgentManager}), walking, nameplates, and the
 * {@code dev.agents} DevBridge command. Phase 3 deepens motion/animation/particles through
 * {@link AgentHooks} and this package.
 */
public final class AgentsFeature {
	/** Right-click on an agent (client thread). The click is never sent to the server. */
	@FunctionalInterface
	public interface ClickHandler {
		void clicked(Player player, ClientAgentEntity agent);
	}

	/** Builds the review/answer screen for one open decision (null = cannot open it). */
	@FunctionalInterface
	public interface DecisionScreenFactory {
		@Nullable Screen create(Minecraft mc, Protocol.Decision decision);
	}

	private static final List<ClickHandler> CLICK_HANDLERS = new CopyOnWriteArrayList<>();
	private static final Map<Protocol.DecisionKind, DecisionScreenFactory> DECISION_SCREENS = new ConcurrentHashMap<>();

	private AgentsFeature() {
	}

	public static void onClick(ClickHandler h) {
		CLICK_HANDLERS.add(h);
	}

	/**
	 * The decisions / diff / permissions features register the screen that reviews or answers one
	 * specific decision of a kind; the agent card's "Review" / "Answer" / "Decide" button opens it.
	 * Without one, the card falls back to the DevBridge screen ("diff", "permission", "decision")
	 * when the decision is the oldest open one of its kind (those factories take no arguments), and
	 * otherwise answers inline (the decision's own options as rows).
	 */
	public static void registerDecisionScreen(Protocol.DecisionKind kind, DecisionScreenFactory factory) {
		DECISION_SCREENS.put(kind, factory);
	}

	static @Nullable DecisionScreenFactory decisionScreen(Protocol.DecisionKind kind) {
		return DECISION_SCREENS.get(kind);
	}

	public static void init() {
		// The type's own renderer draws nothing; the dispatcher mixin routes ClientAgentEntity to the
		// slim/wide AgentRenderer built here on every resource reload.
		EntityRenderers.register(ModEntities.AGENT, ctx -> {
			AgentRenderer.provide(ctx);
			return new NoopRenderer<>(ctx);
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("agents.tick", () -> AgentManager.get().tick(mc)));
		// nameplate declutter: every agent's render state is extracted, nothing is submitted yet
		LevelExtractionEvents.END_EXTRACTION.register(ctx -> Guard.run("agents.plates", () -> PlateLayout.layout(ctx.levelState())));
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onSnapshot(ForemanState state) {
				AgentManager.get().onSnapshot();
			}

			@Override
			public void onSay(Protocol.AgentSay say) {
				AgentManager m = AgentManager.get();
				ClientAgentEntity speaker = m.entity(say.agentId());
				if (speaker == null) {
					return;
				}
				speaker.life().onSay(say, speaker.life().age());
				String to = say.to();
				ClientAgentEntity listener = to == null ? null : m.entity(to);
				if (listener != null && listener != speaker) {
					listener.life().listen(say.agentId(), 60 + Math.min(120, say.text().length()));
				}
			}

			@Override
			public void onTask(Protocol.@Nullable Task prev, Protocol.Task now) {
				if (now.status() == Protocol.TaskStatus.DONE && (prev == null || prev.status() != Protocol.TaskStatus.DONE) && now.assignee() != null) {
					ClientAgentEntity e = AgentManager.get().entity(now.assignee());
					if (e != null) {
						e.life().onTaskDone();
					}
				}
			}
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() && entity instanceof ClientAgentEntity agent) {
				if (hand == InteractionHand.MAIN_HAND) {
					CLICK_HANDLERS.forEach(h -> h.clicked(player, agent));
				}
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});
		// right-click an agent: its card (name, state, task, log tail, message/pause/stop)
		// (an agent holding a document for you opens it instead; Shift+right-click keeps the card)
		onClick((player, agent) -> {
			var st = Foreman.state();
			var doc = st == null || player.isShiftKeyDown() ? null : st.unreadDoc(agent.agentId());
			if (doc == null || !dev.agentcraft.client.library.DocScreen.open(doc.id())) {
				Minecraft.getInstance().gui.setScreen(new AgentCardScreen(agent.agentId()));
			}
		});
		DevBridge.registerScreen("agent", mc -> {
			String id = AgentCardScreen.defaultAgent();
			if (id == null) {
				throw new DevBridge.DevException("no agents (is the Foreman connected?)");
			}
			return new AgentCardScreen(id);
		});
		DevBridge.register("dev.agents.card", 10_000, "{agent} -> open the agent card for one agent (like right-clicking it)", (req, mc) -> {
			String id = Fields.of(req).nonBlank("agent");
			return DevBridge.onClient(mc, () -> {
				if (Foreman.state() == null || Foreman.state().agent(id) == null) {
					throw new DevBridge.DevException("no agent '" + id + "'");
				}
				mc.gui.setScreen(new AgentCardScreen(id));
				JsonObject o = new JsonObject();
				o.addProperty("screen", AgentCardScreen.class.getSimpleName());
				o.addProperty("agent", id);
				return o;
			});
		});
		DevBridge.register("dev.agents.fx", 10_000,
			"{agent, fx: confetti|puff|sparkle|say, text?, to?} -> play an agent effect now (QA preview; 'say' shows a local speech bubble)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String id = f.nonBlank("agent");
				String fx = f.nonBlank("fx");
				String text = f.optStr("text", "Pushed the fix - tests are green again.");
				String to = f.optStr("to", null);
				return DevBridge.onClient(mc, () -> {
					ClientAgentEntity e = AgentManager.get().entity(id);
					if (e == null) {
						throw new DevBridge.DevException("no agent '" + id + "' in the world");
					}
					if (!e.life().preview(fx, text, to)) {
						throw new DevBridge.DevException("unknown fx '" + fx + "' (confetti|puff|sparkle|say)");
					}
					JsonObject o = new JsonObject();
					o.addProperty("agent", id);
					o.addProperty("fx", fx);
					return o;
				});
			});
		DevBridge.register("dev.agents.look", 10_000,
			"{agent?} -> each agent's life: posture, seat, sit, head yaw/pitch, bubble, exclaim, particles, family (QA)",
			(req, mc) -> {
				String only = Fields.of(req).optStr("agent", null);
				return DevBridge.onClient(mc, () -> {
					JsonObject o = new JsonObject();
					JsonArray list = new JsonArray();
					for (ClientAgentEntity e : AgentManager.get().entities().values()) {
						if (only != null && !only.equals(e.agentId())) {
							continue;
						}
						AgentLife l = e.life();
						JsonObject j = new JsonObject();
						j.addProperty("id", e.agentId());
						j.addProperty("family", e.view().family);
						j.addProperty("awaitingUser", e.view().awaitingUser);
						j.addProperty("awaitingDecision", e.view().awaitingDecision);
						j.addProperty("needsYou", e.view().needsYou());
						j.addProperty("paused", e.view().showsPaused());
						j.addProperty("posture", l.posture().name());
						j.addProperty("seated", l.seated());
						j.addProperty("sit", round(l.sitAmount()));
						Seats.Seat st = l.seat();
						if (st != null) {
							JsonObject sj = new JsonObject();
							sj.addProperty("x", round(st.sit().x));
							sj.addProperty("z", round(st.sit().z));
							sj.addProperty("top", round(st.seatTop()));
							sj.addProperty("drop", round(st.drop()));
							sj.addProperty("deskTop", Double.isNaN(st.deskTop()) ? null : round(st.deskTop()));
							j.add("seat", sj);
						}
						j.addProperty("bodyYaw", round(e.yBodyRot));
						j.addProperty("headYaw", round(e.getYHeadRot()));
						j.addProperty("headPitch", round(e.getXRot()));
						j.addProperty("bubble", l.bubble.visible() ? l.bubble.current().text() : null);
						j.addProperty("particles", l.particles.live());
						list.add(j);
					}
					o.add("agents", list);
					o.addProperty("exclaims", exclaims());
					if (mc.gui.screen() instanceof AgentCardScreen card) {
						JsonObject cj = new JsonObject();
						cj.addProperty("agent", card.agentId());
						cj.addProperty("input", card.inputText());
						o.add("card", cj);
					}
					// SDL delivers typed characters only while text input is started (26.x)
					o.addProperty("textInputActive", org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(mc.getWindow().handle()));
					return o;
				});
			});
		if (dev.agentcraft.client.ClientEnv.flag("AGENTCRAFT_DEV_TEST", false)) {
			DevBridge.register("dev.agents.keys", 30_000,
				"{keys: 'm,hello world,return'} - TEST ONLY: press keys as SDL reports a keyboard (SDL events queued for the game window,"
					+ " through Minecraft's SDL event loop; printable keys also type text only while SDL text input is on), one every 3 frames;"
					+ " a token that is not a key name (space return escape back tab left right) is typed letter by letter (a-z 0-9 space)",
				(req, mc) -> {
					String keys = Fields.of(req).nonBlank("keys");
					List<Integer> codes = new java.util.ArrayList<>();
					for (String token : keys.split(",")) {
						int sc = token.trim().length() > 1 ? SdlKeys.scancode(token.trim()) : -1;
						if (sc >= 0) {
							codes.add(sc);
							continue;
						}
						for (char c : token.toCharArray()) {
							int v = SdlKeys.scancode(String.valueOf(c));
							if (v < 0) {
								throw new DevBridge.DevException("cannot type '" + c + "' (lowercase letters, digits, space)");
							}
							codes.add(v);
						}
					}
					List<java.nio.ByteBuffer> keep = new java.util.ArrayList<>();
					int[] texts = {0};
					java.util.concurrent.CompletableFuture<Void> chain = java.util.concurrent.CompletableFuture.completedFuture(null);
					for (int sc : codes) {
						chain = chain.thenCompose(v -> DevBridge.onClient(mc, () -> {
							if (SdlKeys.press(mc.getWindow(), sc, keep)) {
								texts[0]++;
							}
							return null;
						})).thenCompose(v -> dev.agentcraft.client.dev.FrameScheduler.afterFrames(3));
					}
					return chain.thenCompose(v -> DevBridge.onClient(mc, () -> {
						SdlKeys.free(keep);
						JsonObject o = new JsonObject();
						o.addProperty("pressed", codes.size());
						o.addProperty("textEvents", texts[0]);
						o.addProperty("screen", mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
						if (mc.gui.screen() instanceof AgentCardScreen card) {
							o.addProperty("input", card.inputText());
						}
						o.addProperty("textInputActive", org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive(mc.getWindow().handle()));
						return o;
					}));
				});
		}
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
			entity instanceof ClientAgentEntity ? InteractionResult.FAIL : InteractionResult.PASS);

		DevBridge.addStateContributor((mc, o) -> {
			JsonObject a = new JsonObject();
			AgentManager m = AgentManager.get();
			a.addProperty("count", m.entities().size());
			a.addProperty("moving", m.movingCount());
			a.addProperty("pathFailures", m.pathFailures());
			a.addProperty("plates", PlateLayout.laidOut());
			a.addProperty("plateOverlaps", PlateLayout.overlaps());
			a.addProperty("plateOverlapPairs", PlateLayout.overlapPairs());
			a.addProperty("plateLayoutUs", Math.round(PlateLayout.layoutMicros() * 10) / 10.0);
			a.addProperty("exclaims", exclaims());
			o.add("agents", a);
		});
		DevBridge.register("dev.agents.freezeEntityTick", 10_000,
			"{on?} -> skip every agent's entity tick (emulates Entity Culling's tickCulling; agents must keep walking via"
				+ " the client-tick catch-up); always returns {on, entityAdvances, catchUpAdvances, moving} summed over agents",
			(req, mc) -> {
				Boolean on = Fields.of(req).optBool("on");
				return DevBridge.onClient(mc, () -> {
					if (on != null) {
						ClientAgentEntity.freezeEntityTick = on;
					}
					long byEntity = 0;
					long byCatchUp = 0;
					for (ClientAgentEntity e : AgentManager.get().entities().values()) {
						byEntity += e.gate().byEntity();
						byCatchUp += e.gate().byCatchUp();
					}
					JsonObject o = new JsonObject();
					o.addProperty("on", ClientAgentEntity.freezeEntityTick);
					o.addProperty("entityAdvances", byEntity);
					o.addProperty("catchUpAdvances", byCatchUp);
					o.addProperty("moving", AgentManager.get().movingCount());
					return o;
				});
			});
		DevBridge.register("dev.agents", 10_000,
			"{settle?:false} -> {count, moving, plateOverlaps, agents:[{id, x,y,z, yaw, station, anchor, target, walking, state, activity,"
				+ " plate:{mode full|compact, lift, rank, nudge, focused, rect:[x0,y0,x1,y1] screen px}}]};"
				+ " settle:true snaps walking agents to their targets and nameplates to their final layout (next frame)",
			(req, mc) -> {
				boolean settle = Fields.of(req).optBool("settle", false);
				return DevBridge.onClient(mc, () -> {
					AgentManager m = AgentManager.get();
					JsonObject o = new JsonObject();
					if (settle) {
						o.addProperty("settled", m.settle());
						PlateLayout.snapNextFrame();
					}
					o.addProperty("count", m.entities().size());
					o.addProperty("moving", m.movingCount());
					o.addProperty("plates", PlateLayout.laidOut());
					o.addProperty("plateOverlaps", PlateLayout.overlaps());
					JsonArray list = new JsonArray();
					for (ClientAgentEntity e : m.entities().values()) {
						JsonObject j = new JsonObject();
						AgentView v = e.view();
						j.addProperty("id", v.id);
						j.addProperty("entityId", e.getId());
						j.addProperty("x", round(e.getX()));
						j.addProperty("y", round(e.getY()));
						j.addProperty("z", round(e.getZ()));
						j.addProperty("yaw", round(e.getYRot()));
						j.addProperty("station", v.station);
						j.addProperty("anchor", v.anchor);
						var t = e.motion().target();
						if (t != null) {
							JsonObject tj = new JsonObject();
							tj.addProperty("x", round(t.x()));
							tj.addProperty("y", round(t.y()));
							tj.addProperty("z", round(t.z()));
							tj.addProperty("yaw", round(t.yaw()));
							j.add("target", tj);
						}
						j.addProperty("walking", e.motion().walking());
						JsonArray path = new JsonArray();
						for (Vec3 p : e.motion().remainingPath()) {
							JsonArray pj = new JsonArray();
							pj.add(round(p.x));
							pj.add(round(p.y));
							pj.add(round(p.z));
							path.add(pj);
						}
						j.add("path", path);
						j.addProperty("state", v.state.wire());
						j.addProperty("activity", v.activityLine());
						j.addProperty("stale", v.stale);
						PlateLayout.Track pt = PlateLayout.track(v.id);
						if (pt != null) {
							JsonObject pj = new JsonObject();
							pj.addProperty("mode", pt.compact ? "compact" : "full");
							pj.addProperty("lift", round(pt.lift));
							pj.addProperty("target", round(pt.targetLift));
							pj.addProperty("depth", round(pt.depth));
							pj.addProperty("weight", pt.weight);
							pj.addProperty("capped", pt.capped);
							pj.addProperty("leaderGaps", pt.gapCount);
							pj.addProperty("rank", pt.rank);
							pj.addProperty("nudge", Math.round(pt.nudge * 1e5) / 1e5);
							pj.addProperty("scale", round(pt.scale));
							pj.addProperty("focused", pt.focused);
							JsonArray r = new JsonArray();
							r.add(round(pt.rx0));
							r.add(round(pt.ry0));
							r.add(round(pt.rx1));
							r.add(round(pt.ry1));
							pj.add("rect", r);
							j.add("plate", pj);
						}
						j.addProperty("model", e.getSkin().model().getSerializedName());
						j.addProperty("skin", e.getSkin().body().texturePath().toString());
						list.add(j);
					}
					o.add("agents", list);
					return o;
				});
			});
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	/** Agents showing the "needs you" marker right now (QA: should match the open decisions + waiting_user agents). */
	private static int exclaims() {
		int n = 0;
		for (ClientAgentEntity e : AgentManager.get().entities().values()) {
			if (e.view().needsYou()) {
				n++;
			}
		}
		return n;
	}
}

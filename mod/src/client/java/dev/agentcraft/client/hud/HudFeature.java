package dev.agentcraft.client.hud;

import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.ui.GuardedHud;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

/**
 * HUD: the Foreman connection pill / auth banner ({@link ConnectionBanner}), the boss-bar style goal
 * progress with the decisions badge ({@link GoalBar}), paper toasts for {@code notify}
 * ({@link Toasts}) and the decision bell / done chime ({@link HudSounds}).
 *
 * <p>QA: {@code dev.toast {text, level?, decisionId?}} shows a toast without the Foreman,
 * {@code dev.hud.state} reports what the HUD shows (waiting count, toasts, sounds).
 */
public final class HudFeature {
	private HudFeature() {
	}

	public static void init() {
		Keys.ensureRegistered();
		HudElementRegistry.addLast(AgentCraft.id("hud/connection"), GuardedHud.of("hud.connection", new ConnectionBanner()));
		HudElementRegistry.addLast(AgentCraft.id("hud/goal"), GuardedHud.of("hud.goal", new GoalBar()));
		HudElementRegistry.addLast(AgentCraft.id("hud/toasts"), GuardedHud.of("hud.toasts", new Toasts()));
		Toasts.init();
		HudSounds.init();
		// QA: the vanilla key binds screen, to check the AgentCraft category (dev.screen {open:"keybinds"})
		DevBridge.registerScreen("keybinds", mc -> new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(null, mc.options));
		DevBridge.register("dev.toast", 10_000, "{text, level?: info|warn|need_user, decisionId?} - show an in-game toast (no Foreman needed)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String text = f.nonBlank("text");
				String level = f.has("level") ? f.nonBlank("level").toLowerCase(Locale.ROOT) : "info";
				String did = f.has("decisionId") ? f.nonBlank("decisionId") : null;
				NotifyLevel lv = switch (level) {
					case "info" -> NotifyLevel.INFO;
					case "warn" -> NotifyLevel.WARN;
					case "need_user" -> NotifyLevel.NEED_USER;
					default -> throw new DevBridge.DevException("level must be info, warn or need_user");
				};
				return DevBridge.onClient(mc, () -> {
					Toasts.push(new Notify(lv, text, did, System.currentTimeMillis()));
					return hudState();
				});
			});
		DevBridge.register("dev.hud.state", 10_000, "{} - what the AgentCraft HUD shows: decisions waiting, toasts, sounds", (req, mc) -> DevBridge
			.onClient(mc, HudFeature::hudState));
		DevBridge.register("dev.hud.guiScale", 10_000, "{scale: 0 (auto) - 6} - change the GUI scale for this session (layout checks; not saved)",
			(req, mc) -> {
				int scale = Fields.of(req).optInt("scale", 3, 0, 6);
				return DevBridge.onClient(mc, () -> {
					mc.options.guiScale().set(scale);
					JsonObject o = new JsonObject();
					o.addProperty("guiScale", mc.getWindow().getGuiScale());
					o.addProperty("guiWidth", mc.getWindow().getGuiScaledWidth());
					o.addProperty("guiHeight", mc.getWindow().getGuiScaledHeight());
					return o;
				});
			});
	}

	static JsonObject hudState() {
		JsonObject o = new JsonObject();
		o.addProperty("waiting", DecisionsFeature.waitingCount());
		o.addProperty("toastsActive", Toasts.active());
		o.addProperty("toastsShown", Toasts.shown());
		o.addProperty("goalBarBottom", GoalBar.bottom);
		o.addProperty("goalBarRight", GoalBar.right);
		o.addProperty("pillLeft", ConnectionBanner.pillLeft);
		o.addProperty("pillBottom", ConnectionBanner.pillBottom);
		// something the goal bar drew last frame intersected the connection pill (should never be true)
		o.addProperty("goalBarPillClash", GoalBar.pillClash);
		o.addProperty("autoApprove", dev.agentcraft.client.foreman.Foreman.state() != null && dev.agentcraft.client.foreman.Foreman.state().autoApprove());
		o.addProperty("soundsEnabled", HudSounds.enabled());
		o.addProperty("forcedMute", HudSounds.forcedMute());
		o.addProperty("bells", HudSounds.bells());
		o.addProperty("chimes", HudSounds.chimes());
		o.addProperty("lastSound", HudSounds.lastEvent());
		o.addProperty("consoleKey", Keys.label(Keys.console));
		o.addProperty("decisionsKey", Keys.label(Keys.decisions));
		o.addProperty("terminalKey", Keys.label(Keys.terminal));
		// what Options > Controls shows for them (proves the lang keys resolve)
		JsonObject names = new JsonObject();
		for (var k : new net.minecraft.client.KeyMapping[] {Keys.console, Keys.terminal, Keys.decisions}) {
			if (k != null) {
				names.addProperty(k.getName(), net.minecraft.client.resources.language.I18n.get(k.getName()) + " [" + k.getCategory().label().getString() + "]");
			}
		}
		o.add("keyNames", names);
		return o;
	}
}

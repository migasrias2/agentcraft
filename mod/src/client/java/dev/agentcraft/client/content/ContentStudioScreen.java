package dev.agentcraft.client.content;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.console.ConsoleFeature;
import dev.agentcraft.client.content.ContentState.Tab;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.ContentChannel;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Guard;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Content Studio: right-click the lectern in the Content Room (or {@code /content}, or
 * {@code dev.screen {open:"content"}}). Four tabs over one channel: Pipeline (the videos, approve / reject / comment
 * / approve the cut / publish), Analytics (KPIs, charts, the per-video table), References (the videos the agents
 * studied frame by frame) and Playbook (what works for this channel). A dark, flat window with one accent colour.
 *
 * <pre>
 * keys: Esc closes (or leaves the form / field) · Tab moves between the form's fields
 * </pre>
 */
public final class ContentStudioScreen extends Screen {
	private static final int HEAD_H = 30;
	private static final int STRIP_H = 24;
	private static final int FOOT_H = 26;

	private final ContentActions actions = new ContentActions();
	private final Canvas canvas = new Canvas(actions);
	private final Map<Tab, dev.agentcraft.client.content.Tab> tabs = new EnumMap<>(Tab.class);
	private @Nullable ChannelForm form;

	public ContentStudioScreen() {
		super(Component.translatable("agentcraft.content.studio"));
		tabs.put(Tab.PIPELINE, new PipelineTab());
		tabs.put(Tab.ANALYTICS, new AnalyticsTab());
		tabs.put(Tab.REFERENCES, new ReferencesTab());
		tabs.put(Tab.PLAYBOOK, new PlaybookTab());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Typed letters only arrive (charTyped) while the screen holds text-input focus, like the console. */
	@Override
	protected void init() {
		minecraft.onTextInputFocusChange(this, true);
	}

	@Override
	public void removed() {
		minecraft.onTextInputFocusChange(this, false);
		super.removed();
	}

	/** While a field is being edited, keys are text, not game controls. */
	@Override
	public boolean isInputCaptured() {
		return canvas.focus != null || form != null;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// no dimming: the studio stays visible around the window
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.isEscape()) {
			if (form != null) {
				form = null;
				canvas.focus = null;
			} else if (canvas.focus != null) {
				canvas.focus = null;
			} else {
				onClose();
			}
			return true;
		}
		if (form != null) {
			return form.key(canvas, this.font, e) || super.keyPressed(e);
		}
		if (canvas.focus != null) {
			if (canvas.focus.key(this.font, e)) {
				return true;
			}
			return e.key() == InputConstants.KEY_TAB || super.keyPressed(e);
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		if (canvas.focus != null && e.codepoint() >= 32) {
			canvas.focus.model.insert(e.codepointAsString());
			return true;
		}
		return super.charTyped(e);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) { // 26.3: SDL numbering, left = 1
			DarkField before = canvas.focus;
			canvas.focus = null;
			if (canvas.click(e.x(), e.y())) {
				return true;
			}
			canvas.focus = form == null ? null : before;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (canvas.scroll(mx, my, sy)) {
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		// a bug in a tab must not take the client down (a screen that throws while rendering crashes the game)
		Guard.run("content.studio", () -> draw(g, mx, my));
	}

	private void draw(GuiGraphicsExtractor g, int mx, int my) {
		canvas.begin(g, this.font, mx, my);
		int w = Math.min(1100, this.width - 16);
		int h = Math.min(660, this.height - 16);
		int x = (this.width - w) / 2;
		int y = (this.height - h) / 2;
		canvas.fill(x - 1, y - 1, w + 2, h + 2, Canvas.RULE);
		canvas.fill(x, y, w, h, Canvas.BG);
		int ix = x + 12;
		int iw = w - 24;
		header(ix, y + 8, iw);
		ForemanState st = Foreman.state();
		int cy = y + HEAD_H + 8;
		if (st == null || !st.hasData()) {
			canvas.textC("Waiting for the Foreman", x + w / 2, y + h / 2 - 4, Canvas.MUTED);
		} else {
			strip(ix, cy, iw);
			cy += STRIP_H + 6;
			int bodyH = y + h - FOOT_H - 6 - cy;
			if (bodyH >= 80) {
				tabs.get(ContentState.tab()).render(canvas, ix, cy, iw, bodyH);
			} else {
				canvas.text("The window is too small for the Studio", ix, cy + 8, Canvas.MUTED);
			}
		}
		footer(ix, y + h - FOOT_H, iw);
		if (form != null) {
			form.render(canvas, this.width, this.height, () -> {
				form = null;
				canvas.focus = null;
			});
		}
	}

	private void header(int x, int y, int w) {
		canvas.text("Content Studio", x, y + 4, Canvas.TEXT);
		ForemanState st = Foreman.state();
		int waiting = st == null ? 0 : ContentState.waitingForYou(null);
		int right = x + w;
		Tab[] all = Tab.values();
		for (int i = all.length - 1; i >= 0; i--) {
			Tab t = all[i];
			String badge = t == Tab.PIPELINE && waiting > 0 ? " " + waiting : "";
			int tw = this.font.width(t.label + badge) + 20;
			int tx = right - tw;
			boolean active = ContentState.tab() == t;
			boolean hv = canvas.hover(tx, y, tw, 20);
			if (active) {
				canvas.fill(tx, y, tw, 20, Canvas.PANEL_HI);
				canvas.fill(tx + 4, y + 19, tw - 8, 1, Canvas.ACCENT);
			} else if (hv) {
				canvas.fill(tx, y, tw, 20, Canvas.PANEL);
			}
			canvas.text(t.label, tx + 10, y + 6, active ? Canvas.TEXT : Canvas.MUTED);
			if (!badge.isEmpty()) {
				canvas.text(badge.strip(), tx + 10 + this.font.width(t.label) + 4, y + 6, Canvas.ACCENT);
			}
			canvas.hit(tx, y, tw, 20, () -> ContentState.setTab(t));
			right = tx - 2;
		}
	}

	/** Channel pills on the left; autopilot, posts a week, sync and remove on the right. */
	private void strip(int x, int y, int w) {
		ForemanState st = Foreman.state();
		ContentChannel sel = ContentState.channel();
		int cx = x;
		for (ContentChannel ch : st.contentChannels()) {
			boolean on = sel != null && ch.id().equals(sel.id());
			int pw = Math.min(150, this.font.width(ch.name()) + 22);
			boolean hv = canvas.hover(cx, y, pw, STRIP_H - 4);
			canvas.fill(cx, y, pw, STRIP_H - 4, on ? Canvas.PANEL_HI : hv ? Canvas.PANEL : 0);
			if (on) {
				canvas.fill(cx + 4, y + STRIP_H - 5, pw - 8, 1, Canvas.ACCENT);
			}
			canvas.fill(cx + 8, y + 7, 4, 4, ch.connected() ? Canvas.OK : Canvas.FAINT);
			canvas.fit(ch.name(), cx + 16, y + 5, pw - 20, on ? Canvas.TEXT : Canvas.MUTED);
			String id = ch.id();
			canvas.hit(cx, y, pw, STRIP_H - 4, () -> ContentState.selectChannel(id));
			cx += pw + 2;
		}
		canvas.button("+ Channel", cx + 4, y, 70, STRIP_H - 4, Canvas.Kind.GHOST, true, () -> {
			form = new ChannelForm();
			canvas.focus = null;
		});
		if (sel == null) {
			return;
		}
		int right = x + w;
		String id = sel.id();
		right = canvas.button(canvas.actions.armed("remove:" + id) ? "Sure?" : "Remove", right - 56, y, 56, STRIP_H - 4, Canvas.Kind.GHOST, true,
			() -> canvas.actions.confirmed("remove:" + id, () -> canvas.actions.channel(id, "remove", null, null, null, null, null, "Channel removed", null))) - 60;
		right = canvas.button("Sync now", right - 60, y, 60, STRIP_H - 4, Canvas.Kind.SECONDARY, true,
			() -> canvas.actions.channel(id, "sync", null, null, null, null, null, "Vega is syncing the numbers", null)) - 64;
		// posts a week stepper
		int n = sel.postsPerWeek();
		right = canvas.button("+", right - 18, y, 18, STRIP_H - 4, Canvas.Kind.SECONDARY, n < 70,
			() -> canvas.actions.channel(id, "update", null, null, null, null, n + 1, n + 1 + " posts a week", null)) - 22;
		String perWeek = n + " / week";
		canvas.textR(perWeek, right - 4, y + 5, Canvas.MUTED);
		right -= this.font.width(perWeek) + 8;
		right = canvas.button("-", right - 18, y, 18, STRIP_H - 4, Canvas.Kind.SECONDARY, n > 1,
			() -> canvas.actions.channel(id, "update", null, null, null, null, n - 1, n - 1 + " posts a week", null)) - 10;
		// autopilot switch
		boolean ap = sel.autopilot();
		String label = ap ? "Autopilot on" : "Autopilot off";
		int aw = this.font.width(label) + 34;
		int ax = right - aw;
		canvas.fill(ax, y, aw, STRIP_H - 4, ap ? UiMix.mix(Canvas.PANEL_HI, Canvas.ACCENT, 0.25f) : Canvas.PANEL_HI);
		canvas.fill(ax + 7, y + 7, 14, 6, ap ? Canvas.ACCENT : Canvas.FAINT);
		canvas.fill(ap ? ax + 15 : ax + 7, y + 6, 6, 8, Canvas.TEXT);
		canvas.text(label, ax + 26, y + 5, ap ? Canvas.TEXT : Canvas.MUTED);
		canvas.hit(ax, y, aw, STRIP_H - 4, () -> canvas.actions.channel(id, ap ? "autopilot_off" : "autopilot_on", null, null, null, null, null,
			ap ? "Autopilot off: agents wait for your approvals" : "Autopilot on: agents approve ideas and cuts themselves", null));
	}

	private void footer(int x, int y, int w) {
		String msg = actions.flashText();
		int right = x + w;
		String[] ids = ContentState.AGENTS;
		for (int i = ids.length - 1; i >= 0; i--) {
			String id = ids[i];
			String label = UiBits.agentName(id);
			int bw = this.font.width(label) + 16;
			canvas.button(label, right - bw, y + 2, bw, 18, Canvas.Kind.GHOST, true, () -> ConsoleFeature.open("@" + id + " ", false));
			right -= bw + 4;
		}
		canvas.textR("Talk to", right - 2, y + 8, Canvas.FAINT);
		int textW = Math.max(40, right - 56 - x);
		if (!msg.isEmpty()) {
			canvas.fit(msg, x, y + 8, textW, actions.flashIsError() ? Canvas.WARN : Canvas.OK);
		} else {
			canvas.fit("Esc closes", x, y + 8, textW, Canvas.FAINT);
		}
	}
}

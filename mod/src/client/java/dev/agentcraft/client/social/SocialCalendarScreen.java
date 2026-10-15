package dev.agentcraft.client.social;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.console.ConsoleFeature;
import dev.agentcraft.client.console.TextFieldView;
import dev.agentcraft.client.console.TextKeys;
import dev.agentcraft.client.console.TextModel;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.SocialPost;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.monitor.DisplayDraw;
import dev.agentcraft.client.monitor.LocalImages;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Social Desk calendar: right-click the Social Room's wall calendar (or {@code dev.screen {open:"calendar"}}).
 * Left, the week as an agenda (a row per post: platform badge, time, first words, status) or the month as a
 * grid of days with a dot per post; right, the selected post in full with what you can do to it: Approve (the
 * one approval: it goes out at its time without asking again), Unapprove, Post now, Cancel (both ask twice),
 * move it by -1h / +1h / +1d, edit the text, and talk to Nova or Sable. The Foreman's refusals show inline.
 *
 * <pre>
 * keys: Left / Right week (month) · T today · M week/month · Esc close (or leave the editor)
 * </pre>
 */
public final class SocialCalendarScreen extends Screen {
	private static final int BTN_H = 20;
	private static final int ROW_H = 24;
	private static final int DAY_H = 14;
	private static final long HOUR = 3_600_000L;
	private static final long DAY = 24 * HOUR;
	private static final int WHITE = 0xFFFFFFFF;

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean at(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	private final List<Hit> listHits = new ArrayList<>();
	private LocalDate weekStart = SocialCalendar.weekStart(SocialCalendar.today());
	private LocalDate monthFirst = SocialCalendar.today().withDayOfMonth(1);
	private boolean month;
	private @Nullable String selectedId;
	private @Nullable LocalDate selectedDay;

	private boolean editing;
	private final TextModel edit = new TextModel(3000);
	private final TextFieldView editView = new TextFieldView();
	private int fieldX;
	private int fieldY;
	private int fieldW;
	private TextFieldView.@Nullable Style fieldStyle;

	private @Nullable String confirm;
	private long confirmUntil;
	private volatile String flash = "";
	private volatile boolean flashError;
	private volatile long flashUntil;

	private int listScroll;
	private int textScroll;
	private final int[] listBox = new int[4];
	private final int[] textBox = new int[4];
	private int mouseX;
	private int mouseY;

	public SocialCalendarScreen() {
		super(Component.literal("Social calendar"));
		ForemanState st = Foreman.state();
		if (st != null) {
			List<SocialPost> next = SocialCalendar.upcoming(st.socialPosts());
			if (!next.isEmpty()) {
				SocialPost p = next.getFirst();
				selectedId = p.id();
				if (p.scheduledAt() != null) {
					showDay(SocialCalendar.dayOf(p.scheduledAt()));
				}
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// no dimming: the studio stays visible around the panel
	}

	private void showDay(LocalDate d) {
		weekStart = SocialCalendar.weekStart(d);
		monthFirst = d.withDayOfMonth(1);
	}

	private @Nullable SocialPost selected() {
		ForemanState st = Foreman.state();
		return st == null || selectedId == null ? null : st.socialPost(selectedId);
	}

	private void select(SocialPost p) {
		if (!p.id().equals(selectedId)) {
			editing = false;
			confirm = null;
			textScroll = 0;
		}
		selectedId = p.id();
	}

	private void flash(String text, boolean error) {
		flash = text;
		flashError = error;
		flashUntil = System.currentTimeMillis() + (error ? 9000 : 4000);
	}

	// ------------------------------------------------------------------ actions

	private void send(String postId, String action, @Nullable Long at, @Nullable String text, String done, boolean leaveEditor) {
		flash("Working…", false);
		try {
			Foreman.socialAction(postId, action, at, text).whenComplete((ack, err) -> Minecraft.getInstance().execute(() -> {
				if (err != null) {
					Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
					flash(c.getMessage() != null ? TextUtil.firstLine(c.getMessage()) : "Could not reach the Foreman", true);
				} else if (ack == null || !ack.ok()) {
					flash(ack == null || ack.error() == null ? "The Foreman said no" : ack.error(), true);
				} else {
					flash(done + " " + UiBits.CHECK, false);
					if (leaveEditor) {
						editing = false;
					}
					if (at != null) {
						showDay(SocialCalendar.dayOf(at));
					}
				}
			}));
		} catch (RuntimeException e) {
			flash("The Foreman is not connected", true);
		}
	}

	/** First click arms a destructive action, the second (within 4 s) runs it. */
	private void confirmed(String key, Runnable run) {
		long now = System.currentTimeMillis();
		if (key.equals(confirm) && now < confirmUntil) {
			confirm = null;
			run.run();
		} else {
			confirm = key;
			confirmUntil = now + 4000;
			flash("Click again to confirm", false);
		}
	}

	private void move(SocialPost p, long delta) {
		long now = System.currentTimeMillis();
		long base = p.scheduledAt() != null ? p.scheduledAt() : now;
		long at = (delta > 0 ? Math.max(base, now) : base) + delta;
		send(p.id(), "schedule", at, null, "Moved to " + SocialCalendar.dayTime(at), false);
	}

	private void startEdit(SocialPost p) {
		edit.set(p.text());
		editing = true;
	}

	private void step(int dir) {
		if (month) {
			monthFirst = monthFirst.plusMonths(dir);
		} else {
			weekStart = weekStart.plusWeeks(dir);
		}
		listScroll = 0;
	}

	private void today() {
		showDay(SocialCalendar.today());
		listScroll = 0;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent e) {
		int k = e.key();
		if (editing) {
			if (e.isEscape()) {
				editing = false;
				return true;
			}
			if (TextKeys.isEnter(e)) {
				edit.insert("\n");
			} else if ((k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN) && fieldStyle != null) {
				edit.vertical(this.font, TextFieldView.wrapWidth(this.font, edit, fieldW, fieldStyle), k == InputConstants.KEY_UP ? -1 : 1, e.hasShiftDown());
			} else {
				TextKeys.handle(e, edit);
			}
			return true;
		}
		if (e.isEscape()) {
			onClose();
			return true;
		}
		switch (k) {
			case InputConstants.KEY_LEFT -> step(-1);
			case InputConstants.KEY_RIGHT -> step(1);
			case InputConstants.KEY_T -> today();
			case InputConstants.KEY_M -> month = !month;
			default -> {
				return super.keyPressed(e);
			}
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		if (editing && e.codepoint() >= 32) {
			edit.insert(e.codepointAsString());
			return true;
		}
		return super.charTyped(e);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) { // 26.3: SDL numbering, left = 1
			if (editing && fieldStyle != null) {
				int at = editView.hit(this.font, edit, fieldX, fieldY, fieldW, fieldStyle, e.x(), e.y());
				if (at >= 0) {
					edit.moveTo(at, false);
					return true;
				}
			}
			for (int i = hits.size() - 1; i >= 0; i--) {
				if (hits.get(i).at(e.x(), e.y())) {
					hits.get(i).action().run();
					return true;
				}
			}
			if (inBox(listBox, e.x(), e.y())) {
				for (int i = listHits.size() - 1; i >= 0; i--) {
					if (listHits.get(i).at(e.x(), e.y())) {
						listHits.get(i).action().run();
						return true;
					}
				}
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (inBox(textBox, mx, my)) {
			textScroll = Math.max(0, textScroll - (int) (sy * 20));
			return true;
		}
		if (!month && inBox(listBox, mx, my)) {
			listScroll = Math.max(0, listScroll - (int) (sy * 24));
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	private static boolean inBox(int[] b, double mx, double my) {
		return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		hits.clear();
		listHits.clear();
		mouseX = mx;
		mouseY = my;
		ForemanState st = Foreman.state();
		List<SocialPost> all = st == null ? List.of() : st.socialPosts();
		Kit.Padding pp = Kit.padding("panel_paper");
		int w = Math.min(980, this.width - 24);
		int h = Math.min(560, this.height - 24);
		int x0 = (this.width - w) / 2;
		int y0 = (this.height - h) / 2;
		Panels.panel(g, x0, y0, w, h);
		int ix = x0 + pp.left();
		int iy = y0 + pp.top();
		int iw = w - pp.left() - pp.right();
		int ih = h - pp.top() - pp.bottom();
		int ink = UiBits.ink();
		int muted = UiBits.muted();

		// ---- header: title, period, navigation
		Panels.text(g, this.font, "Social calendar", ix, iy + 6, ink);
		String period = month ? SocialCalendar.month(monthFirst) : SocialCalendar.weekRange(weekStart) + " " + weekStart.plusDays(6).getYear();
		Panels.text(g, this.font, period, ix + this.font.width("Social calendar") + 12, iy + 6, muted);
		int bx = ix + iw - (24 + 52 + 24 + 64 + 12);
		bx = button(g, "<", bx, iy, 24, false, true, () -> step(-1)) + 4;
		bx = button(g, "Today", bx, iy, 52, false, true, this::today) + 4;
		bx = button(g, ">", bx, iy, 24, false, true, () -> step(1)) + 4;
		button(g, month ? "Week" : "Month", bx, iy, 64, false, true, () -> {
			month = !month;
			listScroll = 0;
		});
		// ---- counts
		int cx = ix;
		if (st == null || !st.hasData()) {
			Panels.text(g, this.font, "waiting for the Foreman", cx, iy + 26, muted);
		} else {
			List<SocialCalendar.Part> parts = SocialCalendar.counts(all, month ? null : weekStart);
			if (parts.isEmpty()) {
				Panels.text(g, this.font, "nothing planned yet · ask Nova to plan your week", cx, iy + 26, muted);
			}
			for (int i = 0; i < parts.size(); i++) {
				if (i > 0) {
					Panels.text(g, this.font, " · ", cx, iy + 26, muted);
					cx += this.font.width(" · ");
				}
				Panels.text(g, this.font, parts.get(i).text(), cx, iy + 26, SocialCalendar.statusInk(parts.get(i).status()));
				cx += this.font.width(parts.get(i).text());
			}
		}
		Panels.divider(g, ix, iy + 38, iw);

		// ---- footer
		int fy = iy + ih - BTN_H;
		int fx = ix;
		fx = button(g, "Talk to Nova", fx, fy, 100, false, true, () -> ConsoleFeature.open("@nova ", false)) + 4;
		fx = button(g, "Talk to Sable", fx, fy, 104, false, true, () -> ConsoleFeature.open("@sable ", false)) + 10;
		String hint = "Left/Right " + (month ? "month" : "week") + " · T today · M " + (month ? "week" : "month") + " · Esc close";
		Panels.text(g, this.font, TextUtil.ellipsize(this.font, hint, ix + iw - fx), fx, fy + 6, muted);

		// ---- body: calendar left, post right
		int bt = iy + 44;
		int bodyH = fy - 6 - bt;
		int lw = (int) (iw * 0.5);
		int dx = ix + lw + 10;
		int dw = iw - lw - 10;
		Panels.inset(g, ix, bt, lw, bodyH);
		listBox[0] = ix + 2;
		listBox[1] = bt + 2;
		listBox[2] = lw - 4;
		listBox[3] = bodyH - 4;
		if (month) {
			monthGrid(g, all, ix + 4, bt + 4, lw - 8, bodyH - 8);
		} else {
			weekList(g, all, ix + 4, bt + 4, lw - 8, bodyH - 8);
		}
		Panels.inset(g, dx, bt, dw, bodyH);
		detail(g, selected(), dx + 6, bt + 6, dw - 12, bodyH - 12);
	}

	private int button(GuiGraphicsExtractor g, String label, int x, int y, int w, boolean primary, boolean enabled, Runnable action) {
		boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BTN_H;
		Panels.button(g, this.font, label, x, y, w, primary, hover, !enabled);
		if (enabled) {
			hits.add(new Hit(x, y, w, BTN_H, action));
		}
		return x + w;
	}

	private void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
		g.fill(x, y, x + w, y + 1, argb);
		g.fill(x, y + h - 1, x + w, y + h, argb);
		g.fill(x, y, x + 1, y + h, argb);
		g.fill(x + w - 1, y, x + w, y + h, argb);
	}

	/** The platform badge: X = black with a light rim, LinkedIn = blue "in". */
	private void badge(GuiGraphicsExtractor g, String platform, int x, int y) {
		g.fill(x, y, x + 16, y + 12, SocialCalendar.platformColor(platform));
		if ("x".equals(platform)) {
			outline(g, x, y, 16, 12, 0xFFB8B0A3);
		}
		String t = SocialCalendar.badge(platform);
		g.text(this.font, t, x + (16 - this.font.width(t)) / 2, y + 2, WHITE, false);
	}

	// ------------------------------------------------------------------ week agenda

	private void weekList(GuiGraphicsExtractor g, List<SocialPost> all, int x, int y, int w, int h) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		LocalDate today = SocialCalendar.today();
		int cw = w - 6;
		int pos = y - listScroll;
		g.enableScissor(x - 2, y - 2, x + w + 2, y + h + 2);
		for (int d = 0; d < 7; d++) {
			LocalDate day = weekStart.plusDays(d);
			boolean isToday = day.equals(today);
			String head = SocialCalendar.day(day) + (isToday ? " · today" : "");
			Panels.text(g, this.font, head, x + 2, pos + 3, isToday ? UiStyle.color("monitor.attention", 0xFF844331) : d >= 5 ? muted : ink);
			pos += DAY_H;
			List<SocialPost> list = SocialCalendar.onDay(all, day, true);
			if (list.isEmpty()) {
				Panels.text(g, this.font, "nothing planned", x + 10, pos + 1, UiStyle.color("ink_ui.ghost_on_paper", 0xFFBCAD95));
				pos += 12;
			}
			for (SocialPost p : list) {
				chip(g, p, x, pos, cw);
				pos += ROW_H + 2;
			}
			pos += 4;
		}
		List<SocialPost> loose = SocialCalendar.unscheduled(all);
		if (!loose.isEmpty()) {
			Panels.text(g, this.font, "No time yet", x + 2, pos + 3, ink);
			pos += DAY_H;
			for (SocialPost p : loose) {
				chip(g, p, x, pos, cw);
				pos += ROW_H + 2;
			}
		}
		g.disableScissor();
		int content = pos + listScroll - y;
		int max = Math.max(0, content - h);
		listScroll = Math.min(listScroll, max);
		if (max > 0) {
			int thumb = Math.max(14, h * h / content);
			int ty = y + (int) ((long) (h - thumb) * listScroll / max);
			g.fill(x + w - 3, y, x + w, y + h, UiStyle.withAlpha(UiStyle.INK, 24));
			g.fill(x + w - 3, ty, x + w, ty + thumb, UiStyle.withAlpha(UiStyle.INK, 110));
		}
	}

	private void chip(GuiGraphicsExtractor g, SocialPost p, int x, int y, int w) {
		boolean visible = y + ROW_H > listBox[1] && y < listBox[1] + listBox[3];
		if (!visible) {
			return;
		}
		boolean sel = p.id().equals(selectedId);
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H && inBox(listBox, mouseX, mouseY);
		boolean dim = p.status().equals("cancelled");
		int sc = SocialCalendar.statusColor(p.status());
		int bg = DisplayDraw.mix(UiBits.panelHi(), sc, p.status().equals("posted") || dim ? 0.08f : 0.20f);
		g.fill(x, y, x + w, y + ROW_H, bg);
		g.fill(x, y, x + 3, y + ROW_H, sc);
		if (sel) {
			outline(g, x, y, w, ROW_H, UiBits.ink());
		} else if (hover) {
			outline(g, x, y, w, ROW_H, UiBits.muted());
		}
		badge(g, p.platform(), x + 8, y + 6);
		int text = dim || p.status().equals("posted") ? UiBits.muted() : UiBits.ink();
		String time = p.scheduledAt() == null ? "--:--" : SocialCalendar.time(p.scheduledAt());
		g.text(this.font, time, x + 30, y + 3, text, false);
		String word = SocialCalendar.statusLabel(p.status());
		int ww = this.font.width(word);
		g.text(this.font, word, x + w - 6 - ww, y + 3, SocialCalendar.statusInk(p.status()), false);
		g.text(this.font, TextUtil.ellipsize(this.font, SocialCalendar.oneLine(p.text()), w - 38), x + 30, y + 13, UiBits.muted(), false);
		listHits.add(new Hit(x, y, w, ROW_H, () -> select(p)));
	}

	// ------------------------------------------------------------------ month grid

	private void monthGrid(GuiGraphicsExtractor g, List<SocialPost> all, int x, int y, int w, int h) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		LocalDate today = SocialCalendar.today();
		LocalDate start = SocialCalendar.weekStart(monthFirst);
		int cw = w / 7;
		int head = 12;
		int rows = 6;
		int ch = Math.max(24, (h - head) / rows);
		for (int c = 0; c < 7; c++) {
			String name = DayOfWeek.of(c + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
			Panels.text(g, this.font, name, x + c * cw + (cw - this.font.width(name)) / 2, y + 2, muted);
		}
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < 7; c++) {
				LocalDate day = start.plusDays(r * 7L + c);
				int cx = x + c * cw;
				int cy = y + head + r * ch;
				boolean inMonth = day.getMonth() == monthFirst.getMonth();
				boolean isToday = day.equals(today);
				boolean sel = day.equals(selectedDay);
				boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + ch;
				g.fill(cx + 1, cy + 1, cx + cw - 1, cy + ch - 1, isToday ? UiStyle.withAlpha(UiStyle.color("monitor.attention", 0xFF844331), 46)
					: UiStyle.withAlpha(UiStyle.INK, inMonth ? 12 : 4));
				if (sel) {
					outline(g, cx + 1, cy + 1, cw - 2, ch - 2, ink);
				} else if (hover) {
					outline(g, cx + 1, cy + 1, cw - 2, ch - 2, muted);
				}
				String num = String.valueOf(day.getDayOfMonth());
				g.text(this.font, num, cx + 4, cy + 3, inMonth ? (isToday ? UiStyle.color("monitor.attention", 0xFF844331) : ink) : UiStyle.color("ink_ui.ghost_on_paper", 0xFFBCAD95),
					false);
				List<SocialPost> list = SocialCalendar.onDay(all, day, false);
				int shown = Math.min(list.size(), Math.max(1, (cw - 8) / 9));
				for (int i = 0; i < shown; i++) {
					SocialPost p = list.get(i);
					int dxp = cx + 4 + i * 9;
					g.fill(dxp, cy + ch - 12, dxp + 7, cy + ch - 5, SocialCalendar.statusColor(p.status()));
					if (p.isX()) {
						outline(g, dxp, cy + ch - 12, 7, 7, 0xFF000000);
					}
				}
				if (list.size() > shown) {
					g.text(this.font, "+" + (list.size() - shown), cx + cw - 4 - this.font.width("+" + (list.size() - shown)), cy + 3, muted, false);
				}
				LocalDate picked = day;
				hits.add(new Hit(cx, cy, cw, ch, () -> {
					selectedDay = picked;
					List<SocialPost> on = SocialCalendar.onDay(all, picked, true);
					if (!on.isEmpty()) {
						select(on.getFirst());
					}
					weekStart = SocialCalendar.weekStart(picked);
					month = false;
					listScroll = 0;
				}));
			}
		}
	}

	// ------------------------------------------------------------------ detail

	private void detail(GuiGraphicsExtractor g, @Nullable SocialPost p, int x, int y, int w, int h) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		textBox[0] = textBox[1] = textBox[2] = textBox[3] = 0;
		if (p == null) {
			int ly = y + 2;
			for (String line : TextUtil.wrapPlain(this.font, "Pick a post on the left to read it, approve it or move it.", w)) {
				Panels.text(g, this.font, line, x, ly, ink);
				ly += 11;
			}
			ly += 6;
			for (String line : TextUtil.wrapPlain(this.font, "Nova drafts the posts; nothing goes out until you approve it here. "
				+ "Sable posts the approved ones through your Chrome at their time.", w)) {
				Panels.text(g, this.font, line, x, ly, muted);
				ly += 11;
			}
			return;
		}
		int sink = SocialCalendar.statusInk(p.status());
		badge(g, p.platform(), x, y);
		Panels.text(g, this.font, SocialCalendar.platformName(p.platform()) + " post", x + 22, y + 2, ink);
		String word = SocialCalendar.statusLabel(p.status());
		Panels.text(g, this.font, word, x + w - this.font.width(word), y + 2, sink);
		int ly = y + 18;
		String when = p.scheduledAt() == null ? "no time yet" : SocialCalendar.dayTime(p.scheduledAt());
		String by = p.authorId() == null || p.authorId().isEmpty() ? "" : " · by " + UiBits.agentName(p.authorId());
		Panels.text(g, this.font, TextUtil.ellipsize(this.font, when + by, w), x, ly, p.scheduledAt() == null ? muted : ink);
		ly += 12;
		if (p.postedAt() != null) {
			Panels.text(g, this.font, "posted " + SocialCalendar.dayTime(p.postedAt()), x, ly, SocialCalendar.statusInk("posted"));
			ly += 12;
		}
		int len = (int) (editing ? edit.value() : p.text()).codePoints().count();
		String count = len + " / " + p.limit() + " characters";
		Panels.text(g, this.font, count, x, ly, len > p.limit() ? UiBits.errorText() : muted);
		ly += 14;

		// ---- bottom block: buttons, the flash line, then the extras (note, error, link, media)
		boolean canEdit = p.status().equals("draft") || p.status().equals("approved") || p.status().equals("failed");
		int buttonsH = editing ? BTN_H : 2 * BTN_H + 4;
		int by0 = y + h - buttonsH;
		int flashY = by0 - 24;
		List<String> extras = extras(p, w);
		int extrasH = extras.size() * 11 + (extras.isEmpty() ? 0 : 4);
		int textBottom = flashY - extrasH - 2;
		int textH = Math.max(24, textBottom - ly);
		if (editing) {
			int lines = Math.max(3, Math.min(14, (textH - TextFieldView.BASE_H) / TextFieldView.LINE + 1));
			fieldX = x;
			fieldY = ly;
			fieldW = w;
			fieldStyle = new TextFieldView.Style(null, 0, "the post text", null, null, 0, lines);
			editView.draw(g, this.font, edit, x, ly, w, true, fieldStyle);
		} else {
			// the post's images get the lower part of the box (most of it for a short post)
			int mediaH = p.mediaPaths().isEmpty() ? 0 : Math.max(40, Math.min(textH - 34, textH * 3 / 5));
			if (mediaH > 0) {
				textH -= mediaH + 4;
				drawMedia(g, p.mediaPaths(), x, ly + textH + 4, w, mediaH);
			}
			g.fill(x, ly, x + w, ly + textH, UiStyle.withAlpha(UiStyle.INK, 10));
			outline(g, x, ly, w, textH, UiStyle.withAlpha(UiStyle.INK, 40));
			List<String> lines = TextUtil.wrapPlain(this.font, p.text(), w - 14);
			int content = lines.size() * 10 + 8;
			int max = Math.max(0, content - textH);
			textScroll = Math.min(textScroll, max);
			textBox[0] = x;
			textBox[1] = ly;
			textBox[2] = w;
			textBox[3] = textH;
			g.enableScissor(x + 1, ly + 1, x + w - 1, ly + textH - 1);
			int tyy = ly + 4 - textScroll;
			for (String line : lines) {
				g.text(this.font, line, x + 6, tyy, p.status().equals("cancelled") ? muted : ink, false);
				tyy += 10;
			}
			g.disableScissor();
			if (max > 0) {
				int thumb = Math.max(10, textH * textH / content);
				int ty = ly + (int) ((long) (textH - thumb) * textScroll / max);
				g.fill(x + w - 4, ty, x + w - 1, ty + thumb, UiStyle.withAlpha(UiStyle.INK, 110));
			}
		}
		int ey = textBottom + 2 + 2;
		if (!extras.isEmpty()) {
			for (String line : extras) {
				int color = line.startsWith("✘") || line.startsWith("error") ? UiBits.errorText() : muted;
				Panels.text(g, this.font, line, x, ey, color);
				ey += 11;
			}
		}
		// the flash (an ack error stays long enough to read)
		String msg = flash;
		if (!msg.isEmpty() && System.currentTimeMillis() < flashUntil) {
			int fy = flashY;
			int n = 0;
			for (String line : TextUtil.wrapPlain(this.font, msg, w)) {
				if (n++ == 2) {
					break;
				}
				Panels.text(g, this.font, line, x, fy + n * 10 - 8, flashError ? UiBits.errorText() : UiBits.okText());
			}
		} else if (editing && !(p.scheduledAt() != null && p.scheduledAt() > System.currentTimeMillis())) {
			Panels.text(g, this.font, TextUtil.ellipsize(this.font, "Set a future time first (Esc, then +1h / +1d), then edit.", w), x, flashY, UiBits.errorText());
		}
		buttons(g, p, canEdit, x, by0, w);
	}

	private List<String> extras(SocialPost p, int w) {
		List<String> out = new ArrayList<>();
		if (p.error() != null && !p.error().isBlank()) {
			for (String l : TextUtil.wrapPlain(this.font, "error: " + p.error(), w)) {
				if (out.size() >= 3) {
					break;
				}
				out.add(l);
			}
		}
		if (p.note() != null && !p.note().isBlank() && out.size() < 3) {
			out.add(TextUtil.ellipsize(this.font, UiBits.agentName(p.authorId()) + ": " + p.note().strip(), w));
		}
		if (p.url() != null && !p.url().isBlank()) {
			out.add(TextUtil.ellipsize(this.font, p.url(), w));
		}
		if (!p.mediaPaths().isEmpty()) {
			List<String> names = new ArrayList<>();
			for (String m : p.mediaPaths()) {
				names.add(java.nio.file.Path.of(m).getFileName().toString());
			}
			out.add(TextUtil.ellipsize(this.font, "media: " + String.join(", ", names), w));
		}
		return out;
	}

	/** The post's attachments side by side, each scaled to fit its share of the strip. */
	private void drawMedia(GuiGraphicsExtractor g, List<String> paths, int x, int y, int w, int h) {
		int n = Math.min(4, paths.size());
		int gap = 4;
		int cellW = (w - gap * (n - 1)) / n;
		for (int i = 0; i < n; i++) {
			int cx = x + i * (cellW + gap);
			g.fill(cx, y, cx + cellW, y + h, UiStyle.withAlpha(UiStyle.INK, 18));
			outline(g, cx, y, cellW, h, UiStyle.withAlpha(UiStyle.INK, 40));
			String path = paths.get(i);
			LocalImages.State st = LocalImages.get(path);
			LocalImages.Shown img = st.shown();
			if (img != null) {
				double scale = Math.min((cellW - 4) / (double) img.w(), (h - 4) / (double) img.h());
				int iw = Math.max(1, (int) (img.w() * scale));
				int ih = Math.max(1, (int) (img.h() * scale));
				int ix = cx + (cellW - iw) / 2;
				int iy = y + (h - ih) / 2;
				g.blit(RenderPipelines.GUI_TEXTURED, img.texture(), ix, iy, 0, 0, iw, ih, img.w(), img.h(), img.w(), img.h());
			} else {
				String label = st.loading() ? "loading\u2026" : st.error() != null ? st.error() : "";
				String name = java.nio.file.Path.of(path).getFileName().toString();
				int my = y + h / 2 - 10;
				Panels.text(g, this.font, TextUtil.ellipsize(this.font, name, cellW - 8), cx + 4, my, UiBits.ink());
				Panels.text(g, this.font, TextUtil.ellipsize(this.font, label, cellW - 8), cx + 4, my + 11, UiBits.muted());
			}
		}
		if (paths.size() > n) {
			String more = "+" + (paths.size() - n);
			Panels.text(g, this.font, more, x + w - this.font.width(more) - 4, y + h - 11, UiBits.muted());
		}
	}

	private void buttons(GuiGraphicsExtractor g, SocialPost p, boolean canEdit, int x, int y, int w) {
		long now = System.currentTimeMillis();
		boolean future = p.scheduledAt() != null && p.scheduledAt() > now;
		String id = p.id();
		if (editing) {
			String text = edit.value();
			int len = (int) text.codePoints().count();
			boolean ok = !text.isBlank() && len <= p.limit() && future;
			int bw = (w - 8) / 3;
			int bx = x;
			bx = button(g, "Save", bx, y, bw, false, ok, () -> send(id, "schedule", p.scheduledAt(), text, "Saved as a draft", true)) + 4;
			bx = button(g, "Save & approve", bx, y, bw, true, ok, () -> send(id, "approve", null, text, "Approved", true)) + 4;
			button(g, "Discard", bx, y, w - (bx - x), false, true, () -> editing = false);
			return;
		}
		boolean armedNow = "post_now".equals(confirm) && now < confirmUntil;
		boolean armedCancel = "cancel".equals(confirm) && now < confirmUntil;
		boolean open = canEdit;
		int bw = (w - 12) / 4;
		int bx = x;
		bx = button(g, "Approve", bx, y, bw, true, (p.status().equals("draft") || p.status().equals("failed")) && future,
			() -> send(id, "approve", null, null, "Approved: it goes out " + SocialCalendar.dayTime(p.scheduledAt()), false)) + 4;
		bx = button(g, "Unapprove", bx, y, bw, false, p.status().equals("approved"), () -> send(id, "unapprove", null, null, "Back to draft", false)) + 4;
		bx = button(g, armedNow ? "Sure? Post now" : "Post now", bx, y, bw, false, open,
			() -> confirmed("post_now", () -> send(id, "post_now", null, null, "Posting now", false))) + 4;
		button(g, armedCancel ? "Sure? Cancel" : "Cancel post", bx, y, w - (bx - x), false, open,
			() -> confirmed("cancel", () -> send(id, "cancel", null, null, "Cancelled", false)));
		int y2 = y + BTN_H + 4;
		bx = x;
		bx = button(g, "-1h", bx, y2, bw, false, canEdit && p.scheduledAt() != null, () -> move(p, -HOUR)) + 4;
		bx = button(g, "+1h", bx, y2, bw, false, canEdit, () -> move(p, HOUR)) + 4;
		bx = button(g, "+1d", bx, y2, bw, false, canEdit, () -> move(p, DAY)) + 4;
		button(g, "Edit text", bx, y2, w - (bx - x), false, canEdit, () -> startEdit(p));
	}
}

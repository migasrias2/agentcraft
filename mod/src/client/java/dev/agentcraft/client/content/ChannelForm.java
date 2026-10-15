package dev.agentcraft.client.content;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;

/**
 * The "+ Channel" form: name, YouTube URL or handle, niche, a brief on voice and audience, posts a week. Drawn as
 * a modal card over the Studio; Tab moves between the fields.
 */
final class ChannelForm {
	private final DarkField name = new DarkField("Channel name", 80, 1);
	private final DarkField url = new DarkField("https://www.youtube.com/@handle (or leave empty)", 200, 1);
	private final DarkField niche = new DarkField("Niche: what the channel is about", 120, 1);
	private final DarkField brief = new DarkField("Brief: voice, audience, rules, things to avoid", 2000, 4);
	private final DarkField perWeek = new DarkField("35", 2, 1);
	private final DarkField[] order = {name, url, niche, brief, perWeek};

	/** Draws the card centred in the screen and registers its buttons; {@code onClose} closes the form. */
	void render(Canvas c, int sw, int sh, Runnable onClose) {
		c.hit(0, 0, sw, sh, () -> {
		});
		int w = Math.min(460, sw - 32);
		int h = 250;
		int x = (sw - w) / 2;
		int y = Math.max(8, (sh - h) / 2);
		c.fill(0, 0, sw, sh, 0xB0000000);
		c.card(x, y, w, h, Canvas.PANEL);
		c.hit(x, y, w, h, () -> {
		});
		c.text("New channel", x + 14, y + 12, Canvas.TEXT);
		int fx = x + 14;
		int fw = w - 28;
		int cy = y + 30;
		cy += labelled(c, "NAME", name, fx, cy, fw) + 6;
		cy += labelled(c, "URL OR HANDLE", url, fx, cy, fw) + 6;
		cy += labelled(c, "NICHE", niche, fx, cy, fw) + 6;
		cy += labelled(c, "BRIEF", brief, fx, cy, fw) + 6;
		cy += labelled(c, "POSTS A WEEK (35 = 5 A DAY)", perWeek, fx, cy, 60) + 6;
		boolean ok = !name.isBlank();
		int by = y + h - 32;
		c.button("Cancel", x + w - 14 - 70 - 4 - 70, by, 70, 20, Canvas.Kind.SECONDARY, true, onClose);
		c.button("Create", x + w - 14 - 70, by, 70, 20, Canvas.Kind.PRIMARY, ok, () -> submit(c.actions, onClose));
		if (c.focus == null) {
			c.focus = name;
		}
	}

	private static int labelled(Canvas c, String label, DarkField f, int x, int y, int w) {
		c.text(label, x, y, Canvas.FAINT);
		return 11 + c.field(f, x, y + 10, w) - 0;
	}

	private void submit(ContentActions act, Runnable onClose) {
		Integer n = null;
		String raw = perWeek.value().strip();
		if (!raw.isEmpty()) {
			try {
				n = Math.max(1, Math.min(70, Integer.parseInt(raw)));
			} catch (NumberFormatException e) {
				act.flash("Posts a week must be a number from 1 to 70 (35 = 5 a day)", true);
				return;
			}
		}
		act.channel(null, "add", name.value().strip(), blankToNull(url.value()), blankToNull(niche.value()), blankToNull(brief.value()), n, "Channel added",
			onClose);
	}

	private static String blankToNull(String s) {
		return s.isBlank() ? null : s.strip();
	}

	/** Tab / Shift+Tab cycle the fields; anything else goes to the focused field. */
	boolean key(Canvas c, Font font, KeyEvent e) {
		if (e.key() == InputConstants.KEY_TAB) {
			int i = 0;
			for (int k = 0; k < order.length; k++) {
				if (order[k] == c.focus) {
					i = k;
				}
			}
			c.focus = order[(i + (e.hasShiftDown() ? order.length - 1 : 1)) % order.length];
			return true;
		}
		return c.focus != null && c.focus.key(font, e);
	}
}

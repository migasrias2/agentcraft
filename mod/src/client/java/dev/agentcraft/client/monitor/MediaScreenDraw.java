package dev.agentcraft.client.monitor;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.WorldUi;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.jspecify.annotations.Nullable;

/**
 * The Screening Room's big screen (a monitor panel bound to {@code media}): the newest gallery item
 * scaled to fit, a video cycling its frames at its fps (looping), and a caption bar along the bottom
 * (agent name, caption or file name, "video" / "image" and its age). Empty gallery: a hint. Drawn
 * every frame from {@link MonitorRenderer}; the textures come from {@link MediaStore}.
 */
final class MediaScreenDraw {
	/** Monitor binding of the Screening Room's big screen. */
	static final String BINDING = "media";

	private static final float Z_IMAGE = DisplayDraw.Z_STEP;
	private static final float Z_FRAME = 0.5f * DisplayDraw.Z_STEP;
	private static final float Z_BAR = 6 * DisplayDraw.Z_STEP;
	private static final float Z_BAR_TEXT = 7 * DisplayDraw.Z_STEP;
	static final String EMPTY_1 = "No media yet";
	static final String EMPTY_2 = "Agents hand you images and videos with show_media";

	private MediaScreenDraw() {
	}

	static void draw(long nowMs, MonitorScreen m, PoseStack ps, SubmitNodeCollector c, Font font, int light) {
		ScreenStyle st = m.style;
		float x0 = m.bx0 + 3;
		float y0 = m.by0 + 3;
		float x1 = m.bx1 - 3;
		float y1 = m.by1 - 3;
		MediaStore.Clip clip = MediaStore.latest();
		if (clip == null) {
			centred(ps, c, font, light, m, EMPTY_1, EMPTY_2, st.text(), st.muted());
			return;
		}
		MediaStore.Shown shown = MediaStore.frameFor(clip, nowMs);
		if (shown == null) {
			String msg = clip.failed() ? "This one can't be shown here" : "Loading…";
			centred(ps, c, font, light, m, msg, clip.failed() ? "press the right mouse button to open it, or ask the agent for a PNG/JPEG" : "", st.text(),
				st.muted());
			return;
		}
		float aw = x1 - x0;
		float ah = y1 - y0;
		float scale = Math.min(aw / shown.w(), ah / shown.h());
		float w = shown.w() * scale;
		float h = shown.h() * scale;
		float x = x0 + (aw - w) / 2f;
		float y = y0 + (ah - h) / 2f;
		DisplayDraw.Rects frame = m.imgFrame.clear();
		frame.add(x - 1, y - 1, x + w + 1, y + h + 1, Z_FRAME, st.rule(), light);
		frame.submit(ps, c);
		DisplayDraw.submitTexture(ps, c, shown.texture(), x, y, w, h, Z_IMAGE, 0xFFFFFFFF, light);
		caption(nowMs, m, clip, x, y, w, h, ps, c, font, light);
	}

	private static void caption(long nowMs, MonitorScreen m, MediaStore.Clip clip, float x, float y, float w, float h, PoseStack ps,
		SubmitNodeCollector c, Font font, int light) {
		ScreenStyle st = m.style;
		float barH = 13;
		float by = y + h - barH;
		DisplayDraw.Rects bar = m.mediaBar.clear();
		bar.add(x, by, x + w, y + h, Z_BAR, DisplayDraw.mulAlpha(st.badgeBg(), 225), light);
		bar.submit(ps, c);
		String who = UiBits.agentName(clip.meta.agentId());
		String what = what(clip);
		String right = right(nowMs, clip);
		int rightW = font.width(right);
		int whoW = font.width(who);
		ps.pushPose();
		ps.translate(0, 0, Z_BAR_TEXT);
		float tx = x + 4;
		float ty = by + 2.5f;
		WorldUi.submitText(ps, c, who, tx, ty, st.name(clip.meta.agentId()), light);
		float room = w - 8 - whoW - 8 - rightW - 6;
		if (room > 24) {
			WorldUi.submitText(ps, c, TextUtil.ellipsize(font, what, (int) room), tx + whoW + 8, ty, st.badgeText(), light);
		}
		WorldUi.submitText(ps, c, right, x + w - 4 - rightW, ty, st.muted(), light);
		ps.popPose();
		// gallery hint chip, top-right of the image
		int n = MediaStore.size();
		String hint = n > 1 ? "right-click: browse (" + n + ")" : "right-click: open";
		float cw = font.width(hint) + 8;
		if (cw + 8 < w) {
			DisplayDraw.Rects chip = m.mediaChip.clear();
			chip.add(x + w - cw - 3, y + 3, x + w - 3, y + 14, Z_BAR, DisplayDraw.mulAlpha(st.badgeBg(), 200), light);
			chip.submit(ps, c);
			ps.pushPose();
			ps.translate(0, 0, Z_BAR_TEXT);
			WorldUi.submitText(ps, c, hint, x + w - cw + 1, y + 4.5f, st.muted(), light);
			ps.popPose();
		}
	}

	static String what(MediaStore.Clip clip) {
		String cap = clip.meta.caption();
		return cap != null && !cap.isBlank() ? cap.strip() : clip.meta.name();
	}

	/** "video 0:04 . 3m ago" / "image . just now" (with a paused marker). */
	static String right(long nowMs, MediaStore.Clip clip) {
		String age = UiBits.ago(clip.meta.at());
		if (!clip.isVideo()) {
			return "image · " + age;
		}
		long ms = clip.meta.durationMs() != null ? clip.meta.durationMs() : clip.loopMs();
		String len = ms > 0 ? " " + clock(ms) : "";
		return (clip.paused ? "⏸ paused" : "▶ video") + len + " · " + age;
	}

	static String clock(long ms) {
		long s = Math.max(0, ms / 1000);
		return (s / 60) + ":" + String.format("%02d", s % 60);
	}

	private static void centred(PoseStack ps, SubmitNodeCollector c, Font font, int light, MonitorScreen m, String l1, @Nullable String l2, int c1,
		int c2) {
		float cy = (m.by0 + m.by1) / 2f - 10;
		ps.pushPose();
		ps.translate(0, 0, MonitorRenderer.Z_TEXT);
		WorldUi.submitText(ps, c, l1, (m.bx0 + m.bx1 - font.width(l1)) / 2f, cy, c1, light);
		if (l2 != null && !l2.isEmpty()) {
			String t = TextUtil.ellipsize(font, l2, (int) (m.bx1 - m.bx0 - 16));
			WorldUi.submitText(ps, c, t, (m.bx0 + m.bx1 - font.width(t)) / 2f, cy + 12, c2, light);
		}
		ps.popPose();
	}
}

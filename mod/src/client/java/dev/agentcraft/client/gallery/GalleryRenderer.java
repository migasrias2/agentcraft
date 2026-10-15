package dev.agentcraft.client.gallery;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.agentcraft.client.monitor.DisplayDraw;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.WorldUi;
import dev.agentcraft.hq.GalleryLayout;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the paintings in the world: for each bay a gilt-and-walnut frame around the image (kept at its own
 * aspect ratio, as large as the bay allows), a cream mat and a brass nameplate under it. Everything is
 * full-bright geometry in an upright "pixel plane" laid on the wall (100 px to the block, x to the viewer's
 * right, y down, +z towards the viewer), in the style of the casino's table painter. The lectern gets a small
 * floating label with the page. Bays facing away from the camera are skipped.
 */
final class GalleryRenderer {
	private static final float PX = 0.01f;
	private static final double RANGE = 48;
	private static final double LABEL_RANGE = 14;
	private static final float MAT = 5;
	private static final float GOLD = 2;
	private static final float WOOD = 7;
	private static final int PLATE_TEXT = 0xFF2B1B08;
	private static final int PLATE_SUB = 0xFF5A4318;

	private static final DisplayDraw.Rects[] FRAMES = rects();
	private static final DisplayDraw.Rects[] PLATES = rects();

	private GalleryRenderer() {
	}

	private static DisplayDraw.Rects[] rects() {
		DisplayDraw.Rects[] r = new DisplayDraw.Rects[GalleryLayout.PAGE_SIZE];
		for (int i = 0; i < r.length; i++) {
			r[i] = new DisplayDraw.Rects();
		}
		return r;
	}

	static void render(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			GalleryTextures.releaseAll();
			return;
		}
		double cx = GalleryLayout.centreX();
		double cz = GalleryLayout.centreZ();
		double dx = mc.player.getX() - cx;
		double dz = mc.player.getZ() - cz;
		boolean near = dx * dx + dz * dz <= RANGE * RANGE;
		GalleryModel.tick(System.currentTimeMillis(), near);
		if (!near) {
			return;
		}
		List<GalleryItem> page = GalleryModel.pageItems();
		GalleryTextures.trim(page.size());
		PoseStack ps = ctx.poseStack();
		SubmitNodeCollector collector = ctx.submitNodeCollector();
		CameraRenderState camera = ctx.levelState().cameraRenderState;
		Vec3 cam = camera.pos;
		int light = WorldUi.uiLight();
		for (int i = 0; i < page.size(); i++) {
			GalleryLayout.Slot slot = GalleryLayout.SLOTS.get(i);
			GalleryTextures.Shown shown = GalleryTextures.shown(i, page.get(i));
			if (shown != null && facesCamera(slot, cam)) {
				paint(ps, collector, cam, slot, shown, page.get(i), i, light);
			}
		}
		label(ps, collector, camera, page.size(), light);
	}

	private static boolean facesCamera(GalleryLayout.Slot s, Vec3 cam) {
		return (cam.x - s.x()) * s.facing().getStepX() + (cam.z - s.z()) * s.facing().getStepZ() > 0;
	}

	/** Yaw that turns the plane's +x to the bay's "right" and its +z to the room (0 = facing south). */
	private static float yaw(Direction facing) {
		return switch (facing) {
			case EAST -> (float) (Math.PI / 2);
			case NORTH -> (float) Math.PI;
			case WEST -> (float) (-Math.PI / 2);
			default -> 0f;
		};
	}

	private static void paint(PoseStack ps, SubmitNodeCollector c, Vec3 cam, GalleryLayout.Slot s, GalleryTextures.Shown img, GalleryItem item,
		int index, int light) {
		Font font = Minecraft.getInstance().font;
		float boxW = (float) (s.maxW() * 100);
		float boxH = (float) (s.maxH() * 100);
		float scale = Math.min(boxW / img.w(), boxH / img.h());
		float hx = img.w() * scale / 2f;
		float hy = img.h() * scale / 2f;
		float edge = MAT + GOLD + WOOD;
		float fw = hx + edge;
		float fh = hy + edge;

		ps.pushPose();
		ps.translate(s.x() - cam.x + s.facing().getStepX() * 0.02, s.y() - cam.y, s.z() - cam.z + s.facing().getStepZ() * 0.02);
		ps.rotate(Axis.YP.rotation(yaw(s.facing())));
		ps.scale(PX, -PX, PX);

		DisplayDraw.Rects frame = FRAMES[index].clear();
		frame.add(-fw - 1, -fh - 1, fw + 1, fh + 1, 0f, 0xFF1E140C, light);
		frame.add(-fw, -fh, fw, fh, 0.3f, 0xFF86593A, 0xFF4A2D18, light);
		frame.add(-fw + WOOD, -fh + WOOD, fw - WOOD, fh - WOOD, 0.9f, 0xFFE9CC70, 0xFFB08B30, light);
		frame.add(-hx - MAT, -hy - MAT, hx + MAT, hy + MAT, 1.5f, 0xFFF3EEE0, light);
		frame.add(-hx - 1, -hy - 1, hx + 1, hy + 1, 1.8f, 0xFFB8AF98, light);
		frame.submit(ps, c);
		DisplayDraw.submitTexture(ps, c, img.texture(), -hx, -hy, hx * 2, hy * 2, 2.2f, 0xFFFFFFFF, light);

		plate(ps, c, font, item, fw, fh + 7, PLATES[index].clear(), light);
		ps.popPose();
	}

	/** Brass nameplate hanging under the frame: title, and a smaller line for who/what. */
	private static void plate(PoseStack ps, SubmitNodeCollector c, Font font, GalleryItem item, float frameHalfW, float top,
		DisplayDraw.Rects rects, int light) {
		float k = 2.0f;
		float ks = 1.25f;
		float maxW = frameHalfW * 2 - 16;
		String title = TextUtil.ellipsize(font, item.title(), (int) (maxW / k));
		String sub = item.subtitle().isEmpty() ? "" : TextUtil.ellipsize(font, item.subtitle(), (int) (maxW / ks));
		float tw = font.width(title) * k;
		float sw = font.width(sub) * ks;
		float w = Math.min(frameHalfW * 2, Math.max(tw, sw) + 18);
		float h = sub.isEmpty() ? 23 : 37;
		rects.add(-w / 2 - 1, top - 1, w / 2 + 1, top + h + 1, 0.6f, 0xFF4B3510, light);
		rects.add(-w / 2, top, w / 2, top + h, 0.9f, 0xFFD9B766, 0xFFAD8838, light);
		rects.submit(ps, c);
		text(ps, c, title, -tw / 2, top + 3.5f, k, PLATE_TEXT, light);
		if (!sub.isEmpty()) {
			text(ps, c, sub, -sw / 2, top + 23.5f, ks, PLATE_SUB, light);
		}
	}

	private static void text(PoseStack ps, SubmitNodeCollector c, String s, float x, float y, float k, int argb, int light) {
		ps.pushPose();
		ps.translate(x, y, 1.6f);
		ps.scale(k, k, 1f);
		WorldUi.submitText(ps, c, s, 0, 0, argb, light);
		ps.popPose();
	}

	/** The lectern's floating label: where we are in the gallery and how to turn the page. */
	private static void label(PoseStack ps, SubmitNodeCollector c, CameraRenderState camera, int onPage, int light) {
		double lx = GalleryLayout.LECTERN_X + 0.5;
		double ly = GalleryLayout.FLOOR_Y + 1.7;
		double lz = GalleryLayout.LECTERN_Z + 0.5;
		Vec3 cam = camera.pos;
		double d2 = (lx - cam.x) * (lx - cam.x) + (lz - cam.z) * (lz - cam.z);
		if (d2 > LABEL_RANGE * LABEL_RANGE) {
			return;
		}
		int total = GalleryModel.items().size();
		String top = total == 0 ? "Gallery is empty" : "Gallery · page " + (GalleryModel.page() + 1) + " / " + GalleryModel.pages() + " · " + total
			+ (total == 1 ? " painting" : " paintings");
		String bottom = total == 0 ? "Put images in Pictures/AgentCraft Galeria" : GalleryModel.pages() > 1 ? "Right-click to turn the page"
			: "Right-click a painting to see it big";
		Font font = Minecraft.getInstance().font;
		ps.pushPose();
		WorldUi.billboard(ps, camera, lx - cam.x, ly - cam.y, lz - cam.z);
		ps.scale(0.55f, 0.55f, 0.55f);
		int w = Math.max(font.width(top), font.width(bottom));
		WorldUi.submitNineSlice(ps, c, WorldUi.Layer.SOLID, Kit.NAMEPLATE, -w / 2f - 4, -22, w + 8, 22, 0xFFFFFFFF, light);
		WorldUi.submitText(ps, c, top, -font.width(top) / 2f, -19, 0xFF2B2418, light);
		WorldUi.submitText(ps, c, bottom, -font.width(bottom) / 2f, -9, 0xFF6B5E45, light);
		ps.popPose();
	}
}

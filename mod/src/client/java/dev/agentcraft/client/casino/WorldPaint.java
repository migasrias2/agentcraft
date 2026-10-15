package dev.agentcraft.client.casino;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.WorldUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Drawing the blackjack table in the world with the same primitives as the mod's other world UI:
 * coloured rectangles and text in a "pixel plane" that is laid flat on the felt (cards, chips) or
 * stands upright (chip edges, the shoe), plus camera-facing label plates. Everything is full-bright
 * (the lounge is dim on purpose) and depth-tested; layers inside one plane are lifted by tiny steps.
 *
 * <p>The flat plane maps pixel x to world east and pixel y (down) to world south, so that text reads
 * upright for a player sitting south of the table; 100 pixels are one block.
 */
final class WorldPaint {
	static final float PIXEL = 0.01f;
	private static final float LAYER = 0.2f;

	private final PoseStack ps;
	private final SubmitNodeCollector collector;
	private final CameraRenderState camera;
	private final Vec3 cam;
	private final int light = WorldUi.uiLight();

	WorldPaint(PoseStack ps, SubmitNodeCollector collector, CameraRenderState camera) {
		this.ps = ps;
		this.collector = collector;
		this.camera = camera;
		this.cam = camera.pos;
	}

	// ------------------------------------------------------------------ planes

	/** Runs {@code body} in a plane lying on the table at the world point, turned by yaw and rolled about the plane's long (north-south) axis. */
	void flat(double wx, double wy, double wz, float yaw, float roll, float scale, Runnable body) {
		ps.pushPose();
		ps.translate(wx - cam.x, wy - cam.y, wz - cam.z);
		ps.rotate(Axis.YP.rotation(yaw));
		ps.rotate(Axis.ZP.rotation(roll));
		ps.rotate(Axis.XP.rotationDegrees(-90));
		ps.scale(scale, -scale, scale);
		body.run();
		ps.popPose();
	}

	/** Runs {@code body} in an upright plane facing south (yaw 0), north (pi), east (pi/2) or west (-pi/2). */
	void upright(double wx, double wy, double wz, float yaw, float scale, Runnable body) {
		ps.pushPose();
		ps.translate(wx - cam.x, wy - cam.y, wz - cam.z);
		ps.rotate(Axis.YP.rotation(yaw));
		ps.scale(scale, -scale, scale);
		body.run();
		ps.popPose();
	}

	// ------------------------------------------------------------------ primitives (inside a plane)

	void rect(float x0, float y0, float x1, float y1, int argb, int layer) {
		ps.pushPose();
		ps.translate(0, 0, layer * LAYER);
		collector.order(1).submitTextBackground(ps, x0, y0, x1, y1, argb, Font.DisplayMode.NORMAL, light);
		ps.popPose();
	}

	void text(String s, float x, float y, int argb, int layer) {
		ps.pushPose();
		ps.translate(0, 0, layer * LAYER);
		collector.order(1).submitText(ps, x, y, Component.literal(s).getVisualOrderText(), false, Font.DisplayMode.POLYGON_OFFSET, light, argb, 0, 0);
		ps.popPose();
	}

	/** A rectangle with its four corner pixels cut. */
	void rounded(float x0, float y0, float x1, float y1, int argb, int layer) {
		rect(x0 + 1, y0, x1 - 1, y1, argb, layer);
		rect(x0, y0 + 1, x1, y1 - 1, argb, layer);
	}

	/** A suit symbol of 7 x 7 cells of {@code cell} pixels. */
	void suit(Blackjack.Suit suit, float x, float y, int cell, int argb, int layer) {
		String[] rows = CardArt.bitmap(suit);
		for (int row = 0; row < rows.length; row++) {
			String line = rows[row];
			int col = 0;
			while (col < line.length()) {
				if (line.charAt(col) != '#') {
					col++;
					continue;
				}
				int end = col;
				while (end < line.length() && line.charAt(end) == '#') {
					end++;
				}
				rect(x + col * cell, y + row * cell, x + end * cell, y + (row + 1) * cell, argb, layer);
				col = end;
			}
		}
	}

	// ------------------------------------------------------------------ cards, chips, boxes

	/** A 30 x 42 px card centred on the plane origin, front or back. */
	void card(Blackjack.Card card, boolean faceUp) {
		if (!faceUp) {
			rounded(-15, -21, 15, 21, CardArt.CARD_EDGE, 0);
			rounded(-14, -20, 14, 20, 0xFFF4EFE6, 1);
			rect(-12, -18, 12, 18, 0xFF9B3A28, 2);
			for (int yy = -15; yy < 15; yy += 6) {
				for (int xx = -9 + ((yy + 15) / 6 % 2) * 3; xx < 9; xx += 6) {
					rect(xx, yy, xx + 3, yy + 3, 0xFFC9724F, 3);
				}
			}
			return;
		}
		rounded(-15, -21, 15, 21, CardArt.CARD_EDGE, 0);
		rounded(-14, -20, 14, 20, CardArt.CARD_FACE, 1);
		int ink = card.suit().red() ? CardArt.RED : CardArt.BLACK;
		String label = card.label();
		text(label, -12, -18, ink, 2);
		suit(card.suit(), -13, -9, 1, ink, 2);
		suit(card.suit(), -6, -3, 2, ink, 2);
		int w = Minecraft.getInstance().font.width(label);
		text(label, 12 - w, 12, ink, 2);
	}

	/** One chip lying at the world point: an octagon (top) and, if {@code edge}, the south-facing rim. */
	void chip(double wx, double wy, double wz, int argb, boolean detail) {
		flat(wx, wy, wz, 0, 0, PIXEL, () -> {
			rect(-4, -6, 4, 6, argb, 0);
			rect(-6, -4, 6, 4, argb, 0);
			rect(-5, -5, 5, 5, argb, 0);
			if (detail) {
				rect(-3, -4, 3, 4, 0xFFF4EFE6, 1);
				rect(-4, -3, 4, 3, 0xFFF4EFE6, 1);
				rect(-2, -3, 2, 3, argb, 2);
				rect(-3, -2, 3, 2, argb, 2);
			}
		});
		upright(wx, wy - 0.011, wz + 0.06, 0, PIXEL, () -> rect(-5, -1, 5, 1, darker(argb), 0));
	}

	private static int darker(int argb) {
		int r = (argb >> 16 & 0xFF) * 3 / 4;
		int g = (argb >> 8 & 0xFF) * 3 / 4;
		int b = (argb & 0xFF) * 3 / 4;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** A small solid box (top and four sides) standing on the point (bottom centre). */
	void box(double wx, double wy, double wz, double sx, double sy, double sz, int top, int side, int sideDark) {
		float hx = (float) (sx * 50);
		float hy = (float) (sy * 50);
		float hz = (float) (sz * 50);
		flat(wx, wy + sy, wz, 0, 0, PIXEL, () -> rect(-hx, -hz, hx, hz, top, 0));
		upright(wx, wy + sy / 2, wz + sz / 2, 0, PIXEL, () -> rect(-hx, -hy, hx, hy, side, 0));
		upright(wx, wy + sy / 2, wz - sz / 2, (float) Math.PI, PIXEL, () -> rect(-hx, -hy, hx, hy, side, 0));
		upright(wx + sx / 2, wy + sy / 2, wz, (float) (Math.PI / 2), PIXEL, () -> rect(-hz, -hy, hz, hy, sideDark, 0));
		upright(wx - sx / 2, wy + sy / 2, wz, (float) (-Math.PI / 2), PIXEL, () -> rect(-hz, -hy, hz, hy, sideDark, 0));
	}

	// ------------------------------------------------------------------ labels

	/** A camera-facing nameplate-style label centred above the world point. {@code scale} 1 = nameplate size. */
	void label(String text, double wx, double wy, double wz, float scale, int argb) {
		Font font = Minecraft.getInstance().font;
		ps.pushPose();
		WorldUi.billboard(ps, camera, wx - cam.x, wy - cam.y, wz - cam.z);
		ps.scale(scale, scale, scale);
		int w = font.width(text);
		WorldUi.submitNineSlice(ps, collector, WorldUi.Layer.SOLID, Kit.NAMEPLATE, -w / 2f - 4, -11, w + 8, 12, 0xFFFFFFFF, light);
		WorldUi.submitText(ps, collector, text, -w / 2f, -9, argb, light);
		ps.popPose();
	}

	/** Floating text without a plate (the result line); {@code argb} may carry alpha. */
	void floating(String text, double wx, double wy, double wz, float scale, int argb) {
		Font font = Minecraft.getInstance().font;
		ps.pushPose();
		WorldUi.billboard(ps, camera, wx - cam.x, wy - cam.y, wz - cam.z);
		ps.scale(scale, scale, scale);
		int w = font.width(text);
		WorldUi.submitText(ps, collector, text, -w / 2f, -4, argb, light);
		ps.popPose();
	}
}

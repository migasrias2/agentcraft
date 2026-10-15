package dev.agentcraft.client.monitor;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * Pure image helpers for live screen frames (no Minecraft types, safe on any thread): base64 ->
 * ARGB pixels via ImageIO (PNG, JPEG, GIF) and an area-average downscale done by hand, so no AWT
 * graphics context is ever created (AWT and GLFW do not mix well on macOS).
 */
final class ScreenImages {
	/** Decoded pixels, 0xAARRGGBB, row-major. */
	record Pixels(int w, int h, int[] argb) {
	}

	private ScreenImages() {
	}

	static {
		ImageIO.setUseCache(false);
	}

	/** Decode a base64 image; null when the format is unsupported (e.g. WebP) or the data is broken. */
	static @Nullable Pixels decode(String base64) {
		try {
			return decodeBytes(Base64.getMimeDecoder().decode(base64));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Decode encoded image bytes (PNG, JPEG, GIF); null when unsupported or broken. */
	static @Nullable Pixels decodeBytes(byte[] bytes) {
		try {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
			if (img == null || img.getWidth() < 1 || img.getHeight() < 1) {
				return null;
			}
			int w = img.getWidth();
			int h = img.getHeight();
			int[] px = img.getRGB(0, 0, w, h, null, 0, w);
			for (int i = 0; i < px.length; i++) {
				px[i] |= 0xFF000000; // screenshots are opaque
			}
			return new Pixels(w, h, px);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Fit inside maxW x maxH keeping the aspect ratio, never upscaling. Returns {w, h}. */
	static int[] fit(int w, int h, int maxW, int maxH) {
		double s = Math.min(1.0, Math.min(maxW / (double) w, maxH / (double) h));
		return new int[] {Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s))};
	}

	/** Area-average downscale to fit maxW x maxH (returns {@code src} itself when it already fits). */
	static Pixels downscale(Pixels src, int maxW, int maxH) {
		int[] d = fit(src.w(), src.h(), maxW, maxH);
		int dw = d[0];
		int dh = d[1];
		if (dw >= src.w() && dh >= src.h()) {
			return src;
		}
		int[] out = new int[dw * dh];
		for (int y = 0; y < dh; y++) {
			int sy0 = (int) ((long) y * src.h() / dh);
			int sy1 = Math.max(sy0 + 1, (int) ((long) (y + 1) * src.h() / dh));
			for (int x = 0; x < dw; x++) {
				int sx0 = (int) ((long) x * src.w() / dw);
				int sx1 = Math.max(sx0 + 1, (int) ((long) (x + 1) * src.w() / dw));
				long r = 0, g = 0, b = 0;
				int n = 0;
				for (int sy = sy0; sy < sy1; sy++) {
					int row = sy * src.w();
					for (int sx = sx0; sx < sx1; sx++) {
						int p = src.argb()[row + sx];
						r += (p >> 16) & 0xFF;
						g += (p >> 8) & 0xFF;
						b += p & 0xFF;
						n++;
					}
				}
				out[y * dw + x] = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
			}
		}
		return new Pixels(dw, dh, out);
	}

	/** Short label for the tool that made a frame: {@code mcp__claude-in-chrome__computer} -> "browser". */
	static String sourceLabel(String tool) {
		if (tool == null || !tool.startsWith("mcp__")) {
			return "screen";
		}
		String[] parts = tool.split("__");
		String server = parts.length > 1 ? parts[1] : "";
		String lower = server.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("chrome") || lower.contains("browser") || lower.contains("playwright") || lower.contains("puppeteer")) {
			return "browser";
		}
		String s = server.replace('_', ' ').replace('-', ' ').trim();
		return s.isEmpty() ? "screen" : s.length() > 18 ? s.substring(0, 18) : s;
	}
}

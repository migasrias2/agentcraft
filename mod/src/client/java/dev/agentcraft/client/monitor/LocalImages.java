package dev.agentcraft.client.monitor;

import dev.agentcraft.AgentCraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Images from local files (a social post's {@code mediaPaths}: the Foreman and the game run on the
 * same machine) as GUI textures. Decoded off the render thread with ImageIO (PNG, JPEG, GIF; WebP
 * and videos are not previewable), downscaled to at most 1024 px, cached by path and reloaded when
 * the file changes. Render thread only for {@link #get}.
 */
public final class LocalImages {
	/** A ready texture and its pixel size. */
	public record Shown(Identifier texture, int w, int h) {
	}

	/** What {@link #get} knows about a path: ready ({@code shown} set), loading, or failed ({@code error}). */
	public record State(@Nullable Shown shown, boolean loading, @Nullable String error) {
	}

	private static final int MAX_SIDE = 1024;
	private static final int MAX_ENTRIES = 24;
	private static final long MAX_BYTES = 40L * 1024 * 1024;
	private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "agentcraft-local-images");
		t.setDaemon(true);
		return t;
	});

	private static final class Entry {
		final int slot;
		long modified;
		@Nullable DynamicTexture texture;
		@Nullable Shown shown;
		@Nullable String error;
		boolean loading;

		Entry(int slot) {
			this.slot = slot;
		}
	}

	private static final Map<String, Entry> ENTRIES = new HashMap<>();
	private static int nextSlot;

	private LocalImages() {
	}

	/** The image at {@code path}; starts loading it (or reloading a changed file) when needed. */
	public static State get(String path) {
		Entry e = ENTRIES.get(path);
		long modified = modified(path);
		if (e == null) {
			if (ENTRIES.size() >= MAX_ENTRIES) {
				clear();
			}
			e = new Entry(nextSlot++);
			ENTRIES.put(path, e);
			load(path, e, modified);
		} else if (!e.loading && modified != e.modified) {
			load(path, e, modified);
		}
		return new State(e.shown, e.loading, e.error);
	}

	/**
	 * Decode encoded image bytes (PNG, JPEG, GIF) to a native image of at most {@code maxSide} px, or null when the
	 * format is unsupported or the data is broken. Any thread; the caller owns (and must close or place) the image.
	 */
	public static com.mojang.blaze3d.platform.@Nullable NativeImage decodeNative(byte[] bytes, int maxSide) {
		ScreenImages.Pixels raw = ScreenImages.decodeBytes(bytes);
		return raw == null ? null : ScreenFrames.toNative(ScreenImages.downscale(raw, maxSide, maxSide));
	}

	/** Render thread: put {@code img} into the dynamic texture {@code id} (reusing {@code old} when the size matches). */
	public static DynamicTexture place(Identifier id, @Nullable DynamicTexture old, com.mojang.blaze3d.platform.NativeImage img, String label) {
		return ScreenFrames.place(Minecraft.getInstance().getTextureManager(), id, old, img, label);
	}

	/** Release every texture (e.g. when the screen that used them closes). */
	public static void clear() {
		var tm = Minecraft.getInstance().getTextureManager();
		for (Entry e : ENTRIES.values()) {
			if (e.texture != null) {
				tm.release(id(e.slot));
			}
		}
		ENTRIES.clear();
	}

	public static boolean isVideo(String path) {
		String p = path.toLowerCase(Locale.ROOT);
		return p.endsWith(".mp4") || p.endsWith(".mov") || p.endsWith(".m4v") || p.endsWith(".webm") || p.endsWith(".mkv") || p.endsWith(".avi");
	}

	private static void load(String path, Entry e, long modified) {
		e.loading = true;
		e.modified = modified;
		if (isVideo(path)) {
			finish(e, null, "video (no preview here)");
			return;
		}
		DECODER.execute(() -> {
			ScreenImages.Pixels px = null;
			String error = null;
			try {
				Path file = Path.of(path);
				if (!Files.isRegularFile(file)) {
					error = "file not found";
				} else if (Files.size(file) > MAX_BYTES) {
					error = "file too large to preview";
				} else {
					ScreenImages.Pixels raw = ScreenImages.decodeBytes(Files.readAllBytes(file));
					if (raw == null) {
						error = "can't preview this format";
					} else {
						px = ScreenImages.downscale(raw, MAX_SIDE, MAX_SIDE);
					}
				}
			} catch (Exception ex) {
				error = "can't read the file";
			}
			ScreenImages.Pixels ready = px;
			String err = error;
			Minecraft.getInstance().execute(() -> finish(e, ready, err));
		});
	}

	/** Render thread: install the decoded pixels (or the error) unless the entry was cleared meanwhile. */
	private static void finish(Entry e, ScreenImages.@Nullable Pixels px, @Nullable String error) {
		e.loading = false;
		if (!ENTRIES.containsValue(e)) {
			return;
		}
		if (px == null) {
			e.error = error;
			return;
		}
		try {
			var tm = Minecraft.getInstance().getTextureManager();
			Identifier id = id(e.slot);
			e.texture = ScreenFrames.place(tm, id, e.texture, ScreenFrames.toNative(px), "local image " + e.slot);
			e.shown = new Shown(id, px.w(), px.h());
			e.error = null;
		} catch (RuntimeException ex) {
			AgentCraft.LOGGER.warn("local image upload failed: {}", ex.toString());
			e.error = "can't show this image";
		}
	}

	private static long modified(String path) {
		try {
			return Files.getLastModifiedTime(Path.of(path)).toMillis();
		} catch (Exception ex) {
			return -1;
		}
	}

	private static Identifier id(int slot) {
		return AgentCraft.id("dynamic/local_image/" + slot);
	}
}

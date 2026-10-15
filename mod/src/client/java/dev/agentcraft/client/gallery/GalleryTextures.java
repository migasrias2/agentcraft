package dev.agentcraft.client.gallery;

import com.mojang.blaze3d.platform.NativeImage;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.monitor.LocalImages;
import dev.agentcraft.client.monitor.MediaStore;
import dev.agentcraft.hq.GalleryLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One GUI texture per bay (so at most {@link GalleryLayout#PAGE_SIZE} images are ever in memory). A bay
 * decodes its painting in the background when the item in it changes (a new page, a new file) and shows
 * nothing until the pixels are ready; an image that cannot be decoded is reported to {@link GalleryModel}
 * and skipped. Decoding is {@link LocalImages#decodeNative} (the same ImageIO path as the social posts);
 * show_media items use their poster frame from {@link MediaStore}. Render thread only, except the decode.
 */
final class GalleryTextures {
	/** A ready painting and its pixel size. */
	record Shown(Identifier texture, int w, int h) {
	}

	private static final int MAX_SIDE = 1024;
	private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "agentcraft-gallery-decode");
		t.setDaemon(true);
		return t;
	});

	private static final class Bay {
		@Nullable String itemId;
		long time;
		int token;
		boolean loading;
		@Nullable DynamicTexture texture;
		@Nullable Shown shown;
	}

	private static final Bay[] BAYS = new Bay[GalleryLayout.PAGE_SIZE];

	static {
		for (int i = 0; i < BAYS.length; i++) {
			BAYS[i] = new Bay();
		}
	}

	private GalleryTextures() {
	}

	/** The painting for {@code item} in bay {@code slot}, or null while it loads. Starts loading when the bay's item changed. */
	static @Nullable Shown shown(int slot, GalleryItem item) {
		Bay b = BAYS[slot];
		if (!item.id().equals(b.itemId) || item.time() != b.time) {
			start(slot, b, item);
		}
		return b.shown;
	}

	/** Free the textures of bays {@code from} and up (the page has fewer paintings than bays). */
	static void trim(int from) {
		for (int i = from; i < BAYS.length; i++) {
			reset(i, BAYS[i]);
		}
	}

	static void releaseAll() {
		trim(0);
	}

	/** Dev: what each bay currently shows. */
	static String describe(int slot) {
		Bay b = BAYS[slot];
		return b.itemId == null ? "" : b.itemId + (b.shown != null ? " " + b.shown.w() + "x" + b.shown.h() : b.loading ? " (loading)" : "");
	}

	private static void start(int slot, Bay b, GalleryItem item) {
		byte[] clipBytes = null;
		if (item.isMedia()) {
			MediaStore.Clip clip = MediaStore.get(item.clipId());
			clipBytes = clip == null ? null : MediaStore.firstFrame(clip);
			if (clipBytes == null) {
				// frames still unpacking (or the clip is gone): try again next frame without claiming the bay
				b.shown = null;
				b.itemId = null;
				return;
			}
		}
		b.token++;
		b.itemId = item.id();
		b.time = item.time();
		b.shown = null;
		b.loading = true;
		int token = b.token;
		Path file = item.file();
		byte[] preloaded = clipBytes;
		String id = item.id();
		DECODER.execute(() -> {
			NativeImage img = null;
			try {
				byte[] bytes = preloaded != null ? preloaded : file == null ? null : Files.readAllBytes(file);
				img = bytes == null ? null : LocalImages.decodeNative(bytes, MAX_SIDE);
			} catch (Exception | OutOfMemoryError e) {
				img = null;
			}
			NativeImage ready = img;
			Minecraft.getInstance().execute(() -> install(slot, token, id, ready));
		});
	}

	private static void install(int slot, int token, String itemId, @Nullable NativeImage img) {
		Bay b = BAYS[slot];
		if (b.token != token) {
			if (img != null) {
				img.close();
			}
			return;
		}
		b.loading = false;
		if (img == null) {
			GalleryModel.markBad(itemId);
			return;
		}
		try {
			int w = img.getWidth();
			int h = img.getHeight();
			Identifier id = id(slot);
			b.texture = LocalImages.place(id, b.texture, img, "gallery " + slot);
			b.shown = new Shown(id, w, h);
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.warn("gallery image upload failed: {}", e.toString());
			GalleryModel.markBad(itemId);
		}
	}

	private static void reset(int slot, Bay b) {
		b.token++;
		b.itemId = null;
		b.shown = null;
		b.loading = false;
		if (b.texture != null) {
			Minecraft.getInstance().getTextureManager().release(id(slot));
			b.texture = null;
		}
	}

	private static Identifier id(int slot) {
		return AgentCraft.id("dynamic/gallery/" + slot);
	}
}

package dev.agentcraft.client.monitor;

import com.mojang.blaze3d.platform.NativeImage;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.Protocol.Media;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The media gallery (images and videos agents hand the user with show_media): the newest
 * {@link #MAX_ITEMS} items, shown on the Screening Room's big screen and in {@link MediaViewScreen}.
 *
 * <p>Memory stays bounded: a clip keeps its frames only as the encoded bytes the Foreman sent
 * (JPEG/PNG, ~50 KB each) and owns ONE dynamic texture. The frame that is due (image: the only one;
 * video: by playhead and fps, looping) is decoded on a background thread and swapped into that
 * texture on the render thread, so a 150-frame video costs a few MB plus one texture, and a frame
 * that is still decoding simply keeps the previous one on screen. Client (render) thread only,
 * except the decode step. The playhead is per clip, so the world screen and the GUI play and pause
 * together.
 */
public final class MediaStore {
	public static final int MAX_ITEMS = 12;
	private static final int MAX_W = 1280;
	private static final int MAX_H = 800;
	private static final double DEFAULT_FPS = 10;

	/** What a renderer needs to draw a clip right now. */
	public record Shown(Identifier texture, int w, int h) {
	}

	/** One gallery item. Fields other than the playhead are managed by the store. */
	public static final class Clip {
		public final Media meta;
		final Identifier texId;
		volatile byte[][] frames; // null until unpacked
		@Nullable DynamicTexture texture;
		int w;
		int h;
		int shownIdx = -1;
		boolean inFlight;
		boolean failed;
		boolean released;
		// playhead (video): position = posAtAnchor + (now - anchor) unless paused
		long anchorMs;
		long posAtAnchorMs;
		boolean paused;

		Clip(Media meta, long now) {
			this.meta = meta;
			this.texId = AgentCraft.id("dynamic/media/" + meta.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
			this.anchorMs = now;
		}

		public boolean isVideo() {
			return meta.isVideo();
		}

		public int frameCount() {
			byte[][] f = frames;
			return f == null ? 0 : f.length;
		}

		public double fps() {
			Double f = meta.fps();
			return f != null && f > 0 ? Math.min(60, f) : DEFAULT_FPS;
		}

		/** Length of one loop in ms (0 for images or while the frames are still being unpacked). */
		public long loopMs() {
			int n = frameCount();
			return isVideo() && n > 1 ? Math.round(n * 1000.0 / fps()) : 0;
		}

		public long positionMs(long now) {
			long loop = loopMs();
			if (loop <= 0) {
				return 0;
			}
			long p = paused ? posAtAnchorMs : posAtAnchorMs + (now - anchorMs);
			return Math.floorMod(p, loop);
		}

		public void setPaused(boolean pause, long now) {
			if (pause == paused) {
				return;
			}
			posAtAnchorMs = positionMs(now);
			anchorMs = now;
			paused = pause;
		}

		/** Jump to the start and play. */
		public void restart(long now) {
			posAtAnchorMs = 0;
			anchorMs = now;
			paused = false;
		}

		public boolean loading() {
			return frames == null && !failed;
		}

		public boolean failed() {
			return failed;
		}
	}

	private static final List<Clip> ITEMS = new ArrayList<>();
	private static long revision;
	private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "agentcraft-media-decode");
		t.setDaemon(true);
		return t;
	});

	private MediaStore() {
	}

	public static void init() {
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onMedia(Media media) {
				add(media);
				trim();
				revision++;
			}

			@Override
			public void onMediaSnapshot(List<Media> items) {
				rebuild(items);
				revision++;
			}
		});
	}

	/** Bumps whenever the gallery changes (cheap "redraw / re-layout" signal). */
	public static long revision() {
		return revision;
	}

	/** The gallery, oldest first. */
	public static List<Clip> items() {
		return List.copyOf(ITEMS);
	}

	public static int size() {
		return ITEMS.size();
	}

	public static @Nullable Clip latest() {
		return ITEMS.isEmpty() ? null : ITEMS.get(ITEMS.size() - 1);
	}

	public static @Nullable Clip get(String id) {
		for (Clip c : ITEMS) {
			if (c.meta.id().equals(id)) {
				return c;
			}
		}
		return null;
	}

	/** QA / tests: add an item as if the Foreman had sent it. */
	public static void inject(Media media) {
		add(media);
		trim();
		revision++;
	}

	/** The first encoded frame of a clip (its poster), or null while the frames are still being unpacked. */
	public static byte @Nullable [] firstFrame(Clip c) {
		byte[][] f = c.frames;
		return f == null || f.length == 0 ? null : f[0];
	}

	// ------------------------------------------------------------------ gallery

	private static void rebuild(List<Media> items) {
		List<String> keep = new ArrayList<>();
		for (Media m : items) {
			keep.add(m.id());
		}
		for (Clip c : new ArrayList<>(ITEMS)) {
			if (!keep.contains(c.meta.id())) {
				release(c);
				ITEMS.remove(c);
			}
		}
		for (Media m : items) {
			if (get(m.id()) == null) {
				add(m);
			}
		}
		// keep the Foreman's order (newest last)
		ITEMS.sort((a, b) -> Integer.compare(keep.indexOf(a.meta.id()), keep.indexOf(b.meta.id())));
		trim();
	}

	private static void add(Media m) {
		if (m.id() == null || m.frames().isEmpty() || get(m.id()) != null) {
			return;
		}
		Clip c = new Clip(m.withoutFrames(), System.currentTimeMillis());
		ITEMS.add(c);
		List<String> frames = m.frames();
		DECODER.execute(() -> {
			try {
				byte[][] bytes = new byte[frames.size()][];
				for (int i = 0; i < bytes.length; i++) {
					bytes[i] = Base64.getMimeDecoder().decode(frames.get(i));
				}
				c.frames = bytes;
			} catch (RuntimeException e) {
				AgentCraft.LOGGER.warn("Could not unpack media {}", m.id(), e);
				Minecraft.getInstance().execute(() -> c.failed = true);
			}
		});
	}

	private static void trim() {
		while (ITEMS.size() > MAX_ITEMS) {
			release(ITEMS.remove(0));
		}
	}

	private static void release(Clip c) {
		c.released = true;
		c.frames = null;
		if (c.texture != null) {
			Minecraft.getInstance().getTextureManager().release(c.texId);
			c.texture = null;
		}
	}

	// ------------------------------------------------------------------ frames

	/**
	 * The texture to draw for {@code c} at {@code nowMs}, or null while the first frame is still
	 * decoding (or could not be decoded). Starts the background decode of the frame that is due.
	 * Render thread.
	 */
	public static @Nullable Shown frameFor(Clip c, long nowMs) {
		byte[][] fr = c.frames;
		if (fr != null && fr.length > 0 && !c.inFlight && !c.released) {
			int n = fr.length;
			int idx = c.isVideo() && n > 1 ? (int) Math.min(n - 1, c.positionMs(nowMs) * c.fps() / 1000.0) : 0;
			if (idx != c.shownIdx) {
				c.inFlight = true;
				byte[] data = fr[idx];
				DECODER.execute(() -> decode(c, idx, data));
			}
		}
		DynamicTexture t = c.texture;
		return t == null ? null : new Shown(c.texId, c.w, c.h);
	}

	private static void decode(Clip c, int idx, byte[] data) {
		NativeImage img = null;
		try {
			ScreenImages.Pixels px = ScreenImages.decodeBytes(data);
			if (px != null) {
				img = ScreenFrames.toNative(ScreenImages.downscale(px, MAX_W, MAX_H));
			}
		} catch (Throwable t) {
			AgentCraft.LOGGER.warn("Could not decode frame {} of media {}", idx, c.meta.id(), t);
		}
		NativeImage ready = img;
		Minecraft.getInstance().execute(() -> {
			c.inFlight = false;
			if (c.released) {
				if (ready != null) {
					ready.close();
				}
				return;
			}
			c.shownIdx = idx;
			if (ready == null) {
				if (c.texture == null) {
					c.failed = true;
				}
				return;
			}
			c.w = ready.getWidth();
			c.h = ready.getHeight();
			c.texture = ScreenFrames.place(Minecraft.getInstance().getTextureManager(), c.texId, c.texture, ready, "media " + c.meta.id());
		});
	}
}

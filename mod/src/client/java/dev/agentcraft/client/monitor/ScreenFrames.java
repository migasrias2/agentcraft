package dev.agentcraft.client.monitor;

import com.mojang.blaze3d.platform.NativeImage;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.AgentScreen;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Live screen frames ({@code agent.screen}: screenshots an agent got from Claude in Chrome, Roblox
 * Studio, Blender...) as per-agent dynamic textures. Decoding and scaling run on one background
 * thread; only the GPU upload happens on the render thread. Each agent owns two stable texture ids
 * (a small one for the in-world monitor, a big one for {@link MonitorViewScreen}); a new frame of
 * the same size is swapped in place, a different size releases and re-registers the texture, so
 * nothing leaks. A newer frame always wins: older queued decodes are dropped. Client thread only,
 * except the decode step.
 */
public final class ScreenFrames {
	/** A frame older than this is not shown any more (the monitor falls back to the log). */
	public static final long FRESH_MS = 90_000;
	/** Below this age the label says LIVE. */
	public static final long LIVE_MS = 15_000;
	private static final int BIG_W = 1280;
	private static final int BIG_H = 800;
	private static final int THUMB_W = 384;
	private static final int THUMB_H = 240;

	/** What the renderers need: texture ids + sizes + provenance. */
	public record Frame(String agentId, Identifier thumb, int thumbW, int thumbH, Identifier big, int bigW, int bigH, long at, String tool, String label) {
		public long ageMs(long nowMs) {
			return Math.max(0, nowMs - at);
		}

		public boolean live(long nowMs) {
			return ageMs(nowMs) < LIVE_MS;
		}
	}

	private static final class Entry {
		Frame frame;
		@Nullable DynamicTexture thumbTex;
		@Nullable DynamicTexture bigTex;
	}

	private record Decoded(NativeImage thumb, NativeImage big, int tw, int th, int bw, int bh) {
	}

	private static final Map<String, Entry> ENTRIES = new HashMap<>();
	private static final Map<String, AtomicLong> GENERATION = new ConcurrentHashMap<>();
	private static final Set<String> WARNED = new HashSet<>();
	private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "agentcraft-screen-decode");
		t.setDaemon(true);
		return t;
	});

	private ScreenFrames() {
	}

	public static void init() {
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onScreen(AgentScreen screen) {
				submit(screen);
			}

			@Override
			public void onSnapshot(ForemanState st) {
				Map<String, AgentScreen> now = st.screens();
				for (String id : new HashSet<>(ENTRIES.keySet())) {
					if (!now.containsKey(id)) {
						drop(id);
					}
				}
				for (AgentScreen sc : now.values()) {
					Entry e = ENTRIES.get(sc.agentId());
					if (e == null || e.frame.at() < sc.at()) {
						submit(sc);
					}
				}
			}
		});
	}

	/** The fresh frame of an agent, or null (none yet, still decoding the first one, or stale). */
	public static @Nullable Frame fresh(String agentId, long nowMs) {
		Frame f = latest(agentId);
		return f != null && f.ageMs(nowMs) < FRESH_MS ? f : null;
	}

	/** The latest decoded frame of an agent regardless of age (render thread), or null. */
	public static @Nullable Frame latest(String agentId) {
		Entry e = ENTRIES.get(agentId);
		return e == null ? null : e.frame;
	}

	/** Any agent that has a decoded frame (QA: open the big view without knowing who). */
	public static @Nullable String anyAgent() {
		for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
			if (e.getValue().frame != null) {
				return e.getKey();
			}
		}
		return null;
	}

	private static void submit(AgentScreen sc) {
		String agent = sc.agentId();
		AtomicLong gen = GENERATION.computeIfAbsent(agent, k -> new AtomicLong());
		long g = gen.incrementAndGet();
		DECODER.execute(() -> {
			if (gen.get() != g) {
				return; // a newer frame is already queued
			}
			Decoded d = null;
			try {
				d = decode(sc);
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("Could not decode a screen frame for {}", agent, t);
			}
			if (d == null) {
				if (WARNED.add(agent + "|" + sc.mime())) {
					AgentCraft.LOGGER.warn("Unsupported screen frame for {} ({}); the monitor keeps showing the log", agent, sc.mime());
				}
				return;
			}
			Decoded ready = d;
			Minecraft.getInstance().execute(() -> {
				if (gen.get() != g) {
					ready.thumb().close();
					ready.big().close();
					return;
				}
				install(sc, ready);
			});
		});
	}

	private static @Nullable Decoded decode(AgentScreen sc) {
		ScreenImages.Pixels src = ScreenImages.decode(sc.data());
		if (src == null) {
			return null;
		}
		ScreenImages.Pixels big = ScreenImages.downscale(src, BIG_W, BIG_H);
		ScreenImages.Pixels thumb = ScreenImages.downscale(big, THUMB_W, THUMB_H);
		return new Decoded(toNative(thumb), toNative(big), thumb.w(), thumb.h(), big.w(), big.h());
	}

	static NativeImage toNative(ScreenImages.Pixels p) {
		NativeImage img = new NativeImage(p.w(), p.h(), false);
		int[] px = p.argb();
		for (int y = 0; y < p.h(); y++) {
			int row = y * p.w();
			for (int x = 0; x < p.w(); x++) {
				img.setPixel(x, y, px[row + x]);
			}
		}
		return img;
	}

	private static void install(AgentScreen sc, Decoded d) {
		String agent = sc.agentId();
		Entry e = ENTRIES.computeIfAbsent(agent, k -> new Entry());
		Identifier thumbId = id(agent, "t");
		Identifier bigId = id(agent, "b");
		TextureManager tm = Minecraft.getInstance().getTextureManager();
		e.thumbTex = place(tm, thumbId, e.thumbTex, d.thumb(), agent + " thumb");
		e.bigTex = place(tm, bigId, e.bigTex, d.big(), agent + " big");
		e.frame = new Frame(agent, thumbId, d.tw(), d.th(), bigId, d.bw(), d.bh(), sc.at(), sc.tool(), ScreenImages.sourceLabel(sc.tool()));
	}

	/** Swap {@code img} into the texture in place when the size matches, else release and register a new one. */
	static DynamicTexture place(TextureManager tm, Identifier id, @Nullable DynamicTexture old, NativeImage img, String label) {
		if (old != null && old.getPixels().getWidth() == img.getWidth() && old.getPixels().getHeight() == img.getHeight()) {
			old.setPixels(img); // closes the previous image
			old.upload();
			return old;
		}
		if (old != null) {
			tm.release(id); // closes the old texture and its image
		}
		DynamicTexture t = new DynamicTexture(() -> "agentcraft screen " + label, img);
		tm.register(id, t);
		return t;
	}

	private static void drop(String agent) {
		Entry e = ENTRIES.remove(agent);
		GENERATION.computeIfAbsent(agent, k -> new AtomicLong()).incrementAndGet();
		if (e != null) {
			TextureManager tm = Minecraft.getInstance().getTextureManager();
			if (e.thumbTex != null) {
				tm.release(id(agent, "t"));
			}
			if (e.bigTex != null) {
				tm.release(id(agent, "b"));
			}
		}
	}

	private static Identifier id(String agent, String kind) {
		return AgentCraft.id("dynamic/screen/" + agent.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_") + "/" + kind);
	}
}

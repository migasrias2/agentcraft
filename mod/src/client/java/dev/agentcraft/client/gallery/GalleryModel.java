package dev.agentcraft.client.gallery;

import dev.agentcraft.client.monitor.MediaStore;
import dev.agentcraft.hq.GalleryLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * What hangs in the Gallery: every image in {@code ~/Pictures/AgentCraft Gallery} plus everything agents
 * handed the user with show_media ({@link MediaStore}), newest first, split into pages of
 * {@link GalleryLayout#PAGE_SIZE} (one per bay). The folder is listed on a background thread every few
 * seconds while the player is near the room (a directory listing only; images are decoded lazily by
 * {@link GalleryTextures}), so new files show up without a restart. Render thread only, except the scan.
 */
final class GalleryModel {
	static final Path FOLDER = Path.of(System.getProperty("user.home"), "Pictures", "AgentCraft Gallery");
	private static final long SCAN_EVERY_MS = 4000;
	private static final int MAX_FILES = 1000;
	private static final long MAX_BYTES = 40L * 1024 * 1024;
	private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");

	private static final ExecutorService SCANNER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "agentcraft-gallery-scan");
		t.setDaemon(true);
		return t;
	});
	private static final AtomicBoolean SCANNING = new AtomicBoolean();
	private static volatile List<GalleryItem> folderItems = List.of();
	private static List<GalleryItem> mergedFrom = List.of();
	private static List<GalleryItem> items = List.of();
	private static final Set<String> BAD = new HashSet<>();
	private static long mediaRevision = -1;
	private static boolean badChanged;
	private static long lastScanMs;
	private static int page;

	private GalleryModel() {
	}

	/** Call every frame; {@code near} = the player is close enough to the room for it to matter. */
	static void tick(long nowMs, boolean near) {
		if (!near) {
			return;
		}
		if (nowMs - lastScanMs >= SCAN_EVERY_MS && SCANNING.compareAndSet(false, true)) {
			lastScanMs = nowMs;
			SCANNER.execute(() -> {
				try {
					folderItems = scan();
				} finally {
					SCANNING.set(false);
				}
			});
		}
		List<GalleryItem> folder = folderItems;
		long rev = MediaStore.revision();
		if (folder != mergedFrom || rev != mediaRevision || badChanged) {
			mergedFrom = folder;
			mediaRevision = rev;
			badChanged = false;
			items = merge(folder);
			page = Math.max(0, Math.min(page, pages() - 1));
		}
	}

	static List<GalleryItem> items() {
		return items;
	}

	static int pages() {
		return Math.max(1, (items.size() + GalleryLayout.PAGE_SIZE - 1) / GalleryLayout.PAGE_SIZE);
	}

	static int page() {
		return page;
	}

	/** The paintings of the current page, in bay order (newest first). */
	static List<GalleryItem> pageItems() {
		int from = page * GalleryLayout.PAGE_SIZE;
		if (from >= items.size()) {
			return List.of();
		}
		return items.subList(from, Math.min(items.size(), from + GalleryLayout.PAGE_SIZE));
	}

	/** Turn to the next page (wraps round to the first). */
	static void nextPage() {
		page = (page + 1) % pages();
	}

	static void setPage(int p) {
		page = Math.max(0, Math.min(p, pages() - 1));
	}

	/** An image that cannot be decoded is dropped from the gallery for this session. */
	static void markBad(String id) {
		if (BAD.add(id)) {
			badChanged = true;
		}
	}

	/** Make the next {@link #tick} rescan the folder at once (e.g. on entering the room). */
	static void rescanSoon() {
		lastScanMs = 0;
	}

	private static List<GalleryItem> merge(List<GalleryItem> folder) {
		List<GalleryItem> all = new ArrayList<>(folder.size() + MediaStore.MAX_ITEMS);
		for (GalleryItem f : folder) {
			if (!BAD.contains(f.id())) {
				all.add(f);
			}
		}
		for (MediaStore.Clip c : MediaStore.items()) {
			String id = "media:" + c.meta.id();
			if (c.failed() || BAD.contains(id)) {
				continue;
			}
			String cap = c.meta.caption();
			String title = cap != null && !cap.isBlank() ? cap.strip() : stripExtension(c.meta.name());
			String who = dev.agentcraft.client.hud.UiBits.agentName(c.meta.agentId());
			all.add(new GalleryItem(id, title, who + (c.isVideo() ? " · video" : ""), null, c.meta.id(), c.meta.at()));
		}
		all.sort(Comparator.comparingLong(GalleryItem::time).reversed().thenComparing(GalleryItem::id));
		return List.copyOf(all);
	}

	private static List<GalleryItem> scan() {
		try {
			Files.createDirectories(FOLDER);
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(FOLDER)) {
			List<GalleryItem> out = new ArrayList<>();
			for (Path f : (Iterable<Path>) files.limit(MAX_FILES * 2L)::iterator) {
				GalleryItem item = toItem(f);
				if (item != null) {
					out.add(item);
				}
				if (out.size() >= MAX_FILES) {
					break;
				}
			}
			return List.copyOf(out);
		} catch (IOException | RuntimeException e) {
			return folderItems; // keep what we had
		}
	}

	private static @Nullable GalleryItem toItem(Path f) {
		try {
			String name = f.getFileName().toString();
			if (name.startsWith(".") || !EXTENSIONS.contains(extension(name)) || !Files.isRegularFile(f)) {
				return null;
			}
			long size = Files.size(f);
			if (size <= 0 || size > MAX_BYTES) {
				return null;
			}
			long modified = Files.getLastModifiedTime(f).toMillis();
			return new GalleryItem("file:" + f.toAbsolutePath(), stripExtension(name), "Gallery", f.toAbsolutePath(), null, modified);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}
}

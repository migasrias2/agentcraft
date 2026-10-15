package dev.agentcraft.client.gallery;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * One painting: an image file from the gallery folder ({@code file} set) or something an agent handed the
 * user with show_media ({@code clipId} set). {@code time} is epoch ms (file modified / handed over).
 */
public record GalleryItem(String id, String title, String subtitle, @Nullable Path file, @Nullable String clipId, long time) {
	public boolean isMedia() {
		return clipId != null;
	}
}

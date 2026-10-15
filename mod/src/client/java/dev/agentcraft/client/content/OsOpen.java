package dev.agentcraft.client.content;

import dev.agentcraft.AgentCraft;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Opens a file or a link with the operating system's default app (the game and the Foreman run on the same machine). */
final class OsOpen {
	private OsOpen() {
	}

	/** {@code target} is an absolute file path or an http(s) URL. Fire and forget; failures are logged. */
	static void open(String target, ContentActions actions) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> cmd = os.contains("mac") ? List.of("open", target)
			: os.contains("win") ? List.of("cmd", "/c", "start", "", target)
			: List.of("xdg-open", target);
		try {
			new ProcessBuilder(cmd).redirectErrorStream(true).start();
			actions.flash("Opened " + target.substring(target.lastIndexOf('/') + 1), false);
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("Could not open {}", target, e);
			actions.flash("Could not open it: " + e.getMessage(), true);
		}
	}
}

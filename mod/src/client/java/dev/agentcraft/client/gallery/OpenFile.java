package dev.agentcraft.client.gallery;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Opens a file in the OS's own viewer (Preview on the Mac). The game and the file live on the same machine. */
final class OpenFile {
	private OpenFile() {
	}

	/** Completes with true when the viewer was started. Never throws. */
	static CompletableFuture<Boolean> inViewer(Path file) {
		return CompletableFuture.supplyAsync(() -> {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String path = file.toAbsolutePath().toString();
			List<String> cmd;
			if (os.contains("mac")) {
				cmd = List.of("open", "-a", "Preview", path);
			} else if (os.contains("win")) {
				cmd = List.of("cmd", "/c", "start", "", path);
			} else {
				cmd = List.of("xdg-open", path);
			}
			try {
				Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
				pr.getInputStream().close();
				return pr.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) ? pr.exitValue() == 0 : true;
			} catch (Exception e) {
				return false;
			}
		});
	}
}

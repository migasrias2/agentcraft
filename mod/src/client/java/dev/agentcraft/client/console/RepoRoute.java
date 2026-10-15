package dev.agentcraft.client.console;

import dev.agentcraft.client.foreman.Protocol.Repo;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Picks the repo a goal is about from its text (a port of foreman/src/reporoute.ts; keep them in step).
 * An explicit {@code "<repo>: goal"} prefix wins (and is stripped); otherwise a repo named anywhere as a
 * whole word. Names match case-insensitively by folder name or by path under the home folder
 * ({@code Desktop/side-project}). The longest key wins; between equal keys the shallower path wins. Pure.
 */
public final class RepoRoute {
	private static final Pattern PREFIX = Pattern.compile("^\\s*([^\\s:]+(?: [^\\s:]+)?)\\s*:\\s*([\\s\\S]+)$");

	/** {@code text} has an explicit prefix removed. */
	public record Route(String repoId, String text) {
	}

	private record Candidate(Repo repo, String key, int depth) {
	}

	private RepoRoute() {
	}

	public static Optional<Route> route(String text, List<Repo> repos, String home) {
		List<Candidate> all = candidates(repos, home);
		Matcher prefix = PREFIX.matcher(text);
		if (prefix.matches()) {
			String want = prefix.group(1).toLowerCase(Locale.ROOT);
			Optional<Candidate> hit = best(all.stream().filter(c -> c.key().equals(want)).toList());
			if (hit.isPresent()) {
				return Optional.of(new Route(hit.get().repo().id(), prefix.group(2).strip()));
			}
		}
		String lower = text.toLowerCase(Locale.ROOT);
		return best(all.stream().filter(c -> mentions(lower, c.key())).toList()).map(c -> new Route(c.repo().id(), text));
	}

	/** The home-relative path with '/' separators, or null when the repo is outside home. */
	public static @Nullable String relativePath(String repoPath, String home) {
		try {
			String rel = Path.of(home).relativize(Path.of(repoPath)).toString().replace('\\', '/');
			return rel.isEmpty() || rel.startsWith("..") ? null : rel;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static boolean mentions(String lowerText, String key) {
		int from = 0;
		while (true) {
			int i = lowerText.indexOf(key, from);
			if (i < 0) {
				return false;
			}
			boolean startOk = i == 0 || !isWordChar(lowerText.charAt(i - 1));
			int end = i + key.length();
			boolean endOk = end >= lowerText.length() || !isWordChar(lowerText.charAt(end));
			if (startOk && endOk) {
				return true;
			}
			from = i + 1;
		}
	}

	private static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
	}

	private static List<Candidate> candidates(List<Repo> repos, String home) {
		List<Candidate> out = new ArrayList<>();
		for (Repo repo : repos) {
			String rel = relativePath(repo.path(), home);
			int depth = rel == null ? 1 : rel.split("/").length;
			Set<String> keys = new LinkedHashSet<>();
			keys.add(repo.name());
			Path base = safeFileName(repo.path());
			if (base != null) {
				keys.add(base.toString());
			}
			if (rel != null) {
				keys.add(rel);
			}
			for (String key : keys) {
				if (key != null && !key.isEmpty()) {
					out.add(new Candidate(repo, key.toLowerCase(Locale.ROOT), depth));
				}
			}
		}
		return out;
	}

	private static @Nullable Path safeFileName(String p) {
		try {
			return Path.of(p).getFileName();
		} catch (InvalidPathException e) {
			return null;
		}
	}

	private static Optional<Candidate> best(List<Candidate> found) {
		return found.stream().min(Comparator.comparingInt((Candidate c) -> -c.key().length()).thenComparingInt(Candidate::depth));
	}
}

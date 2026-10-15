package dev.agentcraft.client.content;

import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.ContentChannel;
import dev.agentcraft.client.foreman.Protocol.ContentChannelStats;
import dev.agentcraft.client.foreman.Protocol.ContentStat;
import dev.agentcraft.client.foreman.Protocol.ContentVideo;
import dev.agentcraft.client.foreman.Protocol.DailyStat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Shared bits of the Content Room: what the Studio screen and the wall board both need. The data itself lives
 * in {@link ForemanState} (fed by the {@code content.*} messages); this class adds the remembered selection
 * (channel, tab, video, reference), the pipeline columns, the per-video analytics rows and the number and
 * time formatting, so the screen, the wall board and the console all say the same thing.
 */
public final class ContentState {
	/** Monitor binding of the wall analytics board (the Content Room's east wall). */
	public static final String BINDING = dev.agentcraft.hq.ContentLayout.BOARD;
	/** The four Content Room agents, in desk order. */
	public static final String[] AGENTS = dev.agentcraft.hq.ContentLayout.DESK_IDS;

	private static final ZoneId ZONE = ZoneId.systemDefault();
	private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH);

	/** Which tab the Studio shows (remembered across openings). */
	public enum Tab {
		PIPELINE("Pipeline"), ANALYTICS("Analytics"), REFERENCES("References"), PLAYBOOK("Playbook");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	/** Pipeline columns; failed publishes show in Scheduled (red), rejected videos are not shown. */
	public enum Column {
		IDEAS("Ideas"), PRODUCTION("In production"), REVIEW("Review"), SCHEDULED("Scheduled"), PUBLISHED("Published");

		final String label;

		Column(String label) {
			this.label = label;
		}
	}

	/** How the analytics table is sorted. */
	public enum Sort {
		VIEWS("Views"), CTR("CTR"), RETENTION("Avg view %"), NEWEST("Newest");

		final String label;

		Sort(String label) {
			this.label = label;
		}
	}

	private static Tab tab = Tab.PIPELINE;
	private static @Nullable String channelId;
	private static @Nullable String videoId;
	private static @Nullable String analyticsVideoId;
	private static @Nullable String refId;
	private static Sort sort = Sort.VIEWS;

	private ContentState() {
	}

	// ------------------------------------------------------------------ selection

	public static Tab tab() {
		return tab;
	}

	public static void setTab(Tab t) {
		tab = t;
	}

	public static Sort sort() {
		return sort;
	}

	public static void setSort(Sort s) {
		sort = s;
	}

	/** The selected channel (the first one when none was picked or it was removed), or null with no channels. */
	public static @Nullable ContentChannel channel() {
		ForemanState st = Foreman.state();
		if (st == null) {
			return null;
		}
		ContentChannel c = channelId == null ? null : st.contentChannel(channelId);
		if (c == null && !st.contentChannels().isEmpty()) {
			c = st.contentChannels().getFirst();
			channelId = c.id();
		}
		return c;
	}

	public static void selectChannel(String id) {
		if (!id.equals(channelId)) {
			videoId = null;
			analyticsVideoId = null;
		}
		channelId = id;
	}

	public static @Nullable String videoId() {
		return videoId;
	}

	public static void selectVideo(@Nullable String id) {
		videoId = id;
	}

	public static @Nullable String analyticsVideoId() {
		return analyticsVideoId;
	}

	public static void selectAnalyticsVideo(@Nullable String id) {
		analyticsVideoId = id;
	}

	public static @Nullable String refId() {
		return refId;
	}

	public static void selectRef(@Nullable String id) {
		refId = id;
	}

	// ------------------------------------------------------------------ pipeline

	/** The column a video sits in, or null for rejected videos. */
	public static @Nullable Column column(ContentVideo v) {
		return switch (v.status()) {
			case "idea" -> Column.IDEAS;
			case "approved", "producing" -> Column.PRODUCTION;
			case "review" -> Column.REVIEW;
			case "scheduled", "publishing", "failed" -> Column.SCHEDULED;
			case "published" -> Column.PUBLISHED;
			default -> null;
		};
	}

	/** Videos of a channel in a column: scheduled soonest first, published newest first, the rest oldest first. */
	public static List<ContentVideo> inColumn(String channelId, Column col) {
		ForemanState st = Foreman.state();
		List<ContentVideo> out = new ArrayList<>();
		if (st == null) {
			return out;
		}
		for (ContentVideo v : st.contentVideos(channelId)) {
			if (column(v) == col) {
				out.add(v);
			}
		}
		switch (col) {
			case SCHEDULED -> out.sort(Comparator.comparingLong((ContentVideo v) -> v.scheduledAt() == null ? Long.MAX_VALUE : v.scheduledAt()));
			case PUBLISHED -> out.sort(Comparator.comparingLong((ContentVideo v) -> v.publishedAt() == null ? v.updatedAt() : v.publishedAt()).reversed());
			default -> out.sort(Comparator.comparingLong(ContentVideo::createdAt));
		}
		return out;
	}

	public static int countRejected(String channelId) {
		int n = 0;
		ForemanState st = Foreman.state();
		if (st != null) {
			for (ContentVideo v : st.contentVideos(channelId)) {
				if (v.status().equals("rejected")) {
					n++;
				}
			}
		}
		return n;
	}

	/** Videos that wait for the user: ideas to approve and cuts to review. */
	public static int waitingForYou(@Nullable String channelId) {
		int n = 0;
		ForemanState st = Foreman.state();
		if (st != null) {
			for (ContentVideo v : st.contentVideos(channelId)) {
				if (v.status().equals("idea") || v.status().equals("review")) {
					n++;
				}
			}
		}
		return n;
	}

	public static int statusColor(String status) {
		return switch (status) {
			case "idea" -> 0xFF8E8E93;
			case "approved", "producing" -> 0xFFE6A83C;
			case "review" -> 0xFF64A0FF;
			case "scheduled", "publishing" -> 0xFFB48CFF;
			case "published" -> 0xFF5CC98B;
			case "failed", "rejected" -> 0xFFFF6B5E;
			default -> 0xFF8E8E93;
		};
	}

	public static String statusLabel(String status) {
		return switch (status) {
			case "idea" -> "idea";
			case "approved" -> "approved";
			case "producing" -> "in production";
			case "review" -> "ready for review";
			case "scheduled" -> "scheduled";
			case "publishing" -> "publishing...";
			case "published" -> "published";
			case "failed" -> "failed";
			case "rejected" -> "rejected";
			default -> status;
		};
	}

	/** Who an agent-bound comment wakes, by status: the owner of that stage. */
	public static String ownerOf(ContentVideo v) {
		return switch (v.status()) {
			case "producing", "review", "scheduled" -> "reel";
			case "publishing", "failed" -> "cleo";
			case "published" -> "vega";
			default -> "iris";
		};
	}

	// ------------------------------------------------------------------ analytics rows

	/** One row of the analytics table: an own published video with its latest stat and the change since the previous sync. */
	public record Row(ContentVideo video, @Nullable ContentStat stat, long deltaViews) {
		public long views() {
			return stat == null ? 0 : stat.views();
		}
	}

	/** Own published videos of the channel that have a YouTube id, with stats, sorted by {@link #sort()}. */
	public static List<Row> rows(String channelId) {
		ForemanState st = Foreman.state();
		List<Row> out = new ArrayList<>();
		if (st == null) {
			return out;
		}
		for (ContentVideo v : st.contentVideos(channelId)) {
			if (!v.status().equals("published")) {
				continue;
			}
			ContentStat last = v.youtubeVideoId() == null ? null : st.contentStat(v.youtubeVideoId());
			long delta = 0;
			if (v.youtubeVideoId() != null) {
				List<ContentStat> h = st.contentStatHistory(v.youtubeVideoId());
				if (h.size() >= 2) {
					delta = h.get(h.size() - 1).views() - h.get(h.size() - 2).views();
				}
			}
			out.add(new Row(v, last, delta));
		}
		out.sort(switch (sort) {
			case VIEWS -> Comparator.comparingLong(Row::views).reversed();
			case CTR -> Comparator.comparingDouble((Row r) -> r.stat() == null || r.stat().ctr() == null ? -1 : r.stat().ctr()).reversed();
			case RETENTION -> Comparator.comparingDouble((Row r) -> r.stat() == null || r.stat().avgViewPct() == null ? -1 : r.stat().avgViewPct()).reversed();
			case NEWEST -> Comparator.comparingLong((Row r) -> r.video().publishedAt() == null ? r.video().updatedAt() : r.video().publishedAt()).reversed();
		});
		return out;
	}

	/** The video the analytics tab details: the picked one, else the first row. */
	public static @Nullable Row selectedRow(List<Row> rows) {
		for (Row r : rows) {
			if (r.video().id().equals(analyticsVideoId)) {
				return r;
			}
		}
		return rows.isEmpty() ? null : rows.getFirst();
	}

	/** Views-weighted average view percentage (0..1) over the own videos that report it, or null. */
	public static @Nullable Double avgViewPct(List<Row> rows) {
		double sum = 0;
		double weight = 0;
		for (Row r : rows) {
			if (r.stat() != null && r.stat().avgViewPct() != null) {
				sum += r.stat().avgViewPct() * Math.max(1, r.stat().views());
				weight += Math.max(1, r.stat().views());
			}
		}
		return weight == 0 ? null : sum / weight;
	}

	/** The channel's last 28 days of views (oldest first), padded with zeros up to 28 entries when fewer arrived. */
	public static long[] dailyViews(@Nullable ContentChannelStats cs) {
		long[] out = new long[28];
		if (cs == null) {
			return out;
		}
		List<DailyStat> d = cs.daily();
		int n = Math.min(28, d.size());
		for (int i = 0; i < n; i++) {
			out[28 - n + i] = d.get(d.size() - n + i).views();
		}
		return out;
	}

	/** Date label of the {@code i}th of the 28 chart columns (0 = oldest), or "" when unknown. */
	public static String dailyLabel(@Nullable ContentChannelStats cs, int i) {
		if (cs == null) {
			return "";
		}
		List<DailyStat> d = cs.daily();
		int n = Math.min(28, d.size());
		int k = i - (28 - n);
		return k < 0 || k >= n ? "" : d.get(d.size() - n + k).date();
	}

	// ------------------------------------------------------------------ formatting

	/** 1234 -> "1.2K", 3400000 -> "3.4M", small numbers as they are. */
	public static String compact(long n) {
		long a = Math.abs(n);
		if (a >= 10_000_000) {
			return String.format(Locale.ROOT, "%.0fM", n / 1e6);
		}
		if (a >= 1_000_000) {
			return String.format(Locale.ROOT, "%.1fM", n / 1e6);
		}
		if (a >= 10_000) {
			return String.format(Locale.ROOT, "%.0fK", n / 1e3);
		}
		if (a >= 1_000) {
			return String.format(Locale.ROOT, "%.1fK", n / 1e3);
		}
		return Long.toString(n);
	}

	public static String compact(@Nullable Long n) {
		return n == null ? "--" : compact(n.longValue());
	}

	/** 0.427 -> "43%". */
	public static String pct(@Nullable Double ratio) {
		return ratio == null ? "--" : String.format(Locale.ROOT, "%.0f%%", ratio * 100);
	}

	/** A value that is already a percentage (YouTube's averageViewPercentage, 0..100): 45.2 -> "45%". */
	public static String percent(@Nullable Double value) {
		return value == null ? "--" : String.format(Locale.ROOT, "%.0f%%", value);
	}

	/** CTR with a decimal: 0.0473 -> "4.7%". */
	public static String pct1(@Nullable Double ratio) {
		return ratio == null ? "--" : String.format(Locale.ROOT, "%.1f%%", ratio * 100);
	}

	/** Watch minutes as hours: 750 -> "12.5 h". */
	public static String hours(@Nullable Double minutes) {
		return minutes == null ? "--" : String.format(Locale.ROOT, "%.1f h", minutes / 60.0);
	}

	/** "+120" / "-30" / "0". */
	public static String signed(long n) {
		return n > 0 ? "+" + compact(n) : n < 0 ? "-" + compact(-n) : "0";
	}

	/** 125 -> "2:05". */
	public static String duration(@Nullable Double sec) {
		if (sec == null) {
			return "--";
		}
		long s = Math.round(sec);
		return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	public static String dayTime(long ms) {
		return DAY_TIME.format(Instant.ofEpochMilli(ms).atZone(ZONE));
	}

	public static String day(long ms) {
		return DAY.format(Instant.ofEpochMilli(ms).atZone(ZONE));
	}

	public static String inputFormat(long ms) {
		return INPUT.format(Instant.ofEpochMilli(ms).atZone(ZONE));
	}

	/**
	 * A time typed by the user: "2026-10-20 18:00", "18:00" (today, or tomorrow when already past), "+3h" or "+2d" (from now).
	 * Null when it does not parse.
	 */
	public static @Nullable Long parseTime(String raw, long now) {
		String s = raw.strip();
		try {
			if (s.matches("\\+\\d+[hd]")) {
				long n = Long.parseLong(s.substring(1, s.length() - 1));
				return now + n * (s.endsWith("h") ? 3_600_000L : 86_400_000L);
			}
			if (s.matches("\\d{1,2}:\\d{2}")) {
				LocalDateTime t = LocalDate.now(ZONE).atTime(LocalTime.parse(s.length() == 4 ? "0" + s : s));
				long ms = t.atZone(ZONE).toInstant().toEpochMilli();
				return ms <= now ? ms + 86_400_000L : ms;
			}
			return LocalDateTime.parse(s.replace('T', ' '), INPUT).atZone(ZONE).toInstant().toEpochMilli();
		} catch (DateTimeParseException | NumberFormatException e) {
			return null;
		}
	}
}

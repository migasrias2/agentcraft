package dev.agentcraft.client.social;

import dev.agentcraft.client.foreman.Protocol.SocialPost;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Shared bits of the Social Desk calendar: week and day maths in the user's local time zone, the
 * status colours and words, and the one-line summaries. Used by the wall calendar (in world), the
 * calendar screen and the console commands, so all three say the same thing.
 */
public final class SocialCalendar {
	/** Monitor binding of the wall calendar (the Social Room's big screen). */
	public static final String BINDING = "calendar";

	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

	private SocialCalendar() {
	}

	// ------------------------------------------------------------------ time

	public static ZoneId zone() {
		return ZoneId.systemDefault();
	}

	public static LocalDate today() {
		return LocalDate.now(zone());
	}

	/** The Monday of the week {@code d} is in. */
	public static LocalDate weekStart(LocalDate d) {
		return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	public static LocalDate dayOf(long ms) {
		return Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate();
	}

	public static long startOfDay(LocalDate d) {
		return d.atStartOfDay(zone()).toInstant().toEpochMilli();
	}

	/** "09:30" in local time. */
	public static String time(long ms) {
		return HM.format(Instant.ofEpochMilli(ms).atZone(zone()));
	}

	/** "Tue 14 Oct". */
	public static String day(LocalDate d) {
		return DAY.format(d);
	}

	/** "Tue 14 Oct 2026, 09:30". */
	public static String dayTime(long ms) {
		return DAY_TIME.format(Instant.ofEpochMilli(ms).atZone(zone()));
	}

	/** "14 Oct". */
	public static String shortDay(LocalDate d) {
		return SHORT_DAY.format(d);
	}

	/** "October 2026". */
	public static String month(LocalDate d) {
		return MONTH.format(d);
	}

	/** "13 Oct - 19 Oct" for the week starting {@code monday}. */
	public static String weekRange(LocalDate monday) {
		return shortDay(monday) + " - " + shortDay(monday.plusDays(6));
	}

	// ------------------------------------------------------------------ posts

	/** Posts scheduled on {@code d}, earliest first (cancelled ones only when asked for). */
	public static List<SocialPost> onDay(List<SocialPost> all, LocalDate d, boolean withCancelled) {
		List<SocialPost> out = new ArrayList<>();
		for (SocialPost p : all) {
			if (p.scheduledAt() != null && dayOf(p.scheduledAt()).equals(d) && (withCancelled || !"cancelled".equals(p.status()))) {
				out.add(p);
			}
		}
		out.sort(Comparator.comparingLong((SocialPost p) -> p.scheduledAt()).thenComparingLong(SocialPost::createdAt));
		return out;
	}

	/** Open posts with no time yet. */
	public static List<SocialPost> unscheduled(List<SocialPost> all) {
		List<SocialPost> out = new ArrayList<>();
		for (SocialPost p : all) {
			if (p.scheduledAt() == null && p.open()) {
				out.add(p);
			}
		}
		return out;
	}

	/** Open posts (draft, approved, posting, failed), soonest first, those without a time last. */
	public static List<SocialPost> upcoming(List<SocialPost> all) {
		List<SocialPost> out = new ArrayList<>();
		for (SocialPost p : all) {
			if (p.open()) {
				out.add(p);
			}
		}
		out.sort(Comparator.comparingLong((SocialPost p) -> p.scheduledAt() == null ? Long.MAX_VALUE : p.scheduledAt()).thenComparingLong(SocialPost::createdAt));
		return out;
	}

	public static int count(List<SocialPost> all, String status) {
		int n = 0;
		for (SocialPost p : all) {
			if (p.status().equals(status)) {
				n++;
			}
		}
		return n;
	}

	/** One summary part: "3 drafts" and the status it counts (for the colour). */
	public record Part(String text, String status) {
	}

	/** The header counts: drafts, approved, posting, failed (everything open) and posted in the week starting {@code monday}. */
	public static List<Part> counts(List<SocialPost> all, @Nullable LocalDate monday) {
		List<Part> out = new ArrayList<>();
		int drafts = count(all, "draft");
		int approved = count(all, "approved");
		int posting = count(all, "posting");
		int failed = count(all, "failed");
		int posted = 0;
		for (SocialPost p : all) {
			if (p.status().equals("posted")) {
				long at = p.postedAt() != null ? p.postedAt() : p.scheduledAt() != null ? p.scheduledAt() : 0L;
				if (monday == null || (at > 0 && !dayOf(at).isBefore(monday) && dayOf(at).isBefore(monday.plusDays(7)))) {
					posted++;
				}
			}
		}
		if (drafts > 0) {
			out.add(new Part(drafts + (drafts == 1 ? " draft" : " drafts"), "draft"));
		}
		if (approved > 0) {
			out.add(new Part(approved + " approved", "approved"));
		}
		if (posting > 0) {
			out.add(new Part(posting + " posting", "posting"));
		}
		if (failed > 0) {
			out.add(new Part(failed + " failed", "failed"));
		}
		if (posted > 0) {
			out.add(new Part(posted + " posted", "posted"));
		}
		return out;
	}

	/** "3 drafts · 2 approved · 1 failed" or "nothing planned". */
	public static String countsLine(List<SocialPost> all, @Nullable LocalDate monday) {
		List<Part> parts = counts(all, monday);
		if (parts.isEmpty()) {
			return "nothing planned";
		}
		StringBuilder b = new StringBuilder();
		for (Part p : parts) {
			if (!b.isEmpty()) {
				b.append(" · ");
			}
			b.append(p.text());
		}
		return b.toString();
	}

	// ------------------------------------------------------------------ looks

	/** Status colour (ARGB) that reads on the dark wall screen and on paper alike. */
	public static int statusColor(String status) {
		return switch (status) {
			case "approved" -> 0xFF6FAE6E;
			case "posting" -> 0xFFE6A83C;
			case "posted" -> 0xFF4E9C92;
			case "failed" -> 0xFFD9534F;
			case "cancelled" -> 0xFF7C756B;
			default -> 0xFF9C9488; // draft
		};
	}

	/** Status colour for text on paper (darker, readable on cream). */
	public static int statusInk(String status) {
		return switch (status) {
			case "approved" -> 0xFF3F7A3E;
			case "posting" -> 0xFF9A6408;
			case "posted" -> 0xFF2F7068;
			case "failed" -> 0xFFA3302C;
			case "cancelled" -> 0xFF8A8378;
			default -> 0xFF6B645A; // draft
		};
	}

	public static String statusLabel(String status) {
		return switch (status) {
			case "approved" -> "approved";
			case "posting" -> "posting…";
			case "posted" -> "posted";
			case "failed" -> "failed";
			case "cancelled" -> "cancelled";
			default -> "draft";
		};
	}

	public static String platformName(String platform) {
		return "x".equals(platform) ? "X" : "LinkedIn";
	}

	/** The badge text of a platform ("X" / "in"). */
	public static String badge(String platform) {
		return "x".equals(platform) ? "X" : "in";
	}

	public static int platformColor(String platform) {
		return "x".equals(platform) ? 0xFF000000 : 0xFF0A66C2;
	}

	/** The post text on one line (whitespace collapsed). */
	public static String oneLine(String text) {
		return text.replaceAll("\\s+", " ").strip();
	}

	/** The first {@code maxChars} characters of the text on one line, with an ellipsis when cut. */
	public static String firstWords(String text, int maxChars) {
		String t = oneLine(text);
		if (t.length() <= maxChars) {
			return t;
		}
		int cut = t.lastIndexOf(' ', maxChars - 1);
		return t.substring(0, cut > maxChars / 2 ? cut : maxChars - 1).stripTrailing() + "…";
	}
}

package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Java mirror of the Foreman protocol v1 (docs/protocol.md, generated from foreman/src/protocol.ts).
 * Field names are exact. Optional fields are nullable (boxed when numeric/boolean); required lists
 * are never null (normalised to empty). Unknown JSON fields are ignored and unknown enum values map
 * to {@code UNKNOWN}, so a newer Foreman never breaks the mod.
 */
public final class Protocol {
	public static final int VERSION = 1;

	private Protocol() {
	}

	/** Marker for protocol enums: wire value = lower-case constant name; unknown values map to UNKNOWN. */
	public interface Wire {
		default String wire() {
			return ((Enum<?>) this).name().toLowerCase(Locale.ROOT);
		}
	}

	// ------------------------------------------------------------------ enums

	public enum AgentState implements Wire {
		IDLE, THINKING, READING, EDITING, RUNNING, TESTING, WAITING_USER, BLOCKED, DONE, ERROR, UNKNOWN;

		/** Status-dot / lamp family: idle, thinking, working, waiting, error, done. */
		public String family() {
			return switch (this) {
				case THINKING -> "thinking";
				case READING, EDITING, RUNNING, TESTING -> "working";
				case WAITING_USER -> "waiting";
				case BLOCKED, ERROR -> "error";
				case DONE -> "done";
				default -> "idle";
			};
		}
	}

	public enum Station implements Wire {
		DESK, LIBRARY, TERMINAL, TESTBENCH, MERGESTATION, MEETING, LOUNGE, USER, UNKNOWN
	}

	public enum AgentRole implements Wire {
		LEAD, WORKER, UNKNOWN
	}

	public enum TaskStatus implements Wire {
		TODO, DOING, REVIEW, DONE, BLOCKED, CANCELLED, UNKNOWN
	}

	public enum CiStatus implements Wire {
		UNKNOWN, RUNNING, PASS, FAIL
	}

	public enum LogKind implements Wire {
		TEXT, TOOL, RESULT, ERROR, DIFF, UNKNOWN
	}

	public enum DecisionKind implements Wire {
		QUESTION, PERMISSION, MERGE, UNKNOWN
	}

	public enum DecisionStatus implements Wire {
		OPEN, ANSWERED, CANCELLED, UNKNOWN
	}

	public enum GoalStatus implements Wire {
		PLANNING, ACTIVE, DONE, FAILED, CANCELLED, UNKNOWN
	}

	public enum FeedKind implements Wire {
		GOAL, PLAN, TASK, MESSAGE, DECISION, MERGE, CI, MEMORY, SYSTEM, ERROR, USER, UNKNOWN
	}

	public enum NotifyLevel implements Wire {
		INFO, WARN, NEED_USER, UNKNOWN
	}

	public enum WorktreeStatus implements Wire {
		ACTIVE, MERGED, ABANDONED, UNKNOWN
	}

	public enum BackendName implements Wire {
		SIM, CLAUDE, CODEX, UNKNOWN
	}

	public enum AuthStatus implements Wire {
		OK, FAILED, UNKNOWN, CHECKING
	}

	public enum DiffFileStatus implements Wire {
		ADDED, MODIFIED, DELETED, RENAMED, UNKNOWN
	}

	public enum DiffLineKind implements Wire {
		ADD, DEL, CTX, UNKNOWN
	}

	/** Exact option labels (protocol.md). */
	public static final String MERGE = "Merge";
	public static final String REQUEST_CHANGES = "Request changes";
	public static final String REJECT = "Reject";
	public static final String ALLOW_ONCE = "Allow once";
	public static final String ALWAYS_ALLOW = "Always allow for this agent";
	public static final String DENY = "Deny";

	// ------------------------------------------------------------------ entities

	public record Agent(String id, String name, AgentRole role, @Nullable String title, String color, @Nullable String accent, String skin,
		AgentState state, String activity, Station station, @Nullable String taskId, @Nullable String repoId, @Nullable String worktree,
		@Nullable Boolean paused, @Nullable Boolean active, @Nullable String engine, @Nullable String model, @Nullable String team) {
		public Agent {
			name = name == null ? id : name;
			role = role == null ? AgentRole.UNKNOWN : role;
			color = color == null ? "#9C9488" : color;
			skin = skin == null ? id : skin;
			state = state == null ? AgentState.UNKNOWN : state;
			activity = activity == null ? "" : activity;
			station = station == null ? Station.UNKNOWN : station;
		}

		/** false = off shift (render idle in the lounge). Missing = true. */
		public boolean isActive() {
			return active == null || active;
		}

		public boolean isPaused() {
			return paused != null && paused;
		}

		/** True for the Social Desk agents (Nova, Sable): they sit in the Social room, never in the coding hall. */
		public boolean isSocial() {
			return "social".equals(team);
		}

		/** True for the Content Room agents (Iris, Reel, Vega, Cleo): they sit in the Content room, never in the coding hall. */
		public boolean isContent() {
			return "content".equals(team);
		}
	}

	public record LogEntry(long ts, LogKind kind, String text) {
		public LogEntry {
			kind = kind == null ? LogKind.UNKNOWN : kind;
			text = text == null ? "" : text;
		}
	}

	public record Task(String id, String title, @Nullable String description, TaskStatus status, @Nullable String assignee, List<String> deps,
		@Nullable String repoId, @Nullable String goalId, int priority, @Nullable String branch, @Nullable String worktree, CiStatus ci,
		@Nullable String blockedReason, @Nullable String summary, @Nullable String createdBy, long createdAt, long updatedAt) {
		public Task {
			title = title == null ? id : title;
			status = status == null ? TaskStatus.UNKNOWN : status;
			deps = deps == null ? List.of() : List.copyOf(deps);
			ci = ci == null ? CiStatus.UNKNOWN : ci;
		}
	}

	public record DecisionAnswer(@Nullable String option, @Nullable String text, long ts) {
	}

	public record Decision(String id, String agentId, DecisionKind kind, String question, List<String> options, @Nullable String context,
		DecisionStatus status, @Nullable DecisionAnswer answer, @Nullable String taskId, @Nullable String repoId, @Nullable String worktree,
		@Nullable String tool, long createdAt) {
		public Decision {
			kind = kind == null ? DecisionKind.UNKNOWN : kind;
			question = question == null ? "" : question;
			options = options == null ? List.of() : List.copyOf(options);
			status = status == null ? DecisionStatus.UNKNOWN : status;
		}

		public boolean isOpen() {
			return status == DecisionStatus.OPEN;
		}
	}

	public record Worktree(String id, String agentId, @Nullable String taskId, String branch, String base, String path, WorktreeStatus status,
		int ahead, int files, int additions, int deletions) {
		public Worktree {
			status = status == null ? WorktreeStatus.UNKNOWN : status;
		}
	}

	public record Repo(String id, String name, String path, String branch, @Nullable String head, boolean dirty, List<Worktree> worktrees,
		CiStatus ci) {
		public Repo {
			name = name == null ? id : name;
			worktrees = worktrees == null ? List.of() : List.copyOf(worktrees);
			ci = ci == null ? CiStatus.UNKNOWN : ci;
		}
	}

	public record MemoryEntry(String id, String scope, String title, String body, long updated, @Nullable String author) {
		public MemoryEntry {
			scope = scope == null ? "shared" : scope;
			title = title == null ? id : title;
			body = body == null ? "" : body;
		}
	}

	public record Goal(String id, String text, double progress, GoalStatus status, @Nullable String repoId, long createdAt, long updatedAt) {
		public Goal {
			text = text == null ? "" : text;
			status = status == null ? GoalStatus.UNKNOWN : status;
		}
	}

	public record FeedItem(long ts, FeedKind kind, String text, @Nullable String agentId, @Nullable String to) {
		public FeedItem {
			kind = kind == null ? FeedKind.UNKNOWN : kind;
			text = text == null ? "" : text;
		}
	}

	public record ForemanStatus(String version, BackendName backend, AuthStatus auth, @Nullable String message, @Nullable String account,
		@Nullable Double speed, @Nullable Boolean showcase, @Nullable Double costUsd, @Nullable String userName,
		@Nullable Boolean autoApprove) {
		public ForemanStatus {
			version = version == null ? "?" : version;
			backend = backend == null ? BackendName.UNKNOWN : backend;
			auth = auth == null ? AuthStatus.UNKNOWN : auth;
		}
	}

	public record AgentLogs(String agentId, List<LogEntry> entries) {
		public AgentLogs {
			entries = entries == null ? List.of() : List.copyOf(entries);
		}
	}

	public record DiffLine(DiffLineKind kind, String text, @Nullable Integer oldNo, @Nullable Integer newNo) {
		public DiffLine {
			kind = kind == null ? DiffLineKind.UNKNOWN : kind;
			text = text == null ? "" : text;
		}
	}

	public record DiffHunk(String header, int oldStart, int oldLines, int newStart, int newLines, List<DiffLine> lines) {
		public DiffHunk {
			header = header == null ? "" : header;
			lines = lines == null ? List.of() : List.copyOf(lines);
		}
	}

	public record DiffFile(String path, @Nullable String oldPath, DiffFileStatus status, boolean binary, int additions, int deletions,
		List<DiffHunk> hunks) {
		public DiffFile {
			status = status == null ? DiffFileStatus.UNKNOWN : status;
			hunks = hunks == null ? List.of() : List.copyOf(hunks);
		}
	}

	public record DiffStats(int files, int additions, int deletions) {
	}

	// ------------------------------------------------------------------ Foreman -> mod messages

	public record Snapshot(ForemanStatus foreman, List<Agent> agents, List<Task> tasks, List<Decision> decisions, List<Repo> repos,
		List<MemoryEntry> memory, @Nullable Goal goal, List<Goal> goals, List<FeedItem> feed, List<AgentLogs> logs, @Nullable List<AgentScreen> screens,
		@Nullable List<Media> media, @Nullable List<Doc> docs, @Nullable List<SocialPost> socialPosts,
		@Nullable List<ContentChannel> contentChannels, @Nullable List<ContentVideo> contentVideos, @Nullable List<ContentRef> contentRefs,
		@Nullable List<ContentStat> contentStats, @Nullable List<ContentChannelStats> contentChannelStats) {
		public Snapshot {
			agents = agents == null ? List.of() : List.copyOf(agents);
			tasks = tasks == null ? List.of() : List.copyOf(tasks);
			decisions = decisions == null ? List.of() : List.copyOf(decisions);
			repos = repos == null ? List.of() : List.copyOf(repos);
			memory = memory == null ? List.of() : List.copyOf(memory);
			goals = goals == null ? List.of() : List.copyOf(goals);
			feed = feed == null ? List.of() : List.copyOf(feed);
			logs = logs == null ? List.of() : List.copyOf(logs);
			screens = screens == null ? List.of() : List.copyOf(screens);
			media = media == null ? List.of() : List.copyOf(media);
			docs = docs == null ? List.of() : List.copyOf(docs);
			socialPosts = socialPosts == null ? List.of() : List.copyOf(socialPosts);
			contentChannels = contentChannels == null ? List.of() : List.copyOf(contentChannels);
			contentVideos = contentVideos == null ? List.of() : List.copyOf(contentVideos);
			contentRefs = contentRefs == null ? List.of() : List.copyOf(contentRefs);
			contentStats = contentStats == null ? List.of() : List.copyOf(contentStats);
			contentChannelStats = contentChannelStats == null ? List.of() : List.copyOf(contentChannelStats);
		}
	}

	public record AgentUpsert(Agent agent) {
	}

	public record AgentLog(String agentId, List<LogEntry> entries) {
		public AgentLog {
			entries = entries == null ? List.of() : List.copyOf(entries);
		}
	}

	/** A screenshot an agent got from a tool (browser, Roblox Studio, Blender...): base64 PNG/JPEG, throttled by the Foreman. */
	public record AgentScreen(String agentId, String mime, String data, String tool, long at) {
		public AgentScreen {
			mime = mime == null ? "" : mime;
			data = data == null ? "" : data;
			tool = tool == null ? "" : tool;
		}
	}

	/**
	 * Something an agent handed the user with show_media: an image (1 frame) or a video (up to ~150 JPEG
	 * frames, <= 640 px wide, played back at {@code fps}). Frames are base64 images; the mod keeps only
	 * metadata in {@code ForemanState} and decodes frames in {@code MediaStore}.
	 */
	public record Media(String id, String agentId, String kind, String name, @Nullable String caption, String mime, List<String> frames,
		@Nullable Double fps, @Nullable Long durationMs, @Nullable Boolean hasAudio, long at) {
		public Media {
			kind = kind == null ? "image" : kind;
			name = name == null ? id : name;
			mime = mime == null ? "" : mime;
			frames = frames == null ? List.of() : List.copyOf(frames);
		}

		public boolean isVideo() {
			return "video".equals(kind);
		}

		/** Same item without its (large) frames, for the long-lived state. */
		public Media withoutFrames() {
			return frames.isEmpty() ? this : new Media(id, agentId, kind, name, caption, mime, List.of(), fps, durationMs, hasAudio, at);
		}
	}

	public record MediaAdd(Media item) {
	}

	/**
	 * A markdown document an agent handed the user (hand_over): {@code note} is what it says when giving
	 * it over, {@code read} flips once the user opened it. Replaced by id ({@code doc.upsert}).
	 */
	public record Doc(String id, String agentId, String title, String name, @Nullable String path, String markdown, @Nullable String note,
		@Nullable String taskId, boolean read, long at) {
		public Doc {
			title = title == null || title.isBlank() ? (name == null ? id : name) : title;
			name = name == null ? "" : name;
			markdown = markdown == null ? "" : markdown;
		}

		public Doc withRead(boolean r) {
			return new Doc(id, agentId, title, name, path, markdown, note, taskId, r, at);
		}
	}

	public record DocUpsert(Doc doc) {
	}

	/**
	 * A Social Desk post (X or LinkedIn) on the calendar. {@code status}: draft, approved, posting, posted, failed,
	 * cancelled (kept as the wire string so new states degrade gracefully). Times are epoch ms. Replaced by id
	 * ({@code social.post.upsert}).
	 */
	public record SocialPost(String id, String platform, String text, @Nullable Long scheduledAt, String status, @Nullable String authorId,
		@Nullable String note, @Nullable List<String> mediaPaths, @Nullable String url, @Nullable String error, long createdAt, long updatedAt,
		@Nullable Long postedAt) {
		public SocialPost {
			platform = platform == null ? "x" : platform;
			text = text == null ? "" : text;
			status = status == null ? "draft" : status;
			mediaPaths = mediaPaths == null ? List.of() : List.copyOf(mediaPaths);
		}

		public boolean isX() {
			return "x".equals(platform);
		}

		/** Character limit of the platform (the Foreman can allow longer X posts; this is the usual one). */
		public int limit() {
			return isX() ? 280 : 3000;
		}

		/** Still on the calendar as something that will or may go out (not finished). */
		public boolean open() {
			return status.equals("draft") || status.equals("approved") || status.equals("posting") || status.equals("failed");
		}
	}

	public record SocialPostUpsert(SocialPost post) {
	}

	// ------------------------------------------------------------------ Content Room (YouTube channels)

	/** A YouTube channel the Content Room runs. {@code playbook} is the markdown Iris/Vega keep of what works. Replaced by id ({@code content.channel.upsert}). */
	public record ContentChannel(String id, String name, @Nullable String url, @Nullable String youtubeId, @Nullable String niche,
		@Nullable String brief, @Nullable String playbook, boolean autopilot, int postsPerWeek, boolean connected, @Nullable String statsSource, long createdAt,
		long updatedAt) {
		public ContentChannel {
			name = name == null ? id : name;
			postsPerWeek = postsPerWeek <= 0 ? 35 : postsPerWeek;
		}
	}

	public record ContentComment(String by, String text, long at) {
		public ContentComment {
			by = by == null ? "user" : by;
			text = text == null ? "" : text;
		}
	}

	public record PostMortem(long at, String horizon, String text) {
		public PostMortem {
			horizon = horizon == null ? "" : horizon;
			text = text == null ? "" : text;
		}
	}

	/**
	 * A video in the pipeline. {@code status}: idea, approved, producing, review, scheduled, publishing, published, rejected, failed
	 * (kept as the wire string so new states degrade gracefully). Times are epoch ms. Replaced by id ({@code content.video.upsert}).
	 */
	public record ContentVideo(String id, String channelId, String status, String format, String title, @Nullable String hook, @Nullable String pitch,
		@Nullable String script, @Nullable String description, @Nullable List<String> tags, @Nullable List<String> refIds, @Nullable String filePath,
		@Nullable String thumbnailPath, @Nullable Long scheduledAt, @Nullable String url, @Nullable String youtubeVideoId, @Nullable String error,
		@Nullable String authorId, List<ContentComment> comments, long createdAt, long updatedAt, @Nullable Long publishedAt,
		@Nullable List<PostMortem> postMortems) {
		public ContentVideo {
			status = status == null ? "idea" : status;
			format = format == null ? "long" : format;
			title = title == null ? id : title;
			tags = tags == null ? List.of() : List.copyOf(tags);
			refIds = refIds == null ? List.of() : List.copyOf(refIds);
			comments = comments == null ? List.of() : List.copyOf(comments);
			postMortems = postMortems == null ? List.of() : List.copyOf(postMortems);
		}

		public boolean isShort() {
			return "short".equals(format);
		}

		/** Not finished: still moving through the pipeline (or waiting for the user). */
		public boolean open() {
			return !status.equals("published") && !status.equals("rejected");
		}
	}

	/** One snapshot of one video ({@code ctr} and {@code avgViewPct} are 0..1; {@code retention} is 21 points, 0..100 % of the video). */
	public record ContentStat(String videoId, long at, long views, @Nullable Long likes, @Nullable Long comments, @Nullable Double avgViewDurationSec,
		@Nullable Double avgViewPct, @Nullable Double watchMinutes, @Nullable Long subsGained, @Nullable Long impressions, @Nullable Double ctr,
		@Nullable List<Double> retention, @Nullable Double stayedToWatchPct, @Nullable Double swipeAwayPct) {
		public ContentStat {
			retention = retention == null ? List.of() : List.copyOf(retention);
		}
	}

	/** A studied reference video: {@code dir} holds frames/, sheets/, transcript.txt, meta.json, breakdown.md. Replaced by id ({@code content.ref.upsert}). */
	public record ContentRef(String id, String source, @Nullable String title, @Nullable String channel, @Nullable Double durationSec, @Nullable Long views,
		@Nullable Long likes, @Nullable String uploadDate, @Nullable String dir, int frameCount, int sheetCount, boolean hasTranscript,
		@Nullable String studiedBy, long at) {
		public ContentRef {
			source = source == null ? "" : source;
		}
	}

	public record DailyStat(String date, long views, @Nullable Long subsGained, @Nullable Double watchMinutes) {
		public DailyStat {
			date = date == null ? "" : date;
		}
	}

	public record ContentChannelStats(String channelId, long at, @Nullable Long subscribers, @Nullable Long totalViews, @Nullable Long videoCount,
		@Nullable Long views28d, @Nullable Double watchMinutes28d, @Nullable Long subsGained28d, @Nullable List<DailyStat> daily) {
		public ContentChannelStats {
			daily = daily == null ? List.of() : List.copyOf(daily);
		}
	}

	public record ContentChannelUpsert(ContentChannel channel) {
	}

	public record ContentVideoUpsert(ContentVideo video) {
	}

	public record ContentRefUpsert(ContentRef ref) {
	}

	/** After each analytics sync: the full latest set (stats per video, history included, and the channel-level numbers). */
	public record ContentStatsMsg(@Nullable List<ContentStat> stats, @Nullable List<ContentChannelStats> channelStats) {
		public ContentStatsMsg {
			stats = stats == null ? List.of() : List.copyOf(stats);
			channelStats = channelStats == null ? List.of() : List.copyOf(channelStats);
		}
	}

	public record AgentSay(String agentId, String text, @Nullable String to, long ts) {
		public AgentSay {
			text = text == null ? "" : text;
		}
	}

	public record TaskUpsert(Task task) {
	}

	public record DecisionUpsert(Decision decision) {
	}

	public record RepoUpsert(Repo repo) {
	}

	public record MemoryUpsert(MemoryEntry entry) {
	}

	public record GoalUpsert(Goal goal) {
	}

	public record FeedAdd(FeedItem item) {
	}

	public record Diff(String requestId, String repoId, String worktree, @Nullable String base, @Nullable String branch, List<DiffFile> files,
		DiffStats stats, boolean truncated, @Nullable String error) {
		public Diff {
			files = files == null ? List.of() : List.copyOf(files);
			stats = stats == null ? new DiffStats(0, 0, 0) : stats;
		}
	}

	public record Notify(NotifyLevel level, String text, @Nullable String decisionId, long ts) {
		public Notify {
			level = level == null ? NotifyLevel.UNKNOWN : level;
			text = text == null ? "" : text;
		}
	}

	public record ForemanStatusMsg(ForemanStatus status) {
	}

	/** Reply to a client message with an id. {@code result} e.g. {goalId} for goal.submit. */
	public record Ack(String re, boolean ok, @Nullable String error, @Nullable JsonObject result) {
	}

	public record ErrorMsg(String message, @Nullable String re) {
	}
}

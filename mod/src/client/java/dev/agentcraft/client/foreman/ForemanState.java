package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentLog;
import dev.agentcraft.client.foreman.Protocol.AgentLogs;
import dev.agentcraft.client.foreman.Protocol.AgentScreen;
import dev.agentcraft.client.foreman.Protocol.AgentSay;
import dev.agentcraft.client.foreman.Protocol.AgentUpsert;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.client.foreman.Protocol.DecisionUpsert;
import dev.agentcraft.client.foreman.Protocol.Doc;
import dev.agentcraft.client.foreman.Protocol.DocUpsert;
import dev.agentcraft.client.foreman.Protocol.FeedAdd;
import dev.agentcraft.client.foreman.Protocol.FeedItem;
import dev.agentcraft.client.foreman.Protocol.ForemanStatus;
import dev.agentcraft.client.foreman.Protocol.ForemanStatusMsg;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Media;
import dev.agentcraft.client.foreman.Protocol.MediaAdd;
import dev.agentcraft.client.foreman.Protocol.ContentChannel;
import dev.agentcraft.client.foreman.Protocol.ContentChannelStats;
import dev.agentcraft.client.foreman.Protocol.ContentChannelUpsert;
import dev.agentcraft.client.foreman.Protocol.ContentRef;
import dev.agentcraft.client.foreman.Protocol.ContentRefUpsert;
import dev.agentcraft.client.foreman.Protocol.ContentStat;
import dev.agentcraft.client.foreman.Protocol.ContentStatsMsg;
import dev.agentcraft.client.foreman.Protocol.ContentVideo;
import dev.agentcraft.client.foreman.Protocol.ContentVideoUpsert;
import dev.agentcraft.client.foreman.Protocol.SocialPost;
import dev.agentcraft.client.foreman.Protocol.SocialPostUpsert;
import dev.agentcraft.client.foreman.Protocol.GoalUpsert;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import dev.agentcraft.client.foreman.Protocol.MemoryEntry;
import dev.agentcraft.client.foreman.Protocol.MemoryUpsert;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.RepoUpsert;
import dev.agentcraft.client.foreman.Protocol.Snapshot;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.client.foreman.Protocol.TaskUpsert;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The client-side model of the Foreman's state: agents, tasks, decisions, repos, memory, goals,
 * feed, per-agent log tails, the latest speech per agent, recent notifications, the Foreman status
 * and the link state.
 *
 * <p><b>Threading:</b> mutated only on the client (render) thread (the link parses frames on its
 * own thread and hands them over with {@code Minecraft.execute}). Read it from the client thread:
 * renderers, screens, HUD, client tick handlers. From other threads use
 * {@code DevBridge.onClient(...)}. The getters return unmodifiable live views; copy if you keep
 * them. Maps iterate in Foreman order (snapshot order, new entries appended).
 *
 * <p>While the link is down the last known state is kept ({@link #isStale()} true) so the world
 * does not go empty; the next snapshot replaces everything.
 */
public final class ForemanState {
	public static final int LOG_TAIL = 200;
	public static final int FEED_TAIL = 200;
	public static final int NOTIFY_TAIL = 20;
	/** Media items kept in the gallery. */
	public static final int MEDIA_TAIL = 12;
	/** Handed-over documents kept (the Foreman sends at most 30). */
	public static final int DOC_MAX = 30;

	private final Map<String, Agent> agents = new LinkedHashMap<>();
	private final Map<String, Task> tasks = new LinkedHashMap<>();
	private final Map<String, Decision> decisions = new LinkedHashMap<>();
	private final Map<String, Repo> repos = new LinkedHashMap<>();
	private final Map<String, MemoryEntry> memory = new LinkedHashMap<>();
	private final Map<String, Goal> goals = new LinkedHashMap<>();
	private final Map<String, Deque<LogEntry>> logs = new HashMap<>();
	private final Map<String, AgentSay> lastSay = new HashMap<>();
	private final Map<String, AgentScreen> screens = new HashMap<>();
	private final Deque<Media> media = new ArrayDeque<>();
	private final Map<String, Doc> docs = new LinkedHashMap<>();
	private final Map<String, SocialPost> socialPosts = new LinkedHashMap<>();
	private final Map<String, ContentChannel> contentChannels = new LinkedHashMap<>();
	private final Map<String, ContentVideo> contentVideos = new LinkedHashMap<>();
	private final Map<String, ContentRef> contentRefs = new LinkedHashMap<>();
	/** Stat history per YouTube video id, oldest first. */
	private final Map<String, List<ContentStat>> contentStats = new HashMap<>();
	private final Map<String, ContentChannelStats> contentChannelStats = new HashMap<>();
	private final Deque<FeedItem> feed = new ArrayDeque<>();
	private final Deque<Notify> notifications = new ArrayDeque<>();
	private @Nullable Goal goal;
	private @Nullable ForemanStatus status;
	private LinkStatus link;
	private long revision;
	private long snapshotAt;
	private long lastMessageAt;
	private int snapshots;

	private final List<ForemanListener> listeners = new CopyOnWriteArrayList<>();

	ForemanState(LinkStatus initial) {
		this.link = initial;
	}

	// ------------------------------------------------------------------ read API

	public Map<String, Agent> agents() {
		return Collections.unmodifiableMap(agents);
	}

	public @Nullable Agent agent(String id) {
		return agents.get(id);
	}

	public Map<String, Task> tasks() {
		return Collections.unmodifiableMap(tasks);
	}

	public @Nullable Task task(String id) {
		return tasks.get(id);
	}

	/** Tasks with this status, highest priority first, then oldest first. */
	public List<Task> tasksWithStatus(TaskStatus s) {
		List<Task> out = new ArrayList<>();
		for (Task t : tasks.values()) {
			if (t.status() == s) {
				out.add(t);
			}
		}
		out.sort(Comparator.comparingInt(Task::priority).reversed().thenComparingLong(Task::createdAt));
		return out;
	}

	public Map<String, Decision> decisions() {
		return Collections.unmodifiableMap(decisions);
	}

	public @Nullable Decision decision(String id) {
		return decisions.get(id);
	}

	/** Open decisions, oldest first. */
	public List<Decision> openDecisions() {
		List<Decision> out = new ArrayList<>();
		for (Decision d : decisions.values()) {
			if (d.isOpen()) {
				out.add(d);
			}
		}
		out.sort(Comparator.comparingLong(Decision::createdAt));
		return out;
	}

	/** Oldest open decision of this kind, or null. */
	public @Nullable Decision oldestOpen(DecisionKind kind) {
		for (Decision d : openDecisions()) {
			if (d.kind() == kind) {
				return d;
			}
		}
		return null;
	}

	public Map<String, Repo> repos() {
		return Collections.unmodifiableMap(repos);
	}

	public @Nullable Repo repo(String id) {
		return repos.get(id);
	}

	public Map<String, MemoryEntry> memory() {
		return Collections.unmodifiableMap(memory);
	}

	/** The current (latest) goal, or null. */
	public @Nullable Goal goal() {
		return goal;
	}

	/** All goals, oldest first. */
	public Map<String, Goal> goals() {
		return Collections.unmodifiableMap(goals);
	}

	/** Most recent feed items, oldest first (bounded to {@value #FEED_TAIL}); a read-only live view. */
	public SequencedCollection<FeedItem> feed() {
		return Collections.unmodifiableSequencedCollection(feed);
	}

	/** Log tail of one agent, oldest first (bounded to {@value #LOG_TAIL}); empty if none. */
	public List<LogEntry> logs(String agentId) {
		Deque<LogEntry> d = logs.get(agentId);
		return d == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(d));
	}

	/** Number of log entries kept for an agent (cheap; no copy). */
	public int logCount(String agentId) {
		Deque<LogEntry> d = logs.get(agentId);
		return d == null ? 0 : d.size();
	}

	/** Latest live screen frame per agent (base64 image, in memory only), unmodifiable. */
	public Map<String, AgentScreen> screens() {
		return Collections.unmodifiableMap(screens);
	}

	/** Gallery of media agents handed the user, newest last, metadata only (frames live in the client's MediaStore). */
	public List<Media> media() {
		return List.copyOf(media);
	}

	/** Handed-over documents, oldest first (a copy). */
	public List<Doc> docs() {
		List<Doc> out = new ArrayList<>(docs.values());
		out.sort(Comparator.comparingLong(Doc::at));
		return out;
	}

	/** The Social Desk calendar: every post, scheduled time first (posts without a time last), then creation order (a copy). */
	public List<SocialPost> socialPosts() {
		List<SocialPost> out = new ArrayList<>(socialPosts.values());
		out.sort(Comparator.comparingLong((SocialPost p) -> p.scheduledAt() == null ? Long.MAX_VALUE : p.scheduledAt()).thenComparingLong(SocialPost::createdAt));
		return out;
	}

	public @Nullable SocialPost socialPost(String id) {
		return socialPosts.get(id);
	}

	/** Content Room channels in creation order (a copy). */
	public List<ContentChannel> contentChannels() {
		return List.copyOf(contentChannels.values());
	}

	public @Nullable ContentChannel contentChannel(String id) {
		return contentChannels.get(id);
	}

	/** Videos of a channel (null = all), newest first (a copy). */
	public List<ContentVideo> contentVideos(@Nullable String channelId) {
		List<ContentVideo> out = new ArrayList<>();
		for (ContentVideo v : contentVideos.values()) {
			if (channelId == null || channelId.equals(v.channelId())) {
				out.add(v);
			}
		}
		out.sort(Comparator.comparingLong(ContentVideo::createdAt).reversed());
		return out;
	}

	public @Nullable ContentVideo contentVideo(String id) {
		return contentVideos.get(id);
	}

	/** Studied references, newest first (a copy). */
	public List<ContentRef> contentRefs() {
		List<ContentRef> out = new ArrayList<>(contentRefs.values());
		out.sort(Comparator.comparingLong(ContentRef::at).reversed());
		return out;
	}

	/** Stat history of one YouTube video id, oldest first (empty when unknown). */
	public List<ContentStat> contentStatHistory(String youtubeVideoId) {
		return contentStats.getOrDefault(youtubeVideoId, List.of());
	}

	public @Nullable ContentStat contentStat(String youtubeVideoId) {
		List<ContentStat> h = contentStats.get(youtubeVideoId);
		return h == null || h.isEmpty() ? null : h.get(h.size() - 1);
	}

	public @Nullable ContentChannelStats contentChannelStats(String channelId) {
		return contentChannelStats.get(channelId);
	}

	/** Optimistic local "read" (the Foreman's doc.upsert confirms it). Client thread. */
	public void markDocRead(String id) {
		Doc prev = docs.get(id);
		if (prev != null && !prev.read()) {
			Doc now = prev.withRead(true);
			docs.put(id, now);
			fire(l -> l.onDoc(prev, now));
		}
	}

	public @Nullable Doc doc(String id) {
		return docs.get(id);
	}

	/** Newest unread document of this agent, or null. */
	public @Nullable Doc unreadDoc(String agentId) {
		Doc best = null;
		for (Doc d : docs.values()) {
			if (!d.read() && d.agentId().equals(agentId) && (best == null || d.at() >= best.at())) {
				best = d;
			}
		}
		return best;
	}

	/** True while the Foreman runs agent tool calls without asking (ForemanStatus.autoApprove). */
	public boolean autoApprove() {
		ForemanStatus st = status;
		return st != null && Boolean.TRUE.equals(st.autoApprove());
	}

	/** The last thing this agent said (speech bubble source), or null. */
	public @Nullable AgentSay lastSay(String agentId) {
		return lastSay.get(agentId);
	}

	/** Recent notify messages, oldest first; a read-only live view. */
	public SequencedCollection<Notify> notifications() {
		return Collections.unmodifiableSequencedCollection(notifications);
	}

	/** Backend/auth status of the Foreman, or null before the first snapshot. */
	public @Nullable ForemanStatus status() {
		return status;
	}

	public LinkStatus link() {
		return link;
	}

	/** True when the model holds data but the link is not live (show it as "last known"). */
	public boolean isStale() {
		return !link.synced();
	}

	/** True once any snapshot was received (the model has real data). */
	public boolean hasData() {
		return snapshots > 0;
	}

	/** Increments on every change (snapshot, upsert, append, link change). Use it to cache derived data. */
	public long revision() {
		return revision;
	}

	public long snapshotAt() {
		return snapshotAt;
	}

	public long lastMessageAt() {
		return lastMessageAt;
	}

	public int snapshotCount() {
		return snapshots;
	}

	// ------------------------------------------------------------------ listeners

	public void addListener(ForemanListener l) {
		listeners.add(l);
	}

	public void removeListener(ForemanListener l) {
		listeners.remove(l);
	}

	private void fire(Consumer<ForemanListener> call) {
		revision++;
		for (ForemanListener l : listeners) {
			try {
				call.accept(l);
				l.onChange(revision);
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("Foreman listener {} failed", l.getClass().getName(), t);
			}
		}
	}

	// ------------------------------------------------------------------ mutation (client thread, from ForemanLink)

	void setLink(LinkStatus status) {
		this.link = status;
		fire(l -> l.onConnection(status));
	}

	// ------------------------------------------------------------------ hold queue + injection (video choreography)

	/** Upper bound of the hold queue (oldest messages are dropped beyond it; the release reconnects anyway). */
	public static final int HOLD_MAX = 20_000;
	private boolean hold;
	private final Deque<Object[]> held = new ArrayDeque<>();
	private int heldDropped;

	/**
	 * A message from the live link. While {@link #setHold held}, it is queued instead of applied, so
	 * a choreographed shot sees only what it injects; otherwise it is applied at once.
	 */
	void receive(String type, JsonObject json) {
		if (hold) {
			held.addLast(new Object[] {type, json});
			while (held.size() > HOLD_MAX) {
				held.removeFirst();
				heldDropped++;
			}
			return;
		}
		apply(type, json);
	}

	/** True while live Foreman messages are being held back. */
	public boolean isHeld() {
		return hold;
	}

	/** Number of live messages waiting in the hold queue. */
	public int heldCount() {
		return held.size();
	}

	/** Hold live messages (queue them) instead of applying them. Client thread. */
	public void setHold(boolean on) {
		if (on && !hold) {
			held.clear();
			heldDropped = 0;
		}
		hold = on;
	}

	/**
	 * End the hold. {@code replay}: apply the queued messages in order; otherwise drop them (the
	 * caller then reconnects for a fresh snapshot). Returns how many were queued.
	 */
	public int releaseHold(boolean replay) {
		hold = false;
		int n = held.size();
		if (replay) {
			while (!held.isEmpty()) {
				Object[] m = held.removeFirst();
				try {
					apply((String) m[0], (JsonObject) m[1]);
				} catch (Exception e) {
					AgentCraft.LOGGER.warn("Replaying held Foreman message '{}' failed", m[0], e);
				}
			}
		} else {
			held.clear();
		}
		return n;
	}

	public int heldDropped() {
		return heldDropped;
	}

	/**
	 * Apply a Foreman message as if the Foreman had sent it (dev.foreman.inject, dev.record
	 * timelines). Bypasses the hold queue. Client thread. Returns false for types the model does not keep.
	 */
	public boolean inject(String type, JsonObject json) {
		return apply(type, json);
	}

	/**
	 * Build an {@code agent.upsert} / {@code task.upsert} from the current agent or task with a few
	 * fields replaced (wire names, e.g. {@code {"station":"desk","state":"editing"}}) and apply it.
	 * Returns the message that was applied. Client thread.
	 */
	public JsonObject patch(String kind, String id, JsonObject set) {
		Object current = switch (kind) {
			case "agent" -> agents.get(id);
			case "task" -> tasks.get(id);
			default -> throw new IllegalArgumentException("patch kind must be agent or task");
		};
		if (current == null) {
			throw new IllegalArgumentException("no " + kind + " '" + id + "' in the Foreman state (known: "
				+ String.join(", ", kind.equals("agent") ? agents.keySet() : tasks.keySet()) + ")");
		}
		JsonObject obj = ForemanJson.GSON.toJsonTree(current).getAsJsonObject();
		for (var e : set.entrySet()) {
			obj.add(e.getKey(), e.getValue().deepCopy());
		}
		if (kind.equals("task") && !set.has("updatedAt")) {
			obj.addProperty("updatedAt", System.currentTimeMillis());
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("v", Protocol.VERSION);
		msg.addProperty("type", kind + ".upsert");
		msg.add(kind, obj);
		apply(kind + ".upsert", msg);
		return msg;
	}

	/** Apply one parsed Foreman message. Returns false for types the model does not keep (ack, diff, error...). */
	boolean apply(String type, JsonObject json) {
		lastMessageAt = System.currentTimeMillis();
		switch (type) {
			case "snapshot" -> applySnapshot(ForemanJson.read(json, Snapshot.class));
			case "agent.upsert" -> {
				Agent a = ForemanJson.read(json, AgentUpsert.class).agent();
				if (a != null && a.id() != null) {
					Agent prev = agents.put(a.id(), a);
					fire(l -> l.onAgent(prev, a));
				}
			}
			case "agent.log" -> {
				AgentLog m = ForemanJson.read(json, AgentLog.class);
				if (m.agentId() != null && !m.entries().isEmpty()) {
					appendLogs(m.agentId(), m.entries());
					fire(l -> l.onLog(m.agentId(), m.entries()));
				}
			}
			case "agent.screen" -> {
				AgentScreen sc = ForemanJson.read(json, AgentScreen.class);
				if (sc.agentId() != null && !sc.data().isEmpty()) {
					screens.put(sc.agentId(), sc);
					fire(l -> l.onScreen(sc));
				}
			}
			case "media.add" -> {
				Media m = ForemanJson.read(json, MediaAdd.class).item();
				if (m != null && m.id() != null) {
					bounded(media, m.withoutFrames(), MEDIA_TAIL);
					fire(l -> l.onMedia(m));
				}
			}
			case "doc.upsert" -> {
				Doc d = ForemanJson.read(json, DocUpsert.class).doc();
				if (d != null && d.id() != null && d.agentId() != null) {
					Doc prev = docs.remove(d.id());
					docs.put(d.id(), prev != null && prev.read() ? d.withRead(true) : d);
					while (docs.size() > DOC_MAX) {
						docs.remove(docs.keySet().iterator().next());
					}
					Doc now = docs.get(d.id());
					fire(l -> l.onDoc(prev, now));
				}
			}
			case "social.post.upsert" -> {
				SocialPost p = ForemanJson.read(json, SocialPostUpsert.class).post();
				if (p != null && p.id() != null) {
					SocialPost prev = socialPosts.put(p.id(), p);
					fire(l -> l.onSocialPost(prev, p));
				}
			}
			case "content.channel.upsert" -> {
				ContentChannel c = ForemanJson.read(json, ContentChannelUpsert.class).channel();
				if (c != null && c.id() != null) {
					ContentChannel prev = contentChannels.put(c.id(), c);
					fire(l -> l.onContentChannel(prev, c));
				}
			}
			case "content.video.upsert" -> {
				ContentVideo v = ForemanJson.read(json, ContentVideoUpsert.class).video();
				if (v != null && v.id() != null) {
					ContentVideo prev = contentVideos.put(v.id(), v);
					fire(l -> l.onContentVideo(prev, v));
				}
			}
			case "content.ref.upsert" -> {
				ContentRef r = ForemanJson.read(json, ContentRefUpsert.class).ref();
				if (r != null && r.id() != null) {
					ContentRef prev = contentRefs.put(r.id(), r);
					fire(l -> l.onContentRef(prev, r));
				}
			}
			case "content.stats" -> {
				ContentStatsMsg m = ForemanJson.read(json, ContentStatsMsg.class);
				mergeContentStats(m.stats(), m.channelStats());
				fire(ForemanListener::onContentStats);
			}
			case "agent.say" -> {
				AgentSay s = ForemanJson.read(json, AgentSay.class);
				if (s.agentId() != null) {
					lastSay.put(s.agentId(), s);
					fire(l -> l.onSay(s));
				}
			}
			case "task.upsert" -> {
				Task t = ForemanJson.read(json, TaskUpsert.class).task();
				if (t != null && t.id() != null) {
					Task prev = tasks.put(t.id(), t);
					fire(l -> l.onTask(prev, t));
				}
			}
			case "decision.upsert" -> {
				Decision d = ForemanJson.read(json, DecisionUpsert.class).decision();
				if (d != null && d.id() != null) {
					Decision prev = decisions.put(d.id(), d);
					fire(l -> l.onDecision(prev, d));
				}
			}
			case "repo.upsert" -> {
				Repo r = ForemanJson.read(json, RepoUpsert.class).repo();
				if (r != null && r.id() != null) {
					Repo prev = repos.put(r.id(), r);
					fire(l -> l.onRepo(prev, r));
				}
			}
			case "memory.upsert" -> {
				MemoryEntry e = ForemanJson.read(json, MemoryUpsert.class).entry();
				if (e != null && e.id() != null) {
					MemoryEntry prev = memory.put(e.id(), e);
					fire(l -> l.onMemory(prev, e));
				}
			}
			case "goal.upsert" -> {
				Goal g = ForemanJson.read(json, GoalUpsert.class).goal();
				if (g != null && g.id() != null) {
					Goal prev = goals.put(g.id(), g);
					// "latest goal is current": the newest by creation time
					if (goal == null || goal.id().equals(g.id()) || g.createdAt() >= goal.createdAt()) {
						goal = g;
					}
					fire(l -> l.onGoal(prev, g));
				}
			}
			case "feed.add" -> {
				FeedItem item = ForemanJson.read(json, FeedAdd.class).item();
				if (item != null) {
					bounded(feed, item, FEED_TAIL);
					fire(l -> l.onFeed(item));
				}
			}
			case "notify" -> {
				Notify n = ForemanJson.read(json, Notify.class);
				bounded(notifications, n, NOTIFY_TAIL);
				fire(l -> l.onNotify(n));
			}
			case "foreman.status" -> {
				ForemanStatus s = ForemanJson.read(json, ForemanStatusMsg.class).status();
				if (s != null) {
					status = s;
					fire(l -> l.onStatus(s));
				}
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	private void applySnapshot(Snapshot s) {
		agents.clear();
		tasks.clear();
		decisions.clear();
		repos.clear();
		memory.clear();
		goals.clear();
		logs.clear();
		screens.clear();
		media.clear();
		docs.clear();
		socialPosts.clear();
		contentChannels.clear();
		contentVideos.clear();
		contentRefs.clear();
		contentStats.clear();
		contentChannelStats.clear();
		feed.clear();
		for (Agent a : s.agents()) {
			if (a != null && a.id() != null) {
				agents.put(a.id(), a);
			}
		}
		for (Task t : s.tasks()) {
			if (t != null && t.id() != null) {
				tasks.put(t.id(), t);
			}
		}
		for (Decision d : s.decisions()) {
			if (d != null && d.id() != null) {
				decisions.put(d.id(), d);
			}
		}
		for (Repo r : s.repos()) {
			if (r != null && r.id() != null) {
				repos.put(r.id(), r);
			}
		}
		for (MemoryEntry e : s.memory()) {
			if (e != null && e.id() != null) {
				memory.put(e.id(), e);
			}
		}
		for (Goal g : s.goals()) {
			if (g != null && g.id() != null) {
				goals.put(g.id(), g);
			}
		}
		goal = s.goal();
		if (goal != null && goal.id() != null) {
			goals.putIfAbsent(goal.id(), goal);
		}
		for (FeedItem f : s.feed()) {
			if (f != null) {
				bounded(feed, f, FEED_TAIL);
			}
		}
		for (AgentLogs l : s.logs()) {
			if (l != null && l.agentId() != null) {
				appendLogs(l.agentId(), l.entries());
			}
		}
		for (AgentScreen sc : s.screens()) {
			if (sc != null && sc.agentId() != null && !sc.data().isEmpty()) {
				screens.put(sc.agentId(), sc);
			}
		}
		for (Media m : s.media()) {
			if (m != null && m.id() != null) {
				bounded(media, m.withoutFrames(), MEDIA_TAIL);
			}
		}
		for (Doc d : s.docs()) {
			if (d != null && d.id() != null && d.agentId() != null) {
				docs.put(d.id(), d);
			}
		}
		for (SocialPost p : s.socialPosts()) {
			if (p != null && p.id() != null) {
				socialPosts.put(p.id(), p);
			}
		}
		for (ContentChannel c : s.contentChannels()) {
			if (c != null && c.id() != null) {
				contentChannels.put(c.id(), c);
			}
		}
		for (ContentVideo v : s.contentVideos()) {
			if (v != null && v.id() != null) {
				contentVideos.put(v.id(), v);
			}
		}
		for (ContentRef r : s.contentRefs()) {
			if (r != null && r.id() != null) {
				contentRefs.put(r.id(), r);
			}
		}
		mergeContentStats(s.contentStats(), s.contentChannelStats());
		List<Media> mediaWithFrames = s.media();
		fire(l -> l.onMediaSnapshot(mediaWithFrames));
		// speech bubbles are transient: drop the ones of agents that no longer exist
		lastSay.keySet().retainAll(agents.keySet());
		status = s.foreman();
		snapshots++;
		snapshotAt = System.currentTimeMillis();
		fire(l -> l.onSnapshot(this));
	}

	/** Regroup stats by video id (history oldest first); videos named in {@code stats} get their history replaced, the others keep theirs. */
	private void mergeContentStats(List<ContentStat> stats, List<ContentChannelStats> channelStats) {
		Map<String, List<ContentStat>> byVideo = new HashMap<>();
		for (ContentStat st : stats) {
			if (st != null && st.videoId() != null) {
				byVideo.computeIfAbsent(st.videoId(), k -> new ArrayList<>()).add(st);
			}
		}
		for (var e : byVideo.entrySet()) {
			e.getValue().sort(Comparator.comparingLong(ContentStat::at));
			contentStats.put(e.getKey(), List.copyOf(e.getValue()));
		}
		for (ContentChannelStats cs : channelStats) {
			if (cs != null && cs.channelId() != null) {
				contentChannelStats.put(cs.channelId(), cs);
			}
		}
	}

	private void appendLogs(String agentId, List<LogEntry> entries) {
		Deque<LogEntry> d = logs.computeIfAbsent(agentId, k -> new ArrayDeque<>());
		for (LogEntry e : entries) {
			if (e != null) {
				bounded(d, e, LOG_TAIL);
			}
		}
	}

	private static <T> void bounded(Deque<T> d, T item, int max) {
		d.addLast(item);
		while (d.size() > max) {
			d.removeFirst();
		}
	}
}

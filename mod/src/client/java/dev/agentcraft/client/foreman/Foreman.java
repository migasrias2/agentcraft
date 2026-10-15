package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Diff;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Static entry point to the Foreman for every client feature.
 *
 * <pre>
 * Foreman.state().agents()                      // read the model (client thread)
 * Foreman.addListener(new ForemanListener() {...}) // change callbacks (client thread)
 * Foreman.submitGoal("Add OAuth", null).thenAccept(ack -> ...)  // intents; futures complete on the client thread
 * Foreman.requestDiff(repoId, worktree).thenAccept(diff -> ...)
 * </pre>
 *
 * Intents fail fast (exceptionally) while the link is not synced; an {@link Ack} with
 * {@code ok=false} carries the Foreman's error text.
 */
public final class Foreman {
	private static ForemanState state;
	private static ForemanLink link;

	private Foreman() {
	}

	static void install(ForemanState s, ForemanLink l) {
		state = s;
		link = l;
	}

	public static ForemanState state() {
		return state;
	}

	public static ForemanLink link() {
		return link;
	}

	public static void addListener(ForemanListener l) {
		state.addListener(l);
	}

	public static boolean connected() {
		return link != null && link.status().synced();
	}

	/** Send any client message (type + payload); see docs/protocol.md "Mod -> Foreman". */
	public static CompletableFuture<Ack> send(String type, JsonObject payload) {
		JsonObject m = payload.deepCopy();
		m.addProperty("type", type);
		return link.send(m);
	}

	/** New goal for the lead (console: plain text). {@code repoId} null = the Foreman's default repo. */
	public static CompletableFuture<Ack> submitGoal(String text, @Nullable String repoId) {
		return link.send(ForemanJson.msg("goal.submit").put("text", text).put("repoId", repoId).json());
	}

	/** Message an agent ({@code to} = agent id) or everyone ({@code "all"}; a leading "@name" routes it). */
	public static CompletableFuture<Ack> message(String to, String text) {
		return link.send(ForemanJson.msg("user.message").put("to", to).put("text", text).json());
	}

	/** Answer a decision with an option label (preferred) and/or free text. */
	public static CompletableFuture<Ack> answer(String decisionId, @Nullable String option, @Nullable String text) {
		return link.send(ForemanJson.msg("decision.answer").put("decisionId", decisionId).put("option", option).put("text", text).json());
	}

	/** {@code action}: reassign | cancel | retry | prioritize; {@code arg}: agent id / priority. */
	public static CompletableFuture<Ack> taskAction(String taskId, String action, @Nullable String arg) {
		return link.send(ForemanJson.msg("task.action").put("taskId", taskId).put("action", action).put("arg", arg).json());
	}

	/** {@code action}: pause | resume | stop | spawn; {@code arg}: spawn task id. */
	public static CompletableFuture<Ack> agentAction(String agentId, String action, @Nullable String arg) {
		return link.send(ForemanJson.msg("agent.action").put("agentId", agentId).put("action", action).put("arg", arg).json());
	}

	/** Let agents run tool calls without asking (denied things like git push stay denied). The Foreman re-broadcasts its status. */
	public static CompletableFuture<Ack> setAutoApprove(boolean on) {
		return link.send(ForemanJson.msg("settings.set").put("autoApprove", on).json());
	}

	/** Ask the Foreman to open the original file of a media item in the OS default app (videos play with sound). */
	public static CompletableFuture<Ack> openMedia(String mediaId) {
		return link.send(ForemanJson.msg("media.open").put("mediaId", mediaId).json());
	}

	/** Tell the Foreman the user opened a handed-over document (it comes back as a doc.upsert with read=true). */
	public static CompletableFuture<Ack> readDoc(String docId) {
		return link.send(ForemanJson.msg("doc.read").put("docId", docId).json());
	}

	/**
	 * Act on a Social Desk post: approve | unapprove | cancel | post_now | schedule. {@code at} = epoch ms (schedule, or approve of a
	 * post whose time you want set), {@code text} = an edited text applied before the action.
	 */
	public static CompletableFuture<Ack> socialAction(String postId, String action, @Nullable Long at, @Nullable String text) {
		var m = ForemanJson.msg("social.post.action").put("postId", postId).put("action", action);
		if (at != null) {
			m.put("at", at);
		}
		if (text != null) {
			m.put("text", text);
		}
		return link.send(m.json());
	}

	/**
	 * Act on a Content Room channel: add | update | remove | autopilot_on | autopilot_off | sync. {@code channelId} null = add.
	 * Optional fields (name, url, niche, brief, postsPerWeek) are sent when non-null.
	 */
	public static CompletableFuture<Ack> contentChannelAction(@Nullable String channelId, String action, @Nullable String name, @Nullable String url,
		@Nullable String niche, @Nullable String brief, @Nullable Integer postsPerWeek) {
		var m = ForemanJson.msg("content.channel.action").put("channelId", channelId).put("action", action).put("name", name).put("url", url)
			.put("niche", niche).put("brief", brief).put("postsPerWeek", postsPerWeek);
		return link.send(m.json());
	}

	/**
	 * Act on a Content Room video: approve | reject | comment | approve_cut | schedule | publish_now | unschedule | retry.
	 * {@code text} = comment text / reject reason, {@code at} = epoch ms (approve_cut, schedule).
	 */
	public static CompletableFuture<Ack> contentVideoAction(String videoId, String action, @Nullable String text, @Nullable Long at) {
		return link.send(ForemanJson.msg("content.video.action").put("videoId", videoId).put("action", action).put("text", text).put("at", at).json());
	}

	/** Show a studied reference's contact sheets (or one frame, 0-based) on the Screening Room screen. */
	public static CompletableFuture<Ack> contentVideoPreview(String videoId) {
		return link.send(ForemanJson.msg("content.video.preview").put("videoId", videoId).json());
	}

	public static CompletableFuture<Ack> contentRefOpen(String refId, @Nullable Integer frame) {
		return link.send(ForemanJson.msg("content.ref.open").put("refId", refId).put("frame", frame).json());
	}

	/** New git repo (README + first commit) under the Foreman's new-repo dir; optional goal and AUTO. */
	public static CompletableFuture<Ack> createRepo(String name, @Nullable String goal, boolean autoApprove) {
		var m = ForemanJson.msg("repo.create").put("name", name);
		if (goal != null && !goal.isBlank()) {
			m.put("goal", goal);
		}
		if (autoApprove) {
			m.put("autoApprove", true);
		}
		return link.send(m.json());
	}

	public static CompletableFuture<Ack> addRepo(String path) {
		return link.send(ForemanJson.msg("repo.add").put("path", path).json());
	}

	/** Structured diff of a worktree (or an agent id: its current worktree) vs its base. */
	public static CompletableFuture<Diff> requestDiff(String repoId, String worktree) {
		return link.requestDiff(repoId, worktree);
	}
}

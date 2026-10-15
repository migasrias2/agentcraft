package dev.agentcraft.client.foreman;

import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentSay;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.Doc;
import dev.agentcraft.client.foreman.Protocol.FeedItem;
import dev.agentcraft.client.foreman.Protocol.ForemanStatus;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Media;
import dev.agentcraft.client.foreman.Protocol.ContentChannel;
import dev.agentcraft.client.foreman.Protocol.ContentRef;
import dev.agentcraft.client.foreman.Protocol.ContentVideo;
import dev.agentcraft.client.foreman.Protocol.SocialPost;
import dev.agentcraft.client.foreman.Protocol.AgentScreen;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import dev.agentcraft.client.foreman.Protocol.MemoryEntry;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Task;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Change callbacks of {@link ForemanState}. Every method has a no-op default; implement what you
 * need. All callbacks run on the <b>client (render) thread</b>, after the state model was updated,
 * so reading {@link Foreman#state()} inside them sees the new state.
 *
 * <p>A {@code snapshot} (on every (re)connect) replaces the whole model and fires only
 * {@link #onSnapshot}: rebuild anything you derived from the state there. Upserts fire the
 * per-entity callbacks with the previous value ({@code null} when new).
 */
public interface ForemanListener {
	default void onConnection(LinkStatus status) {
	}

	default void onSnapshot(ForemanState state) {
	}

	default void onAgent(@Nullable Agent previous, Agent agent) {
	}

	default void onTask(@Nullable Task previous, Task task) {
	}

	default void onDecision(@Nullable Decision previous, Decision decision) {
	}

	default void onRepo(@Nullable Repo previous, Repo repo) {
	}

	default void onMemory(@Nullable MemoryEntry previous, MemoryEntry entry) {
	}

	default void onGoal(@Nullable Goal previous, Goal goal) {
	}

	default void onFeed(FeedItem item) {
	}

	default void onLog(String agentId, List<LogEntry> entries) {
	}

	/** A new live screen frame for an agent (also replayed per agent from a snapshot, via {@link #onSnapshot}). */
	default void onScreen(AgentScreen screen) {
	}

	/** An agent handed the user an image or video (frames included; the state keeps metadata only). */
	default void onMedia(Media media) {
	}

	/** The gallery of a snapshot (frames included), fired just before {@link #onSnapshot}. Empty list = no media. */
	default void onMediaSnapshot(List<Media> items) {
	}

	/** An agent handed the user a document, or it was marked read ({@code previous} null = new hand-over). */
	default void onDoc(@Nullable Doc previous, Doc doc) {
	}

	/** A Social Desk post was created or changed ({@code previous} null = new on the calendar). */
	default void onSocialPost(@Nullable SocialPost previous, SocialPost post) {
	}

	/** A Content Room channel was added or changed ({@code previous} null = new). */
	default void onContentChannel(@Nullable ContentChannel previous, ContentChannel channel) {
	}

	/** A video moved through the Content Room pipeline or was edited ({@code previous} null = new idea). */
	default void onContentVideo(@Nullable ContentVideo previous, ContentVideo video) {
	}

	/** A reference video was studied. */
	default void onContentRef(@Nullable ContentRef previous, ContentRef ref) {
	}

	/** New analytics arrived (after a sync). */
	default void onContentStats() {
	}

	default void onSay(AgentSay say) {
	}

	default void onNotify(Notify notify) {
	}

	default void onStatus(ForemanStatus status) {
	}

	/** Any change at all (after the specific callback). Cheap hook for "something to redraw". */
	default void onChange(long revision) {
	}
}

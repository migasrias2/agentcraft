package dev.agentcraft.client.content;

import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.TextUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * What the Studio's buttons do: send the {@code content.*} intents to the Foreman and show its answer. The
 * Foreman's refusals ("not in review", "no such video") come back as an ack error and show inline for a while;
 * a success shows a short confirmation. Destructive actions arm on the first click and run on the second.
 */
final class ContentActions {
	private volatile String flash = "";
	private volatile boolean flashError;
	private volatile long flashUntil;
	private @Nullable String confirm;
	private long confirmUntil;

	String flashText() {
		return System.currentTimeMillis() < flashUntil ? flash : "";
	}

	boolean flashIsError() {
		return flashError;
	}

	void flash(String text, boolean error) {
		flash = text;
		flashError = error;
		flashUntil = System.currentTimeMillis() + (error ? 9000 : 4000);
	}

	boolean armed(String key) {
		return key.equals(confirm) && System.currentTimeMillis() < confirmUntil;
	}

	/** First click arms (and says so), a second one within 4 s runs. */
	void confirmed(String key, Runnable run) {
		if (armed(key)) {
			confirm = null;
			run.run();
		} else {
			confirm = key;
			confirmUntil = System.currentTimeMillis() + 4000;
			flash("Click again to confirm", false);
		}
	}

	/** approve | reject | comment | approve_cut | schedule | publish_now | unschedule | retry. */
	void video(String videoId, String action, @Nullable String text, @Nullable Long at, String done, @Nullable Runnable onOk) {
		track(() -> Foreman.contentVideoAction(videoId, action, text, at), done, onOk);
	}

	/** add | update | remove | autopilot_on | autopilot_off | sync. */
	void channel(@Nullable String channelId, String action, @Nullable String name, @Nullable String url, @Nullable String niche, @Nullable String brief,
		@Nullable Integer postsPerWeek, String done, @Nullable Runnable onOk) {
		track(() -> Foreman.contentChannelAction(channelId, action, name, url, niche, brief, postsPerWeek), done, onOk);
	}

	/** Watch a finished cut in game: the Foreman turns it into a flipbook, then the media viewer opens on it. */
	void watch(String videoId) {
		flash("Loading the cut...", false);
		try {
			Foreman.contentVideoPreview(videoId).whenComplete((ack, err) -> Minecraft.getInstance().execute(() -> {
				if (err != null || ack == null || !ack.ok()) {
					flash(ack != null && ack.error() != null ? ack.error() : "Could not load the cut", true);
					return;
				}
				String mediaId = ack.result() != null && ack.result().has("mediaId") ? ack.result().get("mediaId").getAsString() : null;
				flash("Playing the cut " + UiBits.CHECK, false);
				Minecraft.getInstance().gui.setScreen(new dev.agentcraft.client.monitor.MediaViewScreen(mediaId));
			}));
		} catch (RuntimeException e) {
			flash("The Foreman is not connected", true);
		}
	}

	void openRef(String refId, @Nullable Integer frame, String done) {
		track(() -> Foreman.contentRefOpen(refId, frame), done, null);
	}

	private void track(java.util.function.Supplier<CompletableFuture<Ack>> send, String done, @Nullable Runnable onOk) {
		flash("Working...", false);
		try {
			send.get().whenComplete((ack, err) -> Minecraft.getInstance().execute(() -> {
				if (err != null) {
					Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
					flash(c.getMessage() != null ? TextUtil.firstLine(c.getMessage()) : "Could not reach the Foreman", true);
				} else if (ack == null || !ack.ok()) {
					flash(ack == null || ack.error() == null ? "The Foreman said no" : ack.error(), true);
				} else {
					flash(done + " " + UiBits.CHECK, false);
					if (onOk != null) {
						onOk.run();
					}
				}
			}));
		} catch (RuntimeException e) {
			flash("The Foreman is not connected", true);
		}
	}
}

package dev.agentcraft.client.console;

import dev.agentcraft.client.console.ConsoleCommands.AgentAction;
import dev.agentcraft.client.console.ConsoleCommands.Answer;
import dev.agentcraft.client.console.ConsoleCommands.ApprovePost;
import dev.agentcraft.client.console.ConsoleCommands.Auto;
import dev.agentcraft.client.console.ConsoleCommands.Calendar;
import dev.agentcraft.client.console.ConsoleCommands.Content;
import dev.agentcraft.client.console.ConsoleCommands.Clear;
import dev.agentcraft.client.console.ConsoleCommands.Command;
import dev.agentcraft.client.console.ConsoleCommands.Decide;
import dev.agentcraft.client.console.ConsoleCommands.Docs;
import dev.agentcraft.client.console.ConsoleCommands.Empty;
import dev.agentcraft.client.console.ConsoleCommands.Goal;
import dev.agentcraft.client.console.ConsoleCommands.Help;
import dev.agentcraft.client.console.ConsoleCommands.Intent;
import dev.agentcraft.client.console.ConsoleCommands.Invalid;
import dev.agentcraft.client.console.ConsoleCommands.Message;
import dev.agentcraft.client.console.ConsoleCommands.Posts;
import dev.agentcraft.client.console.ConsoleCommands.RepoAdd;
import dev.agentcraft.client.console.ConsoleCommands.RepoNew;
import dev.agentcraft.client.console.ConsoleCommands.Repos;
import dev.agentcraft.client.console.ConsoleCommands.ShowDiff;
import dev.agentcraft.client.console.ConsoleCommands.Sound;
import dev.agentcraft.client.console.ConsoleCommands.Status;
import dev.agentcraft.client.console.ConsoleCommands.TaskAction;
import dev.agentcraft.client.console.ConsoleLog.Tone;
import dev.agentcraft.client.decisions.DecisionQueue;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.decisions.DiffLink;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.client.hud.HudSounds;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.social.SocialCalendar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import dev.agentcraft.client.foreman.Protocol;
import org.jspecify.annotations.Nullable;

/**
 * Runs console intents: sends them to the Foreman, then reports the ack in the status line next to
 * the input ("sent to Juniper ✓" / the Foreman's error) and, for errors and local commands, as
 * console lines. Failed sends hand the input back so it can be fixed and re-sent.
 */
public final class ConsoleActions {
	/** The latest feedback for the status line. {@code pending}: still waiting for the ack. */
	public record Feedback(String text, Tone tone, long at, boolean pending) {
	}

	/** What the console does with its input after Enter. */
	public enum After {
		CLEAR, KEEP, CLOSE
	}

	private static volatile @Nullable Feedback feedback;
	private static int sentCount;
	private static int ackOkCount;
	private static int ackErrorCount;
	private static @Nullable String lastSent;

	private ConsoleActions() {
	}

	public static @Nullable Feedback feedback() {
		return feedback;
	}

	public static void setFeedback(String text, Tone tone, boolean pending) {
		feedback = new Feedback(text, tone, System.currentTimeMillis(), pending);
	}

	public static void clearFeedback() {
		feedback = null;
	}

	public static @Nullable String lastSent() {
		return lastSent;
	}

	public static Map<String, Object> stats() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("sent", sentCount);
		m.put("ackOk", ackOkCount);
		m.put("ackError", ackErrorCount);
		m.put("lastSent", lastSent);
		Feedback f = feedback;
		m.put("feedback", f == null ? null : f.text());
		m.put("feedbackTone", f == null ? null : f.tone().name().toLowerCase(java.util.Locale.ROOT));
		return m;
	}

	/**
	 * Run an intent. {@code raw} is the input as typed (kept in history); {@code restore} puts text
	 * back into the input when a send fails (the screen ignores it once closed).
	 */
	public static After run(Intent in, String raw, Consumer<String> restore) {
		ForemanState s = Foreman.state();
		switch (in) {
			case Empty e -> {
				return After.KEEP;
			}
			case Invalid i -> {
				setFeedback(i.error(), Tone.ERROR, false);
				return After.KEEP;
			}
			case Help h -> {
				ConsoleLog.remember(raw);
				help(h.topic());
				clearFeedback();
				return After.CLEAR;
			}
			case Status st -> {
				ConsoleLog.remember(raw);
				status(s);
				clearFeedback();
				return After.CLEAR;
			}
			case Repos r -> {
				ConsoleLog.remember(raw);
				repos(s);
				clearFeedback();
				return After.CLEAR;
			}
			case Clear c -> {
				ConsoleLog.remember(raw);
				ConsoleLog.clearLocal();
				clearFeedback();
				return After.CLEAR;
			}
			case Sound so -> {
				ConsoleLog.remember(raw);
				if (so.on() != null) {
					HudSounds.setEnabled(so.on());
				}
				String state = HudSounds.enabled() ? "on" : "off";
				String why = HudSounds.forcedMute() ? " (the game was started muted: AGENTCRAFT_MUTE=1)" : "";
				ConsoleLog.add(Tone.INFO, "Decision bell and done chime: " + state + why);
				clearFeedback();
				return After.CLEAR;
			}
			case Docs d -> {
				ConsoleLog.remember(raw);
				clearFeedback();
				return docs(s, d.ref());
			}
			case Posts po -> {
				ConsoleLog.remember(raw);
				posts(s);
				clearFeedback();
				return After.CLEAR;
			}
			case Calendar ca -> {
				ConsoleLog.remember(raw);
				clearFeedback();
				net.minecraft.client.Minecraft.getInstance().gui.setScreen(new dev.agentcraft.client.social.SocialCalendarScreen());
				return After.CLOSE;
			}
			case Content co -> {
				ConsoleLog.remember(raw);
				clearFeedback();
				dev.agentcraft.client.content.ContentFeature.open();
				return After.CLOSE;
			}
			case Decide d -> {
				ConsoleLog.remember(raw);
				clearFeedback();
				DecisionsFeature.openQueue(d.decisionId(), net.minecraft.client.Minecraft.getInstance().gui.screen());
				return After.CLOSE;
			}
			case ShowDiff d -> {
				ConsoleLog.remember(raw);
				clearFeedback();
				if (DiffLink.hasDiffScreen()) {
					DiffLink.open(d.repoId(), d.worktree(), d.decision(), null);
					return After.CLOSE;
				}
				// no diff screen in this build: print a summary into the console
				setFeedback("fetching the diff of " + d.worktree() + "\u2026", Tone.INFO, true);
				DiffLink.summary(d.repoId(), d.worktree()).thenAccept(lines -> {
					for (DiffLink.SummaryLine l : lines) {
						if (l.header() || l.error()) {
							ConsoleLog.add(l.header() ? Tone.HEADER : Tone.ERROR, l.text());
						} else if (l.additions() + l.deletions() > 0) {
							ConsoleLog.add(Tone.FILE, l.text() + "\t+" + l.additions() + "\t\u2212" + l.deletions());
						} else {
							ConsoleLog.add(Tone.INFO, l.text());
						}
					}
					setFeedback("diff of " + d.worktree() + " below", Tone.OK, false);
				});
				return After.CLEAR;
			}
			default -> {
			}
		}
		if (!Foreman.connected()) {
			setFeedback("Foreman offline: nothing was sent (it reconnects by itself)", Tone.ERROR, false);
			return After.KEEP;
		}
		ConsoleLog.remember(raw);
		lastSent = raw.strip();
		switch (in) {
			case Goal g -> track(Foreman.submitGoal(g.text(), g.repoId()), raw, restore, ack -> {
				String gid = ack.result() != null && ack.result().has("goalId") ? ack.result().get("goalId").getAsString() : null;
				return "goal " + (gid != null ? gid + " " : "") + "sent to Marlow" + (g.repoId() != null && s.repos().size() > 1 ? " \u2192 "
					+ ConsoleCommands.repoName(g.repoId(), s) : "") + " " + UiBits.CHECK;
			}, "sending the goal\u2026");
			case Message m -> track(Foreman.message(m.to(), m.text()), raw, restore,
				ack -> "sent to " + ConsoleCommands.displayName(m.to(), s) + " " + UiBits.CHECK, "sending to " + ConsoleCommands.displayName(m.to(), s) + "\u2026");
			case Answer a -> {
				String did = a.decision().id();
				DecisionsFeature.markAnswering(did);
				// a refused answer must show up again on the HUD badge and the podium at once
				track(Foreman.answer(did, a.option(), a.text()), raw, restore,
					ack -> "answered " + did + (a.option() != null ? ": " + a.option() : "") + " " + UiBits.CHECK, "answering " + did + "\u2026",
					() -> DecisionsFeature.unmarkAnswering(did));
			}
			case RepoAdd r -> track(Foreman.addRepo(r.path()), raw, restore, ack -> {
				String rid = ack.result() != null && ack.result().has("repoId") ? ack.result().get("repoId").getAsString() : null;
				return "repo " + (rid != null ? rid + " " : "") + "added " + UiBits.CHECK;
			}, "adding the repo\u2026");
			case RepoNew r -> track(Foreman.createRepo(r.name(), r.goal(), r.auto()), raw, restore, ack -> {
				String rid = ack.result() != null && ack.result().has("repoId") ? ack.result().get("repoId").getAsString() : r.name();
				return "repo " + rid + " created" + (r.goal() != null ? ", goal started" : "") + (r.auto() ? " · AUTO on" : "") + " " + UiBits.CHECK;
			}, "creating the repo…");
			case ApprovePost ap -> {
				Protocol.SocialPost post = findPost(s, ap.ref());
				if (post == null) {
					setFeedback("no post '" + ap.ref() + "' (see /posts)", Tone.ERROR, false);
					return After.KEEP;
				}
				track(Foreman.socialAction(post.id(), "approve", null, null), raw, restore, ack -> post.id() + " approved"
					+ (post.scheduledAt() != null ? ": goes out " + SocialCalendar.dayTime(post.scheduledAt()) : "") + " " + UiBits.CHECK,
					"approving " + post.id() + "\u2026");
			}
			case AgentAction a -> {
				List<CompletableFuture<Ack>> all = new ArrayList<>();
				for (String id : a.agentIds()) {
					all.add(Foreman.agentAction(id, a.action(), a.arg()));
				}
				CompletableFuture<Ack> combined = CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(v -> {
					for (CompletableFuture<Ack> f : all) {
						Ack ack = f.join();
						if (!ack.ok()) {
							return ack;
						}
					}
					return all.get(0).join();
				});
				String who = a.agentIds().size() == 1 ? ConsoleCommands.displayName(a.agentIds().get(0), s) : a.agentIds().size() + " agents";
				track(combined, raw, restore, ack -> pastTense(a.action()) + " " + who + (a.arg() != null ? " on " + a.arg() : "") + " " + UiBits.CHECK,
					a.action() + " " + who + "\u2026");
			}
			case Auto au -> {
				boolean target = au.on() != null ? au.on() : !s.autoApprove();
				track(Foreman.setAutoApprove(target), raw, restore, ack -> target
					? "AUTO on: agents run tool calls without asking (denied things like git push stay denied) " + UiBits.CHECK
					: "AUTO off: agents ask before risky tool calls " + UiBits.CHECK, (target ? "turning AUTO on" : "turning AUTO off") + "\u2026");
			}
			case TaskAction t -> track(Foreman.taskAction(t.taskId(), t.action(), t.arg()), raw, restore,
				ack -> t.taskId() + " " + pastTense(t.action()) + (t.arg() != null ? " \u2192 " + t.arg() : "") + " " + UiBits.CHECK, t.action() + " "
					+ t.taskId() + "\u2026");
			default -> {
				return After.KEEP;
			}
		}
		return After.CLEAR;
	}

	private static String pastTense(String verb) {
		return switch (verb) {
			case "pause" -> "paused";
			case "resume" -> "resumed";
			case "stop" -> "stopped";
			case "spawn" -> "spawned";
			case "cancel" -> "cancelled";
			case "retry" -> "retried";
			case "prioritize" -> "prioritized";
			case "reassign" -> "reassigned";
			default -> verb + "ed";
		};
	}

	private interface OkText {
		String text(Ack ack);
	}

	private static void track(CompletableFuture<Ack> f, String raw, Consumer<String> restore, OkText ok, String pending) {
		track(f, raw, restore, ok, pending, null);
	}

	/** {@code onError} runs (client thread) when the send fails or the Foreman refuses it. */
	private static void track(CompletableFuture<Ack> f, String raw, Consumer<String> restore, OkText ok, String pending, @Nullable Runnable onError) {
		sentCount++;
		setFeedback(pending, Tone.INFO, true);
		f.whenComplete((ack, err) -> {
			if (err == null && ack != null && ack.ok()) {
				ackOkCount++;
				setFeedback(ok.text(ack), Tone.OK, false);
				return;
			}
			ackErrorCount++;
			String msg;
			if (err != null) {
				Throwable c = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
				msg = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
			} else {
				msg = ack == null ? "no answer from the Foreman" : ack.error() != null ? ack.error() : "the Foreman refused it";
			}
			setFeedback(msg, Tone.ERROR, false);
			ConsoleLog.add(Tone.ERROR, UiBits.CROSS + " " + ConsoleCommands.oneLine(raw, 60) + ": " + msg);
			if (onError != null) {
				onError.run();
			}
			restore.accept(raw);
		});
	}

	// ------------------------------------------------------------------ local commands

	private static void help(@Nullable String topic) {
		ConsoleLog.add(Tone.HEADER, "Console");
		ConsoleLog.add(Tone.HELP, "plain text\ta new goal for Marlow (several repos: name one in the text, or pick from the list)");
		ConsoleLog.add(Tone.HELP, "repo: goal\tsend straight to that repo (name or path, e.g. moonbase: or Desktop/side-project:)");
		ConsoleLog.add(Tone.HELP, "@juniper text\tmessage an agent (Tab completes, @all = everyone)");
		for (Command c : ConsoleCommands.COMMANDS) {
			if (topic == null || c.name().startsWith(topic)) {
				ConsoleLog.add(Tone.HELP, c.usage() + "\t" + c.help());
			}
		}
		ConsoleLog.add(Tone.INFO, "Enter send \u00b7 Shift+Enter new line \u00b7 \u2191\u2193 history \u00b7 Tab complete \u00b7 "
			+ dev.agentcraft.client.hud.Keys.label(dev.agentcraft.client.hud.Keys.decisions) + " decisions \u00b7 Esc close (your draft is kept)");
	}

	/** {@code /docs}: list handed-over documents (newest first, unread marked); {@code /docs n|id}: open one. */
	private static After docs(@Nullable ForemanState s, @Nullable String ref) {
		java.util.List<Protocol.Doc> all = new java.util.ArrayList<>(s == null ? java.util.List.of() : s.docs());
		java.util.Collections.reverse(all);
		if (ref == null) {
			if (all.isEmpty()) {
				ConsoleLog.add(Tone.INFO, "No documents handed over yet.");
				return After.CLEAR;
			}
			ConsoleLog.add(Tone.HEADER, "Documents handed to you");
			for (int i = 0; i < all.size(); i++) {
				Protocol.Doc d = all.get(i);
				String who = s.agent(d.agentId()) == null ? d.agentId() : s.agent(d.agentId()).name();
				ConsoleLog.add(Tone.INFO, (i + 1) + ". " + (d.read() ? "  " : "* ") + d.title() + " (" + who + (d.name().isEmpty() ? "" : ", " + d.name())
					+ ")  " + d.id());
			}
			ConsoleLog.add(Tone.INFO, "* = unread. Open one with /docs <number>.");
			return After.CLEAR;
		}
		Protocol.Doc pick = null;
		if (ref.matches("\\d{1,3}")) {
			int n = Integer.parseInt(ref);
			pick = n >= 1 && n <= all.size() ? all.get(n - 1) : null;
		} else {
			for (Protocol.Doc d : all) {
				if (d.id().equalsIgnoreCase(ref)) {
					pick = d;
				}
			}
		}
		if (pick == null || !dev.agentcraft.client.library.DocScreen.open(pick.id())) {
			setFeedback("no document '" + ref + "' (see /docs)", Tone.ERROR, false);
			return After.KEEP;
		}
		return After.CLOSE;
	}

	/** A Social Desk post by id ("p3") or by its number in {@code /posts}; null when unknown. */
	private static Protocol.@Nullable SocialPost findPost(@Nullable ForemanState s, String ref) {
		if (s == null) {
			return null;
		}
		List<Protocol.SocialPost> open = SocialCalendar.upcoming(s.socialPosts());
		if (ref.matches("\\d{1,3}")) {
			int n = Integer.parseInt(ref);
			if (n >= 1 && n <= open.size() && s.socialPost(ref) == null) {
				return open.get(n - 1);
			}
		}
		return s.socialPost(ref) != null ? s.socialPost(ref) : s.socialPost(ref.toLowerCase(java.util.Locale.ROOT));
	}

	/** {@code /posts}: open posts, soonest first: number, id, day and time, platform, status, first words. */
	private static void posts(@Nullable ForemanState s) {
		List<Protocol.SocialPost> open = s == null ? List.of() : SocialCalendar.upcoming(s.socialPosts());
		if (open.isEmpty()) {
			ConsoleLog.add(Tone.INFO, "No posts on the calendar. Ask Nova: @nova plan my week.");
			return;
		}
		ConsoleLog.add(Tone.HEADER, "Social Desk \u00b7 " + SocialCalendar.countsLine(s.socialPosts(), null));
		for (int i = 0; i < open.size(); i++) {
			Protocol.SocialPost p = open.get(i);
			String when = p.scheduledAt() == null ? "no time yet" : SocialCalendar.dayTime(p.scheduledAt());
			Tone tone = p.status().equals("failed") ? Tone.ERROR : p.status().equals("approved") ? Tone.OK : Tone.INFO;
			ConsoleLog.add(tone, (i + 1) + ". " + p.id() + "  " + when + "  " + SocialCalendar.platformName(p.platform()) + "  "
				+ SocialCalendar.statusLabel(p.status()) + "  " + SocialCalendar.firstWords(p.text(), 60));
		}
		ConsoleLog.add(Tone.INFO, "/approve <id|number> approves one (it goes out at its time) \u00b7 /calendar opens the calendar.");
	}

	private static void status(ForemanState s) {
		ConsoleLog.add(Tone.HEADER, "Status");
		if (!s.hasData()) {
			ConsoleLog.add(Tone.ERROR, "no data from the Foreman yet");
			return;
		}
		if (s.isStale()) {
			ConsoleLog.add(Tone.ERROR, "Foreman offline: this is the last known state");
		}
		ConsoleLog.add(Tone.INFO, s.autoApprove() ? "AUTO is on: agents run tool calls without asking (/auto off to stop)"
			: "AUTO is off: agents ask before risky tool calls (/auto to skip the questions)");
		var g = s.goal();
		if (g != null) {
			ConsoleLog.add(Tone.INFO, "goal " + g.id() + " (" + g.status().wire() + ", " + Math.round(g.progress() * 100) + "%): "
				+ ConsoleCommands.oneLine(g.text(), 90));
		} else {
			ConsoleLog.add(Tone.INFO, "no goal yet: type one and press Enter");
		}
		Map<TaskStatus, Integer> counts = new LinkedHashMap<>();
		for (TaskStatus ts : List.of(TaskStatus.DOING, TaskStatus.REVIEW, TaskStatus.TODO, TaskStatus.BLOCKED, TaskStatus.DONE)) {
			counts.put(ts, 0);
		}
		for (Task t : s.tasks().values()) {
			counts.computeIfPresent(t.status(), (k, v) -> v + 1);
		}
		StringBuilder tb = new StringBuilder("tasks: ");
		counts.forEach((k, v) -> tb.append(v).append(' ').append(k.wire()).append("  "));
		ConsoleLog.add(Tone.INFO, tb.toString().strip());
		String spend = spendLabel(s);
		if (spend != null) {
			ConsoleLog.add(Tone.INFO, "Claude API spend so far: " + spend + " (estimated, this profile)");
		}
		for (Agent a : s.agents().values()) {
			String st = !a.isActive() ? "off shift" : a.isPaused() ? "paused" : a.state().wire().replace('_', ' ');
			ConsoleLog.add(Tone.INFO, a.name() + " \u00b7 " + st + (a.activity().isEmpty() ? "" : " \u00b7 " + a.activity()) + (a.taskId() != null ? " ("
				+ a.taskId() + ")" : ""), a.id());
		}
		List<Decision> open = DecisionQueue.open();
		if (open.isEmpty()) {
			ConsoleLog.add(Tone.OK, "no decisions waiting");
		} else {
			for (Decision d : open) {
				ConsoleLog.add(Tone.INFO, d.id() + " " + DecisionQueue.kindLabel(d.kind()) + ": " + ConsoleCommands.oneLine(d.question(), 80), d.agentId());
			}
		}
	}

	/** "$2.46" for the claude backend once something was spent, else null. */
	static @Nullable String spendLabel(ForemanState s) {
		var st = s.status();
		if (st == null || st.costUsd() == null || st.costUsd() <= 0) {
			return null;
		}
		return String.format(java.util.Locale.ROOT, "$%.2f", st.costUsd());
	}

	private static void repos(ForemanState s) {
		ConsoleLog.add(Tone.HEADER, "Repos");
		if (s.repos().isEmpty()) {
			String example = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
				? "C:\\path\\to\\repo" : "/path/to/repo";
			ConsoleLog.add(Tone.INFO, "none yet: /repo add " + example);
			return;
		}
		for (Repo r : s.repos().values()) {
			ConsoleLog.add(Tone.INFO, r.name() + " \u00b7 " + r.branch() + (r.head() != null ? " @ " + r.head() : "") + (r.dirty() ? " \u00b7 uncommitted changes"
				: "") + " \u00b7 " + r.path());
		}
	}
}

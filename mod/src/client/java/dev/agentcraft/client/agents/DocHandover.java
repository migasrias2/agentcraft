package dev.agentcraft.client.agents;

import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentSay;
import dev.agentcraft.client.foreman.Protocol.Doc;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Hand-over documents in the world: an agent with an unread {@link Doc} walks to you and holds a
 * book out for {@value #WINDOW_MS} ms after the hand-over ({@link #offering}), saying its note in a
 * speech bubble; after that it goes back to its station but keeps "Right-click to read" on its
 * plate until you read it ({@link AgentView#docId}). Pure derivation from the Foreman state, so a
 * reconnect or restart needs no bookkeeping beyond the bubble timer.
 */
final class DocHandover {
	/** How long after the hand-over the agent comes to you. */
	static final long WINDOW_MS = 60_000;
	/** Ticks between repeats of the note bubble while the agent waits for you. */
	private static final int REPEAT_TICKS = 260;

	private static final Map<String, Integer> NEXT_BUBBLE = new HashMap<>();

	private DocHandover() {
	}

	/** The agent's newest unread document, or null. */
	static @Nullable Doc unread(ForemanState st, String agentId) {
		return st.unreadDoc(agentId);
	}

	/** True while this agent should come to the player with a fresh hand-over. */
	static boolean offering(ForemanState st, Agent a) {
		if (!a.isActive()) {
			return false;
		}
		Doc d = st.unreadDoc(a.id());
		return d != null && System.currentTimeMillis() - d.at() < WINDOW_MS;
	}

	/** Fill the view's doc fields and (re)speak the note while offering. Client thread, once per tick per agent. */
	static void apply(ForemanState st, ClientAgentEntity e, AgentView v, boolean offering) {
		Doc d = st.unreadDoc(e.agentId());
		v.docId = d == null ? null : d.id();
		v.docTitle = d == null ? null : d.title();
		v.docOffering = d != null && offering;
		String id = e.agentId();
		if (!v.docOffering) {
			NEXT_BUBBLE.remove(id);
			return;
		}
		AgentLife life = e.life();
		int age = life.age();
		Integer next = NEXT_BUBBLE.get(id);
		if ((next == null || age >= next) && !life.bubble.visible()) {
			String text = d.note() != null && !d.note().isBlank() ? d.note() : "I have something for you: " + d.title();
			life.bubble.show(new AgentSay(id, text, null, System.currentTimeMillis()), age);
			NEXT_BUBBLE.put(id, age + REPEAT_TICKS);
		}
	}

	static void clear() {
		NEXT_BUBBLE.clear();
	}
}

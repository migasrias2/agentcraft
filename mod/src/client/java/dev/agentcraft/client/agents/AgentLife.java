package dev.agentcraft.client.agents;

import dev.agentcraft.client.foreman.Protocol.AgentSay;
import dev.agentcraft.client.foreman.Protocol.AgentState;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What makes one agent look alive: its posture (seated typing, reading a book, leaning on the test
 * bench, hand at the chin while thinking, scratching its head on an error, ...), a head that looks
 * at what matters (the monitor, the book, whoever it talks to, you when you come close or when it
 * needs you), idle micro-animations (glances at neighbours, an occasional stretch), state particles
 * ({@link AgentParticles}) and its speech bubble ({@link SpeechBubble}).
 *
 * <p>Simulated per client tick (20 Hz, deterministic per agent: a seeded generator, no wall clock)
 * and interpolated per frame in {@link #extract}. Nothing here allocates per frame. The pose is a
 * small vector of channels ({@code P_*}): arm and leg rotations of the vanilla player model
 * (radians, model space: -x is the agent's right, -z forward), a forward lean, the weight of the
 * custom arm pose against the vanilla walk swing, and the held book. {@link AgentModel} applies it.
 */
public final class AgentLife {
	public static final int P_RAX = 0, P_RAY = 1, P_RAZ = 2, P_LAX = 3, P_LAY = 4, P_LAZ = 5;
	public static final int P_RLX = 6, P_RLY = 7, P_RLZ = 8, P_LLX = 9, P_LLY = 10, P_LLZ = 11;
	public static final int P_LEAN = 12, P_ARMW = 13, P_LEGW = 14, P_BOOK = 15;
	public static final int N = 16;

	/** What the body is doing (picked every tick). */
	public enum Posture {
		WALK, IDLE, SIT_IDLE, SIT_TYPE, SIT_THINK, SIT_SCRATCH, READ, TYPE_STAND, LEAN_BENCH, REVIEW, WAIT, THINK, SCRATCH, RELAX, STRETCH, TALK, OFFER
	}

	/** Eye height of the agent model (player eye 1.62 x model scale 0.9375). */
	static final double EYE = 1.52;
	/** Ear height (blocks above the feet): where the error "steam" puffs out. */
	static final double EAR = 1.47;
	/** Ticks to sit down / stand up. */
	private static final float SIT_STEP = 1f / 8f;
	/** Pose smoothing per tick (fraction of the remaining distance). */
	private static final float POSE_RATE = 0.28f;
	/** The player counts as "near" (agents glance at you) within this distance. */
	private static final double NEAR = 4.2;

	private final ClientAgentEntity e;
	private long rng;
	final float[] cur = new float[N];
	final float[] prev = new float[N];
	final float[] tgt = new float[N];
	/** Interpolated pose for the current frame (written by {@link #extract}). */
	final float[] frame = new float[N];
	private float sit;
	private float sitPrev;
	private float typeW;
	private float typeWPrev;
	private float talkW;
	private float talkWPrev;
	private float exclaim;
	private float exclaimPrev;
	private Posture posture = Posture.IDLE;
	private Seats.@Nullable Seat seat;
	/** Vertical model offset of the seat the agent sits on / last sat on (blocks). */
	private double drop;
	private float headYaw;
	private float headPitch;
	private long lastTeleports = -1;
	private int age;
	// idle behaviour
	private int nextGlance;
	private int glanceUntil;
	private float glanceYaw;
	private float glancePitch;
	private int glanceAt = Integer.MIN_VALUE;
	private int nextStretch;
	private int nextMood;
	private boolean relaxed;
	private int stretchUntil;
	private int typingUntil;
	private int typingPauseUntil;
	private int nextPageFlip;
	private int pageFlipStart = -100;
	/** "Oh, hi": a busy agent looks up at you for a moment when you come near, then back to work. */
	private int greetUntil;
	private int nextGreet;
	private boolean playerWasNear;
	// conversation
	final SpeechBubble bubble = new SpeechBubble();
	private @Nullable String listenTo;
	private int listenUntil;
	// state reactions
	final AgentParticles particles = new AgentParticles();
	private String lastFamily = "";
	/** Ticks the agent has been live (on shift, Foreman online) without a break. */
	private int liveFor;
	private int lastConfetti = -1000;
	private int lastPuff = -1000;
	private int nextAmbientPuff;
	private int nextSpark;
	private int nextMote;
	private boolean confettiPending;

	AgentLife(ClientAgentEntity e) {
		this.e = e;
		long h = 0x9E3779B97F4A7C15L;
		for (char c : e.agentId().toCharArray()) {
			h = (h ^ c) * 0x100000001B3L;
		}
		rng = h == 0 ? 1 : h;
		nextGlance = 40 + rand(80);
		nextStretch = 200 + rand(600);
		nextPageFlip = 60 + rand(120);
		nextAmbientPuff = 60;
		e.motion().setPace(0.92 + rand01() * 0.14);
	}

	// ---------------------------------------------------------------- deterministic randomness

	private long next() {
		rng ^= rng << 13;
		rng ^= rng >>> 7;
		rng ^= rng << 17;
		return rng;
	}

	/** 0..bound-1 */
	int rand(int bound) {
		return (int) Math.floorMod(next(), (long) Math.max(1, bound));
	}

	float rand01() {
		return (next() >>> 40) / (float) (1L << 24);
	}

	float randSigned() {
		return rand01() * 2f - 1f;
	}

	// ---------------------------------------------------------------- accessors

	public Posture posture() {
		return posture;
	}

	public Seats.@Nullable Seat seat() {
		return seat;
	}

	/** Seated amount 0..1 (this tick). */
	public float sitAmount() {
		return sit;
	}

	public boolean seated() {
		return seat != null && sit > 0.5f;
	}

	/** Set by {@link AgentManager} with the seat at the agent's current target (null = stand there). */
	void setSeat(Seats.@Nullable Seat s) {
		seat = s;
	}

	/** Is the agent at (or settling into) its seat right now? */
	private boolean atSeat() {
		Seats.Seat s = seat;
		if (s == null || e.motion().walking()) {
			return false;
		}
		double dx = e.getX() - s.sit().x;
		double dz = e.getZ() - s.sit().z;
		return dx * dx + dz * dz < 0.02;
	}

	// ---------------------------------------------------------------- events (client thread)

	void onSay(AgentSay say, int tick) {
		bubble.show(say, age);
	}

	/** Another agent talks to this one: look at them while they talk. */
	void listen(String speakerId, int ticks) {
		listenTo = speakerId;
		listenUntil = age + ticks;
	}

	void onFamily(String from, String to) {
		if (to.equals("error") && !from.equals("error")) {
			if (age - lastPuff > 20) {
				particles.puff(e, EAR + sitOffset(), 10, this);
				lastPuff = age;
			}
		}
		if (to.equals("done") && !from.equals("done")) {
			confettiPending = true;
		}
	}

	/** QA preview of an effect (dev.agents.fx). */
	boolean preview(String fx, String text, @Nullable String to) {
		switch (fx) {
			case "confetti" -> particles.confetti(e, EYE - 0.15 + sitOffset(), this);
			case "puff" -> particles.puff(e, EAR + sitOffset(), 10, this);
			case "sparkle" -> {
				for (int i = 0; i < 4; i++) {
					particles.sparkle(e, EYE + 0.15 + sitOffset(), this);
				}
			}
			case "say" -> bubble.show(new dev.agentcraft.client.foreman.Protocol.AgentSay(e.agentId(), text, to, System.currentTimeMillis()), age);
			default -> {
				return false;
			}
		}
		return true;
	}

	void onTaskDone() {
		confettiPending = true;
	}

	private double sitOffset() {
		return drop * sit;
	}

	// ---------------------------------------------------------------- tick

	void tick(Minecraft mc) {
		age++;
		AgentView v = e.view();
		AgentMotion m = e.motion();
		boolean snap = m.teleports() != lastTeleports;
		lastTeleports = m.teleports();
		System.arraycopy(cur, 0, prev, 0, N);
		sitPrev = sit;
		typeWPrev = typeW;
		talkWPrev = talkW;
		exclaimPrev = exclaim;

		boolean walking = m.walking() && !m.leaving();
		boolean at = atSeat();
		if (at) {
			drop = seat.drop();
		}
		boolean seatedTarget = at && Math.abs(Mth.wrapDegrees(e.yBodyRot - seat.anchor().yaw())) < 30;
		float sitTarget = seatedTarget && !m.leaving() ? 1f : 0f;
		sit = snap ? sitTarget : approach(sit, sitTarget, SIT_STEP);
		boolean alive = !v.stale;
		String fam = v.family;
		if (alive && v.active) {
			// state reactions only for transitions seen live: coming back from "Foreman offline" or
			// off shift must not replay a puff or a confetti burst for a state that is old news
			if (!v.liveFamily.equals(lastFamily)) {
				if (!lastFamily.isEmpty() && liveFor > 0) {
					onFamily(lastFamily, v.liveFamily);
				}
				lastFamily = v.liveFamily;
			}
			liveFor++;
		} else {
			liveFor = 0;
			lastFamily = "";
		}

		posture = choose(v, walking, seatedTarget || sit > 0.5f);
		pose(posture, tgt);
		if (sit > 0f && !posture.name().startsWith("SIT")) {
			// standing up / sitting down: legs follow the seat amount
			sitLegs(tgt, sit);
		}
		float rate = snap ? 1f : POSE_RATE;
		for (int i = 0; i < N; i++) {
			cur[i] += (tgt[i] - cur[i]) * rate;
		}
		boolean typing = posture == Posture.SIT_TYPE || posture == Posture.TYPE_STAND;
		if (typing && age >= typingPauseUntil && age >= typingUntil) {
			typingUntil = age + 30 + rand(70);
			typingPauseUntil = typingUntil + 10 + rand(30);
		}
		float typeTarget = typing && age < typingUntil ? 1f : 0f;
		typeW = snap ? typeTarget : approach(typeW, typeTarget, 0.2f);
		float talkTarget = posture == Posture.TALK ? 1f : 0f;
		talkW = snap ? talkTarget : approach(talkW, talkTarget, 0.12f);
		float exTarget = v.needsYou() ? 1f : 0f;
		exclaim = snap ? exTarget : approach(exclaim, exTarget, 0.1f);
		if (posture == Posture.READ && age >= nextPageFlip) {
			pageFlipStart = age;
			nextPageFlip = age + 90 + rand(140);
		}

		v.pose = walking ? AgentPose.WALK : sit > 0.5f ? AgentPose.SIT : cur[P_LEAN] > 0.12f ? AgentPose.LEAN : AgentPose.STAND;
		look(mc, v, walking, snap);
		bubble.tick(age);
		particlesTick(v, fam, alive, walking);
		particles.tick();
		if (snap) {
			System.arraycopy(cur, 0, prev, 0, N);
			sitPrev = sit;
			typeWPrev = typeW;
			talkWPrev = talkW;
			exclaimPrev = exclaim;
		}
	}

	private Posture choose(AgentView v, boolean walking, boolean seated) {
		if (walking) {
			return Posture.WALK;
		}
		if (v.stale) {
			return seated ? Posture.SIT_IDLE : Posture.IDLE;
		}
		String fam = v.dotFamily();
		boolean paused = v.paused || !v.active;
		boolean talking = bubble.visible() && !seated;
		if (seated) {
			if (paused) {
				return Posture.SIT_IDLE;
			}
			return switch (fam) {
				case "thinking" -> Posture.SIT_THINK;
				case "working" -> seat != null && !Double.isNaN(seat.deskTop()) ? Posture.SIT_TYPE : Posture.SIT_IDLE;
				case "error" -> Posture.SIT_SCRATCH;
				default -> Posture.SIT_IDLE;
			};
		}
		if (age < stretchUntil) {
			return Posture.STRETCH;
		}
		if (paused) {
			return Posture.IDLE;
		}
		if (v.docOffering) {
			return Posture.OFFER;
		}
		boolean waitingUser = v.state == AgentState.WAITING_USER;
		if (talking && (fam.equals("idle") || fam.equals("done") || waitingUser || v.station.equals(AnchorNames.MEETING))) {
			return Posture.TALK;
		}
		switch (fam) {
			case "waiting":
				return waitingUser || v.station.equals(AnchorNames.USER) ? Posture.WAIT : Posture.IDLE;
			case "error":
				return Posture.SCRATCH;
			case "thinking":
				return Posture.THINK;
			case "working":
				return switch (v.station) {
					case AnchorNames.LIBRARY -> Posture.READ;
					case AnchorNames.TERMINAL, "desk" -> Posture.TYPE_STAND;
					case AnchorNames.TESTBENCH -> Posture.LEAN_BENCH;
					case AnchorNames.MERGESTATION -> Posture.REVIEW;
					default -> Posture.READ;
				};
			case "done":
				// finished agents unwind, each in its own rhythm (not the whole team in one pose)
				if (age >= nextMood) {
					relaxed = rand01() < 0.5f;
					nextMood = age + 300 + rand(600);
				}
				return relaxed && v.station.equals(AnchorNames.LOUNGE) ? Posture.RELAX : Posture.IDLE;
			default:
				// the stretch clock only runs while the agent idles in the lounge, so agents that
				// arrive together don't all stretch at once
				if (v.station.equals(AnchorNames.LOUNGE) && v.active && --nextStretch <= 0) {
					stretchUntil = age + 34;
					nextStretch = 500 + rand(700);
					return Posture.STRETCH;
				}
				return Posture.IDLE;
		}
	}

	/** Target pose channels for a posture (arm/leg radians, model space). */
	private void pose(Posture p, float[] t) {
		java.util.Arrays.fill(t, 0f);
		t[P_ARMW] = 1f;
		t[P_RAZ] = 0.05f;
		t[P_LAZ] = -0.05f;
		switch (p) {
			case WALK -> {
				t[P_ARMW] = 0f;
				t[P_LEAN] = 0.03f;
				if (e.view().docOffering) {
					t[P_BOOK] = 1f; // carries the book to you
				}
			}
			case OFFER -> {
				arms(t, -1.08f, -0.32f, 0f, -1.08f, 0.32f, 0f);
				t[P_BOOK] = 1f;
				t[P_LEAN] = 0.03f;
			}
			case IDLE -> {
			}
			case SIT_IDLE -> {
				arms(t, -0.55f, -0.16f, 0.05f, -0.55f, 0.16f, -0.05f);
				sitLegs(t, 1f);
			}
			case SIT_TYPE -> {
				float reach = typingReach();
				arms(t, -reach, -0.24f, 0f, -reach, 0.24f, 0f);
				sitLegs(t, 1f);
				t[P_RLX] = t[P_LLX] = -1.22f;
				t[P_LEAN] = 0.06f;
			}
			case SIT_THINK -> {
				arms(t, -2.1f, -0.6f, 0f, -0.5f, 0.62f, 0f);
				sitLegs(t, 1f);
			}
			case SIT_SCRATCH -> {
				arms(t, -2.85f, -0.3f, 0f, -0.55f, 0.16f, -0.05f);
				sitLegs(t, 1f);
			}
			case READ -> {
				arms(t, -0.98f, -0.4f, 0f, -0.98f, 0.4f, 0f);
				t[P_BOOK] = 1f;
				t[P_LEAN] = 0.05f;
			}
			case TYPE_STAND -> {
				arms(t, -0.86f, -0.24f, 0f, -0.86f, 0.24f, 0f);
				t[P_LEAN] = 0.16f;
			}
			case LEAN_BENCH -> {
				arms(t, -0.62f, -0.06f, 0.16f, -0.62f, 0.06f, -0.16f);
				t[P_LEAN] = 0.24f;
			}
			case REVIEW -> {
				arms(t, -0.74f, -0.32f, 0f, -0.5f, 0.3f, 0f);
				t[P_LEAN] = 0.1f;
			}
			case WAIT -> arms(t, -0.42f, -0.46f, 0f, -0.42f, 0.46f, 0f);
			case THINK -> arms(t, -2.1f, -0.6f, 0f, -0.5f, 0.62f, 0f);
			case SCRATCH -> arms(t, -2.85f, -0.3f, 0f, 0.04f, 0f, -0.06f);
			case RELAX -> arms(t, -3.7f, 0f, 0.62f, -3.7f, 0f, -0.62f);
			case STRETCH -> arms(t, -3.05f, 0f, -0.22f, -3.05f, 0f, 0.22f);
			case TALK -> arms(t, -0.7f, -0.22f, 0.08f, 0.02f, 0f, -0.05f);
		}
	}

	private static void arms(float[] t, float rx, float ry, float rz, float lx, float ly, float lz) {
		t[P_RAX] = rx;
		t[P_RAY] = ry;
		t[P_RAZ] = rz;
		t[P_LAX] = lx;
		t[P_LAY] = ly;
		t[P_LAZ] = lz;
	}

	private static void sitLegs(float[] t, float w) {
		t[P_RLX] = -1.34f;
		t[P_RLY] = 0.14f;
		t[P_RLZ] = 0.05f;
		t[P_LLX] = -1.34f;
		t[P_LLY] = -0.14f;
		t[P_LLZ] = -0.05f;
		t[P_LEGW] = w;
	}

	/** Arm angle (rad from hanging) that puts the hands just above the desk in front of the seat. */
	private float typingReach() {
		Seats.Seat s = seat;
		if (s == null || Double.isNaN(s.deskTop())) {
			return 1.27f;
		}
		// shoulder pivot: 22 px above the feet in model px (scale 0.9375) when standing, plus the seat drop
		double shoulder = s.anchor().y() + s.drop() + (24 - 2) / 16.0 * 0.9375;
		double hand = 10 / 16.0 * 0.9375;
		double dy = shoulder - (s.deskTop() + 0.04);
		double c = Mth.clamp(dy / hand, -0.2, 0.8);
		return (float) Math.acos(c);
	}

	// ---------------------------------------------------------------- head

	private void look(Minecraft mc, AgentView v, boolean walking, boolean snap) {
		float body = e.yBodyRot;
		Vec3 eye = new Vec3(e.getX(), e.getY() + EYE + sitOffset(), e.getZ());
		float wantYaw = body;
		float wantPitch = 0f;
		boolean tracking = false;
		LocalPlayer player = mc.player;
		Vec3 playerEye = player == null ? null : player.getEyePosition();
		double playerDist = playerEye == null ? Double.MAX_VALUE : playerEye.distanceTo(eye);
		Vec3 point = null;
		boolean faceBody = false;

		if (walking) {
			// look where you go: the next corner, a little ahead of the body turn
			Vec3 n = e.motion().nextPoint();
			if (n != null) {
				point = new Vec3(n.x, eye.y - 0.25, n.z);
			}
		} else if (!v.stale) {
			SpeechBubble.Line say = bubble.visible() ? bubble.current() : null;
			if (say != null && say.to() != null) {
				point = lookPointFor(say.to(), playerEye, playerDist);
			}
			if (point == null && listenTo != null && age < listenUntil) {
				point = lookPointFor(listenTo, playerEye, playerDist);
			}
			if (point == null && v.state == AgentState.WAITING_USER && playerEye != null && playerDist < 24 && v.active) {
				point = playerEye;
				faceBody = true;
			}
			boolean near = playerEye != null && playerDist < NEAR && inFront(eye, playerEye, body, 105);
			boolean busy = isBusy(v);
			if (near && !playerWasNear && busy && age >= nextGreet && player != null && !player.isSpectator()) {
				greetUntil = age + 40 + rand(20);
				nextGreet = age + 300 + rand(200);
			}
			playerWasNear = near;
			// idle agents keep an eye on you while you are close; busy ones look up only briefly
			// (and never at a spectating camera, so QA shots show them at work)
			if (point == null && near && (!busy || age < greetUntil)) {
				point = playerEye;
			}
			if (point == null) {
				point = stationFocus(v);
			}
			if (point == null && age >= glanceUntil && age >= nextGlance) {
				startGlance(v);
			}
			if (point == null && age < glanceUntil) {
				ClientAgentEntity other = glanceAt == Integer.MIN_VALUE ? null : AgentManager.get().byEntityId(glanceAt);
				if (other != null) {
					point = new Vec3(other.getX(), other.getY() + EYE + other.life().sitOffset(), other.getZ());
				} else {
					wantYaw = body + glanceYaw;
					wantPitch = glancePitch;
				}
			}
		}
		if (point != null) {
			double dx = point.x - eye.x;
			double dy = point.y - eye.y;
			double dz = point.z - eye.z;
			double h = Math.sqrt(dx * dx + dz * dz);
			if (h > 1e-3 || Math.abs(dy) > 1e-3) {
				wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
				wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(h, 1e-3)));
				tracking = true;
			}
		} else {
			wantPitch += postureNod();
		}
		float rel = Mth.wrapDegrees(wantYaw - body);
		boolean canTurnBody = !walking && sit < 0.05f && (faceBody || posture == Posture.IDLE || posture == Posture.TALK || posture == Posture.WAIT);
		if (tracking && canTurnBody && Math.abs(rel) > (faceBody ? 25 : 70)) {
			// turn towards whoever we look at (waiting agents face you)
			e.motion().faceTowards(Mth.wrapDegrees(wantYaw));
		} else {
			e.motion().faceTowards(null);
		}
		float limit = sit > 0.5f ? 60f : 75f;
		rel = Mth.clamp(rel, -limit, limit);
		wantPitch = Mth.clamp(wantPitch, -40f, 45f);
		float absWant = body + rel;
		if (snap || age <= 1) {
			headYaw = absWant;
			headPitch = wantPitch;
		} else {
			float dy = Mth.wrapDegrees(absWant - headYaw);
			float step = Mth.clamp(dy * 0.35f, -14f, 14f);
			headYaw = Mth.wrapDegrees(headYaw + step);
			headPitch += Mth.clamp((wantPitch - headPitch) * 0.3f, -9f, 9f);
		}
		// never let the head lag outside the neck's range while the body turns
		float off = Mth.clamp(Mth.wrapDegrees(headYaw - body), -limit, limit);
		headYaw = body + off;
		e.setHeadLook(headYaw, headPitch);
	}

	/** Working, thinking or in trouble: the agent's attention is on its work. */
	private boolean isBusy(AgentView v) {
		String f = v.dotFamily();
		return v.active && !v.paused && (f.equals("working") || f.equals("thinking") || f.equals("error"));
	}

	/** Small posture-specific head pitch when nothing in particular is looked at. */
	private float postureNod() {
		return switch (posture) {
			case READ -> 34f;
			case TYPE_STAND -> 24f;
			case LEAN_BENCH -> 30f;
			case REVIEW -> 32f;
			case SIT_TYPE -> 6f;
			case SIT_THINK, THINK -> -12f;
			case SCRATCH, SIT_SCRATCH -> 14f;
			case STRETCH -> -22f;
			case RELAX -> -8f;
			case WAIT -> 2f;
			default -> 4f;
		};
	}

	private @Nullable Vec3 lookPointFor(String who, @Nullable Vec3 playerEye, double playerDist) {
		if (who.equals("user")) {
			return playerEye != null && playerDist < 24 ? playerEye : null;
		}
		if (who.equals("all")) {
			return null;
		}
		ClientAgentEntity other = AgentManager.get().entity(who);
		if (other == null || other == e || other.distanceToSqr(e) > 24 * 24) {
			return null;
		}
		return new Vec3(other.getX(), other.getY() + EYE + other.life().sitOffset(), other.getZ());
	}

	/** Where a busy agent looks: the monitor at its desk; the book / screen / bench below otherwise (via the nod). */
	private @Nullable Vec3 stationFocus(AgentView v) {
		if (age < glanceUntil && (posture == Posture.SIT_TYPE || posture == Posture.SIT_IDLE)) {
			return null; // a glance away from the screen
		}
		if (v.station.equals("desk") && (posture == Posture.SIT_TYPE || posture == Posture.SIT_IDLE || posture == Posture.TYPE_STAND)) {
			Anchor mon = Anchors.get(AnchorNames.monitor(v.id));
			if (mon != null && mon.pos().distanceToSqr(e.position()) < 9) {
				return new Vec3(mon.x(), mon.y() - 0.12, mon.z());
			}
		}
		return null;
	}

	private void startGlance(AgentView v) {
		boolean busy = !"idle".equals(v.dotFamily()) && !"done".equals(v.dotFamily());
		nextGlance = age + (busy ? 160 + rand(240) : 70 + rand(130));
		glanceUntil = age + (busy ? 22 + rand(20) : 28 + rand(40));
		glanceAt = Integer.MIN_VALUE;
		if (rand01() < 0.55f) {
			ClientAgentEntity best = null;
			double bestD = 7 * 7;
			int skip = rand(3);
			for (ClientAgentEntity o : AgentManager.get().entities().values()) {
				if (o == e) {
					continue;
				}
				double d = o.distanceToSqr(e);
				Vec3 oe = new Vec3(o.getX(), o.getY() + EYE, o.getZ());
				if (d < bestD && inFront(e.position().add(0, EYE, 0), oe, e.yBodyRot, 100)) {
					if (skip-- > 0 && best != null) {
						continue;
					}
					best = o;
					bestD = d;
				}
			}
			if (best != null) {
				glanceAt = best.getId();
				return;
			}
		}
		float side = rand01() < 0.5f ? -1f : 1f;
		glanceYaw = side * (18f + rand01() * 30f);
		glancePitch = -6f + rand01() * 14f;
		if (busy) {
			glanceYaw *= 0.6f;
			glancePitch = -10f;
		}
	}

	private static boolean inFront(Vec3 from, Vec3 to, float bodyYaw, float maxDeg) {
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		return Math.abs(Mth.wrapDegrees(yaw - bodyYaw)) <= maxDeg;
	}

	// ---------------------------------------------------------------- particles

	private void particlesTick(AgentView v, String fam, boolean alive, boolean walking) {
		boolean on = alive && v.active && !v.paused;
		if (confettiPending) {
			confettiPending = false;
			if (on && age - lastConfetti > 60) {
				particles.confetti(e, EYE - 0.15 + sitOffset(), this);
				lastConfetti = age;
			}
		}
		if (!on) {
			return;
		}
		switch (fam) {
			case "thinking" -> {
				if (age >= nextSpark) {
					nextSpark = age + 9 + rand(9);
					particles.sparkle(e, EYE + 0.15 + sitOffset(), this);
				}
			}
			case "working" -> {
				if (!walking && age >= nextMote) {
					nextMote = age + 7 + rand(8);
					particles.mote(e, workPoint(), this);
				}
			}
			case "error" -> {
				if (age >= nextAmbientPuff) {
					nextAmbientPuff = age + 36 + rand(28);
					if (age - lastPuff > 30) {
						particles.puff(e, EAR + sitOffset(), 4, this);
					}
				}
			}
			default -> {
			}
		}
	}

	/** Where working motes rise from: the hands (keyboard, book, bench), relative to the feet. */
	private Vec3 workPoint() {
		double fwd = switch (posture) {
			case SIT_TYPE -> 0.5;
			case READ -> 0.42;
			case LEAN_BENCH, REVIEW -> 0.6;
			case TYPE_STAND -> 0.55;
			default -> 0.4;
		};
		double up = switch (posture) {
			case SIT_TYPE -> 1.0 + sitOffset();
			case READ -> 1.15;
			case LEAN_BENCH -> 0.95;
			default -> 1.05;
		};
		double rad = Math.toRadians(e.yBodyRot);
		return new Vec3(-Math.sin(rad) * fwd, up, Math.cos(rad) * fwd);
	}

	// ---------------------------------------------------------------- frame

	/** Interpolate this tick's simulation into the render state (client thread, once per frame). */
	void extract(AgentRenderState s, float pt) {
		for (int i = 0; i < N; i++) {
			frame[i] = prev[i] + (cur[i] - prev[i]) * pt;
		}
		float t = age + pt;
		float tw = typeWPrev + (typeW - typeWPrev) * pt;
		if (tw > 0.001f) {
			// typing: quick alternating taps with a slow drift between keys
			frame[P_RAX] += tw * (0.075f * Mth.sin(t * 1.9f) + 0.03f * Mth.sin(t * 0.37f));
			frame[P_LAX] += tw * (0.075f * Mth.sin(t * 1.9f + 2.2f) + 0.03f * Mth.sin(t * 0.41f + 1f));
			frame[P_RAY] += tw * 0.04f * Mth.sin(t * 0.23f);
			frame[P_LAY] += tw * 0.04f * Mth.sin(t * 0.27f + 0.5f);
		}
		float kw = talkWPrev + (talkW - talkWPrev) * pt;
		if (kw > 0.001f) {
			// talking: the right hand moves with the words
			frame[P_RAX] += kw * (0.22f * Mth.sin(t * 0.31f) + 0.08f * Mth.sin(t * 0.83f));
			frame[P_RAY] += kw * 0.1f * Mth.sin(t * 0.19f);
		}
		if (posture == Posture.STRETCH) {
			frame[P_RAZ] += 0.06f * Mth.sin(t * 0.5f);
			frame[P_LAZ] -= 0.06f * Mth.sin(t * 0.5f);
		}
		s.posePose = frame;
		s.life = this;
		float sitF = sitPrev + (sit - sitPrev) * pt;
		s.sit = sitF;
		s.sitDrop = (float) (drop * sitF);
		s.plateBase = (float) Nameplate.HEIGHT + s.sitDrop;
		s.book = frame[P_BOOK];
		float flip = (age - pageFlipStart + pt) / 12f;
		s.pageFlip = flip >= 0 && flip <= 1 ? flip : 0f;
		s.exclaim = exclaimPrev + (exclaim - exclaimPrev) * pt;
		s.bubble = bubble.visibility(age, pt);
	}

	public int age() {
		return age;
	}

	private net.minecraft.client.model.object.book.BookModel.@Nullable State bookState;

	/**
	 * The held book's model state, reused while it does not change (the values are quantised to
	 * 1/64, so a steadily reading agent submits the same immutable state every frame: no
	 * allocation; a page flip or opening creates a few).
	 */
	net.minecraft.client.model.object.book.BookModel.State bookState(float openness, float flip1, float flip2) {
		float o = Math.round(openness * 64f) / 64f;
		float a = Math.round(flip1 * 64f) / 64f;
		float b = Math.round(flip2 * 64f) / 64f;
		var st = bookState;
		if (st == null || st.openness() != o || st.pageFlip1() != a || st.pageFlip2() != b) {
			st = new net.minecraft.client.model.object.book.BookModel.State(o, a, b);
			bookState = st;
		}
		return st;
	}

	private static float approach(float v, float target, float step) {
		return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
	}
}

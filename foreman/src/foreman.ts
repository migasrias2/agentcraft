// Foreman core: owns all state, composes the subsystems, applies user intents and exposes the
// primitives backends (sim / claude) use to drive agents. Transport-agnostic: the WS server feeds
// it ClientMessages and subscribes to outbound protocol messages.
import fs from 'node:fs';
import path from 'node:path';
import { MessageBus } from './bus.js';
import { loadCast, type CastMember } from './cast.js';
import type { Config } from './config.js';
import { FOREMAN_VERSION } from './config.js';
import { consoleLogger, type Ctx, type Logger } from './context.js';
import { DecisionError, DecisionQueue, type CreateDecisionInput } from './decisions.js';
import { Memory, MemoryError } from './memory.js';
import { Notifier } from './notifier.js';
import { DocError, docTitle, pruneDocs, readDoc } from './docs.js';
import { type VideoLimits, MediaError, MediaHub } from './media.js';
import { ScreenHub, type ScreenFrame } from './screens.js';
import { SocialDesk, SocialError } from './social.js';
import { ContentError, ContentStudio, type ContentExternals } from './content.js';
import { renderBreakdown, scanChannel, studyVideo } from './videostudy.js';
import { createYouTubeApi, publicStats } from './youtube.js';
import type {
  Agent,
  AgentState,
  BackendName,
  ClientMessage,
  Decision,
  Doc,
  ForemanStatus,
  Media,
  Goal,
  GoalStatus,
  LogEntry,
  LogKind,
  Outbound,
  Station,
  Task,
} from './protocol.js';
import { PERMISSION_OPTIONS } from './protocol.js';
import { RepoError, RepoManager } from './repos.js';
import { routeGoal } from './reporoute.js';
import { Store } from './store.js';
import { TaskError, TaskGraph } from './taskgraph.js';
import { Brain } from './brain.js';
import { setUserName, userName } from './user.js';
import { truncate } from './util/text.js';

export interface Backend {
  readonly name: BackendName;
  /** Called once after the core is ready (and after restart: resume work). */
  start(): Promise<void>;
  stop(): Promise<void>;
  submitGoal(goal: Goal): Promise<void>;
  onUserMessage(to: string, text: string): void;
  /** After a decision was answered and its side effects (merge etc.) applied. */
  onDecisionSettled(d: Decision): void;
  /**
   * An approved merge conflicts with the base branch (another task merged first). Return true when
   * the backend sent the task back to its worker to merge the base and resolve it; false (or no
   * method) leaves the merge decision open with the reason, for the user to handle.
   */
  onMergeConflict?(task: Task, info: { base: string; branch: string; files: string[]; reason: string }): boolean;
  onTaskAction(task: Task, action: 'reassign' | 'cancel' | 'retry' | 'prioritize', arg?: string): void;
  onAgentAction(agentId: string, action: 'pause' | 'resume' | 'stop' | 'spawn', arg?: string): Promise<void> | void;
}

export type Reply = (msg: Outbound) => void;

export class ClientError extends Error {}

export interface ForemanOptions {
  config: Config;
  logger?: Logger;
  notifier?: Notifier;
  now?: () => number;
  /** the Content Room's yt-dlp/ffmpeg and YouTube collaborators (tests inject fakes); default: the real ones */
  content?: ContentExternals;
}

const LOG_TEXT_MAX = 2000;

export class Foreman {
  readonly config: Config;
  readonly store: Store;
  readonly ctx: Ctx;
  readonly tasks: TaskGraph;
  readonly bus: MessageBus;
  readonly memory: Memory;
  /** the user's notes vault (`--brain`), shared by every profile; undefined when off or missing */
  readonly brain?: Brain;
  readonly decisions: DecisionQueue;
  readonly repos: RepoManager;
  readonly notifier: Notifier;
  readonly log: Logger;
  readonly cast: CastMember[];
  backend: Backend | undefined;
  status: ForemanStatus;

  private listeners = new Set<(m: Outbound) => void>();
  private logBuffers = new Map<string, LogEntry[]>();
  private logTimer: NodeJS.Timeout | undefined;
  private goalTimers = new Set<string>();
  private screens: ScreenHub;
  readonly media: MediaHub;
  readonly social: SocialDesk;
  readonly content: ContentStudio;
  private closed = false;

  constructor(opts: ForemanOptions) {
    this.config = opts.config;
    this.log = opts.logger ?? consoleLogger('foreman', { debug: opts.config.debug, quiet: opts.config.quiet });
    this.store = new Store(opts.config.dataDir);
    const now = opts.now ?? Date.now;
    this.ctx = { store: this.store, emit: (m) => this.emit(m), now, log: this.log };
    this.media = new MediaHub((item) => this.emit({ type: 'media.add', item }), now);
    this.screens = new ScreenHub(({ agentId, mime, data, tool, at }) => this.emit({ type: 'agent.screen', agentId, mime, data, tool, at }), now);
    this.tasks = new TaskGraph(this.ctx);
    this.bus = new MessageBus(this.ctx);
    this.memory = new Memory(this.ctx, path.join(opts.config.dataDir, 'memory'));
    if (opts.config.brainDir) {
      try {
        this.brain = new Brain(opts.config.brainDir);
        this.log.info(`brain: ${this.brain.dir}`);
      } catch (e) {
        this.log.warn(`brain off: ${(e as Error).message}`);
      }
    }
    this.decisions = new DecisionQueue(this.ctx);
    this.social = new SocialDesk({
      ctx: this.ctx,
      bus: this.bus,
      names: (id) => this.nameOf(id),
      notify: (level, text) => this.notify(level, text),
      dir: path.join(opts.config.dataDir, 'social'),
    });
    this.content = new ContentStudio({
      ctx: this.ctx,
      bus: this.bus,
      names: (id) => this.nameOf(id),
      notify: (level, text) => this.notify(level, text),
      dir: path.join(opts.config.dataDir, 'content'),
      showMedia: (agentId, file, caption, limits) => this.showMedia(agentId, file, caption, limits),
      ...(opts.content ?? { study: { studyVideo, renderBreakdown, scanChannel }, youtube: createYouTubeApi(), publicStats: (input) => publicStats(input) }),
    });
    this.repos = new RepoManager(this.ctx, path.join(opts.config.dataDir, 'worktrees'), { mergeStyle: opts.config.mergeStyle, signMerges: opts.config.signMerges });
    this.notifier =
      opts.notifier ??
      new Notifier({ enabled: opts.config.notify, silent: opts.config.toastSilent, log: this.log, now });
    const { cast, source } = loadCast(opts.config.projectRoot);
    this.cast = cast;
    this.log.debug(`cast from ${source}`);
    setUserName(opts.config.userName);
    this.status = { version: FOREMAN_VERSION, backend: opts.config.backend, auth: opts.config.backend === 'sim' ? 'ok' : 'unknown', userName: userName() };
    if (opts.config.backend === 'sim') this.status.message = 'Simulated team (sim backend)';
    // --auto-approve turns it on for this start; otherwise the last in-game choice is kept
    if (opts.config.autoApprove) this.store.data.settings = { ...this.store.data.settings, autoApprove: true };
    this.status.autoApprove = this.store.data.settings?.autoApprove === true;
    this.initRoster();
    this.decisions.onCreated((d) => this.onDecisionCreated(d));
  }

  // ---- event plumbing ---------------------------------------------------------------------

  subscribe(l: (m: Outbound) => void): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  private emit(m: Outbound): void {
    if (m.type === 'task.upsert' && m.task.goalId) this.scheduleGoalUpdate(m.task.goalId);
    for (const l of this.listeners) {
      try {
        l(m);
      } catch (e) {
        this.log.error(`listener failed: ${(e as Error).message}`);
      }
    }
  }

  // ---- roster -----------------------------------------------------------------------------

  private initRoster(): void {
    const agents = this.store.data.agents;
    for (const c of this.cast) {
      let a = agents.find((x) => x.id === c.id);
      if (!a) {
        a = {
          id: c.id,
          name: c.name,
          role: c.role,
          title: c.title,
          color: c.color,
          skin: c.id,
          state: 'idle',
          activity: c.role === 'lead' ? 'ready for a goal' : 'off shift',
          station: 'lounge',
          paused: false,
          active: c.role === 'lead' || !!c.team && c.team !== 'studio',
        };
        if (c.team === 'social' || c.team === 'content') {
          a.team = c.team;
          a.station = 'desk';
          a.activity = 'ready';
        }
        if (c.accent) a.accent = c.accent;
        agents.push(a);
      } else {
        // cast may have been updated by the art track
        a.name = c.name;
        a.title = c.title;
        a.color = c.color;
        if (c.accent) a.accent = c.accent;
        a.role = c.role;
        if (c.team === 'social' || c.team === 'content') a.team = c.team;
        else delete a.team;
      }
    }
    this.store.markDirty();
  }

  /** The Social Desk agents (Nova, Sable): never on the coding team, never given tasks. */
  isSocial(id: string): boolean {
    return this.agent(id)?.team === 'social';
  }

  /** The Content Room agents (Iris, Reel, Vega, Cleo): never on the coding team, never given tasks. */
  isContent(id: string): boolean {
    return this.agent(id)?.team === 'content';
  }

  /** A desk agent: works in a plain folder for the user (Social Desk or Content Room), not on the coding team. */
  isDesk(id: string): boolean {
    return this.isSocial(id) || this.isContent(id);
  }

  /** "the Social Desk" / "the Content Room" for messages. */
  deskName(id: string): string {
    return this.isContent(id) ? 'the Content Room' : 'the Social Desk';
  }

  agents(): Agent[] {
    return this.store.data.agents;
  }

  agent(id: string): Agent | undefined {
    return this.store.data.agents.find((a) => a.id === id);
  }

  requireAgent(id: string): Agent {
    const a = this.agent(id);
    if (!a) throw new ClientError(`no agent "${id}"`);
    return a;
  }

  /** Resolve "@Kit", "kit", "Kit" -> "kit". */
  resolveAgentId(nameOrId: string): string | undefined {
    const n = nameOrId.replace(/^@/, '').trim().toLowerCase();
    return this.agents().find((a) => a.id === n || a.name.toLowerCase() === n)?.id;
  }

  nameOf(id: string): string {
    if (id === 'user') return `${userName()}`;
    return this.agent(id)?.name ?? id;
  }

  /** Patch an agent and broadcast if anything changed. */
  setAgent(
    id: string,
    patch: Partial<Pick<Agent, 'state' | 'station' | 'activity' | 'paused' | 'active' | 'engine' | 'model'>> & {
      taskId?: string | null;
      repoId?: string | null;
      worktree?: string | null;
    },
  ): Agent {
    const a = this.requireAgent(id);
    let changed = false;
    const set = <K extends 'state' | 'station' | 'activity' | 'paused' | 'active' | 'engine' | 'model'>(k: K, v: Agent[K] | undefined) => {
      if (v !== undefined && a[k] !== v) {
        a[k] = v;
        changed = true;
      }
    };
    set('state', patch.state);
    set('station', patch.station);
    if (patch.activity !== undefined) set('activity', truncate(patch.activity.replace(/\s+/g, ' ').trim(), 48));
    set('paused', patch.paused);
    set('active', patch.active);
    set('engine', patch.engine);
    set('model', patch.model);
    for (const k of ['taskId', 'repoId', 'worktree'] as const) {
      const v = patch[k];
      if (v === undefined) continue;
      if (v === null) {
        if (a[k] !== undefined) {
          delete a[k];
          changed = true;
        }
      } else if (a[k] !== v) {
        a[k] = v;
        changed = true;
      }
    }
    if (changed) {
      this.store.markDirty();
      this.emit({ type: 'agent.upsert', agent: { ...a } });
    }
    return a;
  }

  /** Convenience: state + station + activity in one call. */
  act(id: string, state: AgentState, station: Station, activity: string): void {
    this.setAgent(id, { state, station, activity });
  }

  // ---- logs ---------------------------------------------------------------------------------

  /** Append a log line to an agent monitor. Batched (~30ms) into agent.log frames. */
  agentLog(agentId: string, kind: LogKind, text: string): void {
    if (this.closed) return;
    const entry: LogEntry = { ts: this.ctx.now(), kind, text: truncate(text.replace(/\r\n/g, '\n'), LOG_TEXT_MAX) };
    this.store.appendLog(agentId, [entry]);
    const buf = this.logBuffers.get(agentId) ?? [];
    buf.push(entry);
    this.logBuffers.set(agentId, buf);
    if (!this.logTimer) {
      this.logTimer = setTimeout(() => this.flushLogs(), 30);
      this.logTimer.unref?.();
    }
  }

  flushLogs(): void {
    if (this.logTimer) clearTimeout(this.logTimer);
    this.logTimer = undefined;
    for (const [agentId, entries] of this.logBuffers) {
      if (entries.length) this.emit({ type: 'agent.log', agentId, entries });
    }
    this.logBuffers.clear();
  }

  /** A tool returned a screenshot: broadcast it (throttled, newest wins). Memory only, not persisted. */
  agentScreen(agentId: string, frame: ScreenFrame): void {
    if (this.closed) return;
    this.screens.push(agentId, frame);
  }

  // ---- goals --------------------------------------------------------------------------------

  goals(): Goal[] {
    return this.store.data.goals;
  }

  currentGoal(): Goal | undefined {
    const g = this.store.data.goals;
    return g[g.length - 1];
  }

  goal(id: string): Goal | undefined {
    return this.store.data.goals.find((g) => g.id === id);
  }

  createGoal(text: string, repoId?: string): Goal {
    const now = this.ctx.now();
    const goal: Goal = { id: this.store.nextId('g'), text: text.trim(), progress: 0, status: 'planning', createdAt: now, updatedAt: now };
    if (repoId) goal.repoId = repoId;
    this.store.data.goals.push(goal);
    this.store.markDirty();
    this.emit({ type: 'goal.upsert', goal: { ...goal } });
    this.bus.feed('goal', `New goal: ${goal.text}`, { agentId: 'user' });
    return goal;
  }

  setGoal(id: string, patch: { status?: GoalStatus; progress?: number; text?: string }): Goal {
    const g = this.goal(id);
    if (!g) throw new ClientError(`no goal ${id}`);
    let changed = false;
    if (patch.status && g.status !== patch.status) {
      g.status = patch.status;
      changed = true;
    }
    if (patch.progress !== undefined && Math.abs(g.progress - patch.progress) > 1e-6) {
      g.progress = Math.max(0, Math.min(1, patch.progress));
      changed = true;
    }
    if (patch.text && patch.text !== g.text) {
      g.text = patch.text;
      changed = true;
    }
    if (changed) {
      g.updatedAt = this.ctx.now();
      this.store.markDirty();
      this.emit({ type: 'goal.upsert', goal: { ...g } });
    }
    return g;
  }

  private scheduleGoalUpdate(goalId: string): void {
    if (this.goalTimers.has(goalId)) return;
    this.goalTimers.add(goalId);
    queueMicrotask(() => {
      this.goalTimers.delete(goalId);
      const g = this.goal(goalId);
      if (!g) return;
      const all = this.tasks.forGoal(goalId);
      const live = all.filter((t) => t.status !== 'cancelled');
      // every task was cancelled or rejected: the goal is over (it would sit at 0% forever);
      // if the lead adds a new task to it later, it is active again
      if (g.status === 'active' && all.length && !live.length) {
        this.setGoal(goalId, { status: 'cancelled', progress: 0 });
        this.bus.feed('goal', `Goal closed: every task was cancelled or rejected (${truncate(g.text, 80)})`);
        return;
      }
      if (g.status === 'cancelled' && live.length) this.setGoal(goalId, { status: 'active' });
      // progress stays 0 while the lead is still planning (avoids a jittering ring)
      if (g.status === 'cancelled' || g.status === 'failed' || g.status === 'planning') return;
      const progress = this.tasks.progress(goalId);
      const complete = this.tasks.goalComplete(goalId);
      const wasDone = g.status === 'done';
      this.setGoal(goalId, { progress: complete ? 1 : progress, ...(complete ? { status: 'done' as const } : g.status === 'done' ? { status: 'active' as const } : {}) });
      if (complete && !wasDone) {
        this.bus.feed('goal', `Goal complete: ${g.text}`);
        this.notify('info', `Goal complete: ${truncate(g.text, 80)}`);
      }
    });
  }

  // ---- decisions ----------------------------------------------------------------------------

  createDecision(input: CreateDecisionInput): Decision {
    return this.decisions.create(input);
  }

  private onDecisionCreated(d: Decision): void {
    const who = this.nameOf(d.agentId);
    const label = d.kind === 'merge' ? 'merge review' : d.kind === 'permission' ? 'permission' : 'question';
    this.bus.feed('decision', `${who} needs you (${label}): ${d.question}`, { agentId: d.agentId, to: 'user' });
    this.notify('need_user', `${who}: ${truncate(d.question, 120)}`, d.id);
    this.notifier.needUser(`${who}: ${d.question}`);
  }

  notify(level: 'info' | 'warn' | 'need_user', text: string, decisionId?: string): void {
    const m: Outbound = {
      type: 'notify',
      level,
      text,
      ts: this.ctx.now(),
      ...(decisionId ? { decisionId } : {}),
    };
    this.emit(m);
  }

  /** Answer a decision, run kind-specific side effects, then wake the waiting agent. */
  async answerDecision(id: string, option?: string | number, text?: string): Promise<Decision> {
    let d: Decision;
    try {
      d = this.decisions.answer(id, option, text);
    } catch (e) {
      if (e instanceof DecisionError) throw new ClientError(e.message);
      throw e;
    }
    const answerText = [d.answer?.option, d.answer?.text].filter(Boolean).join(' — ');
    this.bus.feed('decision', `${userName()} answered ${this.nameOf(d.agentId)}: ${answerText}`, { agentId: 'user', to: d.agentId });
    if (d.kind === 'merge') await this.applyMergeAnswer(d);
    if (d.status === 'answered' || d.status === 'cancelled') {
      this.decisions.settle(d.id);
      try {
        this.backend?.onDecisionSettled(d);
      } catch (e) {
        this.log.error(`backend.onDecisionSettled: ${(e as Error).message}`);
      }
    }
    return d;
  }

  private async applyMergeAnswer(d: Decision): Promise<void> {
    const task = d.taskId ? this.tasks.get(d.taskId) : undefined;
    const option = d.answer?.option;
    if (option === 'Merge') {
      try {
        const res = await this.repos.merge(d, task ? { commitMessage: `${task.id}: ${task.title}${task.summary ? `\n\n${task.summary}` : ''}` } : {});
        if (task) this.tasks.setStatus(task.id, 'done', { viaMerge: true, force: task.status !== 'review' });
        this.bus.feed('merge', `Merged ${res.branch} into ${res.base} (${res.sha}, ${res.files} file${res.files === 1 ? '' : 's'})`, { agentId: d.agentId });
        this.notify('info', `Merged ${res.branch} into ${res.base}`);
      } catch (e) {
        if (e instanceof RepoError && e.code === 'empty' && task && d.repoId && d.worktree) {
          // nothing to merge (a report or investigation): the task is simply done
          await this.repos.abandon(d.repoId, d.worktree, `agentcraft: ${task.id} (no changes)`).catch((err) => this.log.warn(`abandon: ${(err as Error).message}`));
          this.tasks.setStatus(task.id, 'done', { force: true });
          this.bus.feed('merge', `Nothing to merge for ${task.id} (no file changes): closed as done`, { agentId: d.agentId });
          this.notify('info', `${task.id} had no changes: closed as done`);
          return;
        }
        const reason = e instanceof RepoError ? e.message : `merge failed: ${(e as Error).message}`;
        if (e instanceof RepoError && e.code === 'conflict' && task?.assignee && d.repoId && d.worktree && this.backend?.onMergeConflict) {
          // parallel tasks touched the same lines: the branch's worker merges the base and resolves
          // it, then the task comes back through review with a fresh merge decision
          const wt = this.repos.findWorktree(d.repoId, d.worktree);
          const info = { base: wt?.base ?? 'main', branch: wt?.branch ?? d.worktree, files: e.files, reason };
          let handled = false;
          try {
            handled = this.backend.onMergeConflict(task, info);
          } catch (err) {
            this.log.error(`backend.onMergeConflict: ${(err as Error).message}`);
          }
          if (handled) {
            this.log.info(`merge for ${d.id} conflicts with ${info.base} (${e.files.join(', ')}): sent back to ${task.assignee}`);
            this.bus.feed('merge', `${task.id} conflicts with ${info.base} in ${e.files.join(', ') || 'some files'}: ${this.nameOf(task.assignee)} merges ${info.base} and resolves it`, { agentId: task.assignee });
            this.notify('info', `${task.id} conflicts with ${info.base}: sent back to ${this.nameOf(task.assignee)} to resolve`);
            return;
          }
        }
        this.log.warn(`merge for ${d.id} refused: ${reason}`);
        const base = (d.context ?? '').replace(/\n*Merge refused: [\s\S]*$/, '');
        this.decisions.reopen(d.id, `${base}${base ? '\n\n' : ''}Merge refused: ${reason}`);
        this.bus.feed('error', `Merge refused: ${reason}`, { agentId: d.agentId });
        this.notify('warn', `Merge refused: ${truncate(reason, 160)}`, d.id);
        // broadcast the repo as it is now (e.g. dirty=true), so the mod can show why
        if (d.repoId && this.repos.get(d.repoId)) await this.repos.refresh(d.repoId).catch((err) => this.log.warn(`refresh ${d.repoId}: ${(err as Error).message}`));
      }
    } else if (option === 'Request changes') {
      if (task && task.status !== 'doing') {
        this.tasks.setStatus(task.id, 'doing', { force: true });
        if (d.answer?.text) this.tasks.update(task.id, { summary: `Changes requested: ${d.answer.text}` });
      }
    } else if (option === 'Reject') {
      if (d.repoId && d.worktree) await this.repos.abandon(d.repoId, d.worktree).catch((e) => this.log.warn(`abandon: ${(e as Error).message}`));
      if (task) {
        this.tasks.setStatus(task.id, 'cancelled', { force: true });
        for (const dep of this.tasks.dependents(task.id)) {
          if (dep.status === 'todo') this.tasks.setStatus(dep.id, 'blocked', { reason: `depends on rejected ${task.id}`, force: true });
        }
      }
      this.bus.feed('merge', `Rejected ${d.worktree ?? 'branch'} (branch kept for recovery)`, { agentId: d.agentId });
    }
  }

  // ---- settings -----------------------------------------------------------------------------

  /** Agents run tool calls without asking (the policy's "deny" still applies). */
  get autoApprove(): boolean {
    return this.status.autoApprove === true;
  }

  async setAutoApprove(on: boolean): Promise<void> {
    this.store.data.settings = { ...this.store.data.settings, autoApprove: on };
    this.store.markDirty();
    if (this.autoApprove === on) return;
    this.setStatus({ autoApprove: on });
    this.bus.feed('system', on ? `Auto-approve ON: agents run tools without asking ${userName()} (git push stays blocked)` : 'Auto-approve OFF: agents ask before risky tools again');
    if (!on) return;
    // whatever was already waiting at the podium for a permission goes ahead too
    for (const d of this.store.data.decisions.filter((x) => x.status === 'open' && x.kind === 'permission')) {
      await this.answerDecision(d.id, PERMISSION_OPTIONS[0]).catch((e: Error) => this.log.warn(`auto-approve ${d.id}: ${e.message}`));
    }
  }

  // ---- media --------------------------------------------------------------------------------

  /** An agent hands the user an image or video (file path or URL): shown in the Screening Room. */
  async showMedia(agentId: string, source: string, caption?: string, limits?: VideoLimits): Promise<Media> {
    const item = await this.media.show(agentId, source, caption, limits);
    const what = item.kind === 'video' ? `a video (${item.name}${item.durationMs ? `, ${Math.round(item.durationMs / 1000)} s` : ''})` : `an image (${item.name})`;
    this.bus.feed('system', `${this.nameOf(agentId)} put ${what} on the Screening Room screen${item.caption ? `: ${truncate(item.caption, 120)}` : ''}`, { agentId });
    return item;
  }

  // ---- documents ----------------------------------------------------------------------------

  docs(): Doc[] {
    return this.store.data.docs ?? [];
  }

  /** An agent hands the user a document: it walks over and the user reads it in game. */
  handOver(agentId: string, file: string, opts: { title?: string; note?: string; taskId?: string } = {}): Doc {
    const { name, markdown } = readDoc(file);
    const doc: Doc = {
      id: this.store.nextId('doc'),
      agentId,
      title: docTitle(markdown, name, opts.title),
      name,
      path: file,
      markdown,
      ...(opts.note?.trim() ? { note: opts.note.trim().slice(0, 300) } : {}),
      ...(opts.taskId ? { taskId: opts.taskId } : {}),
      read: false,
      at: this.ctx.now(),
    };
    this.store.data.docs = pruneDocs([...this.docs(), doc]);
    this.store.markDirty();
    this.emit({ type: 'doc.upsert', doc });
    this.bus.feed('system', `${this.nameOf(agentId)} handed you "${doc.title}" (${name}): read it in game`, { agentId, to: 'user' });
    return doc;
  }

  markDocRead(docId: string): Doc {
    const d = this.docs().find((x) => x.id === docId);
    if (!d) throw new ClientError(`no document "${docId}"`);
    if (d.read) return d;
    const next = { ...d, read: true };
    this.store.data.docs = this.docs().map((x) => (x.id === docId ? next : x));
    this.store.markDirty();
    this.emit({ type: 'doc.upsert', doc: next });
    return next;
  }

  // ---- foreman status -------------------------------------------------------------------------

  setStatus(patch: Partial<ForemanStatus>): void {
    const next = { ...this.status, ...patch };
    if (JSON.stringify(next) === JSON.stringify(this.status)) return;
    this.status = next;
    this.emit({ type: 'foreman.status', status: { ...this.status } });
  }

  // ---- snapshot -----------------------------------------------------------------------------

  snapshot(): Outbound {
    this.flushLogs();
    const decisions = this.store.data.decisions;
    const open = decisions.filter((d) => d.status === 'open');
    const recent = decisions.filter((d) => d.status !== 'open').slice(-20);
    const goal = this.currentGoal();
    const screens = this.screens.fresh();
    const media = this.media.recent();
    const docs = this.docs();
    const socialPosts = this.social.posts();
    const contentChannels = this.content.channels();
    const contentVideos = this.content.videos();
    const contentRefs = this.content.refs();
    const contentStats = this.content.stats();
    const contentChannelStats = this.content.channelStats();
    return {
      type: 'snapshot',
      foreman: { ...this.status },
      agents: this.agents().map((a) => ({ ...a })),
      tasks: this.tasks.list().map((t) => ({ ...t, deps: [...t.deps] })),
      decisions: [...recent, ...open].sort((a, b) => a.createdAt - b.createdAt),
      repos: this.repos.list().map((r) => ({ ...r, worktrees: r.worktrees.map((w) => ({ ...w })) })),
      memory: this.memory.list(),
      ...(goal ? { goal: { ...goal } } : {}),
      goals: this.goals().map((g) => ({ ...g })),
      feed: this.store.data.feed.slice(-200),
      logs: this.agents().map((a) => ({ agentId: a.id, entries: this.store.logTail(a.id).slice(-60) })),
      ...(screens.length ? { screens } : {}),
      ...(media.length ? { media } : {}),
      ...(docs.length ? { docs: docs.map((d) => ({ ...d })) } : {}),
      ...(socialPosts.length ? { socialPosts: socialPosts.map((p) => ({ ...p })) } : {}),
      ...(contentChannels.length ? { contentChannels } : {}),
      ...(contentVideos.length ? { contentVideos: contentVideos.map((v) => ({ ...v, comments: v.comments.map((c) => ({ ...c })) })) } : {}),
      ...(contentRefs.length ? { contentRefs: contentRefs.map((r) => ({ ...r })) } : {}),
      ...(contentStats.length ? { contentStats: contentStats.map((s) => ({ ...s })) } : {}),
      ...(contentChannelStats.length ? { contentChannelStats: contentChannelStats.map((s) => ({ ...s })) } : {}),
    };
  }

  // ---- inbound ------------------------------------------------------------------------------

  /** Apply a client intent. `reply` sends to the originating client only. */
  async handle(msg: ClientMessage, reply: Reply): Promise<void> {
    const ack = (ok: boolean, extra: { error?: string; result?: Record<string, unknown> } = {}) => {
      if (msg.id) reply({ type: 'ack', re: msg.id, ok, ...extra });
    };
    try {
      const result = await this.dispatch(msg, reply);
      ack(true, result ? { result } : {});
    } catch (e) {
      const known = e instanceof ClientError || e instanceof MediaError || e instanceof DocError || e instanceof SocialError || e instanceof ContentError || e instanceof TaskError || e instanceof RepoError || e instanceof DecisionError || e instanceof MemoryError;
      const message = known ? (e as Error).message : `internal error: ${(e as Error).message}`;
      if (!known) this.log.error(`${msg.type}: ${(e as Error).stack ?? e}`);
      reply({ type: 'error', message, ...(msg.id ? { re: msg.id } : {}) });
      ack(false, { error: message });
    }
  }

  private async dispatch(msg: ClientMessage, reply: Reply): Promise<Record<string, unknown> | undefined> {
    switch (msg.type) {
      case 'hello':
        reply(this.snapshot());
        return undefined;
      case 'goal.submit':
        return { goalId: (await this.submitGoal(msg.text, msg.repoId)).id };
      case 'user.message': {
        const { to, text } = this.routeUserMessage(msg.to, msg.text);
        this.bus.send('user', to, text);
        this.backend?.onUserMessage(to, text);
        return { to };
      }
      case 'decision.answer': {
        const d = await this.answerDecision(msg.decisionId, msg.option, msg.text);
        return { decisionId: d.id, status: d.status };
      }
      case 'task.action':
        this.taskAction(msg.taskId, msg.action, msg.arg);
        return { taskId: msg.taskId };
      case 'agent.action':
        await this.agentAction(msg.agentId, msg.action, msg.arg);
        return { agentId: msg.agentId };
      case 'diff.request': {
        try {
          const d = await this.repos.diff(msg.repoId, msg.worktree);
          reply({ type: 'diff', requestId: msg.requestId, repoId: d.repoId, worktree: d.worktree, base: d.base, branch: d.branch, files: d.files, stats: d.stats, truncated: d.truncated });
        } catch (e) {
          reply({ type: 'diff', requestId: msg.requestId, repoId: msg.repoId, worktree: msg.worktree, files: [], stats: { files: 0, additions: 0, deletions: 0 }, truncated: false, error: (e as Error).message });
        }
        return undefined;
      }
      case 'repo.add': {
        const r = await this.repos.add(msg.path);
        this.bus.feed('system', `Repo connected: ${r.name} (${r.branch})`);
        return { repoId: r.id };
      }
      case 'repo.create': {
        const r = await this.repos.create(msg.name, this.config.newRepoDir);
        this.bus.feed('system', `New repo created: ${r.name} (${r.path})`);
        if (msg.autoApprove) await this.setAutoApprove(true);
        const goal = msg.goal?.trim() ? await this.submitGoal(msg.goal, r.id) : undefined;
        return { repoId: r.id, path: r.path, ...(goal ? { goalId: goal.id } : {}) };
      }
      case 'settings.set':
        if (msg.autoApprove !== undefined) await this.setAutoApprove(msg.autoApprove);
        return { autoApprove: this.autoApprove };
      case 'doc.read':
        return { docId: this.markDocRead(msg.docId).id };
      case 'media.open':
        this.media.open(msg.mediaId);
        return { mediaId: msg.mediaId };
      case 'social.post.action': {
        const post = this.social.act(msg.postId, msg.action, { ...(msg.at !== undefined ? { at: msg.at } : {}), ...(msg.text !== undefined ? { text: msg.text } : {}) });
        return { postId: post.id, status: post.status };
      }
      case 'content.channel.action':
        return this.content.channelAction(msg.channelId, msg.action, {
          ...(msg.name !== undefined ? { name: msg.name } : {}),
          ...(msg.url !== undefined ? { url: msg.url } : {}),
          ...(msg.niche !== undefined ? { niche: msg.niche } : {}),
          ...(msg.brief !== undefined ? { brief: msg.brief } : {}),
          ...(msg.postsPerWeek !== undefined ? { postsPerWeek: msg.postsPerWeek } : {}),
        });
      case 'content.video.action': {
        const video = this.content.act(msg.videoId, msg.action, { ...(msg.text !== undefined ? { text: msg.text } : {}), ...(msg.at !== undefined ? { at: msg.at } : {}) });
        return { videoId: video.id, status: video.status };
      }
      case 'content.ref.open':
        await this.content.openRef(msg.refId, msg.frame);
        return { refId: msg.refId };
      case 'content.video.preview': {
        const media = await this.content.previewVideo(msg.videoId);
        return { videoId: msg.videoId, mediaId: media.id };
      }
    }
  }

  async submitGoal(text: string, repoId?: string): Promise<Goal> {
    const routed = repoId ? undefined : routeGoal(text, this.repos.list());
    const repo = repoId ? this.repos.get(repoId) : routed ? this.repos.get(routed.repoId) : this.repos.defaultRepo();
    if (repoId && !repo) throw new ClientError(`no repo "${repoId}"`);
    if (!repo) throw new ClientError('no repo connected yet — add one with /repo add <path>');
    if (!this.backend) throw new ClientError('no backend running');
    if (!repoId && this.repos.list().length > 1) this.bus.feed('system', `Goal → ${repo.name}${routed ? '' : ' (no repo named; most recent)'}`);
    const goal = this.createGoal(routed?.text ?? text, repo.id);
    await this.backend.submitGoal(goal);
    return goal;
  }

  routeUserMessage(to: string, text: string): { to: string; text: string } {
    let target = to;
    let body = text.trim();
    if (to === 'all') {
      const m = /^@([\w-]+)[\s,:]+([\s\S]+)$/.exec(body);
      if (m) {
        const id = this.resolveAgentId(m[1]!);
        if (!id) throw new ClientError(`no agent named "${m[1]}"`);
        target = id;
        body = m[2]!.trim();
      }
    } else {
      const id = this.resolveAgentId(to);
      if (!id) throw new ClientError(`no agent named "${to}"`);
      target = id;
    }
    return { to: target, text: body };
  }

  taskAction(taskId: string, action: 'reassign' | 'cancel' | 'retry' | 'prioritize', arg?: string): void {
    const t = this.tasks.require(taskId);
    switch (action) {
      case 'cancel':
        this.tasks.setStatus(t.id, 'cancelled', { force: true });
        for (const d of this.decisions.open().filter((d) => d.taskId === t.id)) this.decisions.cancel(d.id, 'task cancelled');
        this.bus.feed('task', `Task ${t.id} cancelled by ${userName()}: ${t.title}`, { agentId: 'user' });
        break;
      case 'retry':
        this.tasks.update(t.id, { ci: 'unknown', blockedReason: null });
        this.tasks.setStatus(t.id, 'todo', { force: true });
        this.bus.feed('task', `Task ${t.id} queued again: ${t.title}`, { agentId: 'user' });
        break;
      case 'prioritize': {
        const top = Math.max(0, ...this.tasks.list().map((x) => x.priority));
        const p = arg !== undefined && arg !== '' && Number.isFinite(Number(arg)) ? Math.trunc(Number(arg)) : top + 1;
        this.tasks.update(t.id, { priority: p });
        this.bus.feed('task', `Task ${t.id} priority -> ${p}`, { agentId: 'user' });
        break;
      }
      case 'reassign': {
        if (!arg) throw new ClientError('reassign needs an agent id');
        const id = this.resolveAgentId(arg);
        if (!id) throw new ClientError(`no agent named "${arg}"`);
        if (this.agent(id)?.role === 'lead') throw new ClientError('tasks are assigned to workers, not the lead');
        if (this.isDesk(id)) throw new ClientError(`${this.nameOf(id)} works for ${this.deskName(id)}, not on the coding team`);
        this.tasks.update(t.id, { assignee: id });
        if (t.status === 'doing') this.tasks.setStatus(t.id, 'todo', { force: true });
        this.bus.feed('task', `Task ${t.id} reassigned to ${this.nameOf(id)}`, { agentId: 'user' });
        break;
      }
    }
    this.backend?.onTaskAction(this.tasks.require(taskId), action, arg);
  }

  async agentAction(agentId: string, action: 'pause' | 'resume' | 'stop' | 'spawn', arg?: string): Promise<void> {
    const id = this.resolveAgentId(agentId);
    if (!id) throw new ClientError(`no agent named "${agentId}"`);
    if (action === 'pause') this.setAgent(id, { paused: true });
    if (action === 'resume') this.setAgent(id, { paused: false });
    if (action === 'spawn' && arg && !this.tasks.get(arg)) throw new ClientError(`no task "${arg}"`);
    if (action === 'spawn' && arg && this.isDesk(id)) throw new ClientError(`${this.nameOf(id)} works for ${this.deskName(id)}, not on the coding team`);
    if (action === 'spawn' && arg && this.agent(id)?.role === 'lead') throw new ClientError('tasks are assigned to workers, not the lead');
    if (action === 'spawn') this.setAgent(id, { active: true, paused: false });
    this.bus.feed('system', `${this.nameOf(id)}: ${action}${arg ? ` ${arg}` : ''}`, { agentId: 'user' });
    await this.backend?.onAgentAction(id, action, arg);
    // spawn @wren t3: on shift, and t3 is hers
    if (action === 'spawn' && arg) this.taskAction(arg, 'reassign', id);
  }

  // ---- lifecycle ----------------------------------------------------------------------------

  async start(backend: Backend): Promise<void> {
    this.backend = backend;
    for (const p of this.config.repos) {
      try {
        const r = await this.repos.add(p);
        this.log.info(`repo ${r.id}: ${r.path} (${r.branch})`);
      } catch (e) {
        this.log.error(`could not add repo ${p}: ${(e as Error).message}`);
      }
    }
    for (const r of this.repos.list()) {
      if (!fs.existsSync(r.path)) {
        this.log.warn(`repo ${r.id} path is gone: ${r.path}`);
        continue;
      }
      await this.repos.refresh(r.id).catch((e) => this.log.warn(`refresh ${r.id}: ${(e as Error).message}`));
    }
    this.repos.startPolling(this.config.repoPollMs);
    this.social.start(); // a post left 'posting' by the last run is failed before anything can publish
    this.content.start(); // same for a video left 'publishing'
    await backend.start();
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.repos.stopPolling();
    this.social.stop();
    this.content.stop();
    try {
      await this.backend?.stop();
    } catch (e) {
      this.log.error(`backend stop: ${(e as Error).message}`);
    }
    this.flushLogs();
    this.screens.close();
    this.media.close();
    this.notifier.dispose();
    this.store.close();
  }
}

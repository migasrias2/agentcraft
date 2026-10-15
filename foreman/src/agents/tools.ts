// The AgentCraft team tools, shared by every engine (Claude: an in-process MCP server named
// "agentcraft", see claude/tools.ts; Codex: app-server dynamic tools, see codex/engine.ts):
//   send_message, ask_user, write_memory, read_memory, update_task, report_status, list_tasks, show_media, hand_over
//   with --brain: search_brain, read_brain, brain_inbox (the user's notes vault, every profile)
//   lead only: create_task, request_merge
//   Social Desk (Nova, Sable): no task tools; list_posts + create_post/update_post/cancel_post or mark_posted/mark_failed (socialtools.ts)
//   Content Room (Iris, Reel, Vega, Cleo): no task tools; channels, videos, study_video, analytics, playbook... (contenttools.ts)
// Every tool result carries any unread messages for the agent (so mid-turn messages arrive).
import path from 'node:path';
import { z } from 'zod';
import { formatInbox } from '../bus.js';
import type { Foreman } from '../foreman.js';
import type { AgentState, Decision, TaskStatus } from '../protocol.js';
import { MERGE_OPTIONS } from '../protocol.js';
import { isPrBranch } from '../pulls.js';
import { truncate } from '../util/text.js';
import { boardSummary } from './prompts.js';
import { socialTools } from './socialtools.js';
import { contentTools } from './contenttools.js';
import { userName } from '../user.js';

export interface ToolHooks {
  /** worker moved its task to review */
  onReview(agentId: string, taskId: string): void;
  /** lead asked for changes on a task (status -> doing) */
  onChangesRequested(taskId: string, feedback: string): void;
  /** a task was created (scheduler tick) */
  onTasksChanged(): void;
  /** lead requested a merge (decision created) */
  onMergeRequested(taskId: string, decision: Decision): void;
  /** agent is blocked waiting for the user */
  onWaiting(agentId: string, waiting: boolean): void;
}

/** The turn a tool server belongs to: once it is aborted, tools refuse to act. */
export interface TurnHandle {
  signal: AbortSignal;
  /** why it was aborted: pause | stop | shutdown | cancel | timeout */
  reason(): string | undefined;
}

export type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

/** One team tool: an engine exposes it under `name` with `shape` as its input schema. */
export interface AgentTool {
  name: string;
  description: string;
  shape: z.ZodRawShape;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  handler(args: any): Promise<ToolResult>;
}

/** The brain tools; only offered when the Foreman has a brain (`--brain`). */
export const BRAIN_TOOL_NAMES = ['search_brain', 'read_brain', 'brain_inbox'] as const;

/** Names of the team tools for a role (unprefixed). */
export const TOOL_NAMES = {
  common: ['send_message', 'ask_user', 'write_memory', 'read_memory', 'update_task', 'report_status', 'list_tasks', 'show_media', 'hand_over', ...BRAIN_TOOL_NAMES],
  lead: ['create_task', 'request_merge'],
} as const;

/** Coding-team tools the Social Desk and the Content Room never see. */
const DESK_HIDDEN: ReadonlySet<string> = new Set(['update_task', 'list_tasks', 'create_task', 'request_merge']);

type Room = 'studio' | 'social' | 'content';
const ROOM_NAME: Record<Room, string> = { studio: 'the coding team', social: 'the Social Desk', content: 'the Content Room' };

function roomOf(fm: Foreman, id: string): Room {
  return fm.isContent(id) ? 'content' : fm.isSocial(id) ? 'social' : 'studio';
}

function tool<S extends z.ZodRawShape>(name: string, description: string, shape: S, handler: (args: z.infer<z.ZodObject<S>>) => Promise<ToolResult>): AgentTool {
  return { name, description, shape, handler };
}

/**
 * A task in review whose worktree changed nothing (a report, an investigation): there is nothing
 * to merge, so it is closed as done (worktree abandoned, branch kept) instead of asking the user to
 * approve an empty merge. Returns true if it was closed.
 */
export async function closeIfNoChanges(fm: Foreman, taskId: string): Promise<boolean> {
  const t = fm.tasks.get(taskId);
  if (!t || t.status !== 'review' || !t.repoId || !t.worktree) return false;
  await fm.repos.refresh(t.repoId);
  const wt = fm.repos.findWorktree(t.repoId, t.worktree);
  if (!wt || wt.status !== 'active' || wt.files > 0) return false;
  await fm.repos.abandon(t.repoId, wt.id, `agentcraft: ${t.id} (no changes)`);
  fm.tasks.setStatus(t.id, 'done', { force: true, summary: t.summary ?? 'no changes' });
  fm.bus.feed('task', `${t.id} changed no files (report only): closed as done, nothing to merge`, { agentId: t.assignee ?? 'marlow' });
  if (t.assignee && fm.agent(t.assignee)?.taskId === t.id) fm.setAgent(t.assignee, { state: 'idle', station: 'lounge', activity: `${t.id} done`, taskId: null, worktree: null });
  return true;
}

/** The team tools for one agent's turn (lead: also create_task and request_merge). */
export function agentTools(fm: Foreman, agentId: string, role: 'lead' | 'worker', hooks: ToolHooks, turn?: TurnHandle, cwd?: string): AgentTool[] {
  const withInbox = (text: string, isError = false): ToolResult => {
    const inbox = fm.bus.inbox(agentId, { markRead: true });
    const extra = inbox.length ? `\n\n[New messages]\n${formatInbox(inbox, (id) => fm.nameOf(id))}` : '';
    return { content: [{ type: 'text', text: text + extra }], ...(isError ? { isError: true } : {}) };
  };
  const fail = (text: string) => withInbox(`Error: ${text}`, true);

  const room = roomOf(fm, agentId);
  const tools: AgentTool[] = [
    tool(
      'hand_over',
      `Walk over to ${userName()} in the studio and hand them a document to read right there (a book screen in game). Use it for every report, plan, research note, summary or other .md/.txt deliverable ${userName()} should read, as soon as it is written (before update_task). Images and videos go to show_media instead.`,
      {
        path: z.string().describe('the .md / .txt file: relative to your working directory, or absolute'),
        title: z.string().optional().describe('short title (default: its first # heading)'),
        note: z.string().optional().describe('one line you say as you hand it over'),
      },
      async ({ path: p, title, note }) => {
        if (turn?.signal.aborted) return fail('your turn was stopped');
        const file = path.resolve(cwd ?? process.cwd(), p.trim().replace(/^~(?=$|[\\/])/, process.env.HOME ?? '~'));
        try {
          const taskId = fm.agent(agentId)?.taskId ?? undefined;
          const doc = fm.handOver(agentId, file, { ...(title ? { title } : {}), ...(note ? { note } : {}), ...(taskId ? { taskId } : {}) });
          return withInbox(`Handed over "${doc.title}". ${userName()} reads it in game; carry on with your work.`);
        } catch (e) {
          return fail((e as Error).message);
        }
      },
    ),
    tool(
      'show_media',
      `Show ${userName()} an image or a video on the big screen in the studio's Screening Room. Use it whenever you made, rendered, downloaded or generated something visual (a screenshot, a render, a generated image or clip) that ${userName()} should see. Videos play there as a silent preview; ${userName()} can open the original with sound.`,
      {
        path: z.string().describe('image (png jpg webp gif...) or video (mp4 mov webm...): a file path (relative to your working directory, or absolute) or an http(s) URL'),
        caption: z.string().optional().describe('one short line: what it is'),
      },
      async ({ path: p, caption }) => {
        if (turn?.signal.aborted) return fail('your turn was stopped');
        const source = /^https?:\/\//i.test(p.trim()) ? p.trim() : path.resolve(cwd ?? process.cwd(), p.trim().replace(/^~(?=$|[\\/])/, process.env.HOME ?? '~'));
        try {
          const item = await fm.showMedia(agentId, source, caption);
          return withInbox(`On the Screening Room screen now: ${item.kind} ${item.name}${item.kind === 'video' ? ` (${item.frames.length} preview frames)` : ''}.`);
        } catch (e) {
          return fail((e as Error).message);
        }
      },
    ),
    tool(
      'send_message',
      `Send a short message to a teammate (id or name), "lead", "all", or "user" (${userName()}; not a question — use ask_user for questions).`,
      { to: z.string().describe('agent id/name, "lead", "all" or "user"'), text: z.string().describe('the message (1-3 sentences)') },
      async ({ to, text }) => {
        let target = to.trim().toLowerCase();
        if (target === 'lead') target = 'marlow';
        if (!['all', 'user'].includes(target)) {
          const id = fm.resolveAgentId(target);
          if (!id) return fail(`no teammate "${to}". Team: ${fm.agents().filter((a) => a.active && roomOf(fm, a.id) === room).map((a) => a.id).join(', ')}`);
          target = id;
        }
        if (target === agentId) return fail('you cannot message yourself');
        // the Social Desk and the Content Room are rooms of their own: the coding team and the desks only talk to the user
        if (target === 'all' && room !== 'studio') return fail(`send to "user" (or your ${room === 'content' ? 'content room colleagues' : 'desk partner'}): the coding team is not part of ${ROOM_NAME[room]}`);
        if (target !== 'user' && target !== 'all' && roomOf(fm, target) !== room) return fail(`${fm.nameOf(target)} is ${roomOf(fm, target) === 'studio' ? 'on the coding team' : `in ${ROOM_NAME[roomOf(fm, target)]}`}, not in your room. ${room !== 'studio' ? 'Talk to the user (send_message to "user").' : `Only the user talks to ${ROOM_NAME[roomOf(fm, target)]}.`}`);
        fm.bus.send(agentId, target, text);
        // A worker reads messages only while it works on a task: one with no task would read this
        // whenever its next task starts, so a request sent this way silently never happens.
        if (role === 'lead' && !['all', 'user'].includes(target) && !fm.agent(target)?.taskId) {
          return withInbox(
            `Sent to ${target}, but ${fm.nameOf(target)} is not on a task, so they will only read it when their next task starts. ` +
              `To have ${fm.nameOf(target)} do something now, create a task for it with create_task (assignee "${target}").`,
          );
        }
        return withInbox(`Sent to ${target}.`);
      },
    ),
    tool(
      'ask_user',
      `Ask ${userName()} a question and WAIT for the answer. Only for decisions that are genuinely the user's. Put the recommended option first.`,
      {
        question: z.string(),
        options: z.array(z.string()).max(6).optional().describe('2-4 short choices, recommended first'),
        context: z.string().optional().describe('one or two lines of background'),
      },
      async ({ question, options, context }) => {
        const prev = fm.agent(agentId);
        // the stream mapper may already show the agent waiting at the user (it saw the tool call):
        // after the answer the agent goes back to thinking at its own station, not "waiting"
        const home = role === 'lead' ? 'meeting' : 'desk';
        const waiting = prev?.state === 'waiting_user' || prev?.station === 'user';
        const prevState = { state: waiting ? 'thinking' : (prev?.state ?? 'thinking'), station: waiting ? home : (prev?.station ?? home), activity: prev?.activity ?? '' };
        const d = fm.createDecision({ agentId, kind: 'question', question, options: options ?? [], ...(context ? { context } : {}), ...(prev?.taskId ? { taskId: prev.taskId } : {}) });
        fm.setAgent(agentId, { state: 'waiting_user', station: 'user', activity: 'waiting for your answer' });
        hooks.onWaiting(agentId, true);
        // If the turn is aborted (stop, pause, task cancelled, timeout) nobody will read the answer:
        // withdraw the question so it does not sit on the podium forever. A Foreman shutdown keeps
        // it open on purpose: after the restart the answer resumes the session.
        const onAbort = () => {
          if (turn?.reason() !== 'shutdown') fm.decisions.cancel(d.id, `${fm.nameOf(agentId)}'s turn was stopped`);
        };
        if (turn?.signal.aborted) onAbort();
        else turn?.signal.addEventListener('abort', onAbort, { once: true });
        const done = await fm.decisions.wait(d.id);
        turn?.signal.removeEventListener('abort', onAbort);
        hooks.onWaiting(agentId, false);
        if (turn?.signal.aborted) return withInbox(`Your turn was stopped before ${userName()} answered.`, true);
        fm.setAgent(agentId, { state: prevState.state as AgentState, station: prevState.station, activity: 'got your answer' });
        if (done.status === 'cancelled') return withInbox('The question was cancelled. Use your best judgement and note the assumption.');
        const ans = [done.answer?.option, done.answer?.text].filter(Boolean).join(' — ');
        return withInbox(`${userName()} answered: ${ans}`);
      },
    ),
    tool(
      'write_memory',
      'Write a markdown note to memory. scope "shared" (whole team) or "private" (only you). mode "append" adds to an existing note with the same title.',
      {
        title: z.string(),
        body: z.string().describe('markdown'),
        scope: z.enum(['shared', 'private']).optional(),
        mode: z.enum(['replace', 'append']).optional(),
      },
      async ({ title, body, scope, mode }) => {
        const e = fm.memory.write({ scope: scope === 'private' ? agentId : 'shared', title, body, author: agentId, mode: mode ?? 'replace' });
        fm.bus.feed('memory', `${fm.nameOf(agentId)} wrote memory: ${e.title}`, { agentId });
        return withInbox(`Saved memory ${e.id}.`);
      },
    ),
    tool(
      'read_memory',
      'Read memory. With id: that note. With query: matching notes. With neither: list all notes you can see.',
      { id: z.string().optional(), query: z.string().optional() },
      async ({ id, query }) => {
        if (id) {
          const e = fm.memory.get(id) ?? fm.memory.get(`shared/${id}`) ?? fm.memory.get(`${agentId}/${id}`);
          if (!e || (e.scope !== 'shared' && e.scope !== agentId)) return fail(`no memory ${id}`);
          return withInbox(`# ${e.title} (${e.id})\n\n${e.body}`);
        }
        const list = query ? fm.memory.search(query, agentId) : fm.memory.visibleTo(agentId);
        if (!list.length) return withInbox('No memory notes.');
        if (query && list.length <= 3) return withInbox(list.map((e) => `# ${e.title} (${e.id})\n\n${truncate(e.body, 3000)}`).join('\n\n---\n\n'));
        return withInbox(list.map((e) => `- ${e.id}: ${e.title}`).join('\n'));
      },
    ),
    tool(
      'update_task',
      role === 'lead'
        ? 'Update a task: status (todo|doing|blocked|cancelled; "doing" on a review task = request changes), summary/feedback, assignee, title, description.'
        : 'Update YOUR task: status "review" when done (with summary of changes + testing), "blocked" with blocked_reason, or "doing".',
      {
        task_id: z.string(),
        status: z.enum(['todo', 'doing', 'review', 'blocked', 'cancelled']).optional(),
        summary: z.string().optional(),
        blocked_reason: z.string().optional(),
        assignee: z.string().optional(),
        title: z.string().optional(),
        description: z.string().optional(),
      },
      async (a) => {
        const t = fm.tasks.get(a.task_id);
        if (!t) return fail(`no task ${a.task_id}. ${boardSummary(fm)}`);
        if (role === 'worker') {
          const current = fm.agent(agentId)?.taskId;
          if (t.assignee !== agentId) return fail(`${t.id} is not your task`);
          if (current && t.id !== current) return fail(`you are working on ${current}; you can only update that task. Tell Marlow (send_message to "lead") if ${t.id} is already covered.`);
          if (a.status && !['review', 'blocked', 'doing'].includes(a.status)) return fail('workers can set status review, blocked or doing');
          if (a.assignee || a.title || a.description) return fail('only the lead can change assignee/title/description');
        }
        try {
          if (a.assignee) {
            const id = fm.resolveAgentId(a.assignee);
            if (!id) return fail(`no agent ${a.assignee}`);
            if (fm.isDesk(id)) return fail(`${fm.nameOf(id)} works for ${fm.deskName(id)}, not on the coding team`);
            fm.tasks.update(t.id, { assignee: id });
          }
          if (a.title) fm.tasks.update(t.id, { title: a.title });
          if (a.description) fm.tasks.update(t.id, { description: a.description });
          if (a.summary) fm.tasks.update(t.id, { summary: a.summary });
          if (a.status && a.status !== t.status) {
            const prev = t.status;
            fm.tasks.setStatus(t.id, a.status as TaskStatus, { force: role === 'lead' || a.status === 'review', ...(a.blocked_reason ? { reason: a.blocked_reason } : {}), ...(a.summary ? { summary: a.summary } : {}) });
            fm.bus.feed('task', `${fm.nameOf(agentId)}: ${t.id} ${prev} -> ${a.status}`, { agentId });
            if (a.status === 'review' && role === 'worker') hooks.onReview(agentId, t.id);
            if (a.status === 'doing' && prev === 'review' && role === 'lead') hooks.onChangesRequested(t.id, a.summary ?? 'see review comments');
          }
          hooks.onTasksChanged();
          return withInbox(`Updated ${t.id}: ${fm.tasks.get(t.id)!.status}.`);
        } catch (e) {
          return fail((e as Error).message);
        }
      },
    ),
    tool(
      'report_status',
      'Tell the team what you are doing (shown on your nameplate). One short line.',
      { activity: z.string().max(80), note: z.string().optional().describe('optional longer line for your monitor') },
      async ({ activity, note }) => {
        fm.setAgent(agentId, { activity });
        if (note) fm.agentLog(agentId, 'text', note);
        return withInbox('ok');
      },
    ),
    tool('list_tasks', 'Show the task board (ids, status, assignee, deps).', {}, async () => withInbox(boardSummary(fm))),
  ];

  if (role === 'lead') {
    tools.push(
      tool(
        'create_task',
        'Create a task for a worker. deps = task ids that must be merged first. Returns the new task id.',
        {
          title: z.string(),
          description: z.string().describe('what to do + acceptance criteria'),
          deps: z.array(z.string()).optional(),
          assignee: z.string().optional().describe('worker id/name'),
          priority: z.number().int().optional(),
          start_branch: z.string().optional().describe('only for a fetched pull request: its branch, e.g. "agentcraft/pr-12"; the worker starts from the contributor\'s commits'),
        },
        async ({ title, description, deps, assignee, priority, start_branch }) => {
          const goal = fm.currentGoal();
          if (start_branch && !isPrBranch(start_branch)) return fail(`start_branch must be a fetched pull request branch (agentcraft/pr-<n>), not ${start_branch}`);
          let who: string | undefined;
          if (assignee) {
            who = fm.resolveAgentId(assignee);
            if (!who) return fail(`no worker ${assignee}`);
            if (fm.agent(who)?.role === 'lead') return fail('assign tasks to workers, not yourself');
            if (fm.isDesk(who)) return fail(`${fm.nameOf(who)} works for ${fm.deskName(who)}${fm.isContent(who) ? ' (YouTube channels)' : ' (posts for X and LinkedIn)'}, not on the coding team: assign the task to a coding worker`);
          }
          try {
            const t = fm.tasks.create({
              title,
              description,
              deps: deps ?? [],
              ...(who ? { assignee: who } : {}),
              ...(priority !== undefined ? { priority } : {}),
              ...(start_branch ? { startBranch: start_branch } : {}),
              createdBy: agentId,
              ...(goal ? { goalId: goal.id } : {}),
              ...(goal?.repoId ? { repoId: goal.repoId } : {}),
            });
            fm.bus.feed('task', `Marlow created ${t.id}: ${t.title}`, { agentId });
            hooks.onTasksChanged();
            return withInbox(`Created ${t.id}.`);
          } catch (e) {
            return fail((e as Error).message);
          }
        },
      ),
      tool(
        'request_merge',
        `After reviewing a task in "review": send ${userName()} a merge decision for its branch. ${userName()} decides; you do not wait.`,
        { task_id: z.string(), summary: z.string().describe(`2-4 lines for ${userName()}: what changed, how it was tested, risks`) },
        async ({ task_id, summary }) => {
          const t = fm.tasks.get(task_id);
          if (!t) return fail(`no task ${task_id}`);
          if (t.status !== 'review') return fail(`${t.id} is ${t.status}, not in review`);
          if (!t.worktree || !t.repoId) return fail(`${t.id} has no worktree to merge`);
          const open = fm.decisions.open().find((d) => d.kind === 'merge' && d.taskId === t.id);
          if (open) return withInbox(`Merge decision ${open.id} for ${t.id} is already waiting for ${userName()}.`);
          if (await closeIfNoChanges(fm, t.id)) {
            hooks.onTasksChanged();
            return withInbox(`${t.id} changed no files, so there is nothing to merge: it is closed as done. Tell ${userName()} the result with send_message if you have not yet.`);
          }
          const wt = fm.repos.requireWorktree(t.repoId, t.worktree);
          const d = fm.createDecision({
            agentId,
            kind: 'merge',
            question: `Merge ${t.id} "${t.title}" (${wt.branch}) into ${wt.base}?`,
            options: [...MERGE_OPTIONS],
            context: `${summary}\n${wt.files} files, +${wt.additions} -${wt.deletions} | tests: ${t.ci}`,
            taskId: t.id,
            repoId: t.repoId,
            worktree: wt.id,
          });
          hooks.onMergeRequested(t.id, d);
          return withInbox(`Merge decision ${d.id} sent to ${userName()}.`);
        },
      ),
    );
  }

  // the Social Desk has no task board and no merges: its own calendar tools instead
  if (fm.brain) tools.push(...brainTools(fm, agentId, withInbox, fail));
  else tools.splice(0, tools.length, ...tools.filter((t) => !(BRAIN_TOOL_NAMES as readonly string[]).includes(t.name)));

  if (room !== 'studio') {
    const reply = (text: string, isError = false) => withInbox(text, isError);
    tools.splice(0, tools.length, ...tools.filter((t) => !DESK_HIDDEN.has(t.name)), ...(room === 'content' ? contentTools(fm, agentId, reply, cwd) : socialTools(fm, agentId, reply)));
  }

  // a stopped/aborted turn must not change anything any more, even if its CLI lingers
  for (const t of tools) {
    const inner = t.handler;
    t.handler = async (args) => (turn?.signal.aborted ? fail('your turn was stopped; nothing was changed') : inner(args));
  }
  return tools;
}

/** search_brain / read_brain / brain_inbox over the user's notes vault (read-only but for Inbox.md). */
function brainTools(fm: Foreman, agentId: string, reply: (text: string, isError?: boolean) => ToolResult, fail: (text: string) => ToolResult): AgentTool[] {
  const brain = fm.brain!;
  return [
    tool(
      'search_brain',
      `Search ${userName()}'s second brain: their notes on every project, preferences, decisions and people (shared across all repos). Check it before asking ${userName()} something they may have told Claude before. Returns ranked note names with a matching line; open one with read_brain.`,
      { query: z.string().describe('a few keywords, e.g. "moonbase map" or "stack defaults"'), limit: z.number().int().min(1).max(20).optional() },
      async ({ query, limit }) => {
        const hits = brain.search(query, limit ?? 8);
        if (!hits.length) return reply(`No brain notes match "${query}".`);
        return reply(hits.map((h) => `- [[${h.name}]] (${h.path}): ${h.snippet}`).join('\n'));
      },
    ),
    tool(
      'read_brain',
      `Read one note from ${userName()}'s second brain by name ("Moonbase", "[[How I Like To Work]]") or path ("Projects/Moonbase.md"). Start with "Home" for the map of everything.`,
      { note: z.string() },
      async ({ note }) => {
        const n = brain.read(note);
        if (!n) return fail(`no brain note "${note}" (try search_brain)`);
        return reply(`# ${n.path}\n\n${truncate(n.text, 12_000)}`);
      },
    ),
    tool(
      'brain_inbox',
      `Drop one line into ${userName()}'s brain Inbox for them to file: a fact, decision or preference they told you that future sessions should know. One plain line; never secrets (keys, passwords, tokens) — say where they live instead. Use write_memory for team working notes.`,
      { line: z.string().describe('one line, plain language, ≤500 characters') },
      async ({ line }) => {
        try {
          const entry = brain.inbox(line, agentId, fm.ctx.now());
          fm.bus.feed('memory', `${fm.nameOf(agentId)} added to the brain inbox`, { agentId });
          return reply(`Added to Inbox.md: ${entry}`);
        } catch (e) {
          return fail((e as Error).message);
        }
      },
    ),
  ];
}

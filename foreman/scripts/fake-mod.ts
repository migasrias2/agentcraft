// fake-mod / TUI: connects to the Foreman exactly like the Minecraft mod, keeps a local mirror of
// the state, prints a live readable view, and lets you drive it from stdin:
//
//   <text>                         submit a goal
//   @kit <text>                    message an agent (@all for everyone)
//   /answer [dN] <n|label> [text]  answer a decision (default: oldest open); n is 1-based
//   /diff <worktree|dN>            show a worktree diff (e.g. /diff kit-t2)
//   /repo add <path>               connect a repo
//   /pause|/resume|/stop|/spawn @name
//   /task <id> cancel|retry|prioritize [n]|reassign <agent>
//   /status /agents /tasks /decisions /memory [id] /feed     views
//   /wait decision|goal|merge|<seconds>   (scripts) wait for something
//   /quit
//
// Flags: --port N  --transcript <file>  --script <file>  --commands <file> (tailed)
//        --auto-answer [merge,permission,question]  --auto-delay <s>  --exit-on-goal-done
//        --quiet-logs  --no-color  --timeout <s>  --once (print snapshot and exit)
import fs from 'node:fs';
import readline from 'node:readline';
import WebSocket from 'ws';
import type { Agent, Decision, DiffFile, FeedItem, Goal, MemoryEntry, Repo, ServerMessage, Task } from '../src/protocol.js';
import { parseFlags } from '../src/config.js';

const { flags } = parseFlags(process.argv.slice(2));
const port = Number(flags.port ?? process.env.AGENTCRAFT_PORT ?? 7878);
const url = `ws://127.0.0.1:${port}`;
const useColor = flags.color !== false && !process.env.NO_COLOR;
const quietLogs = flags['quiet-logs'] === true;
const autoKinds = flags['auto-answer'] === true ? ['merge', 'permission', 'question'] : typeof flags['auto-answer'] === 'string' ? String(flags['auto-answer']).split(',') : [];
const autoDelay = Number(flags['auto-delay'] ?? 2) * 1000;
const transcriptPath = typeof flags.transcript === 'string' ? flags.transcript : undefined;
// a fresh file per session (use --append-transcript to keep adding to an existing one)
const transcript = transcriptPath ? fs.createWriteStream(transcriptPath, { flags: flags['append-transcript'] ? 'a' : 'w' }) : undefined;

// ---- output ---------------------------------------------------------------------------------

const C = {
  dim: '\x1b[2m',
  bold: '\x1b[1m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m',
  reset: '\x1b[0m',
};
const paint = (c: keyof typeof C, s: string) => (useColor ? `${C[c]}${s}${C.reset}` : s);
// local time, like the Foreman's own log
const stamp = () => new Date().toTimeString().slice(0, 8);
// eslint-disable-next-line no-control-regex
const strip = (s: string) => s.replace(/\x1b\[[0-9;]*m/g, '');

function out(line: string): void {
  const text = `${paint('dim', stamp())} ${line}`;
  console.log(text);
  transcript?.write(strip(text) + '\n');
}

// ---- mirror state ---------------------------------------------------------------------------

const S = {
  agents: new Map<string, Agent>(),
  tasks: new Map<string, Task>(),
  decisions: new Map<string, Decision>(),
  repos: new Map<string, Repo>(),
  memory: new Map<string, MemoryEntry>(),
  goals: new Map<string, Goal>(),
  feed: [] as FeedItem[],
  status: undefined as undefined | { backend: string; auth: string; message?: string; costUsd?: number; userName?: string },
};
const counts = new Map<string, number>();
const name = (id?: string) => (id ? (id === 'user' ? (S.status?.userName ?? 'you') : (S.agents.get(id)?.name ?? id)) : '?');

function agentLine(a: Agent): string {
  const st = a.state === 'waiting_user' ? paint('yellow', a.state) : a.state === 'error' || a.state === 'blocked' ? paint('red', a.state) : a.state === 'done' ? paint('green', a.state) : paint('cyan', a.state);
  return `${paint('bold', a.name.padEnd(8))} ${st.padEnd(useColor ? 21 : 12)} @${a.station.padEnd(12)} ${a.activity}${a.taskId ? paint('dim', ` [${a.taskId}]`) : ''}${a.paused ? paint('yellow', ' (paused)') : ''}${a.active || a.activity === 'off shift' ? '' : paint('dim', ' (off shift)')}`;
}

function printBoard(): void {
  const cols = ['todo', 'doing', 'review', 'done', 'blocked'] as const;
  for (const c of cols) {
    const ts = [...S.tasks.values()].filter((t) => t.status === c);
    out(`  ${paint('bold', c.toUpperCase().padEnd(8))} ${ts.map((t) => `${t.id} ${t.title}${t.assignee ? ` (${name(t.assignee)})` : ''}${t.ci !== 'unknown' ? ` ci:${t.ci}` : ''}`).join(' | ') || '-'}`);
  }
}

function printDecision(d: Decision): void {
  const kind = d.kind.toUpperCase();
  out(paint('yellow', `+-- DECISION ${d.id} [${kind}] from ${name(d.agentId)}${d.taskId ? ` (task ${d.taskId})` : ''}`));
  out(paint('yellow', `|   ${d.question}`));
  if (d.context) for (const l of d.context.split('\n').slice(0, 6)) out(paint('yellow', `|   ${paint('dim', l)}`));
  d.options.forEach((o, i) => out(paint('yellow', `|   ${i + 1}) ${o}`)));
  out(paint('yellow', `+-- answer: /answer ${d.id} <1-${d.options.length || 1}>${d.kind === 'question' ? ' [free text]' : ''}${d.kind === 'merge' ? `   (review: /diff ${d.worktree})` : ''}`));
}

function printStatus(): void {
  out(paint('bold', `== Foreman ${S.status?.backend ?? '?'} | auth ${S.status?.auth ?? '?'} | ${S.status?.message ?? ''}${S.status?.costUsd !== undefined ? ` | $${S.status.costUsd}` : ''}`));
  const g = [...S.goals.values()].pop();
  if (g) out(`   goal ${g.id} [${g.status}] ${Math.round(g.progress * 100)}% ${g.text}`);
  for (const r of S.repos.values()) out(`   repo ${r.id} ${r.branch}@${r.head ?? '?'} ci:${r.ci}${r.dirty ? ' DIRTY' : ''} worktrees: ${r.worktrees.map((w) => `${w.id}(${w.status} +${w.additions} -${w.deletions})`).join(', ') || '-'}`);
  out('   agents:');
  for (const a of S.agents.values()) out(`     ${agentLine(a)}`);
  out('   board:');
  printBoard();
  const open = [...S.decisions.values()].filter((d) => d.status === 'open');
  out(`   open decisions: ${open.map((d) => `${d.id}(${d.kind})`).join(', ') || 'none'}`);
  out(`   memory: ${[...S.memory.values()].map((m) => m.title).join(' | ') || '-'}`);
}

function printDiff(files: DiffFile[]): void {
  for (const f of files) {
    out(paint('bold', `--- ${f.status} ${f.oldPath ? `${f.oldPath} -> ` : ''}${f.path}  (+${f.additions} -${f.deletions})`));
    for (const h of f.hunks) {
      out(paint('cyan', h.header));
      for (const l of h.lines) {
        const no = String(l.newNo ?? l.oldNo ?? '').padStart(4);
        if (l.kind === 'add') out(paint('green', `${no} + ${l.text}`));
        else if (l.kind === 'del') out(paint('red', `${no} - ${l.text}`));
        else out(paint('dim', `${no}   ${l.text}`));
      }
    }
  }
}

// ---- connection -----------------------------------------------------------------------------

let ws: WebSocket | undefined;
let backoff = 500;
let msgSeq = 0;
const waiters: Array<{ test: (m: ServerMessage) => boolean; resolve: () => void }> = [];
const autoAnswered = new Set<string>();

function send(obj: Record<string, unknown>): void {
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    out(paint('red', 'not connected'));
    return;
  }
  ws.send(JSON.stringify({ v: 1, id: `c${++msgSeq}`, ...obj }));
}

function connect(): void {
  ws = new WebSocket(url);
  ws.on('open', () => {
    backoff = 500;
    out(paint('green', `connected to ${url}`));
    ws!.send(JSON.stringify({ v: 1, type: 'hello', modVersion: 'fake-mod 0.1.0', protocol: 1, client: 'cli' }));
  });
  ws.on('message', (data) => {
    let m: ServerMessage;
    try {
      m = JSON.parse(data.toString()) as ServerMessage;
    } catch {
      return;
    }
    counts.set(m.type, (counts.get(m.type) ?? 0) + 1);
    onMessage(m);
    for (let i = waiters.length - 1; i >= 0; i--) {
      if (waiters[i]!.test(m)) {
        waiters[i]!.resolve();
        waiters.splice(i, 1);
      }
    }
  });
  ws.on('close', () => {
    out(paint('red', `disconnected; reconnecting in ${backoff}ms`));
    setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 8000);
  });
  ws.on('error', () => {
    /* close follows */
  });
}

function onMessage(m: ServerMessage): void {
  switch (m.type) {
    case 'snapshot':
      S.agents.clear();
      S.tasks.clear();
      S.decisions.clear();
      S.repos.clear();
      S.memory.clear();
      S.goals.clear();
      m.agents.forEach((a) => S.agents.set(a.id, a));
      m.tasks.forEach((t) => S.tasks.set(t.id, t));
      m.decisions.forEach((d) => S.decisions.set(d.id, d));
      m.repos.forEach((r) => S.repos.set(r.id, r));
      m.memory.forEach((e) => S.memory.set(e.id, e));
      m.goals.forEach((g) => S.goals.set(g.id, g));
      S.feed = m.feed;
      S.status = m.foreman;
      out(paint('bold', `== snapshot: ${m.agents.length} agents, ${m.tasks.length} tasks, ${m.decisions.filter((d) => d.status === 'open').length} open decisions, ${m.repos.length} repos, ${m.memory.length} memory, ${m.logs.reduce((s, l) => s + l.entries.length, 0)} log lines`));
      printStatus();
      for (const d of m.decisions.filter((x) => x.status === 'open')) {
        printDecision(d);
        maybeAuto(d);
      }
      if (flags.once) process.exit(0);
      break;
    case 'agent.upsert': {
      const prev = S.agents.get(m.agent.id);
      S.agents.set(m.agent.id, m.agent);
      if (!prev || prev.state !== m.agent.state || prev.station !== m.agent.station || prev.activity !== m.agent.activity || prev.paused !== m.agent.paused) out(`${paint('magenta', '[agent]')} ${agentLine(m.agent)}`);
      break;
    }
    case 'media.add':
      out(`${paint('magenta', '[media]')} ${name(m.item.agentId)} ${m.item.kind} ${m.item.name}${m.item.caption ? ` - ${m.item.caption}` : ''} (${m.item.frames.length} frame${m.item.frames.length === 1 ? '' : 's'})`);
      break;
    case 'agent.screen':
      if (!quietLogs) out(`  ${name(m.agentId).padEnd(8)}| ${paint('dim', `screen ${m.mime} ${Math.round(m.data.length / 1024)} KB from ${m.tool}`)}`);
      break;
    case 'agent.log':
      if (quietLogs) break;
      for (const e of m.entries) {
        const lines = e.text.split('\n');
        const kindCol = e.kind === 'error' ? paint('red', e.kind.padEnd(6)) : e.kind === 'diff' ? paint('green', e.kind.padEnd(6)) : e.kind === 'tool' ? paint('blue', e.kind.padEnd(6)) : paint('dim', e.kind.padEnd(6));
        out(`  ${name(m.agentId).padEnd(8)}| ${kindCol} ${lines[0]}`);
        for (const l of lines.slice(1, e.kind === 'diff' ? 14 : 6)) out(`  ${''.padEnd(8)}|        ${e.kind === 'diff' ? (l.startsWith('+') ? paint('green', l) : l.startsWith('-') ? paint('red', l) : paint('dim', l)) : paint('dim', l)}`);
        if (lines.length > (e.kind === 'diff' ? 14 : 6)) out(`  ${''.padEnd(8)}|        ${paint('dim', `... ${lines.length - (e.kind === 'diff' ? 14 : 6)} more`)}`);
      }
      break;
    case 'agent.say':
      out(`${paint('bold', '>>')} ${paint('bold', name(m.agentId))}${m.to ? ` -> ${m.to === 'all' ? 'everyone' : name(m.to)}` : ''}: ${m.text}`);
      break;
    case 'task.upsert': {
      const prev = S.tasks.get(m.task.id);
      S.tasks.set(m.task.id, m.task);
      if (!prev) out(`${paint('blue', '[task+]')} ${m.task.id} ${m.task.status} "${m.task.title}"${m.task.assignee ? ` -> ${name(m.task.assignee)}` : ''}${m.task.deps.length ? ` deps:${m.task.deps.join(',')}` : ''}`);
      else if (prev.status !== m.task.status || prev.ci !== m.task.ci || prev.assignee !== m.task.assignee)
        out(`${paint('blue', '[task]')} ${m.task.id} ${prev.status === m.task.status ? m.task.status : `${prev.status}->${m.task.status}`}${m.task.ci !== prev.ci ? ` ci:${m.task.ci}` : ''}${m.task.assignee !== prev.assignee ? ` assignee:${name(m.task.assignee)}` : ''}${m.task.blockedReason ? ` (${m.task.blockedReason})` : ''}`);
      break;
    }
    case 'decision.upsert': {
      const prev = S.decisions.get(m.decision.id);
      S.decisions.set(m.decision.id, m.decision);
      if (m.decision.status === 'open' && (!prev || prev.status !== 'open' || prev.context !== m.decision.context)) {
        printDecision(m.decision);
        maybeAuto(m.decision);
      } else if (m.decision.status !== 'open' && prev?.status === 'open') {
        out(paint('yellow', `[decision ${m.decision.id} ${m.decision.status}${m.decision.answer ? `: ${[m.decision.answer.option, m.decision.answer.text].filter(Boolean).join(' - ')}` : ''}]`));
      }
      break;
    }
    case 'repo.upsert': {
      const prev = S.repos.get(m.repo.id);
      S.repos.set(m.repo.id, m.repo);
      const sig = (r?: Repo) => (r ? `${r.head}|${r.ci}|${r.dirty}|${r.worktrees.map((w) => `${w.id}:${w.status}:${w.files}:${w.additions}:${w.deletions}`).join(',')}` : '');
      if (sig(prev) !== sig(m.repo)) out(`${paint('cyan', '[repo]')} ${m.repo.id} ${m.repo.branch}@${m.repo.head ?? '?'} ci:${m.repo.ci}${m.repo.dirty ? ' DIRTY' : ''} | ${m.repo.worktrees.map((w) => `${w.id}:${w.status}(${w.files}f +${w.additions} -${w.deletions})`).join(' ') || 'no worktrees'}`);
      break;
    }
    case 'memory.upsert':
      S.memory.set(m.entry.id, m.entry);
      out(`${paint('cyan', '[memory]')} ${m.entry.id} "${m.entry.title}" by ${name(m.entry.author)} (${m.entry.body.length} chars)`);
      break;
    case 'goal.upsert': {
      const prev = S.goals.get(m.goal.id);
      S.goals.set(m.goal.id, m.goal);
      if (!prev || prev.status !== m.goal.status || Math.round(prev.progress * 100) !== Math.round(m.goal.progress * 100)) out(`${paint('green', '[goal]')} ${m.goal.id} ${m.goal.status} ${Math.round(m.goal.progress * 100)}% ${m.goal.text}`);
      if (m.goal.status === 'done' && flags['exit-on-goal-done']) setTimeout(() => finish(0), 1500);
      break;
    }
    case 'feed.add':
      S.feed.push(m.item);
      if (['goal', 'plan', 'merge', 'ci', 'error', 'system', 'user'].includes(m.item.kind)) out(`${paint(m.item.kind === 'error' ? 'red' : 'dim', `[feed:${m.item.kind}]`)} ${m.item.text}`);
      break;
    case 'diff':
      if (m.error) out(paint('red', `diff error: ${m.error}`));
      else {
        out(paint('bold', `== diff ${m.repoId}/${m.worktree} ${m.branch} vs ${m.base}: ${m.stats.files} files +${m.stats.additions} -${m.stats.deletions}${m.truncated ? ' (truncated)' : ''}`));
        printDiff(m.files);
      }
      break;
    case 'notify':
      out(paint(m.level === 'need_user' ? 'yellow' : m.level === 'warn' ? 'red' : 'green', `[notify:${m.level}] ${m.text}`));
      break;
    case 'foreman.status':
      S.status = m.status;
      out(paint('bold', `[status] backend ${m.status.backend} auth ${m.status.auth}${m.status.account ? ` (${m.status.account})` : ''} - ${m.status.message ?? ''}${m.status.costUsd !== undefined ? ` - $${m.status.costUsd}` : ''}`));
      break;
    case 'ack':
      if (!m.ok) out(paint('red', `[ack ${m.re}] FAILED: ${m.error}`));
      else if (m.result && Object.keys(m.result).length) out(paint('dim', `[ack ${m.re}] ok ${JSON.stringify(m.result)}`));
      break;
    case 'error':
      out(paint('red', `[error] ${m.message}`));
      break;
  }
}

function maybeAuto(d: Decision): void {
  if (!autoKinds.includes(d.kind) || autoAnswered.has(d.id)) return;
  autoAnswered.add(d.id);
  setTimeout(() => {
    if (S.decisions.get(d.id)?.status !== 'open') return;
    out(paint('yellow', `(auto-answer) /answer ${d.id} 1  -> ${d.options[0] ?? '(text)'}`));
    send({ type: 'decision.answer', decisionId: d.id, ...(d.options.length ? { option: d.options[0] } : { text: 'Use your best judgement.' }) });
  }, autoDelay);
}

// ---- commands -------------------------------------------------------------------------------

function waitFor(test: (m: ServerMessage) => boolean, timeoutMs = 30 * 60_000): Promise<void> {
  return new Promise((resolve) => {
    const t = setTimeout(resolve, timeoutMs);
    waiters.push({
      test,
      resolve: () => {
        clearTimeout(t);
        resolve();
      },
    });
  });
}

async function command(line: string): Promise<void> {
  const text = line.trim();
  if (!text || text.startsWith('#')) return;
  transcript?.write(`${stamp()} > ${text}\n`);
  if (!text.startsWith('/')) {
    if (text.startsWith('@')) send({ type: 'user.message', to: 'all', text });
    else send({ type: 'goal.submit', text });
    return;
  }
  const [cmd, ...args] = text.slice(1).split(/\s+/);
  const rest = text.slice(1 + (cmd?.length ?? 0)).trim();
  switch (cmd) {
    case 'answer':
    case 'a': {
      let id = args[0] && /^d\d+$/.test(args[0]) ? args.shift()! : undefined;
      const open = [...S.decisions.values()].filter((d) => d.status === 'open').sort((a, b) => a.createdAt - b.createdAt);
      const d = id ? S.decisions.get(id) : open[0];
      if (!d) return out(paint('red', 'no open decision'));
      id = d.id;
      const first = args[0];
      const n = first && /^\d+$/.test(first) ? Number(first) : undefined;
      if (n !== undefined && n >= 1 && n <= d.options.length) {
        const textArg = args.slice(1).join(' ');
        send({ type: 'decision.answer', decisionId: id, option: d.options[n - 1], ...(textArg ? { text: textArg } : {}) });
      } else {
        const all = args.join(' ');
        const match = d.options.find((o) => o.toLowerCase().startsWith(all.toLowerCase()));
        if (match && all) send({ type: 'decision.answer', decisionId: id, option: match });
        else send({ type: 'decision.answer', decisionId: id, text: all });
      }
      return;
    }
    case 'diff': {
      const target = args[0];
      if (!target) return out('usage: /diff <worktree|dN>');
      let repoId = [...S.repos.values()][0]?.id;
      let wt = target;
      const d = /^d\d+$/.test(target) ? S.decisions.get(target) : undefined;
      if (d) {
        repoId = d.repoId ?? repoId;
        wt = d.worktree ?? target;
      } else {
        for (const r of S.repos.values()) if (r.worktrees.some((w) => w.id === target)) repoId = r.id;
      }
      if (!repoId) return out(paint('red', 'no repo'));
      send({ type: 'diff.request', requestId: `r${++msgSeq}`, repoId, worktree: wt });
      return;
    }
    case 'repo':
      if (args[0] === 'add' && args[1]) send({ type: 'repo.add', path: rest.replace(/^add\s+/, '') });
      else out('usage: /repo add <path>');
      return;
    case 'pause':
    case 'resume':
    case 'stop':
    case 'spawn':
      if (!args[0]) return out(`usage: /${cmd} @name`);
      send({ type: 'agent.action', agentId: args[0].replace(/^@/, ''), action: cmd, ...(args[1] ? { arg: args[1] } : {}) });
      return;
    case 'task':
      if (!args[0] || !args[1]) return out('usage: /task <id> cancel|retry|prioritize [n]|reassign <agent>');
      send({ type: 'task.action', taskId: args[0], action: args[1], ...(args[2] ? { arg: args[2].replace(/^@/, '') } : {}) });
      return;
    case 'status':
      printStatus();
      return;
    case 'agents':
      for (const a of S.agents.values()) out(agentLine(a));
      return;
    case 'tasks':
      printBoard();
      return;
    case 'decisions':
      for (const d of S.decisions.values()) if (d.status === 'open') printDecision(d);
      return;
    case 'memory': {
      if (args[0]) {
        const e = S.memory.get(args[0]) ?? [...S.memory.values()].find((x) => x.id.endsWith(args[0]!));
        if (!e) return out('no such memory');
        out(paint('bold', `# ${e.title} (${e.id})`));
        for (const l of e.body.split('\n')) out(`  ${l}`);
      } else for (const e of S.memory.values()) out(`${e.id}  "${e.title}"  by ${name(e.author)}`);
      return;
    }
    case 'feed':
      for (const f of S.feed.slice(-30)) out(`[${f.kind}] ${f.text}`);
      return;
    case 'counts':
      out(JSON.stringify(Object.fromEntries(counts)));
      return;
    case 'wait': {
      const what = args[0] ?? '1';
      if (/^\d+(\.\d+)?$/.test(what)) {
        await new Promise((r) => setTimeout(r, Number(what) * 1000));
        return;
      }
      out(paint('dim', `(waiting for ${what}...)`));
      if (/^d\d+$/.test(what)) {
        if (S.decisions.get(what)?.status === 'open') return;
        await waitFor((m) => m.type === 'decision.upsert' && m.decision.id === what && m.decision.status === 'open');
      } else if (/^t\d+$/.test(what)) {
        const st = args[1] ?? 'done';
        if (S.tasks.get(what)?.status === st) return;
        await waitFor((m) => m.type === 'task.upsert' && m.task.id === what && m.task.status === st);
      } else if (what === 'decision') {
        const kind = args[1];
        if ([...S.decisions.values()].some((d) => d.status === 'open' && (!kind || d.kind === kind))) return;
        await waitFor((m) => m.type === 'decision.upsert' && m.decision.status === 'open' && (!kind || m.decision.kind === kind));
      } else if (what === 'goal') {
        const st = args[1] ?? 'done';
        if ([...S.goals.values()].some((g) => g.status === st)) return;
        await waitFor((m) => m.type === 'goal.upsert' && m.goal.status === st);
      } else if (what === 'merge') await waitFor((m) => m.type === 'feed.add' && m.item.kind === 'merge');
      else if (what === 'snapshot') await waitFor((m) => m.type === 'snapshot');
      else if (what === 'diff') await waitFor((m) => m.type === 'diff');
      return;
    }
    case 'quit':
    case 'exit':
      finish(0);
      return;
    default:
      out(paint('red', `unknown command /${cmd}`));
  }
}

function finish(code: number): void {
  out(paint('dim', `message counts: ${JSON.stringify(Object.fromEntries(counts))}`));
  transcript?.end();
  setTimeout(() => process.exit(code), 200);
}

// ---- main -----------------------------------------------------------------------------------

out(paint('dim', `fake-mod: connecting to ${url}${transcriptPath ? `, transcript -> ${transcriptPath}` : ''}`));
connect();

let queue: Promise<void> = Promise.resolve();
const enqueueCmd = (l: string) => {
  queue = queue.then(() => command(l)).catch((e) => out(paint('red', String(e))));
};

if (typeof flags.script === 'string') {
  const lines = fs.readFileSync(flags.script, 'utf8').split(/\r?\n/);
  // give the snapshot a moment before running the script
  void waitFor((m) => m.type === 'snapshot', 30_000).then(() => lines.forEach(enqueueCmd));
}

if (typeof flags.commands === 'string') {
  const file = flags.commands;
  let pos = fs.existsSync(file) ? fs.statSync(file).size : 0;
  let buf = '';
  setInterval(() => {
    if (!fs.existsSync(file)) return;
    const size = fs.statSync(file).size;
    if (size < pos) pos = 0;
    if (size === pos) return;
    const fd = fs.openSync(file, 'r');
    const b = Buffer.alloc(size - pos);
    fs.readSync(fd, b, 0, b.length, pos);
    fs.closeSync(fd);
    pos = size;
    buf += b.toString('utf8');
    const parts = buf.split(/\r?\n/);
    buf = parts.pop() ?? '';
    parts.forEach(enqueueCmd);
  }, 300);
}

if (!flags.script && process.stdin.readable) {
  const rl = readline.createInterface({ input: process.stdin, terminal: false });
  rl.on('line', enqueueCmd);
}

if (flags.timeout) setTimeout(() => finish(0), Number(flags.timeout) * 1000);

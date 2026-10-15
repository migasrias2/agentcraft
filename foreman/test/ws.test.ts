import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { SimBackend } from '../src/agents/sim/index.js';
import { BEATS } from '../src/agents/sim/scenario.js';
import { silentLogger } from '../src/context.js';
import { ServerMessage, type ServerMessage as SM } from '../src/protocol.js';
import { ForemanServer } from '../src/server.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

interface Client {
  ws: WebSocket;
  msgs: SM[];
  invalid: string[];
  send(o: Record<string, unknown>): void;
  close(): void;
}

function connect(port: number, origin?: string): Promise<Client> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`, origin ? { origin } : {});
    const c: Client = {
      ws,
      msgs: [],
      invalid: [],
      send: (o) => ws.send(JSON.stringify({ v: 1, ...o })),
      close: () => ws.close(),
    };
    ws.on('message', (d) => {
      const raw = JSON.parse(d.toString());
      const r = ServerMessage.safeParse(raw);
      if (r.success) c.msgs.push(r.data);
      else c.invalid.push(`${raw.type}: ${r.error.issues.map((i) => `${i.path.join('.')} ${i.message}`).join('; ')}`);
    });
    ws.once('open', () => resolve(c));
    ws.once('error', reject);
    ws.once('unexpected-response', (_req, res) => reject(new Error(`HTTP ${res.statusCode}`)));
  });
}

async function boot(home: string, repoPath: string, extra: string[] = []) {
  const h = makeForeman(home, ['--backend', 'sim', '--repo', repoPath, '--speed', '1000', '--no-ambient', ...extra]);
  const sim = new SimBackend(h.fm, h.cfg.sim);
  const server = new ForemanServer(h.fm, { host: '127.0.0.1', port: 0, validateOutbound: true, log: silentLogger });
  const port = await server.start();
  await h.fm.start(sim);
  return { h, sim, server, port };
}

const cleanup: string[] = [];
afterAll(() => {
  for (const d of cleanup) rmrf(d);
});

describe('WebSocket server + sim backend', () => {
  it('serves a snapshot, runs the whole scenario, answers diff requests and restores after restart', async () => {
    const home = tempDir();
    const repoPath = await demoRepo();
    cleanup.push(home, path.dirname(repoPath));
    let { h, sim, server, port } = await boot(home, repoPath, ['--auto-answer']);

    const a = await connect(port);
    a.send({ type: 'hello', modVersion: 'test', protocol: 1, client: 'test' });
    await until(() => a.msgs.some((m) => m.type === 'snapshot'));
    const snap = a.msgs.find((m) => m.type === 'snapshot')!;
    expect(snap.type === 'snapshot' && snap.agents.map((x) => x.id).sort()).toEqual(['cleo', 'iris', 'juniper', 'kit', 'marlow', 'nova', 'reel', 'rowan', 'sable', 'tove', 'vega', 'wren']);

    // invalid message -> error, with ack when it had an id
    a.ws.send(JSON.stringify({ v: 1, id: 'bad1', type: 'goal.submit' }));
    await until(() => a.msgs.some((m) => m.type === 'ack' && m.re === 'bad1' && !m.ok));

    a.send({ type: 'goal.submit', id: 'g', text: 'Add #tags to pocket-notes' });
    await until(() => a.msgs.some((m) => m.type === 'ack' && m.re === 'g'));
    const ack = a.msgs.find((m) => m.type === 'ack' && m.re === 'g')!;
    expect(ack.type === 'ack' && ack.ok).toBe(true);

    // a second client joining mid-run gets its own snapshot and the broadcasts
    await until(() => a.msgs.some((m) => m.type === 'decision.upsert'), 60_000);
    const b = await connect(port);
    b.send({ type: 'hello', modVersion: 'test', protocol: 1, client: 'cli' });
    await until(() => b.msgs.some((m) => m.type === 'snapshot'));

    // @routing to an agent
    a.send({ type: 'user.message', id: 'm', to: 'all', text: '@Kit nice regex' });
    await until(() => a.msgs.some((m) => m.type === 'ack' && m.re === 'm'));
    const mack = a.msgs.find((m) => m.type === 'ack' && m.re === 'm')!;
    expect(mack.type === 'ack' && mack.result).toEqual({ to: 'kit' });

    await until(() => {
      const err = a.msgs.find((m) => m.type === 'feed.add' && m.item.kind === 'error' && m.item.text.startsWith('Sim scenario error'));
      if (err && err.type === 'feed.add') throw new Error(`sim failed: ${err.item.text}`);
      return a.msgs.some((m) => m.type === 'goal.upsert' && m.goal.status === 'done');
    }, 150_000);
    await sim.idle();

    expect(a.invalid).toEqual([]);
    expect(b.invalid).toEqual([]);
    const types = new Set(a.msgs.map((m) => m.type));
    for (const t of ['snapshot', 'agent.upsert', 'agent.log', 'agent.say', 'task.upsert', 'decision.upsert', 'repo.upsert', 'memory.upsert', 'goal.upsert', 'feed.add', 'notify', 'foreman.status', 'ack', 'error']) {
      expect(types.has(t as SM['type']), `missing ${t}`).toBe(true);
    }
    expect(b.msgs.some((m) => m.type === 'agent.log')).toBe(true);

    // every scenario element showed up
    const decisions = a.msgs.filter((m) => m.type === 'decision.upsert').map((m) => (m.type === 'decision.upsert' ? m.decision : undefined)!);
    const kinds = new Set(decisions.map((d) => d.kind));
    expect([...kinds].sort()).toEqual(['merge', 'permission', 'question']);
    const tasks = h.fm.tasks.list();
    expect(tasks.length).toBe(9);
    expect(tasks.filter((t) => t.status === 'done').length).toBe(7);
    // t7 (npm publish) was blocked until the user closed it at the end; t9 was parked
    const taskStatuses = new Set(a.msgs.filter((m) => m.type === 'task.upsert').map((m) => (m.type === 'task.upsert' ? m.task.status : '')));
    for (const s of ['todo', 'doing', 'review', 'done', 'blocked', 'cancelled']) expect(taskStatuses.has(s as never), s).toBe(true);
    expect(tasks.filter((t) => t.status === 'cancelled').map((t) => t.id).sort()).toEqual(['t7', 't9']);
    // the goal completes through the task graph (no forced status): 100%, and it stays done
    expect(h.fm.goal(h.fm.currentGoal()!.id)!.progress).toBe(1);
    const ci = a.msgs.filter((m) => m.type === 'task.upsert').map((m) => (m.type === 'task.upsert' ? m.task.ci : 'unknown'));
    expect(ci).toContain('fail');
    expect(ci).toContain('pass');
    // every agent state the mod renders appears in a sim run
    const states = new Set(a.msgs.filter((m) => m.type === 'agent.upsert').map((m) => (m.type === 'agent.upsert' ? m.agent.state : '')));
    for (const s of ['idle', 'thinking', 'reading', 'editing', 'running', 'testing', 'waiting_user', 'blocked', 'done', 'error']) expect(states.has(s as never), s).toBe(true);
    const stations = new Set(a.msgs.filter((m) => m.type === 'agent.upsert').map((m) => (m.type === 'agent.upsert' ? m.agent.station : '')));
    for (const s of ['desk', 'library', 'terminal', 'testbench', 'mergestation', 'meeting', 'lounge', 'user']) expect(stations.has(s as never), s).toBe(true);
    expect(a.msgs.some((m) => m.type === 'agent.log' && m.entries.some((e) => e.kind === 'diff'))).toBe(true);
    expect(a.msgs.some((m) => m.type === 'agent.log' && m.entries.some((e) => e.kind === 'error'))).toBe(true);
    expect(a.msgs.filter((m) => m.type === 'feed.add' && m.item.kind === 'merge').length).toBe(5);

    // diff for a merged worktree is still reviewable
    a.send({ type: 'diff.request', requestId: 'r1', repoId: 'demo-app', worktree: 'kit-t2' });
    await until(() => a.msgs.some((m) => m.type === 'diff' && m.requestId === 'r1'));
    const diff = a.msgs.find((m) => m.type === 'diff' && m.requestId === 'r1')!;
    expect(diff.type === 'diff' && diff.files.map((f) => f.path).sort()).toEqual(['src/tags.ts', 'test/tags.test.ts']);
    expect(b.msgs.some((m) => m.type === 'diff')).toBe(false); // only the requester gets it

    // browser origins are refused, including `null` (sandboxed iframes, data: URLs, file:// pages)
    await expect(connect(port, 'https://evil.example')).rejects.toThrow(/HTTP 401/);
    await expect(connect(port, 'null')).rejects.toThrow(/HTTP 401/);
    await expect(connect(port, 'file://')).rejects.toThrow(/HTTP 401/);
    // DNS rebinding: a page reaching 127.0.0.1 under its own host name
    await expect(
      new Promise((resolve, reject) => {
        const ws = new WebSocket(`ws://127.0.0.1:${port}`, { headers: { Host: `evil.example:${port}` } });
        ws.once('open', () => resolve('open'));
        ws.once('unexpected-response', (_req, res) => reject(new Error(`HTTP ${res.statusCode}`)));
        ws.once('error', reject);
      }),
    ).rejects.toThrow(/HTTP 401/);
    const local = await connect(port); // no Origin (the mod, the CLI): accepted
    local.close();

    // restart: a new Foreman on the same state dir restores everything
    a.close();
    b.close();
    await server.stop();
    await h.fm.close();
    ({ h, sim, server, port } = await boot(home, repoPath));
    const c = await connect(port);
    c.send({ type: 'hello', modVersion: 'test', protocol: 1 });
    await until(() => c.msgs.some((m) => m.type === 'snapshot'));
    const s2 = c.msgs.find((m) => m.type === 'snapshot')!;
    if (s2.type !== 'snapshot') throw new Error('no snapshot');
    expect(s2.tasks.length).toBe(9);
    expect(s2.goal?.status).toBe('done');
    expect(s2.foreman.message).toMatch(/ - goal done$/); // the banner survives the restart too
    expect(s2.memory.map((m) => m.id)).toContain('shared/plan');
    expect(s2.repos[0]!.worktrees.filter((w) => w.status === 'merged').length).toBe(5);
    expect(s2.logs.find((l) => l.agentId === 'kit')!.entries.length).toBeGreaterThan(5);
    c.close();
    await server.stop();
    await h.fm.close();
  }, 240_000);

  it('resumes a scenario that was interrupted while waiting on the user', async () => {
    const home = tempDir();
    const repoPath = await demoRepo();
    cleanup.push(home, path.dirname(repoPath));
    let { h, sim, server, port } = await boot(home, repoPath);
    await sim.autostart();
    await until(() => h.fm.decisions.open().some((d) => d.kind === 'permission'), 60_000);
    const p1 = h.fm.decisions.open().find((d) => d.kind === 'permission')!;
    const beatBefore = sim.state.beat;
    // "kill" the Foreman while Wren waits for permission
    await server.stop();
    await h.fm.close();

    ({ h, sim, server, port } = await boot(home, repoPath));
    expect(sim.state.beat).toBeGreaterThanOrEqual(beatBefore);
    const c = await connect(port);
    c.send({ type: 'hello', modVersion: 'test', protocol: 1 });
    await until(() => c.msgs.some((m) => m.type === 'snapshot'));
    const snap = c.msgs.find((m) => m.type === 'snapshot');
    expect(snap?.type === 'snapshot' && snap.decisions.some((d) => d.id === p1.id && d.status === 'open')).toBe(true);
    c.send({ type: 'decision.answer', id: 'ans', decisionId: p1.id, option: 'Deny' });
    await until(() => c.msgs.some((m) => m.type === 'ack' && m.re === 'ans'));
    // the team continues: Kit's branch reaches review and a merge decision opens
    await until(() => h.fm.decisions.open().some((d) => d.kind === 'merge'), 60_000);
    expect(h.fm.memory.get('shared/decisions')!.body).toContain('install denied');
    expect(BEATS[sim.state.beat]).toBeDefined();
    c.close();
    await server.stop();
    await h.fm.close();
  }, 180_000);
});

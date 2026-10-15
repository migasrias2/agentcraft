// The two static showcase states for screenshot QA: `--showcase` (busy mid-run) and
// `--showcase late` (blocked, error, done, running agents). Each fast-forwards through the real
// scenario (real worktrees, tests, merges), holds still, and holds again after a restart.
import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { SimBackend } from '../src/agents/sim/index.js';
import type { Agent } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir } from './helpers.js';

const cleanup: string[] = [];
afterAll(() => cleanup.forEach(rmrf));

async function hold(home: string, repo: string, variant: string[]) {
  const h = makeForeman(home, ['--backend', 'sim', '--repo', repo, '--no-ambient', '--showcase', ...variant]);
  const sim = new SimBackend(h.fm, h.cfg.sim);
  await h.fm.start(sim);
  await sim.autostart();
  await sim.idle();
  return { h, sim };
}

// the desk agents (teams 'social' and 'content') sit outside the sim's coding scenario
const states = (agents: Agent[]) => Object.fromEntries(agents.filter((a) => !a.team || a.team === 'studio').map((a) => [a.id, `${a.state}@${a.station}`]));

describe('sim showcase states', () => {
  it('--showcase holds the busy mid-run state with open decisions', async () => {
    const home = tempDir();
    const repo = await demoRepo();
    cleanup.push(home, path.dirname(repo));
    let { h } = await hold(home, repo, []);
    expect(h.fm.status.showcase).toBe(true);
    const s = states(h.fm.agents());
    expect(s).toEqual({
      marlow: 'waiting_user@user',
      juniper: 'editing@desk',
      kit: 'testing@testbench',
      wren: 'idle@lounge',
      rowan: 'reading@library',
      tove: 'thinking@desk',
    });
    expect(h.fm.decisions.open().map((d) => d.kind).sort()).toEqual(['merge', 'question']);
    const wall = h.fm.tasks.list().map((t) => t.status);
    for (const st of ['todo', 'doing', 'review', 'done', 'blocked']) expect(wall).toContain(st);
    await h.fm.close();
    // a restart holds the same state immediately
    ({ h } = await hold(home, repo, []));
    expect(states(h.fm.agents())).toEqual(s);
    await h.fm.close();
  }, 120_000);

  it('--showcase late holds blocked, error, done and running agents', async () => {
    const home = tempDir();
    const repo = await demoRepo();
    cleanup.push(home, path.dirname(repo));
    const { h } = await hold(home, repo, ['late']);
    expect(h.fm.status.showcase).toBe(true);
    expect(states(h.fm.agents())).toEqual({
      marlow: 'thinking@meeting',
      juniper: 'running@terminal',
      kit: 'done@lounge',
      wren: 'blocked@desk',
      rowan: 'error@library',
      tove: 'editing@desk',
    });
    const t7 = h.fm.tasks.list().find((t) => t.title.startsWith('Publish'))!;
    expect(t7.status).toBe('blocked');
    expect(t7.assignee).toBe('wren');
    // four branches merged for real on the way here
    expect(h.fm.repos.list()[0]!.worktrees.filter((w) => w.status === 'merged').length).toBe(4);
    await h.fm.close();
  }, 120_000);
});

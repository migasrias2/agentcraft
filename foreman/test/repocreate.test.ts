// repo.create: a brand-new repo from the game (console `/repo new <name>`).
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { SimBackend } from '../src/agents/sim/index.js';
import type { ClientMessage, Outbound } from '../src/protocol.js';
import { git } from '../src/util/git.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const cleanup: Array<() => void | Promise<void>> = [];
afterEach(async () => {
  for (const f of cleanup.splice(0).reverse()) await f();
});

function start(): { h: Harness; dir: string } {
  const home = tempDir();
  const dir = tempDir('ac-newrepos-');
  const h = makeForeman(home, ['--backend', 'sim', '--new-repo-dir', dir, '--speed', '1000', '--no-ambient']);
  cleanup.push(() => rmrf(home), () => rmrf(dir), () => h.fm.close());
  return { h, dir };
}
async function send(h: Harness, msg: Record<string, unknown>): Promise<Extract<Outbound, { type: 'ack' }>> {
  const replies: Outbound[] = [];
  await h.fm.handle({ v: 1, id: 'x', ...msg } as ClientMessage, (m) => replies.push(m));
  return replies.find((m) => m.type === 'ack') as Extract<Outbound, { type: 'ack' }>;
}

describe('repo.create', () => {
  it('makes a git repo with a first commit on main and registers it', async () => {
    const { h, dir } = start();
    const a = await send(h, { type: 'repo.create', name: 'pixel-garden' });
    expect(a).toMatchObject({ ok: true, result: { repoId: 'pixel-garden', path: path.join(fs.realpathSync(dir), 'pixel-garden') } });
    const repo = h.fm.repos.get('pixel-garden')!;
    expect(repo.branch).toBe('main');
    expect(fs.readFileSync(path.join(repo.path, 'README.md'), 'utf8')).toMatch(/# pixel-garden/);
    expect((await git(repo.path, ['log', '--oneline'])).stdout).toMatch(/start pixel-garden/);
    expect(h.events.some((m) => m.type === 'repo.upsert' && m.repo.id === 'pixel-garden')).toBe(true);
  });

  it('can turn auto-approve on and start a goal in the new repo', async () => {
    const { h } = start();
    await h.fm.start(new SimBackend(h.fm, h.cfg.sim));
    const a = await send(h, { type: 'repo.create', name: 'auto-game', autoApprove: true, goal: 'make a tiny game' });
    expect(a.ok).toBe(true);
    expect(h.fm.autoApprove).toBe(true);
    expect(h.fm.goals().at(-1)).toMatchObject({ repoId: 'auto-game' });
  });

  it('allows a sub folder, refuses bad names and non-empty folders', async () => {
    const { h, dir } = start();
    expect((await send(h, { type: 'repo.create', name: 'games/tiny' })).ok).toBe(true);
    expect(fs.existsSync(path.join(dir, 'games', 'tiny', '.git'))).toBe(true);
    for (const bad of ['../escape', 'a/../../b', 'semi;colon', '/abs/path']) {
      const a = await send(h, { type: 'repo.create', name: bad });
      expect(a.ok, bad).toBe(false);
      expect(a.error).toMatch(/bad repo name/);
    }
    fs.mkdirSync(path.join(dir, 'taken'));
    fs.writeFileSync(path.join(dir, 'taken', 'x.txt'), 'hi');
    const taken = await send(h, { type: 'repo.create', name: 'taken' });
    expect(taken.error).toMatch(/already exists and is not empty/);
  });
});

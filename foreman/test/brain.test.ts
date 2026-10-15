import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { agentTools, type ToolResult } from '../src/agents/tools.js';
import { Brain, BrainError } from '../src/brain.js';
import { makeForeman, rmrf, tempDir } from './helpers.js';

function vault(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ac-brain-'));
  fs.mkdirSync(path.join(dir, 'Projects'));
  fs.mkdirSync(path.join(dir, 'Me'));
  fs.mkdirSync(path.join(dir, '.obsidian'));
  fs.writeFileSync(path.join(dir, 'Home.md'), '# Home\n\n- [[Moonbase]] — Roblox shooter\n');
  fs.writeFileSync(path.join(dir, 'Projects', 'Moonbase.md'), '---\ntype: project\n---\n# Moonbase\n\nTarkov-style extraction shooter. Main city is Tokyo.\n');
  fs.writeFileSync(path.join(dir, 'Me', 'How I Like To Work.md'), '# How I Like To Work\n\nPlain language. Short answers.\n');
  fs.writeFileSync(path.join(dir, '.obsidian', 'secret.md'), 'tokyo hidden config\n');
  return dir;
}

describe('brain (Obsidian vault)', () => {
  let dir: string;
  let brain: Brain;
  beforeEach(() => {
    dir = vault();
    brain = new Brain(dir);
  });

  it('refuses a folder that does not exist', () => {
    expect(() => new Brain(path.join(dir, 'nope'))).toThrow(BrainError);
  });

  it('searches note names and text, best match first, skipping dot folders', () => {
    const hits = brain.search('moonbase tokyo');
    expect(hits[0]?.path).toBe('Projects/Moonbase.md');
    expect(hits[0]?.snippet).toMatch(/Tokyo/);
    expect(hits.map((h) => h.path)).toContain('Home.md');
    expect(hits.some((h) => h.path.startsWith('.obsidian'))).toBe(false);
    expect(brain.search('zzzz')).toEqual([]);
    expect(brain.search('   ')).toEqual([]);
  });

  it('reads a note by wikilink name, path, or name with .md', () => {
    expect(brain.read('Moonbase')?.path).toBe('Projects/Moonbase.md');
    expect(brain.read('[[how i like to work]]')?.text).toMatch(/Plain language/);
    expect(brain.read('Me/How I Like To Work.md')?.path).toBe('Me/How I Like To Work.md');
    expect(brain.read('Missing')).toBeUndefined();
  });

  it('never reads outside the vault or into dot folders', () => {
    fs.writeFileSync(path.join(path.dirname(dir), 'outside.md'), 'nope');
    expect(brain.read('../outside.md')).toBeUndefined();
    expect(brain.read('/etc/hosts')).toBeUndefined();
    expect(brain.read('.obsidian/secret.md')).toBeUndefined();
  });

  it('appends dated, attributed lines to Inbox.md', () => {
    const now = new Date(2026, 9, 13, 12).getTime();
    brain.inbox('Moonbase needs a night map', 'kit', now);
    brain.inbox('second line', 'nova', now);
    const text = fs.readFileSync(path.join(dir, 'Inbox.md'), 'utf8');
    expect(text).toBe('- 2026-10-13 (agent kit): Moonbase needs a night map\n- 2026-10-13 (agent nova): second line\n');
  });

  it('refuses empty, multi-line, huge or secret-looking inbox lines', () => {
    expect(() => brain.inbox('  ', 'kit')).toThrow(/empty/);
    expect(() => brain.inbox('a\nb', 'kit')).toThrow(/one line/);
    expect(() => brain.inbox('x'.repeat(501), 'kit')).toThrow(/500/);
    expect(() => brain.inbox('the key is sk-ant-abc123def456ghi789', 'kit')).toThrow(/secret/);
    expect(() => brain.inbox('password: hunter2', 'kit')).toThrow(/secret/);
    expect(() => brain.inbox('token a8f9e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0', 'kit')).toThrow(/secret/);
    expect(fs.existsSync(path.join(dir, 'Inbox.md'))).toBe(false);
  });
});

describe('brain tools for agents', () => {
  const hooks = { onReview() {}, onChangesRequested() {}, onTasksChanged() {}, onMergeRequested() {}, onWaiting() {} };
  const cleanup: Array<() => unknown> = [];
  afterEach(async () => {
    for (const f of cleanup.splice(0).reverse()) await f();
  });
  const text = (r: ToolResult) => r.content.map((c) => c.text).join('');

  it('are only offered with --brain, and search, read and file inbox lines', async () => {
    const home = tempDir();
    const dir = vault();
    const off = makeForeman(home, ['--backend', 'sim', '--profile', 'off']);
    cleanup.push(() => off.fm.close());
    expect(agentTools(off.fm, 'kit', 'worker', hooks).some((t) => t.name === 'search_brain')).toBe(false);

    const on = makeForeman(home, ['--backend', 'sim', '--profile', 'on', '--brain', dir]);
    cleanup.push(() => on.fm.close(), () => rmrf(home), () => rmrf(dir));
    const tools = agentTools(on.fm, 'kit', 'worker', hooks);
    const call = (name: string, args: object) => tools.find((t) => t.name === name)!.handler(args);

    expect(text(await call('search_brain', { query: 'tokyo' }))).toMatch(/\[\[Moonbase\]\] \(Projects\/Moonbase\.md\)/);
    expect(text(await call('read_brain', { note: 'Moonbase' }))).toMatch(/extraction shooter/);
    expect((await call('read_brain', { note: '../../etc/passwd' })).isError).toBe(true);
    expect(text(await call('brain_inbox', { line: 'prefers night maps' }))).toMatch(/Added to Inbox\.md/);
    expect(fs.readFileSync(path.join(dir, 'Inbox.md'), 'utf8')).toMatch(/\(agent kit\): prefers night maps/);
    const secret = await call('brain_inbox', { line: 'password: hunter2' });
    expect(secret.isError).toBe(true);
  });

  it('a missing brain folder turns the brain off instead of crashing', async () => {
    const home = tempDir();
    const h = makeForeman(home, ['--backend', 'sim', '--brain', path.join(home, 'nope')]);
    cleanup.push(() => h.fm.close(), () => rmrf(home));
    expect(h.fm.brain).toBeUndefined();
  });
});

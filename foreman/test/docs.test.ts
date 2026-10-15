// hand_over: documents agents give the user to read in game (doc.upsert / doc.read).
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { agentTools } from '../src/agents/tools.js';
import { DOC_KEEP, docTitle, pruneDocs } from '../src/docs.js';
import type { ClientMessage, Doc, Outbound } from '../src/protocol.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const cleanup: Array<() => void | Promise<void>> = [];
afterEach(async () => {
  for (const f of cleanup.splice(0).reverse()) await f();
});

function start(home = tempDir()): Harness {
  const h = makeForeman(home, ['--backend', 'sim']);
  cleanup.push(() => rmrf(home), () => h.fm.close());
  return h;
}
function scratch(): string {
  const d = tempDir('ac-docs-');
  cleanup.push(() => rmrf(d));
  return d;
}
const hooks = { onReview() {}, onChangesRequested() {}, onTasksChanged() {}, onMergeRequested() {}, onWaiting() {} };

describe('docs helpers', () => {
  it('titles from the argument, the first heading, else the file name', () => {
    expect(docTitle('# Ignored', 'a.md', ' Given ')).toBe('Given');
    expect(docTitle('intro\n\n## Findings ##\n', 'a.md')).toBe('Findings');
    expect(docTitle('no heading', 'release-notes.md')).toBe('release-notes');
  });

  it('prunes read documents before unread ones', () => {
    const mk = (i: number, read: boolean): Doc => ({ id: `doc${i}`, agentId: 'kit', title: 't', name: 'n.md', path: '/x', markdown: 'm', read, at: i });
    const docs = [mk(0, false), ...Array.from({ length: DOC_KEEP }, (_, i) => mk(i + 1, i % 2 === 0))];
    const kept = pruneDocs(docs);
    expect(kept).toHaveLength(DOC_KEEP);
    expect(kept[0]!.id).toBe('doc0');
  });
});

describe('hand_over', () => {
  it('the tool hands over a file relative to the agent cwd, broadcasts and persists it', async () => {
    const home = tempDir();
    const h = start(home);
    const dir = scratch();
    fs.mkdirSync(path.join(dir, 'docs'));
    fs.writeFileSync(path.join(dir, 'docs', 'report.md'), '# Auth audit\n\nAll good.\n');
    const tool = agentTools(h.fm, 'kit', 'worker', hooks, undefined, dir).find((t) => t.name === 'hand_over')!;
    const res = await tool.handler({ path: 'docs/report.md', note: 'Here you go' });
    expect(res.isError).toBeUndefined();
    const doc = h.fm.docs()[0]!;
    expect(doc).toMatchObject({ agentId: 'kit', title: 'Auth audit', name: 'report.md', note: 'Here you go', read: false });
    expect(h.events.some((m) => m.type === 'doc.upsert' && m.doc.id === doc.id)).toBe(true);
    expect(h.fm.snapshot()).toMatchObject({ docs: [{ id: doc.id }] });
    await h.fm.close();
    cleanup.splice(cleanup.findIndex((f) => f.toString().includes('fm.close')), 1);
    const again = start(home);
    expect(again.fm.docs().map((d) => d.id)).toEqual([doc.id]);
  });

  it('refuses images and missing files with a clear error', async () => {
    const h = start();
    const dir = scratch();
    fs.writeFileSync(path.join(dir, 'shot.png'), 'x');
    const tool = agentTools(h.fm, 'kit', 'worker', hooks, undefined, dir).find((t) => t.name === 'hand_over')!;
    expect((await tool.handler({ path: 'shot.png' })).content[0]!.text).toMatch(/show_media/);
    expect((await tool.handler({ path: 'nope.md' })).content[0]!.text).toMatch(/no file/);
  });

  it('doc.read marks it read once; unknown ids fail', async () => {
    const h = start();
    const file = path.join(scratch(), 'plan.md');
    fs.writeFileSync(file, 'plan');
    const doc = h.fm.handOver('wren', file);
    const send = async (msg: Record<string, unknown>) => {
      const out: Outbound[] = [];
      await h.fm.handle({ v: 1, id: 'x', ...msg } as ClientMessage, (m) => out.push(m));
      return out.find((m) => m.type === 'ack') as Extract<Outbound, { type: 'ack' }>;
    };
    expect(await send({ type: 'doc.read', docId: doc.id })).toMatchObject({ ok: true });
    expect(h.fm.docs()[0]!.read).toBe(true);
    expect((await send({ type: 'doc.read', docId: 'doc99' })).error).toMatch(/no document/);
  });
});

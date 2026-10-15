// show_media / media.add / media.open and the auto-approve toggle (settings.set).
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { MediaHub, VIDEO_MAX_FRAMES } from '../src/media.js';
import { PERMISSION_OPTIONS, type ClientMessage, type Media, type Outbound } from '../src/protocol.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const PNG_1x1 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';
const hasFfmpeg = (() => {
  try {
    execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
})();

const cleanup: Array<() => void | Promise<void>> = [];
afterEach(async () => {
  for (const f of cleanup.splice(0).reverse()) await f();
});

function scratch(): string {
  const d = tempDir('ac-media-');
  cleanup.push(() => rmrf(d));
  return d;
}

function hub(): { hub: MediaHub; emitted: Media[] } {
  const emitted: Media[] = [];
  const h = new MediaHub((m) => emitted.push(m));
  cleanup.push(() => h.close());
  return { hub: h, emitted };
}

describe('MediaHub', () => {
  it('sends a small PNG as is and keeps the file for media.open', async () => {
    const dir = scratch();
    const file = path.join(dir, 'shot.png');
    fs.writeFileSync(file, Buffer.from(PNG_1x1, 'base64'));
    const { hub: h, emitted } = hub();
    const item = await h.show('kit', file, '  the login page ');
    expect(item).toMatchObject({ kind: 'image', agentId: 'kit', name: 'shot.png', mime: 'image/png', caption: 'the login page', frames: [PNG_1x1] });
    expect(emitted).toEqual([item]);
    expect(h.fileOf(item.id)).toBe(file);
    expect(h.recent()).toEqual([item]);
  });

  it('refuses files that are not images or videos, and missing files', async () => {
    const dir = scratch();
    const file = path.join(dir, 'notes.txt');
    fs.writeFileSync(file, 'hi');
    const { hub: h, emitted } = hub();
    await expect(h.show('kit', file)).rejects.toThrow(/not an image or video/);
    await expect(h.show('kit', path.join(dir, 'nope.png'))).rejects.toThrow(/no file/);
    expect(() => h.open('m99')).toThrow(/no media/);
    expect(emitted).toEqual([]);
  });

  it.skipIf(!hasFfmpeg)('samples a video to a bounded flipbook of JPEG frames', async () => {
    const dir = scratch();
    const file = path.join(dir, 'clip.mp4');
    execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=duration=3:size=320x240:rate=25', '-pix_fmt', 'yuv420p', file]);
    const { hub: h } = hub();
    const item = await h.show('wren', file);
    expect(item.kind).toBe('video');
    expect(item.mime).toBe('image/jpeg');
    expect(item.frames.length).toBeGreaterThan(10);
    expect(item.frames.length).toBeLessThanOrEqual(VIDEO_MAX_FRAMES);
    expect(item.fps).toBeGreaterThan(0);
    expect(item.durationMs).toBeGreaterThan(2500);
    expect(item.hasAudio).toBe(false);
    expect(Buffer.from(item.frames[0]!, 'base64').subarray(0, 2)).toEqual(Buffer.from([0xff, 0xd8]));
  });

  it.skipIf(!hasFfmpeg)('re-encodes other image formats (gif) to JPEG', async () => {
    const dir = scratch();
    const file = path.join(dir, 'gen.gif');
    execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=size=64x64', '-frames:v', '1', file]);
    const { hub: h } = hub();
    const item = await h.show('wren', file);
    expect(item).toMatchObject({ kind: 'image', mime: 'image/jpeg' });
    expect(item.frames).toHaveLength(1);
  });

  it('keeps only the newest items for the snapshot', async () => {
    const dir = scratch();
    const { hub: h } = hub();
    for (let i = 0; i < 6; i++) {
      const f = path.join(dir, `s${i}.png`);
      fs.writeFileSync(f, Buffer.from(PNG_1x1, 'base64'));
      await h.show('kit', f);
    }
    expect(h.recent().map((m) => m.name)).toEqual(['s2.png', 's3.png', 's4.png', 's5.png']);
  });
});

describe('Foreman media and auto-approve', () => {
  let h: Harness;
  function start(args: string[] = [], home = scratch()): Harness {
    h = makeForeman(home, ['--backend', 'sim', ...args]);
    cleanup.push(() => h.fm.close());
    return h;
  }
  async function send(msg: Record<string, unknown>): Promise<Outbound[]> {
    const replies: Outbound[] = [];
    await h.fm.handle({ v: 1, id: 'x', ...msg } as ClientMessage, (m) => replies.push(m));
    return replies;
  }
  const ack = (r: Outbound[]) => r.find((m) => m.type === 'ack') as Extract<Outbound, { type: 'ack' }>;

  it('showMedia broadcasts media.add, lands in the feed and the snapshot', async () => {
    start();
    const file = path.join(scratch(), 'render.png');
    fs.writeFileSync(file, Buffer.from(PNG_1x1, 'base64'));
    await h.fm.showMedia('kit', file, 'hero render');
    expect(h.events.some((m) => m.type === 'media.add' && m.item.name === 'render.png')).toBe(true);
    expect(h.fm.store.data.feed.at(-1)!.text).toMatch(/Screening Room.*hero render/);
    const snap = h.fm.snapshot();
    expect(snap.type === 'snapshot' && snap.media?.map((m) => m.name)).toEqual(['render.png']);
  });

  it('media.open of an unknown id fails with a clear error', async () => {
    start();
    const r = await send({ type: 'media.open', mediaId: 'm42' });
    expect(ack(r)).toMatchObject({ ok: false });
    expect(ack(r).error).toMatch(/no media "m42"/);
  });

  it('settings.set toggles auto-approve, broadcasts the status and remembers it', async () => {
    const home = scratch();
    start([], home);
    expect(h.fm.snapshot()).toMatchObject({ foreman: { autoApprove: false } });
    const r = await send({ type: 'settings.set', autoApprove: true });
    expect(ack(r)).toMatchObject({ ok: true, result: { autoApprove: true } });
    expect(h.fm.autoApprove).toBe(true);
    expect(h.events.some((m) => m.type === 'foreman.status' && m.status.autoApprove === true)).toBe(true);
    await h.fm.close();
    cleanup.pop();
    start([], home);
    expect(h.fm.autoApprove).toBe(true);
    await send({ type: 'settings.set', autoApprove: false });
    expect(h.fm.autoApprove).toBe(false);
  });

  it('--auto-approve starts with it on', () => {
    start(['--auto-approve']);
    expect(h.fm.autoApprove).toBe(true);
  });

  it('turning it on allows the permission prompts already waiting', async () => {
    start();
    const d = h.fm.createDecision({ agentId: 'kit', kind: 'permission', tool: 'Bash', question: 'Kit wants to run curl example.com', options: [...PERMISSION_OPTIONS] });
    const q = h.fm.createDecision({ agentId: 'kit', kind: 'question', question: 'Which colour?', options: ['red', 'blue'] });
    await send({ type: 'settings.set', autoApprove: true });
    const byId = (id: string) => h.fm.store.data.decisions.find((x) => x.id === id)!;
    expect(byId(d.id).status).toBe('answered');
    expect(byId(d.id).answer?.option).toBe(PERMISSION_OPTIONS[0]);
    expect(byId(q.id).status).toBe('open');
  });
});

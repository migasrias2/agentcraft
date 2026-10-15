// Content Room: Iris pitches, the user approves, Reel produces, the user approves the cut once with a
// time, Cleo uploads it at that time, Vega reads the numbers. State machine, scheduler, analytics sync,
// references and the real turn path (fake `query()`), all with fake yt-dlp / YouTube collaborators.
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { INTERRUPTED, mergeStat, nextSlot, targetQueue, normalizeChannelUrl, parseVideoId, STATS_HISTORY, validateTags, validateTitle, weeklySlots } from '../src/content.js';
import { parseWhen } from '../src/social.js';
import type { ContentStat, ContentVideo } from '../src/protocol.js';
import { parseServerMessage } from '../src/protocol.js';
import type { Backend } from '../src/foreman.js';
import { tempDir, until } from './helpers.js';
import { addChannel, cut, DAY, fakes, HOUR, pitch, send, startContent, T0, text, toReview, toolsOf } from './contenthelpers.js';

const cleanup: Array<() => void | Promise<void>> = [];
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'], now: T0 });
});
afterEach(async () => {
  vi.useRealTimers();
  for (const f of cleanup.splice(0).reverse()) await f();
});
const start = (...a: [ReturnType<typeof fakes>?, string[]?, string?]) => startContent(cleanup, ...a);
const upserts = (h: ReturnType<typeof start>, id: string) => h.events.filter((m) => m.type === 'content.video.upsert' && m.video.id === id).map((m) => (m as { video: ContentVideo }).video.status);

describe('cast and roster', () => {
  it('iris, reel, vega and cleo are the content team, on shift at their desks; the snapshot validates', () => {
    const h = start();
    for (const id of ['iris', 'reel', 'vega', 'cleo']) expect(h.fm.agent(id), id).toMatchObject({ team: 'content', role: 'worker', active: true, station: 'desk' });
    expect(h.fm.agent('iris')).toMatchObject({ title: 'Showrunner', color: '#E4572E' });
    expect(h.fm.agent('nova')!.team).toBe('social');
    expect(h.fm.isDesk('reel') && h.fm.isDesk('nova') && !h.fm.isDesk('kit')).toBe(true);
    const snap = h.fm.snapshot();
    expect(parseServerMessage({ v: 1, ...snap }).ok).toBe(true);
    expect(snap.type === 'snapshot' && snap.contentChannels).toBeUndefined(); // omitted when empty
  });
});

describe('validation and pure helpers', () => {
  it('title, tags, channel links, video ids', () => {
    expect(validateTitle('  A   title ')).toBe('A title');
    expect(() => validateTitle('x'.repeat(101))).toThrow(/101 characters.*100/);
    expect(validateTitle('x'.repeat(100))).toHaveLength(100);
    expect(() => validateTitle('a <b>')).toThrow(/"<"/);
    expect(() => validateTitle('  ')).toThrow(/empty/);
    expect(validateTags(['#AI', 'ai', ' robots ', ''])).toEqual(['AI', 'robots']);
    expect(() => validateTags(['a,b'])).toThrow(/comma/);
    expect(() => validateTags(Array.from({ length: 80 }, (_, i) => `tag-number-${i}`))).toThrow(/allows 500/);
    expect(normalizeChannelUrl('@Tiny.Robots')).toEqual({ url: 'https://www.youtube.com/@Tiny.Robots' });
    expect(normalizeChannelUrl('youtube.com/@tiny/videos').url).toBe('https://www.youtube.com/@tiny');
    expect(normalizeChannelUrl('https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv')).toEqual({ url: 'https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv', youtubeId: 'UCabcdefghijklmnopqrstuv' });
    expect(() => normalizeChannelUrl('https://vimeo.com/x')).toThrow(/youtube\.com/);
    expect(parseVideoId('https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3')).toBe('dQw4w9WgXcQ');
    expect(parseVideoId('https://youtu.be/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(parseVideoId('https://www.youtube.com/shorts/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(parseVideoId('https://studio.youtube.com/video/dQw4w9WgXcQ/edit')).toBe('dQw4w9WgXcQ');
    expect(parseVideoId('dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
    expect(parseVideoId('https://example.com/nope')).toBeUndefined();
  });

  it('weekly slots spread over the week; the next slot is 18:00 Lisbon, DST aware, and skips taken ones', () => {
    expect(weeklySlots(3)).toEqual([{ wd: 0, minute: 18 * 60 }, { wd: 2, minute: 18 * 60 }, { wd: 4, minute: 18 * 60 }]);
    expect(weeklySlots(7).map((s) => s.wd)).toEqual([0, 1, 2, 3, 4, 5, 6]);
    expect(weeklySlots(14).filter((s) => s.wd === 0).map((s) => s.minute / 60)).toEqual([9, 22]);
    // five a day: 09:00 12:00 15:00 18:00 21:00 every day; 70 a week: ten a day an hour apart
    expect(weeklySlots(35).filter((s) => s.wd === 3).map((s) => s.minute / 60)).toEqual([9, 12, 15, 18, 21]);
    expect(weeklySlots(70).filter((s) => s.wd === 6).map((s) => s.minute / 60)).toEqual([9, 10, 11, 12, 13, 14, 15, 16, 17, 18]);
    expect(weeklySlots(70)).toHaveLength(70);
    expect(weeklySlots(10).map((s) => s.wd)).toEqual([0, 0, 1, 2, 2, 3, 4, 4, 5, 6]); // 10 a week: not all equal, none lost
    expect(targetQueue(3)).toBe(3);
    expect(targetQueue(35)).toBe(10);
    expect(targetQueue(70)).toBe(20);
    const wed = parseWhen('2026-10-14T18:00');
    expect(nextSlot(3, T0)).toBe(wed);
    expect(nextSlot(3, T0, [wed])).toBe(parseWhen('2026-10-16T18:00'));
    expect(nextSlot(3, T0, [wed, parseWhen('2026-10-16T18:00')])).toBe(parseWhen('2026-10-19T18:00'));
    // after the clocks went back (25 Oct) the same wall-clock slot is one hour later in UTC
    expect(nextSlot(1, parseWhen('2026-10-26T20:00'))).toBe(Date.UTC(2026, 10, 2, 18, 0));
    expect(nextSlot(1, T0)).toBe(parseWhen('2026-10-19T18:00'));
  });

  it('mergeStat keeps a bounded history for own videos and only the latest for references', () => {
    let own: ContentStat[] = [];
    for (let i = 0; i < 40; i++) own = mergeStat(own, { videoId: 'v', at: i * HOUR, views: i }, true);
    expect(own).toHaveLength(STATS_HISTORY);
    expect(own[0]!.views).toBe(10);
    expect(mergeStat(own, { videoId: 'v', at: 39 * HOUR + 60_000, views: 99 }, true).at(-1)!.views).toBe(99); // < 10 min later replaces
    let ref: ContentStat[] = [];
    for (let i = 0; i < 5; i++) ref = mergeStat(ref, { videoId: 'r', at: i * HOUR, views: i }, false);
    expect(ref).toEqual([{ videoId: 'r', at: 4 * HOUR, views: 4 }]);
  });
});

describe('channels (content.channel.action)', () => {
  it('add / update / autopilot / remove, with upserts, a playbook file, validation and a wake for Iris', async () => {
    const h = start();
    const ch = await addChannel(h);
    expect(ch).toBe('ch1');
    const c = h.fm.content.channel(ch)!;
    expect(c).toMatchObject({ name: 'Tiny Robots', url: 'https://www.youtube.com/@tinyrobots', autopilot: false, postsPerWeek: 3, connected: false });
    expect(c.playbook).toMatch(/Tiny Robots — playbook/);
    expect(fs.existsSync(path.join(h.cfg.dataDir, 'content', 'channels', ch, 'playbook.md'))).toBe(true);
    expect(h.rec.wakes).toEqual([{ id: 'iris', kind: 'content', prompt: expect.stringContaining('just added the channel "Tiny Robots"') }]);
    expect(h.events.some((m) => m.type === 'content.channel.upsert' && m.channel.id === ch && !!m.channel.playbook)).toBe(true);

    expect((await send(h, { type: 'content.channel.action', action: 'add' })).error).toMatch(/needs a name/);
    expect((await send(h, { type: 'content.channel.action', action: 'add', name: 'X', postsPerWeek: 71 })).error).toMatch(/1 to 70/);
    expect((await send(h, { type: 'content.channel.action', action: 'add', name: 'X', postsPerWeek: 0 })).error).toMatch(/1 to 70/);
    expect((await send(h, { type: 'content.channel.action', action: 'add', name: 'X', url: 'https://vimeo.com/a' })).error).toMatch(/youtube\.com/);
    expect((await send(h, { type: 'content.channel.action', action: 'add', name: 'X', brief: 'b'.repeat(2001) })).error).toMatch(/limit is 2000/);
    expect(h.fm.content.channels()).toHaveLength(1);

    await send(h, { type: 'content.channel.action', action: 'update', channelId: ch, postsPerWeek: 5, brief: 'Warm and curious', niche: 'tiny robots' });
    expect(h.fm.content.channel(ch)).toMatchObject({ postsPerWeek: 5, brief: 'Warm and curious', niche: 'tiny robots', url: 'https://www.youtube.com/@tinyrobots' });
    expect((await send(h, { type: 'content.channel.action', action: 'update', channelId: 'ch9' })).error).toMatch(/no channel/);
    expect((await send(h, { type: 'content.channel.action', action: 'update' })).error).toMatch(/needs a channelId/);

    await send(h, { type: 'content.channel.action', action: 'autopilot_on', channelId: ch });
    expect(h.fm.content.channel(ch)!.autopilot).toBe(true);
    await send(h, { type: 'content.channel.action', action: 'autopilot_off', channelId: ch });
    expect(h.fm.content.channel(ch)!.autopilot).toBe(false);

    const snap = h.fm.snapshot();
    expect(snap.type === 'snapshot' && snap.contentChannels).toHaveLength(1);
    expect(parseServerMessage({ v: 1, ...snap }).ok).toBe(true);

    const id = await pitch(h, ch);
    expect(await send(h, { type: 'content.channel.action', action: 'remove', channelId: ch })).toMatchObject({ ok: true });
    expect(h.fm.content.channels()).toEqual([]);
    expect(h.fm.content.video(id)).toBeUndefined();
  });

  it('connected follows the OAuth tokens of the YouTube channel id', async () => {
    const f = fakes();
    const h = start(f);
    const ch = await addChannel(h, { url: 'https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv' });
    expect(h.fm.content.channel(ch)!.connected).toBe(false);
    f.oauth.connected.push('UCabcdefghijklmnopqrstuv');
    expect(h.fm.content.channel(ch)!.connected).toBe(true);
  });
});

describe('the pipeline: idea -> approved -> producing -> review -> scheduled -> publishing -> published', () => {
  it('walks the whole state machine with the user gates, wakes and upserts', async () => {
    const h = start();
    const ch = await addChannel(h);
    h.rec.wakes.length = 0;
    const id = await pitch(h, ch, { title: 'Why robots never fall', refIds: [] });
    expect(h.fm.content.video(id)).toMatchObject({ status: 'idea', authorId: 'iris', channelId: ch, format: 'short' });
    // an idea is the user's to approve: Reel cannot start it, Iris cannot approve it on a manual channel
    expect(() => h.fm.content.startProduction(id)).toThrow(/only an approved idea.*not approved/);
    expect(text(await toolsOf(h, 'iris').approve_idea!.handler({ id }))).toMatch(/not on autopilot/);

    expect(await send(h, { type: 'content.video.action', videoId: id, action: 'approve' })).toMatchObject({ ok: true, result: { videoId: id, status: 'approved' } });
    expect(h.rec.wakes).toEqual([{ id: 'reel', kind: 'content', prompt: expect.stringContaining('APPROVED the idea') }]);
    expect(h.rec.wakes[0]!.prompt).toContain('read_playbook');
    expect(h.rec.wakes[0]!.prompt).toMatch(/READ the contact sheets|study_video/);

    // cannot approve a cut yet; the cut needs a file
    expect((await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + DAY })).error).toMatch(/only a cut in review/);
    h.fm.content.startProduction(id);
    expect(() => h.fm.content.attachCut(id, '/nope/missing.mp4')).toThrow(/no file for the cut/);
    const file = cut(h, 'v1.mp4');
    fs.writeFileSync(path.join(h.fm.content.dir, 'cuts', 'v1.png'), 'png');
    h.fm.content.attachCut(id, 'cuts/v1.mp4', 'cuts/v1.png');
    expect(h.fm.content.video(id)).toMatchObject({ status: 'review', filePath: path.join(h.fm.content.dir, 'cuts', id, 'v1.mp4'), thumbnailPath: path.join(h.fm.content.dir, 'cuts', id, 'v1.png') });
    expect(fs.readFileSync(h.fm.content.video(id)!.filePath!, 'utf8')).toBe(fs.readFileSync(file, 'utf8'));
    expect(h.events.some((m) => m.type === 'notify' && m.level === 'info' && /ready for your review/.test(m.text))).toBe(true);

    // the approval: once, with a time that must be in the future
    expect((await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 - 1 })).error).toMatch(/in the past/);
    const when = T0 + 5 * HOUR;
    expect(await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: when })).toMatchObject({ ok: true, result: { status: 'scheduled' } });
    expect(h.fm.content.video(id)!.scheduledAt).toBe(when);

    h.fm.content.tick();
    expect(h.rec.published).toHaveLength(0); // not yet
    vi.setSystemTime(when + 1000);
    h.fm.content.tick();
    expect(h.rec.published).toEqual([{ id, channel: ch }]);
    expect(h.fm.content.video(id)!.status).toBe('publishing');
    h.fm.content.tick();
    expect(h.rec.published).toHaveLength(1); // never twice

    expect(() => h.fm.content.markPublished(id, 'https://example.com/x')).toThrow(/not a youtube\.com/);
    expect(() => h.fm.content.markPublished(id, 'https://www.youtube.com/feed/subscriptions')).toThrow(/cannot find a YouTube video id/);
    const live = h.fm.content.markPublished(id, 'https://www.youtube.com/shorts/dQw4w9WgXcQ');
    expect(live).toMatchObject({ status: 'published', url: 'https://www.youtube.com/shorts/dQw4w9WgXcQ', youtubeVideoId: 'dQw4w9WgXcQ', publishedAt: when + 1000 });
    h.rec.resolvers[0]!({ ok: true });
    await Promise.resolve();
    expect(h.fm.content.video(id)!.status).toBe('published');
    expect(upserts(h, id)).toEqual(['idea', 'approved', 'producing', 'review', 'scheduled', 'publishing', 'published']);
    expect(h.events.some((m) => m.type === 'notify' && /Published on YouTube/.test(m.text))).toBe(true);
    for (const action of ['approve', 'approve_cut', 'unschedule', 'retry', 'publish_now']) {
      expect((await send(h, { type: 'content.video.action', videoId: id, action, at: T0 + 9 * DAY })).ok, action).toBe(false);
    }
    expect((await send(h, { type: 'content.video.action', videoId: 'v99', action: 'approve' })).error).toMatch(/no video/);
  });

  it('approve_cut without a time takes the next free slot of the cadence, 18:00 Lisbon, never two on one slot', async () => {
    const h = start();
    const ch = await addChannel(h);
    const a = await toReview(h, ch, { title: 'A' });
    const b = await toReview(h, ch, { title: 'B' });
    await send(h, { type: 'content.video.action', videoId: a, action: 'approve_cut' });
    await send(h, { type: 'content.video.action', videoId: b, action: 'approve_cut' });
    expect(h.fm.content.video(a)!.scheduledAt).toBe(parseWhen('2026-10-14T18:00'));
    expect(h.fm.content.video(b)!.scheduledAt).toBe(parseWhen('2026-10-16T18:00'));
  });

  it('unschedule, schedule, publish_now and retry', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    const act = (action: string, extra: Record<string, unknown> = {}) => send(h, { type: 'content.video.action', videoId: id, action, ...extra });
    expect((await act('schedule', { at: T0 + HOUR })).error).toMatch(/only a scheduled video/);
    await act('approve_cut', { at: T0 + 2 * HOUR });
    expect((await act('schedule')).error).toMatch(/needs a time/);
    expect(await act('schedule', { at: T0 + 8 * HOUR })).toMatchObject({ ok: true, result: { status: 'scheduled' } });
    expect(h.fm.content.video(id)!.scheduledAt).toBe(T0 + 8 * HOUR);
    expect(await act('unschedule')).toMatchObject({ ok: true, result: { status: 'review' } });
    expect(h.fm.content.video(id)!.scheduledAt).toBeUndefined();
    expect((await act('unschedule')).error).toMatch(/not scheduled/);

    expect(await act('publish_now')).toMatchObject({ ok: true, result: { status: 'publishing' } });
    expect(h.rec.published).toHaveLength(1);
    h.fm.content.markFailed(id, 'not logged in');
    expect(h.fm.content.video(id)).toMatchObject({ status: 'failed', error: 'not logged in' });
    expect(h.events.some((m) => m.type === 'notify' && m.level === 'warn' && /did not go out/.test(m.text))).toBe(true);
    h.rec.resolvers[0]!({ ok: true }); // the turn ends after it reported
    await Promise.resolve();
    expect(h.fm.content.video(id)!.status).toBe('failed');
    expect((await act('retry')).result).toMatchObject({ status: 'publishing' });
    expect(h.rec.published).toHaveLength(2);
    expect((await act('retry')).error).toMatch(/not failed/);
  });

  it('a publish turn that ends without a report fails the video; an unavailable publisher keeps it scheduled', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + HOUR });
    h.rec.off.add('cleo');
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();
    expect(h.fm.content.video(id)!.status).toBe('scheduled');
    h.rec.off.delete('cleo');
    h.fm.content.tick();
    expect(h.fm.content.video(id)!.status).toBe('publishing');
    h.rec.resolvers[0]!({ ok: false, error: 'the publish turn timed out' });
    await vi.waitFor(() => expect(h.fm.content.video(id)!.status).toBe('failed'));
    expect(h.fm.content.video(id)!.error).toBe('the publish turn timed out');
  });

  it('the cut file vanishing before its time fails the video instead of publishing nothing', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + HOUR });
    fs.rmSync(h.fm.content.video(id)!.filePath!);
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();
    expect(h.fm.content.video(id)).toMatchObject({ status: 'failed', error: expect.stringMatching(/cut file is gone/) });
    expect(h.rec.published).toHaveLength(0);
  });

  it('reject (idea | approved | review) keeps the reason and wakes Iris; others cannot be rejected', async () => {
    const h = start();
    const ch = await addChannel(h);
    h.rec.wakes.length = 0;
    const idea = await pitch(h, ch);
    const rej = await send(h, { type: 'content.video.action', videoId: idea, action: 'reject', text: 'too generic' });
    expect(rej).toMatchObject({ ok: true, result: { status: 'rejected' } });
    expect(h.fm.content.video(idea)!.comments.at(-1)).toMatchObject({ by: 'user', text: 'Rejected: too generic' });
    expect(h.rec.wakes).toEqual([{ id: 'iris', kind: 'content', prompt: expect.stringContaining('REJECTED') }]);
    expect(h.rec.wakes[0]!.prompt).toContain('too generic');
    const review = await toReview(h, ch);
    expect((await send(h, { type: 'content.video.action', videoId: review, action: 'reject' })).ok).toBe(true);
    const prod = await pitch(h, ch);
    await send(h, { type: 'content.video.action', videoId: prod, action: 'approve' });
    h.fm.content.startProduction(prod);
    expect((await send(h, { type: 'content.video.action', videoId: prod, action: 'reject' })).error).toMatch(/only ideas/);
  });

  it('comments wake the agent that owns the video, whatever its status; agents commenting wake nobody', async () => {
    const h = start();
    const ch = await addChannel(h);
    const comment = async (id: string, t = 'make it punchier') => send(h, { type: 'content.video.action', videoId: id, action: 'comment', text: t });
    const idea = await pitch(h, ch);
    h.rec.wakes.length = 0;
    await comment(idea);
    expect(h.rec.wakes.map((w) => w.id)).toEqual(['iris']);
    expect(h.rec.wakes[0]!.prompt).toContain('make it punchier');
    await send(h, { type: 'content.video.action', videoId: idea, action: 'approve' });
    h.fm.content.startProduction(idea);
    h.rec.wakes.length = 0;
    await comment(idea, 'the intro is slow');
    expect(h.rec.wakes.map((w) => w.id)).toEqual(['reel']);
    h.fm.content.attachCut(idea, cut(h));
    await comment(idea, 'looks good');
    expect(h.rec.wakes.map((w) => w.id)).toEqual(['reel', 'reel']);
    await send(h, { type: 'content.video.action', videoId: idea, action: 'approve_cut', at: T0 + HOUR });
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();
    h.fm.content.markPublished(idea, 'https://youtu.be/dQw4w9WgXcQ');
    h.rec.wakes.length = 0;
    await comment(idea, 'why did it flop?');
    expect(h.rec.wakes.map((w) => w.id)).toEqual(['vega']);
    expect(h.fm.content.video(idea)!.comments.map((c) => c.text)).toEqual(['make it punchier', 'the intro is slow', 'looks good', 'why did it flop?']);

    h.rec.wakes.length = 0;
    await toolsOf(h, 'reel').comment_video!.handler({ id: idea, text: 'noted' });
    expect(h.rec.wakes).toEqual([]);
    expect((await comment(idea, '')).error).toMatch(/needs text/);
    expect((await comment(idea, 'x'.repeat(2001))).error).toMatch(/limit is 2000/);
    // an off-shift owner: the comment is kept, nobody is woken, nothing breaks
    h.rec.off.add('vega');
    expect((await comment(idea, 'still there?')).ok).toBe(true);
    expect(h.rec.wakes).toEqual([]);
    expect(h.fm.content.video(idea)!.comments.at(-1)!.text).toBe('still there?');
  });

  it('keeps the last 50 comments and prunes finished videos beyond 200', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await pitch(h, ch);
    for (let i = 0; i < 55; i++) h.fm.content.comment(id, `c${i}`, 'user');
    const v = h.fm.content.video(id)!;
    expect(v.comments).toHaveLength(50);
    expect(v.comments[0]!.text).toBe('c5');
    const open = await pitch(h, ch);
    for (let i = 0; i < 205; i++) {
      const r = await pitch(h, ch, { title: `t${i}` });
      await send(h, { type: 'content.video.action', videoId: r, action: 'reject' });
      vi.setSystemTime(T0 + (i + 1) * 1000);
    }
    const videos = h.fm.content.videos();
    expect(videos.filter((x) => x.status === 'rejected')).toHaveLength(200);
    expect(videos.some((x) => x.id === open)).toBe(true);
  }, 30_000);
});

describe('updating a video', () => {
  it('validates title / description / tags and refuses once it is scheduled or live', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    const reel = toolsOf(h, 'reel');
    expect(text(await reel.update_video!.handler({ id, title: 'x'.repeat(101) }))).toMatch(/101 characters/);
    expect(text(await reel.update_video!.handler({ id, description: 'd'.repeat(5001) }))).toMatch(/allows 5000/);
    expect(text(await reel.update_video!.handler({ id, tags: Array.from({ length: 80 }, (_, i) => `tag-number-${i}`) }))).toMatch(/allows 500/);
    expect((await reel.update_video!.handler({ id, title: 'x'.repeat(100), description: 'd'.repeat(5000), tags: ['a', 'b'], script: 'Hello' })).isError).toBeUndefined();
    expect(h.fm.content.video(id)).toMatchObject({ title: 'x'.repeat(100), tags: ['a', 'b'], script: 'Hello', status: 'review' });
    expect(text(await reel.update_video!.handler({ id, refIds: ['r9'] }))).toMatch(/no reference/);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + HOUR });
    expect(text(await reel.update_video!.handler({ id, title: 'new' }))).toMatch(/scheduled: you can change it only while/);
    expect(() => h.fm.content.update(id, { title: 'new' }, 'reel')).toThrow(/unschedule it first/);
  });
});

describe('autopilot', () => {
  it('ideas are self-approved by Iris, cuts are scheduled at the next free slots, and Iris is woken when fewer than 3 are upcoming', async () => {
    const h = start();
    const ch = await addChannel(h);
    await send(h, { type: 'content.channel.action', action: 'autopilot_on', channelId: ch });
    expect(h.rec.wakes.at(-1)).toMatchObject({ id: 'iris', kind: 'content', prompt: expect.stringContaining('AUTOPILOT') });
    expect(h.rec.wakes.at(-1)!.prompt).toMatch(/0 upcoming videos, the target is 3/);

    const id = await pitch(h, ch);
    expect(text(await toolsOf(h, 'iris').approve_idea!.handler({ id }))).toMatch(/Approved/);
    expect(h.fm.content.video(id)!.status).toBe('approved');
    expect(h.rec.wakes.at(-1)!.id).toBe('reel');
    h.fm.content.startProduction(id);
    const r = await toolsOf(h, 'reel').attach_cut!.handler({ id, filePath: cut(h, 'a.mp4') });
    expect(text(r)).toMatch(/Autopilot scheduled/);
    expect(h.fm.content.video(id)).toMatchObject({ status: 'scheduled', scheduledAt: parseWhen('2026-10-14T18:00') });
    expect(h.events.some((m) => m.type === 'notify' && /ready for your review/.test(m.text))).toBe(false);

    // top-up: throttled, and only below the target
    const wakes = () => h.rec.wakes.filter((w) => /AUTOPILOT/.test(w.prompt)).length;
    const before = wakes();
    h.fm.content.tick();
    expect(wakes()).toBe(before); // asked a moment ago
    vi.setSystemTime(T0 + 13 * HOUR);
    h.fm.content.tick();
    expect(wakes()).toBe(before + 1);
    expect(h.rec.wakes.at(-1)!.prompt).toMatch(/1 upcoming video, the target is 3/);
    for (let i = 0; i < 2; i++) await pitch(h, ch, { title: `More ${i}` });
    vi.setSystemTime(T0 + 30 * HOUR);
    h.fm.content.tick();
    expect(wakes()).toBe(before + 1); // 3 upcoming: no wake
  });

  it('turning it on schedules cuts already waiting for review; turning it off stops the top-up', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    await send(h, { type: 'content.channel.action', action: 'autopilot_on', channelId: ch });
    expect(h.fm.content.video(id)!.status).toBe('scheduled');
    await send(h, { type: 'content.channel.action', action: 'autopilot_off', channelId: ch });
    h.rec.wakes.length = 0;
    vi.setSystemTime(T0 + 30 * HOUR);
    h.fm.content.tick();
    expect(h.rec.wakes.filter((w) => /AUTOPILOT/.test(w.prompt))).toEqual([]);
  });
});

describe('restart', () => {
  it('a video left publishing by a previous run is failed, never retried', async () => {
    const home = tempDir();
    const h = start(fakes(), ['--backend', 'sim'], home);
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + HOUR });
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();
    expect(h.fm.content.video(id)!.status).toBe('publishing');
    await h.fm.close();
    cleanup.splice(cleanup.findIndex((f) => f.toString().includes('fm.close')), 1);

    const again = start(fakes(), ['--backend', 'sim'], home);
    expect(again.fm.content.video(id)!.status).toBe('publishing'); // as persisted
    const stub: Backend = { name: 'sim', start: async () => undefined, stop: async () => undefined, submitGoal: async () => undefined, onUserMessage() {}, onDecisionSettled() {}, onTaskAction() {}, onAgentAction() {} };
    await again.fm.start(stub);
    expect(again.fm.content.video(id)).toMatchObject({ status: 'failed', error: INTERRUPTED });
    again.fm.content.tick();
    expect(again.rec.published).toHaveLength(0);
    expect(h.rec.published).toHaveLength(1);
    const snap = again.fm.snapshot();
    expect(snap.type === 'snapshot' && snap.contentVideos?.map((v) => [v.id, v.status])).toEqual([[id, 'failed']]);
    expect(parseServerMessage({ v: 1, ...snap }).ok).toBe(true);
    expect(again.events.some((m) => m.type === 'notify' && m.level === 'warn' && /did not go out/.test(m.text))).toBe(true);
  });
});

describe('analytics sync', () => {
  async function published(h: ReturnType<typeof start>, ch: string, ytId = 'dQw4w9WgXcQ'): Promise<string> {
    const id = await toReview(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + HOUR });
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();
    h.fm.content.markPublished(id, `https://youtu.be/${ytId}`);
    return id;
  }

  it('OAuth: channel stats, per-video stats with history (<= 30 points) and a content.stats message', async () => {
    const f = fakes();
    f.oauth.connected.push('UCabcdefghijklmnopqrstuv');
    const h = start(f);
    const ch = await addChannel(h, { url: 'https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv' });
    await published(h, ch);
    for (let i = 0; i < 34; i++) {
      vi.setSystemTime(T0 + 3 * HOUR + i * HOUR);
      const r = await send(h, { type: 'content.channel.action', action: 'sync', channelId: ch });
      expect(r.ok).toBe(true);
      await h.fm.content.sync();
    }
    expect(f.oauth.videoStats).toHaveBeenCalledWith('UCabcdefghijklmnopqrstuv', ['dQw4w9WgXcQ']);
    expect(f.publicStats).not.toHaveBeenCalled();
    expect(h.fm.content.statsOf('dQw4w9WgXcQ')).toHaveLength(30);
    expect(h.fm.content.channelStats(ch)[0]).toMatchObject({ channelId: ch, subscribers: 1280, views28d: 31_000 });
    const msg = h.events.filter((m) => m.type === 'content.stats').at(-1)!;
    expect(msg.type === 'content.stats' && msg.channelStats[0]!.channelId).toBe(ch);
    expect(msg.type === 'content.stats' && msg.stats.filter((s) => s.videoId === 'dQw4w9WgXcQ')).toHaveLength(30);
    const snap = h.fm.snapshot();
    expect(snap.type === 'snapshot' && snap.contentStats).toHaveLength(30);
    expect(snap.type === 'snapshot' && snap.contentChannelStats).toHaveLength(1);
    expect(parseServerMessage({ v: 1, ...snap }).ok).toBe(true);
    expect(parseServerMessage({ v: 1, ...msg }).ok).toBe(true);
    expect(text(await toolsOf(h, 'vega').get_analytics!.handler({ channelId: ch }))).toMatch(/OAuth analytics connected[\s\S]*subscribers 1,280[\s\S]*retention/);
  }, 30_000);

  it('no OAuth: public numbers for the channel and its videos; references keep only their latest numbers', async () => {
    const f = fakes();
    const h = start(f);
    const ch = await addChannel(h);
    await published(h, ch);
    await h.fm.content.study('https://www.youtube.com/watch?v=abcdefghijk', {}, 'reel');
    const report = await h.fm.content.sync();
    expect(report.lines[0]).toMatch(/public numbers, 1 video updated/);
    expect(f.oauth.videoStats).not.toHaveBeenCalled();
    expect(f.publicStats).toHaveBeenCalledWith({ channelUrl: 'https://www.youtube.com/@tinyrobots', videoIds: ['dQw4w9WgXcQ'], limit: 50 });
    expect(h.fm.content.channelStats(ch)[0]).toMatchObject({ subscribers: 321 });
    expect(h.fm.content.channel(ch)!.youtubeId).toBe('UCmine00000000000000000'); // resolved from the scan
    expect(h.fm.content.statsOf('dQw4w9WgXcQ')).toHaveLength(1);
    expect(h.fm.content.statsOf('abcdefghijk')).toHaveLength(1);
    vi.setSystemTime(T0 + 20 * HOUR);
    await h.fm.content.sync();
    expect(h.fm.content.statsOf('dQw4w9WgXcQ')).toHaveLength(2);
    expect(h.fm.content.statsOf('abcdefghijk')).toHaveLength(1);
    expect(text(await toolsOf(h, 'vega').get_analytics!.handler({ videoId: h.fm.content.videos()[0]!.id }))).toMatch(/views 777 \(Δ \+0 since/);
  });

  it('a failing source is reported, not thrown; the timer syncs every 6 h and only when something is published', async () => {
    const f = fakes();
    f.publicStats.mockRejectedValueOnce(new Error('yt-dlp is not installed (brew install yt-dlp)'));
    const h = start(f);
    const ch = await addChannel(h);
    h.fm.content.tick();
    expect(f.publicStats).not.toHaveBeenCalled(); // nothing published yet
    await published(h, ch);
    const first = await h.fm.content.sync(ch);
    expect(first.lines[0]).toMatch(/sync failed — yt-dlp is not installed/);
    expect(first.channels).toBe(0);
    const calls = () => f.publicStats.mock.calls.length;
    await vi.waitFor(() => expect(calls()).toBeGreaterThan(0));
    const base = calls();
    h.fm.content.tick(); // lastSyncAt is recent
    await Promise.resolve();
    expect(calls()).toBe(base);
    vi.setSystemTime(T0 + 9 * HOUR);
    h.fm.content.tick();
    await vi.waitFor(() => expect(calls()).toBeGreaterThan(base));
  });

  it('48 h and 7 d post-mortem jobs for Vega, once each, until she writes them', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + HOUR });
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();
    h.fm.content.markPublished(id, 'https://youtu.be/dQw4w9WgXcQ');
    const published = T0 + 2 * HOUR;
    const pm = () => h.rec.wakes.filter((w) => w.id === 'vega');
    vi.setSystemTime(published + 47 * HOUR);
    h.fm.content.tick();
    expect(pm()).toHaveLength(0);
    vi.setSystemTime(published + 49 * HOUR);
    h.fm.content.tick();
    h.fm.content.tick();
    expect(pm()).toHaveLength(1);
    expect(pm()[0]!.prompt).toMatch(/POST-MORTEM \(48h\)[\s\S]*write_post_mortem/);
    // she is asked again only after a long while, and not at all once written
    vi.setSystemTime(published + 50 * HOUR);
    h.fm.content.tick();
    expect(pm()).toHaveLength(1);
    expect(text(await toolsOf(h, 'vega').write_post_mortem!.handler({ videoId: id, horizon: '48h', text: 'Views 5400, avg view 63%. The hook held; CTR 6.2%.' }))).toMatch(/Saved the 48h/);
    vi.setSystemTime(published + 8 * DAY);
    h.fm.content.tick();
    expect(pm()).toHaveLength(2);
    expect(pm()[1]!.prompt).toContain('(7d)');
    h.fm.content.writePostMortem(id, '7d', 'Final: 40k views.', 'vega');
    h.fm.content.writePostMortem(id, '48h', 'Revised.', 'vega');
    expect(h.fm.content.video(id)!.postMortems!.map((p) => [p.horizon, p.text])).toEqual([['7d', 'Final: 40k views.'], ['48h', 'Revised.']]);
    vi.setSystemTime(published + 20 * DAY);
    h.fm.content.tick();
    expect(pm()).toHaveLength(2);
    expect(() => h.fm.content.writePostMortem(id, '48h', '', 'vega')).toThrow(/empty/);
  });
});

describe('references (study_video, content.ref.open)', () => {
  it('studies a video into a reference with the breakdown on disk; open shows the sheets or one frame on the Screening Room', async () => {
    const f = fakes();
    const h = start(f);
    const { ref, breakdown } = await h.fm.content.study('https://www.youtube.com/watch?v=abcdefghijk', { fps: 2 }, 'reel');
    expect(f.study.studyVideo).toHaveBeenCalledWith('https://www.youtube.com/watch?v=abcdefghijk', path.join(h.cfg.dataDir, 'content', 'refs', 'r1'), { fps: 2 });
    expect(ref).toMatchObject({ id: 'r1', title: 'Study of https://www.youtube.com/watch?v=abcdefghijk', channel: 'Rival', views: 1_200_000, frameCount: 3, sheetCount: 2, hasTranscript: true, studiedBy: 'reel' });
    expect(fs.readFileSync(path.join(ref.dir, 'breakdown.md'), 'utf8')).toBe(breakdown);
    expect(h.events.some((m) => m.type === 'content.ref.upsert' && m.ref.id === 'r1')).toBe(true);
    const snap = h.fm.snapshot();
    expect(snap.type === 'snapshot' && snap.contentRefs?.map((r) => r.id)).toEqual(['r1']);
    expect(parseServerMessage({ v: 1, ...snap }).ok).toBe(true);

    expect(await send(h, { type: 'content.ref.open', refId: 'r1' })).toMatchObject({ ok: true, result: { refId: 'r1' } });
    const shown = h.events.filter((m) => m.type === 'media.add');
    expect(shown.map((m) => m.type === 'media.add' && m.item.name)).toEqual(['sheet01.jpg', 'sheet02.jpg']);
    expect(shown[0]!.type === 'media.add' && shown[0]!.item.agentId).toBe('reel');
    await send(h, { type: 'content.ref.open', refId: 'r1', frame: 2 });
    expect(h.events.filter((m) => m.type === 'media.add').at(-1)).toMatchObject({ item: { name: 'f0002_00m01.00s.jpg' } });
    expect((await send(h, { type: 'content.ref.open', refId: 'r1', frame: 9 })).error).toMatch(/3 frames, no frame 9/);
    expect((await send(h, { type: 'content.ref.open', refId: 'r7' })).error).toMatch(/no reference/);
  });

  it('a failed study leaves nothing behind; only the last 50 references are kept', async () => {
    const f = fakes();
    const h = start(f);
    f.study.studyVideo.mockRejectedValueOnce(new Error('yt-dlp is not installed (brew install yt-dlp)'));
    await expect(h.fm.content.study('https://www.youtube.com/watch?v=abcdefghijk', {}, 'reel')).rejects.toThrow(/brew install yt-dlp/);
    expect(h.fm.content.refs()).toHaveLength(0);
    expect(fs.existsSync(path.join(h.fm.content.dir, 'refs', 'r1'))).toBe(false);
    for (let i = 0; i < 52; i++) {
      await h.fm.content.study(`https://www.youtube.com/watch?v=${String(i).padStart(11, 'x')}`, {}, 'reel');
      vi.setSystemTime(T0 + (i + 1) * 1000);
    }
    expect(h.fm.content.refs()).toHaveLength(50);
    expect(h.fm.content.refs()[0]!.id).toBe('r4');
    expect(fs.existsSync(path.join(h.fm.content.dir, 'refs', 'r2'))).toBe(false);
  });
});

describe('playbook', () => {
  it('is a file per channel, shown on the channel for the mod, bounded to 20000 characters', async () => {
    const h = start();
    const ch = await addChannel(h, { brief: 'Warm, no hype.' });
    expect(h.fm.content.channel(ch)!.playbook).toContain('Warm, no hype.');
    const iris = toolsOf(h, 'iris');
    expect(text(await iris.read_playbook!.handler({ channelId: ch }))).toContain('What works');
    expect(text(await iris.update_playbook!.handler({ channelId: ch, markdown: '# Playbook\n- Numbers in the hook: 78% at 3 s (v3)' }))).toMatch(/Updated the playbook/);
    expect(fs.readFileSync(path.join(h.cfg.dataDir, 'content', 'channels', ch, 'playbook.md'), 'utf8')).toContain('78% at 3 s');
    expect(h.fm.content.channel(ch)!.playbook).toContain('78% at 3 s');
    const msg = h.events.filter((m) => m.type === 'content.channel.upsert').at(-1)!;
    expect(msg.type === 'content.channel.upsert' && msg.channel.playbook).toContain('78% at 3 s');
    expect(text(await iris.update_playbook!.handler({ channelId: ch, markdown: 'p'.repeat(20_001) }))).toMatch(/limit is 20000/);
    expect(text(await iris.update_playbook!.handler({ channelId: ch, markdown: '  ' }))).toMatch(/empty/);
    expect(text(await toolsOf(h, 'vega').update_playbook!.handler({ markdown: 'v'.repeat(20_000) }))).toMatch(/Updated/); // the only channel
    expect(h.fm.content.channel(ch)!.playbook).toHaveLength(20_000);
  });
});

// ---- the real turn path: fake `query()` ---------------------------------------------------------

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
async function callTool(options: Options, name: string, args: Record<string, unknown>): Promise<string> {
  const server = options.mcpServers!.agentcraft as unknown as ToolServer;
  const res = await server.instance._registeredTools[name]!.handler(args, {});
  return res.content.map((c) => c.text).join('\n');
}
const mk = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: `u${Math.random()}`, ...o }) as unknown as SDKMessage;
const initMsg = (s: string) => mk({ type: 'system', subtype: 'init', session_id: s, model: 'fake-model', cwd: '', tools: [] });
const resultMsg = (s: string) => mk({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0.001, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });

describe('turns of the content agents (fake SDK)', () => {
  it('@iris starts a turn in the content workspace with her briefing and tools; a user comment wakes the video owner', async () => {
    const calls: Array<{ prompt: string; cwd: string; system: string; tools: string[] }> = [];
    const queryFn = ({ prompt, options }: { prompt: string; options: Options }) => {
      const server = options.mcpServers!.agentcraft as unknown as ToolServer;
      calls.push({ prompt, cwd: options.cwd!, system: (options.systemPrompt as { append: string }).append, tools: Object.keys(server.instance._registeredTools) });
      async function* run(): AsyncGenerator<SDKMessage> {
        yield initMsg('00000000-0000-4000-8000-00000000c001');
        await callTool(options, 'send_message', { to: 'user', text: 'On it.' });
        yield resultMsg('00000000-0000-4000-8000-00000000c001');
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    const h = startContent(cleanup, fakes(), ['--backend', 'claude', '--workers', 'kit']);
    const backend = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true });
    await h.fm.start(backend); // installs the real runner
    for (const id of ['iris', 'reel', 'vega', 'cleo']) expect(h.fm.agent(id)).toMatchObject({ active: true });

    expect((await send(h, { type: 'user.message', to: 'all', text: '@iris what should we post?' })).result).toMatchObject({ to: 'iris' });
    await until(() => calls.length === 1);
    expect(calls[0]!.cwd).toBe(h.fm.content.dir);
    expect(calls[0]!.system).toMatch(/You are Iris, in Alex's Content Room/);
    expect(calls[0]!.system).toMatch(/THE PLAYBOOK IS LAW/);
    expect(calls[0]!.system).toMatch(/Tuesday 13 October 2026, 11:00 \(Europe\/Lisbon\)/);
    expect(calls[0]!.system).toMatch(/SHOWRUNNER/);
    expect(calls[0]!.tools).toEqual(expect.arrayContaining(['pitch_video', 'update_playbook', 'approve_idea', 'study_video', 'scan_channel', 'get_analytics', 'read_playbook', 'send_message']));
    expect(calls[0]!.tools).not.toEqual(expect.arrayContaining(['update_task', 'attach_cut', 'mark_published']));
    await until(() => h.fm.agent('iris')!.state === 'idle');

    // the studio wakes Reel through the real runner when the user approves an idea
    const ch = await addChannel(h);
    await until(() => calls.length === 2); // new channel -> Iris
    expect(calls[1]!.prompt).toMatch(/just added the channel "Tiny Robots"/);
    const id = await pitch(h, ch);
    await until(() => h.fm.agent('iris')!.state === 'idle');
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve' });
    await until(() => calls.length === 3);
    expect(calls[2]!.system).toMatch(/You are Reel/);
    expect(calls[2]!.system).toMatch(/READ the contact sheets/);
    expect(calls[2]!.system).toMatch(/study_video/);
    expect(calls[2]!.tools).toEqual(expect.arrayContaining(['start_production', 'attach_cut', 'update_video']));
    expect(calls[2]!.prompt).toMatch(/APPROVED the idea/);
    await until(() => h.fm.agent('reel')!.state === 'idle');
    await send(h, { type: 'content.video.action', videoId: id, action: 'comment', text: 'less text on screen' });
    await until(() => calls.length === 4);
    expect(calls[3]!.prompt).toContain('less text on screen');
    expect(h.fm.agent('reel')).toMatchObject({ station: 'desk' });
  }, 40_000);

  it("a due scheduled video runs a publish turn for cleo: Chrome on YouTube / Studio / Google sign-in without asking, other sites and Bash ask; chat turns ask", async () => {
    const verdicts: Array<[string, string]> = [];
    const prompts: string[] = [];
    const queryFn = ({ prompt, options }: { prompt: string; options: Options }) => {
      prompts.push(prompt);
      const ask = async (tool: string, input: Record<string, unknown>) => (await options.canUseTool!(tool, input, { signal: new AbortController().signal, toolUseID: 't', requestId: 'r' } as never))?.behavior ?? 'none';
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = '00000000-0000-4000-8000-00000000d001';
        yield initMsg(s);
        if (prompt.startsWith('PUBLISH NOW')) {
          const id = /video (v\d+)/.exec(prompt)![1]!;
          verdicts.push([`${id} studio`, await ask('mcp__claude-in-chrome__navigate', { url: 'https://studio.youtube.com' })]);
          verdicts.push([`${id} google`, await ask('mcp__claude-in-chrome__navigate', { url: 'https://accounts.google.com/signin' })]);
          verdicts.push([`${id} click`, await ask('mcp__claude-in-chrome__computer', { action: 'left_click', coordinate: [1, 2] })]);
          const own = h.fm.content.video(id)!.filePath!;
          verdicts.push([`${id} upload-own`, await ask('mcp__claude-in-chrome__file_upload', { paths: [own], ref: 'ref_1', tabId: 1 })]);
          verdicts.push([`${id} upload-other`, await ask('mcp__claude-in-chrome__file_upload', { paths: [own, '/etc/passwd'], ref: 'ref_1', tabId: 1 })]);
          verdicts.push([`${id} x.com`, await ask('mcp__claude-in-chrome__navigate', { url: 'https://x.com/home' })]);
          verdicts.push([`${id} lookalike`, await ask('mcp__claude-in-chrome__navigate', { url: 'https://youtube.com.evil.example/' })]);
          if (id === 'v1') await callTool(options, 'mark_published', { id, url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' });
        } else {
          verdicts.push(['chat chrome', await ask('mcp__claude-in-chrome__navigate', { url: 'https://studio.youtube.com' })]);
        }
        yield resultMsg(s);
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    const h = startContent(cleanup, fakes(), ['--backend', 'claude', '--workers', 'kit']);
    const backend = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true });
    await h.fm.start(backend);
    expect(h.fm.autoApprove).toBe(false);

    const ch = await addChannel(h);
    const v1 = await toReview(h, ch, { title: 'Going out via Studio' });
    const v2 = await toReview(h, ch, { title: 'Second', format: 'long' });
    fs.writeFileSync(path.join(h.fm.content.dir, 'cuts', 'v1.jpg'), 'j');
    h.fm.content.update(v1, { description: 'Line one\nLine two', tags: ['robots', 'ai'] }, 'reel');
    for (const id of [v1, v2]) await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + HOUR });
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();

    // nobody answers the off-site / Bash prompts but the test: deny them as they come
    const asked: string[] = [];
    await until(() => {
      for (const d of h.fm.decisions.open().filter((x) => x.kind === 'permission')) {
        asked.push(d.question);
        void h.fm.answerDecision(d.id, 'Deny');
      }
      return h.fm.content.video(v1)!.status === 'published' && h.fm.content.video(v2)!.status === 'failed';
    });
    expect(prompts.find((p) => p.startsWith('PUBLISH NOW'))).toContain('<<<TITLE\nGoing out via Studio\nTITLE>>>');
    expect(prompts.find((p) => p.startsWith('PUBLISH NOW'))).toContain('Line one\nLine two');
    expect(prompts.find((p) => p.startsWith('PUBLISH NOW'))).toMatch(/Tags: robots, ai/);
    expect(prompts.find((p) => p.startsWith('PUBLISH NOW'))).toMatch(/channel "Tiny Robots"/);
    expect(h.fm.content.video(v1)).toMatchObject({ youtubeVideoId: 'dQw4w9WgXcQ' });
    expect(h.fm.content.video(v2)!.error).toMatch(/check YouTube Studio/);
    const byId = (id: string) => Object.fromEntries(verdicts.filter(([k]) => k.startsWith(`${id} `)).map(([k, v]) => [k.slice(id.length + 1), v]));
    for (const id of ['v1', 'v2']) {
      expect(byId(id), id).toMatchObject({ studio: 'allow', google: 'allow', click: 'allow', 'upload-own': 'allow', 'upload-other': 'deny', 'x.com': 'deny', lookalike: 'deny' });
    }
    expect(asked.some((q) => /x\.com/.test(q))).toBe(true);
    expect(asked.some((q) => /youtube\.com\.evil\.example/.test(q))).toBe(true);

    // outside a publish turn (a chat message) the same Chrome call asks
    await send(h, { type: 'user.message', to: 'cleo', text: 'open studio' });
    await until(() => h.fm.decisions.open().some((d) => d.kind === 'permission' && d.agentId === 'cleo' && /navigate/.test(d.question)));
    await h.fm.answerDecision(h.fm.decisions.open().find((d) => d.kind === 'permission')!.id, 'Deny');
    await until(() => verdicts.some(([k]) => k === 'chat chrome'));
    expect(verdicts.find(([k]) => k === 'chat chrome')![1]).toBe('deny');
  }, 40_000);

  it('a stopped publisher leaves due videos scheduled (no turn, no failure) until resumed', async () => {
    const h = startContent(cleanup, fakes(), ['--backend', 'claude', '--workers', 'kit']);
    const backend = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: (() => {
      throw new Error('no turn expected');
    }) as never, skipAuthCheck: true });
    await h.fm.start(backend);
    await h.fm.agentAction('cleo', 'stop');
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + HOUR });
    vi.setSystemTime(T0 + 3 * HOUR);
    h.fm.content.tick();
    expect(h.fm.content.video(id)!.status).toBe('scheduled');
    await h.fm.agentAction('cleo', 'resume');
    expect(h.fm.agent('cleo')!.active).toBe(true);
  }, 20_000);
});

describe('review fixes', () => {
  async function scheduled(h: ReturnType<typeof start>, ch: string, at: number) {
    const id = await toReview(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at });
    return id;
  }

  it('1: approved/producing videos are re-woken for Reel (throttled), and a stale production is reported once', async () => {
    const h = start();
    const ch = await addChannel(h);
    h.rec.off.add('reel');
    const id = await pitch(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve' }); // Reel off: no wake
    h.rec.wakes.length = 0;
    h.rec.off.delete('reel');
    h.fm.content.tick();
    expect(h.rec.wakes.map((w) => w.id)).toEqual(['reel']);
    expect(h.rec.wakes[0]!.prompt).toMatch(/REMINDER[\s\S]*start_production/);
    h.fm.content.tick();
    expect(h.rec.wakes).toHaveLength(1);
    h.fm.content.startProduction(id);
    vi.setSystemTime(T0 + 13 * HOUR);
    h.fm.content.tick();
    expect(h.rec.wakes).toHaveLength(2);
    vi.setSystemTime(T0 + 26 * HOUR);
    h.fm.content.tick();
    h.fm.content.tick();
    expect(h.events.filter((m) => m.type === 'notify' && /in production for over 24 hours/.test(m.text))).toHaveLength(1);
  });

  it('2: turning autopilot off sends autopilot-scheduled videos back to review; a user-approved one stays; publishDue never publishes one on a manual channel', async () => {
    const h = start();
    const ch = await addChannel(h);
    await send(h, { type: 'content.channel.action', action: 'autopilot_on', channelId: ch });
    const auto = await toReview(h, ch);
    expect(h.fm.content.video(auto)).toMatchObject({ status: 'scheduled', autoScheduled: true });
    await send(h, { type: 'content.channel.action', action: 'autopilot_off', channelId: ch });
    expect(h.fm.content.video(auto)).toMatchObject({ status: 'review' });
    expect(h.fm.content.video(auto)!.scheduledAt).toBeUndefined();
    expect(h.fm.content.video(auto)!.autoScheduled).toBeUndefined();
    const manual = await scheduled(h, ch, T0 + HOUR);
    expect(h.fm.content.video(manual)!.autoScheduled).toBeUndefined();
    await send(h, { type: 'content.channel.action', action: 'autopilot_on', channelId: ch });
    const auto2 = await toReview(h, ch);
    // a stale autoScheduled mark on a manual channel is never published
    await send(h, { type: 'content.channel.action', action: 'autopilot_off', channelId: ch });
    expect(h.fm.content.video(auto2)!.status).toBe('review');
    await send(h, { type: 'content.channel.action', action: 'autopilot_off', channelId: ch });
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();
    expect(h.rec.published.map((p) => p.id)).toEqual([manual]);
  });

  it('3/4: the cut is copied into cuts/<id>/ (symlinks resolved), a swapped file is refused at publish time, local study sources must be in the workspace', async () => {
    const h = start();
    const ch = await addChannel(h);
    const outside = path.join(tempDir(), 'secret.mp4');
    cleanup.push(() => fs.rmSync(path.dirname(outside), { recursive: true, force: true }));
    fs.writeFileSync(outside, 'original');
    const link = path.join(h.fm.content.dir, 'cuts', 'link.mp4');
    fs.symlinkSync(outside, link);
    const id = await pitch(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve' });
    h.fm.content.startProduction(id);
    const v = h.fm.content.attachCut(id, link);
    expect(v.filePath).toBe(path.join(h.fm.content.dir, 'cuts', id, 'secret.mp4'));
    expect(fs.lstatSync(v.filePath!).isSymbolicLink()).toBe(false);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + HOUR });
    fs.writeFileSync(outside, 'swapped'); // the source changes: our copy does not
    fs.writeFileSync(v.filePath!, 'tampered');
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();
    expect(h.fm.content.video(id)).toMatchObject({ status: 'failed', error: expect.stringMatching(/changed after it was approved/) });
    expect(h.rec.published).toHaveLength(0);

    await expect(h.fm.content.study(outside, {}, 'reel')).rejects.toThrow(/inside the content workspace/);
    await expect(h.fm.content.study(link, {}, 'reel')).rejects.toThrow(/inside the content workspace/); // symlink out
    const inside = path.join(h.fm.content.dir, 'mine.mp4');
    fs.writeFileSync(inside, 'x');
    await h.fm.content.study(inside, {}, 'reel');
    expect(h.f.study.studyVideo.mock.calls.at(-1)![0]).toBe(fs.realpathSync(inside));
  });

  it('4: comments on a scheduled video go to Iris', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await scheduled(h, ch, T0 + 5 * HOUR);
    h.rec.wakes.length = 0;
    await send(h, { type: 'content.video.action', videoId: id, action: 'comment', text: 'move it' });
    expect(h.rec.wakes.map((w) => w.id)).toEqual(['iris']);
  });

  it('5: mark_published takes only YouTube links or a bare id and stores the canonical link', async () => {
    const h = start();
    const ch = await addChannel(h);
    const mk = async (over: Record<string, unknown>) => {
      const id = await toReview(h, ch, over);
      await send(h, { type: 'content.video.action', videoId: id, action: 'publish_now' });
      return id;
    };
    const long = await mk({ format: 'long' });
    expect(() => h.fm.content.markPublished(long, 'https://evil.example/watch?v=dQw4w9WgXcQ')).toThrow(/not a youtube\.com/);
    expect(h.fm.content.markPublished(long, 'https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=9s&utm=x').url).toBe('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
    h.rec.resolvers[0]!({ ok: true });
    const short = await mk({});
    h.rec.resolvers[1]?.({ ok: true });
    expect(h.fm.content.markPublished(short, 'dQw4w9WgXcR').url).toBe('https://www.youtube.com/shorts/dQw4w9WgXcR');
  });

  it('6: a throwing step does not escape the scheduler; a throwing runner fails the video', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await scheduled(h, ch, T0 + HOUR);
    h.fm.content.setRunner({ ...h.rec.runner, publish: () => { throw new Error('boom'); } });
    vi.setSystemTime(T0 + 2 * HOUR);
    expect(() => h.fm.content.tick()).not.toThrow();
    await vi.waitFor(() => expect(h.fm.content.video(id)).toMatchObject({ status: 'failed', error: 'boom' }));
    h.fm.content.setRunner({ ...h.rec.runner, canRun: () => { throw new Error('bad runner'); } });
    expect(() => h.fm.content.tick()).not.toThrow();
  });

  it('7: a video stuck in publishing for an hour is failed and the queue moves on', async () => {
    const h = start();
    const ch = await addChannel(h);
    const a = await scheduled(h, ch, T0 + HOUR);
    const b = await scheduled(h, ch, T0 + 2.9 * HOUR);
    vi.setSystemTime(T0 + 3 * HOUR);
    h.fm.content.tick();
    expect(h.fm.content.video(a)!.status).toBe('publishing');
    h.fm.content.tick();
    expect(h.fm.content.video(b)!.status).toBe('scheduled'); // one at a time
    vi.setSystemTime(T0 + 3 * HOUR + 61 * 60_000);
    h.fm.content.tick();
    expect(h.fm.content.video(a)).toMatchObject({ status: 'failed', error: expect.stringMatching(/interrupted.*no result after 60 minutes/) });
    expect(h.rec.published.map((p) => p.id)).toEqual([a, b]);
  });

  it('8: a video due more than 2 h ago is not published late: back to review with a note and a warning', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await scheduled(h, ch, T0 + HOUR);
    vi.setSystemTime(T0 + 4 * HOUR);
    h.fm.content.tick();
    expect(h.rec.published).toHaveLength(0);
    expect(h.fm.content.video(id)).toMatchObject({ status: 'review', error: expect.stringMatching(/missed its slot/) });
    expect(h.fm.content.video(id)!.scheduledAt).toBeUndefined();
    expect(h.events.some((m) => m.type === 'notify' && m.level === 'warn' && /Foreman was not running/.test(m.text))).toBe(true);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + 9 * HOUR });
    expect(h.fm.content.video(id)!.error).toBeUndefined();
  });

  it('9: a sync does not resurrect a removed channel; sync of another channel waits for the running one', async () => {
    const f = fakes();
    const h = start(f);
    const a = await addChannel(h, { name: 'A' });
    const b = await addChannel(h, { name: 'B', url: 'https://www.youtube.com/@b' });
    const id = await scheduled(h, a, T0 + HOUR);
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();
    h.fm.content.markPublished(id, 'https://youtu.be/dQw4w9WgXcQ');
    let release: () => void = () => undefined;
    f.publicStats.mockImplementationOnce(async () => {
      await new Promise<void>((r) => (release = r));
      return { stats: [{ videoId: 'dQw4w9WgXcQ', at: Date.now(), views: 5 }], channel: { subscribers: 9 } };
    });
    const first = h.fm.content.sync(a);
    await vi.waitFor(() => expect(f.publicStats).toHaveBeenCalledTimes(1));
    const same = h.fm.content.sync(a);
    const other = h.fm.content.sync(b);
    expect(same).toBe(first);
    expect(other).not.toBe(first);
    await send(h, { type: 'content.channel.action', action: 'remove', channelId: a });
    release();
    await first;
    expect(h.fm.content.channelStats(a)).toEqual([]);
    expect(h.fm.content.stats().some((s) => s.videoId === 'dQw4w9WgXcQ')).toBe(false);
    const r = await other;
    expect(r.lines[0]).toMatch(/^B:/);
    expect(h.fm.content.channelStats(b)).toHaveLength(1);
  });
});

describe('a fast cadence (5 shorts a day)', () => {
  it('new channels default to 35 a week; slots are 09/12/15/18/21 Lisbon, skip taken ones, DST aware', async () => {
    const h = start();
    const ch = await addChannel(h, { postsPerWeek: undefined });
    expect(h.fm.content.channel(ch)!.postsPerWeek).toBe(35);
    const ids: string[] = [];
    for (let i = 0; i < 7; i++) {
      const id = await toReview(h, ch, { title: `S${i}` });
      await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut' });
      ids.push(id);
    }
    const when = ids.map((id) => h.fm.content.video(id)!.scheduledAt);
    // now is Tue 13 Oct 11:00 Lisbon: 12:00 15:00 18:00 21:00 today, then 09:00 12:00 15:00 tomorrow
    expect(when).toEqual(['2026-10-13T12:00', '2026-10-13T15:00', '2026-10-13T18:00', '2026-10-13T21:00', '2026-10-14T09:00', '2026-10-14T12:00', '2026-10-14T15:00'].map((x) => parseWhen(x)));
    expect(nextSlot(35, parseWhen('2026-10-24T22:30'))).toBe(Date.UTC(2026, 9, 25, 9, 0)); // Sat night -> Sun 09:00 (the clocks go back that night: WET)
    expect(nextSlot(35, parseWhen('2026-10-25T22:30'))).toBe(Date.UTC(2026, 9, 26, 9, 0)); // after the clocks went back: 09:00 WET
  });

  it('autopilot targets two days of posts, asks for the shortfall as one batch; Reel gets the whole approved list', async () => {
    const h = start();
    const ch = await addChannel(h, { postsPerWeek: 35 });
    await send(h, { type: 'content.channel.action', action: 'autopilot_on', channelId: ch });
    expect(h.rec.wakes.at(-1)!.prompt).toMatch(/0 upcoming videos, the target is 10 \(35 posts a week, about 5 a day\)/);
    expect(h.rec.wakes.at(-1)!.prompt).toMatch(/BATCH of 10 new ideas in this one turn/);
    const a = await pitch(h, ch, { title: 'A' });
    const b = await pitch(h, ch, { title: 'B' });
    h.rec.wakes.length = 0;
    const iris = toolsOf(h, 'iris');
    await iris.approve_idea!.handler({ id: a });
    await iris.approve_idea!.handler({ id: b });
    // one wake for the batch: the first approval's list is re-checked by Reel after each cut
    expect(h.rec.wakes.filter((w) => w.id === 'reel')).toHaveLength(1);
    expect(h.rec.wakes[0]!.prompt).toMatch(/APPROVED/);
    const c = await pitch(h, ch, { title: 'C' });
    vi.setSystemTime(T0 + 20 * 60_000);
    await iris.approve_idea!.handler({ id: c });
    const last = h.rec.wakes.filter((w) => w.id === 'reel').at(-1)!;
    expect(h.rec.wakes.filter((w) => w.id === 'reel')).toHaveLength(2);
    expect(last.prompt).toMatch(/APPROVED 3 ideas[\s\S]*v1[\s\S]*v2[\s\S]*v3[\s\S]*list_videos\(status "approved"\)/);
    // the top-up repeats after 6 h on a fast cadence, not before
    const topups = () => h.rec.wakes.filter((w) => /AUTOPILOT/.test(w.prompt)).length;
    const n = topups();
    vi.setSystemTime(T0 + 5 * HOUR);
    h.fm.content.tick();
    expect(topups()).toBe(n);
    vi.setSystemTime(T0 + 7 * HOUR);
    h.fm.content.tick();
    expect(topups()).toBe(n + 1);
    expect(h.rec.wakes.filter((w) => /AUTOPILOT/.test(w.prompt)).at(-1)!.prompt).toMatch(/has 3 upcoming videos, the target is 10/);
  });

  it('a slot missed by more than 2 h is moved by autopilot to the next free slot instead of waiting for an approval nobody gives', async () => {
    const h = start();
    const ch = await addChannel(h, { postsPerWeek: 35 });
    await send(h, { type: 'content.channel.action', action: 'autopilot_on', channelId: ch });
    const id = await toReview(h, ch);
    const first = h.fm.content.video(id)!.scheduledAt!;
    expect(first).toBe(parseWhen('2026-10-13T12:00'));
    vi.setSystemTime(first + 3 * HOUR);
    h.fm.content.tick();
    expect(h.fm.content.video(id)).toMatchObject({ status: 'scheduled', autoScheduled: true });
    expect(h.fm.content.video(id)!.scheduledAt).toBeGreaterThan(first + 3 * HOUR);
  });

  it('five a day does not trip the 2 h rule: a stuck publish is failed after 1 h and the next one (3 h later) still goes out on time', async () => {
    const h = start();
    const ch = await addChannel(h, { postsPerWeek: 35 });
    const a = await toReview(h, ch);
    const b = await toReview(h, ch);
    await send(h, { type: 'content.video.action', videoId: a, action: 'approve_cut' });
    await send(h, { type: 'content.video.action', videoId: b, action: 'approve_cut' });
    const [sa, sb] = [h.fm.content.video(a)!.scheduledAt!, h.fm.content.video(b)!.scheduledAt!];
    expect(sb - sa).toBe(3 * HOUR);
    vi.setSystemTime(sa + 1000);
    h.fm.content.tick();
    expect(h.fm.content.video(a)!.status).toBe('publishing');
    vi.setSystemTime(sa + 61 * 60_000);
    h.fm.content.tick();
    expect(h.fm.content.video(a)!.status).toBe('failed');
    vi.setSystemTime(sb + 1000);
    h.fm.content.tick();
    expect(h.rec.published.map((p) => p.id)).toEqual([a, b]);
  });

  it('pitch_video defaults to a short', async () => {
    const h = start();
    const ch = await addChannel(h);
    const r = await toolsOf(h, 'iris').pitch_video!.handler({ channelId: ch, title: 'T', hook: 'h', pitch: 'p' });
    expect(r.isError).toBeUndefined();
    expect(h.fm.content.video('v1')!.format).toBe('short');
  });
});

describe('numbers from YouTube Studio in Chrome (record_stats)', () => {
  async function live(h: ReturnType<typeof start>, ch: string, ytId = 'dQw4w9WgXcQ') {
    const id = await toReview(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve_cut', at: T0 + HOUR });
    vi.setSystemTime(T0 + 2 * HOUR);
    h.fm.content.tick();
    h.fm.content.markPublished(id, `https://youtu.be/${ytId}`);
    return id;
  }

  it('records channel and video numbers like an API sync: history, content.stats, connected/statsSource, retention', async () => {
    const h = start();
    const ch = await addChannel(h);
    await live(h, ch);
    expect(h.fm.content.channel(ch)).toMatchObject({ connected: false });
    expect(h.fm.content.channel(ch)!.statsSource).toBeUndefined();
    const vega = toolsOf(h, 'vega');
    const retention = Array.from({ length: 21 }, (_, i) => 1 - i * 0.04);
    const r = await vega.record_stats!.handler({
      channelId: ch,
      channel: { subscribers: 1234, views28d: 50_000, watchMinutes28d: 3000, subsGained28d: 120, daily: [{ date: '2026-10-12', views: 900, subsGained: 4 }] },
      videos: [
        { youtubeVideoId: 'dQw4w9WgXcQ', views: 5400, likes: 400, avgViewDurationSec: 21, avgViewPct: 63.5, impressions: 40_000, ctr: 0.062, stayedToWatchPct: 71, swipeAwayPct: 29, retention },
        { url: 'https://www.youtube.com/shorts/aaaaaaaaaaa', views: 10 },
        { url: 'not a video', views: 1 },
      ],
    });
    expect(r.isError).toBeUndefined();
    expect(text(r)).toMatch(/Recorded 2 videos and the channel numbers for Tiny Robots\. Skipped.*not a video/);
    expect(h.fm.content.statsOf('dQw4w9WgXcQ')).toHaveLength(1);
    expect(h.fm.content.statsOf('dQw4w9WgXcQ')[0]).toMatchObject({ views: 5400, stayedToWatchPct: 71, swipeAwayPct: 29, ctr: 0.062 });
    expect(h.fm.content.statsOf('aaaaaaaaaaa')).toHaveLength(1);
    expect(h.fm.content.channelStats(ch)[0]).toMatchObject({ subscribers: 1234, views28d: 50_000 });
    expect(h.fm.content.channel(ch)).toMatchObject({ connected: true, statsSource: 'studio' });
    const msg = h.events.filter((m) => m.type === 'content.stats').at(-1)!;
    expect(parseServerMessage({ v: 1, ...msg }).ok).toBe(true);
    expect(h.events.some((m) => m.type === 'content.channel.upsert' && m.channel.statsSource === 'studio')).toBe(true);
    expect(parseServerMessage({ v: 1, ...h.fm.snapshot() }).ok).toBe(true);
    // a second read an hour later extends the history; a public sync keeps the deep numbers for a day
    vi.setSystemTime(T0 + 3 * HOUR);
    await vega.record_stats!.handler({ channelId: ch, videos: [{ youtubeVideoId: 'dQw4w9WgXcQ', views: 6000 }] });
    expect(h.fm.content.statsOf('dQw4w9WgXcQ').map((s) => s.views)).toEqual([5400, 6000]);
    expect(h.fm.content.statsOf('dQw4w9WgXcQ')[1]).toMatchObject({ avgViewPct: 63.5, retention });
    expect(h.fm.content.channelStats(ch)[0]).toMatchObject({ subscribers: 1234 }); // not wiped by a partial record
    // after 24 h the channel is back to public numbers
    vi.setSystemTime(T0 + 30 * HOUR);
    expect(h.fm.content.channel(ch)).toMatchObject({ connected: false, statsSource: 'public' });
    expect(text(await vega.get_analytics!.handler({ videoId: 'v1' }))).toMatch(/views 6,000/);
  });

  it('refuses out-of-range numbers and only Vega has the tool', async () => {
    const h = start();
    const ch = await addChannel(h);
    await live(h, ch);
    const bad = (videos: unknown[], channel?: unknown) => text({ content: [{ text: (() => { try { h.fm.content.recordStats({ channelId: ch, videos, ...(channel ? { channel } : {}) } as never, 'vega'); return 'ok'; } catch (e) { return (e as Error).message; } })() }] });
    expect(bad([{ youtubeVideoId: 'dQw4w9WgXcQ', views: 1, ctr: 6.2 }])).toMatch(/record_stats: videos\.0\.ctr/);
    expect(bad([{ youtubeVideoId: 'dQw4w9WgXcQ', views: 1, avgViewPct: 120 }])).toMatch(/avgViewPct/);
    expect(bad([{ youtubeVideoId: 'dQw4w9WgXcQ', views: -1 }])).toMatch(/views/);
    expect(bad([{ youtubeVideoId: 'dQw4w9WgXcQ', views: 1, retention: [1, 0.5] }])).toMatch(/retention/);
    expect(bad([{ youtubeVideoId: 'dQw4w9WgXcQ', views: 1, stayedToWatchPct: 101 }])).toMatch(/stayedToWatchPct/);
    expect(bad([], { daily: [{ date: 'yesterday', views: 1 }] })).toMatch(/YYYY-MM-DD/);
    expect(h.fm.content.stats()).toEqual([]);
    for (const a of ['iris', 'reel', 'cleo']) expect(toolsOf(h, a).record_stats, a).toBeUndefined();
    expect(toolsOf(h, 'vega').record_stats).toBeDefined();
    expect(text(await toolsOf(h, 'vega').record_stats!.handler({ channelId: 'zz', videos: [] }))).toMatch(/no channel "zz"/);
  });

  it('Vega gets a Studio read job every 6 h, on sync, and before each post-mortem, only when Chrome is available', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await live(h, ch);
    const reads = () => h.rec.wakes.filter((w) => /^STUDIO READ/.test(w.prompt));
    h.fm.content.tick();
    expect(reads()).toHaveLength(0); // no Chrome: the API / public numbers only
    h.rec.studio = true;
    h.fm.content.tick();
    expect(reads()).toHaveLength(1);
    expect(reads()[0]).toMatchObject({ id: 'vega', kind: 'analytics' });
    expect(reads()[0]!.prompt).toContain('https://studio.youtube.com');
    expect(reads()[0]!.prompt).toContain(`record_stats channelId "${ch}"`);
    expect(reads()[0]!.prompt).toContain(`${id} youtubeVideoId=dQw4w9WgXcQ`);
    expect(reads()[0]!.prompt).toMatch(/READ ONLY: never change a setting, never upload/);
    h.fm.content.tick();
    expect(reads()).toHaveLength(1); // asked a moment ago
    // after a successful read it is not due for 6 h; a failed one is asked again after 2 h
    vi.setSystemTime(T0 + 5 * HOUR);
    h.fm.content.tick();
    expect(reads()).toHaveLength(2);
    h.fm.content.recordStats({ channelId: ch, videos: [{ youtubeVideoId: 'dQw4w9WgXcQ', views: 1 }] }, 'vega');
    vi.setSystemTime(T0 + 9 * HOUR);
    h.fm.content.tick();
    expect(reads()).toHaveLength(2);
    vi.setSystemTime(T0 + 12 * HOUR);
    h.fm.content.tick();
    expect(reads()).toHaveLength(3);
    // the sync button
    await send(h, { type: 'content.channel.action', action: 'sync', channelId: ch });
    expect(reads()).toHaveLength(3); // asked 0 h ago: 10 minutes grace
    vi.setSystemTime(T0 + 12 * HOUR + 11 * 60_000);
    await send(h, { type: 'content.channel.action', action: 'sync', channelId: ch });
    expect(reads()).toHaveLength(4);
    // post-mortem with Studio first, in an analytics job; without Chrome the old sync_analytics prompt
    vi.setSystemTime(T0 + 2 * HOUR + 49 * HOUR);
    h.fm.content.tick();
    const pm = h.rec.wakes.filter((w) => /^POST-MORTEM/.test(w.prompt));
    expect(pm).toHaveLength(1);
    expect(pm[0]).toMatchObject({ kind: 'analytics' });
    expect(pm[0]!.prompt).toMatch(/collect fresh numbers from YouTube Studio[\s\S]*record_stats[\s\S]*write_post_mortem/);
    h.rec.studio = false;
    h.fm.content.writePostMortem(id, '48h', 'done', 'vega');
    vi.setSystemTime(T0 + 2 * HOUR + 8 * DAY);
    h.fm.content.tick();
    const pm7 = h.rec.wakes.filter((w) => /^POST-MORTEM \(7d\)/.test(w.prompt));
    expect(pm7[0]).toMatchObject({ kind: 'content' });
    expect(pm7[0]!.prompt).toContain('sync_analytics');
  });

  it('sync_analytics tells Vega to read Studio when there is no OAuth', async () => {
    const h = start();
    const ch = await addChannel(h);
    await live(h, ch);
    expect(text(await toolsOf(h, 'vega').sync_analytics!.handler({ channelId: ch }))).toMatch(/No OAuth analytics for Tiny Robots[\s\S]*record_stats/);
  });
});

describe('the Chrome guard of a Studio read (fake SDK)', () => {
  it('an analytics job allows Chrome on YouTube / Studio / Google only, never file uploads; a chat turn asks', async () => {
    const verdicts: Array<[string, string]> = [];
    const queryFn = ({ prompt, options }: { prompt: string; options: Options }) => {
      const ask = async (tool: string, input: Record<string, unknown>) => (await options.canUseTool!(tool, input, { signal: new AbortController().signal, toolUseID: 't', requestId: 'r' } as never))?.behavior ?? 'none';
      async function* run(): AsyncGenerator<SDKMessage> {
        const s = '00000000-0000-4000-8000-00000000e001';
        yield initMsg(s);
        if (prompt.startsWith('PUBLISH NOW')) {
          await callTool(options, 'mark_published', { id: /video (v\d+)/.exec(prompt)![1]!, url: 'https://youtu.be/dQw4w9WgXcQ' });
        } else if (prompt.startsWith('STUDIO READ') || prompt.startsWith('Message from')) {
          const tag = prompt.startsWith('STUDIO READ') ? 'read' : 'chat';
          verdicts.push([`${tag} studio`, await ask('mcp__claude-in-chrome__navigate', { url: 'https://studio.youtube.com/channel/UC1/analytics' })]);
          verdicts.push([`${tag} click`, await ask('mcp__claude-in-chrome__computer', { action: 'left_click', coordinate: [1, 2] })]);
          verdicts.push([`${tag} evil`, await ask('mcp__claude-in-chrome__navigate', { url: 'https://youtube.com.evil.example/' })]);
          verdicts.push([`${tag} upload`, await ask('mcp__claude-in-chrome__file_upload', { paths: ['/etc/passwd'], ref: 'r', tabId: 1 })]);
          if (tag === 'read') await callTool(options, 'record_stats', { channelId: 'ch1', videos: [{ youtubeVideoId: 'dQw4w9WgXcQ', views: 77 }] });
        }
        yield resultMsg(s);
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };
    const h = startContent(cleanup, fakes(), ['--backend', 'claude', '--workers', 'kit', '--chrome', '--use-claude-login']);
    const backend = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true });
    await h.fm.start(backend); // the real runner: Chrome + Claude login => Studio reads are available
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    const deny = () => {
      for (const d of h.fm.decisions.open().filter((x) => x.kind === 'permission')) void h.fm.answerDecision(d.id, 'Deny');
    };
    await send(h, { type: 'content.video.action', videoId: id, action: 'publish_now' });
    await until(() => (deny(), h.fm.content.video(id)!.status === 'published'));
    await until(() => h.fm.agent('cleo')!.state === 'idle');

    await send(h, { type: 'content.channel.action', action: 'sync', channelId: ch });
    await until(() => (deny(), verdicts.filter(([k]) => k.startsWith('read ')).length === 4 && h.fm.content.statsOf('dQw4w9WgXcQ').some((s) => s.views === 77)));
    const read = Object.fromEntries(verdicts.filter(([k]) => k.startsWith('read ')).map(([k, v]) => [k.slice(5), v]));
    expect(read).toEqual({ studio: 'allow', click: 'allow', evil: 'deny', upload: 'deny' });
    expect(h.fm.content.channel(ch)).toMatchObject({ connected: true, statsSource: 'studio' });

    await until(() => h.fm.agent('vega')!.state === 'idle');
    await send(h, { type: 'user.message', to: 'vega', text: 'how are we doing?' });
    await until(() => (deny(), verdicts.filter(([k]) => k.startsWith('chat ')).length === 4));
    expect(Object.fromEntries(verdicts.filter(([k]) => k.startsWith('chat ')).map(([k, v]) => [k.slice(5), v]))).toEqual({ studio: 'deny', click: 'deny', evil: 'deny', upload: 'deny' });
  }, 40_000);
});

describe('watching a cut in game (content.video.preview)', () => {
  it('shows the whole cut as a flipbook and acks with its media id; refuses a video without a cut', async () => {
    const h = start();
    const ch = await addChannel(h);
    const idea = await pitch(h, ch);
    expect((await send(h, { type: 'content.video.preview', videoId: idea })).error).toMatch(/has no cut yet/);
    expect((await send(h, { type: 'content.video.preview', videoId: 'v99' })).error).toMatch(/v99/);

    const id = await toReview(h, ch);
    const show = vi.spyOn(h.fm, 'showMedia').mockResolvedValue({ id: 'm7', kind: 'video', agentId: 'reel', name: 'cut.mp4', at: T0, mime: 'image/jpeg', frames: ['x'] });
    const ack = await send(h, { type: 'content.video.preview', videoId: id });
    expect(ack).toMatchObject({ ok: true, result: { videoId: id, mediaId: 'm7' } });
    expect(show).toHaveBeenCalledWith('reel', h.fm.content.video(id)!.filePath, expect.stringContaining('the cut'), { maxSeconds: 180, maxFrames: 240 });
  });
});

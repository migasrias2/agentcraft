// The Content Room agents' tools: who gets which, validation, what the agents read back, and the
// room rules (they talk to each other and to the user, never to the coding team or the Social Desk).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { addChannel, cut, fakes, pitch, send, startContent, T0, text, toReview, toolsOf } from './contenthelpers.js';

const cleanup: Array<() => void | Promise<void>> = [];
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'], now: T0 });
});
afterEach(async () => {
  vi.useRealTimers();
  for (const f of cleanup.splice(0).reverse()) await f();
});
const start = (...a: [ReturnType<typeof fakes>?]) => startContent(cleanup, ...a);

describe('tool sets', () => {
  it('every content agent gets the common tools and its own; none gets coding-team tools or the others\' tools', () => {
    const h = start();
    const common = ['list_channels', 'list_videos', 'get_video', 'comment_video', 'study_video', 'scan_channel', 'get_analytics', 'read_playbook', 'send_message', 'ask_user', 'show_media', 'hand_over', 'read_memory', 'write_memory'];
    const own = {
      iris: ['pitch_video', 'update_video', 'update_playbook', 'approve_idea'],
      reel: ['start_production', 'update_video', 'attach_cut'],
      vega: ['sync_analytics', 'update_playbook', 'write_post_mortem'],
      cleo: ['mark_published', 'mark_failed'],
    } as const;
    const all = new Set(Object.values(own).flat());
    for (const [agent, mine] of Object.entries(own)) {
      const names = Object.keys(toolsOf(h, agent));
      expect(names, agent).toEqual(expect.arrayContaining([...common, ...mine]));
      for (const other of all) if (!mine.includes(other as never)) expect(names, `${agent} must not have ${other}`).not.toContain(other);
      for (const banned of ['update_task', 'list_tasks', 'create_task', 'request_merge', 'create_post', 'list_posts']) expect(names).not.toContain(banned);
    }
    expect(Object.keys(toolsOf(h, 'kit'))).not.toEqual(expect.arrayContaining(['pitch_video']));
    expect(Object.keys(toolsOf(h, 'nova'))).not.toContain('pitch_video');
    expect(Object.keys(toolsOf(h, 'nova'))).toContain('create_post');
  });
});

describe('room rules', () => {
  it('the content team talks to each other and the user; not to the coding team, the Social Desk or "all"', async () => {
    const h = start();
    const iris = toolsOf(h, 'iris');
    expect((await iris.send_message!.handler({ to: 'reel', text: 'new idea approved' })).isError).toBeUndefined();
    expect((await iris.send_message!.handler({ to: 'user', text: 'hi' })).isError).toBeUndefined();
    expect(h.fm.bus.inbox('reel').map((m) => m.text)).toEqual(['new idea approved']);
    expect(text(await iris.send_message!.handler({ to: 'all', text: 'x' }))).toMatch(/not part of the Content Room/);
    expect(text(await iris.send_message!.handler({ to: 'kit', text: 'x' }))).toMatch(/Kit is on the coding team, not in your room/);
    expect(text(await iris.send_message!.handler({ to: 'nova', text: 'x' }))).toMatch(/Nova is in the Social Desk, not in your room/);
    expect(text(await iris.send_message!.handler({ to: 'marlow', text: 'x' }))).toMatch(/coding team/);
    expect(text(await iris.send_message!.handler({ to: 'zed', text: 'x' }))).toMatch(/Team: .*iris.*reel.*vega.*cleo/s);
    // and the other way round
    expect(text(await toolsOf(h, 'kit').send_message!.handler({ to: 'iris', text: 'x' }))).toMatch(/Iris is in the Content Room, not in your room/);
    expect(text(await toolsOf(h, 'nova').send_message!.handler({ to: 'iris', text: 'x' }))).toMatch(/Iris is in the Content Room/);
    expect(text(await toolsOf(h, 'nova').send_message!.handler({ to: 'sable', text: 'x' }))).not.toMatch(/Error/);
  });

  it('coding tasks never go to them: create_task, reassign and spawn refuse; the backend team excludes them', async () => {
    const h = startContent(cleanup, fakes(), ['--backend', 'claude', '--workers', 'kit,juniper']);
    const lead = Object.fromEntries(
      (await import('../src/agents/tools.js')).agentTools(h.fm, 'marlow', 'lead', { onReview() {}, onChangesRequested() {}, onTasksChanged() {}, onMergeRequested() {}, onWaiting() {} }).map((t) => [t.name, t]),
    );
    const r = await lead.create_task!.handler({ title: 'x', description: 'y', assignee: 'reel' });
    expect(r.isError).toBe(true);
    expect(text(r)).toMatch(/Content Room/);
    expect(h.fm.tasks.list()).toHaveLength(0);
    const t = h.fm.tasks.create({ title: 'a', description: '', deps: [], createdBy: 'marlow' });
    expect(() => h.fm.taskAction(t.id, 'reassign', 'vega')).toThrow(/Content Room/);
    await expect(h.fm.agentAction('cleo', 'spawn', t.id)).rejects.toThrow(/Content Room/);
    const backend = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: (() => ({})) as never, skipAuthCheck: true });
    expect(backend.team).toEqual(['kit', 'juniper']);
  });
});

describe('what the agents read', () => {
  it('list_channels / list_videos / get_video show the pipeline, the thread and the post-mortems', async () => {
    const h = start();
    const iris = toolsOf(h, 'iris');
    expect(text(await iris.list_channels!.handler({}))).toMatch(/No channels yet/);
    const ch = await addChannel(h, { brief: 'Warm, no hype.', niche: 'tiny robots' });
    const listed = text(await iris.list_channels!.handler({}));
    expect(listed).toContain(`${ch} "Tiny Robots" https://www.youtube.com/@tinyrobots · autopilot off · 3 posts/week · analytics public only`);
    expect(listed).toContain('brief: Warm, no hype.');
    const a = await pitch(h, ch, { title: 'First' });
    const b = await toReview(h, ch, { title: 'Second', hook: 'Look at this' });
    h.fm.content.comment(a, 'sharper please', 'user');
    expect(text(await iris.list_videos!.handler({}))).toMatch(new RegExp(`${a} \\[idea\\] short · "First".*1 comment[\\s\\S]*${b} \\[review\\] short · "Second"`));
    expect(text(await iris.list_videos!.handler({ status: 'review' }))).not.toContain('First');
    expect(text(await iris.list_videos!.handler({ channelId: 'nope' }))).toBe('No videos match.');
    const full = text(await iris.get_video!.handler({ id: a }));
    expect(full).toContain('pitch: Copies r1 pacing.');
    expect(full).toMatch(/comments:\n  \[.*\] Alex: sharper please/);
    expect(text(await iris.get_video!.handler({ id: b }))).toMatch(/cut: .*\.mp4/);
    expect(text(await iris.get_video!.handler({ id: 'v99' }))).toMatch(/no video "v99"/);
  });

  it('read_playbook / get_analytics with several channels need to know which one', async () => {
    const h = start();
    const a = await addChannel(h, { name: 'One' });
    await addChannel(h, { name: 'Two', url: 'https://www.youtube.com/@two' });
    const vega = toolsOf(h, 'vega');
    expect(text(await vega.read_playbook!.handler({}))).toMatch(/which channel\? ch1 \(One\), ch2 \(Two\)/);
    expect(text(await vega.get_analytics!.handler({}))).toMatch(/which channel/);
    expect(text(await vega.get_analytics!.handler({ channelId: a }))).toMatch(/One \(ch1\) — public numbers only[\s\S]*no channel stats yet/);
    expect(text(await vega.read_playbook!.handler({ channelId: 'ch9' }))).toMatch(/no channel "ch9"/);
  });

  it('with no channel at all they are told the user adds one', async () => {
    const h = start();
    expect(text(await toolsOf(h, 'iris').pitch_video!.handler({ title: 'x', format: 'short', hook: 'h', pitch: 'p' }))).toMatch(/there is no channel yet/);
  });
});

describe('iris: pitch_video / update_video / approve_idea', () => {
  it('validates and stays within the statuses an idea can be edited in', async () => {
    const h = start();
    const ch = await addChannel(h);
    const iris = toolsOf(h, 'iris');
    const base = { title: 'T', format: 'short', hook: 'h', pitch: 'p' };
    expect(text(await iris.pitch_video!.handler({ ...base, title: 'x'.repeat(101) }))).toMatch(/101 characters/);
    expect(text(await iris.pitch_video!.handler({ ...base, pitch: 'p'.repeat(1501) }))).toMatch(/limit is 1500/);
    expect(text(await iris.pitch_video!.handler({ ...base, refIds: ['r1'] }))).toMatch(/no reference "r1"/);
    expect(text(await iris.pitch_video!.handler({ ...base, channelId: 'zz' }))).toMatch(/no channel "zz"/);
    expect(h.fm.content.videos()).toHaveLength(0);
    const id = await pitch(h, ch);
    expect(text(await iris.update_video!.handler({ id, title: 'Better title', hook: 'Sharper hook' }))).toMatch(/Updated v1 \[idea\].*Better title/);
    expect(h.fm.content.video(id)).toMatchObject({ title: 'Better title', hook: 'Sharper hook', status: 'idea' });
    // once it is in production the idea belongs to Reel
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve' });
    h.fm.content.startProduction(id);
    expect(text(await iris.update_video!.handler({ id, title: 'No' }))).toMatch(/producing: you can change it only while it is idea \/ approved \/ review/);
    expect((await toolsOf(h, 'reel').update_video!.handler({ id, script: 'Scene 1' })).isError).toBeUndefined();
    // iris cannot start production or attach cuts: she has no such tools
    expect(iris.start_production).toBeUndefined();
  });

  it('studies cited references and links them', async () => {
    const h = start();
    const ch = await addChannel(h);
    const reel = toolsOf(h, 'reel');
    await reel.study_video!.handler({ source: 'https://www.youtube.com/watch?v=abcdefghijk' });
    const id = await pitch(h, ch, { refIds: ['r1', 'r1'] });
    expect(h.fm.content.video(id)!.refIds).toEqual(['r1']);
  });
});

describe('study_video / scan_channel', () => {
  it('returns the breakdown and the contact sheets to Read, with the options passed on', async () => {
    const f = fakes();
    const h = start(f);
    const r = await toolsOf(h, 'reel').study_video!.handler({ source: 'https://www.youtube.com/watch?v=abcdefghijk', fps: 4, maxFrames: 100, start: 0, end: 5 });
    expect(r.isError).toBeUndefined();
    const out = text(r);
    expect(out).toContain('Studied r1');
    expect(out).toContain('3 frames, 2 contact sheets, transcript');
    expect(out).toContain('40 cuts per minute');
    expect(out).toContain('Read the contact sheets (sheets/*.jpg) with your Read tool to see the frames');
    expect(out).toContain('refs/r1/sheets/sheet01.jpg');
    expect(out).toContain('refs/r1/sheets/sheet02.jpg');
    expect(out).toContain('refIds ["r1"]');
    expect(f.study.studyVideo.mock.calls[0]![2]).toEqual({ fps: 4, maxFrames: 100, start: 0, end: 5 });
    expect(f.study.renderBreakdown).toHaveBeenCalledTimes(1);
    expect(h.fm.content.ref('r1')).toMatchObject({ studiedBy: 'reel' });
  });

  it('a missing tool or a bad link is an error the agent can read; very long breakdowns are cut', async () => {
    const f = fakes();
    const h = start(f);
    f.study.studyVideo.mockRejectedValueOnce(new Error('yt-dlp is not installed (brew install yt-dlp)'));
    const bad = await toolsOf(h, 'reel').study_video!.handler({ source: 'https://www.youtube.com/watch?v=abcdefghijk' });
    expect(bad.isError).toBe(true);
    expect(text(bad)).toMatch(/brew install yt-dlp/);
    expect(text(await toolsOf(h, 'reel').study_video!.handler({ source: '  ' }))).toMatch(/needs a URL or a file path/);
    f.study.renderBreakdown.mockReturnValueOnce('B'.repeat(20_000));
    const long = text(await toolsOf(h, 'reel').study_video!.handler({ source: 'https://www.youtube.com/watch?v=abcdefghijk' }));
    expect(long.length).toBeLessThan(10_000);
  });

  it('scan_channel lists the most viewed videos', async () => {
    const f = fakes();
    const h = start(f);
    const out = text(await toolsOf(h, 'iris').scan_channel!.handler({ url: '@rival', limit: 5 }));
    expect(f.study.scanChannel).toHaveBeenCalledWith('https://www.youtube.com/@rival', 5);
    expect(out).toContain('Rival · 90,000 subscribers');
    expect(out).toContain('2,000,000 views · short 30 s · "Top video" https://www.youtube.com/watch?v=aaaaaaaaaaa');
    expect(text(await toolsOf(h, 'iris').scan_channel!.handler({ url: 'https://vimeo.com/x' }))).toMatch(/not a youtube\.com link/);
  });
});

describe('reel: start_production / attach_cut', () => {
  it('checks the status and the files', async () => {
    const h = start();
    const ch = await addChannel(h);
    const reel = toolsOf(h, 'reel');
    const id = await pitch(h, ch);
    expect(text(await reel.start_production!.handler({ id }))).toMatch(/only an approved idea.*not approved/);
    await send(h, { type: 'content.video.action', videoId: id, action: 'approve' });
    expect(text(await reel.start_production!.handler({ id }))).toMatch(/Production started: v1 \[producing\]/);
    expect(text(await reel.attach_cut!.handler({ id, filePath: '/nope/x.mp4' }))).toMatch(/no file for the cut/);
    expect(text(await reel.attach_cut!.handler({ id, filePath: cut(h, 'notes.txt') }))).toMatch(/must be one of .*mp4/);
    const ok = text(await reel.attach_cut!.handler({ id, filePath: cut(h, 'good.mp4') }));
    expect(ok).toMatch(/Cut attached: v1 \[review\].*waits for the user's review/);
    expect(h.fm.content.video(id)!.status).toBe('review');
  });
});

describe('vega: sync_analytics / write_post_mortem', () => {
  it('syncs and reports per channel; post-mortems are for published videos only', async () => {
    const f = fakes();
    const h = start(f);
    const ch = await addChannel(h);
    const vega = toolsOf(h, 'vega');
    expect(text(await vega.sync_analytics!.handler({}))).toMatch(/Synced 1 channel, 0 videos\.\nTiny Robots: public numbers, 0 videos updated/);
    expect(text(await vega.sync_analytics!.handler({ channelId: 'ch9' }))).toMatch(/no channel/);
    const id = await toReview(h, ch);
    expect(text(await vega.write_post_mortem!.handler({ videoId: id, horizon: '48h', text: 'x' }))).toMatch(/post-mortems are for published videos/);
    expect((await vega.write_post_mortem!.handler({ videoId: id, horizon: '1d', text: 'x' })).isError ?? false).toBeDefined();
  });
});

describe('cleo: mark_published / mark_failed', () => {
  it('only report on a video that is being published', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    const cleo = toolsOf(h, 'cleo');
    expect(text(await cleo.mark_published!.handler({ id, url: 'https://youtu.be/dQw4w9WgXcQ' }))).toMatch(/is review, not being published/);
    expect(text(await cleo.mark_failed!.handler({ id, error: 'x' }))).toMatch(/not being published/);
    await send(h, { type: 'content.video.action', videoId: id, action: 'publish_now' });
    expect(h.fm.content.video(id)!.status).toBe('publishing');
    expect(text(await cleo.mark_published!.handler({ id, url: 'not a link' }))).toMatch(/cannot find a YouTube video id/);
    expect(h.fm.content.video(id)!.status).toBe('publishing');
    expect(text(await cleo.mark_published!.handler({ id, url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' }))).toMatch(/Marked v1 as published/);
    expect(text(await cleo.mark_failed!.handler({ id, error: 'x' }))).toMatch(/not being published/);
  });

  it('mark_failed keeps what she saw', async () => {
    const h = start();
    const ch = await addChannel(h);
    const id = await toReview(h, ch);
    await send(h, { type: 'content.video.action', videoId: id, action: 'publish_now' });
    await toolsOf(h, 'cleo').mark_failed!.handler({ id, error: 'Google asked for a captcha on studio.youtube.com' });
    expect(h.fm.content.video(id)).toMatchObject({ status: 'failed', error: 'Google asked for a captcha on studio.youtube.com' });
  });
});

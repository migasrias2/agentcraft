// Shared by the Content Room tests: a Foreman with fake yt-dlp/YouTube collaborators, a recording
// runner, and small builders.
import fs from 'node:fs';
import path from 'node:path';
import { vi } from 'vitest';
import type { ContentExternals, ContentRunner } from '../src/content.js';
import { agentTools, type AgentTool } from '../src/agents/tools.js';
import type { ClientMessage, ContentStat, Outbound } from '../src/protocol.js';
import type { ChannelScan, StudyResult } from '../src/videostudy.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

export const T0 = Date.UTC(2026, 9, 13, 10, 0, 0); // Tue 13 Oct 2026 11:00 in Lisbon (WEST, UTC+1)
export const HOUR = 3_600_000;
export const DAY = 24 * HOUR;

export const hooks = { onReview() {}, onChangesRequested() {}, onTasksChanged() {}, onMergeRequested() {}, onWaiting() {} };
export const text = (r: { content: Array<{ text: string }> }) => r.content.map((c) => c.text).join('\n');

export interface Fakes {
  study: { studyVideo: ReturnType<typeof vi.fn>; renderBreakdown: ReturnType<typeof vi.fn>; scanChannel: ReturnType<typeof vi.fn> };
  oauth: { connected: string[]; channelStats: ReturnType<typeof vi.fn>; videoStats: ReturnType<typeof vi.fn> };
  publicStats: ReturnType<typeof vi.fn>;
  ext: ContentExternals;
}

/** A fake study that writes 3 frames and 2 contact sheets into the folder it is given. */
export function fakes(): Fakes {
  const study = {
    studyVideo: vi.fn(async (source: string, outDir: string): Promise<StudyResult> => {
      fs.mkdirSync(path.join(outDir, 'frames'), { recursive: true });
      fs.mkdirSync(path.join(outDir, 'sheets'), { recursive: true });
      const frames = [0, 1, 2].map((i) => ({ file: path.join(outDir, 'frames', `f000${i + 1}_00m0${i}.00s.jpg`), t: i }));
      for (const f of frames) fs.writeFileSync(f.file, 'jpg');
      const sheets = [1, 2].map((i) => path.join(outDir, 'sheets', `sheet0${i}.jpg`));
      for (const s of sheets) fs.writeFileSync(s, 'jpg');
      fs.writeFileSync(path.join(outDir, 'transcript.txt'), '[00:00] hello');
      return { dir: outDir, title: `Study of ${source}`, channel: 'Rival', durationSec: 42, views: 1_200_000, likes: 50_000, frames, sheets, sceneCuts: [1.2, 2.4], transcriptFile: path.join(outDir, 'transcript.txt'), pacing: { cutsPerMinute: 40, avgShotSec: 1.5 } };
    }),
    renderBreakdown: vi.fn(() => '# Breakdown\n40 cuts per minute, avg shot 1.5 s'),
    scanChannel: vi.fn(async (): Promise<ChannelScan> => ({ channel: 'Rival', channelId: 'UCrival00000000000000000', subscriberCount: 90_000, videos: [{ youtubeId: 'aaaaaaaaaaa', title: 'Top video', url: 'https://www.youtube.com/watch?v=aaaaaaaaaaa', views: 2_000_000, durationSec: 30 }] })),
  };
  const oauth = {
    connected: [] as string[],
    channelStats: vi.fn(async (_id: string) => ({ youtubeChannelId: _id, at: T0, subscribers: 1280, totalViews: 90_000, videoCount: 14, views28d: 31_000, watchMinutes28d: 9800, subsGained28d: 210, daily: [{ date: '2026-10-12', views: 1000 }] })),
    videoStats: vi.fn(async (_id: string, ids: string[]): Promise<ContentStat[]> => ids.map((videoId) => ({ videoId, at: Date.now(), views: 5400, likes: 400, avgViewPct: 63.5, ctr: 0.062, retention: Array.from({ length: 21 }, (_, i) => 1 - i * 0.04) }))),
  };
  const publicStats = vi.fn(async (input: { channelUrl?: string; videoIds?: string[] }) => ({
    stats: (input.videoIds ?? []).map((videoId) => ({ videoId, at: Date.now(), views: 777, likes: 7 })),
    ...(input.channelUrl ? { channel: { name: 'Mine', youtubeChannelId: 'UCmine00000000000000000', subscribers: 321 } } : {}),
  }));
  return {
    study,
    oauth,
    publicStats,
    ext: {
      study,
      youtube: { connectedChannels: () => oauth.connected, channelStats: oauth.channelStats, videoStats: oauth.videoStats },
      publicStats,
      // the Screening Room is covered by media.test; here a recorder
    },
  };
}

export interface Rec {
  wakes: Array<{ id: string; prompt: string; kind?: string }>;
  /** can Vega read YouTube Studio in Chrome? */
  studio: boolean;
  published: Array<{ id: string; channel: string }>;
  resolvers: Array<(o: { ok: boolean; error?: string }) => void>;
  off: Set<string>;
  runner: ContentRunner;
}

export function recorder(): Rec {
  const rec: Rec = {
    wakes: [],
    published: [],
    resolvers: [],
    off: new Set(),
    studio: false,
    runner: {
      canRun: (id) => !rec.off.has(id),
      wake: (id, prompt, o) => void rec.wakes.push({ id, prompt, ...(o?.kind ? { kind: o.kind } : {}) }),
      studioAvailable: () => rec.studio,
      publish: (video, channel) => {
        rec.published.push({ id: video.id, channel: channel.id });
        return new Promise((r) => rec.resolvers.push(r));
      },
    },
  };
  return rec;
}

export function startContent(cleanup: Array<() => void | Promise<void>>, f: Fakes = fakes(), args: string[] = ['--backend', 'sim'], home = tempDir()): Harness & { f: Fakes; rec: Rec } {
  const h = makeForeman(home, args, { content: f.ext });
  cleanup.push(() => rmrf(home), () => h.fm.close());
  const rec = recorder();
  h.fm.content.setRunner(rec.runner);
  return { ...h, f, rec };
}

export function toolsOf(h: Harness, agent: string): Record<string, AgentTool> {
  return Object.fromEntries(agentTools(h.fm, agent, 'worker', hooks, undefined, h.fm.content.dir).map((t) => [t.name, t]));
}

export async function send(h: Harness, msg: Record<string, unknown>) {
  const out: Outbound[] = [];
  await h.fm.handle({ v: 1, id: 'x', ...msg } as ClientMessage, (m) => out.push(m));
  return out.find((m) => m.type === 'ack') as Extract<Outbound, { type: 'ack' }>;
}

/** An mp4 stand-in inside the content workspace. */
export function cut(h: Harness, name = 'cut.mp4'): string {
  const file = path.join(h.fm.content.dir, 'cuts', name);
  fs.writeFileSync(file, 'mp4');
  return file;
}

export async function addChannel(h: Harness, over: Record<string, unknown> = {}): Promise<string> {
  const ack = await send(h, { type: 'content.channel.action', action: 'add', name: 'Tiny Robots', url: 'https://www.youtube.com/@tinyrobots', niche: 'robots', postsPerWeek: 3, ...over });
  if (!ack.ok) throw new Error(ack.error);
  return (ack.result as { channelId: string }).channelId;
}

export async function pitch(h: Harness, channelId: string, over: Record<string, unknown> = {}): Promise<string> {
  const r = await toolsOf(h, 'iris').pitch_video!.handler({ channelId, title: 'Why robots never fall', format: 'short', hook: 'Kicked 40 times.', pitch: 'Copies r1 pacing.', ...over });
  if (r.isError) throw new Error(text(r));
  return /\b(v\d+)\b/.exec(text(r))![1]!;
}

/** pitch -> approve -> produce -> cut, ending in `review`. */
export async function toReview(h: Harness, channelId: string, over: Record<string, unknown> = {}): Promise<string> {
  const id = await pitch(h, channelId, over);
  await send(h, { type: 'content.video.action', videoId: id, action: 'approve' });
  h.fm.content.startProduction(id);
  h.fm.content.attachCut(id, cut(h, `${id}.mp4`));
  return id;
}

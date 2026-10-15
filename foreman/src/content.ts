// The Content Room: YouTube channels run by agents. Iris (showrunner) pitches video ideas, the user
// approves / rejects / comments on them, Reel (producer) studies reference videos frame by frame and
// makes the cut, the user approves the cut ONCE with a publish time, and at that time Cleo
// (publisher) uploads it in YouTube Studio through the user's own Chrome. Vega (analyst) syncs the
// channel analytics, writes a post-mortem 48 h and 7 d after each publish and keeps the playbook.
//
//   idea --approve--> approved --start_production--> producing --attach_cut--> review --approve_cut--> scheduled
//   scheduled --(time reached)--> publishing --mark_published--> published
//                                    |--mark_failed / turn ended--> failed --retry--> scheduled
//   reject: idea | approved | review -> rejected;   unschedule: scheduled -> review
//
// A channel on autopilot skips both gates: Iris approves her own ideas (approve_idea), a finished cut
// is scheduled at the next free slot of the channel's cadence, and Iris is woken whenever fewer than
// TARGET_QUEUE videos are upcoming.
//
// This module owns the state machine, the validation, the scheduler and the channel files
// (`<dir>/channels/<id>/playbook.md`, `<dir>/refs/<id>/...`, `<dir>/cuts/`). Everything that reaches
// outside (waking an agent, the publish turn, yt-dlp/ffmpeg, the YouTube API) is injected, so the
// scheduler and the state machine are testable without agents or network. A video is never published
// twice by accident: after a restart a 'publishing' video is marked failed ("check YouTube Studio
// before retrying") and never retried by itself.
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { z } from 'zod';
import type { Ctx } from './context.js';
import type { MessageBus } from './bus.js';
import type { ContentChannel, ContentChannelStats, ContentRef, ContentStat, ContentVideo, ContentVideoStatus } from './protocol.js';
import { formatNow, formatWhen, parseWhen, SOCIAL_TZ } from './social.js';
import { truncate } from './util/text.js';
// the studio/YouTube modules are injected (ContentExternals); only their shapes are needed here
import type { ChannelScan, StudyOptions, StudyResult } from './videostudy.js';

export class ContentError extends Error {}

const nonneg = z.number().finite().nonnegative();
/** What Vega reads in YouTube Studio (`record_stats`). Anything out of range is refused, not clamped. */
export const RecordStatsInput = z.object({
  channelId: z.string().optional(),
  channel: z
    .object({
      subscribers: nonneg.optional(),
      views28d: nonneg.optional(),
      watchMinutes28d: nonneg.optional(),
      subsGained28d: z.number().finite().optional(),
      daily: z
        .array(z.object({ date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'date must be YYYY-MM-DD'), views: nonneg, subsGained: z.number().finite().optional(), watchMinutes: nonneg.optional() }))
        .max(31)
        .optional(),
    })
    .optional(),
  videos: z
    .array(
      z.object({
        youtubeVideoId: z.string().optional(),
        url: z.string().optional(),
        views: nonneg,
        likes: nonneg.optional(),
        comments: nonneg.optional(),
        avgViewDurationSec: nonneg.optional(),
        avgViewPct: z.number().min(0).max(100).optional(),
        watchMinutes: nonneg.optional(),
        impressions: nonneg.optional(),
        ctr: z.number().min(0).max(1).optional(),
        subsGained: z.number().finite().optional(),
        stayedToWatchPct: z.number().min(0).max(100).optional(),
        swipeAwayPct: z.number().min(0).max(100).optional(),
        retention: z.array(z.number().min(0).max(1)).length(21).optional(),
      }),
    )
    .max(100),
});
export type RecordStatsInput = z.infer<typeof RecordStatsInput>;

export const TITLE_MAX = 100;
export const DESCRIPTION_MAX = 5000;
export const TAGS_MAX = 500;
export const PITCH_MAX = 1500;
export const SCRIPT_MAX = 20_000;
export const BRIEF_MAX = 2000;
export const PLAYBOOK_MAX = 20_000;
export const POST_MORTEM_MAX = 4000;
/** "Watch" in the Pipeline: a whole Short (YouTube allows 3 min) as a flipbook, ~4 fps for a 60 s one */
export const PREVIEW_MAX_SECONDS = 180;
export const PREVIEW_MAX_FRAMES = 240;
export const COMMENT_MAX = 2000;
export const COMMENTS_KEEP = 50;
/** finished (published/rejected) videos kept besides every open one */
export const CONTENT_KEEP_FINISHED = 200;
export const REFS_KEEP = 50;
/** stat snapshots kept per own video */
export const STATS_HISTORY = 30;
export const CONTENT_TICK_MS = 30_000;
export const SYNC_MS = 6 * 3_600_000;
export const DEFAULT_POSTS_PER_WEEK = 35;
export const MAX_POSTS_PER_WEEK = 70;
/** autopilot keeps at least this many upcoming videos in the pipeline (more on a fast cadence: see targetQueue) */
export const TARGET_QUEUE = 3;
/** slots of one day spread between these hours (SOCIAL_TZ) */
export const DAY_START_HOUR = 9;
export const DAY_END_HOUR = 22;
/** a Chrome read of YouTube Studio stays 'fresh' (channel connected) this long */
export const STUDIO_FRESH_MS = 24 * 3_600_000;
/** publishing slot of a channel's cadence, SOCIAL_TZ wall-clock */
export const SLOT_HOUR = 18;
export const INTERRUPTED = 'interrupted — check YouTube Studio before retrying';
/** a video due this long ago is not published late (the Foreman was down): it goes back to review */
export const MISSED_SLOT_MS = 2 * 3_600_000;
/** 'publishing' with no report after this long: the publisher never got to it (or never reported) */
export const PUBLISH_DEADLINE_MS = 60 * 60_000;
/** a video in production this long without a cut is reported to the user (once) */
export const STALE_PRODUCTION_MS = 24 * 3_600_000;
/** how long before an agent job (post-mortem, top-up) may be asked for again while it is still missing */
const REASK_MS = 12 * 3_600_000;
const HORIZONS = [
  { horizon: '48h', ms: 48 * 3_600_000 },
  { horizon: '7d', ms: 7 * 24 * 3_600_000 },
] as const;

export const IRIS = 'iris';
export const REEL = 'reel';
export const VEGA = 'vega';
export const CLEO = 'cleo';

/** statuses a video can still leave (shown in the pipeline, never pruned) */
const OPEN: ReadonlySet<ContentVideoStatus> = new Set(['idea', 'approved', 'producing', 'review', 'scheduled', 'publishing', 'failed']);
/** statuses that count as "coming up" for the autopilot queue */
const UPCOMING: ReadonlySet<ContentVideoStatus> = new Set(['idea', 'approved', 'producing', 'review', 'scheduled', 'publishing']);
/** statuses whose text (title, hook, script...) may still be changed */
const EDITABLE: ReadonlySet<ContentVideoStatus> = new Set(['idea', 'approved', 'producing', 'review']);

// ---- state shape ------------------------------------------------------------------------------

export interface ContentData {
  channels: ContentChannel[];
  videos: ContentVideo[];
  refs: ContentRef[];
  /** snapshots: up to STATS_HISTORY per own video (oldest first), the latest one per reference */
  stats: ContentStat[];
  channelStats: ContentChannelStats[];
  /** when the last analytics sync finished (epoch ms) */
  lastSyncAt?: number;
  /** when Vega last read each channel's numbers from YouTube Studio in Chrome (epoch ms) */
  studioReads?: Record<string, number>;
  /** sha256 of each scheduled cut at the moment it was approved/scheduled: publishing refuses a changed file */
  cutHashes?: Record<string, string>;
}

export const emptyContent = (): ContentData => ({ channels: [], videos: [], refs: [], stats: [], channelStats: [] });

// ---- injected collaborators -------------------------------------------------------------------

/** The agents' side, set by the team backend. */
export interface ContentRunner {
  /** is the agent on shift and not paused? */
  canRun(agentId: string): boolean;
  /** start a 'content' job for the agent (queued behind the one it is in) */
  wake(agentId: string, prompt: string, opts?: { sessionKey?: string; kind?: 'content' | 'analytics' }): void;
  /** can an agent read YouTube Studio through Claude in Chrome (the Foreman runs with --chrome and the Claude login)? */
  studioAvailable?(): boolean;
  /** Cleo's publish turn; resolves when it is over (the video may already be published or failed) */
  publish(video: ContentVideo, channel: ContentChannel): Promise<{ ok: boolean; error?: string }>;
}

/** videostudy.ts */
export interface StudyApi {
  studyVideo(source: string, outDir: string, opts?: StudyOptions): Promise<StudyResult>;
  renderBreakdown(r: StudyResult): string;
  scanChannel(url: string, limit?: number): Promise<ChannelScan>;
}

/** youtube.ts */
export interface AnalyticsApi {
  connectedChannels(): string[];
  channelStats(youtubeChannelId: string, days?: number): Promise<Omit<ContentChannelStats, 'channelId'> & { youtubeChannelId?: string }>;
  videoStats(youtubeChannelId: string, videoIds: string[]): Promise<ContentStat[]>;
}

export interface PublicStatsLike {
  stats: ContentStat[];
  channel?: { name?: string; youtubeChannelId?: string; subscribers?: number };
}

export interface ContentExternals {
  study?: StudyApi;
  /** OAuth analytics (undefined: public numbers only) */
  youtube?: AnalyticsApi;
  /** views/likes/comments without OAuth (youtube.ts `publicStats`): a channel page and/or public video ids */
  publicStats?(input: { channelUrl?: string; videoIds?: string[]; limit?: number }): Promise<PublicStatsLike>;
  /** show a local image on the Screening Room screen */
  showMedia?(agentId: string, file: string, caption?: string, limits?: { maxSeconds?: number; maxFrames?: number }): Promise<unknown>;
}

export interface ContentDeps extends ContentExternals {
  ctx: Ctx;
  bus: MessageBus;
  /** display name of an agent id / "user" */
  names(id: string): string;
  notify(level: 'info' | 'warn' | 'need_user', text: string): void;
  /** the plain workspace directory of the content agents (created here) */
  dir: string;
  tickMs?: number;
  syncMs?: number;
}

// ---- validation -------------------------------------------------------------------------------

const BRACKETS = /[<>]/;

export function validateTitle(title: string): string {
  const t = title.replace(/\s+/g, ' ').trim();
  if (!t) throw new ContentError('the title is empty');
  const n = [...t].length;
  if (n > TITLE_MAX) throw new ContentError(`the title is ${n} characters, YouTube allows ${TITLE_MAX}: shorten it by ${n - TITLE_MAX}`);
  if (BRACKETS.test(t)) throw new ContentError('YouTube rejects "<" and ">" in titles: remove them');
  return t;
}

export function validateDescription(text: string): string {
  const t = text.replace(/\r\n/g, '\n').trim();
  if ([...t].length > DESCRIPTION_MAX) throw new ContentError(`the description is ${[...t].length} characters, YouTube allows ${DESCRIPTION_MAX}`);
  if (BRACKETS.test(t)) throw new ContentError('YouTube rejects "<" and ">" in descriptions: remove them');
  return t;
}

/** Trimmed, de-duplicated tags; all together (with their commas) at most TAGS_MAX characters. */
export function validateTags(tags: string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const raw of tags) {
    const t = raw.replace(/^#/, '').replace(/\s+/g, ' ').trim();
    if (!t || seen.has(t.toLowerCase())) continue;
    if (BRACKETS.test(t) || t.includes(',')) throw new ContentError(`tag "${t}" has a comma or "<" ">": YouTube tags cannot`);
    seen.add(t.toLowerCase());
    out.push(t);
  }
  const total = out.reduce((n, t) => n + [...t].length, 0) + Math.max(0, out.length - 1);
  if (total > TAGS_MAX) throw new ContentError(`the tags are ${total} characters in total, YouTube allows ${TAGS_MAX}: drop some`);
  return out;
}

function limited(label: string, text: string, max: number): string {
  const t = text.replace(/\r\n/g, '\n').trim();
  if ([...t].length > max) throw new ContentError(`${label} is ${[...t].length} characters, the limit is ${max}`);
  return t;
}

export function validatePostsPerWeek(n: number): number {
  if (!Number.isInteger(n) || n < 1 || n > MAX_POSTS_PER_WEEK) throw new ContentError(`postsPerWeek must be a whole number from 1 to ${MAX_POSTS_PER_WEEK}`);
  return n;
}

/** A channel URL / handle -> https://www.youtube.com/@handle (or /channel/UC...). */
export function normalizeChannelUrl(input: string): { url: string; youtubeId?: string } {
  const raw = input.trim();
  if (/^@[\w.-]+$/.test(raw)) return { url: `https://www.youtube.com/${raw}` };
  let u: URL;
  try {
    u = new URL(/^[a-z][a-z0-9+.-]*:/i.test(raw) ? raw : `https://${raw}`);
  } catch {
    throw new ContentError(`"${input}" is not a YouTube channel link: use https://www.youtube.com/@handle`);
  }
  if (!/(^|\.)youtube\.com$/i.test(u.hostname)) throw new ContentError(`"${input}" is not a youtube.com link: use https://www.youtube.com/@handle`);
  const id = /^\/channel\/(UC[\w-]{20,})/.exec(u.pathname)?.[1];
  const seg = u.pathname.split('/').filter(Boolean)[0] ?? '';
  const url = id ? `https://www.youtube.com/channel/${id}` : seg.startsWith('@') ? `https://www.youtube.com/${seg}` : `https://www.youtube.com${u.pathname.replace(/\/+$/, '')}`;
  return { url, ...(id ? { youtubeId: id } : {}) };
}

/** The 11-char video id in a youtube.com / youtu.be / Studio link, or a bare id. */
export function parseVideoId(input: string): string | undefined {
  const s = input.trim();
  if (/^[\w-]{11}$/.test(s)) return s;
  try {
    const u = new URL(/^[a-z][a-z0-9+.-]*:/i.test(s) ? s : `https://${s}`);
    const v = u.searchParams.get('v');
    if (v && /^[\w-]{11}$/.test(v)) return v;
    const m = /^\/(?:shorts|embed|live|video|v)\/([\w-]{11})(?:\/|$)/.exec(u.pathname) ?? (u.hostname === 'youtu.be' ? /^\/([\w-]{11})(?:\/|$)/.exec(u.pathname) : null);
    return m?.[1];
  } catch {
    return undefined;
  }
}

/** The canonical link of a video Cleo reports; only youtube.com / youtu.be links (or a bare id) are accepted. */
export function canonicalVideoUrl(input: string, format: 'short' | 'long'): { url: string; id: string } {
  const s = input.trim();
  let id: string | undefined;
  if (/^[\w-]{11}$/.test(s)) id = s;
  else {
    let host = '';
    try {
      host = new URL(/^[a-z][a-z0-9+.-]*:/i.test(s) ? s : `https://${s}`).hostname;
    } catch {
      /* no id below */
    }
    if (host && !/(^|\.)(youtube\.com|youtu\.be)$/i.test(host)) throw new ContentError(`"${truncate(s, 120)}" is not a youtube.com / youtu.be link: send the link to the live video`);
    id = parseVideoId(s);
  }
  if (!id) throw new ContentError(`cannot find a YouTube video id in "${truncate(s, 120)}": send the video link (https://www.youtube.com/watch?v=... or https://youtu.be/... or /shorts/...)`);
  return { id, url: format === 'short' ? `https://www.youtube.com/shorts/${id}` : `https://www.youtube.com/watch?v=${id}` };
}

/** sha256 of a file (read in chunks: cuts can be large). */
export function hashFile(file: string): string {
  const h = createHash('sha256');
  const fd = fs.openSync(file, 'r');
  try {
    const buf = Buffer.alloc(1 << 20);
    for (let n = fs.readSync(fd, buf, 0, buf.length, null); n > 0; n = fs.readSync(fd, buf, 0, buf.length, null)) h.update(buf.subarray(0, n));
  } finally {
    fs.closeSync(fd);
  }
  return h.digest('hex');
}

// ---- time: the channel's publishing slots -------------------------------------------------------

/** The SOCIAL_TZ calendar date (y, m, d) and weekday (0 = Monday) of an instant. */
function lisbonDay(ts: number): { y: number; m: number; d: number; wd: number } {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: SOCIAL_TZ, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(ts)).map((x) => [x.type, x.value]));
  const y = +p.year!;
  const m = +p.month!;
  const d = +p.day!;
  return { y, m, d, wd: (new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7 };
}

const pad = (n: number) => String(n).padStart(2, '0');

/** How many posts the cadence puts on each weekday (0 = Monday): spread as evenly as whole posts allow. */
export function postsPerDay(postsPerWeek: number): number[] {
  const n = Math.max(1, Math.min(MAX_POSTS_PER_WEEK, Math.trunc(postsPerWeek)));
  return Array.from({ length: 7 }, (_, d) => Math.ceil(((d + 1) * n) / 7) - Math.ceil((d * n) / 7));
}

/** The times of day (minutes after midnight, SOCIAL_TZ) of `count` posts: 18:00 for one, else evenly from 09:00 to 22:00. */
export function dayTimes(count: number): number[] {
  if (count < 1) return [];
  if (count === 1) return [SLOT_HOUR * 60];
  const step = Math.floor(((DAY_END_HOUR - DAY_START_HOUR) * 60) / (count - 1) / 30) * 30; // whole half hours
  return Array.from({ length: count }, (_, k) => DAY_START_HOUR * 60 + k * step);
}

/**
 * The weekly slots of a cadence as (weekday 0-6, minute of the day): one a day at 18:00, five a day at
 * 09:00 12:00 15:00 18:00 21:00, up to ten a day an hour apart.
 */
export function weeklySlots(postsPerWeek: number): Array<{ wd: number; minute: number }> {
  return postsPerDay(postsPerWeek).flatMap((count, wd) => dayTimes(count).map((minute) => ({ wd, minute })));
}

/** Upcoming videos autopilot keeps queued: at least TARGET_QUEUE, two days of posts on a fast cadence. */
export function targetQueue(postsPerWeek: number): number {
  return Math.max(TARGET_QUEUE, Math.ceil(postsPerWeek / 7) * 2);
}

/**
 * The first slot of the cadence after `after` (+ a 5 minute margin) that no time in `taken` sits on
 * (within 30 minutes). Slots are SOCIAL_TZ wall-clock (DST aware).
 */
export function nextSlot(postsPerWeek: number, after: number, taken: number[] = []): number {
  const today = lisbonDay(after);
  const slots = weeklySlots(postsPerWeek);
  for (let week = 0; week < 60; week++) {
    for (const s of slots) {
      const offset = week * 7 + s.wd - today.wd;
      if (offset < -6) continue;
      const day = new Date(Date.UTC(today.y, today.m - 1, today.d + offset));
      const at = parseWhen(`${day.getUTCFullYear()}-${pad(day.getUTCMonth() + 1)}-${pad(day.getUTCDate())}T${pad(Math.floor(s.minute / 60))}:${pad(s.minute % 60)}`);
      if (at > after + 300_000 && !taken.some((t) => Math.abs(t - at) < 1_800_000)) return at;
    }
  }
  throw new ContentError('no free publishing slot in the next 60 weeks');
}

// ---- pure helpers -------------------------------------------------------------------------------

/** Every open video, plus the newest CONTENT_KEEP_FINISHED finished ones. */
export function pruneContentVideos(videos: ContentVideo[]): ContentVideo[] {
  const finished = videos.filter((v) => !OPEN.has(v.status)).sort((a, b) => b.updatedAt - a.updatedAt);
  const drop = new Set(finished.slice(CONTENT_KEEP_FINISHED).map((v) => v.id));
  return videos.filter((v) => !drop.has(v.id));
}

/** The agent that owns a video in a given status (a user comment wakes them). */
export function ownerOf(status: ContentVideoStatus): string {
  switch (status) {
    case 'idea':
    case 'approved':
    case 'rejected':
    case 'scheduled':
      return IRIS;
    case 'producing':
    case 'review':
      return REEL;
    case 'publishing':
    case 'failed':
      return CLEO;
    case 'published':
      return VEGA;
  }
}

export function describeVideo(v: ContentVideo, full = false): string {
  const when = v.scheduledAt !== undefined ? ` · ${formatWhen(v.scheduledAt)}` : '';
  const hook = v.hook ? ` — hook: "${truncate(v.hook.replace(/\s+/g, ' '), full ? 400 : 80)}"` : '';
  return `${v.id} [${v.status}] ${v.format} · "${v.title}"${when}${hook}${v.url ? ` → ${v.url}` : ''}${v.error ? ` ✗ ${truncate(v.error, 160)}` : ''}${v.comments.length ? ` · ${v.comments.length} comment${v.comments.length === 1 ? '' : 's'}` : ''}`;
}

/** Everything about a video for an agent that is about to work on it. */
export function describeVideoFull(v: ContentVideo, names: (id: string) => string, now: number): string {
  const lines = [
    describeVideo(v, true),
    `channel: ${v.channelId} · pitched by ${names(v.authorId)} · created ${formatWhen(v.createdAt)}`,
    ...(v.pitch ? [`pitch: ${v.pitch}`] : []),
    ...(v.refIds?.length ? [`references: ${v.refIds.join(', ')}`] : []),
    ...(v.script ? [`script:\n${v.script}`] : []),
    ...(v.description ? [`description:\n${v.description}`] : []),
    ...(v.tags?.length ? [`tags: ${v.tags.join(', ')}`] : []),
    ...(v.filePath ? [`cut: ${v.filePath}`] : []),
    ...(v.thumbnailPath ? [`thumbnail: ${v.thumbnailPath}`] : []),
    ...(v.publishedAt ? [`published ${formatWhen(v.publishedAt)} (${Math.round((now - v.publishedAt) / 3_600_000)} h ago)`] : []),
    ...(v.comments.length ? [`comments:\n${v.comments.map((c) => `  [${formatWhen(c.at)}] ${names(c.by)}: ${c.text}`).join('\n')}`] : []),
    ...(v.postMortems?.length ? [`post-mortems:\n${v.postMortems.map((p) => `  [${p.horizon}] ${p.text}`).join('\n')}`] : []),
  ];
  return lines.join('\n');
}

const fmtNum = (n: number | undefined, digits = 0): string => (n === undefined ? '–' : n.toLocaleString('en-US', { maximumFractionDigits: digits }));
const pct = (n: number | undefined, scale = 1): string => (n === undefined ? '–' : `${(n * scale).toFixed(1)}%`);

/** The analytics table the agents read (get_analytics). */
export function describeStats(opts: { channel?: ContentChannel; channelStats?: ContentChannelStats; videos: Array<{ video: ContentVideo; history: ContentStat[] }>; now: number }): string {
  const out: string[] = [];
  const cs = opts.channelStats;
  if (opts.channel) {
    out.push(`${opts.channel.name} (${opts.channel.id}) — ${opts.channel.connected ? 'OAuth analytics connected' : 'public numbers only (run youtube-connect for retention, CTR, watch time)'}`);
    if (cs) out.push(`channel @ ${formatWhen(cs.at)}: subscribers ${fmtNum(cs.subscribers)} · total views ${fmtNum(cs.totalViews)} · videos ${fmtNum(cs.videoCount)} · last 28 d: views ${fmtNum(cs.views28d)}, watch hours ${fmtNum(cs.watchMinutes28d === undefined ? undefined : cs.watchMinutes28d / 60, 1)}, subs +${fmtNum(cs.subsGained28d)}`);
    else out.push('no channel stats yet: sync_analytics first');
  }
  for (const { video, history } of opts.videos) {
    const last = history[history.length - 1];
    if (!last) {
      out.push(`${video.id} "${truncate(video.title, 60)}" [${video.status}]: no numbers yet`);
      continue;
    }
    const prev = history.length > 1 ? history[history.length - 2] : undefined;
    const age = video.publishedAt ? `${Math.round((opts.now - video.publishedAt) / 3_600_000)} h old` : 'not published';
    const delta = prev ? ` (Δ ${last.views - prev.views >= 0 ? '+' : ''}${fmtNum(last.views - prev.views)} since ${formatWhen(prev.at)})` : '';
    out.push(
      `${video.id} "${truncate(video.title, 60)}" ${video.format} · ${age}: views ${fmtNum(last.views)}${delta} · likes ${fmtNum(last.likes)} · comments ${fmtNum(last.comments)} · avg view ${fmtNum(last.avgViewDurationSec, 1)} s (${last.avgViewPct === undefined ? '–' : `${last.avgViewPct.toFixed(1)}%`}) · watch min ${fmtNum(last.watchMinutes)} · subs +${fmtNum(last.subsGained)} · impressions ${fmtNum(last.impressions)} · CTR ${pct(last.ctr, 100)}`,
    );
    if (last.retention?.length) out.push(`  retention (0,5..100% of the video): ${last.retention.map((r) => Math.round(r * 100)).join(' ')}`);
    if (history.length > 1) out.push(`  views over time: ${history.slice(-10).map((h) => `${formatWhen(h.at)}=${fmtNum(h.views)}`).join(' · ')}`);
  }
  return out.join('\n');
}

const PLAYBOOK_TEMPLATE = (c: ContentChannel) => `# ${c.name} — playbook

Written by Iris and Vega, read by everyone before every pitch and every cut. Keep it short, specific and backed by numbers.

## Voice and audience
${c.brief ? c.brief : '(not written yet)'}

## What works
(nothing measured yet)

## What does not work
(nothing measured yet)

## Formats and pacing
(nothing measured yet)

## Experiments to run
- (none yet)
`;

// ---- agent prompts --------------------------------------------------------------------------------

export function publishPrompt(video: ContentVideo, channel: ContentChannel, workDir: string): string {
  const tags = video.tags?.length ? video.tags.join(', ') : '(none)';
  return `PUBLISH NOW: video ${video.id} on the YouTube channel "${channel.name}"${channel.url ? ` (${channel.url})` : ''}. The user approved this exact cut and publish time; it is scheduled for this moment.

Format: ${video.format === 'short' ? 'a SHORT (vertical, up to 3 minutes: YouTube files it as a Short by itself)' : 'a regular long video (horizontal)'}
Video file: ${video.filePath ?? '(missing!)'}
Thumbnail: ${video.thumbnailPath ?? '(none: skip the thumbnail step)'}
Title (exact, between the markers):
<<<TITLE
${video.title}
TITLE>>>
Description (exact, keep the line breaks):
<<<DESCRIPTION
${video.description ?? ''}
DESCRIPTION>>>
Tags: ${tags}

Steps
1. Open https://studio.youtube.com in the user's Chrome (Claude in Chrome tools). Make sure the channel switcher shows "${channel.name}"; if it is another channel, switch to it. Never publish on another channel.
2. Create -> Upload videos. Select the file with the Chrome file upload tool (path above) and wait until the upload starts.
3. Title and description exactly as above. Audience: "No, it's not made for kids" unless the description says otherwise. Add the tags (Show more -> Tags) and the thumbnail if there is one.
4. Visibility: Public. Click through to Publish (do not use Schedule: the time has come). Wait until the upload and processing of the first quality finished, or YouTube shows the link.
5. Copy the video link (the dialog's link, or Content -> the newest video -> "Copy video link"). Then call mark_published(id "${video.id}", url).
If anything goes wrong (not logged in, a captcha or verification, copyright or other warnings, an error, the wrong channel, you cannot tell whether it went out), do NOT retry blindly: call mark_failed(id "${video.id}", error: what exactly happened and what you saw), then end your turn. Never publish anything else, never edit or delete other videos, never change channel settings. Working directory: ${workDir}.`;
}

/** What Vega does in YouTube Studio (in the user's Chrome) to collect the numbers: no API, read only. */
function studioReadSteps(channels: Array<{ channel: ContentChannel; videos: ContentVideo[] }>, now: number): string {
  const known = channels
    .map(({ channel, videos }) => {
      const list = videos
        .filter((v) => v.youtubeVideoId)
        .slice(0, 30)
        .map((v) => `    - ${v.id} youtubeVideoId=${v.youtubeVideoId} "${truncate(v.title, 60)}" (${v.format}, ${v.publishedAt ? `${Math.round((now - v.publishedAt) / 3_600_000)} h ago` : 'not published'})`)
        .join('\n');
      return `  ${channel.name} (record_stats channelId "${channel.id}")${channel.url ? ` ${channel.url}` : ''}\n${list || '    (no published videos yet)'}`;
    })
    .join('\n');
  return `Channels and the videos to read (newest first):
${known}

Steps (YouTube Studio in the user's own Chrome, Claude in Chrome tools; you are logged in as them)
1. Open https://studio.youtube.com. The channel switcher (top right, your avatar) must show the channel; switch to it if it does not.
2. Analytics -> Overview, "Last 28 days": views, watch time (hours: send minutes = hours x 60), subscribers (the current total, and the gained over 28 days), and the daily views if the chart or the "See more" table gives them (date YYYY-MM-DD, views, subscribers gained).
3. Analytics -> Content (Shorts and Videos): for each listed video read views, average view duration (seconds), average percentage viewed, impressions and impressions click-through rate, likes, comments, subscribers gained where the table shows them.
4. Per-video analytics (click the video in the list) for the recent ones: audience retention (the curve: read it at 0, 5, 10 ... 100% of the video as 21 numbers from 0 to 1, only if you can read it), and for Shorts "Viewed vs swiped away" (stayedToWatchPct and swipeAwayPct, 0-100).
5. Call record_stats once per channel with what you ACTUALLY read (youtubeVideoId from the list above; ctr is 0..1, avgViewPct 0..100). Leave out whatever you could not read. Never invent or estimate numbers you did not see.
READ ONLY: never change a setting, never upload, never edit, hide, delete or publish a video or a comment, never click Save/Publish/Delete. Chrome may only visit studio.youtube.com, youtube.com and accounts.google.com. If you are not logged in or Google asks for a captcha, send_message to "user" what you saw and end your turn.`;
}

export function studioReadPrompt(channels: Array<{ channel: ContentChannel; videos: ContentVideo[] }>, why: string, now: number): string {
  return `STUDIO READ (${why}): collect the latest YouTube numbers from YouTube Studio, no API needed.\n${studioReadSteps(channels, now)}\nThen end your turn.`;
}

export function postMortemPrompt(video: ContentVideo, channel: ContentChannel, horizon: '48h' | '7d', studio?: { videos: ContentVideo[]; now: number }): string {
  const numbers = studio
    ? `1. First collect fresh numbers from YouTube Studio in Chrome and record them (record_stats):\n${studioReadSteps([{ channel, videos: studio.videos }], studio.now)}\n   Then get_analytics(videoId "${video.id}") and get_analytics(channelId "${channel.id}") to compare it with the channel's other videos (views, average view %, stayed to watch, retention curve where it drops, CTR, subs gained).`
    : `1. sync_analytics("${channel.id}") for fresh numbers, then get_analytics(videoId "${video.id}") and get_analytics(channelId "${channel.id}") to compare it with the channel's other videos (views, average view %, retention curve where it drops, CTR, subs gained).`;
  return `POST-MORTEM (${horizon}): video ${video.id} "${video.title}" on ${channel.name} went live ${video.publishedAt ? formatWhen(video.publishedAt) : 'a while ago'}.
${numbers}
2. get_video("${video.id}") for the pitch, the hook and the references it copied: did the idea work, and why or why not? Use numbers, not adjectives. If it copied a reference, say what carried over and what did not.
3. write_post_mortem("${video.id}", "${horizon}", text): 5-10 lines with the numbers, the verdict and one concrete change for the next videos.
4. read_playbook("${channel.id}") and update_playbook("${channel.id}", markdown) with what this teaches (keep it short; move findings between "works" and "does not work" when the numbers say so).
5. send_message to Iris with the one-line takeaway, then end your turn.`;
}

export function topUpPrompt(channel: ContentChannel, upcoming: number): string {
  const target = targetQueue(channel.postsPerWeek);
  const need = Math.min(20, Math.max(1, target - upcoming));
  const perDay = Math.ceil(channel.postsPerWeek / 7);
  return `AUTOPILOT: ${channel.name} (${channel.id}) has ${upcoming} upcoming video${upcoming === 1 ? '' : 's'}, the target is ${target} (${channel.postsPerWeek} posts a week, about ${perDay} a day). The user is not approving anything on this channel: you approve your own ideas with approve_idea, and finished cuts go to the next free slot by themselves.
1. read_playbook("${channel.id}"), list_videos("${channel.id}") (count again: only pitch the shortfall, and do not repeat or clash with what is there) and get_analytics("${channel.id}").
2. Pitch a BATCH of ${need} new idea${need === 1 ? '' : 's'} in this one turn with pitch_video (format short unless the playbook says otherwise): varied topics and hooks, each backed by the playbook and the numbers; study references with scan_channel / study_video first if you need fresh ones.
3. approve_idea for each good one (Reel works through the approved list). Then end your turn.`;
}

export function newChannelPrompt(channel: ContentChannel, user: string): string {
  return `${user} just added the channel "${channel.name}" (${channel.id})${channel.url ? ` ${channel.url}` : ''}${channel.niche ? `, niche: ${channel.niche}` : ''}.
1. read_playbook("${channel.id}"); the channel brief is in list_channels. If the brief is thin, ask ${user} the 2-3 questions you need (send_message to "user"), but do not wait: start with what you know.
2. Research the niche: scan_channel on the channel itself (if it has videos) and on 2-3 successful channels in the niche, then study_video the best 2-3 of their videos.
3. pitch_video 3 ideas with a hook, why they should work and the references they copy. ${user} approves them in the Content Studio. Tell ${user} in a line what you pitched, then end your turn.`;
}

export function approvedPrompt(video: ContentVideo, channel: ContentChannel, user: string): string {
  return `${user} APPROVED the idea ${video.id} "${video.title}" for ${channel.name}. Produce it.
1. read_playbook("${channel.id}") and get_video("${video.id}") (pitch, hook, references, comments).
2. start_production("${video.id}"). Study the references with study_video (frame by frame) and READ the contact sheets it lists with your Read tool; write down what to copy: opening, pacing, framing, captions, sound.
3. Write the script (update_video script), the description and tags; make the video with the tools you have (image/video/voice generators, Remotion, Blender, ffmpeg...) and a thumbnail.
4. attach_cut("${video.id}", filePath, thumbnailPath) and show it with show_media. ${user} reviews it; do not publish anything yourself.`;
}

/** Reel's job for one or several approved ideas; with several he works through the list and re-checks it. */
export function produceBatchPrompt(items: Array<{ video: ContentVideo; channel: ContentChannel }>, user: string): string {
  const [first] = items;
  if (!first) return '';
  const loop = `\nWhen you attach a cut, list_videos(status "approved") again and carry on with the next one until none is left: several videos a day is normal, so keep each one fresh (never reuse one script for two videos) and keep the quality bar.`;
  if (items.length === 1) return approvedPrompt(first.video, first.channel, user) + loop;
  return `${user} APPROVED ${items.length} ideas. Produce them one after another, oldest first:
${items.map(({ video, channel }) => `- ${video.id} [${video.status}] ${video.format} "${truncate(video.title, 80)}" (${channel.name}, playbook: read_playbook("${channel.id}"))`).join('\n')}
For each one: get_video(id) (pitch, hook, references, comments), start_production(id), study the references with study_video and READ the contact sheets with your Read tool, write the script (update_video), make the video and a thumbnail, attach_cut(id, filePath, thumbnailPath) and show_media it. ${user} reviews the cuts; do not publish anything yourself.${loop}`;
}

export function productionReminderPrompt(items: Array<{ video: ContentVideo; channel: ContentChannel }>, user: string): string {
  return `REMINDER: ${items.length === 1 ? 'a video' : `${items.length} videos`} ${user} approved still ${items.length === 1 ? 'has' : 'have'} no finished cut:
${items.map(({ video, channel }) => `- ${video.id} [${video.status}] "${truncate(video.title, 80)}" (${channel.name})`).join('\n')}
For each: read_playbook, get_video(id), start_production(id) if it is still approved, carry on (study the references, script, make the video) and attach_cut(id, filePath, thumbnailPath) when it is done. If you cannot finish one, tell ${user} why (send_message to "user").`;
}

export function rejectedPrompt(video: ContentVideo, channel: ContentChannel, reason: string | undefined, user: string): string {
  return `${user} REJECTED ${video.id} "${video.title}" (${channel.name})${reason ? `: "${reason}"` : ' without a reason'}. Learn from it: read_playbook("${channel.id}") and add a short "rejected because" line to the playbook (update_playbook) if it says something about taste, then pitch a better replacement if the queue is short. Briefly acknowledge to ${user} (send_message to "user") and end your turn.`;
}

export function commentPrompt(video: ContentVideo, by: string, text: string): string {
  return `${by} commented on ${video.id} "${video.title}" [${video.status}]: "${text}"
get_video("${video.id}") for the whole thread, then act on it: revise what they asked (update_video${video.status === 'idea' || video.status === 'approved' ? ', title/hook/pitch' : ''}), or answer with comment_video("${video.id}", ...). Keep ${by} informed with a short send_message(to "user") when something changed. End your turn.`;
}

// ---- the studio -------------------------------------------------------------------------------------

export interface NewChannel {
  name: string;
  url?: string;
  niche?: string;
  brief?: string;
  postsPerWeek?: number;
}

export interface ChannelPatch {
  name?: string;
  url?: string;
  niche?: string;
  brief?: string;
  postsPerWeek?: number;
}

export interface NewVideo {
  channelId: string;
  title: string;
  format: 'short' | 'long';
  hook?: string;
  pitch?: string;
  refIds?: string[];
  authorId: string;
}

export interface VideoPatch {
  title?: string;
  hook?: string;
  pitch?: string;
  script?: string;
  description?: string;
  tags?: string[];
  refIds?: string[];
  format?: 'short' | 'long';
}

export type ChannelAction = 'add' | 'update' | 'remove' | 'autopilot_on' | 'autopilot_off' | 'sync';
export type VideoAction = 'approve' | 'reject' | 'comment' | 'approve_cut' | 'schedule' | 'publish_now' | 'unschedule' | 'retry';

export interface SyncReport {
  channels: number;
  videos: number;
  lines: string[];
}

export class ContentStudio {
  readonly dir: string;
  private runner: ContentRunner | undefined;
  private timer: NodeJS.Timeout | undefined;
  /** the video being published right now (one at a time) */
  private current: string | undefined;
  private syncing: Promise<SyncReport> | undefined;
  private syncScope: string | undefined;
  /** videos already reported as stuck in production */
  private staleNotified = new Set<string>();
  private closed = false;
  /** when an agent job was last asked for, so a missing one is not asked every tick: "v3:48h" / "topup:ch1" */
  private asked = new Map<string, number>();

  constructor(private d: ContentDeps) {
    this.dir = d.dir;
    fs.mkdirSync(path.join(this.dir, 'cuts'), { recursive: true });
  }

  private get now(): number {
    return this.d.ctx.now();
  }

  setRunner(runner: ContentRunner | undefined): void {
    this.runner = runner;
  }

  /** The persisted state (created on first use; absent in old state files). */
  private get data(): ContentData {
    const store = this.d.ctx.store;
    const cur = store.data.content;
    if (cur) return cur;
    const fresh = emptyContent();
    store.data.content = fresh;
    return fresh;
  }

  private patchData(patch: Partial<ContentData>): void {
    const store = this.d.ctx.store;
    store.data.content = { ...this.data, ...patch };
    store.markDirty();
  }

  // ---- reading --------------------------------------------------------------------------------

  /** Channels as the clients see them: `connected` is live, `playbook` is the file. */
  channels(): ContentChannel[] {
    return this.data.channels.map((c) => this.view(c));
  }

  channel(id: string): ContentChannel | undefined {
    const c = this.data.channels.find((x) => x.id === id);
    return c ? this.view(c) : undefined;
  }

  requireChannel(id: string): ContentChannel {
    const c = this.channel(id);
    if (!c) throw new ContentError(`no channel "${id}". Channels: ${this.data.channels.map((x) => `${x.id} (${x.name})`).join(', ') || 'none yet'}`);
    return c;
  }

  /** The channel an agent means: the id it gave, or the only channel there is. */
  resolveChannel(id: string | undefined): ContentChannel {
    if (id) return this.requireChannel(id);
    const all = this.data.channels;
    if (all.length === 1) return this.view(all[0]!);
    throw new ContentError(all.length ? `which channel? ${all.map((x) => `${x.id} (${x.name})`).join(', ')}` : 'there is no channel yet: the user adds one in the Content Studio');
  }

  private view(c: ContentChannel): ContentChannel {
    const { playbook: _stored, ...base } = c;
    const playbook = this.readPlaybook(c.id);
    const api = this.isConnected(c);
    const studio = this.studioFresh(c.id);
    const statsSource = api ? 'api' : studio ? 'studio' : this.data.channelStats.some((s) => s.channelId === c.id) ? 'public' : undefined;
    const { statsSource: _s, ...rest } = base;
    return { ...rest, connected: api || studio, ...(statsSource ? { statsSource } : {}), ...(playbook ? { playbook } : {}) };
  }

  /** Vega read this channel's numbers from YouTube Studio within the last 24 hours. */
  private studioFresh(channelId: string): boolean {
    const at = this.data.studioReads?.[channelId];
    return at !== undefined && this.now - at < STUDIO_FRESH_MS;
  }

  private isConnected(c: ContentChannel): boolean {
    try {
      return !!c.youtubeId && !!this.d.youtube?.connectedChannels().includes(c.youtubeId);
    } catch {
      return false;
    }
  }

  videos(): ContentVideo[] {
    return this.data.videos;
  }

  video(id: string): ContentVideo | undefined {
    return this.data.videos.find((v) => v.id === id);
  }

  requireVideo(id: string): ContentVideo {
    const v = this.video(id);
    if (!v) throw new ContentError(`no video "${id}"`);
    return v;
  }

  /** Videos, oldest scheduled first (those without a time last). */
  list(filter: { channelId?: string; status?: ContentVideoStatus } = {}): ContentVideo[] {
    return this.videos()
      .filter((v) => (!filter.channelId || v.channelId === filter.channelId) && (!filter.status || v.status === filter.status))
      .sort((a, b) => (a.scheduledAt ?? Infinity) - (b.scheduledAt ?? Infinity) || a.createdAt - b.createdAt);
  }

  refs(): ContentRef[] {
    return this.data.refs;
  }

  ref(id: string): ContentRef | undefined {
    return this.data.refs.find((r) => r.id === id);
  }

  requireRef(id: string): ContentRef {
    const r = this.ref(id);
    if (!r) throw new ContentError(`no reference "${id}"`);
    return r;
  }

  /** Latest snapshot per video id plus the history of own videos, oldest first. */
  stats(): ContentStat[] {
    return this.data.stats;
  }

  statsOf(videoId: string): ContentStat[] {
    return this.data.stats.filter((s) => s.videoId === videoId).sort((a, b) => a.at - b.at);
  }

  channelStats(channelId?: string): ContentChannelStats[] {
    return this.data.channelStats.filter((s) => !channelId || s.channelId === channelId);
  }

  isPublishing(videoId: string | undefined): boolean {
    return !!videoId && this.video(videoId)?.status === 'publishing';
  }

  // ---- saving ---------------------------------------------------------------------------------

  private saveChannel(next: ContentChannel): ContentChannel {
    const { playbook: _playbook, connected: _connected, ...stored } = next;
    const rest = this.data.channels.filter((c) => c.id !== next.id);
    this.patchData({ channels: [...rest, { ...stored, connected: false }].sort((a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id, 'en', { numeric: true })) });
    const out = this.view(next);
    this.d.ctx.emit({ type: 'content.channel.upsert', channel: { ...out } });
    return out;
  }

  private saveVideo(next: ContentVideo): ContentVideo {
    const rest = this.data.videos.filter((v) => v.id !== next.id);
    const comments = next.comments.slice(-COMMENTS_KEEP);
    this.patchData({ videos: pruneContentVideos([...rest, { ...next, comments }].sort((a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id, 'en', { numeric: true }))) });
    this.d.ctx.emit({ type: 'content.video.upsert', video: { ...next, comments: comments.map((c) => ({ ...c })) } });
    return { ...next, comments };
  }

  private feed(text: string, by?: string): void {
    this.d.bus.feed('system', text, by && by !== 'user' ? { agentId: by } : {});
  }

  private label(v: ContentVideo): string {
    return `${v.format === 'short' ? 'Short' : 'video'} ${v.id} "${truncate(v.title, 60)}"`;
  }

  private wake(agentId: string, prompt: string, kind: 'content' | 'analytics' = 'content'): boolean {
    if (!this.runner?.canRun(agentId)) return false;
    this.runner.wake(agentId, prompt, { sessionKey: `${agentId}:content`, kind });
    return true;
  }

  // ---- channels ---------------------------------------------------------------------------------

  addChannel(input: NewChannel, by = 'user'): ContentChannel {
    const name = input.name.replace(/\s+/g, ' ').trim();
    if (!name) throw new ContentError('the channel needs a name');
    if (name.length > 80) throw new ContentError('the channel name is longer than 80 characters');
    const link = input.url?.trim() ? normalizeChannelUrl(input.url) : undefined;
    const at = this.now;
    const niche = input.niche?.trim() ? limited('the niche', input.niche, 200) : undefined;
    const brief = input.brief?.trim() ? limited('the brief', input.brief, BRIEF_MAX) : undefined;
    const postsPerWeek = validatePostsPerWeek(input.postsPerWeek ?? DEFAULT_POSTS_PER_WEEK);
    const draft: ContentChannel = {
      id: this.d.ctx.store.nextId('ch'),
      name,
      ...(link ? { url: link.url, ...(link.youtubeId ? { youtubeId: link.youtubeId } : {}) } : {}),
      ...(niche ? { niche } : {}),
      ...(brief ? { brief } : {}),
      autopilot: false,
      postsPerWeek,
      connected: false,
      createdAt: at,
      updatedAt: at,
    };
    this.writePlaybookFile(draft.id, PLAYBOOK_TEMPLATE(draft));
    const channel = this.saveChannel(draft);
    this.feed(`${this.d.names(by)} added the YouTube channel ${channel.name} (${channel.id})`, by);
    this.wake(IRIS, newChannelPrompt(channel, this.d.names('user')));
    return this.requireChannel(channel.id);
  }

  updateChannel(id: string, patch: ChannelPatch, by = 'user'): ContentChannel {
    const c = this.requireChannel(id);
    const link = patch.url !== undefined ? (patch.url.trim() ? normalizeChannelUrl(patch.url) : undefined) : undefined;
    const name = patch.name !== undefined ? patch.name.replace(/\s+/g, ' ').trim() : c.name;
    if (!name || name.length > 80) throw new ContentError('the channel name must be 1-80 characters');
    const { url: _u, youtubeId: _y, niche: _n, brief: _b, ...base } = c;
    const niche = patch.niche !== undefined ? limited('the niche', patch.niche, 200) : c.niche;
    const brief = patch.brief !== undefined ? limited('the brief', patch.brief, BRIEF_MAX) : c.brief;
    const url = link ? link.url : patch.url !== undefined ? undefined : c.url;
    const youtubeId = link ? link.youtubeId : patch.url !== undefined ? undefined : c.youtubeId;
    const next = this.saveChannel({
      ...base,
      name,
      ...(url ? { url } : {}),
      ...(youtubeId ? { youtubeId } : {}),
      ...(niche ? { niche } : {}),
      ...(brief ? { brief } : {}),
      postsPerWeek: patch.postsPerWeek !== undefined ? validatePostsPerWeek(patch.postsPerWeek) : c.postsPerWeek,
      updatedAt: this.now,
    });
    this.feed(`${this.d.names(by)} updated the channel ${next.name}`, by);
    return next;
  }

  removeChannel(id: string, by = 'user'): void {
    const c = this.requireChannel(id);
    const mine = this.data.videos.filter((v) => v.channelId === id);
    if (mine.some((v) => v.status === 'publishing')) throw new ContentError(`${c.name} has a video being published right now`);
    const ownIds = new Set(mine.map((v) => v.youtubeVideoId).filter((x): x is string => !!x));
    this.patchData({
      channels: this.data.channels.filter((x) => x.id !== id),
      videos: this.data.videos.filter((v) => v.channelId !== id),
      stats: this.data.stats.filter((s) => !ownIds.has(s.videoId)),
      channelStats: this.data.channelStats.filter((s) => s.channelId !== id),
    });
    this.feed(`${this.d.names(by)} removed the channel ${c.name} and its videos from the studio (the YouTube channel is untouched)`, by);
  }

  setAutopilot(id: string, on: boolean, by = 'user'): ContentChannel {
    const c = this.requireChannel(id);
    if (c.autopilot === on) return c;
    const next = this.saveChannel({ ...c, autopilot: on, updatedAt: this.now });
    this.feed(`${this.d.names(by)} turned autopilot ${on ? 'ON' : 'OFF'} for ${next.name}${on ? `: the agents approve ideas and cuts themselves and keep ${TARGET_QUEUE} videos queued` : ''}`, by);
    if (!on) {
      // what autopilot scheduled without the user's say-so goes back to review
      for (const v of this.list({ channelId: id, status: 'scheduled' }).filter((x) => x.autoScheduled)) {
        const back = this.backToReview(v);
        this.feed(`Autopilot is off: ${this.label(back)} goes back to review, approve its cut to schedule it`);
      }
    }
    if (on) {
      // cuts already waiting for the user are scheduled now; Iris looks at the pending ideas
      for (const v of this.list({ channelId: id, status: 'review' })) if (v.filePath) this.autoSchedule(v.id);
      this.topUp(next, true);
    }
    return next;
  }

  // ---- playbook ---------------------------------------------------------------------------------

  private playbookFile(channelId: string): string {
    return path.join(this.dir, 'channels', channelId, 'playbook.md');
  }

  readPlaybook(channelId: string): string | undefined {
    try {
      const t = fs.readFileSync(this.playbookFile(channelId), 'utf8').trim();
      return t ? t.slice(0, PLAYBOOK_MAX) : undefined;
    } catch {
      return undefined;
    }
  }

  private writePlaybookFile(channelId: string, markdown: string): void {
    const file = this.playbookFile(channelId);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, markdown.endsWith('\n') ? markdown : `${markdown}\n`);
  }

  /** Replace the playbook (the agent read it first and sends the whole new text). */
  writePlaybook(channelId: string, markdown: string, by: string): ContentChannel {
    const c = this.requireChannel(channelId);
    const text = limited('the playbook', markdown, PLAYBOOK_MAX);
    if (!text) throw new ContentError('the playbook is empty: send the whole new playbook, not a fragment');
    this.writePlaybookFile(channelId, text);
    const out = this.saveChannel({ ...c, updatedAt: this.now });
    this.feed(`${this.d.names(by)} updated the playbook of ${c.name}`, by);
    return out;
  }

  // ---- ideas: the showrunner -----------------------------------------------------------------------

  pitch(input: NewVideo): ContentVideo {
    const channel = this.requireChannel(input.channelId);
    const title = validateTitle(input.title);
    const refIds = input.refIds?.length ? [...new Set(input.refIds)] : undefined;
    for (const r of refIds ?? []) this.requireRef(r);
    const hook = input.hook?.trim() ? limited('the hook', input.hook, 300) : undefined;
    const pitch = input.pitch?.trim() ? limited('the pitch', input.pitch, PITCH_MAX) : undefined;
    const at = this.now;
    const video = this.saveVideo({
      id: this.d.ctx.store.nextId('v'),
      channelId: channel.id,
      status: 'idea',
      format: input.format,
      title,
      ...(hook ? { hook } : {}),
      ...(pitch ? { pitch } : {}),
      ...(refIds ? { refIds } : {}),
      authorId: input.authorId,
      comments: [],
      createdAt: at,
      updatedAt: at,
    });
    this.feed(`${this.d.names(input.authorId)} pitched a ${video.format} for ${channel.name}: "${truncate(title, 80)}" ${channel.autopilot ? '(autopilot: self-approved next)' : '(waiting for your approval in the Content Studio)'}`, input.authorId);
    return video;
  }

  /** Change a video's text. Nothing that is scheduled or live can change. */
  update(id: string, patch: VideoPatch, by: string): ContentVideo {
    const v = this.requireVideo(id);
    if (!EDITABLE.has(v.status)) throw new ContentError(`${v.id} is ${v.status}: only ideas, approved, producing and review videos can be changed${v.status === 'scheduled' ? ' (unschedule it first)' : ''}`);
    const refIds = patch.refIds !== undefined ? [...new Set(patch.refIds)] : undefined;
    for (const r of refIds ?? []) this.requireRef(r);
    const { hook: _h, pitch: _p, script: _s, description: _d, tags: _t, refIds: _r, ...base } = v;
    const keep = <K extends 'hook' | 'pitch' | 'script' | 'description'>(k: K, value: string | undefined): Partial<ContentVideo> => (value ? { [k]: value } : {});
    const next = this.saveVideo({
      ...base,
      title: patch.title !== undefined ? validateTitle(patch.title) : v.title,
      format: patch.format ?? v.format,
      ...keep('hook', patch.hook !== undefined ? limited('the hook', patch.hook, 300) : v.hook),
      ...keep('pitch', patch.pitch !== undefined ? limited('the pitch', patch.pitch, PITCH_MAX) : v.pitch),
      ...keep('script', patch.script !== undefined ? limited('the script', patch.script, SCRIPT_MAX) : v.script),
      ...keep('description', patch.description !== undefined ? validateDescription(patch.description) : v.description),
      ...((patch.tags !== undefined ? validateTags(patch.tags) : v.tags)?.length ? { tags: patch.tags !== undefined ? validateTags(patch.tags) : v.tags! } : {}),
      ...((refIds ?? v.refIds)?.length ? { refIds: refIds ?? v.refIds! } : {}),
      updatedAt: this.now,
    });
    this.feed(`${this.d.names(by)} updated ${this.label(next)}`, by);
    return next;
  }

  /** A comment in the video's thread. The user's wake the agent that owns the video. */
  comment(id: string, text: string, by: string): ContentVideo {
    const v = this.requireVideo(id);
    const body = limited('the comment', text, COMMENT_MAX);
    if (!body) throw new ContentError('the comment is empty');
    const next = this.saveVideo({ ...v, comments: [...v.comments, { by, text: body, at: this.now }], updatedAt: this.now });
    if (by === 'user') {
      const woke = this.wake(ownerOf(v.status), commentPrompt(next, this.d.names('user'), body));
      this.feed(`${this.d.names('user')} commented on ${this.label(next)}${woke ? `: ${this.d.names(ownerOf(v.status))} is on it` : ''}`);
    }
    return next;
  }

  // ---- the production: the producer -----------------------------------------------------------

  startProduction(id: string): ContentVideo {
    const v = this.requireVideo(id);
    if (v.status === 'producing') return v;
    if (v.status !== 'approved') throw new ContentError(`${v.id} is ${v.status}: only an approved idea can go into production${v.status === 'idea' ? ' (the user has not approved it yet)' : ''}`);
    const next = this.saveVideo({ ...v, status: 'producing', updatedAt: this.now });
    this.feed(`${this.d.names(REEL)} started producing ${this.label(next)}`, REEL);
    return next;
  }

  /** The finished cut (and thumbnail) -> review; on an autopilot channel it is scheduled right away. */
  attachCut(id: string, filePath: string, thumbnailPath?: string): ContentVideo {
    const v = this.requireVideo(id);
    if (v.status !== 'producing' && v.status !== 'review' && v.status !== 'approved') throw new ContentError(`${v.id} is ${v.status}: a cut can be attached while the video is producing or in review`);
    const file = this.importFile(v.id, filePath, 'the cut', ['.mp4', '.mov', '.m4v', '.webm', '.mkv']);
    const thumb = thumbnailPath ? this.importFile(v.id, thumbnailPath, 'the thumbnail', ['.png', '.jpg', '.jpeg', '.webp']) : v.thumbnailPath;
    const { error: _e, thumbnailPath: _t, autoScheduled: _a, ...base } = v;
    const next = this.saveVideo({ ...base, status: 'review', filePath: file, ...(thumb ? { thumbnailPath: thumb } : {}), updatedAt: this.now });
    const channel = this.channel(next.channelId);
    this.feed(`${this.d.names(REEL)} finished the cut of ${this.label(next)}${channel?.autopilot ? ' (autopilot: scheduling it)' : ': waiting for your review in the Content Studio'}`, REEL);
    if (!channel?.autopilot) this.d.notify('info', `${this.label(next)} is ready for your review in the Content Studio`);
    return channel?.autopilot ? this.autoSchedule(next.id) : next;
  }

  /**
   * The file behind a path (symlinks resolved), copied into `cuts/<videoId>/`: what the user approves is
   * a snapshot nobody can swap, and nothing outside the workspace is ever uploaded by path.
   */
  private importFile(videoId: string, raw: string, what: string, exts: string[]): string {
    let src: string;
    try {
      src = fs.realpathSync(path.resolve(this.dir, raw.trim().replace(/^~(?=$|[\\/])/, process.env.HOME ?? '~')));
    } catch {
      throw new ContentError(`no file for ${what} at ${raw.trim()}`);
    }
    if (!fs.statSync(src).isFile()) throw new ContentError(`no file for ${what} at ${src}`);
    if (!exts.includes(path.extname(src).toLowerCase())) throw new ContentError(`${what} must be one of ${exts.join(' ')}, not ${path.basename(src)}`);
    const dir = path.join(this.dir, 'cuts', videoId);
    const dest = path.join(dir, path.basename(src));
    fs.mkdirSync(dir, { recursive: true });
    if (src !== dest) fs.copyFileSync(src, dest);
    return dest;
  }

  /** A scheduled video back to review (autopilot off, a missed slot): no time, no autopilot mark. */
  private backToReview(v: ContentVideo, error?: string): ContentVideo {
    const { scheduledAt: _s, autoScheduled: _a, error: _e, ...base } = v;
    this.forgetHash(v.id);
    return this.saveVideo({ ...base, status: 'review', ...(error ? { error } : {}), updatedAt: this.now });
  }

  private rememberHash(v: ContentVideo): void {
    if (v.filePath) this.patchData({ cutHashes: { ...this.data.cutHashes, [v.id]: hashFile(v.filePath) } });
  }

  private forgetHash(id: string): void {
    const { [id]: _gone, ...rest } = this.data.cutHashes ?? {};
    if (_gone !== undefined) this.patchData({ cutHashes: rest });
  }

  /** Cleo may attach only this video's own cut and thumbnail through Chrome's file upload. */
  uploadPathsOk(videoId: string, input: Record<string, unknown>): boolean {
    const v = this.video(videoId);
    const paths = input.paths;
    if (!v || !Array.isArray(paths) || !paths.length || !paths.every((p) => typeof p === 'string')) return false;
    const allowed = [v.filePath, v.thumbnailPath].filter((x): x is string => !!x);
    return (paths as string[]).every((p) => allowed.includes(path.resolve(p)));
  }

  /** Schedule a review video at the channel's next free slot (autopilot). */
  private autoSchedule(id: string): ContentVideo {
    const v = this.requireVideo(id);
    const at = this.freeSlot(v.channelId);
    const next = this.saveVideo({ ...v, status: 'scheduled', scheduledAt: at, autoScheduled: true, updatedAt: this.now });
    this.rememberHash(next);
    this.feed(`Autopilot scheduled ${this.label(next)} for ${formatWhen(at)}`);
    return next;
  }

  /** The next slot of the channel's cadence that no scheduled video of the channel is near. */
  freeSlot(channelId: string, after = this.now): number {
    const c = this.requireChannel(channelId);
    const taken = this.data.videos.filter((v) => v.channelId === channelId && (v.status === 'scheduled' || v.status === 'publishing') && v.scheduledAt !== undefined).map((v) => v.scheduledAt!);
    return nextSlot(c.postsPerWeek, after, taken);
  }

  // ---- the user's actions ----------------------------------------------------------------------

  channelAction(channelId: string | undefined, action: ChannelAction, input: ChannelPatch = {}): { channelId?: string } {
    if (action === 'add') return { channelId: this.addChannel({ name: input.name ?? '', ...input }).id };
    if (!channelId) throw new ContentError(`${action} needs a channelId`);
    switch (action) {
      case 'update':
        this.updateChannel(channelId, { ...(input.name ? { name: input.name } : {}), ...(input.url !== undefined ? { url: input.url } : {}), ...(input.niche !== undefined ? { niche: input.niche } : {}), ...(input.brief !== undefined ? { brief: input.brief } : {}), ...(input.postsPerWeek !== undefined ? { postsPerWeek: input.postsPerWeek } : {}) });
        break;
      case 'remove':
        this.removeChannel(channelId);
        break;
      case 'autopilot_on':
      case 'autopilot_off':
        this.setAutopilot(channelId, action === 'autopilot_on');
        break;
      case 'sync':
        this.requireChannel(channelId);
        void this.sync(channelId).catch((e: Error) => this.d.ctx.log.warn(`content: sync failed: ${e.message}`));
        this.requestStudioRead([channelId], `${this.d.names('user')} asked for a sync`, true);
        break;
    }
    return { channelId };
  }

  act(videoId: string, action: VideoAction, opts: { text?: string; at?: number } = {}): ContentVideo {
    const v = this.requireVideo(videoId);
    const user = this.d.names('user');
    const channel = this.requireChannel(v.channelId);
    switch (action) {
      case 'comment': {
        if (!opts.text?.trim()) throw new ContentError('comment needs text');
        return this.comment(v.id, opts.text, 'user');
      }
      case 'approve':
        return this.approveIdea(v.id, 'user');
      case 'reject': {
        if (!['idea', 'approved', 'review'].includes(v.status)) throw new ContentError(`${v.id} is ${v.status}: only ideas, approved ideas and cuts in review can be rejected`);
        const reason = opts.text?.trim() ? limited('the reason', opts.text, COMMENT_MAX) : undefined;
        const next = this.saveVideo({
          ...v,
          status: 'rejected',
          comments: reason ? [...v.comments, { by: 'user', text: `Rejected: ${reason}`, at: this.now }] : v.comments,
          updatedAt: this.now,
        });
        this.feed(`${user} rejected ${this.label(next)}${reason ? `: ${truncate(reason, 100)}` : ''}`);
        this.wake(IRIS, rejectedPrompt(next, channel, reason, user));
        return next;
      }
      case 'approve_cut': {
        if (v.status !== 'review') throw new ContentError(`${v.id} is ${v.status}: only a cut in review can be approved`);
        return this.scheduleVideo(v, opts.at, `${user} approved the cut of`);
      }
      case 'schedule': {
        if (v.status !== 'scheduled') throw new ContentError(`${v.id} is ${v.status}: only a scheduled video can move (approve the cut first)`);
        if (opts.at === undefined) throw new ContentError('schedule needs a time (at)');
        return this.scheduleVideo(v, opts.at, `${user} moved`);
      }
      case 'publish_now': {
        if (!['review', 'scheduled', 'failed'].includes(v.status)) throw new ContentError(`${v.id} is ${v.status}: only a cut in review, a scheduled or a failed video can be published now`);
        const next = this.scheduleVideo(v, this.now, `${user} approved to publish now`, true);
        this.tick();
        return this.video(next.id) ?? next;
      }
      case 'unschedule': {
        if (v.status !== 'scheduled') throw new ContentError(`${v.id} is ${v.status}, not scheduled`);
        const { scheduledAt: _s, ...base } = v;
        const next = this.saveVideo({ ...base, status: 'review', updatedAt: this.now });
        this.feed(`${user} took ${this.label(next)} back to review`);
        return next;
      }
      case 'retry': {
        if (v.status !== 'failed') throw new ContentError(`${v.id} is ${v.status}, not failed`);
        const next = this.scheduleVideo(v, this.now, `${user} retries`, true);
        this.tick();
        return this.video(next.id) ?? next;
      }
    }
  }

  /** review/scheduled/failed -> scheduled at `at` (default: the channel's next free slot). */
  private scheduleVideo(v: ContentVideo, at: number | undefined, what: string, allowNow = false): ContentVideo {
    if (!v.filePath || !fs.existsSync(v.filePath)) throw new ContentError(`${v.id} has no cut file on disk${v.filePath ? ` (${v.filePath})` : ''}: Reel has to attach it first`);
    const when = at ?? this.freeSlot(v.channelId);
    if (!allowNow && when <= this.now) throw new ContentError(`${formatWhen(when)} is in the past (now: ${formatNow(this.now)})`);
    const { error: _e, autoScheduled: _a, ...base } = v;
    const next = this.saveVideo({ ...base, status: 'scheduled', scheduledAt: when, updatedAt: this.now });
    this.rememberHash(next);
    this.feed(`${what} ${this.label(next)} for ${allowNow && when <= this.now ? 'right now' : formatWhen(when)}${allowNow ? '' : ': it goes out then, without asking again'}`);
    return next;
  }

  /** idea -> approved and Reel starts. By the user, or by Iris on an autopilot channel. */
  approveIdea(id: string, by: string): ContentVideo {
    const v = this.requireVideo(id);
    const channel = this.requireChannel(v.channelId);
    if (v.status === 'approved' || v.status === 'producing') return v;
    if (v.status !== 'idea') throw new ContentError(`${v.id} is ${v.status}: only an idea can be approved`);
    if (by !== 'user' && !channel.autopilot) throw new ContentError(`${channel.name} is not on autopilot: only ${this.d.names('user')} approves ideas there`);
    const next = this.saveVideo({ ...v, status: 'approved', updatedAt: this.now });
    this.feed(`${this.d.names(by)} approved ${this.label(next)}`, by);
    this.wakeReel(next.id);
    return next;
  }

  /** Every approved / producing video with its channel, oldest first. */
  private openProduction(): Array<{ video: ContentVideo; channel: ContentChannel }> {
    return this.videos()
      .filter((x) => x.status === 'approved' || x.status === 'producing')
      .flatMap((video) => {
        const channel = this.channel(video.channelId);
        return channel ? [{ video, channel }] : [];
      });
  }

  /**
   * A newly approved idea: Reel gets the whole list of open ones and works through it. A wake in the last
   * ten minutes already told him to re-check the list after every cut, so he is not woken again for each
   * idea of a batch (the 12 h reminder catches anything he leaves).
   */
  private wakeReel(videoId: string): void {
    const at = this.now;
    if (at - (this.asked.get('reel:batch') ?? -Infinity) < 10 * 60_000) return;
    const items = this.openProduction();
    if (this.wake(REEL, produceBatchPrompt(items, this.d.names('user')))) {
      this.asked.set('reel:batch', at);
      for (const i of items) this.asked.set(`${i.video.id}:produce`, at);
    } else this.feed(`${this.d.names(REEL)} is off shift: ${videoId} waits until they are back`);
  }

  // ---- the publisher -----------------------------------------------------------------------------

  markPublished(id: string, url: string): ContentVideo {
    const v = this.requireVideo(id);
    if (v.status !== 'publishing') throw new ContentError(`${v.id} is ${v.status}, not being published: nothing to mark`);
    const { url: link, id: youtubeVideoId } = canonicalVideoUrl(url, v.format);
    const at = this.now;
    const { error: _e, ...base } = v;
    const next = this.saveVideo({ ...base, status: 'published', publishedAt: at, updatedAt: at, url: link, youtubeVideoId });
    this.feed(`${this.label(next)} is live: ${next.url}`, CLEO);
    this.d.notify('info', `Published on YouTube: ${next.url}`);
    return next;
  }

  markFailed(id: string, error: string): ContentVideo {
    const v = this.requireVideo(id);
    if (v.status !== 'publishing') throw new ContentError(`${v.id} is ${v.status}, not being published: nothing to mark`);
    const next = this.saveVideo({ ...v, status: 'failed', error: truncate(error.trim() || 'unknown error', 500), updatedAt: this.now });
    this.feed(`${this.label(next)} failed to upload: ${truncate(next.error ?? '', 200)}`, CLEO);
    this.d.notify('warn', `${this.label(next)} did not go out: ${truncate(next.error ?? '', 140)}`);
    return next;
  }

  // ---- the analyst -------------------------------------------------------------------------------

  writePostMortem(videoId: string, horizon: '48h' | '7d', text: string, by: string): ContentVideo {
    const v = this.requireVideo(videoId);
    if (v.status !== 'published') throw new ContentError(`${v.id} is ${v.status}: post-mortems are for published videos`);
    const body = limited('the post-mortem', text, POST_MORTEM_MAX);
    if (!body) throw new ContentError('the post-mortem is empty');
    const others = (v.postMortems ?? []).filter((p) => p.horizon !== horizon);
    const next = this.saveVideo({ ...v, postMortems: [...others, { at: this.now, horizon, text: body }].sort((a, b) => a.at - b.at), updatedAt: this.now });
    this.feed(`${this.d.names(by)} wrote the ${horizon} post-mortem of ${this.label(next)}`, by);
    return next;
  }

  /** Numbers for the get_analytics tool. */
  analyticsText(opts: { channelId?: string; videoId?: string }): string {
    if (opts.videoId) {
      const v = this.requireVideo(opts.videoId);
      const history = v.youtubeVideoId ? this.statsOf(v.youtubeVideoId) : [];
      return describeStats({ channel: this.requireChannel(v.channelId), ...(this.channelStats(v.channelId)[0] ? { channelStats: this.channelStats(v.channelId)[0]! } : {}), videos: [{ video: v, history }], now: this.now });
    }
    const channel = this.resolveChannel(opts.channelId);
    const published = this.list({ channelId: channel.id, status: 'published' }).sort((a, b) => (b.publishedAt ?? 0) - (a.publishedAt ?? 0));
    const cs = this.channelStats(channel.id)[0];
    return describeStats({
      channel,
      ...(cs ? { channelStats: cs } : {}),
      videos: published.map((video) => ({ video, history: video.youtubeVideoId ? this.statsOf(video.youtubeVideoId) : [] })),
      now: this.now,
    });
  }

  // ---- references --------------------------------------------------------------------------------

  /** Study a video frame by frame (yt-dlp + ffmpeg, injected) and keep it as a reference. */
  async study(source: string, opts: StudyOptions, by: string): Promise<{ ref: ContentRef; result: StudyResult; breakdown: string }> {
    const api = this.d.study;
    if (!api) throw new ContentError('video study is not available in this build');
    const src = source.trim();
    if (!src) throw new ContentError('study_video needs a URL or a file path');
    const id = this.d.ctx.store.nextId('r');
    const dir = path.join(this.dir, 'refs', id);
    fs.mkdirSync(dir, { recursive: true });
    let result: StudyResult;
    try {
      result = await api.studyVideo(/^https?:\/\//i.test(src) ? src : this.localSource(src), dir, opts);
    } catch (e) {
      fs.rmSync(dir, { recursive: true, force: true });
      throw new ContentError((e as Error).message);
    }
    const breakdown = api.renderBreakdown(result);
    fs.writeFileSync(path.join(dir, 'breakdown.md'), breakdown);
    const ref: ContentRef = {
      id,
      source: src,
      ...(result.title ? { title: result.title } : {}),
      ...(result.channel ? { channel: result.channel } : {}),
      ...(result.durationSec !== undefined ? { durationSec: result.durationSec } : {}),
      ...(result.views !== undefined ? { views: result.views } : {}),
      ...(result.likes !== undefined ? { likes: result.likes } : {}),
      ...(result.uploadDate ? { uploadDate: result.uploadDate } : {}),
      dir: result.dir || dir,
      frameCount: result.frames.length,
      sheetCount: result.sheets.length,
      hasTranscript: !!result.transcriptFile,
      studiedBy: by,
      at: this.now,
    };
    this.saveRef(ref);
    this.feed(`${this.d.names(by)} studied ${ref.title ? `"${truncate(ref.title, 60)}"` : src} frame by frame (${ref.frameCount} frames, ${ref.sheetCount} contact sheets) as ${ref.id}`, by);
    return { ref, result, breakdown };
  }

  /** A channel's most viewed videos (for competitor research). */
  async scan(url: string, limit?: number): Promise<ChannelScan> {
    if (!this.d.study) throw new ContentError('channel scans are not available in this build');
    try {
      return await this.d.study.scanChannel(normalizeChannelUrl(url).url, limit);
    } catch (e) {
      if (e instanceof ContentError) throw e;
      throw new ContentError((e as Error).message);
    }
  }

  /** A local file to study: it must really live inside the content workspace (symlinks resolved). */
  private localSource(src: string): string {
    const file = path.resolve(this.dir, src.replace(/^~(?=$|[\\/])/, process.env.HOME ?? '~'));
    let real: string;
    let root: string;
    try {
      real = fs.realpathSync(file);
      root = fs.realpathSync(this.dir);
    } catch {
      throw new ContentError(`no file at ${file}`);
    }
    if (!real.startsWith(root + path.sep)) throw new ContentError(`local study sources must be inside the content workspace (${root}): copy the video there first, or use its URL`);
    return real;
  }

  private saveRef(ref: ContentRef): void {
    const all = [...this.data.refs.filter((r) => r.id !== ref.id), ref].sort((a, b) => a.at - b.at);
    const dropped = all.slice(0, Math.max(0, all.length - REFS_KEEP));
    this.patchData({ refs: all.slice(dropped.length) });
    const root = path.join(this.dir, 'refs') + path.sep;
    for (const r of dropped) if (r.dir.startsWith(root)) fs.rmSync(r.dir, { recursive: true, force: true });
    this.d.ctx.emit({ type: 'content.ref.upsert', ref: { ...ref } });
  }

  /** Files of a reference: contact sheets and frames, in order. */
  refFiles(refId: string): { sheets: string[]; frames: string[] } {
    const ref = this.requireRef(refId);
    const list = (sub: string) => {
      const d = path.join(ref.dir, sub);
      try {
        return fs.readdirSync(d).filter((f) => /\.(jpe?g|png)$/i.test(f)).sort().map((f) => path.join(d, f));
      } catch {
        return [];
      }
    };
    return { sheets: list('sheets'), frames: list('frames') };
  }

  /** Show a reference's contact sheets (or one frame, 1-based) on the Screening Room screen. */
  /**
   * The user watches a finished cut in game (Pipeline "Watch"): the whole Short (up to PREVIEW_MAX_SECONDS) becomes a
   * flipbook on the Screening Room screen; the mod opens it, and "Open in player" plays it with sound.
   */
  async previewVideo(videoId: string): Promise<{ id: string }> {
    const v = this.requireVideo(videoId);
    const show = this.d.showMedia;
    if (!show) throw new ContentError('the Screening Room is not available');
    if (!v.filePath || !fs.existsSync(v.filePath)) throw new ContentError(`${v.id} has no cut yet`);
    try {
      const media = (await show(REEL, v.filePath, `${v.title} — the cut`, { maxSeconds: PREVIEW_MAX_SECONDS, maxFrames: PREVIEW_MAX_FRAMES })) as { id?: unknown };
      if (typeof media?.id !== 'string') throw new ContentError('the Screening Room did not return the video');
      return { id: media.id };
    } catch (e) {
      if (e instanceof ContentError) throw e;
      throw new ContentError(`cannot show the cut: ${(e as Error).message}`);
    }
  }

  async openRef(refId: string, frame?: number): Promise<{ shown: number }> {
    const ref = this.requireRef(refId);
    const show = this.d.showMedia;
    if (!show) throw new ContentError('the Screening Room is not available');
    const { sheets, frames } = this.refFiles(refId);
    const title = ref.title ?? ref.source;
    let files: string[];
    if (frame !== undefined) {
      const f = frames[frame - 1];
      if (!f) throw new ContentError(`${ref.id} has ${frames.length} frames, no frame ${frame}`);
      files = [f];
    } else {
      files = sheets.slice(0, 4);
      if (!files.length) throw new ContentError(`${ref.id} has no contact sheets (${ref.dir})`);
    }
    let shown = 0;
    for (const f of files) {
      try {
        await show(ref.studiedBy, f, `${title} — ${path.basename(f)}`);
        shown++;
      } catch (e) {
        throw new ContentError(`cannot show ${path.basename(f)}: ${(e as Error).message}`);
      }
    }
    return { shown };
  }

  // ---- analytics sync ------------------------------------------------------------------------------

  /** Refresh the numbers of one or all channels (OAuth where connected, public numbers otherwise). */
  sync(channelId?: string): Promise<SyncReport> {
    const scope = channelId ?? '*';
    if (this.syncing) {
      // the same scope (or everything) is already running: share it; another channel waits its turn
      if (this.syncScope === '*' || this.syncScope === scope) return this.syncing;
      return this.syncing.catch(() => undefined).then(() => this.sync(channelId));
    }
    const run = this.runSync(channelId).finally(() => {
      this.syncing = undefined;
      this.syncScope = undefined;
    });
    this.syncing = run;
    this.syncScope = scope;
    return run;
  }

  private async runSync(channelId?: string): Promise<SyncReport> {
    const channels = channelId ? [this.requireChannel(channelId)] : this.channels();
    const report: SyncReport = { channels: 0, videos: 0, lines: [] };
    const results: Array<{ c: ContentChannel; got: Awaited<ReturnType<ContentStudio['syncChannel']>> }> = [];
    for (const c of channels) {
      try {
        const got = await this.syncChannel(c);
        results.push({ c, got });
        report.channels++;
        report.videos += got.stats.length;
        report.lines.push(`${c.name}: ${got.source}, ${got.stats.length} video${got.stats.length === 1 ? '' : 's'} updated`);
      } catch (e) {
        report.lines.push(`${c.name}: sync failed — ${(e as Error).message}`);
        this.d.ctx.log.warn(`content: sync ${c.id}: ${(e as Error).message}`);
      }
    }
    const refStats = await this.syncRefs(report);
    // the awaits above took a while: merge into what the store holds NOW, for channels that still exist
    const live = new Set(this.data.channels.map((c) => c.id));
    const own = this.ownIds();
    let stats = this.data.stats;
    let channelStats = this.data.channelStats;
    for (const { c, got } of results) {
      if (!live.has(c.id)) continue;
      stats = got.stats.filter((s) => own.has(s.videoId)).reduce((acc, s) => mergeStat(acc, s, true), stats);
      if (got.channel) channelStats = [...channelStats.filter((x) => x.channelId !== c.id), got.channel];
      if (got.youtubeId && !c.youtubeId && !this.requireChannel(c.id).youtubeId) this.saveChannel({ ...this.requireChannel(c.id), youtubeId: got.youtubeId, updatedAt: this.now });
    }
    stats = refStats.reduce((acc, s) => mergeStat(acc, s, false), stats);
    this.patchData({ stats, channelStats, lastSyncAt: this.now });
    this.d.ctx.emit({ type: 'content.stats', stats: stats.map((s) => ({ ...s })), channelStats: channelStats.map((s) => ({ ...s })) });
    return report;
  }

  private ownIds(): Set<string> {
    return new Set(this.data.videos.map((v) => v.youtubeVideoId).filter((x): x is string => !!x));
  }

  private async syncChannel(c: ContentChannel): Promise<{ stats: ContentStat[]; channel?: ContentChannelStats; source: string; youtubeId?: string }> {
    const ids = this.data.videos.filter((v) => v.channelId === c.id && v.status === 'published' && v.youtubeVideoId).map((v) => v.youtubeVideoId!);
    const api = this.d.youtube;
    if (api && c.youtubeId && api.connectedChannels().includes(c.youtubeId)) {
      const { youtubeChannelId: _y, ...cs } = await api.channelStats(c.youtubeId, 28);
      const stats = ids.length ? await api.videoStats(c.youtubeId, ids) : [];
      return { stats, channel: { ...cs, channelId: c.id, at: cs.at ?? this.now }, source: 'OAuth analytics' };
    }
    // public numbers: the channel page (subscribers, view counts of its videos) and per-video numbers
    if (!this.d.publicStats) throw new ContentError('this channel is not connected to YouTube analytics (run youtube-connect) and public numbers are not available');
    const got = await this.d.publicStats({ ...(c.url ? { channelUrl: c.url } : {}), videoIds: ids, limit: 50 });
    const own = new Set(ids);
    const channel: ContentChannelStats = { channelId: c.id, at: this.now, ...(got.channel?.subscribers !== undefined ? { subscribers: got.channel.subscribers } : {}) };
    return { stats: got.stats.filter((s) => own.has(s.videoId)), channel, source: 'public numbers', ...(got.channel?.youtubeChannelId ? { youtubeId: got.channel.youtubeChannelId } : {}) };
  }

  /** Views/likes of the newest references, from public numbers (errors are ignored). */
  private async syncRefs(report: SyncReport): Promise<ContentStat[]> {
    if (!this.d.publicStats) return [];
    const ids = this.data.refs.slice(-10).flatMap((r) => (parseVideoId(r.source) && /^https?:/i.test(r.source) ? [parseVideoId(r.source)!] : []));
    if (!ids.length) return [];
    try {
      const got = (await this.d.publicStats({ videoIds: ids })).stats;
      report.lines.push(`references: ${got.length} updated`);
      return got;
    } catch (e) {
      this.d.ctx.log.debug(`content: reference stats: ${(e as Error).message}`);
      return [];
    }
  }

  // ---- numbers read from YouTube Studio (Chrome) -----------------------------------------------------

  /** Wake Vega with a Studio read for these channels (only ones with a published video). Returns whether a job started. */
  requestStudioRead(channelIds: string[], why: string, force = false): boolean {
    if (!this.runner?.studioAvailable?.() || !this.runner.canRun(VEGA)) return false;
    const at = this.now;
    const items = channelIds.flatMap((id) => {
      const channel = this.channel(id);
      const videos = this.videos().filter((v) => v.channelId === id && v.status === 'published').sort((a, b) => (b.publishedAt ?? 0) - (a.publishedAt ?? 0));
      const recent = at - (this.asked.get(`studio:${id}`) ?? -Infinity) < (force ? 10 * 60_000 : 2 * 3_600_000);
      return channel && videos.length && !recent ? [{ channel, videos }] : [];
    });
    if (!items.length) return false;
    for (const i of items) this.asked.set(`studio:${i.channel.id}`, at);
    return this.wake(VEGA, studioReadPrompt(items, why, at), 'analytics');
  }

  /** The 6 h timer of the Chrome reads: channels whose last read is older than the sync interval. */
  private studioReads(): void {
    const syncMs = this.d.syncMs ?? SYNC_MS;
    const due = this.channels().filter((c) => this.now - (this.data.studioReads?.[c.id] ?? 0) >= syncMs).map((c) => c.id);
    if (due.length) this.requestStudioRead(due, 'every 6 hours');
  }

  /**
   * Numbers Vega read in YouTube Studio, merged exactly like an API sync: bounded history for our own
   * videos, the latest only for others, then `content.stats`.
   */
  recordStats(raw: RecordStatsInput, by: string): string {
    const parsed = RecordStatsInput.safeParse(raw);
    if (!parsed.success) throw new ContentError(`record_stats: ${parsed.error.issues.slice(0, 4).map((i) => `${i.path.join('.') || '(root)'}: ${i.message}`).join('; ')}`);
    const input = parsed.data;
    const channel = this.resolveChannel(input.channelId);
    const at = this.now;
    const own = this.ownIds();
    const fresh: ContentStat[] = [];
    const skipped: string[] = [];
    for (const v of input.videos) {
      const id = parseVideoId(v.youtubeVideoId ?? v.url ?? '');
      if (!id) {
        skipped.push(truncate(v.youtubeVideoId ?? v.url ?? '(no id)', 40));
        continue;
      }
      const { youtubeVideoId: _y, url: _u, ...numbers } = v;
      fresh.push({ videoId: id, at, ...numbers });
    }
    let stats = this.data.stats;
    for (const s of fresh) stats = mergeStat(stats, s, own.has(s.videoId));
    let channelStats = this.data.channelStats;
    if (input.channel) {
      const prev = channelStats.find((x) => x.channelId === channel.id);
      channelStats = [...channelStats.filter((x) => x.channelId !== channel.id), { ...prev, ...input.channel, channelId: channel.id, at }];
    }
    this.patchData({ stats, channelStats, lastSyncAt: at, studioReads: { ...this.data.studioReads, [channel.id]: at } });
    this.saveChannel({ ...this.requireChannel(channel.id) }); // statsSource / connected changed
    this.d.ctx.emit({ type: 'content.stats', stats: stats.map((s) => ({ ...s })), channelStats: channelStats.map((s) => ({ ...s })) });
    this.feed(`${this.d.names(by)} read ${channel.name} in YouTube Studio: ${fresh.length} video${fresh.length === 1 ? '' : 's'}${input.channel ? ' and the channel numbers' : ''}`, by);
    return `Recorded ${fresh.length} video${fresh.length === 1 ? '' : 's'}${input.channel ? ' and the channel numbers' : ''} for ${channel.name}.${skipped.length ? ` Skipped (no YouTube video id): ${skipped.join(', ')}.` : ''}`;
  }

  // ---- scheduler -----------------------------------------------------------------------------------

  /** Start the scheduler. A video left 'publishing' by a previous run is failed, never retried. */
  start(): void {
    this.closed = false;
    for (const v of this.videos().filter((x) => x.status === 'publishing')) {
      this.d.ctx.log.warn(`content: ${v.id} was publishing when the Foreman stopped: marked failed`);
      this.markFailed(v.id, INTERRUPTED);
    }
    if (this.timer) clearInterval(this.timer);
    this.timer = setInterval(() => this.tick(), this.d.tickMs ?? CONTENT_TICK_MS); // tick() never throws
    this.timer.unref?.();
  }

  stop(): void {
    this.closed = true;
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
  }

  /** One scheduler beat: publish what is due, refresh the numbers, ask for post-mortems, keep the queue full. Never throws. */
  tick(): void {
    if (this.closed) return;
    for (const step of [() => this.failStalePublishing(), () => this.publishDue(), () => this.maybeSync(), () => this.studioReads(), () => this.askPostMortems(), () => this.rewakeProduction(), () => this.topUpAll()]) {
      try {
        step();
      } catch (e) {
        this.d.ctx.log.error(`content: scheduler step failed: ${(e as Error).stack ?? e}`);
      }
    }
  }

  private maybeSync(): void {
    const syncMs = this.d.syncMs ?? SYNC_MS;
    if (this.data.channels.length && !this.syncing && this.now - (this.data.lastSyncAt ?? 0) >= syncMs && this.data.videos.some((v) => v.status === 'published')) {
      void this.sync().catch((e: Error) => this.d.ctx.log.warn(`content: sync failed: ${e.message}`));
    }
  }

  private topUpAll(): void {
    for (const c of this.channels()) if (c.autopilot) this.topUp(c);
  }

  /** 'publishing' with no report for a long time (Cleo paused, busy, signed out): fail it so the queue moves on. */
  private failStalePublishing(): void {
    for (const v of this.videos().filter((x) => x.status === 'publishing' && this.now - x.updatedAt > PUBLISH_DEADLINE_MS)) {
      this.d.ctx.log.warn(`content: ${v.id} was publishing for over ${PUBLISH_DEADLINE_MS / 60_000} min: marked failed`);
      if (this.current === v.id) this.current = undefined;
      this.markFailed(v.id, `${INTERRUPTED} (no result after ${PUBLISH_DEADLINE_MS / 60_000} minutes)`);
    }
  }

  /** Approved / producing videos nobody is working on: ask Reel again now and then; tell the user about a stuck one once. */
  private rewakeProduction(): void {
    const at = this.now;
    for (const v of this.videos().filter((x) => x.status === 'approved' || x.status === 'producing')) {
      if (v.status === 'producing' && at - v.updatedAt > STALE_PRODUCTION_MS && !this.staleNotified.has(v.id)) {
        this.staleNotified.add(v.id);
        this.d.notify('warn', `${this.label(v)} has been in production for over ${STALE_PRODUCTION_MS / 3_600_000} hours without a cut`);
      }
    }
    const stuck = this.openProduction().filter(({ video }) => at - (this.asked.get(`${video.id}:produce`) ?? -Infinity) >= REASK_MS);
    if (!stuck.length || !this.runner?.canRun(REEL)) return;
    for (const i of stuck) this.asked.set(`${i.video.id}:produce`, at);
    this.wake(REEL, productionReminderPrompt(stuck, this.d.names('user')));
  }

  /** Publish the earliest due scheduled video, if nothing is being published. */
  private publishDue(): void {
    if (this.current || !this.runner?.canRun(CLEO)) return;
    const at = this.now;
    const due = this.videos()
      .filter((v) => v.status === 'scheduled' && v.scheduledAt !== undefined && v.scheduledAt <= at)
      .sort((a, b) => a.scheduledAt! - b.scheduledAt!)[0];
    if (!due) return;
    const channel = this.channel(due.channelId);
    if (!channel) {
      this.saveVideo({ ...due, status: 'failed', error: 'its channel was removed', updatedAt: at });
      return;
    }
    if (due.autoScheduled && !channel.autopilot) {
      this.backToReview(due);
      return;
    }
    if (due.scheduledAt! < at - MISSED_SLOT_MS && due.autoScheduled) {
      // autopilot has nobody to re-approve it: take the next free slot of the cadence
      const slot = this.freeSlot(due.channelId);
      this.saveVideo({ ...due, scheduledAt: slot, updatedAt: at });
      this.feed(`${this.label(due)} missed its slot: autopilot moved it to ${formatWhen(slot)}`);
      return;
    }
    if (due.scheduledAt! < at - MISSED_SLOT_MS) {
      this.backToReview(due, `missed its slot (${formatWhen(due.scheduledAt!)}): approve the cut again with a new time`);
      this.d.notify('warn', `${this.label(due)} was due ${formatWhen(due.scheduledAt!)} but the Foreman was not running: it is back in review, pick a new time`);
      return;
    }
    const expected = this.data.cutHashes?.[due.id];
    if (due.filePath && expected && fs.existsSync(due.filePath) && hashFile(due.filePath) !== expected) {
      this.saveVideo({ ...due, status: 'failed', error: 'the cut file changed after it was approved: review it again before publishing', updatedAt: at });
      this.d.notify('warn', `${this.label(due)} was not published: its cut file changed after you approved it`);
      return;
    }
    if (!due.filePath || !fs.existsSync(due.filePath)) {
      this.saveVideo({ ...due, status: 'failed', error: `the cut file is gone${due.filePath ? `: ${due.filePath}` : ''}`, updatedAt: at });
      this.d.notify('warn', `${this.label(due)} cannot be published: the cut file is missing`);
      return;
    }
    const video = this.saveVideo({ ...due, status: 'publishing', updatedAt: at });
    this.current = video.id;
    this.feed(`${this.d.names(CLEO)} is uploading ${this.label(video)} to YouTube now`, CLEO);
    let run: Promise<{ ok: boolean; error?: string }>;
    try {
      run = Promise.resolve(this.runner.publish({ ...video }, channel));
    } catch (e) {
      run = Promise.reject(e);
    }
    void run.then(
      (o) => this.finish(video.id, o),
      (e: Error) => this.finish(video.id, { ok: false, error: e.message }),
    );
  }

  private finish(id: string, outcome: { ok: boolean; error?: string }): void {
    if (this.current === id) this.current = undefined;
    if (this.closed) return; // shutting down: the next start fails what is still 'publishing'
    try {
      if (this.video(id)?.status === 'publishing') {
        this.markFailed(id, outcome.error ?? 'the publisher ended without reporting a result — check YouTube Studio before retrying');
      }
    } catch (e) {
      this.d.ctx.log.error(`content: finishing ${id}: ${(e as Error).stack ?? e}`);
    }
    this.tick();
  }

  /** Vega writes a post-mortem 48 h and 7 d after a video went live. */
  private askPostMortems(): void {
    if (!this.runner?.canRun(VEGA)) return;
    const at = this.now;
    for (const v of this.videos().filter((x) => x.status === 'published' && x.publishedAt !== undefined)) {
      const channel = this.channel(v.channelId);
      if (!channel) continue;
      for (const h of HORIZONS) {
        const key = `${v.id}:${h.horizon}`;
        if (at - v.publishedAt! < h.ms || v.postMortems?.some((p) => p.horizon === h.horizon)) continue;
        if (at - (this.asked.get(key) ?? -Infinity) < REASK_MS) continue;
        this.asked.set(key, at);
        const studio = this.runner.studioAvailable?.() === true;
        const mine = this.videos().filter((x) => x.channelId === channel.id && x.status === 'published').sort((a, b) => (b.publishedAt ?? 0) - (a.publishedAt ?? 0));
        this.wake(VEGA, postMortemPrompt(v, channel, h.horizon, studio ? { videos: mine, now: at } : undefined), studio ? 'analytics' : 'content');
        return; // one job per beat
      }
    }
  }

  /** Autopilot: wake Iris when fewer than TARGET_QUEUE videos are upcoming. */
  private topUp(c: ContentChannel, force = false): void {
    if (!c.autopilot) return;
    const upcoming = this.videos().filter((v) => v.channelId === c.id && UPCOMING.has(v.status)).length;
    if (upcoming >= targetQueue(c.postsPerWeek)) return;
    const key = `topup:${c.id}`;
    const at = this.now;
    // a fast cadence drains the queue in a day: ask again after 6 h instead of 12
    if (!force && at - (this.asked.get(key) ?? -Infinity) < (c.postsPerWeek >= 21 ? REASK_MS / 2 : REASK_MS)) return;
    if (this.wake(IRIS, topUpPrompt(c, upcoming))) this.asked.set(key, at);
  }
}

/** Fields only a Studio/API read gives: a later public snapshot (views, likes) keeps the last read's values for a day. */
const DEEP_FIELDS = ['avgViewDurationSec', 'avgViewPct', 'watchMinutes', 'subsGained', 'impressions', 'ctr', 'retention', 'stayedToWatchPct', 'swipeAwayPct'] as const;

/** Add a snapshot: own videos keep a history (the newest STATS_HISTORY), others only the latest. */
export function mergeStat(stats: ContentStat[], s: ContentStat, own: boolean): ContentStat[] {
  const same = stats.filter((x) => x.videoId === s.videoId).sort((a, b) => a.at - b.at);
  const rest = stats.filter((x) => x.videoId !== s.videoId);
  const last = same[same.length - 1];
  const carried: ContentStat = last && s.at - last.at < 24 * 3_600_000 ? { ...s, ...Object.fromEntries(DEEP_FIELDS.filter((k) => s[k] === undefined && last[k] !== undefined).map((k) => [k, last[k]])) } : s;
  if (!own) return [...rest, carried];
  // a snapshot minutes after the last one replaces it instead of crowding the history
  const history = last && s.at - last.at < 10 * 60_000 ? [...same.slice(0, -1), carried] : [...same, carried];
  return [...rest, ...history.slice(-STATS_HISTORY)];
}

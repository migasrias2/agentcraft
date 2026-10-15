// YouTube analytics for the Content Room. OAuth "installed app" tokens (one per YouTube channel, under
// ~/.agentcraft/youtube/tokens), a small client for the Data API v3 + Analytics API v2, and `publicStats`, the
// no-login fallback built on yt-dlp. Tokens are refreshed on demand and saved with mode 600; they are never logged
// and never appear in an error message. Uploading is deliberately NOT here (see the Content Room spec).
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { scanChannel, videoInfo, type Exec } from './videostudy.js';

export class YouTubeError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
  }
}

// Structurally the same as ContentStat / ContentChannelStats in protocol.ts (zod types live there).
export interface YtStat {
  videoId: string;
  at: number;
  views: number;
  likes?: number;
  comments?: number;
  avgViewDurationSec?: number;
  avgViewPct?: number; // 0..100, as the Analytics API reports it
  watchMinutes?: number;
  subsGained?: number;
  impressions?: number;
  ctr?: number; // 0..1
  retention?: number[]; // 21 points: audience watch ratio at 0,5,...,100% of the video
}

export interface YtChannelStats {
  youtubeChannelId: string;
  at: number;
  subscribers?: number;
  totalViews?: number;
  videoCount?: number;
  views28d?: number;
  watchMinutes28d?: number;
  subsGained28d?: number;
  daily?: { date: string; views: number; subsGained?: number; watchMinutes?: number }[];
}

export interface YouTubeApi {
  /** YouTube channel ids that have a saved token. */
  connectedChannels(): string[];
  channelStats(youtubeChannelId: string, days?: number): Promise<YtChannelStats>;
  videoStats(youtubeChannelId: string, videoIds: string[]): Promise<YtStat[]>;
}

export interface YouTubeOpts {
  dir?: string; // default ~/.agentcraft/youtube
  fetch?: typeof fetch;
  now?: () => number;
}

export const YT_SCOPES = ['https://www.googleapis.com/auth/youtube.readonly', 'https://www.googleapis.com/auth/yt-analytics.readonly'];
const DATA_API = 'https://www.googleapis.com/youtube/v3';
const ANALYTICS_API = 'https://youtubeanalytics.googleapis.com/v2/reports';
const DEFAULT_AUTH_URI = 'https://accounts.google.com/o/oauth2/v2/auth';
const DEFAULT_TOKEN_URI = 'https://oauth2.googleapis.com/token';
const EXPIRY_SLACK_MS = 60_000;
const RETENTION_POINTS = 21;
const CHANNEL_ID_RE = /^[\w-]{3,64}$/;

export const SETUP_HELP = [
  'No Google OAuth client found. One-time setup (about 3 minutes):',
  '  1. Open https://console.cloud.google.com/ and create (or pick) a project.',
  '  2. APIs & Services > Library: enable "YouTube Data API v3" and "YouTube Analytics API".',
  '  3. APIs & Services > OAuth consent screen: User type External, add yourself as a Test user, add the scopes',
  '     youtube.readonly and yt-analytics.readonly.',
  '  4. APIs & Services > Credentials > Create credentials > OAuth client ID > Application type "Desktop app".',
  '  5. Download the JSON and save it as ~/.agentcraft/youtube/client_secret.json',
  '  6. Run this command again; pick the brand account / channel in Google\'s chooser.',
].join('\n');

export function youtubeDir(dir?: string): string {
  return dir ?? path.join(os.homedir(), '.agentcraft', 'youtube');
}

// ---------- client secret + tokens on disk ----------

export interface ClientSecret {
  clientId: string;
  clientSecret: string;
  authUri: string;
  tokenUri: string;
}

export interface StoredToken {
  access_token: string;
  refresh_token: string;
  expires_at: number; // ms epoch
  scope?: string;
  title?: string; // channel title, for humans
}

export function readClientSecret(dir?: string): ClientSecret {
  const file = path.join(youtubeDir(dir), 'client_secret.json');
  if (!fs.existsSync(file)) throw new YouTubeError(`${SETUP_HELP}\n(expected ${file})`);
  try {
    const j = JSON.parse(fs.readFileSync(file, 'utf8')) as Record<string, { client_id?: string; client_secret?: string; auth_uri?: string; token_uri?: string }>;
    const c = j.installed ?? j.web;
    if (!c?.client_id || !c.client_secret) throw new Error('missing client_id/client_secret');
    return { clientId: c.client_id, clientSecret: c.client_secret, authUri: c.auth_uri ?? DEFAULT_AUTH_URI, tokenUri: c.token_uri ?? DEFAULT_TOKEN_URI };
  } catch (e) {
    throw new YouTubeError(`client_secret.json is not a Google OAuth client file (${(e as Error).message}); download the "Desktop app" client JSON again`);
  }
}

function tokensDir(dir?: string): string {
  return path.join(youtubeDir(dir), 'tokens');
}

function tokenFile(dir: string | undefined, channelId: string): string {
  if (!CHANNEL_ID_RE.test(channelId)) throw new YouTubeError(`bad YouTube channel id "${channelId}"`);
  return path.join(tokensDir(dir), `${channelId}.json`);
}

export function readToken(channelId: string, dir?: string): StoredToken | undefined {
  const file = tokenFile(dir, channelId);
  if (!fs.existsSync(file)) return undefined;
  try {
    const t = JSON.parse(fs.readFileSync(file, 'utf8')) as StoredToken;
    return t.refresh_token ? t : undefined;
  } catch {
    return undefined;
  }
}

/** Atomic write, file 600 inside a 700 directory. */
export function saveToken(channelId: string, token: StoredToken, dir?: string): void {
  const file = tokenFile(dir, channelId);
  fs.mkdirSync(youtubeDir(dir), { recursive: true, mode: 0o700 });
  fs.mkdirSync(tokensDir(dir), { recursive: true, mode: 0o700 });
  fs.chmodSync(youtubeDir(dir), 0o700);
  fs.chmodSync(tokensDir(dir), 0o700);
  const tmp = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(token, null, 2), { mode: 0o600 });
  fs.chmodSync(tmp, 0o600);
  fs.renameSync(tmp, file);
}

// ---------- OAuth (loopback + PKCE) ----------

export interface Pkce {
  verifier: string;
  challenge: string;
  state: string;
}

export function makePkce(): Pkce {
  const verifier = crypto.randomBytes(48).toString('base64url');
  return { verifier, challenge: crypto.createHash('sha256').update(verifier).digest('base64url'), state: crypto.randomBytes(16).toString('base64url') };
}

export function buildAuthUrl(c: ClientSecret, redirectUri: string, pkce: Pkce): string {
  const q = new URLSearchParams({
    client_id: c.clientId,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: YT_SCOPES.join(' '),
    access_type: 'offline',
    prompt: 'consent select_account', // consent guarantees a refresh token; select_account lets you pick the brand channel
    code_challenge: pkce.challenge,
    code_challenge_method: 'S256',
    state: pkce.state,
  });
  return `${c.authUri}?${q}`;
}

interface TokenResponse {
  access_token?: string;
  refresh_token?: string;
  expires_in?: number;
  scope?: string;
  error?: string;
  error_description?: string;
}

async function tokenRequest(c: ClientSecret, body: Record<string, string>, f: typeof fetch): Promise<TokenResponse> {
  let res: Response;
  try {
    res = await f(c.tokenUri, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ client_id: c.clientId, client_secret: c.clientSecret, ...body }) });
  } catch (e) {
    throw new YouTubeError(`could not reach Google (${(e as Error).message})`);
  }
  const j = (await res.json().catch(() => ({}))) as TokenResponse;
  if (!res.ok || j.error) {
    if (j.error === 'invalid_grant') throw new YouTubeError('reconnect: run youtube-connect', res.status);
    throw new YouTubeError(`Google token request failed: ${j.error_description ?? j.error ?? `HTTP ${res.status}`}`, res.status);
  }
  return j;
}

/** Trade the loopback `code` for tokens, look up which channel they belong to and save them. */
export async function connectWithCode(code: string, pkce: Pkce, redirectUri: string, opts: YouTubeOpts = {}): Promise<{ channelId: string; title: string }> {
  const f = opts.fetch ?? fetch;
  const now = opts.now ?? Date.now;
  const c = readClientSecret(opts.dir);
  const j = await tokenRequest(c, { grant_type: 'authorization_code', code, code_verifier: pkce.verifier, redirect_uri: redirectUri }, f);
  if (!j.access_token || !j.refresh_token) throw new YouTubeError('Google returned no refresh token; remove the app at https://myaccount.google.com/permissions and run youtube-connect again');
  const res = await f(`${DATA_API}/channels?part=snippet&mine=true`, { headers: { authorization: `Bearer ${j.access_token}` } });
  const body = (await res.json().catch(() => ({}))) as { items?: { id: string; snippet?: { title?: string } }[]; error?: { message?: string } };
  if (!res.ok) throw new YouTubeError(`channels.list failed: ${body.error?.message ?? `HTTP ${res.status}`}`, res.status);
  const ch = body.items?.[0];
  if (!ch) throw new YouTubeError('that Google account has no YouTube channel; pick the brand account in the chooser');
  const title = ch.snippet?.title ?? ch.id;
  saveToken(ch.id, { access_token: j.access_token, refresh_token: j.refresh_token, expires_at: now() + (j.expires_in ?? 3600) * 1000, scope: j.scope, title }, opts.dir);
  return { channelId: ch.id, title };
}

// ---------- API client ----------

interface Row extends Array<string | number> {}
interface Report {
  columnHeaders?: { name: string }[];
  rows?: Row[];
}

const isoDay = (ms: number): string => new Date(ms).toISOString().slice(0, 10);

export function createYouTubeApi(opts: YouTubeOpts = {}): YouTubeApi {
  const f = opts.fetch ?? fetch;
  const now = opts.now ?? Date.now;
  const dir = opts.dir;

  async function accessToken(channelId: string, force = false): Promise<string> {
    const t = readToken(channelId, dir);
    if (!t) throw new YouTubeError(`channel ${channelId} is not connected: run youtube-connect`);
    if (!force && t.expires_at - EXPIRY_SLACK_MS > now()) return t.access_token;
    const j = await tokenRequest(readClientSecret(dir), { grant_type: 'refresh_token', refresh_token: t.refresh_token }, f);
    if (!j.access_token) throw new YouTubeError('Google returned no access token on refresh; run youtube-connect');
    saveToken(channelId, { ...t, access_token: j.access_token, refresh_token: j.refresh_token ?? t.refresh_token, expires_at: now() + (j.expires_in ?? 3600) * 1000, scope: j.scope ?? t.scope }, dir);
    return j.access_token;
  }

  async function get<T>(channelId: string, url: string): Promise<T> {
    for (let attempt = 0; ; attempt++) {
      const token = await accessToken(channelId, attempt > 0);
      let res: Response;
      try {
        res = await f(url, { headers: { authorization: `Bearer ${token}`, accept: 'application/json' } });
      } catch (e) {
        throw new YouTubeError(`could not reach YouTube (${(e as Error).message})`);
      }
      if (res.status === 401 && attempt === 0) continue; // expired earlier than we thought: refresh once and retry
      const body = (await res.json().catch(() => ({}))) as { error?: { message?: string; code?: number } };
      if (!res.ok) throw new YouTubeError(`YouTube API ${res.status}: ${body.error?.message ?? 'request failed'}`, res.status);
      return body as T;
    }
  }

  const report = (channelId: string, params: Record<string, string>): Promise<Report> =>
    get<Report>(channelId, `${ANALYTICS_API}?${new URLSearchParams({ ids: 'channel==MINE', ...params })}`);

  /** Analytics requests that may be refused (a metric the channel lacks, a video that is not ours) yield undefined; reconnect errors still throw. */
  async function tryReport(channelId: string, params: Record<string, string>): Promise<Report | undefined> {
    try {
      return await report(channelId, params);
    } catch (e) {
      if (e instanceof YouTubeError && (e.status === 400 || e.status === 403 || e.status === 404)) return undefined;
      throw e;
    }
  }

  async function enrich(channelId: string, base: YtStat, publishedAt: string | undefined): Promise<YtStat> {
    const range = { startDate: publishedAt ? publishedAt.slice(0, 10) : '2005-02-14', endDate: isoDay(now()) };
    const filters = `video==${base.videoId}`;
    let stat = base;
    const main = await tryReport(channelId, { ...range, filters, metrics: 'views,estimatedMinutesWatched,averageViewDuration,averageViewPercentage,subscribersGained,likes,comments' });
    const r = main?.rows?.[0];
    if (r) {
      stat = {
        ...stat,
        // the Data API count is the fresher public number; Analytics fills what only the owner can see
        watchMinutes: Number(r[1]) || 0,
        avgViewDurationSec: Number(r[2]) || 0,
        avgViewPct: Number(r[3]) || 0,
        subsGained: Number(r[4]) || 0,
        likes: stat.likes ?? (Number(r[5]) || 0),
        comments: stat.comments ?? (Number(r[6]) || 0),
      };
    }
    // Impressions/CTR are not offered to every channel or API version: a 400 just leaves them out.
    const imp = await tryReport(channelId, { ...range, filters, metrics: 'videoThumbnailImpressions,videoThumbnailImpressionsClickRate' });
    const ir = imp?.rows?.[0];
    if (ir) {
      const rate = Number(ir[1]);
      stat = { ...stat, impressions: Number(ir[0]) || 0, ...(Number.isFinite(rate) ? { ctr: rate > 1 ? rate / 100 : rate } : {}) };
    }
    const ret = await tryReport(channelId, { ...range, filters, metrics: 'audienceWatchRatio', dimensions: 'elapsedVideoTimeRatio', sort: 'elapsedVideoTimeRatio' });
    const curve = sampleRetention(ret?.rows);
    return curve ? { ...stat, retention: curve } : stat;
  }

  return {
    connectedChannels(): string[] {
      try {
        return fs.readdirSync(tokensDir(dir)).filter((n) => n.endsWith('.json')).map((n) => n.slice(0, -5)).filter((id) => CHANNEL_ID_RE.test(id)).sort();
      } catch {
        return [];
      }
    },

    async channelStats(youtubeChannelId, days = 28) {
      const end = now();
      const [data, daily] = await Promise.all([
        get<{ items?: { statistics?: { subscriberCount?: string; viewCount?: string; videoCount?: string; hiddenSubscriberCount?: boolean } }[] }>(
          youtubeChannelId,
          `${DATA_API}/channels?part=statistics&id=${encodeURIComponent(youtubeChannelId)}`,
        ),
        report(youtubeChannelId, {
          startDate: isoDay(end - days * 86_400_000),
          endDate: isoDay(end),
          metrics: 'views,estimatedMinutesWatched,subscribersGained',
          dimensions: 'day',
          sort: 'day',
        }),
      ]);
      const st = data.items?.[0]?.statistics;
      const rows = (daily.rows ?? []).map((r) => ({ date: String(r[0]), views: Number(r[1]) || 0, watchMinutes: Number(r[2]) || 0, subsGained: Number(r[3]) || 0 }));
      const sum = (k: 'views' | 'watchMinutes' | 'subsGained'): number => rows.reduce((a, r) => a + r[k], 0);
      return {
        youtubeChannelId,
        at: end,
        ...(st?.subscriberCount !== undefined && !st.hiddenSubscriberCount ? { subscribers: Number(st.subscriberCount) } : {}),
        ...(st?.viewCount !== undefined ? { totalViews: Number(st.viewCount) } : {}),
        ...(st?.videoCount !== undefined ? { videoCount: Number(st.videoCount) } : {}),
        views28d: sum('views'),
        watchMinutes28d: sum('watchMinutes'),
        subsGained28d: sum('subsGained'),
        daily: rows,
      };
    },

    async videoStats(youtubeChannelId, videoIds) {
      const ids = [...new Set(videoIds)].filter((v) => /^[\w-]{6,20}$/.test(v));
      const at = now();
      const out: YtStat[] = [];
      for (let i = 0; i < ids.length; i += 50) {
        const batch = ids.slice(i, i + 50);
        const data = await get<{ items?: { id: string; snippet?: { publishedAt?: string }; statistics?: { viewCount?: string; likeCount?: string; commentCount?: string } }[] }>(
          youtubeChannelId,
          `${DATA_API}/videos?part=snippet,statistics&id=${batch.join(',')}`,
        );
        for (const item of data.items ?? []) {
          const base: YtStat = {
            videoId: item.id,
            at,
            views: Number(item.statistics?.viewCount) || 0,
            ...(item.statistics?.likeCount !== undefined ? { likes: Number(item.statistics.likeCount) } : {}),
            ...(item.statistics?.commentCount !== undefined ? { comments: Number(item.statistics.commentCount) } : {}),
          };
          out.push(await enrich(youtubeChannelId, base, item.snippet?.publishedAt));
        }
      }
      return out;
    },
  };
}

/** Rows [elapsedVideoTimeRatio 0.01..1, audienceWatchRatio] → 21 points at 0,5,..100%. */
export function sampleRetention(rows: Row[] | undefined): number[] | undefined {
  if (!rows?.length) return undefined;
  const pts = rows.map((r) => ({ x: Number(r[0]), y: Number(r[1]) })).filter((p) => Number.isFinite(p.x) && Number.isFinite(p.y)).sort((a, b) => a.x - b.x);
  if (!pts.length) return undefined;
  return Array.from({ length: RETENTION_POINTS }, (_, i) => {
    const x = i / (RETENTION_POINTS - 1);
    const nearest = pts.reduce((best, p) => (Math.abs(p.x - x) < Math.abs(best.x - x) ? p : best), pts[0]!);
    return Math.round(nearest.y * 1000) / 1000;
  });
}

// ---------- public stats (no OAuth) ----------

export interface PublicStatsResult {
  stats: YtStat[];
  channel?: { name?: string; youtubeChannelId?: string; subscribers?: number };
}

/**
 * Views/likes/comments without logging in: a channel scan (views of its latest videos) and/or yt-dlp per video id.
 * `exec` is the same injectable runner as videostudy's.
 */
export async function publicStats(input: { channelUrl?: string; videoIds?: string[]; limit?: number }, opts: { exec?: Exec; now?: () => number } = {}): Promise<PublicStatsResult> {
  const now = opts.now ?? Date.now;
  const deps = { exec: opts.exec };
  const at = now();
  const byId = new Map<string, YtStat>();
  let channel: PublicStatsResult['channel'];
  if (input.channelUrl) {
    const scan = await scanChannel(input.channelUrl, input.limit ?? 50, deps);
    channel = { name: scan.channel, youtubeChannelId: scan.channelId, subscribers: scan.subscriberCount };
    for (const v of scan.videos) byId.set(v.youtubeId, { videoId: v.youtubeId, at, views: v.views });
  }
  const ids = [...new Set(input.videoIds ?? [])];
  for (let i = 0; i < ids.length; i += 3) {
    await Promise.all(
      ids.slice(i, i + 3).map(async (id) => {
        try {
          const info = await videoInfo(id, deps);
          byId.set(id, {
            videoId: id,
            at,
            views: info.view_count ?? 0,
            ...(info.like_count !== undefined ? { likes: info.like_count } : {}),
            ...(info.comment_count !== undefined ? { comments: info.comment_count } : {}),
          });
        } catch {
          /* private/removed video: skipped */
        }
      }),
    );
  }
  return { stats: [...byId.values()], ...(channel ? { channel } : {}) };
}

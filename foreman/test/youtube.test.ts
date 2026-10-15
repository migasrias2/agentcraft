// YouTube client: token storage + refresh, channel/video stats, impressions fallback, OAuth code exchange (mocked fetch).
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { buildAuthUrl, connectWithCode, createYouTubeApi, makePkce, publicStats, readClientSecret, sampleRetention, saveToken, YouTubeError } from '../src/youtube.js';
import type { Exec } from '../src/videostudy.js';
import { rmrf, tempDir } from './helpers.js';

const cleanup: Array<() => void> = [];
afterEach(() => {
  for (const f of cleanup.splice(0)) f();
});

const NOW = Date.UTC(2026, 9, 14, 12);
const SECRET = { installed: { client_id: 'cid', client_secret: 'csecret', auth_uri: 'https://auth.test/o', token_uri: 'https://token.test/t' } };

function setup(token = true) {
  const dir = tempDir('ac-yt-');
  cleanup.push(() => rmrf(dir));
  fs.writeFileSync(path.join(dir, 'client_secret.json'), JSON.stringify(SECRET));
  if (token) saveToken('UCabc', { access_token: 'AT1', refresh_token: 'RT1', expires_at: NOW + 3_600_000 }, dir);
  return dir;
}

type Handler = (url: URL, init?: RequestInit) => { status?: number; body: unknown } | undefined;
function mockFetch(handler: Handler) {
  const calls: { url: URL; init?: RequestInit }[] = [];
  const f = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input));
    calls.push({ url, init });
    const r = handler(url, init) ?? { status: 404, body: { error: { message: 'unhandled' } } };
    return new Response(JSON.stringify(r.body), { status: r.status ?? 200, headers: { 'content-type': 'application/json' } });
  }) as typeof fetch;
  return { f, calls };
}

const auth = (init?: RequestInit): string => (init?.headers as Record<string, string>).authorization!;

describe('tokens on disk', () => {
  it('saves 600 in a 700 directory and lists connected channels', () => {
    const dir = setup();
    expect(fs.statSync(path.join(dir, 'tokens', 'UCabc.json')).mode & 0o777).toBe(0o600);
    expect(fs.statSync(path.join(dir, 'tokens')).mode & 0o777).toBe(0o700);
    expect(createYouTubeApi({ dir }).connectedChannels()).toEqual(['UCabc']);
    expect(createYouTubeApi({ dir: path.join(dir, 'nothing') }).connectedChannels()).toEqual([]);
  });
  it('rejects path-like channel ids', () => {
    expect(() => saveToken('../evil', { access_token: 'a', refresh_token: 'b', expires_at: 1 }, setup(false))).toThrow(YouTubeError);
  });
  it('explains how to set up when client_secret.json is missing', () => {
    const dir = tempDir('ac-yt-');
    cleanup.push(() => rmrf(dir));
    expect(() => readClientSecret(dir)).toThrow(/Desktop app/);
  });
});

describe('refresh', () => {
  it('refreshes an expired token, saves it, keeps the refresh token', async () => {
    const dir = setup(false);
    saveToken('UCabc', { access_token: 'OLD', refresh_token: 'RT1', expires_at: NOW - 1000 }, dir);
    const { f, calls } = mockFetch((u) => {
      if (u.host === 'token.test') return { body: { access_token: 'NEW', expires_in: 3600 } };
      if (u.pathname.endsWith('/channels')) return { body: { items: [{ statistics: { subscriberCount: '10', viewCount: '99', videoCount: '3' } }] } };
      return { body: { rows: [] } };
    });
    await createYouTubeApi({ dir, fetch: f, now: () => NOW }).channelStats('UCabc');
    const refresh = calls.find((c) => c.url.host === 'token.test')!;
    expect(String(refresh.init!.body)).toContain('grant_type=refresh_token');
    expect(auth(calls.find((c) => c.url.host !== 'token.test')!.init)).toBe('Bearer NEW');
    const saved = JSON.parse(fs.readFileSync(path.join(dir, 'tokens', 'UCabc.json'), 'utf8'));
    expect(saved).toMatchObject({ access_token: 'NEW', refresh_token: 'RT1', expires_at: NOW + 3_600_000 });
    expect(fs.statSync(path.join(dir, 'tokens', 'UCabc.json')).mode & 0o777).toBe(0o600);
  });

  it('turns invalid_grant into a reconnect message without leaking secrets', async () => {
    const dir = setup(false);
    saveToken('UCabc', { access_token: 'OLD', refresh_token: 'RT1', expires_at: NOW - 1000 }, dir);
    const { f } = mockFetch(() => ({ status: 400, body: { error: 'invalid_grant' } }));
    const err = await createYouTubeApi({ dir, fetch: f, now: () => NOW }).channelStats('UCabc').catch((e) => e);
    expect(err).toBeInstanceOf(YouTubeError);
    expect(err.message).toBe('reconnect: run youtube-connect');
  });

  it('retries once on 401 with a fresh token', async () => {
    const dir = setup();
    let n = 0;
    const { f, calls } = mockFetch((u) => {
      if (u.host === 'token.test') return { body: { access_token: 'FRESH', expires_in: 3600 } };
      if (u.pathname.endsWith('/channels')) return n++ === 0 ? { status: 401, body: { error: { message: 'expired' } } } : { body: { items: [{ statistics: {} }] } };
      return { body: { rows: [] } };
    });
    await createYouTubeApi({ dir, fetch: f, now: () => NOW }).channelStats('UCabc');
    expect(calls.some((c) => c.url.host === 'token.test')).toBe(true);
  });

  it('errors for a channel that was never connected', async () => {
    const api = createYouTubeApi({ dir: setup(false), fetch: mockFetch(() => undefined).f, now: () => NOW });
    await expect(api.channelStats('UCnope')).rejects.toThrow(/youtube-connect/);
  });
});

describe('channelStats', () => {
  it('combines Data API totals with 28 daily analytics rows', async () => {
    const { f, calls } = mockFetch((u) => {
      if (u.pathname.endsWith('/channels')) return { body: { items: [{ statistics: { subscriberCount: '1200', viewCount: '55000', videoCount: '40' } }] } };
      return { body: { rows: [['2026-10-12', 100, 30, 2], ['2026-10-13', 150, 45, 3]] } };
    });
    const s = await createYouTubeApi({ dir: setup(), fetch: f, now: () => NOW }).channelStats('UCabc');
    expect(s).toMatchObject({ youtubeChannelId: 'UCabc', at: NOW, subscribers: 1200, totalViews: 55000, videoCount: 40, views28d: 250, watchMinutes28d: 75, subsGained28d: 5 });
    expect(s.daily).toEqual([
      { date: '2026-10-12', views: 100, watchMinutes: 30, subsGained: 2 },
      { date: '2026-10-13', views: 150, watchMinutes: 45, subsGained: 3 },
    ]);
    const rep = calls.find((c) => c.url.host === 'youtubeanalytics.googleapis.com')!.url.searchParams;
    expect(rep.get('ids')).toBe('channel==MINE');
    expect(rep.get('dimensions')).toBe('day');
    expect(rep.get('startDate')).toBe('2026-09-16');
    expect(rep.get('endDate')).toBe('2026-10-14');
  });
});

describe('videoStats', () => {
  const videos = { items: [{ id: 'vid00001', snippet: { publishedAt: '2026-10-10T08:00:00Z' }, statistics: { viewCount: '900', likeCount: '40', commentCount: '7' } }] };
  const retention = Array.from({ length: 100 }, (_, i) => [(i + 1) / 100, 1 - i / 200]);

  it('merges Data API, Analytics, impressions/CTR and a 21-point retention curve', async () => {
    const { f, calls } = mockFetch((u) => {
      if (u.pathname.endsWith('/videos')) return { body: videos };
      const m = u.searchParams.get('metrics')!;
      if (m.startsWith('views,')) return { body: { rows: [[901, 300, 22.5, 61.2, 4, 41, 8]] } };
      if (m.startsWith('videoThumbnail')) return { body: { rows: [[5000, 4.5]] } };
      return { body: { rows: retention } };
    });
    const [s] = await createYouTubeApi({ dir: setup(), fetch: f, now: () => NOW }).videoStats('UCabc', ['vid00001', 'vid00001']);
    expect(s).toMatchObject({ videoId: 'vid00001', views: 900, likes: 40, comments: 7, watchMinutes: 300, avgViewDurationSec: 22.5, avgViewPct: 61.2, subsGained: 4, impressions: 5000, ctr: 0.045 });
    expect(s!.retention).toHaveLength(21);
    expect(s!.retention![0]).toBeCloseTo(1, 1);
    expect(s!.retention![20]).toBeCloseTo(0.5, 1);
    const per = calls.filter((c) => c.url.host === 'youtubeanalytics.googleapis.com');
    expect(per.every((c) => c.url.searchParams.get('filters') === 'video==vid00001')).toBe(true);
    expect(per[0]!.url.searchParams.get('startDate')).toBe('2026-10-10');
    expect(calls.filter((c) => c.url.pathname.endsWith('/videos'))).toHaveLength(1);
  });

  it('leaves impressions/CTR undefined when the API rejects them (400)', async () => {
    const { f } = mockFetch((u) => {
      if (u.pathname.endsWith('/videos')) return { body: videos };
      const m = u.searchParams.get('metrics')!;
      if (m.startsWith('videoThumbnail')) return { status: 400, body: { error: { message: 'The query is not supported.' } } };
      if (m.startsWith('views,')) return { body: { rows: [[901, 300, 22.5, 61.2, 4, 41, 8]] } };
      return { body: { rows: retention } };
    });
    const [s] = await createYouTubeApi({ dir: setup(), fetch: f, now: () => NOW }).videoStats('UCabc', ['vid00001']);
    expect(s!.impressions).toBeUndefined();
    expect(s!.ctr).toBeUndefined();
    expect(s!.avgViewPct).toBe(61.2);
    expect(s!.retention).toHaveLength(21);
  });

  it('still returns public numbers when Analytics refuses the video (403)', async () => {
    const { f } = mockFetch((u) => (u.pathname.endsWith('/videos') ? { body: videos } : { status: 403, body: { error: { message: 'forbidden' } } }));
    const [s] = await createYouTubeApi({ dir: setup(), fetch: f, now: () => NOW }).videoStats('UCabc', ['vid00001']);
    expect(s).toEqual({ videoId: 'vid00001', at: NOW, views: 900, likes: 40, comments: 7 });
  });

  it('ignores malformed ids and returns nothing for an empty list', async () => {
    const { f, calls } = mockFetch(() => undefined);
    expect(await createYouTubeApi({ dir: setup(), fetch: f, now: () => NOW }).videoStats('UCabc', ['a b', '../x'])).toEqual([]);
    expect(calls).toHaveLength(0);
  });
});

describe('sampleRetention', () => {
  it('is undefined without rows', () => expect(sampleRetention([])).toBeUndefined());
});

describe('OAuth connect', () => {
  it('builds a PKCE auth url with both scopes and offline access', () => {
    const p = makePkce();
    const u = new URL(buildAuthUrl(readClientSecret(setup(false)), 'http://127.0.0.1:5555', p));
    expect(u.origin + u.pathname).toBe('https://auth.test/o');
    expect(u.searchParams.get('code_challenge_method')).toBe('S256');
    expect(u.searchParams.get('code_challenge')).toBe(p.challenge);
    expect(u.searchParams.get('state')).toBe(p.state);
    expect(u.searchParams.get('access_type')).toBe('offline');
    expect(u.searchParams.get('scope')).toContain('yt-analytics.readonly');
    expect(u.searchParams.get('scope')).toContain('youtube.readonly');
    expect(u.searchParams.get('redirect_uri')).toBe('http://127.0.0.1:5555');
  });

  it('exchanges the code, finds the channel and saves its token', async () => {
    const dir = setup(false);
    const { f, calls } = mockFetch((u) => {
      if (u.host === 'token.test') return { body: { access_token: 'AT', refresh_token: 'RT', expires_in: 3599, scope: 's' } };
      return { body: { items: [{ id: 'UCnew', snippet: { title: 'My Channel' } }] } };
    });
    const pk = makePkce();
    const r = await connectWithCode('the-code', pk, 'http://127.0.0.1:1', { dir, fetch: f, now: () => NOW });
    expect(r).toEqual({ channelId: 'UCnew', title: 'My Channel' });
    const body = String(calls[0]!.init!.body);
    expect(body).toContain('code=the-code');
    expect(body).toContain(`code_verifier=${pk.verifier}`);
    expect(JSON.parse(fs.readFileSync(path.join(dir, 'tokens', 'UCnew.json'), 'utf8'))).toMatchObject({ access_token: 'AT', refresh_token: 'RT', expires_at: NOW + 3_599_000, title: 'My Channel' });
  });

  it('fails clearly when Google sends no refresh token', async () => {
    const { f } = mockFetch(() => ({ body: { access_token: 'AT', expires_in: 3600 } }));
    await expect(connectWithCode('c', makePkce(), 'http://127.0.0.1:1', { dir: setup(false), fetch: f })).rejects.toThrow(/refresh token/);
  });
});

describe('publicStats', () => {
  it('uses the channel scan and per-video info', async () => {
    const exec: Exec = async (_c, args) => {
      const u = args.at(-1)!;
      if (u.includes('/videos')) return { stdout: JSON.stringify({ channel: 'C', channel_id: 'UC1', channel_follower_count: 77, entries: [{ id: 'a1', title: 't', view_count: 10 }] }), stderr: '' };
      if (u.includes('/shorts')) throw Object.assign(new Error('x'), { stderr: 'no shorts' });
      return { stdout: JSON.stringify({ id: 'b2', view_count: 55, like_count: 5, comment_count: 1 }), stderr: '' };
    };
    const r = await publicStats({ channelUrl: 'https://www.youtube.com/@c', videoIds: ['b2'] }, { exec, now: () => NOW });
    expect(r.channel).toEqual({ name: 'C', youtubeChannelId: 'UC1', subscribers: 77 });
    expect(r.stats).toEqual([
      { videoId: 'a1', at: NOW, views: 10 },
      { videoId: 'b2', at: NOW, views: 55, likes: 5, comments: 1 },
    ]);
  });
});

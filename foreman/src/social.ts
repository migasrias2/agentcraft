// The Social Desk: a calendar of posts for X and LinkedIn. Nova (the planner) drafts them, the user
// approves each ONCE in the calendar, and at its scheduled time Sable (the publisher) posts it in
// the user's own Chrome (Claude in Chrome) without asking again.
//
//   draft --approve--> approved --(time reached)--> posting --mark_posted--> posted
//     ^                    |                           |
//     +---- unapprove -----+                           +--mark_failed / turn ended--> failed
//   (editing an approved post, or changing its time, sends it back to draft)
//   cancel: draft | approved | failed -> cancelled;   failed can be approved again.
//
// This module owns the state machine, the validation and the scheduler. The turn that publishes is
// injected (`PublishRunner`, set by the team backend), so the scheduler is testable without agents.
// A post is never published twice by accident: after a restart a 'posting' post is marked failed
// ("check the platform before re-approving") and never retried by itself.
import fs from 'node:fs';
import path from 'node:path';
import type { Ctx } from './context.js';
import type { MessageBus } from './bus.js';
import type { SocialPlatform, SocialPost } from './protocol.js';
import { truncate } from './util/text.js';

export class SocialError extends Error {}

/** Time zone of the calendars: AGENTCRAFT_TZ (IANA name, e.g. Europe/Lisbon), else this machine's zone. */
export const SOCIAL_TZ = process.env.AGENTCRAFT_TZ || Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
export const X_LIMIT = 280;
/** X Premium (only when the user said they have it) */
export const X_LONG_LIMIT = 25_000;
export const LINKEDIN_LIMIT = 3000;
export const MAX_MEDIA = 4;
/** finished (posted/cancelled) posts kept besides every open one */
export const SOCIAL_KEEP_FINISHED = 100;
export const SOCIAL_TICK_MS = 30_000;
export const INTERRUPTED = 'interrupted — check the platform before re-approving';

const PLATFORM_NAME: Record<SocialPlatform, string> = { x: 'X', linkedin: 'LinkedIn' };
const OPEN: ReadonlySet<SocialPost['status']> = new Set(['draft', 'approved', 'posting', 'failed']);
/** statuses whose content may still be changed */
const EDITABLE: ReadonlySet<SocialPost['status']> = new Set(['draft', 'approved', 'failed']);

export const platformName = (p: SocialPlatform): string => PLATFORM_NAME[p];

// ---- text, time, media ------------------------------------------------------------------------

export function textLimit(platform: SocialPlatform, allowLong = false): number {
  return platform === 'linkedin' ? LINKEDIN_LIMIT : allowLong ? X_LONG_LIMIT : X_LIMIT;
}

/** The trimmed text, or a SocialError saying what is wrong. Characters are counted as code points. */
export function validateText(platform: SocialPlatform, text: string, allowLong = false): string {
  const t = text.replace(/\r\n/g, '\n').trim();
  if (!t) throw new SocialError('the post text is empty');
  const limit = textLimit(platform, allowLong);
  const n = [...t].length;
  if (n > limit) {
    throw new SocialError(`the ${PLATFORM_NAME[platform]} post is ${n} characters, the limit is ${limit}${platform === 'x' && !allowLong ? ' (allow_long only if the user said they have X Premium)' : ''}: shorten it by ${n - limit}`);
  }
  return t;
}

function tzOffsetMs(ts: number, tz: string): number {
  const f = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const p = Object.fromEntries(f.formatToParts(new Date(ts)).map((x) => [x.type, x.value]));
  return Date.UTC(+p.year!, +p.month! - 1, +p.day!, +p.hour!, +p.minute!, +p.second!) - Math.floor(ts / 1000) * 1000;
}

/** The instant a wall-clock time has in `tz` (DST aware). */
function zonedToEpoch(y: number, mo: number, d: number, h: number, mi: number, s: number, tz: string): number {
  const guess = Date.UTC(y, mo - 1, d, h, mi, s);
  const o1 = tzOffsetMs(guess, tz);
  const t = guess - o1;
  const o2 = tzOffsetMs(t, tz);
  return o2 === o1 ? t : guess - o2;
}

const WHEN_RE = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?\s*(Z|[+-]\d{2}:?\d{2})?$/i;

/**
 * An ISO date-time to epoch ms. Without an offset it is SOCIAL_TZ wall-clock time. A bare date
 * ("2026-10-14") needs `dateOnly` ('start' | 'end' of that day), used by list filters.
 */
export function parseWhen(input: string, dateOnly?: 'start' | 'end'): number {
  const m = WHEN_RE.exec(input.trim());
  if (!m) throw new SocialError(`cannot read the date "${input}": use ISO 8601, e.g. 2026-10-14T09:30 (${SOCIAL_TZ} time unless it has an offset like +01:00 or Z)`);
  const [, ys, mos, ds, hs, mis, ss, off] = m;
  const [y, mo, d] = [+ys!, +mos!, +ds!];
  if (hs === undefined && !dateOnly) throw new SocialError(`"${input}" has no time of day: use e.g. ${ys}-${mos}-${ds}T09:30`);
  const [h, mi, s] = hs === undefined ? (dateOnly === 'end' ? [23, 59, 59] : [0, 0, 0]) : [+hs, +mis!, ss ? +ss : 0];
  const probe = new Date(Date.UTC(y, mo - 1, d, h, mi, s));
  if (probe.getUTCFullYear() !== y || probe.getUTCMonth() !== mo - 1 || probe.getUTCDate() !== d || h > 23 || mi > 59) throw new SocialError(`"${input}" is not a real date and time`);
  if (off) {
    const sign = off.startsWith('-') ? -1 : 1;
    const digits = off.replace(/[^0-9]/g, '');
    const offMin = off.toUpperCase() === 'Z' ? 0 : sign * (+digits.slice(0, 2) * 60 + +digits.slice(2, 4));
    return probe.getTime() - offMin * 60_000;
  }
  return zonedToEpoch(y, mo, d, h, mi, s, SOCIAL_TZ);
}

/** "Wed, 14 Oct, 09:30" in SOCIAL_TZ. */
export function formatWhen(ts: number): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone: SOCIAL_TZ, weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(ts));
}

/** "Wednesday 14 October 2026, 09:30 (Europe/Lisbon)" for the agents' instructions. */
export function formatNow(ts: number): string {
  const f = new Intl.DateTimeFormat('en-GB', { timeZone: SOCIAL_TZ, weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  const p = Object.fromEntries(f.formatToParts(new Date(ts)).map((x) => [x.type, x.value]));
  return `${p.weekday} ${p.day} ${p.month} ${p.year}, ${p.hour}:${p.minute} (${SOCIAL_TZ})`;
}

/** Media files must exist; they are stored as absolute paths. */
export function resolveMedia(paths: string[] | undefined, base: string): string[] | undefined {
  if (!paths?.length) return undefined;
  if (paths.length > MAX_MEDIA) throw new SocialError(`at most ${MAX_MEDIA} media files per post`);
  return paths.map((raw) => {
    const file = path.resolve(base, raw.trim().replace(/^~(?=$|[\\/])/, process.env.HOME ?? '~'));
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) throw new SocialError(`no media file at ${file}`);
    return file;
  });
}

/** Every open post, plus the newest SOCIAL_KEEP_FINISHED finished ones. Oldest first. */
export function pruneSocialPosts(posts: SocialPost[]): SocialPost[] {
  const finished = posts.filter((p) => !OPEN.has(p.status)).sort((a, b) => b.updatedAt - a.updatedAt);
  const drop = new Set(finished.slice(SOCIAL_KEEP_FINISHED).map((p) => p.id));
  return posts.filter((p) => !drop.has(p.id));
}

export function describePost(p: SocialPost, full = false): string {
  const when = p.scheduledAt !== undefined ? formatWhen(p.scheduledAt) : 'no time yet';
  const body = full ? p.text : truncate(p.text.replace(/\s+/g, ' '), 140);
  return `${p.id} [${p.status}] ${PLATFORM_NAME[p.platform]} · ${when} · "${body}"${p.note ? ` — ${truncate(p.note, 100)}` : ''}${p.url ? ` → ${p.url}` : ''}${p.error ? ` ✗ ${truncate(p.error, 140)}` : ''}${p.mediaPaths?.length ? ` · media: ${p.mediaPaths.length}` : ''}`;
}

// ---- the publish turn -------------------------------------------------------------------------

/** What the injected runner reports once the publish turn is over. */
export interface PublishOutcome {
  /** false: the turn failed, was stopped or timed out */
  ok: boolean;
  error?: string;
}

export interface PublishRunner {
  /** can a publish turn start now (the publisher is on shift and not paused)? */
  canPublish(): boolean;
  /** run the publish turn; resolves when it is over (the post may already be posted or failed) */
  publish(post: SocialPost): Promise<PublishOutcome>;
}

export function publishPrompt(post: SocialPost, workDir: string): string {
  const site = post.platform === 'x' ? 'https://x.com/compose/post' : 'https://www.linkedin.com/feed/';
  const media = post.mediaPaths?.length
    ? `Attach these files (in this order) with the Chrome file upload tool before publishing:\n${post.mediaPaths.map((m) => `  - ${m}`).join('\n')}\n`
    : 'There is no media to attach.\n';
  return `PUBLISH NOW: post ${post.id} on ${PLATFORM_NAME[post.platform]}. The user approved this exact post in the calendar; it is scheduled for this moment.

Post text (exact, between the markers; do not change a character, keep the line breaks):
<<<POST
${post.text}
POST>>>

${media}
Steps
1. Open ${site} in the user's Chrome (Claude in Chrome tools) and start a new post.
2. Put the text in exactly as written above (typing or pasting; check the box shows the full text and nothing else).
3. ${post.mediaPaths?.length ? 'Attach the media and wait until the upload finished.' : 'No media.'}
4. Publish it (click Post).
5. Open the new post and copy its URL (on X: your profile -> the newest post; on LinkedIn: the post's "Copy link to post" or its address). Then call mark_posted(id "${post.id}", url).
If anything goes wrong (not logged in, a captcha or verification, an error message, the text does not fit, you cannot tell whether it went out), do NOT retry blindly: call mark_failed(id "${post.id}", error: what exactly happened and what you saw), then end your turn. Never post anything else, never edit or delete other posts. Working directory: ${workDir}.`;
}

// ---- the desk ---------------------------------------------------------------------------------

export interface SocialDeskDeps {
  ctx: Ctx;
  bus: MessageBus;
  /** display name of an agent id / "user" */
  names(id: string): string;
  notify(level: 'info' | 'warn' | 'need_user', text: string): void;
  /** the plain workspace directory of the social agents (created here) */
  dir: string;
  tickMs?: number;
}

export interface NewPost {
  platform: SocialPlatform;
  text: string;
  scheduledAt: number;
  note?: string;
  mediaPaths?: string[];
  allowLong?: boolean;
  authorId: string;
}

export interface PostPatch {
  platform?: SocialPlatform;
  text?: string;
  scheduledAt?: number;
  note?: string;
  mediaPaths?: string[];
  allowLong?: boolean;
}

export type SocialAction = 'approve' | 'unapprove' | 'cancel' | 'post_now' | 'schedule';

export class SocialDesk {
  readonly dir: string;
  private runner: PublishRunner | undefined;
  private timer: NodeJS.Timeout | undefined;
  /** the post being published right now (one at a time) */
  private current: string | undefined;
  private closed = false;

  constructor(private d: SocialDeskDeps) {
    this.dir = d.dir;
    fs.mkdirSync(this.dir, { recursive: true });
  }

  private get now(): number {
    return this.d.ctx.now();
  }

  setRunner(runner: PublishRunner | undefined): void {
    this.runner = runner;
  }

  posts(): SocialPost[] {
    return this.d.ctx.store.data.socialPosts ?? [];
  }

  get(id: string): SocialPost | undefined {
    return this.posts().find((p) => p.id === id);
  }

  require(id: string): SocialPost {
    const p = this.get(id);
    if (!p) throw new SocialError(`no post "${id}"`);
    return p;
  }

  /** Posts in a time window / status, oldest scheduled first (posts without a time last). */
  list(filter: { from?: number; to?: number; status?: SocialPost['status'] } = {}): SocialPost[] {
    return this.posts()
      .filter((p) => (!filter.status || p.status === filter.status) && (filter.from === undefined || (p.scheduledAt ?? Infinity) >= filter.from) && (filter.to === undefined || (p.scheduledAt ?? -Infinity) <= filter.to))
      .sort((a, b) => (a.scheduledAt ?? Infinity) - (b.scheduledAt ?? Infinity) || a.createdAt - b.createdAt);
  }

  isPublishing(postId: string | undefined): boolean {
    return !!postId && this.get(postId)?.status === 'posting';
  }

  /** Replace a post, keep the calendar bounded, tell the clients. */
  private save(next: SocialPost): SocialPost {
    const store = this.d.ctx.store;
    const rest = this.posts().filter((p) => p.id !== next.id);
    store.data.socialPosts = pruneSocialPosts([...rest, next].sort((a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id, 'en', { numeric: true })));
    store.markDirty();
    this.d.ctx.emit({ type: 'social.post.upsert', post: { ...next } });
    return next;
  }

  private feed(text: string, by?: string): void {
    this.d.bus.feed('system', text, by ? { agentId: by } : {});
  }

  private label(p: SocialPost): string {
    return `${PLATFORM_NAME[p.platform]} post ${p.id}`;
  }

  // ---- planner side -----------------------------------------------------------------------

  create(input: NewPost): SocialPost {
    const text = validateText(input.platform, input.text, input.allowLong);
    this.requireFuture(input.scheduledAt);
    const mediaPaths = resolveMedia(input.mediaPaths, this.dir);
    const at = this.now;
    const post = this.save({
      id: this.d.ctx.store.nextId('p'),
      platform: input.platform,
      text,
      scheduledAt: input.scheduledAt,
      status: 'draft',
      authorId: input.authorId,
      ...(input.note?.trim() ? { note: input.note.trim().slice(0, 300) } : {}),
      ...(mediaPaths ? { mediaPaths } : {}),
      createdAt: at,
      updatedAt: at,
    });
    this.feed(`${this.d.names(input.authorId)} drafted a ${this.label(post)} for ${formatWhen(input.scheduledAt)}: "${truncate(text.replace(/\s+/g, ' '), 100)}" (waiting for your approval in the calendar)`, input.authorId);
    return post;
  }

  /** Change a draft/failed/approved post. Changing what or when goes back to draft (re-approval). */
  update(id: string, patch: PostPatch, by: string): SocialPost {
    const p = this.require(id);
    if (!EDITABLE.has(p.status)) throw new SocialError(`${p.id} is ${p.status}: only drafts, approved and failed posts can be changed`);
    const platform = patch.platform ?? p.platform;
    const text = patch.text !== undefined || patch.platform ? validateText(platform, patch.text ?? p.text, patch.allowLong) : p.text;
    if (patch.scheduledAt !== undefined) this.requireFuture(patch.scheduledAt);
    const mediaPaths = patch.mediaPaths !== undefined ? resolveMedia(patch.mediaPaths, this.dir) : p.mediaPaths;
    const content = text !== p.text || platform !== p.platform || (patch.scheduledAt !== undefined && patch.scheduledAt !== p.scheduledAt) || JSON.stringify(mediaPaths ?? []) !== JSON.stringify(p.mediaPaths ?? []);
    const back = content && p.status !== 'draft';
    const { error: _error, mediaPaths: _media, note: oldNote, ...base } = p;
    const note = patch.note !== undefined ? patch.note.trim().slice(0, 300) || undefined : oldNote;
    const next = this.save({
      ...base,
      platform,
      text,
      status: back ? 'draft' : p.status,
      ...(patch.scheduledAt !== undefined ? { scheduledAt: patch.scheduledAt } : {}),
      ...(note ? { note } : {}),
      ...(mediaPaths?.length ? { mediaPaths } : {}),
      ...(!back && p.error ? { error: p.error } : {}),
      updatedAt: this.now,
    });
    this.feed(`${this.d.names(by)} updated ${this.label(next)}${back ? `: it is a draft again, approve it once more` : ''}`, by === 'user' ? undefined : by);
    return next;
  }

  cancel(id: string, by: string): SocialPost {
    const p = this.require(id);
    if (p.status === 'cancelled') return p;
    if (p.status === 'posting') throw new SocialError(`${p.id} is being published right now`);
    if (p.status === 'posted') throw new SocialError(`${p.id} is already posted`);
    const next = this.save({ ...p, status: 'cancelled', updatedAt: this.now });
    this.feed(`${this.d.names(by)} cancelled ${this.label(next)}`, by === 'user' ? undefined : by);
    return next;
  }

  // ---- publisher side ---------------------------------------------------------------------

  markPosted(id: string, url: string | undefined): SocialPost {
    const p = this.require(id);
    if (p.status !== 'posting') throw new SocialError(`${p.id} is ${p.status}, not being published: nothing to mark`);
    const at = this.now;
    const { error: _e, ...base } = p;
    const next = this.save({ ...base, status: 'posted', postedAt: at, updatedAt: at, ...(url?.trim() ? { url: url.trim().slice(0, 500) } : {}) });
    this.feed(`${this.label(next)} is live${next.url ? `: ${next.url}` : ''}`, 'sable');
    this.d.notify('info', `Posted on ${PLATFORM_NAME[next.platform]}${next.url ? `: ${next.url}` : ''}`);
    return next;
  }

  markFailed(id: string, error: string): SocialPost {
    const p = this.require(id);
    if (p.status !== 'posting') throw new SocialError(`${p.id} is ${p.status}, not being published: nothing to mark`);
    const next = this.save({ ...p, status: 'failed', error: truncate(error.trim() || 'unknown error', 500), updatedAt: this.now });
    this.feed(`${this.label(next)} failed: ${truncate(next.error ?? '', 200)}`, 'sable');
    this.d.notify('warn', `${this.label(next)} did not go out: ${truncate(next.error ?? '', 140)}`);
    return next;
  }

  // ---- the user's calendar actions -------------------------------------------------------

  act(postId: string, action: SocialAction, opts: { at?: number; text?: string } = {}): SocialPost {
    let p = this.require(postId);
    const user = this.d.names('user');
    if (p.status === 'posted') throw new SocialError(`${p.id} is already posted and cannot change`);
    if (p.status === 'posting') throw new SocialError(`${p.id} is being published right now`);
    if (p.status === 'cancelled') throw new SocialError(`${p.id} was cancelled`);
    if (opts.text !== undefined && opts.text.trim() !== p.text) {
      // the approve itself re-approves the edited text, so the edit does not bounce it to draft first
      p = this.update(p.id, { text: opts.text }, 'user');
    }
    switch (action) {
      case 'approve': {
        if (p.status === 'approved') return p;
        if (p.scheduledAt === undefined) throw new SocialError(`${p.id} has no time yet: schedule it first, or post it now`);
        if (p.scheduledAt <= this.now) throw new SocialError(`${p.id} was scheduled for ${formatWhen(p.scheduledAt)}, which has passed: pick a new time, or post it now`);
        const { error: _e, ...base } = p;
        const next = this.save({ ...base, status: 'approved', updatedAt: this.now });
        this.feed(`${user} approved ${this.label(next)} for ${formatWhen(p.scheduledAt)}: it goes out then, without asking again`);
        return next;
      }
      case 'unapprove': {
        if (p.status !== 'approved') throw new SocialError(`${p.id} is ${p.status}, not approved`);
        const next = this.save({ ...p, status: 'draft', updatedAt: this.now });
        this.feed(`${user} took ${this.label(next)} back to draft`);
        return next;
      }
      case 'cancel':
        return this.cancel(p.id, 'user');
      case 'post_now': {
        const { error: _e, ...base } = p;
        const next = this.save({ ...base, status: 'approved', scheduledAt: this.now, updatedAt: this.now });
        this.feed(`${user} approved ${this.label(next)} to go out right now`);
        this.tick();
        return this.get(next.id) ?? next;
      }
      case 'schedule': {
        if (opts.at === undefined) throw new SocialError('schedule needs a time (at)');
        this.requireFuture(opts.at);
        const back = p.status !== 'draft';
        const { error: _e, ...base } = p;
        const next = this.save({ ...base, status: 'draft', scheduledAt: opts.at, updatedAt: this.now });
        this.feed(`${user} moved ${this.label(next)} to ${formatWhen(opts.at)}${back ? ': it is a draft again, approve it once more' : ''}`);
        return next;
      }
    }
  }

  private requireFuture(at: number): void {
    if (at <= this.now) throw new SocialError(`${formatWhen(at)} is in the past (now: ${formatNow(this.now)})`);
  }

  // ---- scheduler ---------------------------------------------------------------------------

  /** Start the scheduler. A post left 'posting' by a previous run is failed, never retried. */
  start(): void {
    this.closed = false;
    for (const p of this.posts().filter((x) => x.status === 'posting')) {
      this.d.ctx.log.warn(`social: ${p.id} was posting when the Foreman stopped: marked failed`);
      this.markFailed(p.id, INTERRUPTED);
    }
    if (this.timer) clearInterval(this.timer);
    this.timer = setInterval(() => this.tick(), this.d.tickMs ?? SOCIAL_TICK_MS);
    this.timer.unref?.();
  }

  stop(): void {
    this.closed = true;
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
  }

  /** Publish the earliest due approved post, if nothing is being published. */
  tick(): void {
    if (this.closed || this.current || !this.runner?.canPublish()) return;
    const at = this.now;
    const due = this.posts()
      .filter((p) => p.status === 'approved' && p.scheduledAt !== undefined && p.scheduledAt <= at)
      .sort((a, b) => a.scheduledAt! - b.scheduledAt!)[0];
    if (!due) return;
    const post = this.save({ ...due, status: 'posting', updatedAt: at });
    this.current = post.id;
    this.feed(`${this.d.names('sable')} is publishing ${this.label(post)} now`, 'sable');
    let run: Promise<PublishOutcome>;
    try {
      run = Promise.resolve(this.runner.publish({ ...post }));
    } catch (e) {
      run = Promise.reject(e);
    }
    void run.then(
      (o) => this.finish(post.id, o),
      (e: Error) => this.finish(post.id, { ok: false, error: e.message }),
    );
  }

  private finish(id: string, outcome: PublishOutcome): void {
    if (this.current === id) this.current = undefined;
    if (this.closed) return; // shutting down: the next start fails what is still 'posting'
    if (this.get(id)?.status === 'posting') {
      this.markFailed(id, outcome.error ?? 'the publisher ended without reporting a result — check the platform before re-approving');
    }
    this.tick();
  }
}

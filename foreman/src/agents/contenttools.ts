// Team tools of the Content Room agents (never offered to the coding team):
//   all four:           list_channels, list_videos, get_video, comment_video, study_video, scan_channel,
//                       get_analytics, read_playbook
//   Iris (showrunner):  pitch_video, update_video, update_playbook, approve_idea (autopilot channels only)
//   Reel (producer):    start_production, update_video, attach_cut
//   Vega (analyst):     sync_analytics, record_stats, update_playbook, write_post_mortem
//   Cleo (publisher):   mark_published, mark_failed
import path from 'node:path';
import { z } from 'zod';
import { CLEO, ContentError, describeVideo, describeVideoFull, RecordStatsInput, REEL, VEGA } from '../content.js';
import type { Foreman } from '../foreman.js';
import { SocialError } from '../social.js';
import { truncate } from '../util/text.js';
import type { AgentTool, ToolResult } from './tools.js';

type Reply = (text: string, isError?: boolean) => ToolResult;

const Format = z.enum(['short', 'long']);
const Status = z.enum(['idea', 'approved', 'producing', 'review', 'scheduled', 'publishing', 'published', 'rejected', 'failed']);
/** the study output kept in the tool result (the full breakdown stays in breakdown.md) */
const BREAKDOWN_MAX = 7000;
const SHEETS_LISTED = 24;

function tool<S extends z.ZodRawShape>(name: string, description: string, shape: S, handler: (args: z.infer<z.ZodObject<S>>) => Promise<ToolResult> | ToolResult): AgentTool {
  return { name, description, shape, handler: async (a) => handler(a) };
}

/** Run a studio call; a ContentError becomes a tool error the agent can act on. */
async function guarded(reply: Reply, fn: () => Promise<ToolResult> | ToolResult): Promise<ToolResult> {
  try {
    return await fn();
  } catch (e) {
    if (e instanceof ContentError || e instanceof SocialError) return reply(`Error: ${e.message}`, true);
    throw e;
  }
}

export function contentTools(fm: Foreman, agentId: string, reply: Reply, cwd?: string): AgentTool[] {
  const studio = fm.content;
  const base = cwd ?? studio.dir;
  const names = (id: string) => fm.nameOf(id);
  const rel = (p: string) => (path.relative(base, p).startsWith('..') ? p : path.relative(base, p));

  const common: AgentTool[] = [
    tool('list_channels', 'List the YouTube channels of the Content Room: id, name, link, niche, brief, autopilot, cadence, whether OAuth analytics are connected.', {}, () =>
      guarded(reply, () => {
        const all = studio.channels();
        if (!all.length) return reply('No channels yet: the user adds one in the Content Studio.');
        return reply(
          all
            .map((c) => `- ${c.id} "${c.name}"${c.url ? ` ${c.url}` : ''} · autopilot ${c.autopilot ? 'ON' : 'off'} · ${c.postsPerWeek} posts/week · analytics ${c.connected ? 'connected' : 'public only'}${c.niche ? `\n  niche: ${c.niche}` : ''}${c.brief ? `\n  brief: ${c.brief}` : ''}`)
            .join('\n'),
        );
      }),
    ),
    tool(
      'list_videos',
      'List the videos of the pipeline (id, status, format, title, time, hook). Filter by channel and/or status. get_video shows one in full.',
      { channelId: z.string().optional(), status: Status.optional() },
      ({ channelId, status }) =>
        guarded(reply, () => {
          const videos = studio.list({ ...(channelId ? { channelId } : {}), ...(status ? { status } : {}) });
          return reply(videos.length ? videos.map((v) => `- ${describeVideo(v)}`).join('\n') : 'No videos match.');
        }),
    ),
    tool('get_video', 'One video in full: pitch, script, description, tags, references, files, the comment thread with the user and the post-mortems.', { id: z.string() }, ({ id }) =>
      guarded(reply, () => reply(describeVideoFull(studio.requireVideo(id), names, fm.ctx.now()))),
    ),
    tool(
      'comment_video',
      "Write in a video's thread (the user sees it in the Content Studio). Use it to answer the user's comments, explain a change or leave a note for a colleague. It does not wake anyone.",
      { id: z.string(), text: z.string().describe('the comment (<= 2000 characters)') },
      ({ id, text }) => guarded(reply, () => reply(`Commented on ${studio.comment(id, text, agentId).id}.`)),
    ),
    tool(
      'study_video',
      'Study a video frame by frame so you can copy what works: downloads it (YouTube URL or a local file), samples frames (a Short at 2 fps, a long video at 0.5 fps, plus every scene change), builds contact sheets with timestamps, the transcript, the cuts per minute and the shot lengths. Returns the breakdown and where the sheets are; it becomes a reference you can cite in pitch_video (refIds). Use start/end to zoom in frame by frame on one part.',
      {
        source: z.string().describe('a YouTube/video URL or a local file path'),
        fps: z.number().optional().describe('frames per second to keep (default: Shorts 2, long 0.5; max 4)'),
        maxFrames: z.number().int().optional().describe('default 240, at most 600'),
        start: z.number().optional().describe('seconds: start of the window to study'),
        end: z.number().optional().describe('seconds: end of the window'),
      },
      ({ source, fps, maxFrames, start, end }) =>
        guarded(reply, async () => {
          const { ref, result, breakdown } = await studio.study(source, { ...(fps !== undefined ? { fps } : {}), ...(maxFrames !== undefined ? { maxFrames } : {}), ...(start !== undefined ? { start } : {}), ...(end !== undefined ? { end } : {}) }, agentId);
          const sheets = result.sheets.slice(0, SHEETS_LISTED).map((s) => `  - ${rel(s)}`).join('\n');
          const more = result.sheets.length > SHEETS_LISTED ? `\n  … ${result.sheets.length - SHEETS_LISTED} more in ${rel(path.join(ref.dir, 'sheets'))}` : '';
          return reply(
            `Studied ${ref.id}: ${ref.title ?? source}${ref.channel ? ` (${ref.channel})` : ''} — ${ref.frameCount} frames, ${ref.sheetCount} contact sheets${ref.hasTranscript ? ', transcript' : ', no transcript'}. Cite it with refIds ["${ref.id}"].\n\n${truncate(breakdown, BREAKDOWN_MAX)}\n\n` +
              `Read the contact sheets (sheets/*.jpg) with your Read tool to see the frames; open single frames for detail (frames/ names carry the timestamp). The breakdown is also in ${rel(path.join(ref.dir, 'breakdown.md'))}.\nContact sheets (${base}):\n${sheets}${more}`,
          );
        }),
    ),
    tool(
      'scan_channel',
      "List a YouTube channel's most viewed videos (title, views, length, link) from its Videos and Shorts tabs, plus its subscriber count when known. For competitor research and for choosing which videos to study_video.",
      { url: z.string().describe('https://www.youtube.com/@handle (or a channel link)'), limit: z.number().int().min(1).max(100).optional().describe('default 30') },
      ({ url, limit }) =>
        guarded(reply, async () => {
          const scan = await studio.scan(url, limit ?? 30);
          const head = `${scan.channel ?? url}${scan.subscriberCount !== undefined ? ` · ${scan.subscriberCount.toLocaleString('en-US')} subscribers` : ''}`;
          return reply(
            scan.videos.length
              ? `${head}\n${scan.videos.map((v) => `- ${v.views.toLocaleString('en-US')} views · ${v.format ?? (v.durationSec !== undefined && v.durationSec <= 75 ? 'short' : 'long')}${v.durationSec !== undefined ? ` ${Math.round(v.durationSec)} s` : ''}${v.uploadDate ? ` · ${v.uploadDate}` : ''} · "${truncate(v.title, 90)}" ${v.url}`).join('\n')}`
              : `${head}\nNo videos found.`,
          );
        }),
    ),
    tool(
      'get_analytics',
      'The numbers: for a channel (subscribers, 28-day views/watch time/subs, and a table of its published videos) or for one video (views with the change since the last sync, average view duration and %, CTR, impressions, subs gained, retention curve, views over time). Retention/CTR/watch time need OAuth analytics (the channel says if connected); otherwise only public numbers.',
      { channelId: z.string().optional().describe('default: the only channel'), videoId: z.string().optional().describe('a pipeline video id like v3 (instead of channelId)') },
      ({ channelId, videoId }) => guarded(reply, () => reply(studio.analyticsText({ ...(channelId ? { channelId } : {}), ...(videoId ? { videoId } : {}) }))),
    ),
    tool('read_playbook', "A channel's playbook: what works and what does not, with numbers. Read it before every pitch and every cut.", { channelId: z.string().optional().describe('default: the only channel') }, ({ channelId }) =>
      guarded(reply, () => {
        const c = studio.resolveChannel(channelId);
        return reply(studio.readPlaybook(c.id) ?? `The playbook of ${c.name} is empty.`);
      }),
    ),
  ];

  const updateVideo = (allowed: ReadonlySet<string>) =>
    tool(
      'update_video',
      `Change a video's text while it is ${[...allowed].join(' / ')}: title (<= 100 characters), hook, pitch, script, description (<= 5000), tags (all together <= 500 characters), refIds. A scheduled or live video cannot change.`,
      {
        id: z.string(),
        title: z.string().optional(),
        hook: z.string().optional(),
        pitch: z.string().optional(),
        script: z.string().optional(),
        description: z.string().optional(),
        tags: z.array(z.string()).optional(),
        refIds: z.array(z.string()).optional(),
      },
      ({ id, ...patch }) =>
        guarded(reply, () => {
          const v = studio.requireVideo(id);
          if (!allowed.has(v.status)) return reply(`Error: ${v.id} is ${v.status}: you can change it only while it is ${[...allowed].join(' / ')}.`, true);
          const defined = Object.fromEntries(Object.entries(patch).filter(([, x]) => x !== undefined));
          return reply(`Updated ${describeVideo(studio.update(id, defined, agentId))}.`);
        }),
    );

  const playbook = tool(
    'update_playbook',
    "Replace a channel's playbook with the whole new markdown (read_playbook first and keep what is still true). Short and specific: what works, what does not, formats and pacing, experiments to run, each backed by numbers or a video id.",
    { channelId: z.string().optional().describe('default: the only channel'), markdown: z.string() },
    ({ channelId, markdown }) =>
      guarded(reply, () => {
        const c = studio.resolveChannel(channelId);
        studio.writePlaybook(c.id, markdown, agentId);
        return reply(`Updated the playbook of ${c.name}.`);
      }),
  );

  if (agentId === CLEO) {
    return [
      ...common,
      tool(
        'mark_published',
        'Report that the video is live. Call it right after publishing, with the video link you copied from YouTube Studio.',
        { id: z.string().describe('the video id you were asked to publish'), url: z.string().describe('link to the live video (watch?v=..., youtu.be/... or /shorts/...)') },
        ({ id, url }) => guarded(reply, () => reply(`Marked ${studio.markPublished(id, url).id} as published. End your turn.`)),
      ),
      tool(
        'mark_failed',
        'Report that you could NOT publish the video (not logged in, captcha, a YouTube warning, an error, unsure whether it went out). Say exactly what happened. Never retry blindly.',
        { id: z.string(), error: z.string().describe('what happened and what you saw') },
        ({ id, error }) => guarded(reply, () => reply(`Marked ${studio.markFailed(id, error).id} as failed. End your turn.`)),
      ),
    ];
  }

  if (agentId === VEGA) {
    return [
      ...common,
      tool(
        'sync_analytics',
        'Refresh the numbers now (OAuth analytics where the channel is connected, public numbers otherwise) for one channel or all of them. Takes a few seconds to a minute.',
        { channelId: z.string().optional().describe('default: all channels') },
        ({ channelId }) =>
          guarded(reply, async () => {
            const r = await studio.sync(channelId);
            const noApi = studio.channels().filter((c) => (channelId ? c.id === channelId : true) && c.statsSource !== 'api');
            const hint = noApi.length ? `\nNo OAuth analytics for ${noApi.map((c) => c.name).join(', ')}: public numbers have no retention, CTR or watch time. Read them in YouTube Studio with Claude in Chrome (Analytics -> Overview / Content / the video) and record_stats.` : '';
            return reply(`Synced ${r.channels} channel${r.channels === 1 ? '' : 's'}, ${r.videos} video${r.videos === 1 ? '' : 's'}.\n${r.lines.join('\n')}${hint}`);
          }),
      ),
      tool(
        'record_stats',
        'Record numbers you READ in YouTube Studio (Analytics, in the user\'s Chrome) for one channel: channel totals and per-video numbers. Only what you actually saw: leave out the rest, never estimate. avgViewPct 0..100, ctr 0..1, stayedToWatchPct/swipeAwayPct 0..100 (Shorts), retention = 21 values 0..1 at 0,5..100% of the video. Merged like an API sync (history, charts, playbook numbers).',
        RecordStatsInput.shape,
        (args) => guarded(reply, () => reply(studio.recordStats(args, agentId))),
      ),
      playbook,
      tool(
        'write_post_mortem',
        'Write the post-mortem of a published video at its 48h or 7d mark: the numbers, the verdict (did the idea work and why), one concrete change for the next videos. 5-10 lines, <= 4000 characters.',
        { videoId: z.string(), horizon: z.enum(['48h', '7d']), text: z.string() },
        ({ videoId, horizon, text }) => guarded(reply, () => reply(`Saved the ${horizon} post-mortem of ${studio.writePostMortem(videoId, horizon, text, agentId).id}.`)),
      ),
    ];
  }

  if (agentId === REEL) {
    return [
      ...common,
      tool('start_production', 'Take an approved idea into production (approved -> producing). Do this before you start making it.', { id: z.string() }, ({ id }) =>
        guarded(reply, () => reply(`Production started: ${describeVideo(studio.startProduction(id))}.`)),
      ),
      updateVideo(new Set(['idea', 'approved', 'producing', 'review'])),
      tool(
        'attach_cut',
        'Hand in the finished cut (and thumbnail) -> review. The user watches it and approves a publish time (on an autopilot channel it is scheduled by itself). The files must exist; show the cut with show_media too.',
        {
          id: z.string(),
          filePath: z.string().describe('the video file (mp4 mov webm...): absolute, or relative to your working directory'),
          thumbnailPath: z.string().optional().describe('the thumbnail (png jpg webp)'),
        },
        ({ id, filePath, thumbnailPath }) =>
          guarded(reply, () => {
            const v = studio.attachCut(id, filePath, thumbnailPath);
            return reply(`Cut attached: ${describeVideo(v)}. ${v.status === 'scheduled' ? 'Autopilot scheduled it.' : 'It waits for the user\'s review.'} Show it with show_media if you have not.`);
          }),
      ),
    ];
  }

  // Iris, and any other content agent: pitches and the playbook; the user (or autopilot) approves
  return [
    ...common,
    tool(
      'pitch_video',
      'Pitch a video idea. It is an IDEA until the user approves it in the Content Studio (they may reject it or comment: you are woken to revise). Make the hook land in the first 3 seconds; say why it should work (numbers from the playbook or the references) and what it copies.',
      {
        channelId: z.string().optional().describe('default: the only channel'),
        title: z.string().describe('<= 100 characters'),
        format: Format.optional().describe('default short'),
        hook: z.string().describe('the first 3 seconds'),
        pitch: z.string().describe('why this video, what it copies, the expected result (<= 1500 characters)'),
        refIds: z.array(z.string()).optional().describe('studied references (r1, r2...) it is modelled on'),
      },
      ({ channelId, title, format, hook, pitch, refIds }) =>
        guarded(reply, () => {
          const c = studio.resolveChannel(channelId);
          const v = studio.pitch({ channelId: c.id, title, format: format ?? 'short', hook, pitch, authorId: agentId, ...(refIds?.length ? { refIds } : {}) });
          return reply(`Pitched ${describeVideo(v)}. ${c.autopilot ? 'This channel is on autopilot: approve_idea when you are sure.' : `It waits for ${names('user')}'s approval in the Content Studio.`}`);
        }),
    ),
    updateVideo(new Set(['idea', 'approved', 'review'])),
    playbook,
    tool('approve_idea', 'Approve an idea yourself. ONLY works on channels the user put on autopilot; everywhere else the user approves.', { id: z.string() }, ({ id }) =>
      guarded(reply, () => reply(`Approved ${describeVideo(studio.approveIdea(id, agentId))}. Reel starts on it.`)),
    ),
  ];
}

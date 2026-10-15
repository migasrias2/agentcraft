// AgentCraft wire protocol, version 1.
//
// This file is the single source of truth. docs/protocol.md is GENERATED from it
// (`npm run gen:protocol-doc`) and the Java mod mirrors it. Every message is one JSON object per
// WebSocket text frame:  { "v": 1, "type": "<type>", "id"?: "<client correlation id>", ...payload }
//
// Conventions
//  - timestamps (`ts`, `createdAt`, `updated`, ...) are integer epoch milliseconds
//  - colors are "#RRGGBB"
//  - unknown fields must be ignored by receivers (forward compatibility); zod strips them here
//  - optional fields are omitted (never null)
import { z } from 'zod';

export const PROTOCOL_VERSION = 1 as const;

// ---------------------------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------------------------

export const AgentState = z
  .enum(['idle', 'thinking', 'reading', 'editing', 'running', 'testing', 'waiting_user', 'blocked', 'done', 'error'])
  .describe('What the agent is doing right now; drives nameplate dot color, particles and animation.');
export type AgentState = z.infer<typeof AgentState>;

export const Station = z
  .enum(['desk', 'library', 'terminal', 'testbench', 'mergestation', 'meeting', 'lounge', 'user'])
  .describe('Where in the HQ the agent should walk to. `user` = next to the player / Decision Podium.');
export type Station = z.infer<typeof Station>;

export const AgentRole = z.enum(['lead', 'worker']);
export type AgentRole = z.infer<typeof AgentRole>;

export const TaskStatus = z
  .enum(['todo', 'doing', 'review', 'done', 'blocked', 'cancelled'])
  .describe('Task Wall column. `cancelled` tasks are kept for history but should not be shown on the wall.');
export type TaskStatus = z.infer<typeof TaskStatus>;

export const CiStatus = z.enum(['unknown', 'running', 'pass', 'fail']);
export type CiStatus = z.infer<typeof CiStatus>;

export const LogKind = z.enum(['text', 'tool', 'result', 'error', 'diff']);
export type LogKind = z.infer<typeof LogKind>;

export const DecisionKind = z.enum(['question', 'permission', 'merge']);
export type DecisionKind = z.infer<typeof DecisionKind>;

export const DecisionStatus = z
  .enum(['open', 'answered', 'cancelled'])
  .describe('`cancelled` = became moot (agent stopped, task cancelled); render like answered.');
export type DecisionStatus = z.infer<typeof DecisionStatus>;

export const GoalStatus = z.enum(['planning', 'active', 'done', 'failed', 'cancelled']);
export type GoalStatus = z.infer<typeof GoalStatus>;

export const FeedKind = z.enum(['goal', 'plan', 'task', 'message', 'decision', 'merge', 'ci', 'memory', 'system', 'error', 'user']);
export type FeedKind = z.infer<typeof FeedKind>;

export const NotifyLevel = z.enum(['info', 'warn', 'need_user']);
export type NotifyLevel = z.infer<typeof NotifyLevel>;

export const WorktreeStatus = z.enum(['active', 'merged', 'abandoned']);
export type WorktreeStatus = z.infer<typeof WorktreeStatus>;

export const BackendName = z.enum(['sim', 'claude', 'codex']);
export type BackendName = z.infer<typeof BackendName>;

export const AuthStatus = z
  .enum(['ok', 'failed', 'unknown', 'checking'])
  .describe('`failed` must be shown loudly (in-world banner): the claude backend cannot run.');
export type AuthStatus = z.infer<typeof AuthStatus>;

const Id = z.string().min(1);
const Ts = z.number().int().nonnegative().describe('epoch milliseconds');
const HexColor = z.string().regex(/^#[0-9A-Fa-f]{6}$/).describe('"#RRGGBB"');

// ---------------------------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------------------------

export const Agent = z.object({
  id: Id.describe('stable lowercase id, e.g. "kit"'),
  name: z.string().describe('display name, e.g. "Kit"'),
  role: AgentRole,
  title: z.string().optional().describe('short descriptive role from cast.json, e.g. "Backend tinkerer"'),
  color: HexColor.describe('agent color (scarf/badge/nameplate)'),
  accent: HexColor.optional(),
  skin: z.string().describe('skin id -> assets/agentcraft/textures/entity/agent/<skin>.png'),
  state: AgentState,
  activity: z.string().describe('one short line for the nameplate, e.g. "editing src/cli.ts" (<= 48 chars)'),
  station: Station,
  taskId: Id.optional(),
  repoId: Id.optional(),
  worktree: Id.optional().describe('id of the worktree the agent is working in (see Repo.worktrees)'),
  paused: z.boolean(),
  active: z.boolean().describe('false = off shift (not on the current team, or stopped by the user); render idle in the lounge'),
  engine: z.enum(['claude', 'codex']).optional().describe('which engine runs this agent (absent: the sim)'),
  model: z.string().optional().describe('the model it runs, for display, e.g. "Opus 5.5" or "GPT-6 Astra" (the real model once a turn reported it)'),
  team: z.enum(['studio', 'social', 'content']).optional().describe('which room the agent belongs to: omitted/"studio" = the coding team; "social" = the Social Desk (Nova plans posts, Sable publishes them); "content" = the Content Room (Iris pitches, Reel produces, Vega analyses, Cleo uploads YouTube videos). Social/content agents never take coding tasks; seat them in their own room'),
});
export type Agent = z.infer<typeof Agent>;

export const LogEntry = z.object({
  ts: Ts,
  kind: LogKind,
  text: z.string().describe('may contain newlines; `diff` entries use unified +/- line prefixes'),
});
export type LogEntry = z.infer<typeof LogEntry>;

export const Task = z.object({
  id: Id.describe('e.g. "t3"'),
  title: z.string(),
  description: z.string().optional(),
  status: TaskStatus,
  assignee: Id.optional().describe('agent id'),
  deps: z.array(Id).describe('task ids that must be done before this one can start'),
  repoId: Id.optional(),
  goalId: Id.optional(),
  priority: z.number().int().describe('higher = sooner; default 0'),
  branch: z.string().optional().describe('git branch, e.g. "agentcraft/kit/t2-tag-parser"'),
  startBranch: z.string().optional().describe('the worker\'s branch starts from this branch instead of the base, e.g. a fetched pull request "agentcraft/pr-12"'),
  worktree: Id.optional(),
  ci: CiStatus,
  blockedReason: z.string().optional(),
  summary: z.string().optional().describe('worker/lead summary of the result'),
  createdBy: Id.describe('agent id or "user"'),
  createdAt: Ts,
  updatedAt: Ts,
});
export type Task = z.infer<typeof Task>;

export const DecisionAnswer = z.object({
  option: z.string().optional().describe('the chosen option label (one of Decision.options)'),
  text: z.string().optional().describe('free-text answer / feedback'),
  ts: Ts,
});
export type DecisionAnswer = z.infer<typeof DecisionAnswer>;

export const Decision = z.object({
  id: Id.describe('e.g. "d4"'),
  agentId: Id.describe('agent waiting on this decision'),
  kind: DecisionKind,
  question: z.string(),
  options: z.array(z.string()).describe('button labels; first is the default/recommended choice'),
  context: z
    .string()
    .optional()
    .describe('extra detail, multi-line plain text. permission: why it asks, the cwd, and a line `"Always allow for this agent" covers: ...` (the scope of that choice). merge: summary + diff stat; after a refused merge the decision is open again and this ends with `Merge refused: <reason>`'),
  status: DecisionStatus,
  answer: DecisionAnswer.optional(),
  taskId: Id.optional(),
  repoId: Id.optional().describe('merge decisions: repo to request the diff from'),
  worktree: Id.optional().describe('merge decisions: worktree to request the diff for'),
  tool: z.string().optional().describe('permission decisions: tool name, e.g. "Bash"'),
  createdAt: Ts,
});
export type Decision = z.infer<typeof Decision>;

export const Worktree = z.object({
  id: Id.describe('e.g. "kit-t2" — use as `worktree` in diff.request'),
  agentId: Id,
  taskId: Id.optional(),
  branch: z.string(),
  base: z.string().describe('branch it was created from / will merge into'),
  path: z.string(),
  status: WorktreeStatus,
  ahead: z.number().int().nonnegative().describe('commits on branch not on base'),
  files: z.number().int().nonnegative().describe('files changed vs base (incl. uncommitted)'),
  additions: z.number().int().nonnegative(),
  deletions: z.number().int().nonnegative(),
});
export type Worktree = z.infer<typeof Worktree>;

export const Repo = z.object({
  id: Id.describe('e.g. "demo-app"'),
  name: z.string(),
  path: z.string().describe('absolute path of the user checkout'),
  branch: z.string().describe('base branch agents branch from and merge into'),
  head: z.string().optional().describe('short sha of base branch'),
  dirty: z.boolean().describe('user checkout has uncommitted tracked changes (merges are refused while dirty)'),
  worktrees: z.array(Worktree),
  ci: CiStatus.describe('latest CI/test result across this repo'),
});
export type Repo = z.infer<typeof Repo>;

export const MemoryEntry = z.object({
  id: Id.describe('"shared/<slug>" or "<agentId>/<slug>"'),
  scope: z.string().describe('"shared" or an agent id'),
  title: z.string(),
  body: z.string().describe('markdown'),
  updated: Ts,
  author: Id.optional().describe('agent id or "user" that last wrote it'),
});
export type MemoryEntry = z.infer<typeof MemoryEntry>;

export const Goal = z.object({
  id: Id.describe('e.g. "g1"'),
  text: z.string(),
  progress: z.number().min(0).max(1),
  status: GoalStatus.describe('planning (lead is planning) -> active -> done (every non-cancelled task merged/done); cancelled: every task was cancelled or rejected (back to active if the lead adds a task); failed: planning failed'),
  repoId: Id.optional(),
  createdAt: Ts,
  updatedAt: Ts,
});
export type Goal = z.infer<typeof Goal>;

export const FeedItem = z.object({
  ts: Ts,
  kind: FeedKind,
  text: z.string(),
  agentId: Id.optional().describe('who it is about / from'),
  to: z.string().optional().describe('message recipient: agent id, "user" or "all"'),
});
export type FeedItem = z.infer<typeof FeedItem>;

export const ForemanStatus = z.object({
  version: z.string(),
  backend: BackendName,
  auth: AuthStatus,
  message: z.string().optional().describe('human-readable backend/auth status for the banner'),
  account: z.string().optional().describe('e.g. organization / plan when auth ok'),
  speed: z.number().optional().describe('sim: speed multiplier'),
  showcase: z.boolean().optional().describe('sim: holding a static showcase state (`--showcase` or `--showcase late`)'),
  costUsd: z.number().optional().describe('claude: estimated spend of this profile (sum over all sessions, survives restarts)'),
  userName: z.string().optional().describe('the person the team works for, as the agents address them (UI: "<name> answered")'),
  autoApprove: z.boolean().optional().describe('true: agents run tool calls without asking (denied ones such as git push stay denied); toggled with `settings.set`'),
});
export type ForemanStatus = z.infer<typeof ForemanStatus>;

export const AgentLogs = z.object({ agentId: Id, entries: z.array(LogEntry) });
export type AgentLogs = z.infer<typeof AgentLogs>;

export const AgentScreen = z.object({
  agentId: Id,
  mime: z.string().describe('image MIME type, e.g. image/png or image/jpeg'),
  data: z.string().describe('base64 image bytes (<= ~3 MB of base64)'),
  tool: z.string().describe('tool that produced it, e.g. mcp__claude-in-chrome__computer'),
  at: Ts.describe('epoch ms when the frame was captured'),
});
export type AgentScreen = z.infer<typeof AgentScreen>;

export const Media = z.object({
  id: Id,
  agentId: Id.describe('who handed it over'),
  kind: z.enum(['image', 'video']),
  name: z.string().describe('file name'),
  caption: z.string().optional(),
  mime: z.string().describe('MIME type of every frame: image/png or image/jpeg'),
  frames: z.array(z.string()).describe('base64 frames: one for an image; a video sampled to <= 120 JPEG frames, <= 640 px wide'),
  fps: z.number().optional().describe('video: playback rate of `frames` (loop them)'),
  durationMs: z.number().int().optional().describe('video: length of the original'),
  hasAudio: z.boolean().optional().describe('video: the original has sound (play it with `media.open`)'),
  at: Ts,
});
export type Media = z.infer<typeof Media>;

export const Doc = z.object({
  id: Id,
  agentId: Id.describe('who handed it over'),
  title: z.string().describe('first # heading, the title the agent gave, or the file name'),
  name: z.string().describe('file name'),
  path: z.string().describe('where the file is (often in the agent worktree)'),
  markdown: z.string().describe('the document (markdown or plain text, <= 200k chars)'),
  note: z.string().optional().describe('what the agent says when handing it over'),
  taskId: Id.optional(),
  read: z.boolean().describe('false until the user opens it (`doc.read`); the agent comes to the player while it has unread docs'),
  at: Ts,
});
export type Doc = z.infer<typeof Doc>;

export const SocialPlatform = z.enum(['x', 'linkedin']);
export type SocialPlatform = z.infer<typeof SocialPlatform>;

export const SocialPostStatus = z
  .enum(['draft', 'approved', 'posting', 'posted', 'failed', 'cancelled'])
  .describe('draft = proposed, waits for the user; approved = the user said yes, goes out at `scheduledAt`; posting = the publisher is on it right now; posted/failed/cancelled are final (a failed one can be approved again)');
export type SocialPostStatus = z.infer<typeof SocialPostStatus>;

export const SocialPost = z.object({
  id: Id.describe('e.g. "p3"'),
  platform: SocialPlatform,
  text: z.string().describe('the exact post text (x: <= 280 chars unless long posts were allowed; linkedin: <= 3000)'),
  scheduledAt: Ts.optional().describe('when it goes out (only once approved); a draft may have none yet'),
  status: SocialPostStatus,
  authorId: Id.describe('agent that drafted it ("nova") or "user"'),
  note: z.string().optional().describe('the planner\'s one-line reasoning'),
  mediaPaths: z.array(z.string()).optional().describe('local image/video files to attach'),
  url: z.string().optional().describe('link to the live post, once posted'),
  error: z.string().optional().describe('why it failed'),
  createdAt: Ts,
  updatedAt: Ts,
  postedAt: Ts.optional(),
});
export type SocialPost = z.infer<typeof SocialPost>;

export const SOCIAL_POST_ACTIONS = ['approve', 'unapprove', 'cancel', 'post_now', 'schedule'] as const;

// ---- Content Room (YouTube channels run by agents) ----
export const ContentChannel = z.object({
  id: Id.describe('e.g. "ch1"'),
  name: z.string(),
  url: z.string().optional().describe('e.g. https://www.youtube.com/@handle'),
  youtubeId: z.string().optional().describe('the YouTube channel id (UC...), once resolved'),
  niche: z.string().optional(),
  brief: z.string().optional().describe('voice, audience, rules (<= 2000 chars)'),
  autopilot: z.boolean().describe('true: the agents approve ideas and cuts themselves and keep the queue full'),
  postsPerWeek: z.number().int().min(1).max(70).describe('cadence: publishing slots per week, spread over the day between 09:00 and 22:00 in the calendar time zone (1 a day = 18:00; 5 a day = 09, 12, 15, 18, 21)'),
  statsSource: z.enum(['studio', 'api', 'public']).optional().describe('where the numbers come from: api = OAuth analytics, studio = read from YouTube Studio in Chrome within the last 24 h, public = yt-dlp public numbers only; absent = no numbers yet'),
  connected: z.boolean().describe('full analytics available: OAuth tokens exist (statsSource api) or Vega read YouTube Studio in Chrome within the last 24 h (statsSource studio); false = public stats only'),
  playbook: z.string().optional().describe('the channel playbook (markdown, <= 20000 chars): what works / what does not, with numbers. Written by Iris and Vega'),
  createdAt: Ts,
  updatedAt: Ts,
});
export type ContentChannel = z.infer<typeof ContentChannel>;

export const ContentVideoStatus = z
  .enum(['idea', 'approved', 'producing', 'review', 'scheduled', 'publishing', 'published', 'rejected', 'failed'])
  .describe('idea = pitched, waits for the user; approved = go (Reel picks it up); producing = Reel is making it; review = the cut waits for the user; scheduled = approved, goes out at `scheduledAt`; publishing = Cleo is uploading it right now; published/rejected are final; failed = the upload failed (retry re-schedules it)');
export type ContentVideoStatus = z.infer<typeof ContentVideoStatus>;

export const ContentComment = z.object({
  by: Id.describe('agent id or "user"'),
  text: z.string(),
  at: Ts,
});
export type ContentComment = z.infer<typeof ContentComment>;

export const ContentPostMortem = z.object({ at: Ts, horizon: z.enum(['48h', '7d']), text: z.string() });
export type ContentPostMortem = z.infer<typeof ContentPostMortem>;

export const ContentVideo = z.object({
  id: Id.describe('e.g. "v3"'),
  channelId: Id,
  status: ContentVideoStatus,
  format: z.enum(['short', 'long']),
  title: z.string().describe('<= 100 chars (YouTube limit)'),
  hook: z.string().optional().describe('the first 3 seconds'),
  pitch: z.string().optional().describe('why this video, what it copies, the expected result (<= 1500)'),
  script: z.string().optional().describe('<= 20000'),
  description: z.string().optional().describe('<= 5000'),
  tags: z.array(z.string()).optional().describe('total <= 500 chars'),
  refIds: z.array(Id).optional().describe('studied reference videos (ContentRef.id)'),
  filePath: z.string().optional().describe('the cut (absolute path)'),
  thumbnailPath: z.string().optional(),
  scheduledAt: Ts.optional(),
  autoScheduled: z.boolean().optional().describe('true: scheduled by the channel\'s autopilot, not approved by the user; turning autopilot off sends it back to review'),
  url: z.string().optional().describe('link to the live video, once published'),
  youtubeVideoId: z.string().optional(),
  error: z.string().optional(),
  authorId: Id.describe('agent that pitched it ("iris") or "user"'),
  comments: z.array(ContentComment).describe('the thread between the user and the agents (last 50)'),
  createdAt: Ts,
  updatedAt: Ts,
  publishedAt: Ts.optional(),
  postMortems: z.array(ContentPostMortem).optional(),
});
export type ContentVideo = z.infer<typeof ContentVideo>;

export const ContentStat = z.object({
  videoId: z.string().describe('YouTube video id (our videos AND references)'),
  at: Ts,
  views: z.number(),
  likes: z.number().optional(),
  comments: z.number().optional(),
  avgViewDurationSec: z.number().optional(),
  avgViewPct: z.number().optional().describe('percent 0..100'),
  watchMinutes: z.number().optional(),
  subsGained: z.number().optional(),
  impressions: z.number().optional(),
  ctr: z.number().optional().describe('0..1'),
  retention: z.array(z.number()).optional().describe('audience watch ratio at 0,5,10..100% of the video (21 points); only with OAuth analytics or a YouTube Studio read'),
  stayedToWatchPct: z.number().optional().describe('Shorts: percent 0..100 of viewers who stayed to watch (Studio "viewed vs swiped away")'),
  swipeAwayPct: z.number().optional().describe('Shorts: percent 0..100 of viewers who swiped away'),
});
export type ContentStat = z.infer<typeof ContentStat>;

export const ContentRef = z.object({
  id: Id.describe('e.g. "r2"'),
  source: z.string().describe('URL or path that was studied'),
  title: z.string().optional(),
  channel: z.string().optional(),
  durationSec: z.number().optional(),
  views: z.number().optional(),
  likes: z.number().optional(),
  uploadDate: z.string().optional(),
  dir: z.string().describe('folder with frames/, sheets/, transcript.txt, meta.json, breakdown.md'),
  frameCount: z.number().int(),
  sheetCount: z.number().int(),
  hasTranscript: z.boolean(),
  studiedBy: Id,
  at: Ts,
});
export type ContentRef = z.infer<typeof ContentRef>;

export const ContentDaily = z.object({
  date: z.string().describe('YYYY-MM-DD'),
  views: z.number(),
  subsGained: z.number().optional(),
  watchMinutes: z.number().optional(),
});
export type ContentDaily = z.infer<typeof ContentDaily>;

export const ContentChannelStats = z.object({
  channelId: Id,
  at: Ts,
  subscribers: z.number().optional(),
  totalViews: z.number().optional(),
  videoCount: z.number().optional(),
  views28d: z.number().optional(),
  watchMinutes28d: z.number().optional(),
  subsGained28d: z.number().optional(),
  daily: z.array(ContentDaily).optional().describe('last 28 days, oldest first'),
});
export type ContentChannelStats = z.infer<typeof ContentChannelStats>;

export const CONTENT_CHANNEL_ACTIONS = ['add', 'update', 'remove', 'autopilot_on', 'autopilot_off', 'sync'] as const;
export const CONTENT_VIDEO_ACTIONS = ['approve', 'reject', 'comment', 'approve_cut', 'schedule', 'publish_now', 'unschedule', 'retry'] as const;

export const DiffLine = z.object({
  kind: z.enum(['add', 'del', 'ctx']),
  text: z.string().describe('line content without the +/-/space prefix'),
  oldNo: z.number().int().optional().describe('line number in base (del, ctx)'),
  newNo: z.number().int().optional().describe('line number in branch (add, ctx)'),
});
export type DiffLine = z.infer<typeof DiffLine>;

export const DiffHunk = z.object({
  header: z.string().describe('e.g. "@@ -12,6 +12,9 @@ export function run"'),
  oldStart: z.number().int(),
  oldLines: z.number().int(),
  newStart: z.number().int(),
  newLines: z.number().int(),
  lines: z.array(DiffLine),
});
export type DiffHunk = z.infer<typeof DiffHunk>;

export const DiffFile = z.object({
  path: z.string(),
  oldPath: z.string().optional().describe('renames only'),
  status: z.enum(['added', 'modified', 'deleted', 'renamed']),
  binary: z.boolean(),
  additions: z.number().int().nonnegative(),
  deletions: z.number().int().nonnegative(),
  hunks: z.array(DiffHunk),
});
export type DiffFile = z.infer<typeof DiffFile>;

// ---------------------------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------------------------

const envelope = <T extends string>(type: T) => ({
  v: z.literal(PROTOCOL_VERSION),
  type: z.literal(type),
  id: z.string().optional().describe('client correlation id; the Foreman answers with `ack` {re: id}'),
});

// Foreman -> Mod ------------------------------------------------------------------------------

export const SnapshotMsg = z.object({
  ...envelope('snapshot'),
  foreman: ForemanStatus,
  agents: z.array(Agent),
  tasks: z.array(Task),
  decisions: z.array(Decision).describe('open decisions plus the most recent answered ones'),
  repos: z.array(Repo),
  memory: z.array(MemoryEntry),
  goal: Goal.optional().describe('current (latest) goal'),
  goals: z.array(Goal).describe('all goals, oldest first'),
  feed: z.array(FeedItem).describe('most recent feed items, oldest first (<= 200)'),
  logs: z.array(AgentLogs).describe('recent log tail per agent (<= 60 entries each)'),
  screens: z.array(AgentScreen).optional().describe('latest fresh (< 90 s) screen frame per agent; omitted when none'),
  media: z.array(Media).optional().describe('newest media agents handed over, oldest first (<= 4); omitted when none'),
  docs: z.array(Doc).optional().describe('documents agents handed over, oldest first (<= 30); omitted when none'),
  socialPosts: z.array(SocialPost).optional().describe('the Social Desk calendar: every draft/approved/posting/failed post plus the last 100 finished ones, oldest first; omitted when none'),
  contentChannels: z.array(ContentChannel).optional().describe('the Content Room: YouTube channels; omitted when none'),
  contentVideos: z.array(ContentVideo).optional().describe('the Content Room pipeline: every open video plus the last 200 finished ones, oldest first; omitted when none'),
  contentRefs: z.array(ContentRef).optional().describe('studied reference videos, the last 50, oldest first; omitted when none'),
  contentStats: z.array(ContentStat).optional().describe('the latest stat snapshot per YouTube video id, plus up to 30 history points per own published video (oldest first); omitted when none'),
  contentChannelStats: z.array(ContentChannelStats).optional().describe('the latest channel-level stats per channel; omitted when none'),
});
export const AgentUpsertMsg = z.object({ ...envelope('agent.upsert'), agent: Agent });
export const AgentLogMsg = z.object({ ...envelope('agent.log'), agentId: Id, entries: z.array(LogEntry) });
export const AgentScreenMsg = z.object({ ...envelope('agent.screen'), ...AgentScreen.shape });
export const MediaAddMsg = z.object({ ...envelope('media.add'), item: Media });
export const DocUpsertMsg = z.object({ ...envelope('doc.upsert'), doc: Doc });
export const SocialPostUpsertMsg = z.object({ ...envelope('social.post.upsert'), post: SocialPost });
export const ContentChannelUpsertMsg = z.object({ ...envelope('content.channel.upsert'), channel: ContentChannel });
export const ContentVideoUpsertMsg = z.object({ ...envelope('content.video.upsert'), video: ContentVideo });
export const ContentRefUpsertMsg = z.object({ ...envelope('content.ref.upsert'), ref: ContentRef });
export const ContentStatsMsg = z.object({ ...envelope('content.stats'), stats: z.array(ContentStat), channelStats: z.array(ContentChannelStats) });
export const AgentSayMsg = z.object({
  ...envelope('agent.say'),
  agentId: Id,
  text: z.string(),
  to: z.string().optional().describe('agent id, "user" or "all"; omitted = said to the room'),
  ts: Ts,
});
export const TaskUpsertMsg = z.object({ ...envelope('task.upsert'), task: Task });
export const DecisionUpsertMsg = z.object({ ...envelope('decision.upsert'), decision: Decision });
export const RepoUpsertMsg = z.object({ ...envelope('repo.upsert'), repo: Repo });
export const MemoryUpsertMsg = z.object({ ...envelope('memory.upsert'), entry: MemoryEntry });
export const GoalUpsertMsg = z.object({ ...envelope('goal.upsert'), goal: Goal });
export const FeedAddMsg = z.object({ ...envelope('feed.add'), item: FeedItem });
export const DiffMsg = z.object({
  ...envelope('diff'),
  requestId: z.string(),
  repoId: Id,
  worktree: Id,
  base: z.string().optional(),
  branch: z.string().optional(),
  files: z.array(DiffFile),
  stats: z.object({ files: z.number().int(), additions: z.number().int(), deletions: z.number().int() }),
  truncated: z.boolean().describe('true if very large files/hunks were cut'),
  error: z.string().optional(),
});
export const NotifyMsg = z.object({
  ...envelope('notify'),
  level: NotifyLevel,
  text: z.string(),
  decisionId: Id.optional(),
  ts: Ts,
});
export const ForemanStatusMsg = z.object({ ...envelope('foreman.status'), status: ForemanStatus });
export const AckMsg = z.object({
  ...envelope('ack'),
  re: z.string().describe('the `id` of the client message being acknowledged'),
  ok: z.boolean(),
  error: z.string().optional(),
  result: z.record(z.string(), z.unknown()).optional().describe('e.g. {goalId} for goal.submit, {repoId} for repo.add'),
});
export const ErrorMsg = z.object({
  ...envelope('error'),
  message: z.string(),
  re: z.string().optional(),
});

export const ServerMessage = z.discriminatedUnion('type', [
  SnapshotMsg,
  AgentUpsertMsg,
  AgentLogMsg,
  AgentScreenMsg,
  MediaAddMsg,
  DocUpsertMsg,
  SocialPostUpsertMsg,
  ContentChannelUpsertMsg,
  ContentVideoUpsertMsg,
  ContentRefUpsertMsg,
  ContentStatsMsg,
  AgentSayMsg,
  TaskUpsertMsg,
  DecisionUpsertMsg,
  RepoUpsertMsg,
  MemoryUpsertMsg,
  GoalUpsertMsg,
  FeedAddMsg,
  DiffMsg,
  NotifyMsg,
  ForemanStatusMsg,
  AckMsg,
  ErrorMsg,
]);
export type ServerMessage = z.infer<typeof ServerMessage>;

// Mod -> Foreman ------------------------------------------------------------------------------

export const HelloMsg = z.object({
  ...envelope('hello'),
  modVersion: z.string(),
  protocol: z.literal(PROTOCOL_VERSION),
  client: z.string().optional().describe('"mod" | "cli" | ... (informational)'),
});
export const GoalSubmitMsg = z.object({
  ...envelope('goal.submit'),
  text: z.string().min(1),
  repoId: Id.optional().describe('defaults to the only/most recently added repo'),
});
export const UserMessageMsg = z.object({
  ...envelope('user.message'),
  to: z.string().min(1).describe('agent id or "all". With "all", a leading "@name" in text routes to that agent.'),
  text: z.string().min(1),
});
export const DecisionAnswerMsg = z.object({
  ...envelope('decision.answer'),
  decisionId: Id,
  option: z
    .union([z.string(), z.number().int().nonnegative()])
    .optional()
    .describe('option label (preferred) or 0-based index into Decision.options'),
  text: z.string().optional().describe('free text (questions) or feedback (merge "Request changes")'),
});
export const TaskActionMsg = z.object({
  ...envelope('task.action'),
  taskId: Id,
  action: z.enum(['reassign', 'cancel', 'retry', 'prioritize']),
  arg: z.string().optional().describe('reassign: agent id; prioritize: integer priority (default: bump to top)'),
});
export const AgentActionMsg = z.object({
  ...envelope('agent.action'),
  agentId: Id,
  action: z
    .enum(['pause', 'resume', 'stop', 'spawn'])
    .describe(
      'pause: abort the current turn, keep the task (open questions are withdrawn); resume continues it. stop: off shift (active=false) until resume/spawn: turn aborted, open questions/permission prompts withdrawn, its doing tasks go back to the board and the next worker continues from the same branch. spawn: bring an off-shift agent onto the team.',
    ),
  arg: z.string().optional().describe('spawn: optional task id to assign to the agent'),
});
export const DiffRequestMsg = z.object({
  ...envelope('diff.request'),
  requestId: z.string().min(1),
  repoId: Id,
  worktree: Id.describe('worktree id (e.g. "kit-t2"); an agent id resolves to that agent\'s current worktree'),
});
export const RepoAddMsg = z.object({ ...envelope('repo.add'), path: z.string().min(1) });
export const RepoCreateMsg = z.object({
  ...envelope('repo.create'),
  name: z.string().min(1).describe('folder name, created under the new-repo dir (default: your home); may be home-relative like "Desktop/my-game"'),
  goal: z.string().optional().describe('a goal to start in the new repo right away'),
  autoApprove: z.boolean().optional().describe('also turn auto-approve on (same as `settings.set`), so the goal runs without asking'),
});
export const SettingsSetMsg = z.object({
  ...envelope('settings.set'),
  autoApprove: z.boolean().optional().describe('true: agents stop asking permission (open permission prompts are allowed once); false: they ask again'),
});
export const MediaOpenMsg = z.object({ ...envelope('media.open'), mediaId: Id });
export const DocReadMsg = z.object({ ...envelope('doc.read'), docId: Id });
export const SocialPostActionMsg = z.object({
  ...envelope('social.post.action'),
  postId: Id,
  action: z.enum(SOCIAL_POST_ACTIONS).describe('approve (needs a future scheduledAt; the ONLY approval: it goes out at that time without asking again), unapprove (back to draft), cancel, post_now (approve and publish right away), schedule (set `at`; an approved post goes back to draft)'),
  at: Ts.optional().describe('schedule: the new time (must be in the future)'),
  text: z.string().optional().describe('an edited text, applied before the action (an approved post goes back to draft unless the action is approve)'),
});
export const ContentChannelActionMsg = z.object({
  ...envelope('content.channel.action'),
  channelId: Id.optional().describe('required for everything but add'),
  action: z.enum(CONTENT_CHANNEL_ACTIONS).describe('add (needs name), update (name/url/niche/brief/postsPerWeek), remove, autopilot_on, autopilot_off, sync (refresh the analytics now)'),
  name: z.string().optional(),
  url: z.string().optional(),
  niche: z.string().optional(),
  brief: z.string().optional(),
  postsPerWeek: z.number().int().optional().describe('1..70'),
});
export const ContentVideoActionMsg = z.object({
  ...envelope('content.video.action'),
  videoId: Id,
  action: z.enum(CONTENT_VIDEO_ACTIONS).describe('approve (idea -> approved, wakes Reel), reject (idea|approved|review -> rejected; `text` = reason), comment (any status; wakes the owner), approve_cut (review -> scheduled at `at`, default the next free slot), schedule (new time for a scheduled video), publish_now, unschedule (scheduled -> review), retry (failed -> scheduled now)'),
  text: z.string().optional().describe('comment text / reject reason'),
  at: Ts.optional().describe('approve_cut / schedule: the publish time'),
});
export const ContentRefOpenMsg = z.object({
  ...envelope('content.ref.open'),
  refId: Id,
  frame: z.number().int().nonnegative().optional().describe('1-based frame number to show; omitted = the contact sheets'),
});

export const ContentVideoPreviewMsg = z.object({
  ...envelope('content.video.preview'),
  videoId: Id,
});

export const ClientMessage = z.discriminatedUnion('type', [
  HelloMsg,
  GoalSubmitMsg,
  UserMessageMsg,
  DecisionAnswerMsg,
  TaskActionMsg,
  AgentActionMsg,
  DiffRequestMsg,
  RepoAddMsg,
  RepoCreateMsg,
  SettingsSetMsg,
  MediaOpenMsg,
  DocReadMsg,
  SocialPostActionMsg,
  ContentChannelActionMsg,
  ContentVideoActionMsg,
  ContentRefOpenMsg,
  ContentVideoPreviewMsg,
]);
export type ClientMessage = z.infer<typeof ClientMessage>;

// Helper types ----------------------------------------------------------------------------------

/** A server message without the envelope's `v` (added by the sender). */
export type Outbound = ServerMessage extends infer M ? (M extends { v: 1 } ? Omit<M, 'v'> : never) : never;
export type ServerMessageOf<T extends ServerMessage['type']> = Extract<ServerMessage, { type: T }>;
export type ClientMessageOf<T extends ClientMessage['type']> = Extract<ClientMessage, { type: T }>;

export function parseClientMessage(raw: unknown): { ok: true; msg: ClientMessage } | { ok: false; error: string } {
  let data = raw;
  if (typeof raw === 'string') {
    try {
      data = JSON.parse(raw);
    } catch {
      return { ok: false, error: 'invalid JSON' };
    }
  }
  const r = ClientMessage.safeParse(data);
  if (r.success) return { ok: true, msg: r.data };
  return { ok: false, error: formatZodError(r.error) };
}

export function parseServerMessage(raw: unknown): { ok: true; msg: ServerMessage } | { ok: false; error: string } {
  let data = raw;
  if (typeof raw === 'string') {
    try {
      data = JSON.parse(raw);
    } catch {
      return { ok: false, error: 'invalid JSON' };
    }
  }
  const r = ServerMessage.safeParse(data);
  if (r.success) return { ok: true, msg: r.data };
  return { ok: false, error: formatZodError(r.error) };
}

export function formatZodError(err: z.ZodError): string {
  return err.issues
    .slice(0, 5)
    .map((i) => `${i.path.join('.') || '(root)'}: ${i.message}`)
    .join('; ');
}

/** Registry used by the doc generator and tests. Order = documentation order. */
export const SERVER_MESSAGES = {
  snapshot: { schema: SnapshotMsg, doc: 'Full state. Sent in reply to every `hello`; the mod rebuilds its view from it.' },
  'agent.upsert': { schema: AgentUpsertMsg, doc: 'An agent was created or changed (state, station, activity, task...). Replace by `agent.id`.' },
  'agent.log': { schema: AgentLogMsg, doc: 'New log lines for an agent monitor (append; keep a bounded tail).' },
  'agent.screen': { schema: AgentScreenMsg, doc: 'Live screenshot an agent just got from a tool (Claude in Chrome, Roblox Studio, Blender...). Throttled to ~1 frame / 1.5 s per agent; replace the agent monitor image. The latest fresh frame per agent is also in `snapshot.screens`.' },
  'media.add': { schema: MediaAddMsg, doc: 'An agent handed the user an image or a video (`show_media`). Append to the media gallery (Screening Room). The newest few are also in `snapshot.media`.' },
  'doc.upsert': { schema: DocUpsertMsg, doc: 'An agent handed the user a document (`hand_over`), or it was read. Replace by `doc.id`. While a doc is unread its agent walks to the player and holds it out; right-click the agent (or `/docs`) to read it.' },
  'social.post.upsert': { schema: SocialPostUpsertMsg, doc: 'A Social Desk post was created or changed (calendar). Replace by `post.id`. The whole calendar is in `snapshot.socialPosts`.' },
  'content.channel.upsert': { schema: ContentChannelUpsertMsg, doc: 'A Content Room channel was added or changed (autopilot, cadence, connected, playbook). Replace by `channel.id`.' },
  'content.video.upsert': { schema: ContentVideoUpsertMsg, doc: 'A Content Room video was created or changed (idea, comment, status). Replace by `video.id`. The pipeline is in `snapshot.contentVideos`.' },
  'content.ref.upsert': { schema: ContentRefUpsertMsg, doc: 'A reference video was studied frame by frame (or re-studied). Replace by `ref.id`.' },
  'content.stats': { schema: ContentStatsMsg, doc: 'An analytics sync finished: the full latest set. Replace your stats by `videoId` (history: several points per own video, oldest first) and `channelStats` by `channelId`.' },
  'agent.say': { schema: AgentSayMsg, doc: 'Speech bubble above the agent; also mirrored to the feed.' },
  'task.upsert': { schema: TaskUpsertMsg, doc: 'Task created or changed. Replace by `task.id`.' },
  'decision.upsert': { schema: DecisionUpsertMsg, doc: 'Decision opened, answered or cancelled. Replace by `decision.id`.' },
  'repo.upsert': { schema: RepoUpsertMsg, doc: 'Repo added or changed (worktrees, CI, head, dirty). Replace by `repo.id`.' },
  'memory.upsert': { schema: MemoryUpsertMsg, doc: 'Memory entry written. Replace by `entry.id`.' },
  'goal.upsert': { schema: GoalUpsertMsg, doc: 'Goal created or progress/status changed. Replace by `goal.id`; latest goal is current.' },
  'feed.add': { schema: FeedAddMsg, doc: 'Append to the activity feed.' },
  diff: { schema: DiffMsg, doc: 'Reply to `diff.request` (sent only to the requesting client). Structured unified diff of worktree vs base, including uncommitted changes.' },
  notify: { schema: NotifyMsg, doc: 'Toast/banner for the player. `need_user` = a decision is waiting (play a bell).' },
  'foreman.status': { schema: ForemanStatusMsg, doc: 'Backend/auth status changed (banner).' },
  ack: { schema: AckMsg, doc: 'Reply to any client message that carried an `id`.' },
  error: { schema: ErrorMsg, doc: 'A client message was invalid or failed (also sent as ack.ok=false when it had an id).' },
} as const;

export const CLIENT_MESSAGES = {
  hello: { schema: HelloMsg, doc: 'First message after connecting. The Foreman replies with `snapshot`, then streams upserts.' },
  'goal.submit': { schema: GoalSubmitMsg, doc: 'New goal for the lead (console: plain text).' },
  'user.message': { schema: UserMessageMsg, doc: 'Message an agent (console: `@name text`) or everyone.' },
  'decision.answer': { schema: DecisionAnswerMsg, doc: 'Answer an open decision. Merge decisions: option "Merge" merges, "Request changes" sends `text` back to the worker, "Reject" abandons the branch.' },
  'task.action': { schema: TaskActionMsg, doc: 'Steer a task from the Task Wall.' },
  'agent.action': { schema: AgentActionMsg, doc: 'Pause/resume/stop an agent, or spawn (activate) an off-shift worker.' },
  'diff.request': { schema: DiffRequestMsg, doc: 'Ask for the structured diff of a worktree. Answered with `diff` (same requestId).' },
  'repo.add': { schema: RepoAddMsg, doc: 'Register a local git repo (console: `/repo add <path>`).' },
  'repo.create': { schema: RepoCreateMsg, doc: 'Create a new empty git repo (README + first commit on main), register it, and optionally start a goal in it (console: `/repo new <name>`, or "+ New repo" in the goal picker). Acks with {repoId, path, goalId?}.' },
  'settings.set': { schema: SettingsSetMsg, doc: 'Change a Foreman setting (console: `/auto on|off`). The new value comes back in `foreman.status`.' },
  'doc.read': { schema: DocReadMsg, doc: 'The user opened a handed-over document: marks it read (the agent goes back to work).' },
  'social.post.action': { schema: SocialPostActionMsg, doc: 'Act on a Social Desk post from the calendar: approve / unapprove / cancel / post_now / schedule (+ optional edited `text`). Acks with {postId, status}; the change comes back as `social.post.upsert`. Only drafts and failed posts can be approved; posted posts never change.' },
  'content.channel.action': { schema: ContentChannelActionMsg, doc: 'Manage a Content Room channel: add / update / remove / autopilot_on / autopilot_off / sync. Acks with {channelId}; the change comes back as `content.channel.upsert`.' },
  'content.video.action': { schema: ContentVideoActionMsg, doc: 'Act on a Content Room video: approve / reject / comment / approve_cut / schedule / publish_now / unschedule / retry. Acks with {videoId, status}; the change comes back as `content.video.upsert`. Comments wake the agent that owns the video.' },
  'content.video.preview': { schema: ContentVideoPreviewMsg, doc: 'Watch a video\'s finished cut in game: the Foreman turns the whole cut (up to 3 min) into a flipbook (`media.add`) and acks with {videoId, mediaId}; the mod opens its media viewer on that id ("Open in player" plays it with sound). Errors when there is no cut yet.' },
  'content.ref.open': { schema: ContentRefOpenMsg, doc: 'Show a studied reference on the Screening Room screen: its contact sheets, or one frame (1-based). Acks with {refId}.' },
  'media.open': { schema: MediaOpenMsg, doc: 'Open the original file of a media item in the OS default app (videos play with sound).' },
} as const;

export const ENTITY_SCHEMAS = {
  Agent,
  LogEntry,
  Task,
  Decision,
  DecisionAnswer,
  Repo,
  Worktree,
  MemoryEntry,
  Goal,
  FeedItem,
  ForemanStatus,
  AgentLogs,
  AgentScreen,
  Media,
  Doc,
  SocialPost,
  ContentChannel,
  ContentVideo,
  ContentStat,
  ContentRef,
  ContentChannelStats,
  DiffFile,
  DiffHunk,
  DiffLine,
} as const;

/** Merge decision option labels (exact strings). */
export const MERGE_OPTIONS = ['Merge', 'Request changes', 'Reject'] as const;
/** Permission decision option labels (exact strings). */
export const PERMISSION_OPTIONS = ['Allow once', 'Always allow for this agent', 'Deny'] as const;

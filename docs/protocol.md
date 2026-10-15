# AgentCraft protocol v1

> GENERATED from `foreman/src/protocol.ts` and `foreman/src/protocol-examples.ts` by `npm run gen:protocol-doc` (in `foreman/`). Do not edit by hand. The Java mod mirrors these shapes.

## Transport

- The Foreman listens on `ws://127.0.0.1:${AGENTCRAFT_PORT:-7878}`. Clients (the mod, CLI tools) connect, send `hello`, and receive a full `snapshot` followed by incremental messages. Multiple clients may be connected; every client receives every broadcast.
- One JSON object per WebSocket **text** frame. Envelope: `{ "v": 1, "type": "<type>", "id"?: "<correlation id>", ...payload }`.
- Client messages that carry an `id` are answered with `ack { re: id, ok, error?, result? }`. Invalid messages get `error` (and a failed `ack` if they had an id).
- Timestamps are integer epoch milliseconds. Colors are `"#RRGGBB"`. Optional fields are omitted, never `null`. Receivers must ignore unknown fields.
- Upserts replace the whole entity by id. `agent.log` and `feed.add` append; `agent.screen` replaces the current screen frame of that agent.
- Connections that carry any `Origin` header (browsers; also `Origin: null` from sandboxed iframes, `data:` and `file:` pages) or a Host header other than `127.0.0.1` / `localhost` / `[::1]` are rejected with HTTP 401, so a web page cannot drive your agents. Clients (the mod, CLI tools) must not send an Origin header. Heartbeat: the Foreman pings every 15 s.
- The mod should reconnect with backoff and re-send `hello`; the snapshot rebuilds the whole view.

## Enums

- <a id="agentstate"></a>**AgentState**: `idle`, `thinking`, `reading`, `editing`, `running`, `testing`, `waiting_user`, `blocked`, `done`, `error` - What the agent is doing right now; drives nameplate dot color, particles and animation.
- <a id="station"></a>**Station**: `desk`, `library`, `terminal`, `testbench`, `mergestation`, `meeting`, `lounge`, `user` - Where in the HQ the agent should walk to. `user` = next to the player / Decision Podium.
- <a id="agentrole"></a>**AgentRole**: `lead`, `worker`
- <a id="taskstatus"></a>**TaskStatus**: `todo`, `doing`, `review`, `done`, `blocked`, `cancelled` - Task Wall column. `cancelled` tasks are kept for history but should not be shown on the wall.
- <a id="cistatus"></a>**CiStatus**: `unknown`, `running`, `pass`, `fail`
- <a id="logkind"></a>**LogKind**: `text`, `tool`, `result`, `error`, `diff`
- <a id="decisionkind"></a>**DecisionKind**: `question`, `permission`, `merge`
- <a id="decisionstatus"></a>**DecisionStatus**: `open`, `answered`, `cancelled` - `cancelled` = became moot (agent stopped, task cancelled); render like answered.
- <a id="goalstatus"></a>**GoalStatus**: `planning`, `active`, `done`, `failed`, `cancelled`
- <a id="feedkind"></a>**FeedKind**: `goal`, `plan`, `task`, `message`, `decision`, `merge`, `ci`, `memory`, `system`, `error`, `user`
- <a id="notifylevel"></a>**NotifyLevel**: `info`, `warn`, `need_user`
- <a id="worktreestatus"></a>**WorktreeStatus**: `active`, `merged`, `abandoned`
- <a id="backendname"></a>**BackendName**: `sim`, `claude`, `codex`
- <a id="authstatus"></a>**AuthStatus**: `ok`, `failed`, `unknown`, `checking` - `failed` must be shown loudly (in-world banner): the claude backend cannot run.

Exact option labels: merge decisions use `Merge`, `Request changes`, `Reject`; permission decisions use `Allow once`, `Always allow for this agent`, `Deny`. Question decisions use agent-supplied options (may be empty: free text).

## Entities

### <a id="agent"></a>Agent

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | stable lowercase id, e.g. "kit" |
| `name` | string | yes | display name, e.g. "Kit" |
| `role` | [AgentRole](#agentrole) | yes |  |
| `title` | string | no | short descriptive role from cast.json, e.g. "Backend tinkerer" |
| `color` | string (#RRGGBB) | yes | agent color (scarf/badge/nameplate) |
| `accent` | string (#RRGGBB) | no | "#RRGGBB" |
| `skin` | string | yes | skin id -> assets/agentcraft/textures/entity/agent/<skin>.png |
| `state` | [AgentState](#agentstate) | yes | What the agent is doing right now; drives nameplate dot color, particles and animation. |
| `activity` | string | yes | one short line for the nameplate, e.g. "editing src/cli.ts" (<= 48 chars) |
| `station` | [Station](#station) | yes | Where in the HQ the agent should walk to. `user` = next to the player / Decision Podium. |
| `taskId` | string | no |  |
| `repoId` | string | no |  |
| `worktree` | string | no | id of the worktree the agent is working in (see Repo.worktrees) |
| `paused` | boolean | yes |  |
| `active` | boolean | yes | false = off shift (not on the current team, or stopped by the user); render idle in the lounge |
| `engine` | `claude` \| `codex` | no | which engine runs this agent (absent: the sim) |
| `model` | string | no | the model it runs, for display, e.g. "Opus 5.5" or "GPT-6 Astra" (the real model once a turn reported it) |
| `team` | `studio` \| `social` \| `content` | no | which room the agent belongs to: omitted/"studio" = the coding team; "social" = the Social Desk (Nova plans posts, Sable publishes them); "content" = the Content Room (Iris pitches, Reel produces, Vega analyses, Cleo uploads YouTube videos). Social/content agents never take coding tasks; seat them in their own room |

### <a id="logentry"></a>LogEntry

| field | type | required | notes |
| --- | --- | --- | --- |
| `ts` | integer | yes | epoch milliseconds |
| `kind` | [LogKind](#logkind) | yes |  |
| `text` | string | yes | may contain newlines; `diff` entries use unified +/- line prefixes |

### <a id="task"></a>Task

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "t3" |
| `title` | string | yes |  |
| `description` | string | no |  |
| `status` | [TaskStatus](#taskstatus) | yes | Task Wall column. `cancelled` tasks are kept for history but should not be shown on the wall. |
| `assignee` | string | no | agent id |
| `deps` | string[] | yes | task ids that must be done before this one can start |
| `repoId` | string | no |  |
| `goalId` | string | no |  |
| `priority` | integer | yes | higher = sooner; default 0 |
| `branch` | string | no | git branch, e.g. "agentcraft/kit/t2-tag-parser" |
| `startBranch` | string | no | the worker's branch starts from this branch instead of the base, e.g. a fetched pull request "agentcraft/pr-12" |
| `worktree` | string | no |  |
| `ci` | [CiStatus](#cistatus) | yes |  |
| `blockedReason` | string | no |  |
| `summary` | string | no | worker/lead summary of the result |
| `createdBy` | string | yes | agent id or "user" |
| `createdAt` | integer | yes | epoch milliseconds |
| `updatedAt` | integer | yes | epoch milliseconds |

### <a id="decision"></a>Decision

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "d4" |
| `agentId` | string | yes | agent waiting on this decision |
| `kind` | [DecisionKind](#decisionkind) | yes |  |
| `question` | string | yes |  |
| `options` | string[] | yes | button labels; first is the default/recommended choice |
| `context` | string | no | extra detail, multi-line plain text. permission: why it asks, the cwd, and a line `"Always allow for this agent" covers: ...` (the scope of that choice). merge: summary + diff stat; after a refused merge the decision is open again and this ends with `Merge refused: <reason>` |
| `status` | [DecisionStatus](#decisionstatus) | yes | `cancelled` = became moot (agent stopped, task cancelled); render like answered. |
| `answer` | [DecisionAnswer](#decisionanswer) | no |  |
| `taskId` | string | no |  |
| `repoId` | string | no | merge decisions: repo to request the diff from |
| `worktree` | string | no | merge decisions: worktree to request the diff for |
| `tool` | string | no | permission decisions: tool name, e.g. "Bash" |
| `createdAt` | integer | yes | epoch milliseconds |

### <a id="decisionanswer"></a>DecisionAnswer

| field | type | required | notes |
| --- | --- | --- | --- |
| `option` | string | no | the chosen option label (one of Decision.options) |
| `text` | string | no | free-text answer / feedback |
| `ts` | integer | yes | epoch milliseconds |

### <a id="repo"></a>Repo

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "demo-app" |
| `name` | string | yes |  |
| `path` | string | yes | absolute path of the user checkout |
| `branch` | string | yes | base branch agents branch from and merge into |
| `head` | string | no | short sha of base branch |
| `dirty` | boolean | yes | user checkout has uncommitted tracked changes (merges are refused while dirty) |
| `worktrees` | [Worktree](#worktree)[] | yes |  |
| `ci` | `unknown` \| `running` \| `pass` \| `fail` | yes | latest CI/test result across this repo |

### <a id="worktree"></a>Worktree

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "kit-t2" — use as `worktree` in diff.request |
| `agentId` | string | yes |  |
| `taskId` | string | no |  |
| `branch` | string | yes |  |
| `base` | string | yes | branch it was created from / will merge into |
| `path` | string | yes |  |
| `status` | [WorktreeStatus](#worktreestatus) | yes |  |
| `ahead` | integer | yes | commits on branch not on base |
| `files` | integer | yes | files changed vs base (incl. uncommitted) |
| `additions` | integer | yes |  |
| `deletions` | integer | yes |  |

### <a id="memoryentry"></a>MemoryEntry

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | "shared/<slug>" or "<agentId>/<slug>" |
| `scope` | string | yes | "shared" or an agent id |
| `title` | string | yes |  |
| `body` | string | yes | markdown |
| `updated` | integer | yes | epoch milliseconds |
| `author` | string | no | agent id or "user" that last wrote it |

### <a id="goal"></a>Goal

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "g1" |
| `text` | string | yes |  |
| `progress` | number | yes |  |
| `status` | `planning` \| `active` \| `done` \| `failed` \| `cancelled` | yes | planning (lead is planning) -> active -> done (every non-cancelled task merged/done); cancelled: every task was cancelled or rejected (back to active if the lead adds a task); failed: planning failed |
| `repoId` | string | no |  |
| `createdAt` | integer | yes | epoch milliseconds |
| `updatedAt` | integer | yes | epoch milliseconds |

### <a id="feeditem"></a>FeedItem

| field | type | required | notes |
| --- | --- | --- | --- |
| `ts` | integer | yes | epoch milliseconds |
| `kind` | [FeedKind](#feedkind) | yes |  |
| `text` | string | yes |  |
| `agentId` | string | no | who it is about / from |
| `to` | string | no | message recipient: agent id, "user" or "all" |

### <a id="foremanstatus"></a>ForemanStatus

| field | type | required | notes |
| --- | --- | --- | --- |
| `version` | string | yes |  |
| `backend` | [BackendName](#backendname) | yes |  |
| `auth` | [AuthStatus](#authstatus) | yes | `failed` must be shown loudly (in-world banner): the claude backend cannot run. |
| `message` | string | no | human-readable backend/auth status for the banner |
| `account` | string | no | e.g. organization / plan when auth ok |
| `speed` | number | no | sim: speed multiplier |
| `showcase` | boolean | no | sim: holding a static showcase state (`--showcase` or `--showcase late`) |
| `costUsd` | number | no | claude: estimated spend of this profile (sum over all sessions, survives restarts) |
| `userName` | string | no | the person the team works for, as the agents address them (UI: "<name> answered") |
| `autoApprove` | boolean | no | true: agents run tool calls without asking (denied ones such as git push stay denied); toggled with `settings.set` |

### <a id="agentlogs"></a>AgentLogs

| field | type | required | notes |
| --- | --- | --- | --- |
| `agentId` | string | yes |  |
| `entries` | [LogEntry](#logentry)[] | yes |  |

### <a id="agentscreen"></a>AgentScreen

| field | type | required | notes |
| --- | --- | --- | --- |
| `agentId` | string | yes |  |
| `mime` | string | yes | image MIME type, e.g. image/png or image/jpeg |
| `data` | string | yes | base64 image bytes (<= ~3 MB of base64) |
| `tool` | string | yes | tool that produced it, e.g. mcp__claude-in-chrome__computer |
| `at` | integer | yes | epoch ms when the frame was captured |

### <a id="media"></a>Media

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes |  |
| `agentId` | string | yes | who handed it over |
| `kind` | `image` \| `video` | yes |  |
| `name` | string | yes | file name |
| `caption` | string | no |  |
| `mime` | string | yes | MIME type of every frame: image/png or image/jpeg |
| `frames` | string[] | yes | base64 frames: one for an image; a video sampled to <= 120 JPEG frames, <= 640 px wide |
| `fps` | number | no | video: playback rate of `frames` (loop them) |
| `durationMs` | integer | no | video: length of the original |
| `hasAudio` | boolean | no | video: the original has sound (play it with `media.open`) |
| `at` | integer | yes | epoch milliseconds |

### <a id="doc"></a>Doc

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes |  |
| `agentId` | string | yes | who handed it over |
| `title` | string | yes | first # heading, the title the agent gave, or the file name |
| `name` | string | yes | file name |
| `path` | string | yes | where the file is (often in the agent worktree) |
| `markdown` | string | yes | the document (markdown or plain text, <= 200k chars) |
| `note` | string | no | what the agent says when handing it over |
| `taskId` | string | no |  |
| `read` | boolean | yes | false until the user opens it (`doc.read`); the agent comes to the player while it has unread docs |
| `at` | integer | yes | epoch milliseconds |

### <a id="socialpost"></a>SocialPost

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "p3" |
| `platform` | `x` \| `linkedin` | yes |  |
| `text` | string | yes | the exact post text (x: <= 280 chars unless long posts were allowed; linkedin: <= 3000) |
| `scheduledAt` | integer | no | when it goes out (only once approved); a draft may have none yet |
| `status` | `draft` \| `approved` \| `posting` \| `posted` \| `failed` \| `cancelled` | yes | draft = proposed, waits for the user; approved = the user said yes, goes out at `scheduledAt`; posting = the publisher is on it right now; posted/failed/cancelled are final (a failed one can be approved again) |
| `authorId` | string | yes | agent that drafted it ("nova") or "user" |
| `note` | string | no | the planner's one-line reasoning |
| `mediaPaths` | string[] | no | local image/video files to attach |
| `url` | string | no | link to the live post, once posted |
| `error` | string | no | why it failed |
| `createdAt` | integer | yes | epoch milliseconds |
| `updatedAt` | integer | yes | epoch milliseconds |
| `postedAt` | integer | no | epoch milliseconds |

### <a id="contentchannel"></a>ContentChannel

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "ch1" |
| `name` | string | yes |  |
| `url` | string | no | e.g. https://www.youtube.com/@handle |
| `youtubeId` | string | no | the YouTube channel id (UC...), once resolved |
| `niche` | string | no |  |
| `brief` | string | no | voice, audience, rules (<= 2000 chars) |
| `autopilot` | boolean | yes | true: the agents approve ideas and cuts themselves and keep the queue full |
| `postsPerWeek` | integer | yes | cadence: publishing slots per week, spread over the day between 09:00 and 22:00 in the calendar time zone (1 a day = 18:00; 5 a day = 09, 12, 15, 18, 21) |
| `statsSource` | `studio` \| `api` \| `public` | no | where the numbers come from: api = OAuth analytics, studio = read from YouTube Studio in Chrome within the last 24 h, public = yt-dlp public numbers only; absent = no numbers yet |
| `connected` | boolean | yes | full analytics available: OAuth tokens exist (statsSource api) or Vega read YouTube Studio in Chrome within the last 24 h (statsSource studio); false = public stats only |
| `playbook` | string | no | the channel playbook (markdown, <= 20000 chars): what works / what does not, with numbers. Written by Iris and Vega |
| `createdAt` | integer | yes | epoch milliseconds |
| `updatedAt` | integer | yes | epoch milliseconds |

### <a id="contentvideo"></a>ContentVideo

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "v3" |
| `channelId` | string | yes |  |
| `status` | `idea` \| `approved` \| `producing` \| `review` \| `scheduled` \| `publishing` \| `published` \| `rejected` \| `failed` | yes | idea = pitched, waits for the user; approved = go (Reel picks it up); producing = Reel is making it; review = the cut waits for the user; scheduled = approved, goes out at `scheduledAt`; publishing = Cleo is uploading it right now; published/rejected are final; failed = the upload failed (retry re-schedules it) |
| `format` | `short` \| `long` | yes |  |
| `title` | string | yes | <= 100 chars (YouTube limit) |
| `hook` | string | no | the first 3 seconds |
| `pitch` | string | no | why this video, what it copies, the expected result (<= 1500) |
| `script` | string | no | <= 20000 |
| `description` | string | no | <= 5000 |
| `tags` | string[] | no | total <= 500 chars |
| `refIds` | string[] | no | studied reference videos (ContentRef.id) |
| `filePath` | string | no | the cut (absolute path) |
| `thumbnailPath` | string | no |  |
| `scheduledAt` | integer | no | epoch milliseconds |
| `autoScheduled` | boolean | no | true: scheduled by the channel's autopilot, not approved by the user; turning autopilot off sends it back to review |
| `url` | string | no | link to the live video, once published |
| `youtubeVideoId` | string | no |  |
| `error` | string | no |  |
| `authorId` | string | yes | agent that pitched it ("iris") or "user" |
| `comments` | { by: string, text: string, at: integer }[] | yes | the thread between the user and the agents (last 50) |
| `createdAt` | integer | yes | epoch milliseconds |
| `updatedAt` | integer | yes | epoch milliseconds |
| `publishedAt` | integer | no | epoch milliseconds |
| `postMortems` | { at: integer, horizon: `48h` \| `7d`, text: string }[] | no |  |

### <a id="contentstat"></a>ContentStat

| field | type | required | notes |
| --- | --- | --- | --- |
| `videoId` | string | yes | YouTube video id (our videos AND references) |
| `at` | integer | yes | epoch milliseconds |
| `views` | number | yes |  |
| `likes` | number | no |  |
| `comments` | number | no |  |
| `avgViewDurationSec` | number | no |  |
| `avgViewPct` | number | no | percent 0..100 |
| `watchMinutes` | number | no |  |
| `subsGained` | number | no |  |
| `impressions` | number | no |  |
| `ctr` | number | no | 0..1 |
| `retention` | number[] | no | audience watch ratio at 0,5,10..100% of the video (21 points); only with OAuth analytics or a YouTube Studio read |
| `stayedToWatchPct` | number | no | Shorts: percent 0..100 of viewers who stayed to watch (Studio "viewed vs swiped away") |
| `swipeAwayPct` | number | no | Shorts: percent 0..100 of viewers who swiped away |

### <a id="contentref"></a>ContentRef

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | yes | e.g. "r2" |
| `source` | string | yes | URL or path that was studied |
| `title` | string | no |  |
| `channel` | string | no |  |
| `durationSec` | number | no |  |
| `views` | number | no |  |
| `likes` | number | no |  |
| `uploadDate` | string | no |  |
| `dir` | string | yes | folder with frames/, sheets/, transcript.txt, meta.json, breakdown.md |
| `frameCount` | integer | yes |  |
| `sheetCount` | integer | yes |  |
| `hasTranscript` | boolean | yes |  |
| `studiedBy` | string | yes |  |
| `at` | integer | yes | epoch milliseconds |

### <a id="contentchannelstats"></a>ContentChannelStats

| field | type | required | notes |
| --- | --- | --- | --- |
| `channelId` | string | yes |  |
| `at` | integer | yes | epoch milliseconds |
| `subscribers` | number | no |  |
| `totalViews` | number | no |  |
| `videoCount` | number | no |  |
| `views28d` | number | no |  |
| `watchMinutes28d` | number | no |  |
| `subsGained28d` | number | no |  |
| `daily` | { date: string, views: number, subsGained?: number, watchMinutes?: number }[] | no | last 28 days, oldest first |

### <a id="difffile"></a>DiffFile

| field | type | required | notes |
| --- | --- | --- | --- |
| `path` | string | yes |  |
| `oldPath` | string | no | renames only |
| `status` | `added` \| `modified` \| `deleted` \| `renamed` | yes |  |
| `binary` | boolean | yes |  |
| `additions` | integer | yes |  |
| `deletions` | integer | yes |  |
| `hunks` | [DiffHunk](#diffhunk)[] | yes |  |

### <a id="diffhunk"></a>DiffHunk

| field | type | required | notes |
| --- | --- | --- | --- |
| `header` | string | yes | e.g. "@@ -12,6 +12,9 @@ export function run" |
| `oldStart` | integer | yes |  |
| `oldLines` | integer | yes |  |
| `newStart` | integer | yes |  |
| `newLines` | integer | yes |  |
| `lines` | [DiffLine](#diffline)[] | yes |  |

### <a id="diffline"></a>DiffLine

| field | type | required | notes |
| --- | --- | --- | --- |
| `kind` | `add` \| `del` \| `ctx` | yes |  |
| `text` | string | yes | line content without the +/-/space prefix |
| `oldNo` | integer | no | line number in base (del, ctx) |
| `newNo` | integer | no | line number in branch (add, ctx) |

## Foreman -> Mod

### `snapshot`

Full state. Sent in reply to every `hello`; the mod rebuilds its view from it.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `foreman` | [ForemanStatus](#foremanstatus) | yes |  |
| `agents` | [Agent](#agent)[] | yes |  |
| `tasks` | [Task](#task)[] | yes |  |
| `decisions` | [Decision](#decision)[] | yes | open decisions plus the most recent answered ones |
| `repos` | [Repo](#repo)[] | yes |  |
| `memory` | [MemoryEntry](#memoryentry)[] | yes |  |
| `goal` | [Goal](#goal) | no | current (latest) goal |
| `goals` | [Goal](#goal)[] | yes | all goals, oldest first |
| `feed` | [FeedItem](#feeditem)[] | yes | most recent feed items, oldest first (<= 200) |
| `logs` | [AgentLogs](#agentlogs)[] | yes | recent log tail per agent (<= 60 entries each) |
| `screens` | [AgentScreen](#agentscreen)[] | no | latest fresh (< 90 s) screen frame per agent; omitted when none |
| `media` | [Media](#media)[] | no | newest media agents handed over, oldest first (<= 4); omitted when none |
| `docs` | [Doc](#doc)[] | no | documents agents handed over, oldest first (<= 30); omitted when none |
| `socialPosts` | [SocialPost](#socialpost)[] | no | the Social Desk calendar: every draft/approved/posting/failed post plus the last 100 finished ones, oldest first; omitted when none |
| `contentChannels` | [ContentChannel](#contentchannel)[] | no | the Content Room: YouTube channels; omitted when none |
| `contentVideos` | [ContentVideo](#contentvideo)[] | no | the Content Room pipeline: every open video plus the last 200 finished ones, oldest first; omitted when none |
| `contentRefs` | [ContentRef](#contentref)[] | no | studied reference videos, the last 50, oldest first; omitted when none |
| `contentStats` | [ContentStat](#contentstat)[] | no | the latest stat snapshot per YouTube video id, plus up to 30 history points per own published video (oldest first); omitted when none |
| `contentChannelStats` | [ContentChannelStats](#contentchannelstats)[] | no | the latest channel-level stats per channel; omitted when none |

```json
{
  "v": 1,
  "type": "snapshot",
  "foreman": {
    "version": "0.1.0",
    "backend": "claude",
    "auth": "ok",
    "account": "fal · Claude Enterprise",
    "message": "Claude (lead opus, workers sonnet)",
    "costUsd": 0.42
  },
  "agents": [
    {
      "id": "kit",
      "name": "Kit",
      "role": "worker",
      "title": "Builder & tester",
      "color": "#2E78C6",
      "accent": "#F4EFE6",
      "skin": "kit",
      "state": "editing",
      "activity": "editing src/tags.ts",
      "station": "desk",
      "taskId": "t2",
      "repoId": "demo-app",
      "worktree": "kit-t2",
      "paused": false,
      "active": true
    }
  ],
  "tasks": [
    {
      "id": "t2",
      "title": "Tag parser module (src/tags.ts)",
      "description": "parseTags/hasTag/normalizeTag with unit tests.",
      "status": "doing",
      "assignee": "kit",
      "deps": [
        "t1"
      ],
      "repoId": "demo-app",
      "goalId": "g1",
      "priority": 2,
      "branch": "agentcraft/kit/t2-tag-parser-module",
      "worktree": "kit-t2",
      "ci": "fail",
      "createdBy": "marlow",
      "createdAt": 1790850000000,
      "updatedAt": 1790850060000
    }
  ],
  "decisions": [
    {
      "id": "d2",
      "agentId": "marlow",
      "kind": "merge",
      "question": "Merge t2 \"Tag parser module (src/tags.ts)\" (agentcraft/kit/t2-tag-parser-module) into main?",
      "options": [
        "Merge",
        "Request changes",
        "Reject"
      ],
      "context": "2 files, +45 -0 | tests: pass",
      "status": "open",
      "taskId": "t2",
      "repoId": "demo-app",
      "worktree": "kit-t2",
      "createdAt": 1790850120000
    }
  ],
  "repos": [
    {
      "id": "demo-app",
      "name": "demo-app",
      "path": "C:\\Projects\\agentcraft\\sandbox\\demo-app",
      "branch": "main",
      "head": "a6cbf49",
      "dirty": false,
      "ci": "pass",
      "worktrees": [
        {
          "id": "kit-t2",
          "agentId": "kit",
          "taskId": "t2",
          "branch": "agentcraft/kit/t2-tag-parser-module",
          "base": "main",
          "path": "C:\\Users\\you\\.agentcraft\\claude\\worktrees\\demo-app\\kit-t2",
          "status": "active",
          "ahead": 1,
          "files": 2,
          "additions": 45,
          "deletions": 0
        }
      ]
    }
  ],
  "memory": [
    {
      "id": "shared/plan",
      "scope": "shared",
      "title": "Plan: #tags for pocket-notes",
      "body": "# Plan\n\n- t2 Tag parser module - Kit\n- t3 `list --tag` - Juniper",
      "updated": 1790850030000,
      "author": "marlow"
    }
  ],
  "goal": {
    "id": "g1",
    "text": "Add #tags to pocket-notes",
    "progress": 0.39,
    "status": "active",
    "repoId": "demo-app",
    "createdAt": 1790850000000,
    "updatedAt": 1790850120000
  },
  "goals": [
    {
      "id": "g1",
      "text": "Add #tags to pocket-notes",
      "progress": 0.39,
      "status": "active",
      "repoId": "demo-app",
      "createdAt": 1790850000000,
      "updatedAt": 1790850120000
    }
  ],
  "feed": [
    {
      "ts": 1790850005000,
      "kind": "plan",
      "text": "Marlow planned the goal into 9 tasks",
      "agentId": "marlow"
    }
  ],
  "logs": [
    {
      "agentId": "kit",
      "entries": [
        {
          "ts": 1790850090000,
          "kind": "tool",
          "text": "Edit src/tags.ts"
        }
      ]
    }
  ]
}
```

### `agent.upsert`

An agent was created or changed (state, station, activity, task...). Replace by `agent.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `agent` | [Agent](#agent) | yes |  |

```json
{
  "v": 1,
  "type": "agent.upsert",
  "agent": {
    "id": "kit",
    "name": "Kit",
    "role": "worker",
    "title": "Builder & tester",
    "color": "#2E78C6",
    "accent": "#F4EFE6",
    "skin": "kit",
    "state": "editing",
    "activity": "editing src/tags.ts",
    "station": "desk",
    "taskId": "t2",
    "repoId": "demo-app",
    "worktree": "kit-t2",
    "paused": false,
    "active": true
  }
}
```

### `agent.log`

New log lines for an agent monitor (append; keep a bounded tail).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `agentId` | string | yes |  |
| `entries` | [LogEntry](#logentry)[] | yes |  |

```json
{
  "v": 1,
  "type": "agent.log",
  "agentId": "kit",
  "entries": [
    {
      "ts": 1790850091000,
      "kind": "tool",
      "text": "$ npm test"
    },
    {
      "ts": 1790850092000,
      "kind": "error",
      "text": "tests FAILED (0.4s) - tests 15, pass 12, fail 3\nFAIL parseTags keeps hyphenated tags"
    },
    {
      "ts": 1790850093000,
      "kind": "diff",
      "text": "src/tags.ts\n- const TAG_RE = /#(\\w+)/g;\n+ const TAG_RE = /(?:^|\\s)#(\\w[\\w-]*)/g;"
    }
  ]
}
```

### `agent.screen`

Live screenshot an agent just got from a tool (Claude in Chrome, Roblox Studio, Blender...). Throttled to ~1 frame / 1.5 s per agent; replace the agent monitor image. The latest fresh frame per agent is also in `snapshot.screens`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `agentId` | string | yes |  |
| `mime` | string | yes | image MIME type, e.g. image/png or image/jpeg |
| `data` | string | yes | base64 image bytes (<= ~3 MB of base64) |
| `tool` | string | yes | tool that produced it, e.g. mcp__claude-in-chrome__computer |
| `at` | integer | yes | epoch ms when the frame was captured |

```json
{
  "v": 1,
  "type": "agent.screen",
  "agentId": "kit",
  "mime": "image/png",
  "data": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
  "tool": "mcp__claude-in-chrome__computer",
  "at": 1790850094000
}
```

### `media.add`

An agent handed the user an image or a video (`show_media`). Append to the media gallery (Screening Room). The newest few are also in `snapshot.media`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `item` | [Media](#media) | yes |  |

```json
{
  "v": 1,
  "type": "media.add",
  "item": {
    "id": "m1",
    "agentId": "wren",
    "kind": "video",
    "name": "launch-teaser.mp4",
    "caption": "First cut of the launch teaser",
    "mime": "image/jpeg",
    "frames": [
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    ],
    "fps": 8,
    "durationMs": 14200,
    "hasAudio": true,
    "at": 1790850094500
  }
}
```

### `doc.upsert`

An agent handed the user a document (`hand_over`), or it was read. Replace by `doc.id`. While a doc is unread its agent walks to the player and holds it out; right-click the agent (or `/docs`) to read it.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `doc` | [Doc](#doc) | yes |  |

```json
{
  "v": 1,
  "type": "doc.upsert",
  "doc": {
    "id": "doc1",
    "agentId": "juniper",
    "title": "Tag parser research",
    "name": "tag-parser-research.md",
    "path": "/home/you/.agentcraft/claude/worktrees/demo-app/juniper-t3/docs/tag-parser-research.md",
    "markdown": "# Tag parser research\n\n- `#tag` at the start of a word only\n- emoji tags need `\\p{Extended_Pictographic}`",
    "note": "Here is what I found on tag parsing.",
    "taskId": "t3",
    "read": false,
    "at": 1790850096000
  }
}
```

### `social.post.upsert`

A Social Desk post was created or changed (calendar). Replace by `post.id`. The whole calendar is in `snapshot.socialPosts`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `post` | [SocialPost](#socialpost) | yes |  |

```json
{
  "v": 1,
  "type": "social.post.upsert",
  "post": {
    "id": "p3",
    "platform": "x",
    "text": "Shipped: your AI team now has a Social Desk. Nova plans the posts, Sable publishes them, you approve once.",
    "scheduledAt": 1790936400000,
    "status": "approved",
    "authorId": "nova",
    "note": "Launch day, morning slot when the audience is online.",
    "mediaPaths": [
      "/home/you/.agentcraft/claude/social/launch.png"
    ],
    "createdAt": 1790850097000,
    "updatedAt": 1790850098000
  }
}
```

### `content.channel.upsert`

A Content Room channel was added or changed (autopilot, cadence, connected, playbook). Replace by `channel.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `channel` | [ContentChannel](#contentchannel) | yes |  |

```json
{
  "v": 1,
  "type": "content.channel.upsert",
  "channel": {
    "id": "ch1",
    "name": "Tiny Robots",
    "url": "https://www.youtube.com/@tinyrobots",
    "youtubeId": "UC1234567890abcdefghij",
    "niche": "short explainers about robotics",
    "brief": "Warm, curious, no hype. Audience: teenagers.",
    "autopilot": false,
    "postsPerWeek": 3,
    "connected": true,
    "playbook": "# Playbook\n\n- Hooks that name a number in the first 2 s keep 78% at 3 s.",
    "createdAt": 1790850099000,
    "updatedAt": 1790850100000
  }
}
```

### `content.video.upsert`

A Content Room video was created or changed (idea, comment, status). Replace by `video.id`. The pipeline is in `snapshot.contentVideos`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `video` | [ContentVideo](#contentvideo) | yes |  |

```json
{
  "v": 1,
  "type": "content.video.upsert",
  "video": {
    "id": "v3",
    "channelId": "ch1",
    "status": "review",
    "format": "short",
    "title": "Why this robot never falls over",
    "hook": "This robot gets kicked 40 times and never falls.",
    "pitch": "Copies the pacing of r2 (cut every 1.4 s); expect 60% average view.",
    "refIds": [
      "r2"
    ],
    "filePath": "/home/you/.agentcraft/content/cuts/v3.mp4",
    "thumbnailPath": "/home/you/.agentcraft/content/cuts/v3.jpg",
    "authorId": "iris",
    "comments": [
      {
        "by": "user",
        "text": "Make the hook punchier.",
        "at": 1790850101000
      }
    ],
    "createdAt": 1790850100500,
    "updatedAt": 1790850102000
  }
}
```

### `content.ref.upsert`

A reference video was studied frame by frame (or re-studied). Replace by `ref.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `ref` | [ContentRef](#contentref) | yes |  |

```json
{
  "v": 1,
  "type": "content.ref.upsert",
  "ref": {
    "id": "r2",
    "source": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "title": "Robot kick test",
    "channel": "Some Robots",
    "durationSec": 42,
    "views": 1200000,
    "likes": 54000,
    "uploadDate": "20260301",
    "dir": "/home/you/.agentcraft/content/refs/r2",
    "frameCount": 84,
    "sheetCount": 6,
    "hasTranscript": true,
    "studiedBy": "reel",
    "at": 1790850103000
  }
}
```

### `content.stats`

An analytics sync finished: the full latest set. Replace your stats by `videoId` (history: several points per own video, oldest first) and `channelStats` by `channelId`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `stats` | [ContentStat](#contentstat)[] | yes |  |
| `channelStats` | [ContentChannelStats](#contentchannelstats)[] | yes |  |

```json
{
  "v": 1,
  "type": "content.stats",
  "stats": [
    {
      "videoId": "abc123XYZ_-",
      "at": 1790850104000,
      "views": 5400,
      "likes": 410,
      "comments": 12,
      "avgViewDurationSec": 21,
      "avgViewPct": 63.5,
      "watchMinutes": 1900,
      "subsGained": 31,
      "impressions": 40000,
      "ctr": 0.062,
      "retention": [
        1,
        0.9,
        0.82,
        0.74,
        0.7,
        0.66,
        0.63,
        0.6,
        0.58,
        0.55,
        0.52,
        0.5,
        0.48,
        0.45,
        0.43,
        0.4,
        0.38,
        0.35,
        0.3,
        0.25,
        0.2
      ]
    }
  ],
  "channelStats": [
    {
      "channelId": "ch1",
      "at": 1790850104000,
      "subscribers": 1280,
      "totalViews": 90000,
      "videoCount": 14,
      "views28d": 31000,
      "watchMinutes28d": 9800,
      "subsGained28d": 210,
      "daily": [
        {
          "date": "2026-10-13",
          "views": 1200,
          "subsGained": 9,
          "watchMinutes": 400
        }
      ]
    }
  ]
}
```

### `agent.say`

Speech bubble above the agent; also mirrored to the feed.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `agentId` | string | yes |  |
| `text` | string | yes |  |
| `to` | string | no | agent id, "user" or "all"; omitted = said to the room |
| `ts` | integer | yes | epoch milliseconds |

```json
{
  "v": 1,
  "type": "agent.say",
  "agentId": "kit",
  "to": "juniper",
  "text": "parseTags() is in src/tags.ts - you are unblocked once it merges.",
  "ts": 1790850095000
}
```

### `task.upsert`

Task created or changed. Replace by `task.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `task` | [Task](#task) | yes |  |

```json
{
  "v": 1,
  "type": "task.upsert",
  "task": {
    "id": "t2",
    "title": "Tag parser module (src/tags.ts)",
    "description": "parseTags/hasTag/normalizeTag with unit tests.",
    "status": "doing",
    "assignee": "kit",
    "deps": [
      "t1"
    ],
    "repoId": "demo-app",
    "goalId": "g1",
    "priority": 2,
    "branch": "agentcraft/kit/t2-tag-parser-module",
    "worktree": "kit-t2",
    "ci": "fail",
    "createdBy": "marlow",
    "createdAt": 1790850000000,
    "updatedAt": 1790850060000
  }
}
```

### `decision.upsert`

Decision opened, answered or cancelled. Replace by `decision.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `decision` | [Decision](#decision) | yes |  |

```json
{
  "v": 1,
  "type": "decision.upsert",
  "decision": {
    "id": "d2",
    "agentId": "marlow",
    "kind": "merge",
    "question": "Merge t2 \"Tag parser module (src/tags.ts)\" (agentcraft/kit/t2-tag-parser-module) into main?",
    "options": [
      "Merge",
      "Request changes",
      "Reject"
    ],
    "context": "2 files, +45 -0 | tests: pass",
    "status": "open",
    "taskId": "t2",
    "repoId": "demo-app",
    "worktree": "kit-t2",
    "createdAt": 1790850120000
  }
}
```

### `repo.upsert`

Repo added or changed (worktrees, CI, head, dirty). Replace by `repo.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `repo` | [Repo](#repo) | yes |  |

```json
{
  "v": 1,
  "type": "repo.upsert",
  "repo": {
    "id": "demo-app",
    "name": "demo-app",
    "path": "C:\\Projects\\agentcraft\\sandbox\\demo-app",
    "branch": "main",
    "head": "a6cbf49",
    "dirty": false,
    "ci": "pass",
    "worktrees": [
      {
        "id": "kit-t2",
        "agentId": "kit",
        "taskId": "t2",
        "branch": "agentcraft/kit/t2-tag-parser-module",
        "base": "main",
        "path": "C:\\Users\\you\\.agentcraft\\claude\\worktrees\\demo-app\\kit-t2",
        "status": "active",
        "ahead": 1,
        "files": 2,
        "additions": 45,
        "deletions": 0
      }
    ]
  }
}
```

### `memory.upsert`

Memory entry written. Replace by `entry.id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `entry` | [MemoryEntry](#memoryentry) | yes |  |

```json
{
  "v": 1,
  "type": "memory.upsert",
  "entry": {
    "id": "shared/plan",
    "scope": "shared",
    "title": "Plan: #tags for pocket-notes",
    "body": "# Plan\n\n- t2 Tag parser module - Kit\n- t3 `list --tag` - Juniper",
    "updated": 1790850030000,
    "author": "marlow"
  }
}
```

### `goal.upsert`

Goal created or progress/status changed. Replace by `goal.id`; latest goal is current.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `goal` | [Goal](#goal) | yes |  |

```json
{
  "v": 1,
  "type": "goal.upsert",
  "goal": {
    "id": "g1",
    "text": "Add #tags to pocket-notes",
    "progress": 0.56,
    "status": "active",
    "repoId": "demo-app",
    "createdAt": 1790850000000,
    "updatedAt": 1790850200000
  }
}
```

### `feed.add`

Append to the activity feed.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `item` | [FeedItem](#feeditem) | yes |  |

```json
{
  "v": 1,
  "type": "feed.add",
  "item": {
    "ts": 1790850210000,
    "kind": "merge",
    "text": "Merged agentcraft/kit/t2-tag-parser-module into main (7cf1999, 2 files)",
    "agentId": "marlow"
  }
}
```

### `diff`

Reply to `diff.request` (sent only to the requesting client). Structured unified diff of worktree vs base, including uncommitted changes.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `requestId` | string | yes |  |
| `repoId` | string | yes |  |
| `worktree` | string | yes |  |
| `base` | string | no |  |
| `branch` | string | no |  |
| `files` | [DiffFile](#difffile)[] | yes |  |
| `stats` | { files: integer, additions: integer, deletions: integer } | yes |  |
| `truncated` | boolean | yes | true if very large files/hunks were cut |
| `error` | string | no |  |

```json
{
  "v": 1,
  "type": "diff",
  "requestId": "r7",
  "repoId": "demo-app",
  "worktree": "kit-t2",
  "base": "main",
  "branch": "agentcraft/kit/t2-tag-parser-module",
  "files": [
    {
      "path": "src/tags.ts",
      "status": "added",
      "binary": false,
      "additions": 2,
      "deletions": 0,
      "hunks": [
        {
          "header": "@@ -0,0 +1,2 @@",
          "oldStart": 0,
          "oldLines": 0,
          "newStart": 1,
          "newLines": 2,
          "lines": [
            {
              "kind": "add",
              "text": "// Tag parsing for notes",
              "newNo": 1
            },
            {
              "kind": "add",
              "text": "export const TAG_RE = /(?:^|\\s)#(\\w[\\w-]*)/g;",
              "newNo": 2
            }
          ]
        }
      ]
    },
    {
      "path": "src/notes.ts",
      "status": "modified",
      "binary": false,
      "additions": 1,
      "deletions": 1,
      "hunks": [
        {
          "header": "@@ -9,3 +9,3 @@ export interface Note {",
          "oldStart": 9,
          "oldLines": 3,
          "newStart": 9,
          "newLines": 3,
          "lines": [
            {
              "kind": "ctx",
              "text": "export interface ListOptions {",
              "oldNo": 9,
              "newNo": 9
            },
            {
              "kind": "del",
              "text": "  all?: boolean;",
              "oldNo": 10
            },
            {
              "kind": "add",
              "text": "  all?: boolean; tag?: string;",
              "newNo": 10
            },
            {
              "kind": "ctx",
              "text": "}",
              "oldNo": 11,
              "newNo": 11
            }
          ]
        }
      ]
    }
  ],
  "stats": {
    "files": 2,
    "additions": 3,
    "deletions": 1
  },
  "truncated": false
}
```

### `notify`

Toast/banner for the player. `need_user` = a decision is waiting (play a bell).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `level` | [NotifyLevel](#notifylevel) | yes |  |
| `text` | string | yes |  |
| `decisionId` | string | no |  |
| `ts` | integer | yes | epoch milliseconds |

```json
{
  "v": 1,
  "type": "notify",
  "level": "need_user",
  "text": "Marlow: Merge t2 \"Tag parser module\" into main?",
  "decisionId": "d2",
  "ts": 1790850120000
}
```

### `foreman.status`

Backend/auth status changed (banner).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `status` | [ForemanStatus](#foremanstatus) | yes |  |

```json
{
  "v": 1,
  "type": "foreman.status",
  "status": {
    "version": "0.1.0",
    "backend": "claude",
    "auth": "failed",
    "message": "Claude login check failed: not logged in. Run `claude` and /login, then restart the Foreman."
  }
}
```

### `ack`

Reply to any client message that carried an `id`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `re` | string | yes | the `id` of the client message being acknowledged |
| `ok` | boolean | yes |  |
| `error` | string | no |  |
| `result` | map<string, any> | no | e.g. {goalId} for goal.submit, {repoId} for repo.add |

```json
{
  "v": 1,
  "type": "ack",
  "re": "c12",
  "ok": true,
  "result": {
    "goalId": "g2"
  }
}
```

### `error`

A client message was invalid or failed (also sent as ack.ok=false when it had an id).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `message` | string | yes |  |
| `re` | string | no |  |

```json
{
  "v": 1,
  "type": "error",
  "message": "no agent named \"kitt\"",
  "re": "c13"
}
```

## Mod -> Foreman

### `hello`

First message after connecting. The Foreman replies with `snapshot`, then streams upserts.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `modVersion` | string | yes |  |
| `protocol` | 1 | yes |  |
| `client` | string | no | "mod" \| "cli" \| ... (informational) |

```json
{
  "v": 1,
  "type": "hello",
  "modVersion": "0.1.0",
  "protocol": 1,
  "client": "mod"
}
```

### `goal.submit`

New goal for the lead (console: plain text).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `text` | string | yes |  |
| `repoId` | string | no | defaults to the only/most recently added repo |

```json
{
  "v": 1,
  "type": "goal.submit",
  "id": "c12",
  "text": "Add a --version flag to the CLI",
  "repoId": "demo-app"
}
```

### `user.message`

Message an agent (console: `@name text`) or everyone.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `to` | string | yes | agent id or "all". With "all", a leading "@name" in text routes to that agent. |
| `text` | string | yes |  |

```json
{
  "v": 1,
  "type": "user.message",
  "id": "c13",
  "to": "all",
  "text": "@kit please also cover #tags with emoji"
}
```

### `decision.answer`

Answer an open decision. Merge decisions: option "Merge" merges, "Request changes" sends `text` back to the worker, "Reject" abandons the branch.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `decisionId` | string | yes |  |
| `option` | string \| integer | no | option label (preferred) or 0-based index into Decision.options |
| `text` | string | no | free text (questions) or feedback (merge "Request changes") |

```json
{
  "v": 1,
  "type": "decision.answer",
  "id": "c14",
  "decisionId": "d2",
  "option": "Request changes",
  "text": "Export TAG_RE so format.ts can reuse it."
}
```

### `task.action`

Steer a task from the Task Wall.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `taskId` | string | yes |  |
| `action` | `reassign` \| `cancel` \| `retry` \| `prioritize` | yes |  |
| `arg` | string | no | reassign: agent id; prioritize: integer priority (default: bump to top) |

```json
{
  "v": 1,
  "type": "task.action",
  "id": "c15",
  "taskId": "t5",
  "action": "reassign",
  "arg": "wren"
}
```

### `agent.action`

Pause/resume/stop an agent, or spawn (activate) an off-shift worker.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `agentId` | string | yes |  |
| `action` | `pause` \| `resume` \| `stop` \| `spawn` | yes | pause: abort the current turn, keep the task (open questions are withdrawn); resume continues it. stop: off shift (active=false) until resume/spawn: turn aborted, open questions/permission prompts withdrawn, its doing tasks go back to the board and the next worker continues from the same branch. spawn: bring an off-shift agent onto the team. |
| `arg` | string | no | spawn: optional task id to assign to the agent |

```json
{
  "v": 1,
  "type": "agent.action",
  "id": "c16",
  "agentId": "juniper",
  "action": "pause"
}
```

### `diff.request`

Ask for the structured diff of a worktree. Answered with `diff` (same requestId).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `requestId` | string | yes |  |
| `repoId` | string | yes |  |
| `worktree` | string | yes | worktree id (e.g. "kit-t2"); an agent id resolves to that agent's current worktree |

```json
{
  "v": 1,
  "type": "diff.request",
  "id": "c17",
  "requestId": "r7",
  "repoId": "demo-app",
  "worktree": "kit-t2"
}
```

### `repo.add`

Register a local git repo (console: `/repo add <path>`).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `path` | string | yes |  |

```json
{
  "v": 1,
  "type": "repo.add",
  "id": "c18",
  "path": "C:\\Projects\\agentcraft\\sandbox\\demo-app"
}
```

### `repo.create`

Create a new empty git repo (README + first commit on main), register it, and optionally start a goal in it (console: `/repo new <name>`, or "+ New repo" in the goal picker). Acks with {repoId, path, goalId?}.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `name` | string | yes | folder name, created under the new-repo dir (default: your home); may be home-relative like "Desktop/my-game" |
| `goal` | string | no | a goal to start in the new repo right away |
| `autoApprove` | boolean | no | also turn auto-approve on (same as `settings.set`), so the goal runs without asking |

```json
{
  "v": 1,
  "type": "repo.create",
  "id": "c21",
  "name": "pixel-garden",
  "goal": "Make a tiny canvas game where flowers grow where you click"
}
```

### `settings.set`

Change a Foreman setting (console: `/auto on|off`). The new value comes back in `foreman.status`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `autoApprove` | boolean | no | true: agents stop asking permission (open permission prompts are allowed once); false: they ask again |

```json
{
  "v": 1,
  "type": "settings.set",
  "id": "c19",
  "autoApprove": true
}
```

### `doc.read`

The user opened a handed-over document: marks it read (the agent goes back to work).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `docId` | string | yes |  |

```json
{
  "v": 1,
  "type": "doc.read",
  "id": "c22",
  "docId": "doc1"
}
```

### `social.post.action`

Act on a Social Desk post from the calendar: approve / unapprove / cancel / post_now / schedule (+ optional edited `text`). Acks with {postId, status}; the change comes back as `social.post.upsert`. Only drafts and failed posts can be approved; posted posts never change.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `postId` | string | yes |  |
| `action` | `approve` \| `unapprove` \| `cancel` \| `post_now` \| `schedule` | yes | approve (needs a future scheduledAt; the ONLY approval: it goes out at that time without asking again), unapprove (back to draft), cancel, post_now (approve and publish right away), schedule (set `at`; an approved post goes back to draft) |
| `at` | integer | no | schedule: the new time (must be in the future) |
| `text` | string | no | an edited text, applied before the action (an approved post goes back to draft unless the action is approve) |

```json
{
  "v": 1,
  "type": "social.post.action",
  "id": "c23",
  "postId": "p3",
  "action": "approve"
}
```

### `content.channel.action`

Manage a Content Room channel: add / update / remove / autopilot_on / autopilot_off / sync. Acks with {channelId}; the change comes back as `content.channel.upsert`.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `channelId` | string | no | required for everything but add |
| `action` | `add` \| `update` \| `remove` \| `autopilot_on` \| `autopilot_off` \| `sync` | yes | add (needs name), update (name/url/niche/brief/postsPerWeek), remove, autopilot_on, autopilot_off, sync (refresh the analytics now) |
| `name` | string | no |  |
| `url` | string | no |  |
| `niche` | string | no |  |
| `brief` | string | no |  |
| `postsPerWeek` | integer | no | 1..70 |

```json
{
  "v": 1,
  "type": "content.channel.action",
  "id": "c24",
  "action": "add",
  "name": "Tiny Robots",
  "url": "https://www.youtube.com/@tinyrobots",
  "niche": "short explainers about robotics",
  "postsPerWeek": 3
}
```

### `content.video.action`

Act on a Content Room video: approve / reject / comment / approve_cut / schedule / publish_now / unschedule / retry. Acks with {videoId, status}; the change comes back as `content.video.upsert`. Comments wake the agent that owns the video.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `videoId` | string | yes |  |
| `action` | `approve` \| `reject` \| `comment` \| `approve_cut` \| `schedule` \| `publish_now` \| `unschedule` \| `retry` | yes | approve (idea -> approved, wakes Reel), reject (idea\|approved\|review -> rejected; `text` = reason), comment (any status; wakes the owner), approve_cut (review -> scheduled at `at`, default the next free slot), schedule (new time for a scheduled video), publish_now, unschedule (scheduled -> review), retry (failed -> scheduled now) |
| `text` | string | no | comment text / reject reason |
| `at` | integer | no | approve_cut / schedule: the publish time |

```json
{
  "v": 1,
  "type": "content.video.action",
  "id": "c25",
  "videoId": "v3",
  "action": "approve_cut",
  "at": 1790936400000
}
```

### `content.video.preview`

Watch a video's finished cut in game: the Foreman turns the whole cut (up to 3 min) into a flipbook (`media.add`) and acks with {videoId, mediaId}; the mod opens its media viewer on that id ("Open in player" plays it with sound). Errors when there is no cut yet.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `videoId` | string | yes |  |

```json
{
  "v": 1,
  "type": "content.video.preview",
  "id": "c27",
  "videoId": "v3"
}
```

### `content.ref.open`

Show a studied reference on the Screening Room screen: its contact sheets, or one frame (1-based). Acks with {refId}.

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `refId` | string | yes |  |
| `frame` | integer | no | 1-based frame number to show; omitted = the contact sheets |

```json
{
  "v": 1,
  "type": "content.ref.open",
  "id": "c26",
  "refId": "r2",
  "frame": 12
}
```

### `media.open`

Open the original file of a media item in the OS default app (videos play with sound).

| field | type | required | notes |
| --- | --- | --- | --- |
| `id` | string | no | client correlation id; the Foreman answers with `ack` {re: id} |
| `mediaId` | string | yes |  |

```json
{
  "v": 1,
  "type": "media.open",
  "id": "c20",
  "mediaId": "m1"
}
```

## Console mapping (mod)

| console input | message |
| --- | --- |
| plain text | `goal.submit {text}` |
| `@name text` | `user.message {to:"all", text:"@name text"}` (the Foreman routes it) or `{to:"name", text}` |
| `/answer [dN] <n\|label> [text]` | `decision.answer {decisionId, option, text?}` |
| `/repo add <path>` | `repo.add {path}` |
| `/pause @name`, `/resume @name`, `/stop @name`, `/spawn @name [taskId]` | `agent.action` (spawn: `arg` = task id) |
| `/task <id> cancel\|retry\|prioritize [n]\|reassign <agent>` | `task.action` |


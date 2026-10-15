# AgentCraft mod: feature map for Phase 3

Read this and `mod/DEV.md` (build, run, DevBridge, 26.3 API notes). Everything below exists and
was verified in game in Phase 2 (`artifacts/shots/phase2_*.png`).

## Rules for parallel work

- **Edit only your feature package** (table below). Each feature has an `init()` that registers
  everything it needs (renderers, HUD elements, screens, keybinds, DevBridge commands, Foreman
  listeners). Central wiring is one line per feature in
  `src/client/java/dev/agentcraft/client/ClientFeatures.java` (client) and
  `src/main/java/dev/agentcraft/AgentCraft.java` (common); both lines already exist for every feature.
- Shared packages (`foreman`, `ui`, `world`, `layout`, `block`, `entity`, `dev`) are owned by the
  core integrator. If you need something there, add it **additively** (a new method or class, never a
  changed signature) and say so in your report, or keep the helper inside your package.
- Assets (textures, models, lang, `gui/ui-style.json`, `gui/kit.json`, `cast.json`) come from
  `assets-src/` via `python assets-src/sync.py`. Don't hand-edit `mod/src/main/resources/assets`.
- Minecraft is **26.3** with Mojang names. Grep `mod/build/mcsrc` (`gradlew mcSources`) for real
  signatures; don't code from 1.21 memory.
- Verify visually: `dev.camera {anchor:"cam_..."}` + `dev.screenshot`, then Read the PNG.

## Packages

| package | owner (Phase 3) | owns |
|---|---|---|
| `client.foreman` | core | WebSocket link, protocol mirror, state model, `Foreman` facade |
| `client.agents` | agents specialist | agent NPCs: manager, motion, pathfinding, renderer, nameplate, hooks |
| `client.hud` | hud specialist | connection banner (done), goal boss bar, in-game toasts |
| `client.monitor` | monitor specialist | `MonitorRenderer` (BER): live agent log on desk monitors |
| `client.taskwall` | task wall specialist | `TaskBoardRenderer` (BER): kanban cards |
| `client.decisions` | decisions specialist | `DecisionPodiumRenderer` + decision GUI |
| `client.console` | console specialist | console screen + keybind, `ConsoleTerminalRenderer` |
| `client.social` | social specialist | Social Desk calendar: `SocialCalendar` (week maths, status colours), `SocialCalendarScreen` |
| `client.content` | content specialist | Content Room: `ContentState` (selection, pipeline columns, analytics rows, formatting), `ContentStudioScreen` + the four tab classes, `ContentFeature` |
| `client.diff` | diff specialist | diff/merge review screen, `MergeStationRenderer` |
| `client.library` | library specialist | memory screen, `MemoryArchiveRenderer` |
| `client.permissions` | permissions specialist | permission decision UX |
| `client.hq` + `hq` (main) | HQ specialist | the real HQ builder (main), world blocks driven by state (client), `StatusLampRenderer` |
| `client.ui` | core (additive) | kit drawing, style tokens, text utils (screens + world) |
| `client.world` | core (additive) | `StationRenderer` base, `ServerTasks`, `StationInteractions`, dev helpers |
| `layout` (main) | core | anchor registry + naming contract |
| `block`, `entity` (main) | core | the 16 blocks, block entities, the agent entity type |

## The Foreman state model (`dev.agentcraft.client.foreman`)

`Foreman.state()` is a `ForemanState`: the client's copy of everything the Foreman knows.
**Read and listen on the client (render) thread only** (renderers' extract step, screens, HUD, tick
handlers). From another thread use `DevBridge.onClient(mc, () -> ...)`.

```java
ForemanState s = Foreman.state();
s.agents();                 // Map<String, Protocol.Agent>, Foreman order
s.agent("kit");             // or null
s.tasks(); s.tasksWithStatus(TaskStatus.DOING);   // priority desc, then oldest
s.decisions(); s.openDecisions(); s.oldestOpen(DecisionKind.MERGE);
s.repos(); s.memory(); s.goal(); s.goals();
s.feed();                   // last 200 FeedItems, oldest first (read-only live view: copy it to keep it)
s.logs("kit");              // last 200 LogEntries of an agent (copy); s.logCount("kit") is cheap
s.lastSay("kit");           // latest agent.say (speech bubble), or null
s.notifications();          // last 20 notify messages (read-only live view)
s.status();                 // ForemanStatus: backend sim|claude, auth ok|failed|..., message, account
s.link();                   // LinkStatus: phase DISABLED|CONNECTING|HANDSHAKE|SYNCED|WAITING_RETRY, attempt, lastError, everSynced
s.isStale();                // link not live: keep showing the last known state, but dimmed / read-only
s.hasData();                // a snapshot was received at least once
s.revision();               // ++ on every change: cache derived layouts by it
```

Change callbacks (all optional, client thread, after the model was updated):

```java
Foreman.addListener(new ForemanListener() {
    public void onSnapshot(ForemanState st) { rebuildEverything(); }      // every (re)connect replaces the model
    public void onAgent(Agent prev, Agent now) { ... }                    // prev == null: new
    public void onTask(Task prev, Task now) { ... }
    public void onDecision(Decision prev, Decision now) { ... }
    public void onLog(String agentId, List<LogEntry> appended) { ... }
    public void onSay(AgentSay say) { ... }
    public void onNotify(Notify n) { ... }                                // need_user = a decision is waiting
    public void onConnection(LinkStatus l) { ... }
    public void onChange(long revision) { ... }                           // after any of the above
});
```

Intents (futures complete on the client thread; they fail fast when the link is not synced; an
`Ack` with `ok == false` carries the Foreman's error text):

```java
Foreman.submitGoal("Add OAuth to life-tracker", null).thenAccept(ack -> ...);   // ack.result().goalId
Foreman.message("kit", "please also cover emoji tags");                          // or ("all", "@kit ...")
Foreman.answer("d3", Protocol.MERGE, null);                                      // REQUEST_CHANGES + text, REJECT, ALLOW_ONCE, ALWAYS_ALLOW, DENY
Foreman.taskAction("t3", "reassign", "wren");                                    // cancel | retry | prioritize [n] | reassign <agent>
Foreman.agentAction("kit", "pause", null);                                       // resume | stop | spawn [taskId]
Foreman.addRepo("C:\\path\\to\\repo");
Foreman.requestDiff(decision.repoId(), decision.worktree()).thenAccept(diff -> ...); // Protocol.Diff, files/hunks/lines
Foreman.send("any.type", payloadJson);                                           // anything else in docs/protocol.md
```

`Protocol` mirrors `docs/protocol.md` exactly (records + enums). Optional fields are nullable,
required lists are never null, unknown enum values map to `UNKNOWN`, unknown fields are ignored.
`AgentState.family()` gives the status family (idle thinking working waiting error done) used for
dots, lamps and colours. Exact option labels: `Protocol.MERGE`, `REQUEST_CHANGES`, `REJECT`,
`ALLOW_ONCE`, `ALWAYS_ALLOW`, `DENY`.

Link details: `ws://127.0.0.1:${AGENTCRAFT_PORT:-7878}` (java.net.http, no Origin header), hello on
every connect, reconnect forever with backoff 0.25 s doubling to 5 s, 15 s pings, a watchdog drops
a silent connection after 45 s. `AGENTCRAFT_FOREMAN=0` disables it.

## Anchors (`dev.agentcraft.layout`)

The single source of named world positions. An HQ builder publishes a layout; it is saved as
`agentcraft-anchors.json` in the world folder and reloaded on start. Read it anywhere:
`Anchors.current()` (immutable `Layout`: name, revision, bounds, anchors), `Anchors.get("desk_kit")`,
`Anchors.addListener(layout -> ...)`.

`Anchor(name, x, y, z, yaw, pitch)`. Stations/agent spots: **feet** position + facing yaw. `cam_*`:
**eye** position + view. Block anchors (`monitor_*`, `task_wall`, `decision_podium`): surface centre,
yaw = the direction the front faces. Yaw: 0 = +Z (south), 90 = -X (west), 180 = north, -90 = east.

Naming contract (`AnchorNames`, required from every HQ builder):

| name | meaning |
|---|---|
| `desk_<agentId>`, `monitor_<agentId>` | per cast agent (marlow juniper kit wren rowan tove) |
| `library`, `terminal`, `testbench`, `mergestation`, `meeting`, `lounge`, `user` | shared station slot 1 (`user` = next to the player / podium) |
| `<station>_2` .. `_N` | more slots at the same station (lounge needs 6: off-shift agents go there) |

**Slot spacing:** nameplates declutter themselves (see Agents), so tight slots never garble, but
plates sit at their natural height only when neighbours are far enough apart on screen. A full plate
is up to 3.15 blocks wide, a name-only pill about 0.9-1.5. Space shared-station slots **at least 1.6
blocks** apart (2+ where agents show activity, e.g. library, meeting), and stagger rows so the back
row is not directly behind the front row. The test room's lounge (1.4 apart, two rows) is the
deliberate worst case.
| `task_wall`, `decision_podium`, `goal_atrium`, `entrance`, `spawn` | fixed points |
| `cam_<name>` | QA camera points |

QA: `dev.anchors {prefix?}` lists them; `dev.camera {anchor:"cam_agents"}` puts the camera on one
(fields given explicitly still win; non-cam anchors stand you on the spot). The temporary test room
(`/agentcraft hq`, `hq.TestRoomBuilder`) writes all of the above plus `cam_overview`, `cam_room`,
`cam_agents` (3 agent faces in the showcase), `cam_commons`, `cam_desk_<id>`, `cam_desks_back`,
`cam_library`, `cam_task_wall`, `cam_east_wall`, `cam_podium`, `cam_blockrow(_left/_right)`.

**HQ specialist:** implement `hq.HqBuilder` (deterministic, idempotent, writes the anchors, sets
`bounds` to the walkable region, binds station block entities), register it in
`HqFeature.init()` with `HqBuilders.register(...)` and `HqBuilders.setDefault(id)`. `/agentcraft hq`
then builds yours; `/agentcraft hq test` still builds the test room.

## The real HQ (`hq.StudioHqBuilder`, default builder `studio`)

`/agentcraft hq` builds it (`/agentcraft hq test` still builds the Phase 2 test room); a fresh HQ
world builds it automatically on first start (`AGENTCRAFT_HQ_AUTOBUILD=0` turns that off). A long
timber-framed studio hall (x -24..24, z -10..6, floor blocks y=65, **agents stand at y=66**) with an
octagonal Goal Atrium in front under a ribbed copper dome with a glazed lantern (centre 0,15;
entrance and spawn at z=22, gabled portico, path south to a lychgate at z=48), terraces, pond,
cottage garden, all in a meadow hollow ringed by low wooded hills (closed horizon).

| zone | where | stations / anchors |
|---|---|---|
| Goal Atrium | octagon, centre (0, 66, 15) | `goal_atrium`; hologram = the `goal:atrium` status lamp's BER (goal text, progress ring, task/agent counts) |
| Task Wall | atrium west wall, 7 x 4 `task_board` facing east, x=-8, z 12..18, y 67..70, walnut hood | `task_wall` (surface centre) |
| Decision Podium | atrium east side, podium at (6, 66, 15) facing west; walnut niche behind (x=8): `decisions` lamp (8,69,15), bell, three signal bulbs (8,71,14..16) | `decision_podium`, `podium_user` (player's spot), `user`, `user_2`, `user_3` (waiting agents, facing the player) |
| Desks | north wall, bays at x -15 -11 -7 7 11 15 = tove juniper marlow wren rowan kit (`DESK_ORDER`), 3 x 2 monitor at z=-9, lamp `agent:<id>` in the wall above (bx, 69, -10) | `desk_<id>` = **the chair** (dark oak stairs at (bx-1, 66, -8), the agents' seat contract; step-in cell bx), `seat_<id>` (same spot), `monitor_<id>`, `cam_desk_<id>` |
| Screening Room | east wing built onto the hall's east gable (`ScreeningRoomBuilder`), x 25..40, z -9..5; doorway in the gable at x=24, z 4..5 (next to the merge station) | big 9 x 5 monitor bound `media` on the east wall (x=40, z -6..2, y 67..71), wall of 6 agent monitors (3 x 2, bound to the agent ids, 2 rows of 3) on the north wall z=-9, two sofa rows facing east (the back one on a 1-block tier, steps at the aisle and the door), `cam_screening`, `cam_media_screen`, `cam_agent_wall` |
| Casino Lounge | west wing built onto the hall's west gable (`CasinoLoungeBuilder`, shared positions in `CasinoLayout`), x -40..-25, z -9..5; doorway at x=-24, z 4..5 (through the library's archive wall, next to the south-west corner) | warm and dim: walnut wainscot, dark-oak floor, red rug, hanging lanterns. Blackjack table = compact 5 x 3 table (dark-oak slabs, fence-post legs) with a 3 x 2 green-carpet felt at x -34..-32, z -6..-5 (surface y 67.06, dealer edge north), 3 stools in front at (-34..-32, -3), a deal shoe on its east rim and a discard tray on its west rim (both drawn by the client), back bar with bottles and shelves behind the dealer, sofas either side of the table and a card nook (round table, two sofas). The dealer is a no-AI, silent, invulnerable villager (cleric, level 5) named `Dealer`, tagged `agentcraft_dealer` (a rebuild removes the old one first), standing at (-33, 66, -7) facing south (client-side it turns its head to the seated player and nods when a card leaves the shoe). `cam_casino`, `cam_casino_table` |
| Social Room | north annex built onto the hall's north wall (`SocialRoomBuilder`), x -9..9, z -24..-11 (interior x -8..8, z -23..-12), its own N-S copper gable (ridge x=0, eaves x +-10); doorway in the middle of the big north window, x -1..0, z -10..-11 (the hall's window seat makes way; the rest of the window becomes a glass wall into the room). `HqLandscape` keeps the ground flat out to z -31 and keeps trees away (`nearRoom`) | Nova's desk (bay x -7, chair `desk_nova` at (-8, 66, -22)) and Sable's desk (bay x 7, chair `desk_sable` at (6, 66, -22)) against the north wall z=-23, 3 x 2 monitors bound `nova` / `sable`, lamps `agent:<id>` in the wall; between them the **wall calendar**, a 9 x 5 monitor bound `calendar` (x -4..4, y 67..71, z=-23); couch (x -2..2, z=-15) + rug + coffee table facing it for you; a 5 x 5 wool banner on each side wall, X white-on-black (east, x=8) and LinkedIn "in" white-on-blue (west, x=-8); plants, shelves, lanterns. `cam_social`, `cam_social_calendar`, `cam_desk_nova`, `cam_desk_sable`; path-finding bounds reach z=-23 |
| Gallery | north annex west of the Social Room (`GalleryRoomBuilder`, shared positions in `GalleryLayout`), x -31..-11, z -25..-11 (interior x -29..-13, z -12..-23); 3-wide doorway in the hall's north wall at x -22..-20, z -10..-11 | framed paintings drawn by the client (`client.gallery`) from real images: every png/jpg/gif/webp in `~/Pictures/AgentCraft Gallery` (rescanned every 4 s while you are near) plus everything agents handed you with show_media (poster frame), newest first, 11 bays (3 north, 3 west, 3 east, 2 south); aspect ratio kept, walnut and gilt frame, brass nameplate. Right-click a painting for the big view (`GalleryViewScreen`, O opens it in Preview; show_media items open `MediaViewScreen`); right-click the lectern (-21, 66, -17) for the next page. `cam_gallery`, `cam_gallery_wall`; `dev.gallery`, `dev.screen {open:"gallery"}` |
| Content Room | north annex east of the Social Room, the Gallery's mirror image (`ContentRoomBuilder`, shared positions in `ContentLayout`), x 11..31, z -25..-11 (interior x 13..29, z -12..-23), its own N-S copper gable (ridge x=21, eaves x 11 and 31); 3-wide doorway in the hall's north wall at x 20..22, z -10..-11. `HqLandscape` keeps trees away (`nearRoom`) | Iris', Reel's, Vega's and Cleo's desks against the north wall z=-23 (bays x 15, 19, 23, 27; chairs `desk_<id>` at (14/18/22/26, 66, -22); 3 x 2 monitors bound to their ids, lamps `agent:<id>`); shelves with lanterns between the bays; the **analytics board** on the east wall, a 9 x 5 monitor bound `content` (x=29, y 67..71, z -22..-14, facing west); sofas (x=26, z -20..-16) + rug facing it; the **lectern** at (21, 66, -18), right-click opens the Content Studio; a red wool play-button banner on the west wall. `cam_content`, `cam_content_board`, `cam_desk_iris/reel/vega/cleo`; `dev.screen {open:"content"}` |
| Library | west end | `library` .. `library_4`; memory archives bound `shared`, catalogs, lecterns |
| Test bench | east wall z -6..-2, CI lamps `ci:#1..#3` in the gable wall | `testbench` .. `testbench_3` |
| Terminals | north-east corner | `terminal`, `terminal_2` |
| Merge station | walnut alcove in the east gable wall, worktops (23, 66, -1..1); `merge` lamp (24,69,0), signal bulbs (24,69,±1) | `mergestation`, `mergestation_2` |
| Lounge | fireplace (south wall), six brown leather armchairs round a low table | `lounge` .. `lounge_6` = the armchairs (seats) |
| Meeting | long table x 8..13, z 0, three chairs a side | `meeting` .. `meeting_6` = the chairs (seats) |

Seats: every `desk_<id>`, `lounge*` and `meeting*` anchor is a bottom-half stairs block with its
back away from the table/desk and a free floor cell on its right-hand side (the cell the agents
step in from). `dev.hq.check` lists them under `seats`. Without the agents' seat support an agent
would stand inside the chair.

Cameras: every QA anchor (`cam_exterior_hero`, `cam_entrance_atrium`, `cam_task_wall`,
`cam_agent_desk` = Juniper's desk, `cam_wide_interior`, `cam_decision_podium`, `cam_night`,
`cam_console`, `cam_merge_station`, `cam_library`, `cam_desk_<id>`) plus `cam_hall`, `cam_lounge`,
`cam_testbench`. Desk cameras look over the agent's right shoulder (seat at the left third of the
screen), so the nameplate sits beside the log rather than on it.

Status lamp bindings (driven by `client.hq.HqWorldDriver`, applied on the integrated server only
when a state differs, re-applied every 2 s so rebuilt or newly placed lamps catch up; nothing
changes while the Foreman link is down): `agent:<id>` (the agent's status family, the same as its
nameplate: an idle/done agent with a decision waiting on you is `waiting`; off when off shift or
gone), `ci:<repoId>` or `ci:#<n>` (n-th repo in Foreman order; unused slots idle), `goal` and
`goal:atrium` (planning thinking, active working, done, failed error), `decisions` (waiting while
any decision is open), `merge` (waiting while a merge decision is open), `beacon` (the band of lamps
in the dome's lantern: the whole studio at a glance from outside, most urgent first: waiting on you,
error, working, thinking, done, idle; the BER adds a status-colour halo, breathing while waiting).
Waiting `decisions`/`merge` niches get a breathing clay pool of light on the floor in front (the
earlier glowing outline read as a debug box). Also driven: podium `open`
(any open decision), merge station `active` (an open merge decision), monitor `lit` (its agent is
on shift), and **signal bulbs**: vanilla copper bulbs within 3 blocks of the `decision_podium`
anchor or a `mergestation` slot are lit while that station needs you. Waiting `decisions`/`merge`
lamps also draw a breathing clay glow frame round their niche (BER), so "a decision is waiting"
reads from the entrance. Waiting lamps breathe and shed clay motes, an open podium sheds motes;
**none of that while the Foreman link is down** (the lamps hold their last state, quietly).

Build contract: everything inside the site box x -46..46, y 60..100, z -36..54 is planned in
memory (terrain heightmap first: the hills rise from the plain at the box edge, so the site meets
the world without a step) and applied as a diff (only differing cells are written, no neighbour
updates; connections of stairs/panes/fences/panels computed in a second pass; Foreman-driven
properties kept). Nothing outside the box is touched. **Player changes are kept**: the plan of the
last studio build is stored in the world folder (`agentcraft-hq-plan.dat`, palette + one index per
cell, ~30 KB compressed), and a cell that matches neither the old nor the new plan (the player built, broke
or replaced it since) is left alone; `/agentcraft hq force` resets those too. Another builder (`hq
test`) invalidates the stored plan. The command reports what it did, e.g. "62 blocks updated; kept
3 blocks you changed since the last build (/agentcraft hq force resets them)", "replaced 1331
blocks that were not part of the HQ (...)", "removed 164 dropped items" (item entities and orbs in
the box are cleared at the end of a build; players and agents are untouched). A rebuild of an
unchanged world writes 0 cells (~0.1 s including reading and writing the plan file); a fresh world ~0.6 s including chunk
generation.

QA hooks: `dev.state.hq` (layout, wanted lamp states, podium/merge flags, cells changed by the last
driver apply, `lastBuild` = the last build's report) and `dev.hq.check {minLight?}`: the agents' own
A* from `entrance` and `lounge` to every standing anchor (a seat is reached via its step-in cell),
`climbing` (routes that go over furniture: see "Known" below), spots that would stand on
furniture, shared slots closer than 1.6, and block light over every roofed walkable cell (last run:
68 routes, 0 unreachable, 0 misplaced, 0 crowded, light min 7 / mean 9.9 at feet height).

Known: `GridPathfinder` treats any block whose collision top is >= 0.9 (stairs chairs, table tops)
as floor, and a 1-block climb costs only +0.6, so a few routes between neighbouring armchairs or
meeting chairs step over a chair (`dev.hq.check.climbing`, 6 routes). That is the agents'
pathfinder (never stand on furniture / non-full tops); the HQ cannot hide it without invisible
barriers (which would block clicks on seated agents).

## Social Desk (`client.social`, `hq.SocialRoomBuilder`, `monitor.CalendarScreenDraw`)

The user posts on X and LinkedIn from the studio. Two social agents live in the **Social Room** (north of the
hall, see the HQ zone table): **Nova** plans and drafts posts, **Sable** publishes the approved ones through
the user's Chrome. The Foreman (`foreman/src/social.ts`) owns the state machine; the mod mirrors it:
`Protocol.SocialPost` (+ `Agent.team`, `Snapshot.socialPosts`), `ForemanState.socialPosts()` /
`socialPost(id)` (`social.post.upsert` replaces by id, `onSocialPost` fires), `Foreman.socialAction(postId,
action, at, text)` -> `social.post.action`.

- **Seating**: `Agent.team == "social"` -> `StationAssigner.stationKey` is always `desk` while the agent is on
  shift, whatever station the Foreman reports, so Nova and Sable sit at `desk_nova` / `desk_sable` in the
  Social Room; the six coding desks (`DESK_ORDER`) are untouched. Their desk monitors are bound to their ids and
  show their live logs and screens like any other desk. Both walk the same pathfinder (bounds now reach z=-23).
- **Talking**: `@nova ...` / `@sable ...` in the console (existing `user.message`); the console's
  `/posts`, `/approve <postId|n>` and `/calendar` (below).
- **Wall calendar** (`CalendarScreenDraw`, `MonitorScreen.Mode.CALENDAR`, binding `calendar`): "Social
  calendar - week of 13 Oct" with the counts ("3 drafts - 2 approved - 1 failed", each in its status colour),
  Mon..Sun columns (today highlighted, weekend dimmed), one chip per post (time, platform badge: X = black with
  a light rim, LinkedIn = blue "in", status word, first words). Status colours: draft grey, approved green,
  posting amber and pulsing, posted teal with a check, failed red with a cross. Overflow shows "+N more"; drafts
  without a time are counted in the footer. Cancelled posts are not shown. Redrawn every frame (the pulse).
  Right-click it -> `SocialCalendarScreen`.
- **Calendar screen** (`SocialCalendarScreen`, `dev.screen {open:"calendar"}`): left, the week as an agenda
  (a row per post; wheel scrolls; "No time yet" section) or the month as a grid (a dot per post, click a day to
  jump to its week); `<` `Today` `>` and `Week`/`Month` buttons (keys: Left/Right, T, M). Right, the selected
  post: platform, status, local time, author, `n / limit` characters (280 X, 3000 LinkedIn; red when over),
  the full wrapped text (scrolls), the error / the planner's note / the link / media paths, and the actions:
  **Approve** (primary, only draft/failed posts with a future time; the one approval, it goes out at that time
  without asking again), **Unapprove** (approved only), **Post now** and **Cancel post** (draft/approved/failed;
  both ask twice: the label becomes "Sure? ..." for 4 s), **-1h / +1h / +1d** (`schedule` with `at`; an approved
  post goes back to draft, as the Foreman says), **Edit text** (multi-line field; **Save** = `schedule` with the
  same future `at` plus `text`, which keeps it a draft, **Save & approve** = `approve` plus `text`, Esc/Discard
  leaves; both need a future time, so move it first). The Foreman applies `text` before whatever action it is
  sent with, which is why Save goes through `schedule`. Refusals ("... is in the past", "... is being published
  right now") show inline under the text. "Talk to Nova" / "Talk to Sable" open the console prefilled with
  `@nova ` / `@sable `.
- **Console**: `/posts` lists the open posts (number, id, day and time, platform, status, first words, soonest
  first), `/approve <postId|number>` approves one (number = its place in `/posts`), `/calendar` opens the screen.
- QA: rebuild the HQ (`/agentcraft hq`) in an existing world to get the room; `dev.foreman.inject` a
  `social.post.upsert` to fill the calendar; `dev.camera {anchor:"cam_social_calendar"}` for the wall.

## Content Room (`client.content`, `hq.ContentRoomBuilder`, `monitor.ContentBoardDraw`)

The YouTube channels the team runs. Four agents with `team == "content"` live in the **Content Room**: **Iris**
(showrunner) pitches videos and keeps the playbook, **Reel** (producer) studies references frame by frame and makes
the cut, **Vega** (analyst) reads the analytics and writes post-mortems, **Cleo** (publisher) uploads approved cuts
through the user's Chrome. The Foreman (`foreman/src/content.ts`) owns the state machine; the mod mirrors it:
`Protocol.ContentChannel` (with `playbook`), `ContentVideo`, `ContentRef`, `ContentStat`, `ContentChannelStats`
(+ `Agent.isContent()`, `Snapshot.contentChannels/contentVideos/contentRefs/contentStats/contentChannelStats`),
`ForemanState.contentChannels()` / `contentVideos(channelId)` / `contentRefs()` / `contentStat(youtubeVideoId)` /
`contentStatHistory(...)` / `contentChannelStats(channelId)`. Foreman -> mod: `content.channel.upsert`,
`content.video.upsert`, `content.ref.upsert` (replace by id), `content.stats` (per video: the history sent is
regrouped oldest first; channel stats replaced per channel); listener callbacks `onContentChannel`,
`onContentVideo`, `onContentRef`, `onContentStats`. Mod -> Foreman (`Foreman.contentChannelAction`,
`contentVideoAction`, `contentRefOpen`): `content.channel.action` {channelId?, action add|update|remove|
autopilot_on|autopilot_off|sync, name?, url?, niche?, brief?, postsPerWeek?}, `content.video.action` {videoId,
action approve|reject|comment|approve_cut|schedule|publish_now|unschedule|retry, text?, at?},
`content.ref.open` {refId, frame?}. Acks: the Foreman's refusal shows inline in the screen's footer.

- **Seating**: `StationAssigner.stationKey` treats `isSocial() || isContent()` as `desk`, so the four sit at their
  `desk_<id>` anchors in the Content Room whatever station the Foreman reports.
- **Talking**: `@iris ...` / `@reel ...` / `@vega ...` / `@cleo ...` work like any `@name` (routing is by agent
  name); the screen's footer and the pipeline's "Talk to <owner of this stage>" prefill the console. `/content`
  (alias `/youtube`) opens the screen.
- **Studio screen** (`ContentStudioScreen`, `dev.screen {open:"content"}`, lectern right-click, `/content`, the wall
  board): a flat dark window, one accent (ember orange). Header: tabs Pipeline / Analytics / References / Playbook
  (Pipeline shows how many videos wait for you). Strip: a pill per channel (green dot = analytics connected),
  **+ Channel** (modal form: name, URL or handle, niche, brief, posts a week), Autopilot switch, posts-a-week
  stepper, **Sync now**, **Remove** (asks twice). Footer: the Foreman's answer or error, "Talk to" the four agents.
  - **Pipeline**: the channel's videos in sections (Ideas, In production, Review, Scheduled, Published; failed
    publishes sit in Scheduled in red, rejected ones are hidden and counted); the picked one on the right (title,
    status, format, author, time, hook, pitch, script excerpt, studied references (click to jump to the
    References tab), files, post-mortems, comment thread). Actions by status: idea -> **Approve** / **Reject**;
    approved -> Reject; review -> **Approve cut** (time field: empty = next free slot, or `2026-10-20 18:00`,
    `18:00`, `+3h`, `+2d`), Reject, **Open cut** (OS default app); scheduled -> **Publish now** (asks twice),
    **Reschedule**, **Unschedule**; failed -> **Retry**; published -> **Open on YouTube**. The comment box sends
    `comment` (wakes the stage owner) and doubles as the reject reason.
  - **Analytics**: five big numbers (subscribers, views 28 d, watch time 28 d, subs gained 28 d, average view %,
    views-weighted), a 28-day views column chart (hover = day and count), the **retention curve** (21 points) and
    **views-over-time** line of the picked video, and a per-video table (title, published, views, avg view %, CTR,
    likes, change since the previous sync); click a header to sort (views, avg view %, CTR, newest), click a row to
    pick the video. With `channel.connected == false` a banner says "Analytics not connected: run `npm run
    youtube:connect` in foreman/", the OAuth-only numbers show "--" and the public ones (views, likes, subscribers)
    still show.
  - **References**: studied videos (title, channel, views, frames); for the picked one: stats, the contact sheets
    shown in the screen (`LocalImages`, from `<dir>/sheets/*.jpg`, `<` `>` to page), its `breakdown.md` and
    `transcript.txt` (read from disk, first 60 KB), **Sheets on the big screen** (`content.ref.open`) and a frame
    stepper + **Show frame** (`content.ref.open` with `frame`) for the Screening Room screen.
  - **Playbook**: the channel's `playbook` markdown (headings, bullets) with the latest post-mortems below it.
- **Wall board** (`ContentBoardDraw`, `MonitorScreen.Mode.CONTENT`, binding `content`): the channel's views in 28
  days as a hero number over a column chart, subscribers, watch time, "N waiting for you", and the pipeline in one
  line (ideas, in production, review, scheduled). Right-click -> the Studio.
- **Toasts**: a video reaching `review` ("ready for your review"), `published` ("is live") or `failed`.
- **State kept in the client**: the selected tab/channel/video/reference and the analytics sort live in
  `ContentState` (static, survive closing the screen).
- QA: rebuild the HQ (`/agentcraft hq`) in an existing world to get the room; `dev.foreman.inject` a
  `content.channel.upsert`, `content.video.upsert` and `content.stats` to fill the screen and the board;
  `dev.camera {anchor:"cam_content_board"}` for the wall.

## Blocks and block entities (`dev.agentcraft.block`)

All 16 blocks from the assets-src block contract (README "Block contract") are registered with
their properties, facing rule (front faces the placer), luminance and shapes, with block items in
the "AgentCraft Studio" creative tab (`dev.screen {open:"creative_agentcraft"}`). Render layers are
automatic in 26.x (each quad picks solid/cutout/translucent from its sprite).

Stations with dynamic content have a block entity (`block.entity.*BlockEntity`, all extend
`StationBlockEntity`): monitor, task_board, decision_podium, merge_station, status_lamp,
console_terminal, memory_archive. Each carries a **binding** string (saved + synced) that the HQ
builder sets: monitor = agent id, status_lamp = `agent:<id>` / `ci:<repoId>` / `goal`, memory_archive
= scope, others empty = "the default". Add your own fields to your station's BE class and call
`changed()` after changing them.

Connectable panels (`PanelBlock`: monitor, task_board): `up/down/left/right` connect same-facing
neighbours; `PanelBlock.origin(...)` / `extent(...)` give the bottom-left block and the size of the
whole surface. The BER base draws from the origin only.

**BER base** `client.world.StationRenderer<T, S extends StationRenderState>`: extract fills
`binding`, `facing`, `panelOrigin`, `panelWidth/Height`, `timeSeconds`, `foremanRevision`; override
`extractStation` (read the Foreman model there, never in submit) and `submit`.
A surface drawn only from its origin block disappears when that block is culled (off screen, or
behind the camera while the rest of the panel is in view): override
`shouldRenderOffScreen()` to return true (and `getViewDistance()` if needed) in renderers of
multi-block panels.
`StationRenderer.toFace(poseStack, facing, depth, pxPerBlock)` gives screen-pixel space on the block
front (x right, y down, origin top-left as seen by a viewer); depth of the north-facing model: monitor
screen `12/16`, task board linen `14/16` (minus a hair). `MonitorRenderer` uses it for its Phase 2
placeholder (agent name on the screen), which proves the transform.

World state driven by the Foreman (lamp `status`, podium `open`, merge station `active`, monitor
`lit`): change blocks on the integrated server with `client.world.ServerTasks.run(level -> ...)`
(singleplayer; only set a state when it differs). Clicks on stations: 
`StationInteractions.onUse(ModBlocks.CONSOLE_TERMINAL, (player, pos, state, be) -> open...)`
(client side, consumed, sneak-click still places blocks).

## Agents (`client.agents`)

Architecture A (decided by measurement, see DEV.md "Agents: client-side"): agents are client-only
`ClientAgentEntity` (extends `Avatar`, drawn by the vanilla player model with both skin layers and
slim/wide arms from cast.json). `AgentManager` keeps one per Foreman agent in sync every client tick:
spawn at the target anchor, walk on station changes (`GridPathfinder` A* over the real blocks +
string pulling, `AgentMotion` 2.9 blocks/s, smooth turns, exact arrival + facing), teleport when
there is no route, remove agents that leave the snapshot, keep them dimmed ("Foreman offline") while
the link is down, re-place everyone when the layout is republished. `StationAssigner` maps station ->
anchor slot (sticky, deterministic; off-shift -> lounge).
Agents advance once per client tick, from their entity tick or, when a culling mod skips it (Entity
Culling's `tickCulling`), from `AgentManager`'s catch-up (`TickGate`; DEV.md "Compatibility: entity
tick culling"). `AgentHooks` tickers therefore run even when the entity tick does not.

What an agent shows lives in its `AgentView` (`entity.view()`: name, colours, state, family,
activity, station, anchor, active, paused, stale, pose). Phase 3 hooks:

```java
AgentHooks.onTick(agent -> ...);                               // per tick, after movement: particles, look-at, idle anims
AgentHooks.onExtract((agent, state, pt) -> ...);               // adjust AgentRenderState (pose, head rotation, extra fields)
AgentHooks.onSubmit((state, poseStack, collector, camera) -> ...); // extra geometry at the entity origin (speech bubbles)
AgentsFeature.onClick((player, agent) -> ...);                  // right-click an agent (never sent to the server)
```

Poses: `AgentView.pose` is the coarse pose (WALK, SIT, LEAN, STAND); the detailed posture lives in
`AgentLife` (see "Agent life" below).

**Nameplates** (`Nameplate` + `PlateLayout`): an opaque kit pill with status dot + name (agent
colour) + activity, visible within 40 blocks (also with the HUD hidden), never overlapping another
plate on screen once settled:
- Two cached variants per agent: full, and a name-only pill. Low-value plates
  (`AgentView.plateWeight()` <= 2: idle, done, off shift, Foreman offline) collapse to the pill when
  their full plate would touch another plate; the plate under the crosshair always shows in full.
- `PlateLayout` (once per frame, Fabric `LevelExtractionEvents.END_EXTRACTION`) places plates in
  priority order (focused, waiting, error, working/thinking, then the low-value tier; nearest first
  within a tier) at the lowest lift above the head that is free, slides lifts smoothly, draws a
  leader line from a lifted plate to its head, and gives each plate a depth nudge by rank so that two
  plates that still overlap (mid-slide) never show each other's text. It writes `plate`,
  `plateLift`, `plateScale`, `plateNudge` into `AgentRenderState`.
- Plates keep their world size up to 12 blocks, then grow to 1.6x (mid-distance legibility).
- Anything that belongs with the plate (speech bubbles, a "!" above a waiting agent) should draw in
  plate space from an `AgentHooks.onSubmit` hook, above `Nameplate.top(state)`:
  `poseStack.pushPose(); Nameplate.plateSpace(state, poseStack, camera); ...; poseStack.popPose();`.
  A bubble taller than the plate should either be part of the plate's layout (extend `PlateLayout`
  so it reserves the space) or appear only for the focused/nearest agent, or it will cover plates
  stacked above.
- Draw such billboards opaque (`WorldUi.Layer.SOLID`); translucent billboards let other plates'
  text ghost through (DEV.md "Phase 2 gotchas").

`dev.agents {settle?}` lists positions/targets/paths and each laid-out plate (`plate{mode, lift,
rank, rect, ...}`); `settle:true` snaps walkers to their targets and plates to their final layout
before a shot. `dev.state.agents.plateOverlaps` counts overlapping plates in the last frame (0 when
settled).

### Agent life (Phase 3, `client.agents`: `AgentLife`, `AgentModel`, `AgentParticles`, `SpeechBubble`, `PlateStack`, `Seats`, `AgentCardScreen`)

Every `ClientAgentEntity` has an `AgentLife` (`entity.life()`), simulated per client tick
(deterministic per agent, seeded by its id) and interpolated per frame; nothing allocates per frame
except the submit nodes themselves.
- **Postures** (`AgentLife.posture()`), picked from state family + station + seat: seated typing
  (hands reach the desk top in front of the seat), seated / standing thinking (hand at the chin),
  reading an open vanilla book (library), standing typing (terminal, desk without a seat), leaning
  on the bench (test bench), reviewing (merge station), waiting (hands clasped, faces you),
  scratching its head (error/blocked), relaxing (done, in the lounge, not all at once), talking
  (hand gestures while its bubble shows), idle with occasional stretches (lounge). Walking uses the
  vanilla swing with eased starts and a slow last step; turning to the spot's yaw on arrival.
  `AgentModel` (a `PlayerModel`) applies the pose channels and a hip-pivot forward lean.
- **Seats come from the world at the anchor** (`Seats`): if the block at a station anchor's feet
  position (`desk_<id>`, a lounge sofa, a meeting chair; never `user`) is a bottom-half stairs block
  (its back facing away from the desk), a bottom slab, or any block whose collision top is 0.3-0.7
  high, the agent walks to a free neighbour cell, steps onto the seat and sits facing the anchor
  yaw (getting up first when it leaves). **HQ builders: put a seat block at each `desk_<id>`** (the
  test room uses dark oak stairs facing away from the desks), **or** publish the chair as
  `seat_<id>` (feet or seat-top position + facing yaw): it is used when the block right in front of
  the chair is the desk (so the typing hands reach it); a chair further back is ignored and the agent
  stands at `desk_<id>`. Without a seat the agent stands and types.
- **Head**: looks where it walks; at the monitor (`monitor_<id>`) when seated at its desk; at whoever
  it talks to (`agent.say.to`: an agent, or you for `user`) and at an agent talking to it; at you when
  you are within ~4 blocks (busy agents only glance up briefly, and never at a spectating camera);
  waiting agents face you within 24 blocks; idle glances at neighbours or around every few seconds.
- **Waiting on you**: a `waiting_user` agent walks to the `user` spot by the podium, or, when you are
  inside the HQ bounds and not spectating, to a free spot ~3.2 blocks from you (conversation
  distance: agent, plate and "!" fit on screen; several fan out) and waits there facing you; it
  follows when you move more than ~2.6 blocks.
- **Who needs you: one owner per decision** (`AgentManager.owner(state, decision)`): a merge belongs
  to the worker whose task it merges (the lead files it), a question or permission prompt to the
  agent that asked. The owner gets `AgentView.awaitingUser` / `awaitingDecision` (`awaitingCount`
  when it owns several) and the pulsing clay "!" (`AgentView.needsYou()`: owns an open decision or
  is `waiting_user`), so the number of "!" in the HQ is the number of decisions waiting on the user.
- **Status family** (`AgentView.family`): the family to show everywhere. An idle/done agent that
  owns an open decision is `waiting` (clay), e.g. the showcase's "t4 awaiting your merge"; an
  off-shift agent or any agent while the Foreman is offline is `idle`. **Lamps and monitors: read
  `entity.view().family`** (not `state.family()`) to agree with the nameplate. `liveFamily` is the
  family without the off-shift/offline override (for effects).
- **Paused** agents (by you) show a pause glyph instead of the dot, on the full plate and on the
  name-only pill, and keep their full plate ("paused · ...", weight 3) when plates compete.
- **Particles** (vanilla particle sprites tinted with the status palette): thinking = brass twinkles
  round the head, working = teal motes from the hands, error = red "steam" puffing out of both ears
  (a burst on entering, then a small puff every ~2-3 s), done = a confetti burst on task done (stays
  below the plate). Reactions fire only for transitions seen live (not when the Foreman reconnects
  or an agent comes back on shift).
- **Plate stack** (`PlateStack`, plate space): the speech bubble (kit `bubble`, 3 lines max, "@Name"
  addressee in that agent's paper colour, pops in, 3.5-11 s by length, fades out) and the pulsing
  clay "!" for agents that need you. `AgentRenderState.stackHeight/stackWidth` are reserved by
  `PlateLayout`, so neighbouring plates lift clear of a bubble; a speaking agent's plate gets weight 5.
- **Plate placement on screen** (`PlateLayout`): near plates stop growing at ~1.1x the GUI text size
  (they shrink in the world below ~6 blocks), a plate and its stack never go past the top edge of the
  screen (one that does not fit above may come down over its own head, nudged in front of it), a
  plate that cannot find a free spot on screen overlaps cleanly by rank instead of flying off screen,
  a slide never runs through a plate placed before it (it jumps), and leader lines pass behind other
  plates and bubbles (gaps cut where they cross: `AgentRenderState.leaderGaps`).
- **Agent card** (`AgentCardScreen`, right-click an agent): name/title/role, state, activity, task,
  **the decision it owns with a way to act on it**, decisions it filed that wait on you through
  another agent ("Filed d3 for you: merge of t4 (Wren's work)"), the last log lines, Message /
  Pause|Resume / Stop|Spawn. The decision block opens the decision's review screen when one is
  registered (**decisions / diff / permissions features: `AgentsFeature.registerDecisionScreen(kind,
  (mc, decision) -> screen)`**; without it the no-argument DevBridge screen `"diff"` / `"permission"` /
  `"decision"` is used when it would show exactly this decision, i.e. it is the oldest open one),
  otherwise it lists the decision's options as rows: press a row (click or 1-4) twice to answer;
  "Request changes" asks for the feedback text. Message opens the screen registered as `"console"`
  and types `@<id> ` into it (`AgentCardScreen.openConsole(mc, prefill)`; the console must accept
  `charTyped` right after `setScreen`, like `dev.type`); without a console it opens a message line in
  the card: a vanilla `EditBox` in kit style (focus starts SDL text input, so a real keyboard types
  into it; scrolls to the caret, clipboard, selection). Offline / off shift: no brass frame, no pulse,
  buttons disabled, "Foreman offline: read only". DevBridge screen `"agent"` (last clicked agent, else
  whoever needs you) and `dev.agents.card {agent}`.
- QA: `dev.agents.look {agent?}` (posture, seat, sit, head yaw/pitch, bubble, particles, family,
  needsYou, awaitingDecision; top level `exclaims`, the open card's `input`, `textInputActive`),
  `dev.agents.fx {agent, fx: confetti|puff|sparkle|say, text?, to?}` (preview an effect),
  `dev.state.agents.exclaims` / `plateOverlapPairs`, with `AGENTCRAFT_DEV_TEST=1` also
  `dev.agents.keys {keys}` (queues SDL key events for the game window: Minecraft's real keyboard
  path, text only while SDL text input is on, unlike `dev.type`; `tools/agents-typing.mjs` uses it), `tools/scenes/agents.json` (busy showcase) and
  `tools/scenes/agents-late.json` (late showcase) in the test room, `tools/agents-live.mjs` (live sim
  observer: shots + per-agent log, optional auto-answer).

## Hand-over documents (`client.agents.DocHandover`, `client.library.DocScreen`)

An agent that wrote a markdown file hands it over in person (`doc.upsert`, `snapshot.docs`; state in `ForemanState.docs()`).
- **Comes to you:** for 60 s after the hand-over the agent leaves its station, walks to the player (same spot logic as a `waiting_user` agent, about 3 blocks away, facing you), holds an open book out (`Posture.OFFER`; the book is also carried while walking) and says its `note` (or "I have something for you: <title>") in a speech bubble, repeated every ~13 s. Outside the HQ it waits at the Decision Podium. After the window it walks back, but its plate keeps "Right-click to read" and the "!" until the doc is read.
- **Read it:** right-click the agent opens `DocScreen` (paper sheet: title, "from <agent>", file name, the note as a quote, markdown via `MdLayout`; wheel/Up/Down/j k/PgUp/PgDn/Space/Home/End, `c` copies the markdown, Esc closes). Opening plays the page-turn sound, marks it read locally and sends `doc.read`. Shift+right-click still opens the agent card.
- **Find it again:** `/docs` lists handed-over docs (newest first, `*` = unread), `/docs <n|id>` opens one. A toast announces a new hand-over.
- QA: `dev.screen {open:"doc"}` opens the newest doc.

## UI kit (`client.ui`)

```java
UiStyle.color("paper.text"); UiStyle.color("monitor.diff_add"); UiStyle.status("waiting");
UiStyle.agentOnLight("kit"); UiStyle.agentOnDark("kit"); UiStyle.metric("metrics.gui_panel_padding", 8);
Kit.PANEL_PAPER, Kit.FRAME_BRASS, Kit.button(primary, "hover"), Kit.dot("working", halo), Kit.card("doing"),
Kit.progressRing(p), Kit.icon("edit"), Kit.padding("panel_paper")   // content padding from kit.json
Panels.panel(g, x, y, w, h); Panels.header(g, font, "Title", x, y, w); Panels.button(g, font, "Merge", x, y, w, true, hovered, false);
Panels.progress(g, x, y, w, 0.4, "teal"); Panels.pill(g, font, "@kit", x, y, color); Panels.scrollbar(g, x, y, h, scroll, hovered);
TextUtil.ellipsize(font, s, px); TextUtil.wrap(font, s, px); new TextUtil.Scroll().update(rows, viewRows).scrollBy(n);
WorldUi.billboard(poseStack, camera, x, y, z);                       // in-world pixel space facing the camera
WorldUi.billboard(poseStack, camera, x, y, z, scale, nudge, ox, oy, oz); // + size factor + depth nudge (see javadoc)
WorldUi.submitNineSlice(ps, c, WorldUi.Layer.SOLID, Kit.BUBBLE, ...);  // opaque plate: use for billboards
WorldUi.submitNineSlice(...); WorldUi.submitSprite(...); WorldUi.submitText(...); WorldUi.submitFill(...);
```

World UI layers: `SOLID` (opaque, depth-correct, overlap-safe: billboards, bubbles, labels),
`BASE` (translucent, the sprite's own alpha: only on block faces nothing else overlaps), `OVERLAY`
(icons/dots on top of a plate). Opaque text renders in the solid pass, so a translucent plate can
never hide text behind it.

Screens: paper panels, ink text, no shadows, no vanilla grey GUI (docs/visual-bar.md). Never
hard-code colours; ask `UiStyle`. Sprites are 1 texel = 1 GUI px (GUI scale 3 at 1080p).

## HUD (`client.hud`)

`HudElementRegistry.addLast(AgentCraft.id("hud/<name>"), element)`. Phase 2 ships the connection
banner (top right: "Foreman · sim" / "Reconnecting to the Foreman" / "Foreman not running"; loud
paper banner at the top centre when claude auth failed). Phase 3: goal boss bar, toasts for `notify`.
**AUTO pill**: while `foreman.status.autoApprove` is true a clay "AUTO" chip with a pulsing dot sits
left of the Foreman pill (agents run tool calls without asking; denied things like git push stay
denied). Toggle from the console: `/auto` (toggle), `/auto on`, `/auto off` -> `settings.set`, the
Foreman re-broadcasts `foreman.status`, the chip follows; `/status` and `/help` mention it.
`dev.hud.state` reports `autoApprove`. No in-world lever yet (the console command is the toggle).

## Displays (`client.monitor`, `client.taskwall`)

**Monitors** (`MonitorFeature`, `MonitorRenderer`, layout in `MonitorScreen` + `LogRows`): every
connected monitor panel streams its agent's `agent.log` from the origin block: header (state dot,
name in `text_on_dark`, activity, task id), log rows newest at the bottom (prose wraps to 4 lines
without markdown markup, tool lines = kit icon + tool name + argument, results/errors their first
lines, diffs = path + tinted +/- rows, dedented, blank lines left out), the top row fading, new rows
sliding in (260 ms) under the header band, a blinking caret while the agent works or thinks, a
pulsing "Waiting for you" ("Needs you" on narrow screens) when it waits.
**Live screen** (`ScreenFrames`, `ScreenImages`, `MonitorViewScreen`): when an agent's tool returns a
screenshot (Claude in Chrome, Roblox Studio, Blender...) the Foreman sends `agent.screen`
(base64 PNG/JPEG, <= ~3 MB, one frame per 1.5 s per agent; the latest fresh one is also in
`snapshot.screens`). A background thread decodes it with ImageIO and area-averages it to two
textures per agent (384x240 for the world, 1280x800 for the GUI); the render thread only swaps the
pixels in (same size) or re-registers the texture (new size), so nothing leaks (stable ids
`agentcraft:dynamic/screen/<agent>/t|b`). While a frame is < 90 s old the agent's monitor shows it
scaled to fit under the header with a chip ("LIVE . browser", or "browser . 42s ago" after 15 s) and,
on panels with >= 110 px of body (3x2 desk monitors), a two-row log strip below; 1-block screens show
the image only. Stale frame -> the normal log view. Right-click a monitor opens `MonitorViewScreen`
(the big view: the frame as large as the window allows, source, age, last 3 log lines; QA:
`dev.screen {open:"monitor"}`). WebP and other formats ImageIO cannot read are ignored (log view stays).

**Blackjack** (`client/casino/`: `Blackjack` rules, `TableView` + `WorldPaint` world drawing, `CasinoSession`
seat/keys/dealer pacing, `SeatHud`, `CasinoFeature`, `BlackjackScreen` big view, `CardArt`, `BlackjackSave`): a
client-only minigame for the Casino Lounge, no money and no Foreman, played AT the table in the world.
Right-click the table (any block of its 5 x 3 footprint, found by position via `CasinoLayout.isTable`) or the
dealer (a villager named `Dealer` near its spot) to sit down; no GUI opens. Sneak-right-click still builds.
Rules: 6-deck shoe reshuffled before a deal once fewer than 25% of the cards are left; aces soft; the dealer
hits 16 and below (soft 16 too) and stands on every 17 (soft too) and peeks for blackjack; blackjack pays 3:2,
wins 1:1, push returns the bet; Hit, Stand, Double (first two cards of a hand), Split (once, equal-value pair,
split aces get one card and stand; 21 after a split is not a blackjack). No insurance or surrender.
Chips start at 1000, "Rebuy 1000" when broke. Balance and stats (hands, won, lost, biggest win) persist in
`config/agentcraft-blackjack.json` (written on every settle and when leaving; chips on the table of a round in
progress count as still in the pocket). The game object lives for the session, so leaving mid-hand only pauses it.

*Seated play.* While seated (within 5 blocks of the table, no screen open) the keys work without any screen;
they are KeyMappings in the "AgentCraft Blackjack" category and are only consumed while seated: Enter deal /
deal again, H hit, J stand, K double, U split, ] / [ raise / lower the bet by 10 (hold Shift for 50),
Backspace clear the bet, R rebuy, V big view (`BlackjackScreen`, the old full-screen table, still available and
shootable as `dev.screen {open:"blackjack"}`), X leave the seat. (S and D clash with walking and Space with
jumping, hence the different defaults; all rebindable.) Walking more than 5 blocks away stands you up too.
`SeatHud` is a strip above the hotbar with chips, bet, your and the dealer's visible totals, the result line
and the keys that work in the current phase.

*In the world* (`TableView`, a `LevelRenderEvents.COLLECT_SUBMITS` listener; cards, chips and boxes are
coloured quads and text on a pixel plane laid flat on the felt, 0.3 x 0.42 block cards, suits from pixel
bitmaps, no textures): the dealer's cards on the north row (hole card face down until revealed), your hand(s)
on the south row (two rows of a split side by side), a chip stack in the middle (100 black, 50 teal, 10 clay),
camera-facing totals ("Soft 17", "Bust 24", "Blackjack") that count only cards that have landed, a "Bet n"
tag, the dealer's speech line above its head, a shoe and a discard tray on the rims.

*Animation* is purely visual and driven by timestamps taken when `TableView` first sees a change in the
`Blackjack` state (the rules know nothing about timing; the only addition is a round counter): each card flies
from the shoe in an arc (0.35 s, eased, turning face up mid-flight; the hole card stays face down) in deal order
(player, dealer, player, dealer-hole) 0.3 s apart; the hole card does a real 3D flip about its long axis when
the dealer plays; the dealer draws a card every 0.45 s (paced by `CasinoSession`'s tick); chips drop onto the
stack with a bounce; on a win payout chips slide in from the dealer and then both stacks are swept to the player,
on a loss the bet slides to the dealer, on a push the stack hops; the result ("Blackjack! +150", "Bust -50",
"Push") rises and fades over the player's cards after the last card has landed, with happy-villager particles on
a win (plus fireworks on blackjack); when the next hand starts or the bet is changed the old cards are swept to
the tray and shrink away before the new deal. The dealer villager turns its head to the player and nods when a
card leaves the shoe (client-side rotation each frame). The strip's totals and the labels never show a card
before it has landed.
QA: `dev.screen {open:"blackjack"}` for the big view; rebuild the HQ (`/agentcraft hq`) in an existing world to
get the smaller table. `Blackjack` has no Minecraft imports; the mod has no JUnit setup.

**Media gallery + Screening Room screen** (`MediaStore`, `MediaScreenDraw`, `MediaViewScreen`): when an
agent hands the user an image or video (show_media) the Foreman sends `media.add` (and `snapshot.media`,
<= 8, newest last); `ForemanState` keeps only the metadata (<= 12 items) and fires `onMedia` /
`onMediaSnapshot` with the frames, which `MediaStore` unpacks on a background thread into encoded
bytes (~50 KB a frame). Each item owns ONE dynamic texture (`agentcraft:dynamic/media/<id>`, <= 1280x800):
the frame that is due (video: playhead x `fps`, looping; a clip has a single playhead, so the world
screen and the GUI play/pause together) is decoded off-thread by `ScreenImages` and swapped in on the
render thread, so a 150-frame video costs a few MB, never 150 textures. The monitor bound `media`
(the big Screening Room screen) shows the newest item scaled to fit with a caption bar (agent name,
caption or file name, "image" / "video" + length, age; "paused" when paused) and a "right-click"
hint; empty gallery: "No media yet / Agents hand you images and videos with show_media". Right-click it
-> `MediaViewScreen`: the item as large as the window allows, `Left`/`Right` browse (also the Prev/Next
buttons, Home = oldest, End = newest), `Space` play/pause, `O` or the "Open in player" button sends
`media.open` (the Foreman opens the original file in the OS default app, so videos play with sound).
QA: `dev.screen {open:"media"}`; `dev.foreman.inject` with a `media.add` fills the gallery. The media
screen is always lit (`HqWorldDriver`). Rebuild the HQ (`/agentcraft hq`) in an existing world to get
the room.

States: off shift (centred "Off shift"), unknown agent, no Foreman yet (the agent's name stays in
the header, centre "Foreman not running", the HUD pill's wording, `DisplayText`), link lost (last
known log dimmed + "Foreman offline" badge, "Offline" on 1-block screens), `lit=false` (smoked glass
from the model + a quiet "off shift"/"screen off" label in room light).
- **Look: warm charcoal glass**, chosen over the paper take by a side-by-side test in game
  (`dev.displays {look:"split"}` alternates them): lit paper screens read as framed notes at mid
  distance, the glass reads as a screen. Tokens `monitor_dark.*` in ui-style.json; the block
  texture `monitor_screen_on` matches (the BER draws the same glass plus a soft top glow).
- **Binding**: agent id; empty = the agent whose `monitor_<id>` anchor lies on the panel, else
  `feed` (team activity feed with the goal + progress; also selectable explicitly).
- **Size**: 128 px/block on 1-block-high (or wide) panels (about 12 characters x 7 rows on a 1x1),
  96 px/block from 2x2 up (a 3x2 desk monitor holds ~40 characters x 17 rows). Side padding is
  0.09 block (12 / 9 px): the brass lip stands 1/16 block in front of the glass, and closer text
  hides behind it at oblique views. Long snake_case tool names shorten on narrow screens
  (`request_merge` -> `merge`) so the argument still shows. **HQ builders: prefer 2x1 or larger desk
  monitors** (3x2 checked in the display bay, see below), clear of the standing agent's head and
  nameplate from the desk camera (in the test room the agent stands in front of its 1x1 monitor).
- `lit` is driven by the HQ client feature (see `HqClientFeature`); the renderer handles both.

**Task Wall** (`TaskWallFeature`, `TaskBoardRenderer`, model in `TaskBoard`, `TaskScreen`): a
kanban of `Foreman.state().tasks()` on every connected task_board panel: Todo / Doing / Review /
Done with counts (red dot when a column holds blocked cards), blocked tasks as red `card_blocked`
cards at the top of the column where they stalled (Doing with a worktree, else Todo), cancelled
hidden.
- **Layout** (`TaskBoard`, re-planned only when tasks or the panel size change; agent changes only
  refresh card contents): column widths follow the content (an empty column is a slim lane with its
  header; a crowded column borrows width from one with slack, a small hill-climb over a card
  score); cards are sized to their title: **full** (title 1-3 lines, face + name, one footer hint),
  **brief** (title, 2 lines) and **compact** (1 line). A column steps down through them (top cards
  keep the most room) before the tail goes behind "+N more"; wide columns put 2 cards per row when
  that shows more. Brief/compact cards keep the face only in Doing/Review/blocked.
- **Cards**: the assignee's live state dot only while that agent works on this very card
  (`agent.taskId == task.id`, so a busy agent's queued Todo card shows no dot); footer hint =
  blocked reason / CI failing / `after t2, t3` / CI running / P2 / the id; when the name and the hint
  do not both fit, the name goes first (the face still says who).
- **Motion**: a status change flies the card (lifted, shadow) to its new slot and glows it in the
  new column's colour (lifted towards cream) for 1.4 s once it lands; neighbours and columns glide
  to their new places; new cards pop in. While a card's size animates it keeps drawing the content
  that fits (the new one as soon as it is no wider than the card; title lines that would meet the
  footer drop out), so no frame shows overlapping text.
- **Density**: 64 px/block up to 3 blocks high, 72 on a 4-high wall (chosen side by side on a 7x4
  wall from the HQ camera distance: 64 drops names, 80 leaves the board half empty), then ~320 px
  of board height. Cards take the room light with a block-light floor of 10 (12 looked backlit at
  night). The board surface is a walnut pinboard (`task_board_surface`).
- **Clicks**: right-click a card -> `TaskScreen` (title one step larger, status, assignee + live
  activity and, while they work on this task, their newest log line (tool icon + call, errors in
  red) with the status dots pulsing, description, deps with their status, CI, priority, branch, blocked reason, summary;
  Retry / Prioritize / Reassign (worker chips) / Cancel via `Foreman.taskAction`, the Foreman's
  answer shown in place; one clay button at a time (Cancel turns into the only primary Confirm,
  with a prompt, and lapses after 5 s); the panel's top stays put when chips or the feedback line
  appear; left/right browse the wall's tasks). The "+N more" chip opens the first hidden task.
  With the HUD on, the card under the crosshair gets a brass outline and vanilla's black block box
  is suppressed on it (`LevelRenderEvents.BEFORE_BLOCK_OUTLINE`).
- Empty board: "No tasks yet / Press [key] and type a goal", the key read from the console's
  KeyMapping (`key.agentcraft.console*`, backtick when none is registered yet).
- Console goals: a repo named in the text (`moonbase: make it moodier`, or anywhere as a whole word, by name or
  home-relative path like `Desktop/side-project`) sends the goal straight to it (`RepoRoute`, a port of
  `foreman/src/reporoute.ts`). Otherwise, with several repos, a filterable list opens: type to filter, ↑↓/Tab move,
  Enter or a click picks, Esc goes back; it starts at the repo most recently used for a goal.
- New repo from the game: in that list, type a name no connected repo has and the last row becomes
  "+ New repo “name”" (Enter creates `~/name`, a git repo with a README and a first commit on main, and starts the
  goal there; `Desktop/name` makes it in a sub folder). Or `/repo new <name> [--auto]` (--auto also turns AUTO on).
  Sends `repo.create`; the Foreman's `--new-repo-dir` changes where repos are made (default: home).
- Dev: `dev.screen {open:"task"}` (last opened task, else the first doing one),
  `dev.taskwall {open?: id, press?: button, aim?: id, lightFloor?, ppb?, relayout?}` (boards with
  column widths and cards with size/lines/dot; press a screen button: prev next retry prioritize
  reassign cancel `to:<agent>`; `aim` = world point of a card for a real `key.use` click test;
  `lightFloor`/`ppb` override for A/B tests, 0 = auto), `dev.displays {look?, reset?}` (screens +
  CPU cost per frame).
- Shots: `tools/scenes/displays.json` (test room: wall, monitors incl. an oblique view, task
  screens incl. the cancel confirmation, crosshair hover, night) and `tools/scenes/displays_bay.json`
  (builds a bay far from any HQ, around x=1000 z=1000, with the HQ's sizes: a 7x4 east-facing wall
  and three 3x2 monitors, framed like the HQ cameras; its setup clears that box), `node tools/shoot.mjs <scene> --port <dev> --foreman <port>`.

Drawing helpers shared by both (`client.monitor.DisplayDraw`): opaque flat rects/gradients in one
custom-geometry node per screen (a 4x4 white `DynamicTexture` on a no-blend copy of the world text
pipeline), z-aware nine-slice with the kit card's translucent shadow row trimmed, opaque portrait
quads, cached dot sprite ids. Measured in the live sim at speed 4 with the test room (9 monitor
panels, 3 boards) and the display bay (3 monitors, 1 board) loaded: ~42 us/frame for all monitors
and ~42 us/frame for all boards, extract + submit, rebuilds included; a full board re-plan costs
0.1-0.35 ms (warm).

## Making things shootable (QA)

- Screens: `DevBridge.registerScreen("console", mc -> new ConsoleScreen())` in your `init()`, then
  `dev.screen {open:"console"}` (or a scene shot with `"screen":"console"`). Names in use or reserved:
  `console`, `diff`, `decision`, `library`, `permission`, `task`, `creative_agentcraft`.
- Cameras: add `cam_<name>` anchors in the HQ builder and shoot with `dev.camera {anchor}`.
- State: `dev.state` has `foreman` (link, backend, auth, counts, goal) and `agents` (count, moving);
  `dev.foreman` (+ `reconnect:true`), `dev.foreman.send {message:{type,...}}` (drive the Foreman
  through the mod's own link, e.g. submit a goal or answer `d3`), `dev.agents {settle}`,
  `dev.anchors`. With `AGENTCRAFT_DEV_TEST=1`: `dev.test.foremanMessage {message}` applies a message
  as if the Foreman sent it (e.g. `foreman.status` with `auth:"failed"`).
- Foreman for QA (always a project-local home, your own port and **your own profile name**, since
  profiles are locked per running Foreman):
  `npm run start -- --backend sim --profile <you>-showcase --reset --showcase --home C:\Projects\agentcraft\.agentcraft-home --port <p> --no-notify`
  then launch the game with `AGENTCRAFT_PORT=<p>`. The sim's repo id is `sim-demo-<profile>` cut to
  24 characters (`--profile verify2-core-showcase` gives `sim-demo-verify2-core-sh`), so read it from
  the decision (`decision.repoId()`) or `s.repos()` instead of building it (decision `d3` = merge of
  `wren-t4`). The live sim (`--reset --speed 4`, no `--showcase`) starts
  on the first `goal.submit`.

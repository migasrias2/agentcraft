// The scripted sim scenario: "add #tags to pocket-notes". A linear list of beats; the sim
// persists the index of the next beat, so a Foreman restart resumes where it left off (beats are
// idempotent). Several agents act inside a beat, so the world shows parallel work.
//
// Exercises: lead planning + shared memory, ~9 tasks with deps, real worktrees and edits,
// agent<->agent messages, a permission decision, a question decision, CI fail -> fix -> pass,
// a blocked task (npm publish needs the user) with a blocked agent, an agent error + recovery
// (API overload), reviews, five merge decisions with real diffs, a cancelled task, QA on the
// merged main branch, and a final question that decides how the goal ends. Across the run every
// agent state and station appears; the two checkpoints hold static states for screenshots:
//   showcase       busy mid-run: waiting_user, editing, testing, idle, reading, thinking
//   showcase-late  late run:     thinking, running, done, blocked, error, editing
import type { ShowcaseCheckpoint } from '../../config.js';
import { PERMISSION_OPTIONS } from '../../protocol.js';
import type { SimDirector } from './director.js';
import * as E from './edits.js';
import { userName } from '../../user.js';

export interface Beat {
  name: string;
  /** after this beat, `--showcase` (or `--showcase late`) holds a static state */
  checkpoint?: ShowcaseCheckpoint;
  run(d: SimDirector): Promise<void>;
}

export const DEFAULT_SIM_GOAL = 'Add #tags to pocket-notes: parse them, filter with `notes list --tag`, and show a `notes tags` summary';

const Q1_OPTIONS = ['Only open notes (recommended)', 'Include completed notes'];
export const q2Options = (id: string) => [`Close ${id} - I'll publish 0.3.0 myself (recommended)`, `Keep ${id} on the wall for later`];

function q1IncludesDone(d: SimDirector): boolean {
  const id = d.vars['dec:q1'];
  const dec = typeof id === 'string' ? d.fm.decisions.get(id) : undefined;
  return dec?.answer?.option === Q1_OPTIONS[1];
}

export const BEATS: Beat[] = [
  {
    name: 'kickoff',
    async run(d) {
      for (const a of d.fm.agents().filter((x) => !d.fm.isDesk(x.id))) d.fm.setAgent(a.id, { active: true, paused: false });
      const goal = d.fm.goal(d.goalId)!;
      d.act('marlow', 'thinking', 'meeting', 'reading the goal');
      d.log('marlow', 'text', `New goal from ${userName()}: ${goal.text}`);
      for (const w of ['juniper', 'kit', 'wren', 'rowan', 'tove']) d.act(w, 'idle', 'meeting', 'listening to Marlow');
      await d.sleep(1200);
      d.say('marlow', 'all', `Morning, team. New goal from ${userName()}: ${goal.text}. Give me a minute with the repo.`);
      await d.sleep(1500);
      for (const w of ['juniper', 'kit', 'wren', 'rowan', 'tove']) d.act(w, 'idle', 'lounge', 'waiting for the plan');
    },
  },
  {
    name: 'explore',
    async run(d) {
      const root = d.repoPath;
      await d.read('marlow', root, 'README.md');
      await d.read('marlow', root, 'package.json');
      await d.glob('marlow', root, 'src/**/*.ts');
      await d.read('marlow', root, 'src/cli.ts', 1300);
      await d.read('marlow', root, 'src/notes.ts');
      await d.grep('marlow', root, '^export function');
      await d.read('marlow', root, 'test/cli.test.ts');
      const ok = await d.runTests('marlow');
      d.log('marlow', 'text', ok ? 'Baseline is green. No tag support anywhere yet; notes are plain text, so tags can be derived on read (no storage migration).' : 'Baseline tests fail - noting it in the plan.');
      await d.sleep(800);
    },
  },
  {
    name: 'plan',
    async run(d) {
      await d.think('marlow', 'Splitting the goal into tasks with dependencies', 'desk', 1600);
      const t1 = d.ensureTask('t1', { title: 'Explore repo & draft plan', createdBy: 'marlow', assignee: 'marlow' });
      d.setTask('t1', 'doing');
      const t2 = d.ensureTask('t2', { title: 'Tag parser module (src/tags.ts)', description: 'parseTags/hasTag/normalizeTag: lowercase, de-duplicated, "-" and "_" allowed, "issue#12" is not a tag. Unit tests.', deps: ['t1'], assignee: 'kit', createdBy: 'marlow', priority: 2 });
      d.log('marlow', 'tool', `create_task "${t2.title}"`);
      await d.sleep(400);
      const t3 = d.ensureTask('t3', { title: '`notes list --tag` + `notes tags`', description: 'Filter list by tag; new `tags` command with usage counts. Uses src/tags.ts.', deps: ['t2'], assignee: 'juniper', createdBy: 'marlow', priority: 1 });
      d.log('marlow', 'tool', `create_task "${t3.title}"`);
      await d.sleep(400);
      const t4 = d.ensureTask('t4', { title: 'Highlight #tags in list output', description: 'Cyan tags when printing to a terminal; formatNote/formatList take {color}. No new dependencies.', deps: ['t1'], assignee: 'wren', createdBy: 'marlow', priority: 1 });
      d.log('marlow', 'tool', `create_task "${t4.title}"`);
      await d.sleep(400);
      const t5 = d.ensureTask('t5', { title: 'Tag edge cases: unicode & punctuation', description: 'Tests + fixes for #café, #crème-brûlée, trailing punctuation.', deps: ['t2'], assignee: 'kit', createdBy: 'marlow' });
      d.log('marlow', 'tool', `create_task "${t5.title}"`);
      await d.sleep(400);
      const t6 = d.ensureTask('t6', { title: 'README + help text for tags', description: 'Document tags; update `notes help`; enable colors on a TTY.', deps: ['t3', 't4'], assignee: 'tove', createdBy: 'marlow' });
      d.log('marlow', 'tool', `create_task "${t6.title}"`);
      await d.sleep(400);
      const t7 = d.ensureTask('t7', { title: 'Publish 0.3.0 to npm', description: `Needs ${userName()}: agents never publish or push.`, deps: ['t6'], createdBy: 'marlow', priority: -1 });
      d.setTask('t7', 'blocked', { reason: `needs ${userName()}'s npm credentials - agents never publish` });
      d.log('marlow', 'tool', `create_task "${t7.title}" (blocked)`);
      await d.sleep(400);
      const t8 = d.ensureTask('t8', { title: 'QA: full test run + CLI smoke on main', description: 'After merges: npm test on main, smoke the CLI with tagged notes, draft release notes.', deps: ['t5', 't6'], assignee: 'tove', createdBy: 'marlow' });
      d.log('marlow', 'tool', `create_task "${t8.title}"`);
      await d.sleep(400);
      const t9 = d.ensureTask('t9', { title: 'Stretch: shell completion for #tags', deps: ['t3'], assignee: 'rowan', createdBy: 'marlow', priority: -2 });
      d.log('marlow', 'tool', `create_task "${t9.title}"`);
      await d.sleep(500);
      const goal = d.fm.goal(d.goalId)!;
      d.memory(
        'marlow',
        'shared',
        'Plan: #tags for pocket-notes',
        `# Plan: #tags for pocket-notes

**Goal:** ${goal.text}

## Approach
- Tags live in the note text (\`#work\`), so no storage migration: derive them on read.
- One parser (\`src/tags.ts\`) used everywhere: lowercase, de-duplicated, \`-\`/\`_\` allowed, \`issue#12\` is not a tag.
- CLI: \`notes list --tag <t>\` filter + \`notes tags\` summary.
- Output: tags in cyan when stdout is a terminal (respect NO_COLOR).

## Tasks
- ${t1.id} Explore repo & draft plan - Marlow
- ${t2.id} Tag parser module - Kit
- ${t3.id} \`list --tag\` + \`tags\` command - Juniper (after ${t2.id})
- ${t4.id} Highlight tags in output - Wren
- ${t5.id} Unicode & punctuation edge cases - Kit (after ${t2.id})
- ${t6.id} README + help text - Tove (after ${t3.id}, ${t4.id})
- ${t7.id} Publish to npm - **blocked: needs ${userName()}**
- ${t8.id} QA on main - Tove (after ${t5.id}, ${t6.id})
- ${t9.id} Stretch: shell completion - Rowan

## Review
Rowan reviews every branch; then it goes to ${userName()} as a merge decision. Nothing merges without ${userName()}.
`,
        'replace',
        'plan',
      );
      await d.sleep(600);
      d.memory(
        'marlow',
        'shared',
        'Repo conventions',
        `# Repo conventions (pocket-notes)

- Node >= 22.18 runs the TypeScript sources directly (type stripping): no build step.
- Tests: \`npm test\` (node:test), files in \`test/*.test.ts\`. Keep the suite green before review.
- Zero runtime dependencies - keep it that way.
- Pure functions in \`src/notes.ts\`; I/O only in \`src/cli.ts\` and \`src/store.ts\`.
`,
        'replace',
        'conventions',
      );
      d.doneNoCode('t1', 'Plan written to shared memory (Plan: #tags for pocket-notes).');
      d.fm.setGoal(d.goalId, { status: 'active' });
      d.fm.bus.feed('plan', `Marlow planned ${goal.text.slice(0, 60)}... into 9 tasks`, { agentId: 'marlow' });
      await d.sleep(500);
      d.act('marlow', 'idle', 'meeting', 'briefing the team');
      d.say('marlow', 'all', `Plan is in shared memory. Kit: tag parser first (${t2.id}). Wren: tag highlighting (${t4.id}). Juniper: the CLI once Kit's parser lands. Tove: docs + QA. Rowan: reviews every branch.`);
      await d.sleep(1500);
    },
  },
  {
    name: 'wave1-start',
    async run(d) {
      const kit = await d.startTask('kit', 't2');
      const wren = await d.startTask('wren', 't4');
      await d.read('kit', kit.path, 'src/notes.ts', 800);
      await d.read('wren', wren.path, 'src/format.ts', 800);
      await d.read('juniper', d.repoPath, 'src/cli.ts', 900);
      d.log('juniper', 'text', `Studying run()'s switch so --tag slots in cleanly once ${d.task('t2').id} lands.`);
      await d.read('kit', kit.path, 'test/notes.test.ts', 700);
      await d.read('wren', wren.path, 'test/format.test.ts', 700);
      await d.read('tove', d.repoPath, 'README.md', 800);
      d.act('rowan', 'idle', 'lounge', 'waiting for something to review');
      d.act('juniper', 'reading', 'library', `waiting on ${d.task('t2').id}`);
    },
  },
  {
    name: 'kit-writes-parser',
    async run(d) {
      const kit = d.wt('t2');
      await d.think('kit', 'Writing the tests first, then the parser', 'desk', 900);
      await d.patch('kit', kit.path, [{ file: 'test/tags.test.ts', create: E.TAGS_TEST_V1 }, { file: 'src/tags.ts', create: E.TAGS_V1 }]);
      await d.think('wren', 'Colors: a tiny dependency or plain ANSI escapes?', 'desk', 1000);
      d.act('wren', 'running', 'terminal', '$ npm install chalk@5');
      d.log('wren', 'tool', '$ npm install chalk@5');
      d.log('wren', 'text', `Permission needed: network access + a new dependency. Asking ${userName()}.`);
      d.openDecision('p1', () => ({
        agentId: 'wren',
        kind: 'permission',
        tool: 'Bash',
        question: 'Wren wants to run `npm install chalk@5` (network access, adds a dependency).',
        options: [...PERMISSION_OPTIONS],
        context: `Bash: npm install chalk@5\ncwd: ${d.wt('t4').path}\nreason: package install downloads from the network and changes package.json`,
        taskId: d.task('t4').id,
      }));
      d.act('wren', 'waiting_user', 'user', 'asking to install chalk');
      await d.sleep(600);
    },
  },
  {
    name: 'kit-tests-fail',
    async run(d) {
      const pass = await d.runTests('kit', { taskKey: 't2', worktree: d.wt('t2') });
      if (!pass) d.say('kit', 'all', 'Hyphens and mid-word #s trip my first regex (3 red). Fixing.');
      await d.sleep(800);
    },
  },
  {
    name: 'permission-answer',
    async run(d) {
      const p = await d.awaitDecision('p1');
      const allowed = p.answer?.option !== 'Deny';
      if (allowed) {
        d.log('wren', 'result', '(sim) install skipped - the demo stays offline');
        d.log('wren', 'text', 'Thanks! On reflection plain ANSI escapes are 4 lines; keeping pocket-notes zero-dependency.');
      } else {
        d.log('wren', 'result', `Permission denied by ${userName()}`);
        d.log('wren', 'text', 'No problem - plain ANSI escapes it is, zero dependencies.');
      }
      d.memory('wren', 'shared', 'Decisions', `# Decisions\n\n- Tag colors use plain ANSI escapes, no chalk (${allowed ? 'install allowed, not needed' : 'install denied'}).`, 'append', 'decisions');
      await d.sleep(600);
    },
  },
  {
    name: 'fixes-and-highlight',
    async run(d) {
      const wren = d.wt('t4');
      await d.patch('wren', wren.path, E.T4_FORMAT, 1200);
      const kit = d.wt('t2');
      await d.think('kit', 'Anchor tags at start/whitespace and allow "-" inside', 'desk', 900);
      await d.patch('kit', kit.path, E.T2_FIX, 1100);
      await d.patch('wren', wren.path, E.T4_TEST, 1000);
      d.memory('kit', 'kit', 'Regex notes', `# Regex notes\n\n- \`/#(\\w+)/\` splits #to-do and matches issue#12. Use \`(?:^|\\s)#(\\w[\\w-]*)\`.\n- Unicode letters (#café) still need a look in ${d.task('t5').id}.`, 'replace', 'regex-notes');
    },
  },
  {
    name: 'kit-green',
    async run(d) {
      const pass = await d.runTests('kit', { taskKey: 't2', worktree: d.wt('t2') });
      if (!pass) throw new Error('sim: t2 tests should pass after the fix');
      await d.commit('kit', 't2', 'Add tag parser (parseTags, hasTag, normalizeTag)');
      d.finishTask('kit', 't2', 'src/tags.ts: parseTags/hasTag/normalizeTag + 5 tests. Hyphens ok, "issue#12" ignored, case-insensitive.');
      d.say('kit', 'juniper', 'parseTags() and hasTag() are in src/tags.ts: lowercase + de-duplicated. You are unblocked as soon as it merges.');
      await d.sleep(1200);
      d.say('juniper', 'kit', "Perfect - I'll build `list --tag` on hasTag() and `tags` on parseTags().");
      d.act('kit', 'idle', 'lounge', `${d.task('t2').id} in review`);
      await d.sleep(800);
    },
  },
  {
    name: 'review-t2',
    async run(d) {
      await d.requestMerge('t2', 1, 'kit-t2 reads well: tests first, regex anchored. Nit for later: export the pattern so format.ts can reuse it.');
      const pass = await d.runTests('wren', { taskKey: 't4', worktree: d.wt('t4') });
      if (!pass) throw new Error('sim: t4 tests should pass');
      await d.commit('wren', 't4', 'Highlight #tags in list output (opt-in color)');
      d.finishTask('wren', 't4', 'highlightTags() + {color} option on formatNote/formatList; test added. Plain ANSI, no deps.');
      d.act('wren', 'idle', 'lounge', `${d.task('t4').id} in review`);
    },
  },
  {
    name: 'merge-t2',
    async run(d) {
      const outcome = await d.settleMerge('t2', 'kit', 'src/tags.ts');
      if (outcome === 'rejected') return;
      d.fm.bus.feed('task', `${d.task('t3').id} and ${d.task('t5').id} are unblocked`, { agentId: 'marlow' });
    },
  },
  {
    name: 'wave2-start',
    async run(d) {
      if (d.vars.rejected) return;
      const jun = await d.startTask('juniper', 't3');
      const kit = await d.startTask('kit', 't5');
      await d.read('juniper', jun.path, 'src/tags.ts', 800);
      await d.patch('juniper', jun.path, E.T3_NOTES, 1100);
      await d.patch('kit', kit.path, E.T5_TESTS, 1000);
      await d.requestMerge('t4', 1, 'wren-t4: small and clean, color is opt-in so tests stay deterministic.');
      await d.think('marlow', 'Should `notes tags` count completed notes? That is a product call.', 'user', 900);
      d.openDecision('q1', () => ({
        agentId: 'marlow',
        kind: 'question',
        question: 'Should `notes tags` count tags on completed notes too?',
        options: [...Q1_OPTIONS],
        context: 'Juniper is building the `tags` command now. Open-only matches what `notes list` shows by default; `--all` could include done notes.',
        taskId: d.task('t3').id,
      }));
      d.act('marlow', 'waiting_user', 'user', 'asking you about `notes tags`');
    },
  },
  {
    name: 'showcase-moment',
    checkpoint: 'showcase',
    async run(d) {
      if (d.vars.rejected) return;
      const pass = await d.runTests('kit', { taskKey: 't5', worktree: d.wt('t5') });
      if (!pass) d.say('kit', 'all', 'Unicode tags (#café) slip past \\w. Switching to \\p{L} with the u flag.');
      const jun = d.wt('t3');
      await d.patch('juniper', jun.path, E.T3_CLI_LIST, 1000);
      d.act('juniper', 'editing', 'desk', 'editing src/cli.ts');
      d.act('rowan', 'reading', 'library', 'reading src/notes.ts');
      d.act('tove', 'thinking', 'desk', 'outlining the Tags docs');
      d.log('tove', 'text', 'Drafting the README "Tags" section while the CLI lands.');
      d.act('wren', 'idle', 'lounge', `${d.task('t4').id} awaiting your merge`);
    },
  },
  {
    name: 'question-answer',
    async run(d) {
      if (d.vars.rejected) return;
      const q = await d.awaitDecision('q1');
      const includeDone = q1IncludesDone(d);
      const answer = [q.answer?.option, q.answer?.text].filter(Boolean).join(' - ');
      d.act('marlow', 'thinking', 'meeting', 'relaying your answer');
      d.say('marlow', 'juniper', includeDone ? `${userName()} says: count completed notes too.` : `${userName()} says: open notes only; \`notes tags --all\` includes completed ones.`);
      d.memory('marlow', 'shared', 'Decisions', `- \`notes tags\`: ${answer} (asked by Marlow).`, 'append', 'decisions');
      const jun = d.wt('t3');
      await d.patch('juniper', jun.path, E.t3CliTags(includeDone), 1200);
      await d.patch('juniper', jun.path, E.t3CliTests(includeDone), 1000);
      d.act('marlow', 'idle', 'meeting', 'keeping an eye on the wall');
    },
  },
  {
    name: 'kit-unicode-fix',
    async run(d) {
      if (d.vars.rejected) return;
      const kit = d.wt('t5');
      await d.think('kit', 'Use \\p{L}\\p{N} with the u flag; NFC-normalize before lowercasing', 'desk', 900);
      await d.patch('kit', kit.path, E.T5_FIX, 1100);
      const pass = await d.runTests('kit', { taskKey: 't5', worktree: kit });
      if (!pass) throw new Error('sim: t5 tests should pass after the fix');
      await d.commit('kit', 't5', 'Unicode-aware tags; punctuation ends a tag');
      d.finishTask('kit', 't5', 'Tags accept any script (\\p{L}) and are NFC-normalized; 2 new tests.');
      d.act('kit', 'idle', 'lounge', `${d.task('t5').id} in review`);
    },
  },
  {
    name: 'merge-t4',
    async run(d) {
      if (d.vars.rejected) return;
      await d.settleMerge('t4', 'wren', 'src/format.ts');
    },
  },
  {
    name: 'juniper-green',
    async run(d) {
      if (d.vars.rejected) return;
      const jun = d.wt('t3');
      const pass = await d.runTests('juniper', { taskKey: 't3', worktree: jun });
      if (!pass) throw new Error('sim: t3 tests should pass');
      await d.commit('juniper', 't3', 'notes list --tag and notes tags');
      d.finishTask('juniper', 't3', '`list --tag <t>` / `-t`, `tags` with counts (most used first), 3 CLI tests.');
      d.act('juniper', 'idle', 'lounge', `${d.task('t3').id} in review`);
      await d.requestMerge('t3', 1, 'juniper-t3: flagValue() helper keeps run() readable; tests cover filter + counts.');
      await d.requestMerge('t5', 1, 'kit-t5: the u-flag regex is right; NFC is a nice touch for composed accents.');
    },
  },
  {
    name: 'merge-t3-t5',
    async run(d) {
      if (d.vars.rejected) return;
      const a = await d.settleMerge('t3', 'juniper', 'src/cli.ts');
      if (a === 'rejected') return;
      await d.settleMerge('t5', 'kit', 'src/tags.ts');
    },
  },
  {
    name: 'docs-start',
    checkpoint: 'showcase-late',
    async run(d) {
      if (d.vars.rejected) return;
      const wt = await d.startTask('tove', 't6');
      await d.read('tove', wt.path, 'README.md', 700);
      await d.patch('tove', wt.path, E.T6_README, 1200);
      d.act('kit', 'done', 'lounge', `${d.task('t2').id} + ${d.task('t5').id} merged - done`);
      d.say('kit', 'all', 'Both my branches are merged. Shout if the parser misbehaves.');
      // Wren takes the release task and hits the wall agents never cross: publishing
      const t7 = d.task('t7');
      d.say('marlow', 'wren', `Wren, can you take ${t7.id} (0.3.0 release)? Publishing itself is ${userName()}'s call.`);
      d.fm.tasks.update(t7.id, { assignee: 'wren' });
      d.fm.setAgent('wren', { taskId: t7.id, repoId: d.repoId, worktree: null });
      await d.read('wren', d.repoPath, 'package.json', 700);
      await d.think('wren', 'npm publish needs an npm login - agents never log in or publish', 'desk', 900);
      d.act('wren', 'blocked', 'desk', `${t7.id}: needs ${userName()}'s npm login`);
      d.log('wren', 'error', `${t7.id} blocked: publishing needs ${userName()}'s npm credentials`);
      d.say('wren', 'marlow', `${t7.id} is blocked: npm publish needs ${userName()}'s login. Version bump and notes can wait for that.`);
      // Juniper smoke-tests the merged CLI on main (real run of the CLI)
      await d.cli('juniper', ['add', 'triage inbox #work #today'], ['tags']);
      d.act('juniper', 'running', 'terminal', '$ notes tags (smoke test on main)');
      // Rowan's session hits an API overload: an agent error that recovers in the next beat
      await d.read('rowan', d.repoPath, 'src/cli.ts', 800);
      d.log('rowan', 'error', 'API error: overloaded_error (529). Retrying in 20s...');
      d.act('rowan', 'error', 'library', 'API overloaded - retrying');
      d.act('marlow', 'thinking', 'meeting', 'drafting the 0.3.0 release plan');
      d.act('tove', 'editing', 'desk', 'editing README.md');
    },
  },
  {
    name: 'docs-finish',
    async run(d) {
      if (d.vars.rejected) return;
      const wt = d.wt('t6');
      d.log('rowan', 'result', 'Retried after the overload - back on track.');
      d.act('rowan', 'reading', 'library', 'reviewing merged main');
      d.act('juniper', 'idle', 'lounge', 'smoke test passed');
      await d.read('tove', wt.path, 'src/cli.ts', 700);
      await d.patch('tove', wt.path, E.T6_CLI, 1000);
      const pass = await d.runTests('tove', { taskKey: 't6', worktree: wt });
      if (!pass) throw new Error('sim: t6 tests should pass');
      await d.commit('tove', 't6', 'Document tags; help text; colors on a TTY');
      d.finishTask('tove', 't6', 'README Tags section + command table, `notes help` mentions tags, colors when stdout is a TTY.');
      d.act('tove', 'idle', 'lounge', `${d.task('t6').id} in review`);
      await d.requestMerge('t6', 1, 'tove-t6: docs match the behaviour; color only on a TTY and honours NO_COLOR.');
      await d.settleMerge('t6', 'tove', 'README.md');
    },
  },
  {
    name: 'qa',
    async run(d) {
      if (d.vars.rejected) return;
      const t8 = d.task('t8');
      d.fm.tasks.update(t8.id, { assignee: 'tove' });
      d.setTask('t8', 'doing');
      d.fm.setAgent('tove', { taskId: t8.id, repoId: d.repoId, worktree: null });
      const pass = await d.runTests('tove', { taskKey: 't8' });
      d.act('tove', 'running', 'terminal', 'smoke-testing the CLI');
      await d.cli('tove', ['add', 'plan sprint #work'], ['add', 'call mum #family'], ['add', 'review PR #work #urgent'], ['tags'], ['list', '--tag', 'work']);
      d.memory(
        'tove',
        'shared',
        'Release notes 0.3.0 (draft)',
        `# pocket-notes 0.3.0 (draft)\n\n- New: \`#tags\` anywhere in a note (case-insensitive, any language).\n- New: \`notes list --tag <name>\` (or \`-t\`).\n- New: \`notes tags\` shows tag counts.\n- Tags are highlighted in the terminal (NO_COLOR respected).\n\nQA: full suite ${pass ? 'green' : 'RED'} on main; CLI smoke passed.`,
        'replace',
        'release-notes',
      );
      d.doneNoCode('t8', pass ? 'Suite green on main; CLI smoke ok; release notes drafted.' : 'Suite red on main - see log.');
      d.fm.setAgent('tove', { taskId: null });
      d.act('tove', 'done', 'lounge', 'QA done');
    },
  },
  {
    name: 'wrap-up',
    async run(d) {
      await d.think('marlow', `Wrapping up: what is left for ${userName()}?`, 'meeting', 1200);
      if (!d.vars.rejected) {
        d.setTask('t9', 'cancelled', { summary: 'Stretch goal parked for a later goal.' });
        d.log('marlow', 'tool', `update_task ${d.task('t9').id} cancelled`);
        d.say('rowan', 'marlow', 'Fine by me - completion can be its own goal.');
      }
      await d.sleep(800);
      const t7 = d.task('t7');
      if (d.vars.rejected) {
        // the goal stays active: the rejected work and its dependents are on the wall for the user
        d.say('marlow', 'user', 'Stopped after your rejection. Everything that was approved is merged; the rest is on the wall.');
      } else {
        d.say('marlow', 'user', `All merged into main: parser, --tag filter, tags command, highlighting, docs; QA is green. Only ${t7.id} (npm publish) is left, and that needs you.`);
        await d.think('marlow', `Asking ${userName()} what to do with ${t7.id}`, 'user', 800);
        d.openDecision('q2', () => ({
          agentId: 'marlow',
          kind: 'question',
          question: `${t7.id} "${t7.title}" needs your npm login (agents never publish). Close it and wrap up the goal?`,
          options: q2Options(t7.id),
          context: 'Everything else is merged and QA is green. Closing t7 completes the goal; keeping it leaves the goal open until you publish.',
          taskId: t7.id,
        }));
        d.act('marlow', 'waiting_user', 'user', `asking you about ${t7.id}`);
        const q = await d.awaitDecision('q2');
        const close = q.answer?.option === q2Options(t7.id)[0] || (!q.answer?.option && q.status === 'answered');
        if (close) {
          // cancelled tasks do not count towards the goal: the task graph completes it (100%)
          d.setTask('t7', 'cancelled', { summary: `${userName()} publishes 0.3.0 by hand.` });
          d.log('marlow', 'tool', `update_task ${t7.id} cancelled (${userName()} publishes)`);
          d.say('marlow', 'all', `${userName()} will publish ${t7.id} by hand. That's the goal - thanks, team.`);
        } else {
          d.say('marlow', 'all', `${t7.id} stays on the wall for ${userName()}; the goal stays open until it is published.`);
        }
        await d.sleep(600);
      }
      const goalDone = d.fm.goal(d.goalId)?.status === 'done';
      for (const a of d.fm.agents().filter((x) => !d.fm.isDesk(x.id))) {
        if (a.id === 'marlow') d.act(a.id, goalDone ? 'done' : 'idle', 'meeting', goalDone ? 'goal done' : `waiting on ${t7.id}`);
        else d.act(a.id, 'done', 'lounge', 'done for today');
        d.fm.setAgent(a.id, { taskId: null, worktree: null });
      }
    },
  },
];

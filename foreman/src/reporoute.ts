// Picks the repo a goal is about from its text, so one studio can hold many repos. The console
// sends goals without a repoId; without this every goal went to the most recently added repo.
//
//   "moonbase: make the lighting moodier"   explicit prefix (wins)
//   "fix the login bug in shop.example"     a repo named anywhere in the text
//
// Names are matched as whole words, case-insensitive, by folder name or by path under your home
// ("Desktop/side-project"). The longest match wins, so "website-2" beats "website"; between repos with
// the same name, the shallower path wins ("~/Pulse" over "~/Pulse/Pulse").
import os from 'node:os';
import path from 'node:path';

export interface RoutableRepo {
  id: string;
  name: string;
  path: string;
}

export interface RepoRoute {
  repoId: string;
  /** the goal text with an explicit "<repo>:" prefix removed */
  text: string;
}

interface Candidate {
  repo: RoutableRepo;
  key: string;
  depth: number;
}

const WORD = '[\\w.-]';
const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

function candidates(repos: RoutableRepo[], home: string): Candidate[] {
  return repos.flatMap((repo) => {
    const rel = path.relative(home, repo.path).split(path.sep).join('/');
    const depth = rel.split('/').length;
    const keys = new Set([repo.name, path.basename(repo.path)]);
    if (rel && !rel.startsWith('..')) keys.add(rel);
    return [...keys].filter(Boolean).map((key) => ({ repo, key: key.toLowerCase(), depth }));
  });
}

/** Longest key first, then the shallower repo. */
function best(found: Candidate[]): Candidate | undefined {
  return [...found].sort((a, b) => b.key.length - a.key.length || a.depth - b.depth)[0];
}

export function routeGoal(text: string, repos: RoutableRepo[], home = os.homedir()): RepoRoute | undefined {
  const all = candidates(repos, home);
  const prefix = /^\s*([^\s:]+(?: [^\s:]+)?)\s*:\s*([\s\S]+)$/.exec(text);
  if (prefix) {
    const hit = best(all.filter((c) => c.key === prefix[1]!.toLowerCase()));
    if (hit) return { repoId: hit.repo.id, text: prefix[2]!.trim() };
  }
  const lower = text.toLowerCase();
  const hit = best(all.filter((c) => new RegExp(`(^|[^\\w.-])${escape(c.key)}(?!${WORD})`).test(lower)));
  return hit ? { repoId: hit.repo.id, text } : undefined;
}

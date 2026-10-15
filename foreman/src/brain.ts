// Brain: the user's Obsidian vault, shared by every agent in every profile (`--brain <dir>`).
//
// Read-only except for one door: `inbox()` appends a dated, attributed line to Inbox.md, which the
// user (or their own Claude session) files into the right note later. Dot folders (.obsidian,
// .trash) are never read, and nothing outside the vault is reachable.
import fs from 'node:fs';
import path from 'node:path';

export class BrainError extends Error {}

export interface BrainHit {
  /** vault-relative path, forward slashes */
  path: string;
  name: string;
  snippet: string;
  score: number;
}

const INBOX = 'Inbox.md';
const MAX_INBOX_LINE = 500;
const SECRET_PATTERNS = [
  /\b(sk|pk|rk)[-_][a-z0-9-_]{12,}/i,
  /\b(password|passwd|api[-_ ]?key|secret|bearer)\b\s*[:=]/i,
  /\b(apikey|ghp|gho|xox[abp])_[a-z0-9_]{12,}/i,
  /\b[a-f0-9]{32,}\b/i,
  /\b[A-Za-z0-9+/_-]{40,}={0,2}(?![A-Za-z0-9])/,
];

function looksSecret(text: string): boolean {
  return SECRET_PATTERNS.some((re) => re.test(text));
}

function localDate(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** The line that matches the most query words; body text beats a heading on a tie. */
function bestLine(text: string, terms: string[]): string {
  let best = '';
  let bestScore = 0;
  for (const raw of text.split('\n')) {
    const l = raw.trim();
    const matched = terms.filter((t) => l.toLowerCase().includes(t)).length;
    if (!matched) continue;
    const s = matched * 2 + (l.startsWith('#') ? 0 : 1);
    if (s > bestScore) [best, bestScore] = [l, s];
  }
  return best;
}

export class Brain {
  readonly dir: string;

  constructor(dir: string) {
    const resolved = path.resolve(dir);
    if (!fs.existsSync(resolved) || !fs.statSync(resolved).isDirectory()) throw new BrainError(`brain folder not found: ${resolved}`);
    this.dir = fs.realpathSync(resolved);
  }

  /** Every .md note in the vault (vault-relative paths), skipping dot folders. Re-read each call: the user edits it live. */
  private notes(): string[] {
    const out: string[] = [];
    const walk = (rel: string) => {
      for (const ent of fs.readdirSync(path.join(this.dir, rel), { withFileTypes: true })) {
        if (ent.name.startsWith('.')) continue;
        const child = rel ? `${rel}/${ent.name}` : ent.name;
        if (ent.isDirectory()) walk(child);
        else if (ent.isFile() && ent.name.endsWith('.md')) out.push(child);
      }
    };
    walk('');
    return out.sort();
  }

  /** Notes matching any query word, ranked: name hits count most, then how often the words appear. */
  search(query: string, limit = 8): BrainHit[] {
    const terms = query.toLowerCase().split(/\s+/).filter((t) => t.length > 1);
    if (!terms.length) return [];
    const hits: BrainHit[] = [];
    for (const rel of this.notes()) {
      const name = path.basename(rel, '.md');
      const text = fs.readFileSync(path.join(this.dir, rel), 'utf8');
      const lower = text.toLowerCase();
      const lname = name.toLowerCase();
      let score = 0;
      for (const t of terms) {
        if (lname.includes(t)) score += 10;
        score += Math.min(lower.split(t).length - 1, 20);
      }
      if (!score) continue;
      hits.push({ path: rel, name, snippet: bestLine(text, terms).slice(0, 240), score });
    }
    return hits.sort((a, b) => b.score - a.score || a.path.localeCompare(b.path)).slice(0, limit);
  }

  /** A note by wikilink name (`Moonbase`, `[[Moonbase]]`), name with .md, or vault-relative path. */
  read(ref: string): { path: string; text: string } | undefined {
    const clean = ref.trim().replace(/^\[\[|\]\]$/g, '').split('|')[0]?.trim() ?? '';
    if (!clean || path.isAbsolute(clean)) return undefined;
    const withExt = clean.endsWith('.md') ? clean : `${clean}.md`;
    const notes = this.notes();
    const rel =
      notes.find((n) => n === withExt) ??
      notes.find((n) => n.toLowerCase() === withExt.toLowerCase()) ??
      notes.find((n) => path.basename(n).toLowerCase() === path.basename(withExt).toLowerCase());
    if (!rel) return undefined;
    const full = fs.realpathSync(path.join(this.dir, rel));
    if (!full.startsWith(this.dir + path.sep)) return undefined;
    return { path: rel, text: fs.readFileSync(full, 'utf8') };
  }

  /** Append one dated line to Inbox.md for the user to file later. Refuses anything that looks like a secret. */
  inbox(text: string, author: string, now = Date.now()): string {
    const line = text.trim();
    if (!line) throw new BrainError('inbox line is empty');
    if (/[\r\n]/.test(line)) throw new BrainError('inbox takes one line');
    if (line.length > MAX_INBOX_LINE) throw new BrainError(`inbox line is over ${MAX_INBOX_LINE} characters`);
    if (looksSecret(line)) throw new BrainError('that looks like a secret (key, password or token); never put secrets in the brain, say where they live instead');
    const entry = `- ${localDate(now)} (agent ${author.replace(/[^\w-]/g, '')}): ${line}\n`;
    const file = path.join(this.dir, INBOX);
    const prev = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
    const sep = prev && !prev.endsWith('\n') ? '\n' : '';
    fs.appendFileSync(file, sep + entry);
    return entry.trim();
  }
}

// Documents agents hand the user (`hand_over`): a markdown/text file read in game. The agent walks
// over to the player with it; the user reads it on a book screen and `doc.read` marks it read.
// Kept in state.json (small, capped) so a hand-over survives a restart until it is read.
import fs from 'node:fs';
import path from 'node:path';
import type { Doc } from './protocol.js';

export const DOC_EXT = new Set(['.md', '.markdown', '.txt', '.mdx', '.rst']);
/** Largest document handed over (bigger ones are cut, with a note at the end). */
export const DOC_MAX_CHARS = 200_000;
/** Documents kept (read ones are dropped first). */
export const DOC_KEEP = 30;

export class DocError extends Error {}

/** Read a document file into a Doc (without id/at, which the caller assigns). */
export function readDoc(file: string): { name: string; markdown: string; truncated: boolean } {
  const ext = path.extname(file).toLowerCase();
  if (!DOC_EXT.has(ext)) throw new DocError(`not a document: ${path.basename(file)} (hand over ${[...DOC_EXT].join(' ')} files; images and videos go to show_media)`);
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) throw new DocError(`no file at ${file}`);
  const text = fs.readFileSync(file, 'utf8');
  if (!text.trim()) throw new DocError(`${path.basename(file)} is empty`);
  const truncated = text.length > DOC_MAX_CHARS;
  return { name: path.basename(file), markdown: truncated ? `${text.slice(0, DOC_MAX_CHARS)}\n\n_(cut: the full file is ${file})_` : text, truncated };
}

/** The document's title: the given one, else its first `# heading`, else the file name. */
export function docTitle(markdown: string, name: string, title?: string): string {
  if (title?.trim()) return title.trim().slice(0, 120);
  const h = /^\s{0,3}#{1,3}\s+(.+?)\s*#*\s*$/m.exec(markdown);
  return (h?.[1] ?? name.replace(/\.[^.]+$/, '')).slice(0, 120);
}

/** Keep the newest DOC_KEEP, dropping read documents before unread ones. */
export function pruneDocs(docs: Doc[]): Doc[] {
  if (docs.length <= DOC_KEEP) return docs;
  let drop = docs.length - DOC_KEEP;
  const out: Doc[] = [];
  for (const d of docs) {
    if (drop > 0 && d.read) {
      drop--;
      continue;
    }
    out.push(d);
  }
  return out.slice(-DOC_KEEP);
}

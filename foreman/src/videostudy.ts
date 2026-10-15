// Video study: how content agents "see" a reference video frame by frame. yt-dlp fetches a URL (or a local file is
// used as is), ffmpeg samples frames with their timestamps in the file names, detects scene cuts, and builds 4x4
// contact sheets with the timestamp burned into every cell, so an agent can Read one image and see 16 moments at once.
// The YouTube subtitles become a clean [mm:ss] transcript. Everything runs through an injectable `Exec` (tests mock it).
// Timestamps on the sheets are drawn with `drawbox` pixels (a tiny built-in 3x5 font) instead of `drawtext`, because
// stock macOS ffmpeg builds (Homebrew) ship without freetype/drawtext.
import { execFile } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { promisify } from 'node:util';

const run = promisify(execFile);

export class StudyError extends Error {}

export interface StudyOptions {
  fps?: number; // frames per second to keep; default auto: shorts (<=75 s) 2 fps, else 0.5 fps; max 4
  maxFrames?: number; // default 240, hard max 600
  sceneCuts?: boolean; // default true: also extract a frame at every scene change
  start?: number;
  end?: number; // seconds window (to zoom in frame by frame on a part)
}

export interface StudyResult {
  dir: string;
  title?: string;
  channel?: string;
  durationSec?: number;
  views?: number;
  likes?: number;
  comments?: number;
  uploadDate?: string;
  description?: string;
  tags?: string[];
  youtubeId?: string;
  frames: { file: string; t: number }[]; // absolute paths: frames/f0001_00m03.50s.jpg, 768px wide
  sheets: string[]; // absolute paths: sheets/sheet01.jpg, 4x4 grids of consecutive frames with timestamps burned in
  sceneCuts: number[]; // seconds
  transcriptFile?: string; // transcript.txt with [mm:ss] lines
  pacing: { cutsPerMinute: number; avgShotSec: number; firstCutSec?: number };
  audio?: { hasAudio: boolean; meanVolumeDb?: number };
}

export interface ChannelVideo {
  youtubeId: string;
  title: string;
  url: string;
  views: number;
  durationSec?: number;
  uploadDate?: string;
  format?: 'short' | 'long';
}

export interface ChannelScan {
  channel?: string;
  channelId?: string;
  subscriberCount?: number;
  videos: ChannelVideo[]; // sorted by views, most viewed first
}

export type ExecResult = { stdout: string; stderr: string };
export type Exec = (cmd: string, args: string[], opts?: { timeoutMs?: number }) => Promise<ExecResult>;

export interface StudyDeps {
  exec?: Exec;
}

const BIN_FALLBACKS = ['/opt/homebrew/bin', '/usr/local/bin'];
const DEFAULT_MAX_FRAMES = 240;
const HARD_MAX_FRAMES = 600;
const SHORT_MAX_SEC = 75;
const MAX_FPS = 4;
const SCENE_THRESHOLD = 0.3;
const MAX_CUT_FRAMES = 60;
const FRAME_WIDTH = 768;
const SUB_LANGS = 'en,en-US,en-GB,en-orig,pt,pt-PT,pt-BR,pt-orig';
const SHEET_MAX_EDGE = 1568; // larger images get downscaled by the model's image reader
const SHEET_COLS = 4;
const SHEET_ROWS = 4;
const SHEET_CELLS = SHEET_COLS * SHEET_ROWS;

/** Real runner: tries PATH first, then the usual Homebrew locations (a GUI-launched foreman may have a bare PATH). */
export const defaultExec: Exec = async (cmd, args, opts) => {
  const o = { timeout: opts?.timeoutMs ?? 120_000, maxBuffer: 64 * 1024 * 1024 };
  try {
    return await run(cmd, args, o);
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code !== 'ENOENT') throw e;
    for (const dir of BIN_FALLBACKS) {
      const full = path.join(dir, cmd);
      if (fs.existsSync(full)) return run(full, args, o);
    }
    throw e;
  }
};

function tail(e: unknown): string {
  const err = e as { stderr?: string; message?: string };
  return (err.stderr || err.message || String(e)).trim().split('\n').filter(Boolean).slice(-2).join(' ');
}

async function tool(exec: Exec, cmd: 'ffmpeg' | 'ffprobe' | 'yt-dlp', args: string[], timeoutMs = 120_000): Promise<ExecResult> {
  try {
    return await exec(cmd, args, { timeoutMs });
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === 'ENOENT') {
      throw new StudyError(`${cmd} is not installed (brew install ${cmd === 'ffprobe' ? 'ffmpeg' : cmd}); it is needed to study videos`);
    }
    const msg = tail(e);
    const hint = cmd === 'yt-dlp' && /sign in|403|unable to extract|nsig|HTTP Error/i.test(msg) ? ' (try: brew upgrade yt-dlp)' : '';
    throw new StudyError(`${cmd} failed: ${msg}${hint}`);
  }
}

const isUrl = (s: string): boolean => /^https?:\/\//i.test(s);

// ---------- formatting ----------

const pad2 = (n: number): string => String(n).padStart(2, '0');

/** mm:ss (minutes keep growing past 59). */
export function fmtClock(t: number): string {
  const s = Math.max(0, Math.floor(t));
  return `${pad2(Math.floor(s / 60))}:${pad2(s % 60)}`;
}

/** 00m03.50s, as used in frame file names. */
export function fmtFrameStamp(t: number): string {
  const x = Math.max(0, t);
  const m = Math.floor(x / 60);
  return `${pad2(m)}m${(x - m * 60).toFixed(2).padStart(5, '0')}s`;
}

// ---------- tiny bitmap font for the sheet timestamps ----------

const GLYPHS: Record<string, string[]> = {
  '0': ['111', '101', '101', '101', '111'],
  '1': ['010', '110', '010', '010', '111'],
  '2': ['111', '001', '111', '100', '111'],
  '3': ['111', '001', '111', '001', '111'],
  '4': ['101', '101', '111', '001', '001'],
  '5': ['111', '100', '111', '001', '111'],
  '6': ['111', '100', '111', '101', '111'],
  '7': ['111', '001', '001', '001', '001'],
  '8': ['111', '101', '111', '101', '111'],
  '9': ['111', '101', '111', '001', '111'],
  ':': ['0', '1', '0', '1', '0'],
  '.': ['0', '0', '0', '0', '1'],
};
const GLYPH_PX = 4;
const GLYPH_GAP = 1;
const LABEL_PAD = 3;

/** drawbox filters painting `text` (digits : .) white on a dark plate in the top-left corner. */
export function labelFilters(text: string): string[] {
  const px = GLYPH_PX;
  const rows = 5;
  let x = LABEL_PAD;
  const boxes: string[] = [];
  for (const ch of text) {
    const g = GLYPHS[ch];
    if (!g) continue;
    const w = g[0]!.length;
    g.forEach((line, r) => {
      // merge horizontal runs of lit pixels into one box
      for (const m of line.matchAll(/1+/g)) {
        boxes.push(`drawbox=x=${x + m.index * px}:y=${LABEL_PAD + r * px}:w=${m[0].length * px}:h=${px}:color=white:t=fill`);
      }
    });
    x += (w + GLYPH_GAP) * px;
  }
  const plateW = x + LABEL_PAD - GLYPH_GAP * px;
  const plateH = rows * px + LABEL_PAD * 2;
  return [`drawbox=x=0:y=0:w=${plateW}:h=${plateH}:color=black@0.75:t=fill`, ...boxes];
}

export function sheetLabel(t: number): string {
  const x = Math.max(0, t);
  const m = Math.floor(x / 60);
  return `${pad2(m)}:${(x - m * 60).toFixed(1).padStart(4, '0')}`;
}

// ---------- subtitles ----------

function decodeEntities(s: string): string {
  return s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&nbsp;/g, ' ');
}

function parseVttTime(s: string): number {
  const p = s.trim().split(':').map(Number);
  return p.length === 3 ? p[0]! * 3600 + p[1]! * 60 + p[2]! : p[0]! * 60 + p[1]!;
}

/**
 * WebVTT → "[mm:ss] text" lines. Auto-subs roll: every cue repeats the previous line and adds a new one, and carry
 * inline <00:00:01.200><c> word timing tags; both are removed so each spoken line appears once.
 */
export function vttToTranscript(vtt: string): string {
  const out: string[] = [];
  let prev = new Set<string>();
  let lastLine = '';
  for (const block of vtt.replace(/\r/g, '').split(/\n{2,}/)) {
    const lines = block.split('\n');
    const ti = lines.findIndex((l) => l.includes('-->'));
    if (ti < 0) continue;
    const start = parseVttTime(lines[ti]!.split('-->')[0]!.trim().split(/\s+/)[0]!);
    if (!Number.isFinite(start)) continue;
    const text = lines
      .slice(ti + 1)
      .map((l) => decodeEntities(l.replace(/<[^>]*>/g, '')).replace(/\s+/g, ' ').trim())
      .filter(Boolean);
    const seen = new Set(text);
    for (const l of text) {
      if (prev.has(l) || l === lastLine) continue;
      out.push(`[${fmtClock(start)}] ${l}`);
      lastLine = l;
    }
    prev = seen;
  }
  return out.join('\n');
}

/** Best subtitle file from yt-dlp's `<base>.<lang>.vtt` outputs: English first, then Portuguese, then anything. */
export function pickSubtitle(files: string[]): string | undefined {
  const rank = (f: string): number => {
    const lang = f.replace(/\.vtt$/, '').split('.').pop() ?? '';
    if (lang === 'en') return 0;
    if (lang === 'en-orig') return 1;
    if (lang.startsWith('en')) return 2;
    if (lang === 'pt') return 3;
    if (lang.startsWith('pt')) return 4;
    return 5;
  };
  return files.filter((f) => f.endsWith('.vtt')).sort((a, b) => rank(a) - rank(b) || a.localeCompare(b))[0];
}

// ---------- ffprobe / ffmpeg steps ----------

interface Probe {
  durationSec: number;
  width: number;
  height: number;
  hasAudio: boolean;
}

async function probe(exec: Exec, file: string): Promise<Probe> {
  const { stdout } = await tool(exec, 'ffprobe', ['-v', 'error', '-show_entries', 'format=duration:stream=codec_type,width,height', '-of', 'json', file]);
  let j: { format?: { duration?: string }; streams?: { codec_type?: string; width?: number; height?: number }[] };
  try {
    j = JSON.parse(stdout);
  } catch {
    throw new StudyError(`ffprobe could not read ${path.basename(file)}`);
  }
  const v = j.streams?.find((s) => s.codec_type === 'video');
  const durationSec = Number(j.format?.duration);
  if (!v || !Number.isFinite(durationSec) || durationSec <= 0) throw new StudyError(`${path.basename(file)} has no readable video stream`);
  return { durationSec, width: v.width ?? 0, height: v.height ?? 0, hasAudio: !!j.streams?.some((s) => s.codec_type === 'audio') };
}

async function meanVolume(exec: Exec, file: string, start: number, dur: number): Promise<number | undefined> {
  try {
    const { stderr } = await exec('ffmpeg', ['-hide_banner', '-nostats', '-ss', String(start), '-t', String(dur), '-i', file, '-vn', '-af', 'volumedetect', '-f', 'null', '-'], { timeoutMs: 120_000 });
    const m = /mean_volume:\s*(-?[\d.]+) dB/.exec(stderr);
    return m ? Number(m[1]) : undefined;
  } catch {
    return undefined; // loudness is a nice-to-have
  }
}

async function detectCuts(exec: Exec, file: string, start: number, dur: number): Promise<number[]> {
  const { stderr } = await tool(
    exec,
    'ffmpeg',
    ['-hide_banner', '-nostats', '-loglevel', 'info', '-ss', String(start), '-t', String(dur), '-i', file, '-an', '-vf', `scale=320:-2,select='gt(scene,${SCENE_THRESHOLD})',showinfo`, '-f', 'null', '-'],
    600_000,
  );
  const cuts: number[] = [];
  for (const m of stderr.matchAll(/pts_time:\s*(-?[\d.]+)/g)) {
    const t = start + Number(m[1]);
    if (Number.isFinite(t) && t > start + 0.05 && (cuts.length === 0 || t - cuts[cuts.length - 1]! > 0.2)) cuts.push(Math.round(t * 100) / 100);
  }
  return cuts;
}

const scaleFilter = `scale=w='min(${FRAME_WIDTH},iw)':h=-2`;

/** Build the filter graph of one sheet: scale/pad each frame into a cell, stamp its time, then stack 4x4. */
export function sheetFilterGraph(times: number[], cellW: number, cellH: number): string {
  const parts: string[] = [];
  times.forEach((t, i) => {
    const chain = [
      `scale=${cellW}:${cellH}:force_original_aspect_ratio=decrease`,
      `pad=${cellW}:${cellH}:(ow-iw)/2:(oh-ih)/2:color=black`,
      ...labelFilters(sheetLabel(t)),
    ];
    parts.push(`[${i}:v]${chain.join(',')}[c${i}]`);
  });
  const rowCount = Math.ceil(times.length / SHEET_COLS);
  const rowNames: string[] = [];
  for (let r = 0; r < rowCount; r++) {
    const idx = Array.from({ length: Math.min(SHEET_COLS, times.length - r * SHEET_COLS) }, (_, k) => r * SHEET_COLS + k);
    const ins = idx.map((i) => `[c${i}]`).join('');
    const padTo = `pad=${cellW * SHEET_COLS}:${cellH}:0:0:color=black`;
    parts.push(idx.length > 1 ? `${ins}hstack=inputs=${idx.length},${padTo}[r${r}]` : `${ins}${padTo}[r${r}]`);
    rowNames.push(`[r${r}]`);
  }
  parts.push(rowNames.length > 1 ? `${rowNames.join('')}vstack=inputs=${rowNames.length}[out]` : `${rowNames[0]}null[out]`);
  return parts.join(';');
}

function cellSize(width: number, height: number): { w: number; h: number } {
  const edge = Math.floor(SHEET_MAX_EDGE / SHEET_COLS);
  if (!width || !height) return { w: edge, h: Math.round((edge * 9) / 16 / 2) * 2 };
  const ratio = width / height;
  // the sheet is 4 cells wide and 4 tall; keep its longest edge under SHEET_MAX_EDGE
  const w = ratio >= 1 ? edge : Math.floor((edge * ratio) / 2) * 2;
  const h = ratio >= 1 ? Math.floor(edge / ratio / 2) * 2 : edge;
  return { w: Math.max(w, 64), h: Math.max(h, 64) };
}

// ---------- yt-dlp ----------

interface Info {
  id?: string;
  title?: string;
  channel?: string;
  uploader?: string;
  duration?: number;
  view_count?: number;
  like_count?: number;
  comment_count?: number;
  upload_date?: string;
  description?: string;
  tags?: string[];
}

function fmtUploadDate(s: string | undefined): string | undefined {
  return s && /^\d{8}$/.test(s) ? `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6)}` : s || undefined;
}

async function download(exec: Exec, url: string, dir: string): Promise<{ video: string; info?: Info; subs?: string }> {
  const args = [
    '--no-playlist', '--no-progress', '--no-warnings', '-q',
    '-f', 'bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*[height<=1080]+ba/b[height<=1080]/b',
    '--merge-output-format', 'mp4',
    '-o', path.join(dir, 'source.%(ext)s'),
    '--write-info-json',
  ];
  // Exact language codes, not "en.*": that glob also matches YouTube's machine-translated tracks (en-de, en-fr...), each
  // one a request that gets rate limited (429), and yt-dlp aborts the whole run on the first failure.
  const subArgs = ['--write-auto-subs', '--write-subs', '--sub-langs', SUB_LANGS, '--sub-format', 'vtt'];
  let failure: unknown;
  try {
    await tool(exec, 'yt-dlp', [...args, ...subArgs, url], 600_000);
  } catch (e) {
    failure = e;
    // a subtitle hiccup can fail the run; the video is the point, so retry without subtitles
    if (!fs.readdirSync(dir).some((n) => /^source\.(mp4|mkv|webm|mov)$/.test(n))) {
      try {
        await tool(exec, 'yt-dlp', [...args, url], 600_000);
        failure = undefined;
      } catch (e2) {
        failure = e2;
      }
    }
  }
  const names = fs.readdirSync(dir);
  const video = names.find((n) => /^source\.(mp4|mkv|webm|mov)$/.test(n));
  if (!video) throw failure instanceof Error ? failure : new StudyError(`yt-dlp downloaded nothing from ${url}`);
  let info: Info | undefined;
  const infoName = names.find((n) => n === 'source.info.json');
  if (infoName) {
    try {
      info = JSON.parse(fs.readFileSync(path.join(dir, infoName), 'utf8')) as Info;
    } catch {
      info = undefined;
    }
  }
  const sub = pickSubtitle(names.filter((n) => n.startsWith('source.')));
  return { video: path.join(dir, video), info, subs: sub ? path.join(dir, sub) : undefined };
}

// ---------- study ----------

function clampFps(opts: StudyOptions, windowSec: number): number {
  const auto = windowSec <= SHORT_MAX_SEC ? 2 : 0.5;
  const want = Math.min(Math.max(opts.fps && opts.fps > 0 ? opts.fps : auto, 0.05), MAX_FPS);
  const maxFrames = Math.min(Math.max(Math.floor(opts.maxFrames ?? DEFAULT_MAX_FRAMES), 1), HARD_MAX_FRAMES);
  return Math.min(want, maxFrames / Math.max(windowSec, 0.5));
}

function listJpgs(dir: string): string[] {
  return fs.readdirSync(dir).filter((n) => n.endsWith('.jpg')).sort();
}

export async function studyVideo(source: string, outDir: string, opts: StudyOptions = {}, deps: StudyDeps = {}): Promise<StudyResult> {
  const exec = deps.exec ?? defaultExec;
  const dir = path.resolve(outDir);
  if (!source.trim()) throw new StudyError('no video given (pass a URL or a file path)');
  if (opts.start !== undefined && (!Number.isFinite(opts.start) || opts.start < 0)) throw new StudyError('start must be 0 or more seconds');
  if (opts.end !== undefined && (!Number.isFinite(opts.end) || opts.end <= (opts.start ?? 0))) throw new StudyError('end must be after start');
  fs.mkdirSync(dir, { recursive: true });

  let video = source;
  let info: Info | undefined;
  let subs: string | undefined;
  if (isUrl(source)) ({ video, info, subs } = await download(exec, source, dir));
  else if (!fs.existsSync(source) || !fs.statSync(source).isFile()) throw new StudyError(`no file at ${source}`);

  const pr = await probe(exec, video);
  const start = Math.min(opts.start ?? 0, pr.durationSec - 0.1);
  const end = Math.min(opts.end ?? pr.durationSec, pr.durationSec);
  const win = Math.max(end - start, 0.1);
  const fps = clampFps(opts, win);
  const maxFrames = Math.min(Math.max(Math.floor(opts.maxFrames ?? DEFAULT_MAX_FRAMES), 1), HARD_MAX_FRAMES);

  // fresh frames/ and sheets/ so a second study with other settings does not mix with the first
  const framesDir = path.join(dir, 'frames');
  const sheetsDir = path.join(dir, 'sheets');
  const tmpDir = path.join(dir, '.tmp');
  for (const d of [framesDir, sheetsDir, tmpDir]) {
    fs.rmSync(d, { recursive: true, force: true });
    fs.mkdirSync(d, { recursive: true });
  }

  const cuts = opts.sceneCuts === false ? [] : await detectCuts(exec, video, start, win);

  await tool(exec, 'ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-ss', String(start), '-t', String(win), '-i', video, '-an', '-vf', `fps=${Number(fps.toFixed(4))},${scaleFilter}`, '-q:v', '3', '-frames:v', String(maxFrames), path.join(tmpDir, 's%05d.jpg')], 600_000);
  const sampled = listJpgs(tmpDir).map((n, k) => ({ tmp: path.join(tmpDir, n), t: Math.round((start + k / fps) * 100) / 100 }));
  if (!sampled.length) throw new StudyError(`ffmpeg read no frames from ${path.basename(video)}`);

  // an extra frame at each scene change, unless a sampled frame already sits within 0.15 s of it
  const extra: { tmp: string; t: number }[] = [];
  const room = Math.min(MAX_CUT_FRAMES, HARD_MAX_FRAMES - sampled.length);
  for (const c of cuts.filter((c) => !sampled.some((s) => Math.abs(s.t - c) < 0.15)).slice(0, Math.max(room, 0))) {
    const tmp = path.join(tmpDir, `c${String(extra.length).padStart(5, '0')}.jpg`);
    try {
      await tool(exec, 'ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-ss', String(c), '-i', video, '-frames:v', '1', '-vf', scaleFilter, '-q:v', '3', tmp]);
      if (fs.existsSync(tmp)) extra.push({ tmp, t: c });
    } catch (e) {
      if (e instanceof StudyError && /not installed/.test(e.message)) throw e; // a single missed cut frame is not worth failing for
    }
  }

  const all = [...sampled, ...extra].sort((a, b) => a.t - b.t);
  const frames = all.map((f, i) => {
    const file = path.join(framesDir, `f${String(i + 1).padStart(4, '0')}_${fmtFrameStamp(f.t)}.jpg`);
    fs.renameSync(f.tmp, file);
    return { file, t: f.t };
  });
  fs.rmSync(tmpDir, { recursive: true, force: true });

  const cell = cellSize(pr.width, pr.height);
  const sheets: string[] = [];
  for (let i = 0; i < frames.length; i += SHEET_CELLS) {
    const chunk = frames.slice(i, i + SHEET_CELLS);
    const out = path.join(sheetsDir, `sheet${String(sheets.length + 1).padStart(2, '0')}.jpg`);
    await tool(exec, 'ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...chunk.flatMap((f) => ['-i', f.file]), '-filter_complex', sheetFilterGraph(chunk.map((f) => f.t), cell.w, cell.h), '-map', '[out]', '-frames:v', '1', '-q:v', '3', out]);
    sheets.push(out);
  }

  let transcriptFile: string | undefined;
  if (subs) {
    try {
      const text = vttToTranscript(fs.readFileSync(subs, 'utf8'));
      if (text) {
        transcriptFile = path.join(dir, 'transcript.txt');
        fs.writeFileSync(transcriptFile, text + '\n');
      }
    } catch {
      transcriptFile = undefined;
    }
  }

  const audio = pr.hasAudio ? { hasAudio: true, meanVolumeDb: await meanVolume(exec, video, start, win) } : { hasAudio: false };
  const pacing = {
    cutsPerMinute: Math.round((cuts.length / (win / 60)) * 10) / 10,
    avgShotSec: Math.round((win / (cuts.length + 1)) * 100) / 100,
    ...(cuts.length ? { firstCutSec: cuts[0] } : {}),
  };

  const result: StudyResult = {
    dir,
    title: info?.title,
    channel: info?.channel ?? info?.uploader,
    durationSec: Math.round((info?.duration ?? pr.durationSec) * 100) / 100,
    views: info?.view_count,
    likes: info?.like_count,
    comments: info?.comment_count,
    uploadDate: fmtUploadDate(info?.upload_date),
    description: info?.description?.slice(0, 2000),
    tags: info?.tags?.slice(0, 30),
    youtubeId: info?.id,
    frames,
    sheets,
    sceneCuts: cuts,
    ...(transcriptFile ? { transcriptFile } : {}),
    pacing,
    audio,
  };
  fs.writeFileSync(path.join(dir, 'meta.json'), JSON.stringify({ source, ...result }, null, 2));
  fs.writeFileSync(path.join(dir, 'breakdown.md'), renderBreakdown(result));
  return result;
}

// ---------- breakdown ----------

const rel = (dir: string, f: string): string => path.relative(dir, f) || f;
const num = (n: number | undefined): string => (n === undefined ? '?' : n.toLocaleString('en-US'));

export function renderBreakdown(r: StudyResult): string {
  const L: string[] = [];
  L.push(`# ${r.title ?? 'Video study'}`);
  const meta = [
    r.channel && `channel: ${r.channel}`,
    r.durationSec !== undefined && `length: ${fmtClock(r.durationSec)} (${r.durationSec}s)`,
    r.uploadDate && `uploaded: ${r.uploadDate}`,
    r.views !== undefined && `views: ${num(r.views)}`,
    r.likes !== undefined && `likes: ${num(r.likes)}`,
    r.comments !== undefined && `comments: ${num(r.comments)}`,
    r.youtubeId && `id: ${r.youtubeId}`,
  ].filter(Boolean);
  if (meta.length) L.push('', meta.join(' | '));
  if (r.tags?.length) L.push('', `tags: ${r.tags.join(', ')}`);
  if (r.description) L.push('', '## Description', '', r.description.slice(0, 800));

  L.push('', '## Pacing', '');
  L.push(`- ${r.sceneCuts.length} scene cuts, ${r.pacing.cutsPerMinute} per minute, average shot ${r.pacing.avgShotSec}s`);
  L.push(r.pacing.firstCutSec !== undefined ? `- first cut at ${r.pacing.firstCutSec}s` : '- no cut detected (one continuous shot)');
  if (r.audio) L.push(r.audio.hasAudio ? `- audio: yes${r.audio.meanVolumeDb !== undefined ? `, mean volume ${r.audio.meanVolumeDb} dB` : ''}` : '- audio: none');

  if (r.sceneCuts.length) {
    const shown = r.sceneCuts.slice(0, 80);
    L.push('', '## Scene cuts', '', shown.map((t) => `${fmtClock(t)}.${String(Math.round((t % 1) * 10) % 10)}`).join('  ') + (r.sceneCuts.length > shown.length ? `  ... +${r.sceneCuts.length - shown.length} more` : ''));
  }

  if (r.transcriptFile && fs.existsSync(r.transcriptFile)) {
    const lines = fs.readFileSync(r.transcriptFile, 'utf8').split('\n').filter(Boolean);
    const excerpt = lines.slice(0, 60).join('\n');
    L.push('', `## Transcript (${lines.length} lines, full text in ${rel(r.dir, r.transcriptFile)})`, '', '```', excerpt.slice(0, 4000), '```');
  } else {
    L.push('', '## Transcript', '', 'No subtitles available for this video.');
  }

  L.push('', '## Files', '', `- ${r.frames.length} frames in frames/ (timestamp is in the file name)`);
  r.sheets.forEach((s, i) => {
    const part = r.frames.slice(i * SHEET_CELLS, (i + 1) * SHEET_CELLS);
    const range = part.length ? ` (${fmtClock(part[0]!.t)} to ${fmtClock(part[part.length - 1]!.t)})` : '';
    L.push(`- ${rel(r.dir, s)}${range}`);
  });
  L.push('', 'Read the sheets to see the video; each cell has its time (mm:ss.s) burned into the top-left corner. Open single frames for detail.');
  return L.join('\n') + '\n';
}

// ---------- channel scan ----------

interface FlatEntry {
  id?: string;
  title?: string;
  url?: string;
  view_count?: number | null;
  duration?: number | null;
  upload_date?: string;
}

interface FlatPlaylist {
  channel?: string;
  uploader?: string;
  channel_id?: string;
  channel_follower_count?: number;
  entries?: FlatEntry[];
}

function channelBase(url: string): string {
  const u = url.trim().replace(/[?#].*$/, '').replace(/\/+$/, '');
  return u.replace(/\/(videos|shorts|streams|featured|live|playlists|community)$/i, '');
}

async function flatTab(exec: Exec, base: string, tab: 'videos' | 'shorts', limit: number): Promise<FlatPlaylist | undefined> {
  try {
    const { stdout } = await tool(exec, 'yt-dlp', ['--flat-playlist', '-J', '--no-warnings', '--playlist-end', String(limit), `${base}/${tab}`], 180_000);
    return JSON.parse(stdout) as FlatPlaylist;
  } catch (e) {
    if (e instanceof StudyError && /not installed/.test(e.message)) throw e;
    return undefined; // a channel without a Shorts (or Videos) tab is normal
  }
}

/** Public videos of a channel (Videos + Shorts tabs), most viewed first. For competitor research and pre-OAuth stats. */
export async function scanChannel(url: string, limit = 30, deps: StudyDeps = {}): Promise<ChannelScan> {
  const exec = deps.exec ?? defaultExec;
  if (!isUrl(url)) throw new StudyError('scan_channel needs a channel URL like https://www.youtube.com/@handle');
  const cap = Math.min(Math.max(Math.floor(limit) || 30, 1), 200);
  const base = channelBase(url);
  const [longs, shorts] = await Promise.all([flatTab(exec, base, 'videos', cap), flatTab(exec, base, 'shorts', cap)]);
  if (!longs && !shorts) throw new StudyError(`could not read the channel at ${base} (check the URL; brew upgrade yt-dlp if it keeps failing)`);

  const byId = new Map<string, ChannelVideo>();
  const add = (pl: FlatPlaylist | undefined, format: 'short' | 'long'): void => {
    for (const e of pl?.entries ?? []) {
      if (!e?.id || byId.has(e.id)) continue;
      byId.set(e.id, {
        youtubeId: e.id,
        title: e.title ?? e.id,
        url: format === 'short' ? `https://www.youtube.com/shorts/${e.id}` : `https://www.youtube.com/watch?v=${e.id}`,
        views: typeof e.view_count === 'number' ? e.view_count : 0,
        ...(typeof e.duration === 'number' ? { durationSec: e.duration } : {}),
        ...(e.upload_date ? { uploadDate: fmtUploadDate(e.upload_date) } : {}),
        format,
      });
    }
  };
  add(longs, 'long');
  add(shorts, 'short');
  const head = longs ?? shorts;
  const subs = longs?.channel_follower_count ?? shorts?.channel_follower_count;
  return {
    ...((head?.channel ?? head?.uploader) ? { channel: head?.channel ?? head?.uploader } : {}),
    ...(head?.channel_id ? { channelId: head.channel_id } : {}),
    ...(typeof subs === 'number' ? { subscriberCount: subs } : {}),
    videos: [...byId.values()].sort((a, b) => b.views - a.views).slice(0, cap),
  };
}

/** Full public metadata of one video (no download). Used by publicStats. */
export async function videoInfo(urlOrId: string, deps: StudyDeps = {}): Promise<Info> {
  const exec = deps.exec ?? defaultExec;
  const url = isUrl(urlOrId) ? urlOrId : `https://www.youtube.com/watch?v=${urlOrId}`;
  const { stdout } = await tool(exec, 'yt-dlp', ['-J', '--no-playlist', '--no-warnings', url], 120_000);
  try {
    return JSON.parse(stdout) as Info;
  } catch {
    throw new StudyError(`yt-dlp returned unreadable data for ${url}`);
  }
}

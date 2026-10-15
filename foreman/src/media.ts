// Media agents hand the user (`show_media`): an image or a video file, or an http(s) URL to one.
// The mod has no video decoder, so a video becomes a short flipbook here: ffmpeg samples it to at
// most VIDEO_MAX_FRAMES small JPEG frames (and the mod loops them at `fps`). The original file is
// kept so `media.open` can play it, with sound, in the OS player. Images other than PNG/JPEG
// (webp, gif, heic...) and oversized ones go through ffmpeg too. Memory only, never persisted.
import { execFile, spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import type { Media } from './protocol.js';

const run = promisify(execFile);

export const IMAGE_EXT = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.heic', '.avif', '.tif', '.tiff']);
export const VIDEO_EXT = new Set(['.mp4', '.mov', '.m4v', '.webm', '.mkv', '.avi']);
/** Frames a video is sampled to (the mod keeps them all as textures). */
export const VIDEO_MAX_FRAMES = 120;
export const VIDEO_MAX_FPS = 12;
/** Only the first seconds of a long video are sampled; "Open in player" plays the whole thing. */
export const VIDEO_MAX_SECONDS = 30;
/** Largest image sent as is (base64 grows it by a third); bigger ones are re-encoded smaller. */
const IMAGE_RAW_MAX = 2_000_000;
const DOWNLOAD_MAX = 300 * 1024 * 1024;
/** Items kept for `media.open` and the snapshot. */
export const MEDIA_KEEP = 12;
export const MEDIA_SNAPSHOT = 4;

export class MediaError extends Error {}

/** How much of a video becomes frames (defaults: VIDEO_MAX_SECONDS, VIDEO_MAX_FRAMES). */
export interface VideoLimits {
  maxSeconds?: number;
  maxFrames?: number;
}

interface Kept {
  item: Media;
  file: string;
}

export class MediaHub {
  private items: Kept[] = [];
  private seq = 0;
  private dir: string;

  constructor(
    private emit: (item: Media) => void,
    private now: () => number = Date.now,
  ) {
    this.dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agentcraft-media-'));
  }

  /** Newest items for a (re)connecting client, oldest first. */
  recent(): Media[] {
    return this.items.slice(-MEDIA_SNAPSHOT).map((k) => k.item);
  }

  fileOf(mediaId: string): string | undefined {
    return this.items.find((k) => k.item.id === mediaId)?.file;
  }

  /** Read a file or URL, turn it into frames and broadcast it. */
  async show(agentId: string, source: string, caption?: string, limits: VideoLimits = {}): Promise<Media> {
    const file = /^https?:\/\//i.test(source) ? await this.download(source) : source;
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) throw new MediaError(`no file at ${file}`);
    const ext = path.extname(file).toLowerCase();
    const id = `m${++this.seq}`;
    const base = { id, agentId, name: path.basename(file), at: this.now(), ...(caption?.trim() ? { caption: caption.trim() } : {}) };
    let item: Media;
    if (VIDEO_EXT.has(ext)) item = { ...base, kind: 'video', ...(await this.videoFrames(file, id, limits)) };
    else if (IMAGE_EXT.has(ext)) item = { ...base, kind: 'image', ...(await this.imageFrame(file, ext, id)) };
    else throw new MediaError(`not an image or video: ${path.basename(file)} (images: ${[...IMAGE_EXT].join(' ')}; videos: ${[...VIDEO_EXT].join(' ')})`);
    this.items = [...this.items, { item, file }].slice(-MEDIA_KEEP);
    this.emit(item);
    return item;
  }

  /** Open the original in the OS default app (videos play with sound there). */
  open(mediaId: string): void {
    const file = this.fileOf(mediaId);
    if (!file) throw new MediaError(`no media "${mediaId}" (only the last ${MEDIA_KEEP} are kept)`);
    const [cmd, args] = process.platform === 'darwin' ? ['open', [file]] : process.platform === 'win32' ? ['cmd', ['/c', 'start', '""', file]] : ['xdg-open', [file]];
    spawn(cmd, args as string[], { detached: true, stdio: 'ignore' }).on('error', () => undefined).unref();
  }

  close(): void {
    fs.rmSync(this.dir, { recursive: true, force: true });
  }

  private async download(url: string): Promise<string> {
    const res = await fetch(url).catch((e: Error) => {
      throw new MediaError(`download failed: ${e.message}`);
    });
    if (!res.ok) throw new MediaError(`download failed: HTTP ${res.status}`);
    const len = Number(res.headers.get('content-length') ?? 0);
    if (len > DOWNLOAD_MAX) throw new MediaError(`file too large (${Math.round(len / 1e6)} MB)`);
    const buf = Buffer.from(await res.arrayBuffer());
    if (buf.length > DOWNLOAD_MAX) throw new MediaError(`file too large (${Math.round(buf.length / 1e6)} MB)`);
    const fromUrl = path.extname(new URL(url).pathname).toLowerCase();
    const ext = IMAGE_EXT.has(fromUrl) || VIDEO_EXT.has(fromUrl) ? fromUrl : extFromType(res.headers.get('content-type') ?? '');
    const file = path.join(this.dir, `download-${++this.seq}${ext}`);
    fs.writeFileSync(file, buf);
    return file;
  }

  private async imageFrame(file: string, ext: string, id: string): Promise<Pick<Media, 'mime' | 'frames'>> {
    const size = fs.statSync(file).size;
    if ((ext === '.png' || ext === '.jpg' || ext === '.jpeg') && size <= IMAGE_RAW_MAX) {
      return { mime: ext === '.png' ? 'image/png' : 'image/jpeg', frames: [fs.readFileSync(file).toString('base64')] };
    }
    const out = path.join(this.dir, `${id}.jpg`);
    await ffmpeg(['-i', file, '-frames:v', '1', '-vf', "scale='min(1600,iw)':-2", '-q:v', '3', out]);
    return { mime: 'image/jpeg', frames: [fs.readFileSync(out).toString('base64')] };
  }

  private async videoFrames(file: string, id: string, limits: VideoLimits): Promise<Pick<Media, 'mime' | 'frames' | 'fps' | 'durationMs' | 'hasAudio'>> {
    const probe = await probeVideo(file);
    const maxSeconds = limits.maxSeconds ?? VIDEO_MAX_SECONDS;
    const maxFrames = limits.maxFrames ?? VIDEO_MAX_FRAMES;
    const seconds = Math.min(probe.seconds || maxSeconds, maxSeconds);
    const fps = Math.max(1, Math.min(VIDEO_MAX_FPS, Math.floor(maxFrames / Math.max(seconds, 1))));
    const outDir = path.join(this.dir, id);
    fs.mkdirSync(outDir);
    await ffmpeg(['-t', String(seconds), '-i', file, '-vf', `fps=${fps},scale='min(640,iw)':-2`, '-q:v', '5', '-frames:v', String(maxFrames), path.join(outDir, 'f%04d.jpg')]);
    const frames = fs
      .readdirSync(outDir)
      .filter((f) => f.endsWith('.jpg'))
      .sort()
      .map((f) => fs.readFileSync(path.join(outDir, f)).toString('base64'));
    fs.rmSync(outDir, { recursive: true, force: true });
    if (!frames.length) throw new MediaError(`ffmpeg read no frames from ${path.basename(file)}`);
    return { mime: 'image/jpeg', frames, fps, ...(probe.seconds ? { durationMs: Math.round(probe.seconds * 1000) } : {}), hasAudio: probe.hasAudio };
  }
}

function extFromType(type: string): string {
  const t = type.split(';')[0]!.trim().toLowerCase();
  const map: Record<string, string> = { 'image/png': '.png', 'image/jpeg': '.jpg', 'image/webp': '.webp', 'image/gif': '.gif', 'video/mp4': '.mp4', 'video/quicktime': '.mov', 'video/webm': '.webm' };
  return map[t] ?? '';
}

async function ffmpeg(args: string[]): Promise<void> {
  try {
    await run('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args], { timeout: 120_000, maxBuffer: 4 * 1024 * 1024 });
  } catch (e) {
    const err = e as NodeJS.ErrnoException & { stderr?: string };
    if (err.code === 'ENOENT') throw new MediaError('ffmpeg is not installed (brew install ffmpeg); it is needed for videos and webp/gif images');
    throw new MediaError(`ffmpeg failed: ${(err.stderr ?? err.message).trim().split('\n').slice(-2).join(' ')}`);
  }
}

async function probeVideo(file: string): Promise<{ seconds: number; hasAudio: boolean }> {
  try {
    const { stdout } = await run('ffprobe', ['-v', 'error', '-show_entries', 'format=duration:stream=codec_type', '-of', 'json', file], { timeout: 30_000 });
    const j = JSON.parse(stdout) as { format?: { duration?: string }; streams?: Array<{ codec_type?: string }> };
    return { seconds: Number(j.format?.duration ?? 0) || 0, hasAudio: (j.streams ?? []).some((s) => s.codec_type === 'audio') };
  } catch {
    return { seconds: 0, hasAudio: false };
  }
}

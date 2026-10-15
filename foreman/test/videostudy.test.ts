// Video study: vtt cleanup, sheet graph, studyVideo / scanChannel with a mocked exec, plus one real ffmpeg run.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { labelFilters, pickSubtitle, renderBreakdown, scanChannel, sheetFilterGraph, studyVideo, StudyError, vttToTranscript, type Exec } from '../src/videostudy.js';
import { rmrf, tempDir } from './helpers.js';

const cleanup: Array<() => void> = [];
afterEach(() => {
  for (const f of cleanup.splice(0)) f();
});
function scratch(): string {
  const d = tempDir('ac-study-');
  cleanup.push(() => rmrf(d));
  return d;
}

const AUTO_VTT = `WEBVTT
Kind: captions
Language: en

00:00:01.000 --> 00:00:03.000 align:start position:0%
all right so<00:00:01.500><c> here</c><00:00:02.000><c> we</c><00:00:02.500><c> are</c>

00:00:03.000 --> 00:00:03.010 align:start position:0%
all right so here we are

00:00:03.010 --> 00:00:05.000 align:start position:0%
all right so here we are
in front of the &amp; elephants

00:00:05.000 --> 00:00:05.010 align:start position:0%
in front of the &amp; elephants

00:01:05.000 --> 00:01:07.000
in front of the &amp; elephants
that is cool
`;

describe('vttToTranscript', () => {
  it('drops rolling repeats, inline tags and entities', () => {
    expect(vttToTranscript(AUTO_VTT).split('\n')).toEqual([
      '[00:01] all right so here we are',
      '[00:03] in front of the & elephants',
      '[01:05] that is cool',
    ]);
  });
  it('is empty without cues', () => expect(vttToTranscript('WEBVTT\n')).toBe(''));
});

describe('pickSubtitle', () => {
  it('prefers english, then portuguese', () => {
    expect(pickSubtitle(['source.mp4', 'source.pt.vtt', 'source.en-orig.vtt', 'source.en.vtt'])).toBe('source.en.vtt');
    expect(pickSubtitle(['source.pt-BR.vtt', 'source.pt.vtt'])).toBe('source.pt.vtt');
    expect(pickSubtitle(['source.mp4'])).toBeUndefined();
  });
});

describe('sheet graph', () => {
  it('stamps every cell and stacks 4x4', () => {
    const g = sheetFilterGraph(Array.from({ length: 16 }, (_, i) => i * 0.5), 392, 220);
    expect(g.match(/hstack=inputs=4/g)).toHaveLength(4);
    expect(g).toContain('vstack=inputs=4[out]');
    expect(g).not.toContain('drawtext');
    expect(labelFilters('00:03.5').length).toBeGreaterThan(10);
  });
  it('handles a short last sheet', () => {
    const g = sheetFilterGraph([1, 2, 3, 4, 5], 392, 220);
    expect(g).toContain('hstack=inputs=4');
    expect(g).toContain('[c4]pad=1568:220:0:0:color=black[r1]');
    expect(g).toContain('vstack=inputs=2[out]');
  });
});

/** A fake yt-dlp/ffprobe/ffmpeg: writes the files the real ones would. */
function fakeExec(log: string[][], opts: { subs?: boolean; cuts?: number[] } = {}): Exec {
  return async (cmd, args) => {
    log.push([cmd, ...args]);
    if (cmd === 'yt-dlp') {
      const out = args[args.indexOf('-o') + 1]!.replace('%(ext)s', '');
      fs.writeFileSync(out + 'mp4', 'x');
      fs.writeFileSync(out + 'info.json', JSON.stringify({ id: 'abc123', title: 'Hello', channel: 'Chan', duration: 10, view_count: 1234, like_count: 5, upload_date: '20261001', tags: ['a'] }));
      if (opts.subs) fs.writeFileSync(out + 'en.vtt', AUTO_VTT);
      return { stdout: '', stderr: '' };
    }
    if (cmd === 'ffprobe') return { stdout: JSON.stringify({ format: { duration: '10.0' }, streams: [{ codec_type: 'video', width: 1080, height: 1920 }, { codec_type: 'audio' }] }), stderr: '' };
    if (args.includes('volumedetect')) return { stdout: '', stderr: '[Parsed_volumedetect_0] mean_volume: -17.3 dB' };
    const vf = args[args.indexOf('-vf') + 1] ?? '';
    if (vf.includes('showinfo')) return { stdout: '', stderr: (opts.cuts ?? [2, 6]).map((t) => `[Parsed_showinfo_2] n: 0 pts: 1 pts_time:${t}`).join('\n') };
    const last = args[args.length - 1]!;
    if (last.includes('%05d')) {
      for (let i = 1; i <= 20; i++) fs.writeFileSync(last.replace('%05d', String(i).padStart(5, '0')), 'jpg'); // 10 s at 2 fps
    } else fs.writeFileSync(last, 'jpg');
    return { stdout: '', stderr: '' };
  };
}

describe('studyVideo (mocked tools)', () => {
  it('downloads a URL, names frames by time, builds sheets, transcript and breakdown', async () => {
    const dir = scratch();
    const log: string[][] = [];
    const r = await studyVideo('https://www.youtube.com/shorts/abc123', dir, {}, { exec: fakeExec(log, { subs: true, cuts: [2.2, 6.3] }) });
    expect(r.title).toBe('Hello');
    expect(r.channel).toBe('Chan');
    expect(r.views).toBe(1234);
    expect(r.uploadDate).toBe('2026-10-01');
    expect(r.youtubeId).toBe('abc123');
    expect(r.sceneCuts).toEqual([2.2, 6.3]);
    expect(r.frames).toHaveLength(22); // 20 sampled + 2 cut frames
    expect(path.basename(r.frames[0]!.file)).toBe('f0001_00m00.00s.jpg');
    expect(r.frames.map((f) => f.t)).toEqual([...r.frames.map((f) => f.t)].sort((a, b) => a - b));
    expect(r.sheets).toHaveLength(2); // 16 + 6
    expect(r.pacing).toEqual({ cutsPerMinute: 12, avgShotSec: 3.33, firstCutSec: 2.2 });
    expect(r.audio).toEqual({ hasAudio: true, meanVolumeDb: -17.3 });
    expect(fs.readFileSync(r.transcriptFile!, 'utf8')).toContain('[00:01] all right so here we are');
    expect(fs.existsSync(path.join(dir, 'breakdown.md'))).toBe(true);
    expect(fs.existsSync(path.join(dir, 'meta.json'))).toBe(true);
    expect(fs.existsSync(path.join(dir, '.tmp'))).toBe(false);
    const ytdlp = log.find((c) => c[0] === 'yt-dlp')!;
    expect(ytdlp).toContain('--write-auto-subs');
    expect(ytdlp).toContain('--no-playlist');
    expect(ytdlp.at(-1)).toBe('https://www.youtube.com/shorts/abc123');
    expect(ytdlp[ytdlp.indexOf('--sub-langs') + 1]).not.toContain('*');
  });

  it('honours fps / window / maxFrames and can skip scene cuts', async () => {
    const dir = scratch();
    const log: string[][] = [];
    const file = path.join(scratch(), 'clip.mp4');
    fs.writeFileSync(file, 'x');
    const r = await studyVideo(file, dir, { fps: 1, start: 2, end: 8, sceneCuts: false, maxFrames: 5 }, { exec: fakeExec(log) });
    const sample = log.find((c) => c[0] === 'ffmpeg' && c.includes('-frames:v') && c.at(-1)!.includes('%05d'))!;
    expect(sample[sample.indexOf('-ss') + 1]).toBe('2');
    expect(sample[sample.indexOf('-t') + 1]).toBe('6');
    expect(sample[sample.indexOf('-frames:v') + 1]).toBe('5');
    expect(sample[sample.indexOf('-vf') + 1]).toMatch(/^fps=0\.8333,/); // 5 frames over 6 s
    expect(log.some((c) => c.join(' ').includes('showinfo'))).toBe(false);
    expect(r.sceneCuts).toEqual([]);
    expect(r.pacing.firstCutSec).toBeUndefined();
    expect(log.some((c) => c[0] === 'yt-dlp')).toBe(false);
  });

  it('retries yt-dlp without subtitles when the subtitle fetch kills the run', async () => {
    const dir = scratch();
    const inner = fakeExec([]);
    let calls = 0;
    const exec: Exec = async (cmd, args) => {
      if (cmd === 'yt-dlp' && calls++ === 0) throw Object.assign(new Error('x'), { stderr: 'ERROR: Unable to download video subtitles: HTTP Error 429' });
      return inner(cmd, args);
    };
    const r = await studyVideo('https://youtu.be/abc123', dir, {}, { exec });
    expect(calls).toBe(2);
    expect(r.frames.length).toBeGreaterThan(0);
    expect(r.transcriptFile).toBeUndefined();
  });

  it('explains missing tools and bad input', async () => {
    const enoent: Exec = async () => {
      throw Object.assign(new Error('spawn yt-dlp ENOENT'), { code: 'ENOENT' });
    };
    await expect(studyVideo('https://youtu.be/x', scratch(), {}, { exec: enoent })).rejects.toThrow(/brew install yt-dlp/);
    const file = path.join(scratch(), 'a.mp4');
    fs.writeFileSync(file, 'x');
    await expect(studyVideo(file, scratch(), {}, { exec: enoent })).rejects.toThrow(/brew install ffmpeg/);
    await expect(studyVideo('/nope/missing.mp4', scratch(), {}, { exec: enoent })).rejects.toBeInstanceOf(StudyError);
    await expect(studyVideo(file, scratch(), { start: 5, end: 2 }, { exec: enoent })).rejects.toThrow(/end must be after start/);
  });
});

describe('renderBreakdown', () => {
  it('lists stats, pacing, cuts and files', () => {
    const md = renderBreakdown({
      dir: '/d', title: 'T', channel: 'C', durationSec: 65, views: 1000, frames: [{ file: '/d/frames/f0001.jpg', t: 0 }], sheets: ['/d/sheets/sheet01.jpg'],
      sceneCuts: [3.3, 9.9], pacing: { cutsPerMinute: 1.8, avgShotSec: 21, firstCutSec: 3.3 }, audio: { hasAudio: false },
    });
    expect(md).toContain('# T');
    expect(md).toContain('views: 1,000');
    expect(md).toContain('00:03.3  00:09.9');
    expect(md).toContain('audio: none');
    expect(md).toContain('sheets/sheet01.jpg');
    expect(md).toContain('No subtitles');
  });
});

describe('scanChannel', () => {
  const tab = (entries: object[], extra: object = {}): string => JSON.stringify({ channel: 'Chan', channel_id: 'UC1', entries, ...extra });
  it('merges Videos + Shorts, sorts by views, normalises the url', async () => {
    const urls: string[] = [];
    const exec: Exec = async (_c, args) => {
      const u = args.at(-1)!;
      urls.push(u);
      if (u.endsWith('/videos')) return { stdout: tab([{ id: 'L1', title: 'long', view_count: 500, duration: 600 }, { id: 'L2', title: 'long2', view_count: null }], { channel_follower_count: 4200 }), stderr: '' };
      return { stdout: tab([{ id: 'S1', title: 'short', view_count: 9000 }, { id: 'L1', title: 'dup', view_count: 1 }]), stderr: '' };
    };
    const scan = await scanChannel('https://www.youtube.com/@chan/videos?x=1', 30, { exec });
    expect(urls.sort()).toEqual(['https://www.youtube.com/@chan/shorts', 'https://www.youtube.com/@chan/videos']);
    expect(scan.subscriberCount).toBe(4200);
    expect(scan.channel).toBe('Chan');
    expect(scan.videos.map((v) => v.youtubeId)).toEqual(['S1', 'L1', 'L2']);
    expect(scan.videos[0]).toMatchObject({ format: 'short', url: 'https://www.youtube.com/shorts/S1', views: 9000 });
  });
  it('survives a channel without a Shorts tab, fails when both tabs fail', async () => {
    const noShorts: Exec = async (_c, args) => {
      if (args.at(-1)!.endsWith('/shorts')) throw Object.assign(new Error('x'), { stderr: 'ERROR: This channel does not have a shorts tab' });
      return { stdout: tab([{ id: 'L1', title: 'a', view_count: 1 }]), stderr: '' };
    };
    expect((await scanChannel('https://www.youtube.com/@c', 5, { exec: noShorts })).videos).toHaveLength(1);
    const broken: Exec = async () => {
      throw Object.assign(new Error('x'), { stderr: 'boom' });
    };
    await expect(scanChannel('https://www.youtube.com/@c', 5, { exec: broken })).rejects.toBeInstanceOf(StudyError);
    await expect(scanChannel('@c', 5, { exec: broken })).rejects.toThrow(/channel URL/);
  });
});

const hasFfmpeg = (() => {
  try {
    execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
})();

describe.skipIf(!hasFfmpeg)('studyVideo (real ffmpeg)', () => {
  it('studies a generated clip with a hard cut and produces real sheets', async () => {
    const work = scratch();
    const clip = path.join(work, 'clip.mp4');
    execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=size=320x568:rate=10:duration=3', '-f', 'lavfi', '-i', 'color=c=red:size=320x568:rate=10:duration=3', '-filter_complex', '[0:v][1:v]concat=n=2:v=1[v]', '-map', '[v]', '-pix_fmt', 'yuv420p', clip]);
    const r = await studyVideo(clip, path.join(work, 'out'), { fps: 2 });
    expect(r.durationSec).toBeCloseTo(6, 0);
    expect(r.sceneCuts.length).toBeGreaterThanOrEqual(1);
    expect(r.sceneCuts[0]).toBeCloseTo(3, 0);
    expect(r.sheets).toHaveLength(Math.ceil(r.frames.length / 16));
    for (const s of r.sheets) expect(fs.statSync(s).size).toBeGreaterThan(1000);
    expect(r.audio?.hasAudio).toBe(false);
  }, 60_000);
});

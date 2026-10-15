import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { extractImages } from '../src/agents/claude/images.js';
import { StreamMapper } from '../src/agents/claude/stream.js';
import { AgentScreen, AgentScreenMsg, parseServerMessage, ServerMessage } from '../src/protocol.js';
import { SCREEN_FRESH_MS, SCREEN_MAX_B64, ScreenHub } from '../src/screens.js';
import { makeForeman, rmrf, tempDir } from './helpers.js';

const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';
const img = (data = PNG, media_type = 'image/png') => ({ type: 'image', source: { type: 'base64', media_type, data } });

describe('extractImages', () => {
  it('finds base64 image blocks and ignores text', () => {
    expect(extractImages([{ type: 'text', text: 'hi' }, img(), img('QUJD', 'image/jpeg')])).toEqual([
      { mime: 'image/png', data: PNG },
      { mime: 'image/jpeg', data: 'QUJD' },
    ]);
  });
  it('ignores strings, url sources, non-image mimes and empty data', () => {
    expect(extractImages('plain')).toEqual([]);
    expect(extractImages([{ type: 'image', source: { type: 'url', url: 'http://x' } }])).toEqual([]);
    expect(extractImages([img(PNG, 'text/plain'), img(''), null, 5])).toEqual([]);
  });
  it('skips frames over the size cap', () => {
    expect(extractImages([img('A'.repeat(SCREEN_MAX_B64 + 1))])).toEqual([]);
    expect(extractImages([img('A'.repeat(SCREEN_MAX_B64))])).toHaveLength(1);
  });
});

describe('ScreenHub', () => {
  let t = 1_000_000;
  const sent: AgentScreen[] = [];
  const mk = () => new ScreenHub((f) => sent.push(f), () => t, 1500);
  const f = (data: string, at = t) => ({ mime: 'image/png', data, tool: 'mcp__x__shot', at });
  beforeEach(() => {
    vi.useFakeTimers();
    sent.length = 0;
  });
  afterEach(() => vi.useRealTimers());

  it('sends the first frame at once, collapses a burst to the newest', () => {
    const hub = mk();
    hub.push('kit', f('a'));
    hub.push('kit', f('b'));
    hub.push('kit', f('c'));
    expect(sent.map((s) => s.data)).toEqual(['a']);
    t += 1500;
    vi.advanceTimersByTime(1500);
    expect(sent.map((s) => s.data)).toEqual(['a', 'c']);
    hub.close();
  });
  it('throttles per agent, not globally', () => {
    const hub = mk();
    hub.push('kit', f('a'));
    hub.push('juniper', f('b'));
    expect(sent.map((s) => s.agentId)).toEqual(['kit', 'juniper']);
    hub.close();
  });
  it('rejects oversized and empty frames', () => {
    const hub = mk();
    expect(hub.push('kit', f('A'.repeat(SCREEN_MAX_B64 + 1)))).toBe(false);
    expect(hub.push('kit', f(''))).toBe(false);
    expect(sent).toEqual([]);
    hub.close();
  });
  it('keeps only fresh latest frames for the initial sync', () => {
    const hub = mk();
    hub.push('kit', f('a'));
    expect(hub.fresh().map((s) => s.data)).toEqual(['a']);
    t += SCREEN_FRESH_MS + 1;
    expect(hub.fresh()).toEqual([]);
    hub.close();
  });
  it('close drops a pending frame', () => {
    const hub = mk();
    hub.push('kit', f('a'));
    hub.push('kit', f('b'));
    hub.close();
    vi.advanceTimersByTime(5000);
    expect(sent.map((s) => s.data)).toEqual(['a']);
  });
});

describe('agent.screen protocol', () => {
  const msg = { v: 1, type: 'agent.screen', agentId: 'kit', mime: 'image/png', data: PNG, tool: 'mcp__x__shot', at: 1 };
  it('parses and is part of the union', () => {
    expect(AgentScreenMsg.safeParse(msg).success).toBe(true);
    expect(ServerMessage.safeParse(msg).success).toBe(true);
    expect(parseServerMessage(JSON.stringify(msg)).ok).toBe(true);
  });
  it('rejects missing fields', () => {
    const { data: _d, ...rest } = msg;
    expect(AgentScreenMsg.safeParse(rest).success).toBe(false);
    expect(AgentScreenMsg.safeParse({ ...msg, at: 'now' }).success).toBe(false);
  });
});

describe('stream -> screen', () => {
  let home: string;
  beforeEach(() => {
    home = tempDir();
  });
  afterEach(() => rmrf(home));

  const m = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, ...o }) as unknown as SDKMessage;

  it('broadcasts a screenshot from an MCP tool_result, keeps text logging, and replays it in the snapshot', async () => {
    const h = makeForeman(home);
    const agentId = h.fm.agents()[0]!.id;
    const sm = new StreamMapper(h.fm, agentId, home, 'worker');
    sm.handle(m({ type: 'assistant', message: { content: [{ type: 'tool_use', id: 't1', name: 'mcp__claude-in-chrome__computer', input: { action: 'screenshot' } }] } }));
    sm.handle(m({ type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: 't1', content: [{ type: 'text', text: 'Successfully took a screenshot' }, img()] }] } }));
    h.fm.flushLogs();
    const screens = h.events.filter((e) => e.type === 'agent.screen');
    expect(screens).toHaveLength(1);
    expect(screens[0]).toMatchObject({ agentId, mime: 'image/png', data: PNG, tool: 'mcp__claude-in-chrome__computer' });
    expect(h.events.some((e) => e.type === 'agent.log' && e.entries.some((x) => x.kind === 'result' && x.text.includes('Successfully took')))).toBe(true);
    const snap = h.fm.snapshot();
    expect(snap.type === 'snapshot' && snap.screens?.map((s) => s.agentId)).toEqual([agentId]);
    await h.fm.close();
  });

  it('does not emit for errors or text-only results', async () => {
    const h = makeForeman(home);
    const agentId = h.fm.agents()[0]!.id;
    const sm = new StreamMapper(h.fm, agentId, home, 'worker');
    sm.handle(m({ type: 'assistant', message: { content: [{ type: 'tool_use', id: 't1', name: 'mcp__x__shot', input: {} }] } }));
    sm.handle(m({ type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: 't1', is_error: true, content: [img()] }] } }));
    sm.handle(m({ type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: 't1', content: 'just text' }] } }));
    expect(h.events.some((e) => e.type === 'agent.screen')).toBe(false);
    await h.fm.close();
  });
});

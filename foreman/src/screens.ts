// Live "screen" frames (screenshots from MCP tools) per agent: throttled, newest wins, kept in
// memory only (never in state.json). The mod shows them on the agent's desk monitor.
import type { AgentScreen } from './protocol.js';

/** Largest accepted base64 payload per frame (about 2.2 MB of image bytes). */
export const SCREEN_MAX_B64 = 3_000_000;
/** At most one frame per agent per interval; frames in between are collapsed to the newest. */
export const SCREEN_MIN_INTERVAL_MS = 1500;
/** Frames older than this are not replayed to a (re)connecting client. */
export const SCREEN_FRESH_MS = 90_000;

export type ScreenFrame = Omit<AgentScreen, 'agentId'>;

export class ScreenHub {
  private latest = new Map<string, AgentScreen>();
  private lastEmit = new Map<string, number>();
  private pending = new Map<string, AgentScreen>();
  private timers = new Map<string, NodeJS.Timeout>();
  private closed = false;

  constructor(
    private emit: (frame: AgentScreen) => void,
    private now: () => number = Date.now,
    private minIntervalMs = SCREEN_MIN_INTERVAL_MS,
  ) {}

  /** Offer a frame. Returns false when it was rejected (too big / empty). */
  push(agentId: string, frame: ScreenFrame): boolean {
    if (this.closed || !frame.data || frame.data.length > SCREEN_MAX_B64) return false;
    const full: AgentScreen = { agentId, ...frame };
    const wait = this.minIntervalMs - (this.now() - (this.lastEmit.get(agentId) ?? -Infinity));
    if (wait <= 0) {
      this.send(full);
      return true;
    }
    this.pending.set(agentId, full); // newest wins
    if (!this.timers.has(agentId)) {
      const t = setTimeout(() => this.flush(agentId), wait);
      t.unref?.();
      this.timers.set(agentId, t);
    }
    return true;
  }

  private flush(agentId: string): void {
    this.timers.delete(agentId);
    const f = this.pending.get(agentId);
    this.pending.delete(agentId);
    if (f && !this.closed) this.send(f);
  }

  private send(f: AgentScreen): void {
    this.lastEmit.set(f.agentId, this.now());
    this.latest.set(f.agentId, f);
    this.emit(f);
  }

  /** Latest sent frame per agent that is still fresh, for the initial sync. */
  fresh(): AgentScreen[] {
    const t = this.now();
    return [...this.latest.values()].filter((f) => t - f.at <= SCREEN_FRESH_MS);
  }

  close(): void {
    this.closed = true;
    for (const t of this.timers.values()) clearTimeout(t);
    this.timers.clear();
    this.pending.clear();
  }
}

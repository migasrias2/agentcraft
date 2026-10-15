import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { loadConfig, PROJECT_ROOT, type Config } from '../src/config.js';
import { silentLogger } from '../src/context.js';
import { Foreman, type ForemanOptions } from '../src/foreman.js';
import { Notifier } from '../src/notifier.js';
import type { Outbound } from '../src/protocol.js';

/** The user name every test Foreman runs with (the default would be the OS account name). */
export const TEST_USER = 'Alex';

export function tempDir(prefix = 'ac-test-'): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

export function rmrf(p: string): void {
  try {
    fs.rmSync(p, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  } catch {
    /* windows may hold handles briefly */
  }
}

let demoMod: { createDemo: (o: { dir: string; force?: boolean; quiet?: boolean }) => { dir: string; head: string } } | undefined;

/** Create the pocket-notes demo repo in a fresh temp dir. */
export async function demoRepo(): Promise<string> {
  demoMod ??= (await import(pathToFileURL(path.join(PROJECT_ROOT, 'sandbox', 'create-demo.mjs')).href)) as typeof demoMod;
  const dir = path.join(tempDir('ac-demo-'), 'demo-app');
  demoMod!.createDemo({ dir, quiet: true });
  return dir;
}

export function testConfig(home: string, args: string[] = []): Config {
  const cfg = loadConfig(['--home', home, '--no-notify', '--user-name', TEST_USER, ...args], {});
  return cfg;
}

export interface Harness {
  fm: Foreman;
  cfg: Config;
  home: string;
  events: Outbound[];
  notifier: Notifier;
  toasts: Array<{ title: string; body: string }>;
}

export function makeForeman(home: string, args: string[] = [], extra: Partial<ForemanOptions> = {}): Harness {
  const cfg = testConfig(home, args);
  const toasts: Array<{ title: string; body: string }> = [];
  const notifier = new Notifier({
    enabled: true,
    minIntervalMs: 0,
    bell: false,
    spawnToast: async (title, body) => {
      toasts.push({ title, body });
      return true;
    },
  });
  const fm = new Foreman({ config: cfg, logger: silentLogger, notifier, ...extra });
  const events: Outbound[] = [];
  fm.subscribe((m) => events.push(m));
  return { fm, cfg, home, events, notifier, toasts };
}

export async function until(cond: () => boolean, timeoutMs = 20_000, stepMs = 25): Promise<void> {
  const t0 = Date.now();
  while (!cond()) {
    if (Date.now() - t0 > timeoutMs) throw new Error(`timed out after ${timeoutMs}ms`);
    await new Promise((r) => setTimeout(r, stepMs));
  }
}

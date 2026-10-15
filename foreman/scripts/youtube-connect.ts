// One-time: connect a YouTube channel's analytics to AgentCraft.   npm run youtube:connect
// Opens Google's consent page, catches the redirect on 127.0.0.1 (random port, PKCE + state), and saves the token
// under ~/.agentcraft/youtube/tokens/<channelId>.json (mode 600). Run once per channel; pick the brand account.
import { spawn } from 'node:child_process';
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { buildAuthUrl, connectWithCode, makePkce, readClientSecret, YouTubeError } from '../src/youtube.js';

const TIMEOUT_MS = 5 * 60_000;

function openBrowser(url: string): void {
  const [cmd, args] = process.platform === 'darwin' ? ['open', [url]] : process.platform === 'win32' ? ['cmd', ['/c', 'start', '""', url]] : ['xdg-open', [url]];
  spawn(cmd, args as string[], { detached: true, stdio: 'ignore' }).on('error', () => undefined).unref();
}

const page = (msg: string): string => `<!doctype html><meta charset="utf-8"><title>AgentCraft</title><body style="font:16px system-ui;padding:3rem">${msg}</body>`;

async function main(): Promise<void> {
  const client = readClientSecret(); // throws with the setup steps when the file is missing
  const pkce = makePkce();

  const code = await new Promise<string>((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const u = new URL(req.url ?? '/', 'http://127.0.0.1');
      if (u.pathname !== '/') {
        res.writeHead(404).end();
        return;
      }
      const err = u.searchParams.get('error');
      const got = u.searchParams.get('code');
      const ok = !err && !!got && u.searchParams.get('state') === pkce.state;
      res.writeHead(ok ? 200 : 400, { 'content-type': 'text/html; charset=utf-8' }).end(page(ok ? 'Connected. You can close this tab and go back to the terminal.' : 'Something went wrong. Go back to the terminal.'));
      clearTimeout(timer);
      server.close();
      if (ok) resolve(got);
      else reject(new YouTubeError(err ? `Google said: ${err}` : 'the redirect had no code or a wrong state'));
    });
    const timer = setTimeout(() => {
      server.close();
      reject(new YouTubeError('timed out waiting for the browser (5 minutes)'));
    }, TIMEOUT_MS);
    server.listen(0, '127.0.0.1', () => {
      const redirectUri = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
      const url = buildAuthUrl(client, redirectUri, pkce);
      process.stdout.write(`Opening your browser. If nothing opens, visit:\n${url}\n\nWaiting for you to approve access...\n`);
      openBrowser(url);
      redirectRef = redirectUri;
    });
  });

  const { channelId, title } = await connectWithCode(code, pkce, redirectRef);
  process.stdout.write(`Connected "${title}" (${channelId}). Tokens saved; analytics sync can use it now.\n`);
}

let redirectRef = '';

main().catch((e) => {
  process.stderr.write(`${e instanceof Error ? e.message : String(e)}\n`);
  process.exit(1);
});

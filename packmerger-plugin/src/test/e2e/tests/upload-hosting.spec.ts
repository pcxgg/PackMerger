import { readFileSync, writeFileSync } from 'node:fs';
import { expect, test } from '@plugwright/runner';
import {
  PACK_URL, PLUGIN_DIR, SELF_HOST_PORT, consoleCommand, downloadPack, serverLogMark,
} from './support/pack.js';

// Self-host HTTP semantics (HEAD, ETag/304, Range, exact paths), the host resolution and
// reachability verification reported by /pm status, and upload.fallback.

const BASE = `http://localhost:${SELF_HOST_PORT}`;
const CONFIG = `${PLUGIN_DIR}config.yml`;

test('HEAD returns the pack headers without a body', async () => {
  const pack = await downloadPack();
  const res = await fetch(PACK_URL, { method: 'HEAD' });
  expect(res.status).toBe(200);
  expect(res.headers.get('content-length')).toBe(String(pack.bytes.length));
  expect(res.headers.get('etag')).toBe(`"${pack.sha1}"`);
  expect(res.headers.get('accept-ranges')).toBe('bytes');
  expect(res.headers.get('content-type')).toBe('application/zip');
  expect((await res.arrayBuffer()).byteLength).toBe(0);
});

test('If-None-Match with the current SHA-1 ETag answers 304; a stale one gets the pack', async () => {
  const pack = await downloadPack();
  const notModified = await fetch(PACK_URL, { headers: { 'If-None-Match': `"${pack.sha1}"` } });
  expect(notModified.status).toBe(304);
  expect((await notModified.arrayBuffer()).byteLength).toBe(0);

  const stale = await fetch(PACK_URL, { headers: { 'If-None-Match': '"0000000000000000000000000000000000000000"' } });
  expect(stale.status).toBe(200);
  expect(Buffer.from(await stale.arrayBuffer()).equals(pack.bytes)).toBe(true);
});

test('a Range request returns 206 with exactly the requested bytes', async () => {
  const pack = await downloadPack();
  const end = Math.min(1023, pack.bytes.length - 1);
  const res = await fetch(PACK_URL, { headers: { Range: `bytes=0-${end}` } });
  expect(res.status).toBe(206);
  expect(res.headers.get('content-range')).toBe(`bytes 0-${end}/${pack.bytes.length}`);
  const body = Buffer.from(await res.arrayBuffer());
  expect(body.equals(pack.bytes.subarray(0, end + 1))).toBe(true);

  const tooFar = await fetch(PACK_URL, { headers: { Range: `bytes=${pack.bytes.length}-` } });
  expect(tooFar.status).toBe(416);
});

test('only the exact pack path is served', async () => {
  expect((await fetch(`${BASE}/packfoo`)).status).toBe(404);
  expect((await fetch(`${BASE}/`)).status).toBe(404);
  expect((await fetch(`${BASE}/pack/extra`)).status).toBe(404);
  expect((await fetch(PACK_URL, { method: 'POST' })).status).toBe(405);
});

test('/pm status reports the hosting route, resolved host and a passed verification', async ({ server }) => {
  const reply = await consoleCommand(server, 'pm status');
  expect(reply).toContain('Hosting route: self-host');
  expect(reply).toContain(`Self-host: port ${SELF_HOST_PORT}, listening`);
  // server-ip is empty on the test server, so the bind address is advertised as localhost...
  expect(reply).toContain('Resolved host: localhost (from bind address (nothing configured))');
  // ...which external players can't reach: status says exactly what to set.
  expect(reply).toContain('Not reachable from the internet');
  expect(reply).toContain('Set upload.self-host.public-url');
  expect(reply).toMatch(/Verification: passed at \d{2}:\d{2}:\d{2} \(served on http:\/\/127\.0\.0\.1:\d+\/pack|Verification: passed at \d{2}:\d{2}:\d{2} \(local and advertised URL serve the pack\)/);
});

test('admins are told on join that the self-host URL is not reachable from the internet', async ({ player }) => {
  await player.makeOp();
  await player.rejoin();
  await expect(player).toHaveReceivedMessage(/\[PackMerger\] The self-host URL http:\/\/localhost:\d+\/pack uses localhost/,
    { timeout: 10_000 });
});

test('a public-url that does not serve the pack fails verification with the reason', async ({ server, cleanup }) => {
  const original = readFileSync(CONFIG, 'utf8');
  cleanup(async () => {
    writeFileSync(CONFIG, original);
    const mark = serverLogMark(server);
    await consoleCommand(server, 'pm reload');
    await expect(server).toHaveReceivedMessage(`Pack uploaded successfully: ${PACK_URL}`, { since: mark, timeout: 20_000 });
  });
  // Nothing listens on port 1: the local check passes, the advertised-URL check cannot connect.
  const dead = 'http://127.0.0.1:1/pack';
  const changed = original.replace(/^    public-url: (""|'')$/m, `    public-url: "${dead}"`);
  expect(changed).not.toBe(original);
  writeFileSync(CONFIG, changed);
  const mark = serverLogMark(server);
  await consoleCommand(server, 'pm reload');
  await expect(server).toHaveReceivedMessage(`Pack uploaded successfully: ${dead}`, { since: mark, timeout: 20_000 });
  await expect(server).toHaveReceivedMessage(`Self-host verification failed: the pack is served locally, but ${dead}`,
    { since: mark, timeout: 20_000 });

  const reply = await consoleCommand(server, 'pm status');
  expect(reply).toContain(`Pack URL: ${dead}`);
  expect(reply).toContain('Resolved host: 127.0.0.1 (from upload.self-host.public-url)');
  expect(reply).toMatch(/Verification: FAILED at \d{2}:\d{2}:\d{2} \(advertised URL check failed: could not connect to http:\/\/127\.0\.0\.1:1\/pack/);
});

test('upload.fallback publishes through the next provider when the primary fails', async ({ server, cleanup }) => {
  const original = readFileSync(CONFIG, 'utf8');
  cleanup(async () => {
    writeFileSync(CONFIG, original);
    const mark = serverLogMark(server);
    await consoleCommand(server, 'pm reload');
    await expect(server).toHaveReceivedMessage(`Pack uploaded successfully: ${PACK_URL}`, { since: mark, timeout: 20_000 });
    expect(await consoleCommand(server, 'pm status')).toContain('Hosting route: self-host');
  });
  // Polymath pointed at a port nothing listens on, with self-host as the fallback.
  const changed = original
    .replace(/^  provider: ("self-host"|self-host)$/m,
      '  provider: "polymath"\n  fallback: ["self-host"]\n  polymath:\n    server: "http://127.0.0.1:1"\n    secret: "e2e"');
  expect(changed).not.toBe(original);
  writeFileSync(CONFIG, changed);
  const mark = serverLogMark(server);
  await consoleCommand(server, 'pm reload');
  await expect(server).toHaveReceivedMessage(/Pack hosting problem: published through self-host \(fallback[;)].*polymath upload failed/,
    { since: mark, timeout: 20_000 });
  await expect(server).toHaveReceivedMessage(`Pack uploaded successfully: ${PACK_URL}`, { since: mark, timeout: 20_000 });

  const reply = await consoleCommand(server, 'pm status');
  expect(reply).toContain('Upload Provider: polymath');
  expect(reply).toMatch(/Hosting route: self-host \(fallback[;)].*polymath upload failed: Failed to connect to Polymath server/);
  expect(reply).toContain(`Self-host: port ${SELF_HOST_PORT}, listening`);
  const pack = await downloadPack();
  expect(pack.status).toBe(200);
});

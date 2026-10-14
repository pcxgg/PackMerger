import { expect, sleep, test } from '@plugwright/runner';
import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import {
  PACK_URL, PLUGIN_DIR, RUN_DIR, answerResourcePack, consoleCommand, downloadPack, nextResourcePackOffer,
  offeredWithin, packUuidForServer, serverLogMark,
} from './support/pack.js';

// Fixture: distribution.retry.max-attempts 3, retry.delay-ticks 40 (2 s), join-delay-ticks 60.

test('a failed download is re-sent after the retry delay, and the per-session cap holds', async ({ player, server }) => {
  const name = player.username;
  let offer = await nextResourcePackOffer(player.bot);
  for (let attempt = 1; attempt <= 3; attempt++) {
    const next = nextResourcePackOffer(player.bot, 6_000);
    answerResourcePack(player.bot, offer.uuid, 'failed_download');
    await expect(server).toHaveReceivedMessage(
      `Resource pack FAILED_DOWNLOAD for ${name}; resending in 40 ticks (attempt ${attempt}/3)`);
    const resent = await next;
    expect(resent.uuid).toBe(offer.uuid);
    expect(resent.hash).toBe(offer.hash);
    offer = resent;
  }
  const none = offeredWithin(player.bot, 4_000);
  answerResourcePack(player.bot, offer.uuid, 'failed_download');
  await expect(server).toHaveReceivedMessage(
    new RegExp(`Resource pack delivery to ${name} failed \\(last status FAILED_DOWNLOAD\\) after 3 resend\\(s\\); giving up`));
  expect(await none).toBe('not offered');
});

test('a discarded pack is re-sent as well', async ({ player, server }) => {
  const offer = await nextResourcePackOffer(player.bot);
  const next = nextResourcePackOffer(player.bot, 6_000);
  answerResourcePack(player.bot, offer.uuid, 'discarded');
  await expect(server).toHaveReceivedMessage(
    `Resource pack DISCARDED for ${player.username}; resending in 40 ticks (attempt 1/3)`);
  expect((await next).uuid).toBe(offer.uuid);
});

test('INVALID_URL and FAILED_RELOAD are diagnosed but not retried', async ({ player, server }) => {
  const offer = await nextResourcePackOffer(player.bot);
  const none = offeredWithin(player.bot, 4_000);
  answerResourcePack(player.bot, offer.uuid, 'invalid_url');
  await expect(server).toHaveReceivedMessage(`${player.username} reported INVALID_URL for the resource pack`);
  answerResourcePack(player.bot, offer.uuid, 'failed_reload');
  await expect(server).toHaveReceivedMessage(
    `${player.username} downloaded the resource pack but the client failed to apply it (FAILED_RELOAD)`);
  expect(await none).toBe('not offered');
});

test('status reports for packs that are not PackMerger\'s are ignored', async ({ player, server }) => {
  await nextResourcePackOffer(player.bot);
  const foreign = randomUUID();
  const none = offeredWithin(player.bot, 4_000);
  answerResourcePack(player.bot, foreign, 'failed_download');
  await expect(server).toHaveReceivedMessage(
    `${player.username} resource pack status FAILED_DOWNLOAD for another pack (${foreign}); ignored`);
  expect(await none).toBe('not offered');
  await expect(server).not.toHaveReceivedMessage(`Resource pack FAILED_DOWNLOAD for ${player.username}`);
});

const LANG = 'assets/minecraft/lang/en_us.json';
const EXTRA = `${PLUGIN_DIR}packs/identity-pack`;

test('the pack UUID stays the same when the pack content changes', async ({ player, server, cleanup }) => {
  const before = await nextResourcePackOffer(player.bot);
  expect(before.uuid).toBe(packUuidForServer('e2e'));
  const originalSha = (await downloadPack()).sha1;

  cleanup(async () => {
    rmSync(EXTRA, { recursive: true, force: true });
  });
  const staging = `${RUN_DIR}e2e-staging/identity-pack`;
  rmSync(staging, { recursive: true, force: true });
  mkdirSync(`${staging}/assets/minecraft/lang`, { recursive: true });
  writeFileSync(`${staging}/pack.mcmeta`,
    JSON.stringify({ pack: { description: 'identity', min_format: 84, max_format: 97 } }));
  writeFileSync(`${staging}/${LANG}`, JSON.stringify({ 'pmtest.identity': 'Identity' }));
  renameSync(staging, EXTRA);

  await expect.poll(async () => (await downloadPack()).sha1 !== originalSha, { timeout: 20_000 }).toBe(true);
  const changedSha = (await downloadPack()).sha1;

  const resent = nextResourcePackOffer(player.bot);
  await consoleCommand(server, `pm apply ${player.username}`);
  const after = await resent;
  expect(after.hash).toBe(changedSha);
  expect(after.hash).not.toBe(before.hash);
  expect(after.uuid).toBe(before.uuid);

  // Removing the pack re-merges back to identical content, so the SHA-1 is the original one again.
  rmSync(EXTRA, { recursive: true, force: true });
  await expect.poll(async () => (await downloadPack()).sha1, { timeout: 20_000 }).toBe(originalSha);
});

test('/pm reload keeps the advertised URL serving, and an identical re-merge keeps the SHA-1', async ({ server }) => {
  const before = await downloadPack();
  expect(before.status).toBe(200);
  const mark = serverLogMark(server);

  // Hammer the advertised URL while the reload re-initialises the provider and re-merges.
  const results: string[] = [];
  let polling = true;
  const poller = (async () => {
    while (polling) {
      try {
        const p = await downloadPack();
        results.push(p.status === 200 ? p.sha1 : `HTTP ${p.status}`);
      } catch (e) {
        results.push(`error: ${(e as Error).message}`);
      }
      await sleep(25);
    }
  })();

  await consoleCommand(server, 'pm reload');
  await expect(server).toHaveReceivedMessage(`Merged pack SHA1: ${before.sha1} (unchanged)`,
    { since: mark, timeout: 15_000 });
  await expect(server).toHaveReceivedMessage(`Pack uploaded successfully: ${PACK_URL}`, { since: mark, timeout: 10_000 });
  await sleep(500);
  polling = false;
  await poller;

  expect(results.length).toBeGreaterThan(5);
  expect(results.filter((r) => r !== before.sha1)).toEqual([]);
  await expect(server).toHaveReceivedMessage('Self-host HTTP server kept running', { since: mark });
  await expect(server).not.toHaveReceivedMessage('Self-host HTTP server stopped', { since: mark });
  // Identical content is not a "new pack": no notify spam.
  await expect(server).not.toHaveReceivedMessage('Notified online players about new resource pack', { since: mark });
});

const CONFIG = `${PLUGIN_DIR}config.yml`;

test('with required + enforce-required, declining the pack kicks the player', async ({ player, server, cleanup }) => {
  const original = readFileSync(CONFIG, 'utf8');
  cleanup(async () => {
    writeFileSync(CONFIG, original);
    await consoleCommand(server, 'pm reload');
  });
  const enforced = original
    .replace('  required: false', '  required: true')
    .replace('  enforce-required:\n    enabled: false', '  enforce-required:\n    enabled: true');
  expect(enforced).not.toBe(original);
  writeFileSync(CONFIG, enforced);
  const mark = serverLogMark(server);
  await consoleCommand(server, 'pm reload');
  await expect(server).toHaveReceivedMessage('Pack uploaded successfully', { since: mark, timeout: 15_000 });

  await player.rejoin();
  const offer = await nextResourcePackOffer(player.bot);
  expect(offer.forced).toBe(true);
  const kicked = new Promise<string>((resolve) => player.bot.once('kicked', (reason: unknown) => resolve(JSON.stringify(reason))));
  answerResourcePack(player.bot, offer.uuid, 'declined');
  await expect(server).toHaveReceivedMessage(
    `Kicking ${player.username}: declined the required resource pack (distribution.enforce-required)`);
  expect(await kicked).toContain('This server requires its resource pack');
});

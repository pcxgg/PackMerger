import { expect, test } from '@plugwright/runner';
import { readFileSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import {
  PACK_URL, PLUGIN_DIR, consoleCommand, nextResourcePackOffer, offeredWithin, serverLogMark,
} from './support/pack.js';
import {
  PROXY_PACK_ID, decodeMessage, encodeMessage, hmac, nextChannelMessage, registerChannel, sendChannelMessage,
} from './support/proxy.js';

// These tests stand in for the PackMerger proxy plugin: with no proxy in front of the
// server, the bot's own connection carries the plugin messages a proxy would see.

const CONFIG = `${PLUGIN_DIR}config.yml`;
const SECRET = 'e2e-proxy-secret';

/** Switches the proxy link on (with the shared secret), reloads, and returns a restore function. */
async function enableProxyLink(server: any, cleanup: (fn: () => Promise<void>) => void): Promise<void> {
  const original = readFileSync(CONFIG, 'utf8');
  cleanup(async () => {
    writeFileSync(CONFIG, original);
    const mark = serverLogMark(server);
    await consoleCommand(server, 'pm reload');
    await expect(server).toHaveReceivedMessage(`Pack uploaded successfully: ${PACK_URL}`, { since: mark, timeout: 20_000 });
  });
  // Specs that save config.yml through Bukkit (/pm priority, /pm verbose) rewrite "" as ''.
  const notify = /^  proxy-notify: false$/m;
  const secret = /^  proxy-secret: (""|'')$/m;
  expect(notify.test(original)).toBe(true);
  expect(secret.test(original)).toBe(true);
  writeFileSync(CONFIG, original
    .replace(notify, '  proxy-notify: true')
    .replace(secret, `  proxy-secret: "${SECRET}"`));
}

function dashed(uuid: string): string {
  return uuid.includes('-') ? uuid
    : `${uuid.slice(0, 8)}-${uuid.slice(8, 12)}-${uuid.slice(12, 16)}-${uuid.slice(16, 20)}-${uuid.slice(20)}`;
}

test('proxy-notify with nobody online relays the signed pack through the next player who joins',
  async ({ player: first, server, createPlayer, cleanup }) => {
    // Empty the server: the default bot leaves before the pack is published.
    const leaveMark = serverLogMark(server);
    first.bot.quit();
    await expect(server).toHaveReceivedMessage(`${first.username} left the game`, { since: leaveMark, timeout: 10_000 });
    await enableProxyLink(server, cleanup);
    const mark = serverLogMark(server);
    await consoleCommand(server, 'pm reload');
    // Backend distribution is still on: the double send is called out.
    await expect(server).toHaveReceivedMessage(
      '[proxy] distribution.proxy-notify is on while distribution.enabled is true', { since: mark, timeout: 20_000 });
    await expect(server).toHaveReceivedMessage(
      '[proxy] no players online; the pack update will be relayed to the proxy through the next player who joins',
      { since: mark, timeout: 20_000 });

    // A player joins whose connection listens on the channel (as a proxy with the plugin would).
    const joinMark = serverLogMark(server);
    const player = await createPlayer();
    const relayed = nextChannelMessage(player.bot, 15_000);
    registerChannel(player.bot);
    const raw = await relayed;
    await expect(server).toHaveReceivedMessage(
      new RegExp(`\\[proxy\\] relayed the pack to the proxy through ${player.username} \\(.*; signed\\): `
        + PACK_URL.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')),
      { since: joinMark, timeout: 10_000 });

    const { message, body, signature } = decodeMessage(raw);
    expect(message.kind).toBe('PUBLISH');
    expect(message.server).toBe('e2e');
    expect(message.url).toBe(PACK_URL);
    const status = await consoleCommand(server, 'pm status');
    expect(status).toContain(`SHA1: ${message.sha1}`);
    expect(signature.equals(hmac(SECRET, body))).toBe(true);
    expect(status).toMatch(new RegExp(`Proxy link: notify on, signed; last relayed at \\d{2}:\\d{2}:\\d{2} through ${player.username}`));

    // Already relayed: the next join doesn't send it again.
    const second = await createPlayer();
    const again = nextChannelMessage(second.bot, 4_000).then(() => 'sent', () => 'not sent');
    registerChannel(second.bot);
    expect(await again).toBe('not sent');
  });

test('a signed proxy offer is sent to the player; forged or misdirected offers are rejected',
  async ({ player, server, cleanup }) => {
    const ownOffer = nextResourcePackOffer(player.bot, 30_000);
    await enableProxyLink(server, cleanup);
    const mark = serverLogMark(server);
    await consoleCommand(server, 'pm reload');
    await expect(server).toHaveReceivedMessage(`Pack uploaded successfully: ${PACK_URL}`, { since: mark, timeout: 20_000 });

    const own = await ownOffer;
    const uuid = dashed(player.bot._client.uuid);
    const url = `${PACK_URL}?via=proxy`;
    const offer = (secret: string | null, target = uuid) => encodeMessage({
      kind: 'OFFER', server: 'lobby', timestamp: BigInt(Date.now()), url, sha1: own.hash,
      player: target, packId: PROXY_PACK_ID, required: false, prompt: '',
    }, secret);

    // Valid: sent under the proxy's pack id, next to (not instead of) this server's own pack.
    const next = nextResourcePackOffer(player.bot, 10_000);
    sendChannelMessage(player.bot, offer(SECRET));
    const proxied = await next;
    expect(proxied.uuid).toBe(PROXY_PACK_ID);
    expect(proxied.url).toBe(url);
    expect(proxied.hash).toBe(own.hash);
    expect(proxied.uuid).not.toBe(own.uuid);
    await expect(server).toHaveReceivedMessage(`[proxy] sending the proxy's pack to ${player.username}: ${url}`);
    await expect(server).toHaveReceivedMessage('[proxy] the proxy asks this server to send the network pack, '
      + 'and this server also sends its own (distribution.enabled: true)');

    // Wrong secret, unsigned, and an offer for someone else: logged, nothing sent.
    const none = offeredWithin(player.bot, 4_000);
    const rejectMark = serverLogMark(server);
    sendChannelMessage(player.bot, offer('not-the-secret'));
    sendChannelMessage(player.bot, offer(null));
    sendChannelMessage(player.bot, offer(SECRET, randomUUID()));
    await expect(server).toHaveReceivedMessage(
      `[proxy] rejected a pack offer via ${player.username}: bad signature`, { since: rejectMark });
    await expect(server).toHaveReceivedMessage(
      `[proxy] rejected a pack offer via ${player.username}: the message is not signed`, { since: rejectMark });
    await expect(server).toHaveReceivedMessage(
      `[proxy] rejected a pack offer via ${player.username}: the offer is for another player`, { since: rejectMark });
    expect(await none).toBe('not offered');
  });

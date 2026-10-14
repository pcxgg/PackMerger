import { expect, sleep, test } from '@plugwright/runner';
import {
  PACK_URL, answerResourcePack, consoleCommand, downloadPack, nextResourcePackOffer, packUuidForServer,
} from './support/pack.js';

// distribution.join-delay-ticks is 60 in the fixture config, so the offer arrives ~3 s
// after join — after the test body has attached its listener.

// The run directory is wiped before every run (fresh install) and the fixture delays the
// startup merge by plugin-packs.merge-delay-seconds (30 s for runs that include this file,
// see build.gradle). This is the first spec in the suite, so its first bot joins while no
// pack exists yet.
test('a player who joins before the first merge is sent the pack once it is published', async ({ player, server }) => {
  await expect(server).toHaveReceivedMessage(
    `No pack published yet; ${player.username} will be sent the pack as soon as the first one is published`,
    { timeout: 10_000 });
  const offer = await nextResourcePackOffer(player.bot, 25_000);
  await expect(server).toHaveReceivedMessage(
    `Sending the newly published pack to ${player.username} (joined before a pack was available)`);
  expect(offer.url).toBe(PACK_URL);
  expect(offer.hash).toBe((await downloadPack()).sha1);
  // It came from the first publish, not from the "notify" on-new-pack action.
  await expect(player).not.toHaveReceivedMessage('A new resource pack is available');
});

test('joining players are offered the merged pack under the stable server pack UUID', async ({ player, server }) => {
  const offer = await nextResourcePackOffer(player.bot);
  expect(offer.url).toBe(PACK_URL);
  expect(offer.uuid).toBe(packUuidForServer('e2e'));
  const served = await downloadPack();
  expect(offer.hash).toBe(served.sha1);
  await expect(server).toHaveReceivedMessage(`Sent resource pack to ${player.username} (URL: ${PACK_URL})`);
});

// The client drops server packs when it disconnects, so a rejoining player must be sent
// the pack again (it was previously skipped via a persistent player cache).
test('a player who loaded the pack is offered it again on rejoin', async ({ player, server }) => {
  const offer = await nextResourcePackOffer(player.bot);
  answerResourcePack(player.bot, offer.uuid, 'accepted');
  answerResourcePack(player.bot, offer.uuid, 'loaded');
  await expect(server).toHaveReceivedMessage(`${player.username} successfully loaded the resource pack`);

  await player.rejoin();
  const again = await nextResourcePackOffer(player.bot);
  expect(again.uuid).toBe(offer.uuid);
  expect(again.hash).toBe(offer.hash);
  expect(again.url).toBe(PACK_URL);
});

test('/pm apply <player> force-sends the pack', async ({ player, server }) => {
  const first = await nextResourcePackOffer(player.bot);
  answerResourcePack(player.bot, first.uuid, 'accepted');
  answerResourcePack(player.bot, first.uuid, 'loaded');
  await expect(server).toHaveReceivedMessage(`${player.username} successfully loaded the resource pack`);

  const forced = nextResourcePackOffer(player.bot);
  expect(await consoleCommand(server, `pm apply ${player.username}`))
    .toContain(`Force-sent resource pack to ${player.username}`);
  const resent = await forced;
  expect(resent.url).toBe(PACK_URL);
  // Same id: the client replaces PackMerger's pack instead of stacking a second copy.
  expect(resent.uuid).toBe(first.uuid);
});

test('declining the pack is logged', async ({ player, server }) => {
  const offer = await nextResourcePackOffer(player.bot);
  expect(offer.forced).toBe(false);
  answerResourcePack(player.bot, offer.uuid, 'declined');
  await expect(server).toHaveReceivedMessage(`${player.username} declined the resource pack`);
  // distribution.required is false, so declining must not kick the player.
  await sleep(2_000);
  await expect(server).not.toHaveReceivedMessage(`${player.username} lost connection`);
  expect(player.bot.entity).toBeTruthy();
});

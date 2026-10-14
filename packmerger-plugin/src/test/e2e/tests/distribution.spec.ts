import { expect, sleep, test } from '@plugwright/runner';
import { PACK_URL, answerResourcePack, consoleCommand, downloadPack, nextResourcePackOffer } from './support/pack.js';

// distribution.join-delay-ticks is 60 in the fixture config, so the offer arrives ~3 s
// after join — after the test body has attached its listener.

test('joining players are offered the merged pack', async ({ player, server }) => {
  const offer = await nextResourcePackOffer(player.bot);
  expect(offer.url).toBe(PACK_URL);
  const served = await downloadPack();
  expect(offer.hash).toBe(served.sha1);
  await expect(server).toHaveReceivedMessage(`Sent resource pack to ${player.username} (URL: ${PACK_URL})`);
});

test('a player who loaded the pack is not re-sent it on rejoin', async ({ player, server }) => {
  const offer = await nextResourcePackOffer(player.bot);
  answerResourcePack(player.bot, offer.uuid, 'accepted');
  answerResourcePack(player.bot, offer.uuid, 'loaded');
  await expect(server).toHaveReceivedMessage(`${player.username} successfully loaded the resource pack`);

  await player.rejoin();
  const again = nextResourcePackOffer(player.bot, 6_000).then(() => 'offered', () => 'not offered');
  await expect(server).toHaveReceivedMessage(
    `Skipping pack send for ${player.username} — already has current pack`, { timeout: 10_000 });
  expect(await again).toBe('not offered');
});

test('/pm apply <player> force-sends the pack even when cached', async ({ player, server }) => {
  const first = await nextResourcePackOffer(player.bot);
  answerResourcePack(player.bot, first.uuid, 'accepted');
  answerResourcePack(player.bot, first.uuid, 'loaded');
  await expect(server).toHaveReceivedMessage(`${player.username} successfully loaded the resource pack`);

  const forced = nextResourcePackOffer(player.bot);
  expect(await consoleCommand(server, `pm apply ${player.username}`))
    .toContain(`Force-sent resource pack to ${player.username}`);
  expect((await forced).url).toBe(PACK_URL);
});

test('declining the pack is logged', async ({ player, server }) => {
  const offer = await nextResourcePackOffer(player.bot);
  answerResourcePack(player.bot, offer.uuid, 'declined');
  await expect(server).toHaveReceivedMessage(`${player.username} declined the resource pack`);
  // distribution.required is false, so declining must not kick the player.
  await sleep(2_000);
  await expect(server).not.toHaveReceivedMessage(`${player.username} lost connection`);
  expect(player.bot.entity).toBeTruthy();
});

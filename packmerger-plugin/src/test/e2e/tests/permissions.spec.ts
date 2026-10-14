import { expect, sleep, test } from '@plugwright/runner';

// Note: expect(...).not.toHaveReceivedMessage returns as soon as the message is absent,
// so each negative check below waits for a positive reply (or a settle delay) first.

test('players without packmerger.admin cannot run /pm', async ({ player, server }) => {
  player.chat('/pm status');
  // The Brigadier node is hidden from non-admins, so Paper treats it as an unknown command.
  await expect(player).toHaveReceivedMessage(/Unknown or incomplete command/i);
  player.chat('/packmerger reload');
  player.chat('/pm priority top base-pack');
  await sleep(2_000);
  await expect(player).not.toHaveReceivedMessage('PackMerger Status');
  await expect(player).not.toHaveReceivedMessage('Reloading PackMerger...');
  await expect(player).not.toHaveReceivedMessage('Priority updated');
  await expect(server).not.toHaveReceivedMessage('Starting merge process');
  await expect(server).not.toHaveReceivedMessage('Merge order (highest priority first)');
});

test('the same player gains access once opped', async ({ player }) => {
  player.chat('/pm status');
  await expect(player).toHaveReceivedMessage(/Unknown or incomplete command/i);
  await expect(player).not.toHaveReceivedMessage('PackMerger Status');
  await player.makeOp();
  const since = player.getMessageBufferIndex();
  player.chat('/pm status');
  await expect(player).toHaveReceivedMessage('PackMerger Status', { since });
});

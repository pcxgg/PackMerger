import { describe, expect, sleep, test } from '@plugwright/runner';
import type { PlayerWrapper, ServerWrapper } from '@plugwright/runner';
import { PACK_URL, downloadPack, entryJson, entryText } from './support/pack.js';

const MERGED = `Merge and upload complete! URL: ${PACK_URL}`;

/** Runs a /pm command that triggers a merge and waits for that merge to finish. */
async function runAndAwaitMerge(player: PlayerWrapper, command: string): Promise<void> {
  const since = player.getMessageBufferIndex();
  player.chat(command);
  await expect(player).toHaveReceivedMessage(MERGED, { since, timeout: 15_000 });
}

async function expectPriorityList(player: PlayerWrapper, first: string, second: string): Promise<void> {
  const since = player.getMessageBufferIndex();
  player.chat('/pm priority list');
  await expect(player).toHaveReceivedMessage('Priority (highest first)', { since });
  await expect(player).toHaveReceivedMessage(`1. ${first}`, { since, strict: true });
  await expect(player).toHaveReceivedMessage(`2. ${second}`, { since, strict: true });
}

/** Puts overlay-pack back on top from the console, even if a test failed half-way. */
async function restoreDefaultOrder(server: ServerWrapper): Promise<void> {
  await server.execute('pm priority top overlay-pack');
  await sleep(2_000);
}

test('/pm priority list shows the configured order', async ({ player }) => {
  await player.makeOp();
  await expectPriorityList(player, 'overlay-pack', 'base-pack');
});

test('/pm priority top re-merges with the new order and persists it to config.yml', async ({ player, server, cleanup }) => {
  cleanup(() => restoreDefaultOrder(server));
  await player.makeOp();

  const since = player.getMessageBufferIndex();
  player.chat('/pm priority top base-pack');
  await expect(player).toHaveReceivedMessage('Priority updated: moved base-pack top', { since });
  await expect(player).toHaveReceivedMessage('Triggering merge with new priority…', { since });
  await expect(player).toHaveReceivedMessage(MERGED, { since, timeout: 15_000 });

  // The served pack now reflects base-pack winning every collision.
  const pack = await downloadPack();
  expect(entryJson(pack, 'assets/minecraft/lang/en_us.json')['pmtest.shared']).toBe('Shared from base');
  expect(entryText(pack, 'assets/pmtest/marker.txt').trim()).toBe('base');

  // Persisted: /pm reload re-reads config.yml from disk and the new order is still in effect.
  await runAndAwaitMerge(player, '/pm reload');
  await expectPriorityList(player, 'base-pack', 'overlay-pack');

  // And back again with an absolute placement.
  await runAndAwaitMerge(player, '/pm priority set overlay-pack 1');
  await expectPriorityList(player, 'overlay-pack', 'base-pack');
  const restored = await downloadPack();
  expect(entryText(restored, 'assets/pmtest/marker.txt').trim()).toBe('overlay');
});

test('/pm priority rejects impossible moves without re-merging', async ({ player }) => {
  await player.makeOp();
  player.chat('/pm priority up overlay-pack');
  await expect(player).toHaveReceivedMessage('overlay-pack is already at the top');
  player.chat('/pm priority bottom nosuchpack');
  await expect(player).toHaveReceivedMessage('nosuchpack is not in the priority list');
  player.chat('/pm priority set base-pack 5');
  await expect(player).toHaveReceivedMessage('position must be between 1 and 2');
  await sleep(1_000);
  await expect(player).not.toHaveReceivedMessage('Starting merge process...');
  await expectPriorityList(player, 'overlay-pack', 'base-pack');
});

describe.serial('profiles', () => {
  test('/pm profile list shows the defined profiles with none active', async ({ player }) => {
    await player.makeOp();
    player.chat('/pm profile list');
    await expect(player).toHaveReceivedMessage('Profiles');
    await expect(player).toHaveReceivedMessage(/^\s*standard \[2 packs\]$/);
    await expect(player).toHaveReceivedMessage(/^\s*inverted \[2 packs\]$/);
  });

  test('/pm profile switch rejects unknown profiles', async ({ player }) => {
    player.chat('/pm profile switch nope');
    await expect(player).toHaveReceivedMessage('Unknown profile: nope');
    await expect(player).toHaveReceivedMessage('Known profiles: standard, inverted');
  });

  test('/pm profile switch activates the profile priority and re-merges', async ({ player }) => {
    const since = player.getMessageBufferIndex();
    player.chat('/pm profile switch inverted');
    await expect(player).toHaveReceivedMessage('Switched to profile inverted', { since });
    await expect(player).toHaveReceivedMessage(MERGED, { since, timeout: 15_000 });
    await expectPriorityList(player, 'base-pack', 'overlay-pack');
    const pack = await downloadPack();
    expect(entryText(pack, 'assets/pmtest/marker.txt').trim()).toBe('base');

    const listSince = player.getMessageBufferIndex();
    player.chat('/pm profile list');
    await expect(player).toHaveReceivedMessage(/^\s*inverted \[2 packs\] \(active\)$/, { since: listSince });
  });

  test('/pm priority edits the active profile order', async ({ player }) => {
    // The effective order comes from the active profile, so that is what a move must change.
    await runAndAwaitMerge(player, '/pm priority top overlay-pack');
    await expectPriorityList(player, 'overlay-pack', 'base-pack');
    const pack = await downloadPack();
    expect(entryText(pack, 'assets/pmtest/marker.txt').trim()).toBe('overlay');

    // Put the inverted profile back the way the fixture defines it.
    await runAndAwaitMerge(player, '/pm priority top base-pack');
    await expectPriorityList(player, 'base-pack', 'overlay-pack');
  });

  test('switching to the standard profile restores the default order', async ({ player }) => {
    await runAndAwaitMerge(player, '/pm profile switch standard');
    await expectPriorityList(player, 'overlay-pack', 'base-pack');
    const pack = await downloadPack();
    expect(entryText(pack, 'assets/pmtest/marker.txt').trim()).toBe('overlay');
  });
});

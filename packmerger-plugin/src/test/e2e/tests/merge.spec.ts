import { expect, sleep, test } from '@plugwright/runner';
import { PACK_URL, SELF_HOST_PORT, downloadPack, entryJson, entryText } from './support/pack.js';

test('self-host provider serves the merged pack whose SHA-1 matches /pm status', async ({ player }) => {
  const pack = await downloadPack();
  expect(pack.status).toBe(200);
  expect(pack.contentType).toBe('application/zip');
  expect(pack.entries.size).toBeGreaterThan(0);

  await player.makeOp();
  player.chat('/pm status');
  await expect(player).toHaveReceivedMessage(`SHA1: ${pack.sha1}`);
});

test('overlapping packs are merged format-aware with the higher-priority pack winning', async () => {
  const pack = await downloadPack();
  expect([...pack.entries.keys()].sort()).toEqual([
    'assets/minecraft/lang/en_us.json',
    'assets/minecraft/models/item/stick.json',
    'assets/pmtest/marker.txt',
    'pack.mcmeta',
  ]);

  // Language files: key union, overlay-pack (priority 1) wins the shared key.
  expect(entryJson(pack, 'assets/minecraft/lang/en_us.json')).toEqual({
    'pmtest.base_only': 'Base only',
    'pmtest.overlay_only': 'Overlay only',
    'pmtest.shared': 'Shared from overlay',
  });

  // Models: deep merge, conflicting keys from the higher-priority pack.
  const stick = entryJson(pack, 'assets/minecraft/models/item/stick.json');
  expect(stick.parent).toBe('minecraft:item/generated');
  expect(stick.gui_light).toBe('front');
  expect(stick.display.thirdperson_righthand.scale).toEqual([1.5, 1.5, 1.5]);

  // Non-JSON files: last write wins, i.e. the higher-priority pack.
  expect(entryText(pack, 'assets/pmtest/marker.txt').trim()).toBe('overlay');

  // pack.mcmeta: the 26.1 min_format/max_format schema survives the merge.
  const mcmeta = entryJson(pack, 'pack.mcmeta');
  expect(mcmeta.pack.description).toBe('PackMerger E2E overlay pack');
  expect(mcmeta.pack.min_format).toBe(84);
  expect(mcmeta.pack.max_format).toBe(84);
});

test('/pm validate passes a clean 26.1-format pack', async ({ player }) => {
  await player.makeOp();
  player.chat('/pm validate');
  await expect(player).toHaveReceivedMessage('Running validation...');
  await expect(player).toHaveReceivedMessage('Validation complete: 0 warnings, 0 errors', { timeout: 10_000 });
  // Per-issue lines follow the summary; give them a moment to arrive before asserting none did.
  await sleep(1_000);
  await expect(player).not.toHaveReceivedMessage(/ERROR:|WARNING:/);
});

test('/pm reload reloads the config and re-merges', async ({ player, server }) => {
  await player.makeOp();
  player.chat('/pm reload');
  await expect(player).toHaveReceivedMessage('Reloading PackMerger...');
  await expect(player).toHaveReceivedMessage('Starting merge process...');
  await expect(player).toHaveReceivedMessage(`Merge and upload complete! URL: ${PACK_URL}`, { timeout: 15_000 });
  await expect(server).toHaveReceivedMessage(`Self-host HTTP server started on port ${SELF_HOST_PORT}`);
  await expect(server).toHaveReceivedMessage('Merge order (highest priority first): [overlay-pack, base-pack]');
  await expect(server).not.toHaveReceivedMessage(/\[PackMerger\].*\[ERROR\]|Exception/);

  const pack = await downloadPack();
  expect(pack.status).toBe(200);
});

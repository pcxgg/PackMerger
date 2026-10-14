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
    'assets/minecraft/items/paper.json',
    'assets/minecraft/lang/en_us.json',
    'assets/minecraft/models/item/stick.json',
    'assets/pmtest/marker.txt',
    'pack.mcmeta',
    'pm_overlay/assets/minecraft/lang/en_us.json',
  ]);

  // Language files: key union, overlay-pack (priority 1) wins the shared key.
  expect(entryJson(pack, 'assets/minecraft/lang/en_us.json')).toEqual({
    'pmtest.base_only': 'Base only',
    'pmtest.overlay_only': 'Overlay only',
    'pmtest.shared': 'Shared from overlay',
  });

  // Models: under the default merge.model-policy (whole-file) the higher-priority
  // model wins whole; base-pack's gui_light and parent do not leak into it.
  expect(entryJson(pack, 'assets/minecraft/models/item/stick.json')).toEqual({
    parent: 'minecraft:item/generated',
    display: { thirdperson_righthand: { scale: [1.5, 1.5, 1.5] } },
  });

  // Non-JSON files: last write wins, i.e. the higher-priority pack.
  expect(entryText(pack, 'assets/pmtest/marker.txt').trim()).toBe('overlay');

  // pack.mcmeta: the 26.1 min_format/max_format schema survives the merge.
  const mcmeta = entryJson(pack, 'pack.mcmeta');
  expect(mcmeta.pack.description).toBe('PackMerger E2E overlay pack');
  expect(mcmeta.pack.min_format).toBe(84);
  expect(mcmeta.pack.max_format).toBe(84);
});

test('1.21.4+ item definitions: range_dispatch entries are unioned by threshold', async () => {
  const pack = await downloadPack();
  const paper = entryJson(pack, 'assets/minecraft/items/paper.json');
  expect(paper.model.type).toBe('minecraft:range_dispatch');
  expect(paper.model.property).toBe('minecraft:custom_model_data');
  expect(paper.model.cases).toBeUndefined();
  // Sorted by threshold; the shared threshold 1000 goes to overlay-pack (higher priority),
  // base-pack's unique thresholds 1 and 2000 survive.
  expect(paper.model.entries.map((e: any) => [e.threshold, e.model.model])).toEqual([
    [1, 'pmtest:item/base_coin'],
    [5, 'pmtest:item/overlay_topaz'],
    [1000, 'pmtest:item/overlay_ruby'],
    [2000, 'pmtest:item/base_gun'],
  ]);
  expect(paper.model.fallback.model).toBe('minecraft:item/paper');
});

test('overlay directories get the same format-aware merge, and overlay formats are completed', async () => {
  const pack = await downloadPack();
  expect(entryJson(pack, 'pm_overlay/assets/minecraft/lang/en_us.json')).toEqual({
    'pmtest.overlay_dir.from_base': 'Overlay dir from base',
    'pmtest.overlay_dir.from_overlay': 'Overlay dir from overlay',
    'pmtest.overlay_dir.shared': 'Overlay dir shared from overlay',
  });

  // base-pack's list reaches format 64, so every combined entry needs "formats":
  // overlay-pack's pm_overlay entry (which won the directory) gains one.
  const entries = entryJson(pack, 'pack.mcmeta').overlays.entries;
  expect(entries.map((e: any) => e.directory)).toEqual(['pm_overlay', 'pm_legacy']);
  expect(entries[0].formats).toEqual({ min_inclusive: 84, max_inclusive: 84 });
  expect(entries[1].formats).toEqual([46, 64]);
});

test('/pm inspect collisions shows the item-definition threshold clash with both pack names', async ({ player, server }) => {
  await player.makeOp();
  const since = player.getMessageBufferIndex();
  player.chat('/pm inspect collisions');
  await expect(player).toHaveReceivedMessage('assets/minecraft/items/paper.json', { since });
  await expect(player).toHaveReceivedMessage(
    /note: .*threshold 1000 is defined by both overlay-pack and base-pack; overlay-pack wins/, { since });
  await expect(player).toHaveReceivedMessage(/note: .*added a 'formats' range to 1 overlay entry/, { since });
  // The warning is logged by the merge (the startup one at the latest), so read the log from server start.
  await expect(server).toHaveReceivedMessage(
    /Item definition collision in assets\/minecraft\/items\/paper\.json: .*overlay-pack and base-pack/, { since: 0 });
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
  // The HTTP server is kept (not restarted) so the advertised URL never stops answering.
  await expect(server).toHaveReceivedMessage(`Self-host HTTP server kept running on port ${SELF_HOST_PORT}`);
  await expect(server).toHaveReceivedMessage('Merge order (highest priority first): [overlay-pack, base-pack]');
  await expect(server).not.toHaveReceivedMessage(/\[PackMerger\].*\[ERROR\]|Exception/);

  const pack = await downloadPack();
  expect(pack.status).toBe(200);
});

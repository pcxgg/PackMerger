import { expect, test } from '@plugwright/runner';
import { mkdirSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { PLUGIN_DIR, RUN_DIR, downloadPack, entryJson } from './support/pack.js';

const LANG = 'assets/minecraft/lang/en_us.json';
const HOTFIX = `${PLUGIN_DIR}packs/hotfix-pack`;

async function langKeys(): Promise<Record<string, string>> {
  const pack = await downloadPack();
  return pack.status === 200 ? entryJson(pack, LANG) : {};
}

test('dropping a new pack into packs/ hot-reloads the merge and notifies players', async ({ player, cleanup }) => {
  cleanup(() => rmSync(HOTFIX, { recursive: true, force: true }));
  await player.makeOp();

  // Build the pack outside the watched folder, then move it in with one rename so the
  // watcher sees a single complete directory appear.
  const staging = `${RUN_DIR}e2e-staging/hotfix-pack`;
  rmSync(staging, { recursive: true, force: true });
  mkdirSync(`${staging}/assets/minecraft/lang`, { recursive: true });
  writeFileSync(`${staging}/pack.mcmeta`,
    JSON.stringify({ pack: { description: 'hotfix', min_format: 84, max_format: 97 } }));
  writeFileSync(`${staging}/${LANG}`, JSON.stringify({ 'pmtest.hotfix': 'Hotfix', 'pmtest.shared': 'Shared from hotfix' }));
  renameSync(staging, HOTFIX);

  // Unlisted packs merge at the lowest priority: the new key appears, the shared key keeps overlay's value.
  await expect.poll(async () => (await langKeys())['pmtest.hotfix'], { timeout: 20_000 }).toBe('Hotfix');
  expect((await langKeys())['pmtest.shared']).toBe('Shared from overlay');
  await expect(player).toHaveReceivedMessage(
    '[PackMerger] A new resource pack is available. Rejoin or use F3+T to reload.', { timeout: 10_000 });

  const since = player.getMessageBufferIndex();
  player.chat('/pm inspect');
  await expect(player).toHaveReceivedMessage(/^\s*3\. hotfix-pack$/, { since });

  // Removing it re-merges back to the two fixture packs.
  rmSync(HOTFIX, { recursive: true, force: true });
  await expect.poll(async () => (await langKeys())['pmtest.hotfix'], { timeout: 20_000 }).toBe(undefined);
});

test('editing a file deep inside an unzipped pack folder hot-reloads the merge', async ({ player, cleanup }) => {
  const nested = `${PLUGIN_DIR}packs/nested-edit-pack`;
  cleanup(() => rmSync(nested, { recursive: true, force: true }));
  await player.makeOp();

  const staging = `${RUN_DIR}e2e-staging/nested-edit-pack`;
  rmSync(staging, { recursive: true, force: true });
  mkdirSync(`${staging}/assets/minecraft/lang`, { recursive: true });
  writeFileSync(`${staging}/pack.mcmeta`,
    JSON.stringify({ pack: { description: 'nested', min_format: 84, max_format: 97 } }));
  writeFileSync(`${staging}/${LANG}`, JSON.stringify({ 'pmtest.nested': 'v1' }));
  renameSync(staging, nested);
  await expect.poll(async () => (await langKeys())['pmtest.nested'], { timeout: 20_000 }).toBe('v1');

  // Only a file three folders below packs/ changes now: the watcher must still notice it.
  writeFileSync(`${nested}/${LANG}`, JSON.stringify({ 'pmtest.nested': 'v2' }));
  await expect.poll(async () => (await langKeys())['pmtest.nested'], { timeout: 20_000 }).toBe('v2');

  // A folder created after the pack appeared is watched too.
  mkdirSync(`${nested}/assets/pmtest/lang`, { recursive: true });
  await new Promise((r) => setTimeout(r, 1_500));
  writeFileSync(`${nested}/assets/pmtest/lang/en_us.json`, JSON.stringify({ 'pmtest.nested.deep': 'deep' }));
  await expect.poll(async () => {
    const pack = await downloadPack();
    return pack.entries.has('assets/pmtest/lang/en_us.json');
  }, { timeout: 20_000 }).toBe(true);

  rmSync(nested, { recursive: true, force: true });
  await expect.poll(async () => (await langKeys())['pmtest.nested'], { timeout: 20_000 }).toBe(undefined);
});

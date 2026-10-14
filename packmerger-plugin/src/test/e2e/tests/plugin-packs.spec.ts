import { expect, sleep, test } from '@plugwright/runner';
import type { PlayerWrapper, ServerWrapper } from '@plugwright/runner';
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { PACK_URL, PLUGIN_DIR, RUN_DIR, buildZip, downloadPack, entryJson } from './support/pack.js';

// Plugin-pack discovery against a fake plugin's output staged by the fixtures:
//   plugins/PmE2EFake/resource_pack/{alpha,beta}/  a cluster of two packs (adapter "e2e-fake")
//   plugins/PmE2EFake/not_a_pack/                  a folder that is not a pack (adapter "e2e-broken")
// Both adapters are declared in fixtures/PackMerger/config.yml; plugin-packs.enabled
// is false there so the other specs never see them. These tests flip it and restore it.

const CONFIG = `${PLUGIN_DIR}config.yml`;
const CLUSTER = `${RUN_DIR}plugins/PmE2EFake/resource_pack`;
const STAGED = `${PLUGIN_DIR}packs/.plugin-packs/e2e-fake.zip`;
const LANG = 'assets/minecraft/lang/en_us.json';
const MERGED = `Merge and upload complete! URL: ${PACK_URL}`;

function setConfig(pluginPacks: boolean, fakeSource: boolean): void {
  const text = readFileSync(CONFIG, 'utf8')
    .replace(/^(plugin-packs:\n {2}enabled: )(true|false)/m, `$1${pluginPacks}`)
    .replace(/^( {4}e2e-fake:\n {6}enabled: )(true|false)/m, `$1${fakeSource}`);
  writeFileSync(CONFIG, text);
}

async function entries(): Promise<string[]> {
  const pack = await downloadPack();
  return pack.status === 200 ? [...pack.entries.keys()] : [];
}

async function langKeys(): Promise<Record<string, string>> {
  const pack = await downloadPack();
  return pack.status === 200 ? entryJson(pack, LANG) : {};
}

async function reloadAndAwaitMerge(player: PlayerWrapper): Promise<void> {
  const since = player.getMessageBufferIndex();
  player.chat('/pm reload');
  await expect(player).toHaveReceivedMessage(MERGED, { since, timeout: 20_000 });
}

/** Turns discovery back off from the console; the reload re-merges and clears .plugin-packs/. */
async function restore(server: ServerWrapper): Promise<void> {
  setConfig(false, true);
  rmSync(`${CLUSTER}/gamma`, { recursive: true, force: true });
  await server.execute('pm reload');
  await sleep(3_000);
}

test('a custom adapter stages a fake plugin pack cluster, re-merges on change, and is removed when disabled',
  async ({ player, server, cleanup }) => {
    cleanup(() => restore(server));
    await player.makeOp();

    // 1. Enable discovery: the two cluster members are unioned into one staged pack and merged.
    setConfig(true, true);
    await reloadAndAwaitMerge(player);
    expect(existsSync(STAGED)).toBe(true);
    const merged = await entries();
    expect(merged).toContain('assets/pmtest/fake_alpha.txt');
    expect(merged).toContain('assets/pmtest/fake_beta.txt');
    // Both members' lang files are merged key by key with the fixture packs.
    const lang = await langKeys();
    expect(lang['pmtest.fake_alpha']).toBe('Fake alpha');
    expect(lang['pmtest.fake_beta']).toBe('Fake beta');
    expect(lang['pmtest.shared']).toBe('Shared from overlay');

    let since = player.getMessageBufferIndex();
    player.chat('/pm status');
    await expect(player).toHaveReceivedMessage('Plugin packs: e2e-fake', { since });

    // 2. A source that is not a resource pack is skipped with a log line and shows in the advisory.
    await expect(server).toHaveReceivedMessage(
      /\[plugin-packs\] skipped e2e-broken \('e2e-broken'\): not_a_pack\/ has no pack\.mcmeta or assets\/ at its root/);
    await player.rejoin();
    await player.makeOp();
    await expect(player).toHaveReceivedMessage(/installed but not merged.*e2e-broken \(not a resource pack/,
      { timeout: 10_000 });

    // 3. The plugin "rebuilds" its pack: a new cluster member appears. The source watcher
    //    picks it up and re-merges without any /pm command.
    const staging = `${RUN_DIR}e2e-staging/gamma`;
    rmSync(staging, { recursive: true, force: true });
    mkdirSync(`${staging}/assets/pmtest`, { recursive: true });
    writeFileSync(`${staging}/pack.mcmeta`, JSON.stringify({ pack: { description: 'gamma', min_format: 84, max_format: 84 } }));
    writeFileSync(`${staging}/assets/pmtest/fake_gamma.txt`, 'gamma');
    renameSync(staging, `${CLUSTER}/gamma`);
    await expect.poll(async () => (await entries()).includes('assets/pmtest/fake_gamma.txt'), { timeout: 20_000 })
      .toBe(true);
    await expect(server).toHaveReceivedMessage("a plugin's generated pack changed on disk; re-merging");

    rmSync(`${CLUSTER}/gamma`, { recursive: true, force: true });
    await expect.poll(async () => (await entries()).includes('assets/pmtest/fake_gamma.txt'), { timeout: 20_000 })
      .toBe(false);

    // 4. Disabling the adapter deletes the staged copy, so it stops merging.
    setConfig(true, false);
    await reloadAndAwaitMerge(player);
    expect(existsSync(STAGED)).toBe(false);
    const after = await entries();
    expect(after).not.toContain('assets/pmtest/fake_alpha.txt');
    expect(after).not.toContain('assets/pmtest/fake_beta.txt');
    expect((await langKeys())['pmtest.fake_alpha']).toBe(undefined);
    await expect(server).toHaveReceivedMessage("[plugin-packs] removed stale staged pack 'e2e-fake'");

    since = player.getMessageBufferIndex();
    player.chat('/pm status');
    await expect(player).toHaveReceivedMessage('Plugin packs: none', { since });
  });

test('/pm merge merges and uploads without a config reload', async ({ player }) => {
  await player.makeOp();
  const since = player.getMessageBufferIndex();
  player.chat('/pm merge');
  await expect(player).toHaveReceivedMessage('Starting merge process...', { since });
  await expect(player).toHaveReceivedMessage(MERGED, { since, timeout: 20_000 });
  await expect(player).not.toHaveReceivedMessage('Reloading PackMerger...', { since });
});

test('a cached remote pack whose alias is no longer declared is deleted, not merged', async ({ player, server, cleanup }) => {
  const cacheDir = `${PLUGIN_DIR}packs/.remote-cache`;
  const ghost = `${cacheDir}/ghost.zip`;
  cleanup(() => rmSync(ghost, { force: true }));
  await player.makeOp();

  mkdirSync(cacheDir, { recursive: true });
  writeFileSync(ghost, buildZip({
    'pack.mcmeta': JSON.stringify({ pack: { description: 'ghost', min_format: 84, max_format: 84 } }),
    'assets/pmtest/ghost.txt': 'ghost',
  }));
  writeFileSync(`${cacheDir}/ghost.meta.json`, '{}');

  const since = player.getMessageBufferIndex();
  player.chat('/pm merge');
  await expect(player).toHaveReceivedMessage(MERGED, { since, timeout: 20_000 });
  expect(existsSync(ghost)).toBe(false);
  expect(existsSync(`${cacheDir}/ghost.meta.json`)).toBe(false);
  expect(await entries()).not.toContain('assets/pmtest/ghost.txt');
  await expect(server).toHaveReceivedMessage("removed cached pack 'ghost' (no longer declared in remote-packs)");
});

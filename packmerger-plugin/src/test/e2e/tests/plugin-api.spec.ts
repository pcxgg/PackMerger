import { expect, sleep, test } from '@plugwright/runner';
import { consoleCommand, downloadPack, serverLogMark } from './support/pack.js';

// The public API used the way a third-party plugin uses it: PmApiTest (built from
// src/e2ePlugin, staged into plugins/) depends on PackMerger, looks the API up with
// PackMergerApi.get(), listens to PackMerger's events, and is driven over RCON with
// /pmapitest. Its packs live in plugins/PmApiTest/{pack,lazy}/ (fixtures).

const API_MARKER = 'assets/pmapi/api_marker.txt';
const LAZY_MARKER = 'assets/pmapi/lazy_marker.txt';

async function entries(): Promise<string[]> {
  const pack = await downloadPack();
  return pack.status === 200 ? [...pack.entries.keys()] : [];
}

test('a plugin finds the API from its own classloader and registers a pack that is merged, listed and removed',
  async ({ player, server, cleanup }) => {
    cleanup(async () => {
      await consoleCommand(server, 'pmapitest unregister');
      await sleep(3_000);
    });
    await player.makeOp();
    await expect(server).toHaveReceivedMessage('[pmapitest] found PackMerger API: true', { since: 0 });

    // 1. Register: a merge is scheduled on its own and the pack's file is served.
    let mark = serverLogMark(server);
    expect(await consoleCommand(server, 'pmapitest register')).toContain('pmapitest: registered api-test');
    await expect.poll(async () => (await entries()).includes(API_MARKER), { timeout: 20_000 }).toBe(true);
    await expect(server).toHaveReceivedMessage("[api-packs] PmApiTest registered pack 'api-test'", { since: mark });
    await expect(server).toHaveReceivedMessage("[api-packs] staged 'api-test' from PmApiTest", { since: mark });
    // PackMerger's events reach a listener in another plugin (same HandlerList).
    await expect(server).toHaveReceivedMessage('[pmapitest] PackMergerCollectPacksEvent received', { since: mark });
    await expect(server).toHaveReceivedMessage(/\[pmapitest\] PackUploadedEvent received: http/, { since: mark });

    // 2. It is listed by the API and in /pm status, and usable by its alias.
    expect(await consoleCommand(server, 'pmapitest list')).toContain('api-test by PmApiTest');
    let since = player.getMessageBufferIndex();
    player.chat('/pm status');
    await expect(player).toHaveReceivedMessage('API packs: api-test (PmApiTest)', { since });
    since = player.getMessageBufferIndex();
    player.chat('/pm inspect api-test');
    await expect(player).toHaveReceivedMessage(/api-test/, { since });

    // 3. Another plugin cannot take over the alias.
    expect(await consoleCommand(server, 'pmapitest steal'))
      .toContain("pmapitest: refused: pack alias 'api-test' is already registered by PmApiTest");

    // 4. Unregister: the staged copy is deleted and the file leaves the merged pack.
    mark = serverLogMark(server);
    expect(await consoleCommand(server, 'pmapitest unregister')).toContain('unregistered=true');
    await expect.poll(async () => (await entries()).includes(API_MARKER), { timeout: 20_000 }).toBe(false);
    await expect(server).toHaveReceivedMessage("[api-packs] removed staged pack 'api-test'", { since: mark });
  });

test('a plugin contributes a pack lazily from PackMergerCollectPacksEvent', async ({ server, cleanup }) => {
  cleanup(async () => {
    await consoleCommand(server, 'pmapitest lazy off');
    await consoleCommand(server, 'pmapitest unregister');
    await sleep(3_000);
  });
  expect(await consoleCommand(server, 'pmapitest lazy on')).toContain('lazy=true');
  const mark = serverLogMark(server);
  await consoleCommand(server, 'pm merge');
  await expect(server).toHaveReceivedMessage('[pmapitest] PackMergerCollectPacksEvent received (lazy=true)',
    { since: mark, timeout: 10_000 });
  // Registered during the event, merged by that same merge (no second merge needed).
  await expect(server).toHaveReceivedMessage("[api-packs] staged 'api-lazy' from PmApiTest", { since: mark });
  await expect.poll(async () => (await entries()).includes(LAZY_MARKER), { timeout: 20_000 }).toBe(true);
});

// Last in this file: it leaves PmApiTest disabled for the rest of the run.
test('the packs of a plugin that is disabled are unregistered and leave the merged pack', async ({ server }) => {
  expect(await consoleCommand(server, 'pmapitest register')).toContain('registered api-test');
  await expect.poll(async () => (await entries()).includes(API_MARKER), { timeout: 20_000 }).toBe(true);

  const mark = serverLogMark(server);
  await consoleCommand(server, 'pmapitest disable');
  await expect(server).toHaveReceivedMessage('[api-packs] PmApiTest was disabled; unregistered api-test',
    { since: mark, timeout: 10_000 });
  await expect.poll(async () => (await entries()).includes(API_MARKER), { timeout: 20_000 }).toBe(false);
});

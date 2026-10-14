import { expect, test } from '@plugwright/runner';
import { PACK_URL, SELF_HOST_PORT, consoleCommand } from './support/pack.js';

test('plugin enables cleanly and ships the first merge', async ({ server }) => {
  // since: 0 reads the log from server start instead of from the start of this test.
  const all = { since: 0, timeout: 15_000 };
  await expect(server).toHaveReceivedMessage('PackMerger enabled!', all);
  await expect(server).toHaveReceivedMessage(`Self-host HTTP server started on port ${SELF_HOST_PORT}`, all);
  await expect(server).toHaveReceivedMessage('Discovered 2 pack(s): [overlay-pack, base-pack]', all);
  await expect(server).toHaveReceivedMessage('Merge order (highest priority first): [overlay-pack, base-pack]', all);
  await expect(server).toHaveReceivedMessage(/Merged pack SHA1: [0-9a-f]{40}/, all);
  await expect(server).toHaveReceivedMessage(`Pack uploaded successfully: ${PACK_URL}`, all);

  const quiet = { since: 0 };
  await expect(server).not.toHaveReceivedMessage(/\[PackMerger\].*\[ERROR\]/, quiet);
  await expect(server).not.toHaveReceivedMessage(/Exception/, quiet);
  // Profiles are defined but active-profile is "" (documented as "no profile in use"),
  // which must not be reported as a misconfiguration.
  await expect(server).not.toHaveReceivedMessage('is not defined under profiles', quiet);
});

test('/pm status reports provider, pack URL, hash and pack count', async ({ player }) => {
  await player.makeOp();
  player.chat('/pm status');
  await expect(player).toHaveReceivedMessage('PackMerger Status');
  await expect(player).toHaveReceivedMessage('Server: e2e');
  await expect(player).toHaveReceivedMessage('Upload Provider: self-host');
  await expect(player).toHaveReceivedMessage(/Last Merge: \d{4}-\d{2}-\d{2}/);
  await expect(player).toHaveReceivedMessage(`Pack URL: ${PACK_URL}`);
  await expect(player).toHaveReceivedMessage(/SHA1: [0-9a-f]{40}/);
  await expect(player).toHaveReceivedMessage(/Pack Size: \d+(\.\d)? (B|KB)/);
  await expect(player).toHaveReceivedMessage('Merging: No');
  await expect(player).toHaveReceivedMessage('Packs in folder: 2');
});

test('/packmerger status also works from the server console', async ({ server }) => {
  const reply = await consoleCommand(server, 'packmerger status');
  expect(reply).toContain('PackMerger Status');
  expect(reply).toContain('Upload Provider: self-host');
  expect(reply).toContain(`Pack URL: ${PACK_URL}`);
  expect(reply).toContain('Packs in folder: 2');
});

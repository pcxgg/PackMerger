import { expect, test } from '@plugwright/runner';
import { existsSync, readFileSync } from 'node:fs';
import { PLUGIN_DIR } from './support/pack.js';

test('/pm inspect summarises the last merge', async ({ player }) => {
  await player.makeOp();
  player.chat('/pm inspect');
  await expect(player).toHaveReceivedMessage('PackMerger inspect');
  await expect(player).toHaveReceivedMessage('Pack order (highest priority first):');
  await expect(player).toHaveReceivedMessage(/^\s*1\. overlay-pack$/);
  await expect(player).toHaveReceivedMessage(/^\s*2\. base-pack$/);
  await expect(player).toHaveReceivedMessage('Total files: 6, collisions: 6, JSON merged: 5');
});

test('/pm inspect collisions lists every path touched by both packs', async ({ player }) => {
  await player.makeOp();
  player.chat('/pm inspect collisions');
  await expect(player).toHaveReceivedMessage('Inspect: collisions');
  await expect(player).toHaveReceivedMessage('6 file(s) contributed by two or more packs.');
  await expect(player).toHaveReceivedMessage('assets/minecraft/lang/en_us.json');
  await expect(player).toHaveReceivedMessage('assets/pmtest/marker.txt');
});

test('/pm inspect <pack> shows what a pack won and lost', async ({ player }) => {
  await player.makeOp();
  player.chat('/pm inspect overlay-pack');
  await expect(player).toHaveReceivedMessage('Inspect: overlay-pack');
  await expect(player).toHaveReceivedMessage(/Won: 6\s+Contributed but overridden: 0/);

  player.chat('/pm inspect base-pack');
  await expect(player).toHaveReceivedMessage('Inspect: base-pack');
  await expect(player).toHaveReceivedMessage(/Won: 0\s+Contributed but overridden: 6/);
  await expect(player).toHaveReceivedMessage('assets/pmtest/marker.txt → overlay-pack');
});

test('/pm inspect with an unknown pack lists the known ones', async ({ player }) => {
  await player.makeOp();
  player.chat('/pm inspect nosuchpack');
  await expect(player).toHaveReceivedMessage('Pack nosuchpack was not in the last merge.');
  await expect(player).toHaveReceivedMessage('Known packs: overlay-pack, base-pack');
});

test('/pm inspect export writes the full report to disk', async ({ player }) => {
  await player.makeOp();
  player.chat('/pm inspect export');
  await expect(player).toHaveReceivedMessage(/Wrote merge report to .*last-merge-report\.txt/);
  const report = `${PLUGIN_DIR}output/last-merge-report.txt`;
  expect(existsSync(report)).toBe(true);
  const text = readFileSync(report, 'utf8');
  expect(text).toContain('overlay-pack');
  expect(text).toContain('assets/minecraft/lang/en_us.json');
});

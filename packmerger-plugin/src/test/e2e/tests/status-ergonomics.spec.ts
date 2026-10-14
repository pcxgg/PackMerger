import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { expect, sleep, test } from '@plugwright/runner';
import { PACK_URL, PLUGIN_DIR, RUN_DIR, consoleCommand, downloadPack, serverLogMark } from './support/pack.js';

const CONFIG = `${PLUGIN_DIR}config.yml`;

test('/pm status reports the last upload, integrations, other hosts, Bedrock and logging', async ({ player }) => {
  await player.makeOp();
  const since = player.getMessageBufferIndex();
  player.chat('/pm status');
  await expect(player).toHaveReceivedMessage(/Last upload: ok at \d{2}:\d{2}:\d{2} \(http:\/\/localhost:\d+\/pack\)/,
    { since });
  await expect(player).toHaveReceivedMessage('Plugin packs: discovery off (plugin-packs.enabled: false)', { since });
  await expect(player).toHaveReceivedMessage('Other pack hosts: none (checked: itemsadder)', { since });
  await expect(player).toHaveReceivedMessage(
    'Bedrock: conversion off; Floodgate not installed; Java pack not sent to Bedrock players', { since });
  await expect(player).toHaveReceivedMessage('Logging: debug (verbose on; /pm verbose on|off)', { since });
});

test('/pm verbose off|on switches debug logging at runtime and saves log-level', async ({ player, server, cleanup }) => {
  const original = readFileSync(CONFIG, 'utf8');
  cleanup(async () => {
    writeFileSync(CONFIG, original);
    await consoleCommand(server, 'pm verbose on');
  });
  await player.makeOp();

  let since = player.getMessageBufferIndex();
  player.chat('/pm verbose off');
  await expect(player).toHaveReceivedMessage('Verbose logging disabled (log-level: info, saved to config.yml)', { since });
  expect(readFileSync(CONFIG, 'utf8')).toMatch(/^log-level: (info|"info"|'info')$/m);
  // Debug lines (every merge writes 'Wrote merge provenance' at debug) stop; info lines keep coming.
  let mark = serverLogMark(server);
  await consoleCommand(server, 'pm merge');
  await expect(server).toHaveReceivedMessage(/Merged pack SHA1: [0-9a-f]{40}/, { since: mark, timeout: 15_000 });
  await sleep(1_000);
  await expect(server).not.toHaveReceivedMessage('Wrote merge provenance', { since: mark });

  since = player.getMessageBufferIndex();
  player.chat('/pm verbose');
  await expect(player).toHaveReceivedMessage('Verbose logging is off (log-level: info).', { since });

  since = player.getMessageBufferIndex();
  player.chat('/pm verbose on');
  await expect(player).toHaveReceivedMessage('Verbose logging enabled (log-level: debug, saved to config.yml)', { since });
  expect(readFileSync(CONFIG, 'utf8')).toMatch(/^log-level: (debug|"debug"|'debug')$/m);
  mark = serverLogMark(server);
  await consoleCommand(server, 'pm merge');
  await expect(server).toHaveReceivedMessage('Wrote merge provenance', { since: mark, timeout: 15_000 });
});

test('merge.output-copy-to gets a byte-identical copy of every merged pack', async ({ server }) => {
  const mark = serverLogMark(server);
  await consoleCommand(server, 'pm merge');
  await expect(server).toHaveReceivedMessage(`Pack uploaded successfully: ${PACK_URL}`, { since: mark, timeout: 15_000 });
  await expect(server).toHaveReceivedMessage('[output] copied merged pack to ', { since: mark });
  const served = await downloadPack();
  const copy = readFileSync(`${RUN_DIR}plugins/PmE2ECopy/merged-pack.zip`);
  expect(createHash('sha1').update(copy).digest('hex')).toBe(served.sha1);
});

import { existsSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { expect, sleep, test } from '@plugwright/runner';
import { PLUGIN_DIR, RUN_DIR, consoleCommand, serverLogMark } from './support/pack.js';

// Competing-host detection and /pm fixup against a stand-in "ItemsAdder" plugin (an
// empty jar from src/e2ePlugin) and its config in plugins/ItemsAdder/config.yml.

const IA_DIR = `${RUN_DIR}plugins/ItemsAdder`;
const IA_CONFIG = `${IA_DIR}/config.yml`;
const DISMISSED = `${PLUGIN_DIR}dismissed-warnings.yml`;
const CONFLICTING = `# ItemsAdder config written by other-hosts.spec
resource-pack:
  hosting:
    no-host:
      enabled: false
    self-host:
      enabled: true
      pack-port: 8163
  zip:
    protect-file-from-unzip:
      protection_1: true
    compress-json-files: true
`;

function backups(): string[] {
  return readdirSync(IA_DIR).filter((f) => f.startsWith('config.yml.packmerger-backup-'));
}

test('ItemsAdder hosting its own pack is reported, fixed with /pm fixup, and backed up',
  async ({ player, server, cleanup }) => {
    const original = readFileSync(IA_CONFIG, 'utf8');
    cleanup(async () => {
      writeFileSync(IA_CONFIG, original);
      for (const b of backups()) rmSync(`${IA_DIR}/${b}`);
      await consoleCommand(server, 'pm reload');
      await sleep(3_000);
    });
    await player.makeOp();

    // 1. A config that hosts, protects and compresses: warned on the console and in /pm status.
    writeFileSync(IA_CONFIG, CONFLICTING);
    const mark = serverLogMark(server);
    await consoleCommand(server, 'pm reload');
    await expect(server).toHaveReceivedMessage(
      '[hosts] ItemsAdder resource-pack.hosting.self-host.enabled: true (ItemsAdder hosts/sends its own pack',
      { since: mark, timeout: 10_000 });
    await expect(server).toHaveReceivedMessage('resource-pack.zip.protect-file-from-unzip.protection_1: true',
      { since: mark });
    let since = player.getMessageBufferIndex();
    player.chat('/pm status');
    await expect(player).toHaveReceivedMessage(
      'Other pack host: ItemsAdder resource-pack.hosting.self-host.enabled: true (set false; /pm fixup itemsadder)',
      { since });
    await expect(player).toHaveReceivedMessage('Other pack host: ItemsAdder resource-pack.zip.compress-json-files: true',
      { since });

    // 2. /pm fixup itemsadder rewrites the config, keeps a backup and says what to run next.
    since = player.getMessageBufferIndex();
    player.chat('/pm fixup itemsadder');
    await expect(player).toHaveReceivedMessage('Updated ItemsAdder/config.yml:', { since, timeout: 10_000 });
    await expect(player).toHaveReceivedMessage('resource-pack.hosting.no-host.enabled: true', { since });
    await expect(player).toHaveReceivedMessage('Now run /iareload, then /iazip so it takes effect', { since });
    expect(backups().length).toBe(1);
    expect(readFileSync(`${IA_DIR}/${backups()[0]}`, 'utf8')).toBe(CONFLICTING);
    const fixed = readFileSync(IA_CONFIG, 'utf8');
    expect(fixed).toMatch(/no-host:\n\s+enabled: true/);
    expect(fixed).toMatch(/self-host:\n\s+enabled: false/);
    expect(fixed).toMatch(/protection_1: false/);
    expect(fixed).toMatch(/compress-json-files: false/);
    expect(fixed).toContain('pack-port: 8163');

    since = player.getMessageBufferIndex();
    player.chat('/pm status');
    await expect(player).toHaveReceivedMessage('Other pack hosts: none (checked: itemsadder)', { since });
    since = player.getMessageBufferIndex();
    player.chat('/pm fixup itemsadder');
    await expect(player).toHaveReceivedMessage('ItemsAdder: nothing to change', { since, timeout: 10_000 });
  });

test('admins are told on join; /pm fixup dismiss hides it, persisted across a reload',
  async ({ player, server, cleanup }) => {
    const original = readFileSync(IA_CONFIG, 'utf8');
    cleanup(async () => {
      writeFileSync(IA_CONFIG, original);
      rmSync(DISMISSED, { force: true });
      await consoleCommand(server, 'pm reload');
      await sleep(3_000);
    });
    writeFileSync(IA_CONFIG, CONFLICTING);
    await consoleCommand(server, 'pm reload');
    await sleep(2_000);
    await player.makeOp();
    await player.rejoin();
    await expect(player).toHaveReceivedMessage(
      /\[PackMerger\] ItemsAdder is set up to host or protect its own pack \(resource-pack\.hosting\.self-host\.enabled: true.*Run \/pm fixup itemsadder/,
      { timeout: 10_000 });

    let since = player.getMessageBufferIndex();
    player.chat('/pm fixup dismiss');
    await expect(player).toHaveReceivedMessage('Dismissed 3 warning(s).', { since, timeout: 10_000 });
    expect(existsSync(DISMISSED)).toBe(true);
    expect(readFileSync(DISMISSED, 'utf8')).toContain('itemsadder:resource-pack.hosting.self-host.enabled=true');

    await consoleCommand(server, 'pm reload');
    await sleep(2_000);
    since = player.getMessageBufferIndex();
    await player.rejoin();
    // Positive anchor: the hosting check's join notice is sent 40 ticks after join; wait past it.
    await sleep(5_000);
    await expect(player).not.toHaveReceivedMessage('is set up to host or protect its own pack', { since });
    since = player.getMessageBufferIndex();
    player.chat('/pm status');
    await expect(player).toHaveReceivedMessage(
      'Other pack host: ItemsAdder resource-pack.hosting.self-host.enabled: true (dismissed; /pm fixup itemsadder)',
      { since });
  });

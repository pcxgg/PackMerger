import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { crc32, inflateRawSync } from 'node:zlib';

/** Port the self-host provider listens on (see fixtures/PackMerger/config.yml). */
/** The Paper server's run directory (this file compiles to dist/tests/support/pack.js). */
export const RUN_DIR = fileURLToPath(new URL('../../../generated/local/run/', import.meta.url));
export const PLUGIN_DIR = `${RUN_DIR}plugins/PackMerger/`;

/** Ports the Gradle build staged for this run (see packmerger-plugin/build.gradle). */
const PORTS: { httpPort: number; serverPort: number } = JSON.parse(readFileSync(`${PLUGIN_DIR}e2e-ports.json`, 'utf8'));
export const SELF_HOST_PORT: number = PORTS.httpPort;
/** URL PackMerger auto-builds for the self-host provider (empty server-ip -> localhost). */
export const PACK_URL = `http://localhost:${SELF_HOST_PORT}/pack`;

export interface DownloadedPack {
  status: number;
  contentType: string | null;
  bytes: Buffer;
  sha1: string;
  entries: Map<string, Buffer>;
}

/** Downloads the merged pack from PackMerger's built-in HTTP server and unpacks it in memory. */
export async function downloadPack(): Promise<DownloadedPack> {
  const res = await fetch(PACK_URL);
  const bytes = Buffer.from(await res.arrayBuffer());
  return {
    status: res.status,
    contentType: res.headers.get('content-type'),
    bytes,
    sha1: createHash('sha1').update(bytes).digest('hex'),
    entries: res.status === 200 ? readZip(bytes) : new Map(),
  };
}

export function entryText(pack: DownloadedPack, path: string): string {
  const data = pack.entries.get(path);
  if (!data) throw new Error(`merged pack has no entry ${path}; entries: ${[...pack.entries.keys()].join(', ')}`);
  return data.toString('utf8');
}

export function entryJson(pack: DownloadedPack, path: string): any {
  return JSON.parse(entryText(pack, path));
}

/** Minimal ZIP reader (central directory + stored/deflated entries). Enough for PackMerger's output. */
export function readZip(buf: Buffer): Map<string, Buffer> {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`not a zip archive (${buf.length} bytes)`);
  const count = buf.readUInt16LE(eocd + 10);
  let ptr = buf.readUInt32LE(eocd + 16);
  const out = new Map<string, Buffer>();
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(ptr) !== 0x02014b50) throw new Error('corrupt central directory');
    const method = buf.readUInt16LE(ptr + 10);
    const compSize = buf.readUInt32LE(ptr + 20);
    const nameLen = buf.readUInt16LE(ptr + 28);
    const extraLen = buf.readUInt16LE(ptr + 30);
    const commentLen = buf.readUInt16LE(ptr + 32);
    const localOffset = buf.readUInt32LE(ptr + 42);
    const name = buf.toString('utf8', ptr + 46, ptr + 46 + nameLen);
    ptr += 46 + nameLen + extraLen + commentLen;
    if (name.endsWith('/')) continue;
    const dataStart = localOffset + 30 + buf.readUInt16LE(localOffset + 26) + buf.readUInt16LE(localOffset + 28);
    const raw = buf.subarray(dataStart, dataStart + compSize);
    out.set(name, method === 0 ? Buffer.from(raw) : inflateRawSync(raw));
  }
  return out;
}

/** Raw add_resource_pack packet as decoded by minecraft-protocol. */
export interface ResourcePackOffer {
  uuid: string;
  url: string;
  hash: string;
  forced: boolean;
}

/**
 * Resolves with the next resource-pack offer the bot's client receives, or rejects after
 * timeoutMs. Listens on the raw protocol client so the pack UUID/hash are available.
 */
export function nextResourcePackOffer(bot: any, timeoutMs = 10_000): Promise<ResourcePackOffer> {
  return new Promise((resolve, reject) => {
    const onPack = (data: any) => {
      clearTimeout(timer);
      resolve({ uuid: data.uuid, url: data.url, hash: data.hash, forced: data.forced });
    };
    const timer = setTimeout(() => {
      bot._client.removeListener('add_resource_pack', onPack);
      reject(new Error(`no resource pack offer within ${timeoutMs} ms`));
    }, timeoutMs);
    bot._client.once('add_resource_pack', onPack);
  });
}

/** Resource-pack status ids, per the protocol (and Bukkit's PlayerResourcePackStatusEvent.Status order). */
const PACK_STATUS = {
  loaded: 0, declined: 1, failed_download: 2, accepted: 3,
  downloaded: 4, invalid_url: 5, failed_reload: 6, discarded: 7,
} as const;
export type PackStatus = keyof typeof PACK_STATUS;

/** Answers a resource-pack offer the way a vanilla client does. */
export function answerResourcePack(bot: any, uuid: string, result: PackStatus): void {
  bot._client.write('resource_pack_receive', { uuid, result: PACK_STATUS[result] });
}

/** Resolves 'offered' if a resource-pack offer arrives within ms, otherwise 'not offered'. */
export function offeredWithin(bot: any, ms: number): Promise<'offered' | 'not offered'> {
  return nextResourcePackOffer(bot, ms).then(() => 'offered' as const, () => 'not offered' as const);
}

/**
 * The pack UUID PackMerger uses for a server: Java's
 * UUID.nameUUIDFromBytes(("packmerger:" + serverName).getBytes(UTF_8)), i.e. an MD5 name-based (v3) UUID.
 */
export function packUuidForServer(serverName: string): string {
  const md5 = createHash('md5').update(`packmerger:${serverName}`, 'utf8').digest();
  md5[6] = (md5[6] & 0x0f) | 0x30;
  md5[8] = (md5[8] & 0x3f) | 0x80;
  const hex = md5.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Current end of the shared server log, for `{ since }` on server assertions made mid-test. */
export function serverLogMark(server: any): number {
  return server.session.consoleLog.length;
}

/**
 * Runs a console command (over RCON in LocalMode) and returns the reply with legacy
 * colour codes stripped. Replies to the console sender come back here, not in the log.
 */
export async function consoleCommand(server: { execute(cmd: string): Promise<string> }, cmd: string): Promise<string> {
  return (await server.execute(cmd)).replace(/§[0-9a-fk-orx]/gi, '');
}

/** Builds a minimal stored (uncompressed) ZIP archive, enough for PackMerger to read. */
export function buildZip(files: Record<string, string>): Buffer {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const [name, content] of Object.entries(files)) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.from(content, 'utf8');
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    locals.push(local, nameBuf, data);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBuf);
    offset += 30 + nameBuf.length + data.length;
  }
  const centralSize = centrals.reduce((n, b) => n + b.length, 0);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(Object.keys(files).length, 8);
  eocd.writeUInt16LE(Object.keys(files).length, 10);
  eocd.writeUInt32LE(centralSize, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, ...centrals, eocd]);
}

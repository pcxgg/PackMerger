import { createHash, createHmac } from 'node:crypto';

/** Plugin channel between PackMerger backends and the PackMerger proxy plugin. */
export const CHANNEL = 'packmerger:pack';

/** The pack UUID the proxy plugin offers under: Java UUID.nameUUIDFromBytes("packmerger:proxy"). */
export const PROXY_PACK_ID = nameUuid('packmerger:proxy');

export interface PackMessage {
  kind: 'PUBLISH' | 'OFFER';
  server: string;
  timestamp: bigint;
  url: string;
  sha1: string;
  player: string | null;
  packId: string | null;
  required: boolean;
  prompt: string;
}

export interface DecodedMessage {
  message: PackMessage;
  body: Buffer;
  signature: Buffer;
}

/** Java's UUID.nameUUIDFromBytes (MD5, version 3). */
export function nameUuid(name: string): string {
  const md5 = createHash('md5').update(name, 'utf8').digest();
  md5[6] = (md5[6] & 0x0f) | 0x30;
  md5[8] = (md5[8] & 0x3f) | 0x80;
  return formatUuid(md5.toString('hex'));
}

function formatUuid(hex: string): string {
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function utf(s: string): Buffer {
  const b = Buffer.from(s, 'utf8');
  const len = Buffer.alloc(2);
  len.writeUInt16BE(b.length);
  return Buffer.concat([len, b]);
}

function uuidBytes(id: string | null): Buffer {
  return id ? Buffer.from(id.replace(/-/g, ''), 'hex') : Buffer.alloc(16);
}

/** Encodes a version-2 message like PackMessaging.encode, signed with HMAC-SHA256 when a secret is given. */
export function encodeMessage(m: PackMessage, secret: string | null): Buffer {
  const ts = Buffer.alloc(8);
  ts.writeBigInt64BE(m.timestamp);
  const body = Buffer.concat([
    Buffer.from([0, 0, 0x50, 0x4d, 2, m.kind === 'PUBLISH' ? 1 : 2]),
    utf(m.server), ts, utf(m.url), utf(m.sha1),
    uuidBytes(m.player), uuidBytes(m.packId),
    Buffer.from([m.required ? 1 : 0]), utf(m.prompt),
  ]);
  const sig = secret ? createHmac('sha256', Buffer.from(secret, 'utf8')).update(body).digest() : Buffer.alloc(0);
  const sigLen = Buffer.alloc(2);
  sigLen.writeUInt16BE(sig.length);
  return Buffer.concat([body, sigLen, sig]);
}

/** Decodes a version-2 message (throws on anything else). */
export function decodeMessage(buf: Buffer): DecodedMessage {
  if (buf.readUInt32BE(0) !== 0x0000504d) throw new Error(`not a v2 PackMerger message: ${buf.toString('hex')}`);
  if (buf[4] !== 2) throw new Error(`unexpected version ${buf[4]}`);
  let p = 6;
  const readUtf = () => { const n = buf.readUInt16BE(p); const s = buf.toString('utf8', p + 2, p + 2 + n); p += 2 + n; return s; };
  const readUuid = () => { const hex = buf.toString('hex', p, p + 16); p += 16; return /^0+$/.test(hex) ? null : formatUuid(hex); };
  const kind = buf[5] === 1 ? 'PUBLISH' : 'OFFER';
  const server = readUtf();
  const timestamp = buf.readBigInt64BE(p); p += 8;
  const url = readUtf();
  const sha1 = readUtf();
  const player = readUuid();
  const packId = readUuid();
  const required = buf[p] === 1; p += 1;
  const prompt = readUtf();
  const body = buf.subarray(0, p);
  const sigLen = buf.readUInt16BE(p);
  const signature = buf.subarray(p + 2, p + 2 + sigLen);
  return { message: { kind, server, timestamp, url, sha1, player, packId, required, prompt }, body, signature };
}

export function hmac(secret: string, body: Buffer): Buffer {
  return createHmac('sha256', Buffer.from(secret, 'utf8')).update(body).digest();
}

/** Makes the bot's connection listen on the channel, like a proxy with the PackMerger plugin does. */
export function registerChannel(bot: any): void {
  bot._client.write('custom_payload', { channel: 'minecraft:register', data: Buffer.from(CHANNEL, 'utf8') });
}

/** Resolves with the next message the server sends the bot on the channel. */
export function nextChannelMessage(bot: any, timeoutMs = 10_000): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const onPayload = (packet: any) => {
      if (packet.channel !== CHANNEL) return;
      clearTimeout(timer);
      bot._client.removeListener('custom_payload', onPayload);
      resolve(Buffer.from(packet.data));
    };
    const timer = setTimeout(() => {
      bot._client.removeListener('custom_payload', onPayload);
      reject(new Error(`no ${CHANNEL} message within ${timeoutMs} ms`));
    }, timeoutMs);
    bot._client.on('custom_payload', onPayload);
  });
}

/** Sends a message to the server on the channel, as the proxy would. */
export function sendChannelMessage(bot: any, payload: Buffer): void {
  bot._client.write('custom_payload', { channel: CHANNEL, data: payload });
}

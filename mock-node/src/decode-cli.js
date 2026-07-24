#!/usr/bin/env node
// Tiny decode helper: turn a hex 32-byte record (or several, concatenated) into
// readable fields. Mirrors the future `node decode` CLI surface (§7). Usage:
//   node src/decode-cli.js <hex>
//   echo <hex> | node src/decode-cli.js

import { decodeRecord, RECORD_BYTES } from './record.js';

const hex = (process.argv[2] ?? (await readStdin())).replace(/\s+/g, '');
if (!hex) {
  console.error('usage: decode-cli <hex-record>  (32 bytes = 64 hex chars each)');
  process.exit(2);
}
const buf = Buffer.from(hex, 'hex');
if (buf.length % RECORD_BYTES !== 0) {
  console.error(`hex is ${buf.length} bytes, not a multiple of ${RECORD_BYTES}`);
  process.exit(2);
}
for (let off = 0; off < buf.length; off += RECORD_BYTES) {
  console.log(JSON.stringify(decodeRecord(buf, off), null, 2));
}

async function readStdin() {
  const chunks = [];
  for await (const c of process.stdin) chunks.push(c);
  return Buffer.concat(chunks).toString('utf8').trim();
}

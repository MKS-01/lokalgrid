// Codec tests — round-trip, CRC rejection, exact byte layout, and (once
// generated) the golden vectors. Run: `npm test`.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  encodeRecord,
  decodeRecord,
  packFlags,
  FLAG,
  RECORD_BYTES,
  BARO_ABSENT,
  TEMP_ABSENT,
} from '../src/record.js';
import { TrackGenerator } from '../src/track.js';

const here = dirname(fileURLToPath(import.meta.url));

test('every record is exactly 32 bytes', () => {
  const gen = new TrackGenerator({ epoch0: 1_800_000_000, seed: 7 });
  for (let i = 0; i < 100; i++) {
    assert.equal(gen.nextEncoded().length, RECORD_BYTES);
  }
});

test('encode → decode round-trips all fields', () => {
  const fields = {
    epoch: 1_800_000_000,
    lat_e7: -338_688_000,
    lon_e7: 1_512_093_000,
    alt: -12,
    baro: BARO_ABSENT,
    spd: 2500,
    hdg: 35_999,
    sv: 12,
    hd: 7,
    bat: 5,
    tmp: -8,
    flags: packFlags({ bits: FLAG.TIME_VALID | FLAG.FIX_3D, zoneMask: 0x5a, seq: 0xffff }),
  };
  const decoded = decodeRecord(encodeRecord(fields));
  for (const k of Object.keys(fields)) assert.equal(decoded[k], fields[k], `field ${k}`);
  assert.equal(decoded.zoneMask, 0x5a);
  assert.equal(decoded.seqLo, 0xffff);
});

test('field layout is little-endian at the documented offsets', () => {
  const buf = encodeRecord({
    epoch: 0x01020304,
    lat_e7: 0,
    lon_e7: 0,
    tmp: TEMP_ABSENT,
    baro: BARO_ABSENT,
  });
  // epoch u32 LE at offset 0
  assert.deepEqual([...buf.subarray(0, 4)], [0x04, 0x03, 0x02, 0x01]);
  // temp i8 sentinel at offset 23
  assert.equal(buf.readInt8(23), -128);
  // baro i16 sentinel 0x8000 at offset 14
  assert.deepEqual([...buf.subarray(14, 16)], [0x00, 0x80]);
});

test('a flipped byte fails the CRC check', () => {
  const buf = encodeRecord({ epoch: 42, lat_e7: 1, lon_e7: 2 });
  buf[4] ^= 0xff; // corrupt lat
  assert.throws(() => decodeRecord(buf), /crc32 mismatch/);
});

test('golden vectors decode to their stated fields', () => {
  const file = join(here, '..', 'golden', 'vectors.json');
  if (!existsSync(file)) {
    // golden set not generated yet — skip rather than fail a fresh checkout
    return;
  }
  const { vectors } = JSON.parse(readFileSync(file, 'utf8'));
  for (const v of vectors) {
    const buf = Buffer.from(v.hex, 'hex');
    assert.equal(buf.length, RECORD_BYTES, `${v.name} length`);
    const decoded = decodeRecord(buf);
    for (const k of Object.keys(v.fields)) {
      assert.equal(decoded[k], v.fields[k], `${v.name}.${k}`);
    }
    // and re-encoding the fields reproduces the exact bytes
    assert.equal(encodeRecord(v.fields).toString('hex'), v.hex, `${v.name} re-encode`);
  }
});

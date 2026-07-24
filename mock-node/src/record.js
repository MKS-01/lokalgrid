// Track record codec — 32 bytes, fixed width, little-endian (PROJECT.md §4).
//
// The whole design is `offset = index * 32`, so the layout never shrinks per
// build: absent sensors write sentinels, they do not remove the field. This is
// the JS half of the "one wire format, two hand-written codecs" plan — the
// Kotlin app decodes exactly these bytes. When the two disagree, that is the
// Phase 05 drift lesson arriving on schedule.

import { crc32 } from './crc32.js';

export const RECORD_BYTES = 32;

// Sentinels for absent sensors — written, never omitted.
export const BARO_ABSENT = -32768; //      i16 sentinel (bytes 0x00 0x80), "no BME280"
export const TEMP_ABSENT = -128; //        i8  sentinel (0x80), "no temp"

// Flags word (offset 24, u32) — bit positions from §4.
export const FLAG = {
  TIME_VALID: 1 << 0, // clear when logged without a fix; client repairs timestamp
  FIX_3D: 1 << 1, //     clear => 2D fix, altitude is fictional
  MOTION: 1 << 2, //     IMU or GNSS-speed said moving
  TRIP_START: 1 << 3, // first point after wake — segments trips
  TAMPER: 1 << 4, //     reserved, unused
  CHARGING: 1 << 5, //   from PMU
  // bits 6-7 reserved, validate zero on read
  // bits 8-15  zone_mask  (see helpers below)
  // bits 16-31 seq_lo     (low 16 bits of the monotonic counter)
};

export const zoneMask = (flags) => (flags >>> 8) & 0xff;
export const seqLo = (flags) => (flags >>> 16) & 0xffff;

/**
 * Build a flags word from parts. `seq` and `zoneMask` are packed into their
 * high bits; `bits` is an OR of FLAG.* values for the low byte.
 */
export function packFlags({ bits = 0, zoneMask = 0, seq = 0 }) {
  return (
    ((bits & 0xff) | ((zoneMask & 0xff) << 8) | ((seq & 0xffff) << 16)) >>> 0
  );
}

/**
 * Encode one track record to a 32-byte Buffer. Missing fields fall back to
 * their sentinels so the width is always 32. `crc32` is computed over the
 * first 28 bytes and written into the trailer.
 */
export function encodeRecord(r) {
  const b = Buffer.alloc(RECORD_BYTES);
  b.writeUInt32LE(r.epoch >>> 0, 0);
  b.writeInt32LE(r.lat_e7 | 0, 4);
  b.writeInt32LE(r.lon_e7 | 0, 8);
  b.writeInt16LE(clampI16(r.alt ?? 0), 12);
  b.writeInt16LE(clampI16(r.baro ?? BARO_ABSENT), 14);
  b.writeUInt16LE(clampU16(r.spd ?? 0), 16);
  b.writeUInt16LE(clampU16(r.hdg ?? 0), 18);
  b.writeUInt8((r.sv ?? 0) & 0xff, 20);
  b.writeUInt8((r.hd ?? 0) & 0xff, 21);
  b.writeUInt8((r.bat ?? 0) & 0xff, 22);
  b.writeInt8(clampI8(r.tmp ?? TEMP_ABSENT), 23);
  b.writeUInt32LE((r.flags ?? 0) >>> 0, 24);
  b.writeUInt32LE(crc32(b, 0, 28), 28);
  return b;
}

/**
 * Decode a 32-byte record. Throws on a bad length or CRC mismatch — a caller
 * doing sync recovery should catch and re-request from its last good offset.
 */
export function decodeRecord(b, offset = 0) {
  if (b.length - offset < RECORD_BYTES) {
    throw new RangeError(`record needs ${RECORD_BYTES} bytes, got ${b.length - offset}`);
  }
  const stored = b.readUInt32LE(offset + 28);
  const computed = crc32(b, offset, offset + 28);
  if (stored !== computed) {
    throw new Error(`crc32 mismatch: stored ${hex32(stored)} computed ${hex32(computed)}`);
  }
  const flags = b.readUInt32LE(offset + 24);
  return {
    epoch: b.readUInt32LE(offset + 0),
    lat_e7: b.readInt32LE(offset + 4),
    lon_e7: b.readInt32LE(offset + 8),
    alt: b.readInt16LE(offset + 12),
    baro: b.readInt16LE(offset + 14),
    spd: b.readUInt16LE(offset + 16),
    hdg: b.readUInt16LE(offset + 18),
    sv: b.readUInt8(offset + 20),
    hd: b.readUInt8(offset + 21),
    bat: b.readUInt8(offset + 22),
    tmp: b.readInt8(offset + 23),
    flags,
    // convenience fields, not on the wire:
    zoneMask: zoneMask(flags),
    seqLo: seqLo(flags),
    crc32: stored,
  };
}

const hex32 = (n) => '0x' + (n >>> 0).toString(16).padStart(8, '0');
const clampI16 = (v) => Math.max(-32768, Math.min(32767, v | 0));
const clampU16 = (v) => Math.max(0, Math.min(65535, v | 0));
const clampI8 = (v) => Math.max(-128, Math.min(127, v | 0));

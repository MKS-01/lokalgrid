// Golden vectors — the shared fixture both codecs must agree on. The Kotlin app
// decodes these exact hex strings in its own test suite; if C, Kotlin and JS
// all reproduce the same 32 bytes for the same fields, the wire format is one
// thing rather than three hopeful ones (§6). Run: `npm run golden`.
//
// Cases are chosen to exercise the sentinel and sign-extension paths that are
// where hand-written codecs actually drift — not the happy path.

import { writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  encodeRecord,
  decodeRecord,
  packFlags,
  FLAG,
  BARO_ABSENT,
  TEMP_ABSENT,
} from './record.js';

const cases = [
  {
    name: 'nominal_3d_fix',
    fields: {
      epoch: 1_800_000_000,
      lat_e7: 129_716_000, // 12.9716°N
      lon_e7: 775_946_000, // 77.5946°E
      alt: 920,
      baro: 9800,
      spd: 140,
      hdg: 12_345,
      sv: 11,
      hd: 9,
      bat: 87,
      tmp: 27,
      flags: packFlags({ bits: FLAG.TIME_VALID | FLAG.FIX_3D | FLAG.MOTION, seq: 1 }),
    },
  },
  {
    name: 'trip_start_stationary',
    fields: {
      epoch: 1_800_000_000,
      lat_e7: 129_716_000,
      lon_e7: 775_946_000,
      alt: 921,
      baro: BARO_ABSENT, // no BME280 — sentinel
      spd: 0,
      hdg: 0,
      sv: 8,
      hd: 21,
      bat: 100,
      tmp: TEMP_ABSENT, // no temp — sentinel
      flags: packFlags({ bits: FLAG.TIME_VALID | FLAG.FIX_3D | FLAG.TRIP_START }),
    },
  },
  {
    name: 'two_d_fix_no_time',
    fields: {
      epoch: 0, // logged before a fix — time_valid clear, client repairs
      lat_e7: 129_716_000,
      lon_e7: 775_946_000,
      alt: 0, // 2D fix: altitude fictional
      baro: BARO_ABSENT,
      spd: 0,
      hdg: 0,
      sv: 3,
      hd: 45,
      bat: 64,
      tmp: 30,
      flags: packFlags({ bits: 0, seq: 2 }), // neither TIME_VALID nor FIX_3D
    },
  },
  {
    name: 'southern_western_hemisphere',
    fields: {
      epoch: 1_800_000_500,
      lat_e7: -338_688_000, // 33.8688°S (Sydney-ish) — negative i32
      lon_e7: 1_512_093_000, // 151.2093°E
      alt: -12, // below sea level — negative i16
      baro: 10_130,
      spd: 2500,
      hdg: 35_999, // just under 360.00°
      sv: 12,
      hd: 7,
      bat: 5,
      tmp: -8, // negative °C — sign extension check
      flags: packFlags({ bits: FLAG.TIME_VALID | FLAG.FIX_3D | FLAG.CHARGING, seq: 0xffff }),
    },
  },
  {
    name: 'field_extremes',
    fields: {
      epoch: 0xffffffff, // max u32
      lat_e7: 900_000_000, // +90°
      lon_e7: -1_800_000_000, // −180°
      alt: 32_767, // max i16
      baro: -32_768, // min i16 (also the sentinel value, on purpose)
      spd: 65_535, // max u16
      hdg: 65_535,
      sv: 255,
      hd: 255,
      bat: 255,
      tmp: 127, // max i8
      flags: packFlags({ bits: 0xff, zoneMask: 0xff, seq: 0xffff }), // all bits set
    },
  },
];

const vectors = cases.map((c) => {
  const buf = encodeRecord(c.fields);
  const round = decodeRecord(buf); // self-check: encode→decode must round-trip
  for (const k of Object.keys(c.fields)) {
    if (round[k] !== c.fields[k]) {
      throw new Error(`${c.name}: field ${k} did not round-trip (${c.fields[k]} → ${round[k]})`);
    }
  }
  return { name: c.name, hex: buf.toString('hex'), fields: c.fields, crc32: round.crc32 };
});

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, '..', 'golden');
mkdirSync(outDir, { recursive: true });
const outFile = join(outDir, 'vectors.json');
writeFileSync(
  outFile,
  JSON.stringify(
    {
      note: 'Golden track-record vectors. 32-byte records, little-endian, CRC-32 (zlib/IEEE) trailer. Both the C firmware and the Kotlin app must reproduce these hex strings from these fields. Regenerating must leave this file byte-identical unless the wire format changed — no timestamp, so a git diff means the format moved.',
      recordBytes: 32,
      vectors,
    },
    null,
    2
  ) + '\n'
);

console.log(`wrote ${vectors.length} golden vectors → ${outFile}`);
for (const v of vectors) console.log(`  ${v.name.padEnd(28)} ${v.hex}`);

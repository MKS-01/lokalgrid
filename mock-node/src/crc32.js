// Standard CRC-32 (IEEE 802.3 / zlib), the same variant as java.util.zip.CRC32
// and ESP-IDF's esp_crc32_le(0, buf, len) — so the JS mock, the Kotlin app and
// the C firmware all agree on the record trailer.
//
// We use Node's built-in zlib.crc32 rather than a hand-rolled table: a first
// hand-written attempt here was subtly wrong (gave 0x2a239dc3 for "123456789"
// instead of 0xcbf43926) and disagreed with Java, which the golden-vector
// cross-check caught immediately. The built-in is the reference implementation.

import { crc32 as zlibCrc32 } from 'node:zlib';

/** CRC-32 over a Buffer/Uint8Array slice. Returns an unsigned 32-bit int. */
export function crc32(buf, start = 0, end = buf.length) {
  const view = start === 0 && end === buf.length ? buf : buf.subarray(start, end);
  return zlibCrc32(view) >>> 0;
}

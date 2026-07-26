/* Lokalgrid — the 32-byte track record (PROJECT.md §4), C half.
 *
 * This is the **third** hand-written implementation of one wire format: JS in
 * the mock, Kotlin in the app, C here. That is deliberate (§6) — codegen arrives
 * at Phase 05, *after* the drift has caused a real bug, because understanding
 * the format beats complying with a generator. The golden vectors in
 * `mock-node/golden/vectors.json` are what keep the three honest; `test/` builds
 * this file for the host and checks it against them.
 *
 * The whole design is `offset = index * 32`: fixed width, little-endian, absent
 * sensors write sentinels rather than shrinking the record.
 */
#pragma once

#include <stdint.h>
#include <stddef.h>

#define LG_RECORD_BYTES 32

/* Sentinels for absent sensors — written, never omitted. */
#define LG_BARO_ABSENT ((int16_t)-32768)
#define LG_TEMP_ABSENT ((int8_t)-128)

/* Flags word (offset 24) — bit positions from §4. */
#define LG_FLAG_TIME_VALID (1u << 0)
#define LG_FLAG_FIX_3D     (1u << 1)
#define LG_FLAG_MOTION     (1u << 2)
#define LG_FLAG_TRIP_START (1u << 3)
#define LG_FLAG_TAMPER     (1u << 4)   /* reserved, unused by decision (§2) */
#define LG_FLAG_CHARGING   (1u << 5)
/* bits 6-7 reserved (validate zero on read), 8-15 zone_mask, 16-31 seq_lo */

typedef struct {
    uint32_t epoch;    /* GPS time when time_valid */
    int32_t  lat_e7;
    int32_t  lon_e7;
    int16_t  alt;      /* metres, GNSS */
    int16_t  baro;     /* LG_BARO_ABSENT when no BME280 */
    uint16_t spd;      /* cm/s */
    uint16_t hdg;      /* centidegrees */
    uint8_t  sv;
    uint8_t  hd;       /* HDOP ×10 */
    uint8_t  bat;      /* percent */
    int8_t   tmp;      /* °C, LG_TEMP_ABSENT when absent */
    uint32_t flags;
} lg_record_t;

/** Pack the low bits, the zone mask and the sequence low word into `flags`. */
uint32_t lg_pack_flags(uint8_t bits, uint8_t zone_mask, uint16_t seq);

/** Encode into exactly LG_RECORD_BYTES, CRC-32 over the first 28. */
void lg_record_encode(const lg_record_t *r, uint8_t out[LG_RECORD_BYTES]);

/** CRC-32 (zlib/IEEE), the same polynomial the other two implementations use. */
uint32_t lg_crc32(const uint8_t *data, size_t len);

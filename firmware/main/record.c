#include "record.h"

/* Bit-at-a-time CRC-32. A table would be four times faster and 1 KiB larger;
 * at one record per second the cost is irrelevant, and this way the function is
 * short enough to read and compare against the other two implementations. */
uint32_t lg_crc32(const uint8_t *data, size_t len)
{
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (int b = 0; b < 8; b++) {
            crc = (crc >> 1) ^ (0xedb88320u & (uint32_t)(-(int32_t)(crc & 1)));
        }
    }
    return crc ^ 0xffffffffu;
}

uint32_t lg_pack_flags(uint8_t bits, uint8_t zone_mask, uint16_t seq)
{
    return (uint32_t)bits | ((uint32_t)zone_mask << 8) | ((uint32_t)seq << 16);
}

/* Written byte by byte rather than by casting a packed struct over the buffer:
 * the S3 is little-endian so a struct cast would work today and silently rot the
 * day anything else reads it. Explicit is the same speed here and travels. */
static void put_u16(uint8_t *p, uint16_t v)
{
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)(v >> 8);
}

static void put_u32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v & 0xff);
    p[1] = (uint8_t)((v >> 8) & 0xff);
    p[2] = (uint8_t)((v >> 16) & 0xff);
    p[3] = (uint8_t)((v >> 24) & 0xff);
}

void lg_record_encode(const lg_record_t *r, uint8_t out[LG_RECORD_BYTES])
{
    put_u32(&out[0], r->epoch);
    put_u32(&out[4], (uint32_t)r->lat_e7);
    put_u32(&out[8], (uint32_t)r->lon_e7);
    put_u16(&out[12], (uint16_t)r->alt);
    put_u16(&out[14], (uint16_t)r->baro);
    put_u16(&out[16], r->spd);
    put_u16(&out[18], r->hdg);
    out[20] = r->sv;
    out[21] = r->hd;
    out[22] = r->bat;
    out[23] = (uint8_t)r->tmp;
    put_u32(&out[24], r->flags);
    put_u32(&out[28], lg_crc32(out, 28));
}

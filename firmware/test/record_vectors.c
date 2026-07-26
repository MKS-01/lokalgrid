/* Host test: does the C codec produce the same bytes as the other two?
 *
 * §5 wants the pure logic to build for the host so CI needs no hardware. This is
 * that, at its smallest: read the field sets from the golden vectors, encode
 * them with the firmware's own encoder, print hex. `run.sh` diffs the output
 * against `mock-node/golden/vectors.json`, which the Kotlin suite also checks
 * itself against — so if C and Kotlin ever disagree, the difference is one line
 * of a diff rather than a mystery on a phone.
 *
 * Fields arrive on argv so this file stays free of a JSON parser; the shell
 * script is what knows where the vectors live.
 *
 *   ./record_vectors epoch lat lon alt baro spd hdg sv hd bat tmp flags
 */
#include <stdio.h>
#include <stdlib.h>

#include "../main/record.h"

int main(int argc, char **argv)
{
    if (argc != 13) {
        fprintf(stderr, "usage: %s epoch lat lon alt baro spd hdg sv hd bat tmp flags\n", argv[0]);
        return 2;
    }

    lg_record_t r = {
        .epoch  = (uint32_t)strtoul(argv[1], NULL, 10),
        .lat_e7 = (int32_t)strtol(argv[2], NULL, 10),
        .lon_e7 = (int32_t)strtol(argv[3], NULL, 10),
        .alt    = (int16_t)strtol(argv[4], NULL, 10),
        .baro   = (int16_t)strtol(argv[5], NULL, 10),
        .spd    = (uint16_t)strtoul(argv[6], NULL, 10),
        .hdg    = (uint16_t)strtoul(argv[7], NULL, 10),
        .sv     = (uint8_t)strtoul(argv[8], NULL, 10),
        .hd     = (uint8_t)strtoul(argv[9], NULL, 10),
        .bat    = (uint8_t)strtoul(argv[10], NULL, 10),
        .tmp    = (int8_t)strtol(argv[11], NULL, 10),
        .flags  = (uint32_t)strtoul(argv[12], NULL, 10),
    };

    uint8_t buf[LG_RECORD_BYTES];
    lg_record_encode(&r, buf);
    for (int i = 0; i < LG_RECORD_BYTES; i++) {
        printf("%02x", buf[i]);
    }
    printf("\n");
    return 0;
}

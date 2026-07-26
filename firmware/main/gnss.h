/* Lokalgrid — GNSS on UART1.
 *
 * The point of the whole product is a position you can trust, with its
 * uncertainty attached (§2, "Truth"). This is where that starts: NMEA in, a
 * 32-byte record out, HDOP and satellite count carried rather than smoothed away.
 *
 * The pins are the next unverified guess in `board_pins.h`, so this **probes**
 * instead of asserting — the same tactic that found the second I²C bus. It tries
 * a short list of candidate pin pairs and baud rates, looks for something that is
 * unmistakably a GNSS talking (`$G…` sentences, or a UBX preamble), and reports
 * which combination answered. A wrong guess then costs a log line rather than a
 * silent dead sensor.
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

/** A parsed fix, in the units the 32-byte record wants (§4). */
typedef struct {
    bool     valid;        /**< a fix, not just a talking receiver */
    bool     fix_3d;
    uint32_t epoch;        /**< GPS time, 0 when the date is not yet known */
    int32_t  lat_e7;
    int32_t  lon_e7;
    int16_t  alt_m;
    uint16_t speed_cms;
    uint16_t course_cdeg;
    uint8_t  sats;
    uint8_t  hdop_x10;
    int64_t  at_us;        /**< esp_timer when this was parsed, for staleness */
} lg_fix_t;

/**
 * Find the GNSS and start reading it. Returns false when nothing answered on any
 * candidate — which is a fact worth logging, not a reason to fail boot.
 *
 * Call *after* the GNSS rail is on: a receiver with no power is indistinguishable
 * from the wrong pins, and that ambiguity is exactly what wastes an evening.
 */
bool gnss_start(void);

/** The most recent fix, or a zeroed struct with `valid == false`. */
void gnss_get(lg_fix_t *out);

/** True once a valid fix has been seen — what flips `hello.mode` to "gnss".
 *  It describes the *source*, not the moment: a receiver that had a fix and has
 *  since lost one is still a GNSS-driven node, and the age below is how the
 *  momentary truth gets told. */
bool gnss_live(void);

/** Seconds since the last valid fix, or -1 when there has never been one. */
int32_t gnss_age_s(void);

/** A fix new enough to log as current. Anything older is history, and logging it
 *  every second as though it were now is precisely the lie this project refuses:
 *  the dot would sit still and look live. */
bool gnss_fresh(void);

/** Sentences seen and sentences with a fix, for the display and diagnostics. */
void gnss_counters(uint32_t *sentences, uint32_t *fixes);

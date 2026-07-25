/* Lokalgrid — what the board actually answered. */
#pragma once

#include <stdbool.h>
#include <stdint.h>

/** What the I²C scan found. Every field is a fact from the bus, not an
 *  assumption from the board's model number: variants ship without the BME280,
 *  and the GNSS module differs between units too. */
typedef struct {
    uint8_t count;          /**< how many addresses answered */
    bool    pmu;            /**< AXP2101 — without it there is no power ladder */
    bool    imu;            /**< QMI8658 */
    bool    magnetometer;   /**< QMC6310, parked by decision */
    bool    rtc;            /**< PCF8563 */
    bool    oled;           /**< 1.3" display */
    bool    baro;           /**< BME280, absent on some variants */
    uint8_t addrs[16];      /**< raw addresses, in scan order */
} lg_board_t;

/** Bring up I²C0 and scan it. Never blocks longer than the per-address probe
 *  timeout, and never aborts: a bus with nothing on it is a diagnosis to log,
 *  not a reason to fail boot (§8 — probe with timeout, log what answered,
 *  branch). Returns false only if the bus itself could not be created. */
bool board_scan_i2c(lg_board_t *out);

/** One line per finding, plus a plain statement of what is missing and what
 *  that costs. Called right after the scan so the very first boot log answers
 *  "is this board the variant the code assumes?". */
void board_report(const lg_board_t *b);

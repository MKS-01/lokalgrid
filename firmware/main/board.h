/* Lokalgrid — what the board actually answered. */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "driver/i2c_master.h"

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
    uint8_t oled_bus;       /**< which bus the display answered on, 0xff if none */
    uint8_t pmu_bus;        /**< which bus the PMU answered on, 0xff if none */
    uint8_t addrs[16];      /**< raw addresses, in scan order */
} lg_board_t;

/** Bring up both I²C buses and sweep them. Never blocks longer than the
 *  per-address probe timeout, and never aborts: a bus with nothing on it is a
 *  diagnosis to log, not a reason to fail boot (§8 — probe with timeout, log
 *  what answered, branch). Returns false only if neither bus could be created.
 *
 *  Two buses because the first scan of i2c0 found the display, the barometer
 *  and the magnetometer but no PMU, RTC or IMU — which cannot be true of a
 *  board that runs off a battery. The Supreme splits them across two buses. */
bool board_scan_i2c(lg_board_t *out);

/** The live handle for a bus the scan brought up, or NULL. Drivers attach to
 *  this rather than configuring the pins a second time. */
i2c_master_bus_handle_t board_i2c_bus(uint8_t port);

/** One line per finding, plus a plain statement of what is missing and what
 *  that costs. Called right after the scan so the very first boot log answers
 *  "is this board the variant the code assumes?". */
void board_report(const lg_board_t *b);

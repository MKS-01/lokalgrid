/* Lokalgrid — T-Beam Supreme (S3-Core v3.0) pin map.
 *
 * Provenance matters more than the numbers here. These come from LilyGO's own
 * `utilities.h` for the T-Beam S3 Supreme, which is the closest thing the board
 * has to a datasheet. **They are not yet verified against this unit.**
 *
 * The I²C pair verifies itself: `board_scan_i2c()` reports every address that
 * answers, and this board has seven chips on I²C0 (PMU, IMU, magnetometer,
 * RTC, OLED, and a BME280 on some variants). If the scan comes back empty, the
 * pins below are wrong — that is the first thing to suspect, not the chips.
 *
 * Everything else stays commented out until the subsystem is actually brought
 * up. A pin map that lists confident numbers for peripherals nobody has talked
 * to yet is a trap: the wrong constant looks exactly like a dead chip.
 */
#pragma once

/* ── I²C0 — PMU, IMU, magnetometer, RTC, OLED, optional BME280 ───────────── */
#define LG_I2C_PORT       0
#define LG_I2C_SDA        17
#define LG_I2C_SCL        18
#define LG_I2C_HZ         100000   /* 100 kHz for the scan; the OLED can go faster later */

/* Known addresses on this bus, for naming what answers (§8: probe, log, branch). */
#define LG_ADDR_AXP2101   0x34     /* PMU + coulomb counter */
#define LG_ADDR_QMI8658_L 0x6A     /* IMU, address depends on SA0 */
#define LG_ADDR_QMI8658_H 0x6B
#define LG_ADDR_QMC6310   0x1C     /* magnetometer — parked by decision (§2) */
#define LG_ADDR_PCF8563   0x51     /* RTC */
#define LG_ADDR_OLED      0x3C     /* 1.3" 128×64 */
#define LG_ADDR_BME280_L  0x76     /* absent on some variants — sentinels, never a shrink (§4) */
#define LG_ADDR_BME280_H  0x77

/* ── Not yet brought up ──────────────────────────────────────────────────────
 * GNSS (UART1), SX1262 (SPI2), microSD (SPI3) and the user button arrive with
 * the phase that uses them. The SX1262 in particular must not get pin
 * definitions before the antenna flag exists: no TX path can be reachable by
 * accident (§1 hardware safety rule 1).
 */

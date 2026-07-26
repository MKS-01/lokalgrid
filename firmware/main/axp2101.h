/* Lokalgrid — the AXP2101, read first.
 *
 * The PMU matters here for two reasons, and only one of them is the battery:
 *
 *  1. **It switches the rails.** GNSS and the SX1262 hang off its LDOs, which is
 *     the leading explanation for why the QMI8658 answered on neither I²C bus.
 *  2. Power state. This node runs from USB (Mac, phone or power bank — decided
 *     2026-07-26), so there is usually no cell: the honest answer is "mains", not
 *     a percentage. §2's power ladder only means something on a battery, and it
 *     says so rather than inventing hours-left from nothing.
 *
 * This pass is **read-only on purpose**. Every hardware-damage path on this board
 * runs through a rail or the PA (§1), and the LDO map for this variant is not
 * confirmed. Reading the registers first turns a guess into evidence; writes come
 * after, one rail at a time.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    bool     present;        /**< the chip answered at all */
    uint8_t  chip_id;        /**< reg 0x03 — 0x4a is the AXP2101 */
    bool     vbus_good;      /**< USB present and usable */
    bool     battery_present;/**< a cell is actually connected */
    bool     charging;
    uint16_t vbat_mv;        /**< 0 when there is no cell */
    int16_t  percent;        /**< -1 when the gauge has nothing to measure */
    uint8_t  ldo_enable0;    /**< reg 0x90 — which LDOs are on */
    uint8_t  ldo_enable1;    /**< reg 0x91 */
    uint8_t  ldo_volt[8];    /**< regs 0x92..0x99, raw */
} lg_pmu_t;

/** Attach to the PMU on the bus the scan found it on and read its state.
 *  Writes nothing. Returns false when the chip does not answer. */
bool axp_read(uint8_t i2c_port, lg_pmu_t *out);

/** One block of log, with the raw registers — the point is to *see* the map
 *  before changing it. */
void axp_report(const lg_pmu_t *p);

/** "usb" / "usb + cell 87%" / "cell 87%" / "unknown" — for the display, which
 *  must never show a percentage this node cannot actually measure. */
void axp_power_label(const lg_pmu_t *p, char *out, size_t out_len);

/**
 * Switch on one of the ALDO/BLDO rails, by its 1-based index as printed by
 * [axp_report].
 *
 * **This only flips the enable bit in 0x90 — it never writes a voltage register.**
 * That is the whole safety argument: the rails on this board are already
 * programmed by the factory bootloader (ALDO4 sits at 3.3 V, switched off), so
 * enabling one cannot over-volt whatever hangs off it. Changing a voltage is a
 * different, riskier operation that nothing here needs.
 *
 * Returns the register value read back afterwards, so the caller can log what
 * actually happened rather than what was asked for.
 */
bool axp_enable_rail(uint8_t index_1based, uint8_t *readback);

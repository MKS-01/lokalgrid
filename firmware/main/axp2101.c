#include "axp2101.h"
#include "board.h"
#include "board_pins.h"

#include <stdio.h>
#include <string.h>

#include "driver/i2c_master.h"
#include "esp_log.h"

static const char *TAG = "pmu";

/* Registers used here. Only the ones this pass actually reads are named, so the
 * file cannot imply knowledge of the LDO map it does not have yet. */
#define REG_STATUS1      0x00   /* VBUS present / battery present bits */
#define REG_STATUS2      0x01   /* charge state */
#define REG_CHIP_ID      0x03
#define REG_LDO_ONOFF0   0x90
#define REG_LDO_ONOFF1   0x91
#define REG_LDO_VOLT0    0x92   /* 0x92..0x99, one per LDO */
#define REG_VBAT_H       0x34
#define REG_VBAT_L       0x35
#define REG_PERCENT      0xa4

#define AXP2101_CHIP_ID  0x4a

static i2c_master_dev_handle_t s_dev = NULL;

/* ALDO/BLDO encoding is 0.5 V + 100 mV per step, so a raw value is readable at a
 * glance — which is the point of printing both. */
static uint16_t ldo_mv(uint8_t raw)
{
    return (uint16_t)(500 + 100 * (raw & 0x1f));
}

static bool rd(uint8_t reg, uint8_t *val)
{
    if (!s_dev) return false;
    return i2c_master_transmit_receive(s_dev, &reg, 1, val, 1, 100) == ESP_OK;
}

static bool wr(uint8_t reg, uint8_t val)
{
    if (!s_dev) return false;
    const uint8_t buf[2] = { reg, val };
    return i2c_master_transmit(s_dev, buf, sizeof(buf), 100) == ESP_OK;
}

bool axp_enable_rail(uint8_t index_1based, uint8_t *readback)
{
    if (index_1based < 1 || index_1based > 8) return false;
    uint8_t on = 0;
    if (!rd(REG_LDO_ONOFF0, &on)) return false;

    const uint8_t bit = (uint8_t)(1u << (index_1based - 1));
    if (on & bit) {
        ESP_LOGI(TAG, "ldo%u was already on", index_1based);
        if (readback) *readback = on;
        return true;
    }

    /* One bit, in the enable register only. Voltages stay exactly as the factory
     * bootloader left them (see the header). */
    if (!wr(REG_LDO_ONOFF0, (uint8_t)(on | bit))) return false;

    uint8_t after = 0;
    rd(REG_LDO_ONOFF0, &after);
    if (readback) *readback = after;

    uint8_t volt = 0;
    rd((uint8_t)(REG_LDO_VOLT0 + index_1based - 1), &volt);
    ESP_LOGI(TAG, "ldo%u switched on at its existing %u mV (0x90: 0x%02x -> 0x%02x)",
             index_1based, ldo_mv(volt), on, after);
    return (after & bit) != 0;
}

bool axp_read(uint8_t i2c_port, lg_pmu_t *out)
{
    memset(out, 0, sizeof(*out));
    out->percent = -1;

    if (i2c_port == 0xff) return false;

    if (s_dev == NULL) {
        i2c_master_bus_handle_t bus = board_i2c_bus(i2c_port);
        if (!bus) return false;
        i2c_device_config_t cfg = {
            .dev_addr_length = I2C_ADDR_BIT_LEN_7,
            .device_address = LG_ADDR_AXP2101,
            .scl_speed_hz = 100000,
        };
        if (i2c_master_bus_add_device(bus, &cfg, &s_dev) != ESP_OK) {
            s_dev = NULL;
            return false;
        }
    }

    uint8_t id = 0;
    if (!rd(REG_CHIP_ID, &id)) return false;
    out->present = true;
    out->chip_id = id;

    uint8_t s1 = 0, s2 = 0;
    if (rd(REG_STATUS1, &s1)) {
        /* Bit meanings per the AXP2101 datasheet's PMU_STATUS1: bit 5 vbus good,
         * bit 3 battery present. Marked as the thing to confirm against reality —
         * unplugging the cable is the test, and the log makes it checkable. */
        out->vbus_good = (s1 & (1 << 5)) != 0;
        out->battery_present = (s1 & (1 << 3)) != 0;
    }
    if (rd(REG_STATUS2, &s2)) {
        /* bits 6:5 — 01 = charging. Anything else is not charging. */
        out->charging = ((s2 >> 5) & 0x03) == 0x01;
    }

    uint8_t vh = 0, vl = 0;
    if (rd(REG_VBAT_H, &vh) && rd(REG_VBAT_L, &vl)) {
        out->vbat_mv = (uint16_t)(((vh & 0x3f) << 8) | vl);
    }

    uint8_t pct = 0;
    if (rd(REG_PERCENT, &pct) && pct <= 100 && out->battery_present) {
        out->percent = (int16_t)pct;
    }

    rd(REG_LDO_ONOFF0, &out->ldo_enable0);
    rd(REG_LDO_ONOFF1, &out->ldo_enable1);
    for (int i = 0; i < 8; i++) {
        rd((uint8_t)(REG_LDO_VOLT0 + i), &out->ldo_volt[i]);
    }
    return true;
}

void axp_report(const lg_pmu_t *p)
{
    if (!p->present) {
        ESP_LOGW(TAG, "AXP2101 did not answer — no rails can be switched, so GNSS "
                      "and the SX1262 stay dark whatever else works");
        return;
    }

    ESP_LOGI(TAG, "AXP2101 id 0x%02x%s", p->chip_id,
             p->chip_id == AXP2101_CHIP_ID ? "" : " (not the id the datasheet gives — check the map)");

    if (p->battery_present) {
        if (p->percent >= 0) {
            ESP_LOGI(TAG, "cell: %d%%, %u mV, %s", p->percent, p->vbat_mv,
                     p->charging ? "charging" : "not charging");
        } else {
            ESP_LOGI(TAG, "cell: present, %u mV, gauge has no reading yet", p->vbat_mv);
        }
    } else {
        /* The expected state for this build: USB from a Mac, a phone or a power
         * bank (2026-07-26). No cell means no percentage and no power ladder, and
         * saying that is better than a plausible-looking zero. */
        ESP_LOGI(TAG, "no cell — running on %s. No battery percentage exists to "
                      "report, and the power ladder has nothing to measure",
                 p->vbus_good ? "usb" : "an unknown supply");
    }

    /* The reason this pass exists: see the rail map before touching it. */
    ESP_LOGI(TAG, "ldo enable: 0x90=0x%02x 0x91=0x%02x", p->ldo_enable0, p->ldo_enable1);
    for (int i = 0; i < 8; i++) {
        ESP_LOGI(TAG, "  ldo%d (reg 0x%02x) raw 0x%02x = %u mV, %s",
                 i + 1, REG_LDO_VOLT0 + i, p->ldo_volt[i], ldo_mv(p->ldo_volt[i]),
                 (p->ldo_enable0 & (1 << i)) ? "ON" : "off");
    }
    ESP_LOGI(TAG, "nothing was written — the rail map is evidence now, not a guess");
}

void axp_power_label(const lg_pmu_t *p, char *out, size_t out_len)
{
    if (!p->present) {
        snprintf(out, out_len, "power no pmu");
    } else if (p->battery_present && p->percent >= 0) {
        snprintf(out, out_len, "power %s%d%%", p->charging ? "chg " : "cell ", p->percent);
    } else if (p->battery_present) {
        snprintf(out, out_len, "power cell %umV", p->vbat_mv);
    } else {
        snprintf(out, out_len, "power usb, no cell");
    }
}

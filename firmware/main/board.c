#include "board.h"
#include "board_pins.h"

#include <string.h>

#include "driver/i2c_master.h"
#include "esp_log.h"

static const char *TAG = "board";

/* Long enough for a slow chip to answer, short enough that a whole 112-address
 * sweep of an empty bus still finishes in a couple of seconds. */
#define PROBE_TIMEOUT_MS 20

/* Kept for the drivers that attach later (OLED now; PMU, RTC, IMU next) —
 * bringing a bus up twice is two chances to get the pins wrong. */
static i2c_master_bus_handle_t s_bus0 = NULL;
static i2c_master_bus_handle_t s_bus1 = NULL;

static const char *name_of(uint8_t addr)
{
    switch (addr) {
    case LG_ADDR_AXP2101:   return "AXP2101 PMU";
    case LG_ADDR_QMI8658_L: /* fall through */
    case LG_ADDR_QMI8658_H: return "QMI8658 IMU";
    case LG_ADDR_QMC6310:   return "QMC6310 magnetometer (parked)";
    case LG_ADDR_PCF8563:   return "PCF8563 RTC";
    case LG_ADDR_OLED:      return "OLED 128x64";
    case LG_ADDR_BME280_L:  /* fall through */
    case LG_ADDR_BME280_H:  return "BME280 baro/temp/humidity";
    default:                return "unknown";
    }
}

static void note(lg_board_t *out, uint8_t addr, uint8_t bus)
{
    ESP_LOGI(TAG, "  i2c%u 0x%02x  %s", bus, addr, name_of(addr));
    if (out->count < sizeof(out->addrs)) {
        out->addrs[out->count] = addr;
    }
    out->count++;

    switch (addr) {
    case LG_ADDR_AXP2101:   out->pmu = true; out->pmu_bus = bus; break;
    case LG_ADDR_QMI8658_L:
    case LG_ADDR_QMI8658_H: out->imu = true; break;
    case LG_ADDR_QMC6310:   out->magnetometer = true; break;
    case LG_ADDR_PCF8563:   out->rtc = true; break;
    case LG_ADDR_OLED:      out->oled = true; out->oled_bus = bus; break;
    case LG_ADDR_BME280_L:
    case LG_ADDR_BME280_H:  out->baro = true; break;
    default: break;
    }
}

/* Bring one bus up and sweep it. A bus that will not start, or answers with
 * nothing, is a diagnosis to log rather than a reason to fail boot (§8). */
static i2c_master_bus_handle_t scan_bus(lg_board_t *out, uint8_t port, int sda, int scl)
{
    i2c_master_bus_config_t cfg = {
        .i2c_port = port,
        .sda_io_num = sda,
        .scl_io_num = scl,
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };

    i2c_master_bus_handle_t bus = NULL;
    esp_err_t err = i2c_new_master_bus(&cfg, &bus);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "i2c%u would not start on sda=%d scl=%d: %s",
                 port, sda, scl, esp_err_to_name(err));
        return NULL;
    }

    ESP_LOGI(TAG, "scanning i2c%u (sda=%d scl=%d)", port, sda, scl);
    uint8_t found = 0;
    for (uint8_t addr = 0x08; addr <= 0x77; addr++) {
        if (i2c_master_probe(bus, addr, PROBE_TIMEOUT_MS) == ESP_OK) {
            note(out, addr, port);
            found++;
        }
    }
    if (found == 0) {
        ESP_LOGW(TAG, "i2c%u: nothing answered", port);
    }
    return bus;
}

bool board_scan_i2c(lg_board_t *out)
{
    memset(out, 0, sizeof(*out));
    out->oled_bus = 0xff;
    out->pmu_bus = 0xff;

    s_bus0 = scan_bus(out, LG_I2C_PORT, LG_I2C_SDA, LG_I2C_SCL);
    s_bus1 = scan_bus(out, LG_I2C1_PORT, LG_I2C1_SDA, LG_I2C1_SCL);

    return s_bus0 != NULL || s_bus1 != NULL;
}

i2c_master_bus_handle_t board_i2c_bus(uint8_t port)
{
    return port == LG_I2C1_PORT ? s_bus1 : s_bus0;
}

void board_report(const lg_board_t *b)
{
    if (b->count == 0) {
        ESP_LOGE(TAG, "nothing answered on either bus — suspect the pin map "
                      "(board_pins.h: i2c0 sda=%d scl=%d, i2c1 sda=%d scl=%d) "
                      "before the chips",
                 LG_I2C_SDA, LG_I2C_SCL, LG_I2C1_SDA, LG_I2C1_SCL);
        return;
    }

    ESP_LOGI(TAG, "%u device%s across both buses", b->count, b->count == 1 ? "" : "s");

    /* State what is missing *and what it costs*, so a variant difference reads
     * as a known consequence rather than an unexplained gap later. */
    if (!b->pmu) {
        ESP_LOGW(TAG, "no AXP2101 on either bus: no battery percentage, no coulomb "
                      "counter, no power ladder — and the GNSS/LoRa rails it "
                      "switches stay off, so neither will answer");
    }
    if (!b->baro) {
        ESP_LOGI(TAG, "no BME280 (expected on some variants): baro and temp "
                      "log as sentinels, the record stays 32 bytes");
    }
    if (!b->oled) {
        ESP_LOGW(TAG, "no OLED: the node cannot be read without a phone attached");
    }
    if (!b->rtc) {
        ESP_LOGW(TAG, "no PCF8563: time will not survive deep sleep without GNSS");
    }
    if (!b->imu) {
        ESP_LOGI(TAG, "no QMI8658: the motion gate falls back to GNSS speed alone");
    }
}

/* Lokalgrid — Phase 03, the board comes out.
 *
 * What this boot does, in the order it does it:
 *   1. says hello, so there is a breakpoint target and a first log line
 *   2. scans I²C and reports which of this variant's chips actually answered
 *   3. mounts LittleFS (the track log lives there)
 *   4. brings up BLE advertising — the always-on layer, ~2 mA
 *   5. brings up the SoftAP — findable at boot, then taken down by a firmware
 *      timeout, because ~100 mA left on turns a week of runtime into a day
 *
 * The WebSocket server that the Android app talks to, the GNSS reader, and the
 * BLE GATT path come next. Nothing here touches the SX1262: no TX code exists
 * until an antenna flag does (§1 safety rule 1).
 */

#include <stdio.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_littlefs.h"
#include "esp_log.h"
#include "esp_system.h"
#include "nvs_flash.h"

#include "axp2101.h"
#include "ble_adv.h"
#include "board.h"
#include "board_pins.h"
#include "gnss.h"
#include "net_ws.h"
#include "session.h"
#include "oled.h"
#include "wifi_ap.h"

static const char *TAG = "lokalgrid";

static void mount_storage(void)
{
    esp_vfs_littlefs_conf_t conf = {
        .base_path = "/storage",
        .partition_label = "storage",
        .format_if_mount_failed = true,
        .dont_mount = false,
    };
    esp_err_t err = esp_vfs_littlefs_register(&conf);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "littlefs mount failed: %s", esp_err_to_name(err));
        return;
    }

    size_t total = 0, used = 0;
    esp_littlefs_info(conf.partition_label, &total, &used);
    ESP_LOGI(TAG, "littlefs mounted: %u KiB used of %u KiB", used / 1024, total / 1024);
}

/* WiFi keeps its calibration data in NVS, so this has to succeed before the AP
 * starts. A truncated or version-bumped partition is recoverable by erasing it;
 * anything else is worth failing loudly for. */
static void init_nvs(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGW(TAG, "nvs unusable (%s) — erasing it and trying once more",
                 esp_err_to_name(err));
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
}

void app_main(void)
{
    ESP_LOGI(TAG, "lokalgrid boot");   /* <-- set the first USB-JTAG breakpoint here */

    lg_board_t board;
    if (board_scan_i2c(&board)) {
        board_report(&board);
    }

    /* Read the PMU before anything else asks it for power: which rails are on is
     * the evidence needed before switching any of them, and the power *source*
     * decides what the display can honestly claim. */
    static lg_pmu_t pmu;
    if (board.pmu && axp_read(board.pmu_bus, &pmu)) {
        axp_report(&pmu);
    }

    /* ALDO4 is programmed for 3.3 V and switched off — the signature of a rail
     * the factory firmware turns on when it wants the peripheral. GNSS and the
     * SX1262 are what hang off these, so switching it on and looking is how the
     * map gets settled. Only the enable bit is written; no voltage is touched. */
    if (pmu.present) {
        uint8_t after = 0;
        if (axp_enable_rail(LG_GNSS_RAIL, &after)) {
            /* Give the receiver a moment to come up before asking it anything. */
            vTaskDelay(pdMS_TO_TICKS(300));
            lg_board_t again;
            if (board_scan_i2c(&again)) {
                if (again.imu && !board.imu) {
                    ESP_LOGI(TAG, "the QMI8658 appeared once ldo4 came on — it was "
                                  "behind the rail, not absent");
                } else if (!again.imu) {
                    ESP_LOGI(TAG, "still no QMI8658 with ldo4 on — this variant "
                                  "does not have one, or it is on another rail");
                }
                board = again;
            }
        }
    }

    /* The screen comes up before the network so it is never blank while
     * something is still happening — the same rule as the app's boot screen. */
    oled_init(board.oled_bus);
    oled_splash("mounting storage");

    init_nvs();
    mount_storage();

    oled_splash("starting radios");
    if (!ble_adv_start()) {
        ESP_LOGE(TAG, "no BLE — the always-on layer is missing, WiFi still works");
    }
    ESP_ERROR_CHECK(wifi_ap_start(LG_AP_REASON_BOOT));

    /* The app that was built against the mock node talks to this: same proto 2,
     * same frames, real hardware underneath. Only the URL changes.
     *
     * Not ESP_ERROR_CHECK: a server that will not start must cost the node its
     * WebSocket and say so, not reboot forever. The PSRAM boot loop earlier today
     * was exactly this mistake made by the SDK's default. */
    /* GNSS after the rail, before the session: a fix that exists at the first tick
     * is a record logged as real rather than a synthetic one nobody wanted. */
    if (!gnss_start()) {
        ESP_LOGW(TAG, "no GNSS — records stay synthetic and every client is told so "
                      "in hello.mode");
    }

    esp_err_t sess = lg_session_init();
    if (sess != ESP_OK) {
        ESP_LOGE(TAG, "session would not start: %s", esp_err_to_name(sess));
    }
    esp_err_t ws = net_ws_start();
    if (ws != ESP_OK) {
        ESP_LOGE(TAG, "no websocket (%s) — the phone app cannot attach; "
                      "BLE and the display still work", esp_err_to_name(ws));
    }

    /* Heartbeat. Says what is *true* rather than that it is alive: which
     * transports are up, how many phones are attached, and how much heap is
     * left, since the backlog buffers and tile serving live in that number. */
    uint32_t up_s = 0;
    while (true) {
        const uint8_t stations = wifi_ap_stations();
        {
            uint32_t nm = 0, fx = 0;
            gnss_counters(&nm, &fx);
            if (nm > 0 && fx == 0) {
                /* Alive and searching is a state worth naming: a cold start with no
                 * ephemeris is a minute outdoors and forever indoors, and this node
                 * has no backup rail to keep the almanac (USB power, no cell). */
                ESP_LOGI(TAG, "gnss: %lu sentences, no fix yet — needs sky view",
                         (unsigned long)nm);
            }
        }
        ESP_LOGI(TAG, "ap %s · %u station%s · ble %s · heap %lu",
                 wifi_ap_is_up() ? "up" : "down",
                 stations,
                 stations == 1 ? "" : "s",
                 ble_adv_is_advertising() ? "advertising" : "quiet",
                 (unsigned long)esp_get_free_heap_size());

        /* The same facts on the screen, for someone standing over the node with
         * no phone attached. Named states, no spinner — the §6 rule applies to
         * the device's own display too.
         *
         * Battery is deliberately absent: the AXP2101 has not been read yet, and
         * a made-up percentage is worse than none. It appears when the PMU does. */
        /* 22 = 21 characters on a line plus the terminator; the compiler checks
         * these against the format strings, so the sizes are not decoration. */
        char l_wifi[32], l_ssid[32], l_ble[32], l_up[32];
        snprintf(l_ssid, sizeof(l_ssid), "ssid  %s", LG_AP_SSID);
        if (wifi_ap_is_up()) {
            snprintf(l_wifi, sizeof(l_wifi), "wifi  up, %u phone%s", stations,
                     stations == 1 ? "" : "s");
        } else {
            snprintf(l_wifi, sizeof(l_wifi), "wifi  down, idle");
        }
        snprintf(l_ble, sizeof(l_ble), "ble   %s",
                 ble_adv_is_advertising() ? "advertising" : "quiet");
        uint32_t nmea = 0, fixes = 0;
        gnss_counters(&nmea, &fixes);
        snprintf(l_up, sizeof(l_up), "up %lum  gnss %s",
                 (unsigned long)(up_s / 60),
                 net_ws_gnss_live() ? "fix" : (nmea > 0 ? "no fix" : "--"));

        char l_ws[32];
        snprintf(l_ws, sizeof(l_ws), "ws    %u client%s %s", net_ws_clients(),
                 net_ws_clients() == 1 ? "" : "s", net_ws_gnss_live() ? "" : "synth");

        /* Power is a *source*, not a percentage: this node runs from USB, so a
         * battery figure would be invented. The line says which it is. */
        char l_pwr[32];
        axp_power_label(&pmu, l_pwr, sizeof(l_pwr));

        const char *lines[] = { "lokalgrid", l_wifi, l_ble, l_ws, l_pwr, l_up };
        oled_lines(lines, 6);

        vTaskDelay(pdMS_TO_TICKS(5000));
        up_s += 5;
    }
}

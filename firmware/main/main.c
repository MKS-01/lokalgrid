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

#include "ble_adv.h"
#include "board.h"
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

    /* Heartbeat. Says what is *true* rather than that it is alive: which
     * transports are up, how many phones are attached, and how much heap is
     * left, since the backlog buffers and tile serving live in that number. */
    uint32_t up_s = 0;
    while (true) {
        const uint8_t stations = wifi_ap_stations();
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
        snprintf(l_up, sizeof(l_up), "up    %lum  gnss --",
                 (unsigned long)(up_s / 60));

        const char *lines[] = { "lokalgrid", l_ssid, l_wifi, l_ble, l_up, "no phone needed" };
        oled_lines(lines, 6);

        vTaskDelay(pdMS_TO_TICKS(5000));
        up_s += 5;
    }
}

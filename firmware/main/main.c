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

    init_nvs();
    mount_storage();

    if (!ble_adv_start()) {
        ESP_LOGE(TAG, "no BLE — the always-on layer is missing, WiFi still works");
    }
    ESP_ERROR_CHECK(wifi_ap_start(LG_AP_REASON_BOOT));

    /* Heartbeat. Says what is *true* rather than that it is alive: which
     * transports are up, how many phones are attached, and how much heap is
     * left, since the backlog buffers and tile serving live in that number. */
    while (true) {
        ESP_LOGI(TAG, "ap %s · %u station%s · ble %s · heap %lu",
                 wifi_ap_is_up() ? "up" : "down",
                 wifi_ap_stations(),
                 wifi_ap_stations() == 1 ? "" : "s",
                 ble_adv_is_advertising() ? "advertising" : "quiet",
                 (unsigned long)esp_get_free_heap_size());
        vTaskDelay(pdMS_TO_TICKS(10000));
    }
}

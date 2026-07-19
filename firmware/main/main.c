/* Lokalgrid — Phase 01: boot, mount LittleFS, prove the toolchain.
 * SoftAP + NimBLE advertising land in the next step of this phase.
 */

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_littlefs.h"

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

void app_main(void)
{
    ESP_LOGI(TAG, "lokalgrid boot");   /* <-- set the first USB-JTAG breakpoint here */

    mount_storage();

    while (true) {
        ESP_LOGI(TAG, "alive, heap free: %lu", esp_get_free_heap_size());
        vTaskDelay(pdMS_TO_TICKS(10000));
    }
}

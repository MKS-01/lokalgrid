#include "ble_adv.h"

#include <string.h>

#include "esp_log.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"

static const char *TAG = "ble";

static uint8_t s_addr_type;
static bool s_advertising = false;

static int on_gap_event(struct ble_gap_event *event, void *arg);

static void advertise(void)
{
    struct ble_hs_adv_fields fields = { 0 };
    const char *name = ble_svc_gap_device_name();

    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)name;
    fields.name_len = strlen(name);
    fields.name_is_complete = 1;
    fields.tx_pwr_lvl_is_present = 1;
    fields.tx_pwr_lvl = BLE_HS_ADV_TX_PWR_LVL_AUTO;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv fields rejected: %d", rc);
        return;
    }

    struct ble_gap_adv_params params = {
        .conn_mode = BLE_GAP_CONN_MODE_UND,
        .disc_mode = BLE_GAP_DISC_MODE_GEN,
    };
    rc = ble_gap_adv_start(s_addr_type, NULL, BLE_HS_FOREVER, &params, on_gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "advertising would not start: %d", rc);
        return;
    }
    s_advertising = true;
    ESP_LOGI(TAG, "advertising as \"%s\" — visible in nRF Connect", name);
}

static int on_gap_event(struct ble_gap_event *event, void *arg)
{
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        /* Nothing to serve yet, and the log says so rather than leaving a
         * connection that silently does nothing looking like a broken link. */
        ESP_LOGI(TAG, "connected (status %d) — no GATT service registered yet, "
                      "so there is nothing to read; that is the next step",
                 event->connect.status);
        if (event->connect.status != 0) {
            advertise();
        } else {
            s_advertising = false;
        }
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected (reason %d) — advertising again",
                 event->disconnect.reason);
        advertise();
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        return 0;

    case BLE_GAP_EVENT_MTU:
        /* Worth logging from the first day: chunk sizes come from the
         * negotiated MTU at runtime, never a guess (§4), and this is the number
         * the app's Diagnostics screen will have to agree with. */
        ESP_LOGI(TAG, "mtu now %d on conn %d", event->mtu.value, event->mtu.conn_handle);
        return 0;

    default:
        return 0;
    }
}

static void on_reset(int reason)
{
    ESP_LOGE(TAG, "controller reset, reason %d", reason);
    s_advertising = false;
}

static void on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) {
        ESP_LOGE(TAG, "no usable BLE address: %d", rc);
        return;
    }
    rc = ble_hs_id_infer_auto(0, &s_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "address type inference failed: %d", rc);
        return;
    }
    advertise();
}

static void host_task(void *param)
{
    nimble_port_run();               /* returns only when the stack stops */
    nimble_port_freertos_deinit();
}

bool ble_adv_start(void)
{
    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nimble would not init: %s", esp_err_to_name(err));
        return false;
    }

    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;

    ble_svc_gap_init();
    int rc = ble_svc_gap_device_name_set(LG_BLE_NAME);
    if (rc != 0) {
        ESP_LOGW(TAG, "device name not set: %d", rc);
    }

    /* No bonding during development: a stale bond on the phone produces
     * connection failures that look exactly like firmware bugs (§8). */
    ble_hs_cfg.sm_bonding = 0;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 0;

    nimble_port_freertos_init(host_task);
    ESP_LOGI(TAG, "nimble host started");
    return true;
}

bool ble_adv_is_advertising(void)
{
    return s_advertising;
}

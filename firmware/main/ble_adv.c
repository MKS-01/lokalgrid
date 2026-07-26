#include "ble_adv.h"
#include "ble_gatt.h"

#include <string.h>

#include "esp_log.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

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
        ESP_LOGE(TAG, "adv fields rejected: %d%s", rc,
                 rc == BLE_HS_EMSGSIZE ? " (too big for 31 bytes)" : "");
        return;
    }

    /* The 128-bit service UUID goes in the **scan response**, not the
     * advertisement. Flags (3) + name (11) + tx power (3) + a 128-bit UUID (18)
     * is 35 bytes and the advertising payload holds 31 — which NimBLE reports as
     * a bare "rejected: 4" (EMSGSIZE), and the symptom is a node that never
     * advertises at all. Android merges the scan response into the same
     * ScanRecord, so filtering on the service still works.
     *
     * Little-endian, same order as ble_gatt.c: prints as
     * 6f6b616c-6772-6964-0000-000000000001 */
    static const ble_uuid128_t svc = BLE_UUID128_INIT(
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x64, 0x69, 0x72, 0x67, 0x6c, 0x61, 0x6b, 0x6f);
    struct ble_hs_adv_fields rsp = { 0 };
    rsp.uuids128 = (ble_uuid128_t *)&svc;
    rsp.num_uuids128 = 1;
    rsp.uuids128_is_complete = 1;
    rc = ble_gap_adv_rsp_set_fields(&rsp);
    if (rc != 0) {
        ESP_LOGW(TAG, "scan response rejected: %d — the app will have to match on "
                      "the name instead of the service", rc);
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
        ESP_LOGI(TAG, "connected (status %d)", event->connect.status);
        if (event->connect.status != 0) {
            advertise();
            return 0;
        }
        s_advertising = false;
        /* A connection is not yet a client: nothing can be notified to it until
         * it subscribes, so the session join waits for BLE_GAP_EVENT_SUBSCRIBE
         * below. */
        ble_gatt_on_connect(event->connect.conn_handle,
                            ble_att_mtu(event->connect.conn_handle));
        /* Keep advertising with room left — this node serves several phones (§3),
         * so one connection must not make it invisible to the rest. */
        advertise();
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected (reason %d) — advertising again",
                 event->disconnect.reason);
        ble_gatt_on_disconnect(event->disconnect.conn.conn_handle);
        advertise();
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE:
        /* The event that actually admits a client. Until the CCCD is written
         * there is nowhere for a notification to go, so `hello` sent any earlier
         * is simply discarded — which looked, from the phone, like a node that
         * connects and then says nothing at all. */
        ble_gatt_on_subscribe(event->subscribe.conn_handle,
                              event->subscribe.attr_handle,
                              event->subscribe.cur_notify);
        return 0;

    case BLE_GAP_EVENT_MTU:
        /* Worth logging from the first day: chunk sizes come from the
         * negotiated MTU at runtime, never a guess (§4), and this is the number
         * the app's Diagnostics screen will have to agree with. */
        ble_gatt_on_mtu(event->mtu.conn_handle, event->mtu.value);
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
    ble_svc_gatt_init();
    if (!ble_gatt_init()) {
        ESP_LOGE(TAG, "no GATT service — the phone will see the node but find "
                      "nothing to sync with");
    }
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

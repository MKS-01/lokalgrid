/* Lokalgrid — SoftAP, with the idle timeout enforced here rather than exposed.
 *
 * The AP draws ~100 mA against BLE's ~2 mA, which is the difference between a
 * week of runtime and a day (§3). So it is on demand, and the timeout that
 * takes it back down is **compiled in, not configurable** (§2): a config toggle
 * will eventually be left wrong, and the failure is silent — a flat battery
 * three days early with nothing in the log to explain it.
 */
#include "wifi_ap.h"

#include <string.h>

#include "esp_err.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_mac.h"      /* MACSTR / MAC2STR — not pulled in by esp_wifi.h */
#include "esp_netif.h"
#include "esp_timer.h"
#include "esp_wifi.h"

static const char *TAG = "wifi";

/* WPA2 rather than open: an open AP means anyone in range joins the node's
 * network, and while the *protocol* is what actually protects the data, an
 * unauthenticated station still costs airtime and a client slot. Shared
 * passphrase, printed on the case — the group is known (§2). */
#define LG_AP_PASS "lokalgrid"

/* Channel 6 by default. Nothing here scans for a quiet channel yet; when a
 * booster arrives (§1) both cells want to be told which channel to use. */
#define LG_AP_CHANNEL 6

/* The two constants that are deliberately not settings.
 *
 * BOOT_GRACE is long enough to find the node in a WiFi list, walk over to it
 * and connect. IDLE_LIMIT is what applies once someone has used it and gone. */
#define LG_AP_BOOT_GRACE_S 600
#define LG_AP_IDLE_LIMIT_S 300

/* The effective client cap is 9 — min(NimBLE connections, SoftAP stations) —
 * and every client holds both a station and a BLE link (§3). The AP is given
 * one slot of headroom so a phone that reconnects before the old association
 * has timed out is not refused for a slot it already owns. */
#define LG_AP_MAX_STA 10

static bool s_up = false;
static bool s_ever_used = false;      /* has any phone associated since boot? */
static uint32_t s_idle_s = 0;
static esp_timer_handle_t s_tick = NULL;

static void on_wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base != WIFI_EVENT) {
        return;
    }
    switch (id) {
    case WIFI_EVENT_AP_STACONNECTED: {
        wifi_event_ap_staconnected_t *e = (wifi_event_ap_staconnected_t *)data;
        s_ever_used = true;
        s_idle_s = 0;
        ESP_LOGI(TAG, "station joined: " MACSTR " (aid %d) — %u of %d",
                 MAC2STR(e->mac), e->aid, wifi_ap_stations(), LG_AP_MAX_STA);
        break;
    }
    case WIFI_EVENT_AP_STADISCONNECTED: {
        wifi_event_ap_stadisconnected_t *e = (wifi_event_ap_stadisconnected_t *)data;
        ESP_LOGI(TAG, "station left: " MACSTR " (aid %d) — %u remain",
                 MAC2STR(e->mac), e->aid, wifi_ap_stations());
        break;
    }
    default:
        break;
    }
}

/* One second of accounting. Kept dull on purpose: the interesting part is that
 * the rule is stated in the log *before* it fires, so the AP going away is
 * never a surprise. */
static void tick(void *arg)
{
    if (!s_up) {
        return;
    }
    if (wifi_ap_stations() > 0) {
        s_idle_s = 0;
        return;
    }

    s_idle_s++;
    const uint32_t limit = s_ever_used ? LG_AP_IDLE_LIMIT_S : LG_AP_BOOT_GRACE_S;

    if (s_idle_s == limit / 2 || s_idle_s + 30 == limit) {
        ESP_LOGI(TAG, "idle %lus of %lu — AP goes down at the limit to save ~100 mA",
                 (unsigned long)s_idle_s, (unsigned long)limit);
    }
    if (s_idle_s >= limit) {
        wifi_ap_stop(s_ever_used ? "idle since the last phone left"
                                 : "nobody connected during the boot grace");
    }
}

esp_err_t wifi_ap_start(lg_ap_reason_t reason)
{
    if (s_up) {
        ESP_LOGI(TAG, "AP already up");
        return ESP_OK;
    }

    static bool inited = false;
    if (!inited) {
        ESP_ERROR_CHECK(esp_netif_init());
        ESP_ERROR_CHECK(esp_event_loop_create_default());
        esp_netif_create_default_wifi_ap();

        wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
        ESP_ERROR_CHECK(esp_wifi_init(&cfg));
        ESP_ERROR_CHECK(esp_event_handler_instance_register(
            WIFI_EVENT, ESP_EVENT_ANY_ID, &on_wifi_event, NULL, NULL));

        const esp_timer_create_args_t targs = {
            .callback = &tick,
            .name = "ap_idle",
        };
        ESP_ERROR_CHECK(esp_timer_create(&targs, &s_tick));
        ESP_ERROR_CHECK(esp_timer_start_periodic(s_tick, 1000 * 1000));
        inited = true;
    }

    wifi_config_t wc = {
        .ap = {
            .ssid = LG_AP_SSID,
            .ssid_len = strlen(LG_AP_SSID),
            .password = LG_AP_PASS,
            .channel = LG_AP_CHANNEL,
            .max_connection = LG_AP_MAX_STA,
            .authmode = WIFI_AUTH_WPA2_PSK,
            .pmf_cfg = { .required = false },
        },
    };

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &wc));
    ESP_ERROR_CHECK(esp_wifi_start());

    s_up = true;
    s_idle_s = 0;

    const char *why = (reason == LG_AP_REASON_BOOT)
        ? "boot — so the node is findable without a phone already paired"
        : "a client asked for it";
    ESP_LOGI(TAG, "AP up: ssid \"%s\" ch %d, up to %d stations — reason: %s",
             LG_AP_SSID, LG_AP_CHANNEL, LG_AP_MAX_STA, why);
    ESP_LOGI(TAG, "it goes down after %ds with nobody connected (%ds once a "
                  "phone has been and gone) — a firmware limit, not a setting",
             LG_AP_BOOT_GRACE_S, LG_AP_IDLE_LIMIT_S);
    return ESP_OK;
}

void wifi_ap_stop(const char *why)
{
    if (!s_up) {
        return;
    }
    esp_err_t err = esp_wifi_stop();
    s_up = false;
    s_idle_s = 0;
    ESP_LOGI(TAG, "AP down (%s)%s", why ? why : "no reason given",
             err == ESP_OK ? "" : " — with an error on the way out");
    ESP_LOGI(TAG, "BLE is what brings it back; until Phase 04 wires that, reset the board");
}

uint8_t wifi_ap_stations(void)
{
    wifi_sta_list_t list = { 0 };
    if (esp_wifi_ap_get_sta_list(&list) != ESP_OK) {
        return 0;
    }
    return (uint8_t)list.num;
}

bool wifi_ap_is_up(void)
{
    return s_up;
}

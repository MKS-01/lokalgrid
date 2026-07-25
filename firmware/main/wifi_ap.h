/* Lokalgrid — the SoftAP the phone joins. */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

/** The SSID a phone looks for. Fixed, not derived from the MAC: you should be
 *  able to recognise the node in a WiFi list without reading a label. */
#define LG_AP_SSID "lokalgrid"

/** Why the AP is up. Logged on every start, because an AP that came up for a
 *  reason nobody recorded is how a week of runtime turns into a day (§8). */
typedef enum {
    LG_AP_REASON_BOOT,        /**< brought up at boot so the node is findable */
    LG_AP_REASON_REQUESTED,   /**< a client asked for it over BLE (Phase 04) */
} lg_ap_reason_t;

/** Bring the AP up. Idempotent: a second call while it is already up only logs. */
esp_err_t wifi_ap_start(lg_ap_reason_t reason);

/** Take the AP down and say why. */
void wifi_ap_stop(const char *why);

/** How many phones are associated right now. */
uint8_t wifi_ap_stations(void);

bool wifi_ap_is_up(void);

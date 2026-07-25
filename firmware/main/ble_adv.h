/* Lokalgrid — BLE presence. Advertising only, for now. */
#pragma once

#include <stdbool.h>

/** The name the phone sees in nRF Connect, and the same string as the SSID:
 *  one node, one name, on both transports. */
#define LG_BLE_NAME "lokalgrid"

/**
 * Start NimBLE and advertise. This is the always-on layer (~2 mA against the
 * AP's ~100 mA, §3), so it comes up at boot and stays up.
 *
 * Deliberately advertising *only*: no GATT service is registered yet. The sync
 * path is the one thing the mock could never stand in for (§6), so it gets
 * built against this board with the protocol in front of it — not guessed at
 * now and debugged later. What this buys today is the honest half: the phone
 * can see the node exists over BLE.
 */
bool ble_adv_start(void);

/** True once the controller has synced and advertising is live. */
bool ble_adv_is_advertising(void);

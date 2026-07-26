/* Lokalgrid — proto 2 over BLE GATT.
 *
 * This is the transport the mock node could never stand in for (§6), and the
 * reason the app exists as a native app at all: BLE is the always-on layer at
 * ~2 mA, so the node stays reachable for a week instead of a day, and the phone
 * can sync with its screen off.
 *
 * Same protocol as the WebSocket — the session (session.c) does not know or care
 * which wire a client arrived on. Two characteristics:
 *
 *   control  write + notify   one JSON object per frame, exactly as over WiFi
 *   data     notify           32-byte records, in the §4 chunk framing
 *
 * The chunk header is the one thing BLE adds, and it is specified rather than
 * invented: `seq u16 | len u16 | payload | crc16`, payload sized from the
 * *negotiated* MTU and carrying **whole records only**. Whole records waste a few
 * bytes per chunk and eliminate an entire reassembly bug class (§4).
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

/** Register the GATT service. Call before advertising starts so the service is
 *  discoverable on the first connection. */
bool ble_gatt_init(void);

/* Driven by ble_adv.c's GAP events — GAP and GATT are one connection seen from
 * two sides, and keeping the event handling in one place means the two cannot
 * disagree about who is attached. */
void ble_gatt_on_connect(uint16_t conn, uint16_t mtu);
void ble_gatt_on_disconnect(uint16_t conn);
void ble_gatt_on_mtu(uint16_t conn, uint16_t mtu);

/**
 * A client subscribed to, or unsubscribed from, one of the two characteristics.
 *
 * **This is what admits a client to the session, not the GAP connection.** A
 * notification sent to a characteristic nobody has subscribed to goes nowhere:
 * NimBLE has no one to send it to and drops it. Joining at connect time meant
 * `hello`, the config and the roster were all issued into that hole, so the app
 * connected, waited for a `hello` that had already been thrown away, never stated
 * its cursors, and sat there with a live link and no data.
 *
 * So the join waits until **both** characteristics are subscribed: by then the
 * MTU is negotiated too (the app asks for it first), which means the very first
 * chunk is sized from the real value rather than from 23.
 */
void ble_gatt_on_subscribe(uint16_t conn, uint16_t attr_handle, bool notify);

/** How many BLE clients are attached right now — attached meaning *in the
 *  session*, so this agrees with the roster and the OLED rather than counting
 *  connections that are still discovering. */
uint8_t ble_gatt_clients(void);

/** Negotiated MTU of the most recent connection, for the app's Diagnostics
 *  screen and for the chunk size — 0 before anything has connected. */
uint16_t ble_gatt_mtu(void);

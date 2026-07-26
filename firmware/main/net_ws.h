/* Lokalgrid — the WebSocket the Android app talks to.
 *
 * Same `proto 2` the mock node serves (mock-node/src/server.js), so the app that
 * was built against the mock connects to this with no protocol change: one JSON
 * object per text frame for control, raw 32-byte records for positions (§4).
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

/** Start the HTTP server and the 1 Hz position tick. */
esp_err_t net_ws_start(void);

/** How many clients hold a live socket right now. */
uint8_t net_ws_clients(void);

/** True while a GNSS fix is driving the records, false while they are synthetic.
 *  Reported to every client in `hello.mode`, so nothing has to guess. */
bool net_ws_gnss_live(void);

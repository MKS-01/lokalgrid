/* Lokalgrid — the session: one protocol, two transports.
 *
 * `proto 2` is the same over WiFi and over BLE — the same `hello`, the same
 * roster, the same 32-byte records (§4). So the logic lives here **once**, and
 * each transport is a thin adapter that knows how to put bytes on its own wire.
 *
 * The alternative — a copy of the protocol per transport — is the drift bug this
 * project already expects to hit between C and Kotlin (§6, Phase 05). Inviting a
 * third copy on purpose would be daft.
 *
 * A client is a client whichever wire it arrived on: it holds its own cursors and
 * is authoritative about what it has received (§3). The roster says which
 * transport each one is using, because that is a fact the UI should show.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "esp_err.h"

/** How a transport sends. Both may be called from any task; the transport is
 *  responsible for its own serialisation. */
typedef struct {
    const char *name;    /**< "wifi" or "ble" — goes straight into the roster */
    esp_err_t (*send_text)(void *ctx, const char *text);
    esp_err_t (*send_bin)(void *ctx, const uint8_t *data, size_t len);
} lg_tx_t;

/** Set up the rings and the config. Call once, before any transport starts. */
esp_err_t lg_session_init(void);

/**
 * A client arrived. Returns its id, or -1 when the node is full — the caller
 * must then refuse the connection *with a reason* (§3, admission control).
 *
 * `hello`, the config and the roster go out immediately. History does not: the
 * client states its cursors next and the node answers that.
 */
int lg_session_join(const lg_tx_t *tx, void *ctx);

/** A client went away. Idempotent. */
void lg_session_leave(int id);

/** One control frame (a JSON object) from a client. */
void lg_session_frame(int id, const char *body);

/** Called once a second by whoever owns the clock: logs a position, pumps any
 *  client that is catching up, and broadcasts stats every few ticks. */
void lg_session_tick(void);

uint8_t lg_session_clients(void);

/** True while a GNSS fix drives the records; false while they are synthetic.
 *  Reported in `hello.mode`, so no client has to guess. */
bool lg_session_gnss_live(void);

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

/** How a transport sends. All three may be called from any task; the transport is
 *  responsible for its own serialisation. */
typedef struct {
    const char *name;    /**< "wifi" or "ble" — goes straight into the roster */

    esp_err_t (*send_text)(void *ctx, const char *text);

    /**
     * Send `len` bytes — always a whole number of 32-byte records.
     *
     * Three answers, and the middle one is the whole reason this comment exists:
     *
     *   ESP_OK             everything went out
     *   ESP_ERR_TIMEOUT    **nothing** went out; the wire is congested, ask again
     *   anything else      this client is unreachable, drop it
     *
     * `ESP_ERR_TIMEOUT` must be all-or-nothing: the session rewinds nothing, so a
     * transport that reports congestion after sending half a buffer would make
     * the client's cursor lie. BLE's notification pool empties routinely during a
     * backlog burst, and treating that as a dead client — which is what it did
     * until 2026-08-01 — evicts phones that are working perfectly.
     */
    esp_err_t (*send_bin)(void *ctx, const uint8_t *data, size_t len);

    /**
     * Optional. The session has given up on this client and will never name `ctx`
     * again: close the wire.
     *
     * Without this the transport keeps a peer that still believes it is client N
     * while the session hands N to the next phone that arrives — so one client's
     * chat goes out under another's callsign, and its eventual disconnect evicts
     * a stranger. Not called from lg_session_leave(): there the transport is
     * already the one doing the telling.
     */
    void (*on_drop)(void *ctx);
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

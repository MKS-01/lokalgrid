#include "ble_gatt.h"

#include <string.h>

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "os/os_mbuf.h"

#include "record.h"
#include "session.h"

static const char *TAG = "gatt";

/* UUIDs. Fixed, and readable on purpose: they print as
 *   6f6b616c-6772-6964-0000-00000000000N     ("okal" "gr" "id")
 * so an nRF Connect scan is recognisable by eye during development.
 *
 * **NimBLE takes the sixteen bytes little-endian**, i.e. the printed string
 * reversed. Getting that wrong produces a service the app filters for and never
 * finds, with nothing anywhere saying why — so the literal below is written in
 * the order NimBLE wants and the comment carries the order humans read. */
#define UUID128_LG(last)                                                        \
    BLE_UUID128_INIT((last), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,          \
                     0x64, 0x69, 0x72, 0x67, 0x6c, 0x61, 0x6b, 0x6f)

static const ble_uuid128_t SVC_UUID     = UUID128_LG(0x01);
static const ble_uuid128_t CHR_CONTROL  = UUID128_LG(0x02);
static const ble_uuid128_t CHR_DATA     = UUID128_LG(0x03);

#define MAX_BLE_CLIENTS 9

typedef struct {
    bool     used;
    uint16_t conn;
    int      id;          /* session client id, valid only while `joined` */
    bool     joined;      /* in the session — see ble_gatt_on_subscribe */
    bool     sub_control;
    bool     sub_data;
    uint16_t mtu;
    uint16_t chunk_seq;
} peer_t;

static peer_t s_peers[MAX_BLE_CLIENTS];
static uint16_t s_control_val_handle = 0;
static uint16_t s_data_val_handle = 0;
static uint16_t s_last_mtu = 0;

/* Who the NimBLE host task is. Learned rather than declared: every GAP and GATT
 * callback below runs on it, so the first one to fire knows. See
 * notify_blocking() for the one thing this is used to decide. */
static TaskHandle_t s_host_task = NULL;
static inline void note_host_task(void) { s_host_task = xTaskGetCurrentTaskHandle(); }

static peer_t *peer_by_conn(uint16_t conn)
{
    for (int i = 0; i < MAX_BLE_CLIENTS; i++) {
        if (s_peers[i].used && s_peers[i].conn == conn) return &s_peers[i];
    }
    return NULL;
}

/* CRC-16/CCITT-FALSE over the chunk header and payload (§4). Hand-written, like
 * everything else on this wire, and small enough to compare against the app's. */
static uint16_t crc16_ccitt(const uint8_t *data, size_t len)
{
    uint16_t crc = 0xffff;
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)data[i] << 8;
        for (int b = 0; b < 8; b++) {
            crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
        }
    }
    return crc;
}

/* ── the session's transport ─────────────────────────────────────────────── */

/* The MTU this connection is *actually* on, asked of the stack rather than
 * remembered.
 *
 * The cached copy is only as fresh as the last BLE_GAP_EVENT_MTU, and nothing
 * guarantees that event lands before the client finishes subscribing — which is
 * the moment the session joins it and sends a 250-byte `hello`. Against a stale
 * 23 the frame was refused and merely logged, so the phone connected,
 * subscribed, and heard nothing: the exact symptom fixed in f64bdfe, reached
 * through a different door. */
static uint16_t peer_mtu(const peer_t *p)
{
    uint16_t live = ble_att_mtu(p->conn);
    if (live >= 23) return live;
    return p->mtu ? p->mtu : 23;
}

/* How many times to wait on a full notification pool before calling it a wall.
 * ~10 ms a try: long enough for the controller to drain a few packets, short
 * enough that one congested client cannot hold the 1 Hz tick. */
#define NOTIFY_TRIES 16
#define NOTIFY_WAIT_MS 10

/* One notification, with backpressure treated as backpressure. NimBLE answers a
 * drained mbuf pool with a NULL buffer or BLE_HS_ENOMEM, and looping blindly on
 * that is the "silent chunk loss under fast sync" trap (§8) — but so is calling
 * it a dead client, which is what dropped phones mid-backfill. Returns
 * ESP_ERR_TIMEOUT when it never got the bytes out. */
static esp_err_t notify_blocking(uint16_t conn, uint16_t handle, const void *data, size_t len)
{
    /* Waiting only works from a task that is not the one doing the draining. The
     * host task is what frees mbufs as the controller acknowledges them, so
     * sleeping on it to wait for mbufs guarantees the wait fails — and holds up
     * every other connection for the duration. From there, one honest attempt. */
    const int tries = (xTaskGetCurrentTaskHandle() == s_host_task) ? 1 : NOTIFY_TRIES;

    for (int try = 0; try < tries; try++) {
        struct os_mbuf *om = ble_hs_mbuf_from_flat(data, len);
        if (om) {
            int rc = ble_gatts_notify_custom(conn, handle, om);
            if (rc == 0) return ESP_OK;
            /* notify_custom consumes the mbuf whatever it returns. */
            if (rc != BLE_HS_ENOMEM) {
                ESP_LOGW(TAG, "notify on conn %u refused: rc %d", conn, rc);
                return ESP_FAIL;   /* a real refusal — the link is gone */
            }
        }
        if (try + 1 < tries) vTaskDelay(pdMS_TO_TICKS(NOTIFY_WAIT_MS));
    }
    return ESP_ERR_TIMEOUT;
}

/**
 * A control frame, fragmented across notifications when it does not fit one.
 *
 * "Keep the frames small enough to fit the MTU" was a bet, and this phone called
 * it: a Galaxy S22 answers a 517-byte request with **256**, leaving 253 bytes,
 * and `stats` (~300 with one client) and `config` (~800) are both over that. The
 * MTU is negotiated and may legally be as low as 23, so any rule of the form
 * "our JSON always fits" is one handset away from being false.
 *
 * One leading byte per notification: 1 = more follows, 0 = this ends the frame.
 * Fragments carry **bytes, not characters** — a multi-byte UTF-8 sequence may be
 * split across two of them, so the receiver joins the bytes and decodes once at
 * the end (the callsigns are ASCII today, the chat text is not).
 *
 * Partial delivery is turned into a hard failure on purpose: the receiver holds
 * a half-built frame, and the only way to guarantee it is not silently welded to
 * the next one is to end the connection and let the app reconnect with a clean
 * buffer.
 */
static esp_err_t tx_text(void *ctx, const char *text)
{
    peer_t *p = (peer_t *)ctx;
    if (!p || !p->used || !p->sub_control) return ESP_FAIL;

    const uint16_t room = (uint16_t)(peer_mtu(p) - 3);
    if (room < 2) {
        ESP_LOGE(TAG, "conn %u: an MTU of %u leaves no room for a control frame",
                 p->conn, peer_mtu(p));
        return ESP_FAIL;
    }
    uint8_t frag[520];
    uint16_t per = (uint16_t)(room - 1);                    /* less the header byte */
    if ((size_t)per + 1 > sizeof(frag)) per = sizeof(frag) - 1;

    const size_t len = strlen(text);
    size_t sent = 0;
    do {
        uint16_t n = (uint16_t)((len - sent) > per ? per : (len - sent));
        frag[0] = (uint8_t)((sent + n) < len ? 1 : 0);
        memcpy(&frag[1], text + sent, n);

        esp_err_t err = notify_blocking(p->conn, s_control_val_handle, frag, (size_t)n + 1);
        if (err != ESP_OK) {
            /* Congestion is reportable only while nothing has gone out; from the
             * second fragment on, the receiver is mid-frame and the honest answer
             * is to drop the link (session.h). */
            return sent == 0 ? err : ESP_FAIL;
        }
        sent += n;
    } while (sent < len);
    return ESP_OK;
}

static esp_err_t tx_bin(void *ctx, const uint8_t *data, size_t len)
{
    peer_t *p = (peer_t *)ctx;
    if (!p || !p->used || !p->sub_data) return ESP_FAIL;

    /* §4 chunk framing. `N = negotiated_mtu - 9` is the payload budget: 3 bytes
     * of ATT overhead plus this 6-byte header. Whole records only, so a chunk
     * carries floor(N / 32) of them and never a fragment. */
    const uint16_t mtu = peer_mtu(p);
    const uint16_t att = (uint16_t)(mtu - 3);
    const uint16_t budget = (att > 6) ? (uint16_t)(att - 6) : 0;
    uint16_t per_chunk = (uint16_t)((budget / LG_RECORD_BYTES) * LG_RECORD_BYTES);
    if (per_chunk == 0) {
        ESP_LOGW(TAG, "mtu %u leaves no room for a whole 32-byte record", mtu);
        return ESP_ERR_INVALID_SIZE;
    }
    uint8_t frame[6 + 512];
    if ((size_t)per_chunk + 6 > sizeof(frame)) per_chunk = sizeof(frame) - 6;

    size_t sent = 0;
    while (sent < len) {
        uint16_t n = (uint16_t)((len - sent) > per_chunk ? per_chunk : (len - sent));

        frame[0] = (uint8_t)(p->chunk_seq & 0xff);
        frame[1] = (uint8_t)(p->chunk_seq >> 8);
        frame[2] = (uint8_t)(n & 0xff);
        frame[3] = (uint8_t)(n >> 8);
        memcpy(&frame[4], data + sent, n);
        uint16_t crc = crc16_ccitt(frame, (size_t)n + 4);
        frame[4 + n] = (uint8_t)(crc & 0xff);
        frame[5 + n] = (uint8_t)(crc >> 8);

        esp_err_t err = notify_blocking(p->conn, s_data_val_handle, frame, (size_t)n + 6);
        if (err == ESP_ERR_TIMEOUT) {
            /* Congestion. Reportable as "nothing went out" only while nothing
             * has: once a chunk is on the wire the session has already counted
             * it, so from there this is a wall rather than a pause (session.h). */
            ESP_LOGW(TAG, "conn %u is congested at chunk %u", p->conn, p->chunk_seq);
            return sent == 0 ? ESP_ERR_TIMEOUT : ESP_FAIL;
        }
        if (err != ESP_OK) return ESP_FAIL;

        p->chunk_seq++;
        sent += n;
    }
    return ESP_OK;
}

/* The session has given up on this peer. Clear the join *before* terminating, so
 * the disconnect that follows does not hand a recycled client id to
 * lg_session_leave() and evict whoever holds it by then. */
static void tx_drop(void *ctx)
{
    peer_t *p = (peer_t *)ctx;
    if (!p) return;
    ESP_LOGI(TAG, "session dropped conn %u — closing the link", p->conn);
    p->joined = false;
    p->used = false;
    ble_gap_terminate(p->conn, BLE_ERR_REM_USER_CONN_TERM);
}

static const lg_tx_t BLE_TX = {
    .name = "ble",
    .send_text = tx_text,
    .send_bin = tx_bin,
    .on_drop = tx_drop,
};

/* ── GATT access ─────────────────────────────────────────────────────────── */

static int on_control_write(uint16_t conn, struct ble_gatt_access_ctxt *ctxt)
{
    peer_t *p = peer_by_conn(conn);
    if (!p) return BLE_ATT_ERR_UNLIKELY;
    if (!p->joined) {
        /* `p->id` means nothing until the join, and handing a stale one to the
         * session would put this frame in another client's mouth. */
        ESP_LOGW(TAG, "conn %u wrote before subscribing — frame refused", conn);
        return BLE_ATT_ERR_UNLIKELY;
    }

    uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
    if (len == 0 || len > 1024) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;

    char body[1025];
    uint16_t copied = 0;
    if (ble_hs_mbuf_to_flat(ctxt->om, body, sizeof(body) - 1, &copied) != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    body[copied] = '\0';
    lg_session_frame(p->id, body);
    return 0;
}

static int chr_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    note_host_task();
    switch (ctxt->op) {
    case BLE_GATT_ACCESS_OP_WRITE_CHR:
        return on_control_write(conn, ctxt);
    case BLE_GATT_ACCESS_OP_READ_CHR:
        /* Nothing to read: everything arrives by notification, which is what makes
         * the sync path a stream rather than a poll. Answering with an empty value
         * is honest; refusing the read would look like a broken service. */
        return 0;
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

static const struct ble_gatt_svc_def SERVICES[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &SVC_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &CHR_CONTROL.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ,
                .val_handle = &s_control_val_handle,
            },
            {
                .uuid = &CHR_DATA.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ,
                .val_handle = &s_data_val_handle,
            },
            { 0 },
        },
    },
    { 0 },
};

/* ── connection lifecycle, driven by ble_adv.c's GAP events ──────────────── */

void ble_gatt_on_connect(uint16_t conn, uint16_t mtu)
{
    note_host_task();
    peer_t *p = NULL;
    for (int i = 0; i < MAX_BLE_CLIENTS; i++) {
        if (!s_peers[i].used) { p = &s_peers[i]; break; }
    }
    if (!p) {
        /* Refuse with a reason rather than leaving a connection nothing will ever
         * answer — a link that opens and stays mute is the worst of both (§3). */
        ESP_LOGW(TAG, "no room for another BLE client — terminating conn %u", conn);
        ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
        return;
    }
    memset(p, 0, sizeof(*p));
    p->used = true;
    p->conn = conn;
    p->mtu = mtu ? mtu : 23;

    /* No session join here: nothing can be *sent* to this client yet. It joins
     * once it has subscribed — see ble_gatt_on_subscribe. */
    ESP_LOGI(TAG, "conn %u attached, mtu %u — waiting for it to subscribe", conn, p->mtu);
}

void ble_gatt_on_subscribe(uint16_t conn, uint16_t attr_handle, bool notify)
{
    note_host_task();
    peer_t *p = peer_by_conn(conn);
    if (!p) return;

    if (attr_handle == s_control_val_handle)   p->sub_control = notify;
    else if (attr_handle == s_data_val_handle) p->sub_data = notify;
    else return;

    if (p->joined) {
        /* Dropping either subscription makes this client unreachable on that half
         * of the protocol, so it is a departure, not a degraded mode. */
        if (!p->sub_control || !p->sub_data) {
            ESP_LOGI(TAG, "conn %u unsubscribed — leaving the session", conn);
            lg_session_leave(p->id);
            p->joined = false;
        }
        return;
    }

    if (!p->sub_control || !p->sub_data) {
        ESP_LOGI(TAG, "conn %u subscribed to %s — waiting for the other", conn,
                 p->sub_control ? "control" : "data");
        return;
    }

    /* The session decides whether there is room across *all* transports: the cap
     * is min(NimBLE, SoftAP) and a phone holds both (§3). */
    int id = lg_session_join(&BLE_TX, p);
    if (id < 0) {
        ESP_LOGW(TAG, "session full — dropping conn %u", conn);
        p->used = false;
        ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
        return;
    }
    p->id = id;
    p->joined = true;
    ESP_LOGI(TAG, "ble client %d on conn %u, mtu %u — hello on its way", id, conn, p->mtu);
}

void ble_gatt_on_disconnect(uint16_t conn)
{
    peer_t *p = peer_by_conn(conn);
    if (!p) return;
    if (p->joined) lg_session_leave(p->id);
    p->used = false;
}

void ble_gatt_on_mtu(uint16_t conn, uint16_t mtu)
{
    s_last_mtu = mtu;
    peer_t *p = peer_by_conn(conn);
    if (!p) return;
    p->mtu = mtu;
    /* Chunk sizes come from this number at runtime, never a guess — the "works on
     * one phone, truncates on another" trap (§8). */
    ESP_LOGI(TAG, "conn %u mtu %u → %u whole records per chunk", conn, mtu,
             (unsigned)(((mtu - 3 - 6) / LG_RECORD_BYTES)));
}

bool ble_gatt_init(void)
{
    int rc = ble_gatts_count_cfg(SERVICES);
    if (rc != 0) {
        ESP_LOGE(TAG, "count_cfg failed: %d", rc);
        return false;
    }
    rc = ble_gatts_add_svcs(SERVICES);
    if (rc != 0) {
        ESP_LOGE(TAG, "add_svcs failed: %d", rc);
        return false;
    }
    ESP_LOGI(TAG, "service registered — control + data, proto 2 over BLE");
    return true;
}

uint8_t ble_gatt_clients(void)
{
    uint8_t n = 0;
    for (int i = 0; i < MAX_BLE_CLIENTS; i++) if (s_peers[i].used && s_peers[i].joined) n++;
    return n;
}

uint16_t ble_gatt_mtu(void)
{
    return s_last_mtu;
}

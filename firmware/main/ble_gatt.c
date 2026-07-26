#include "ble_gatt.h"

#include <string.h>

#include "esp_log.h"
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
    int      id;          /* session client id */
    uint16_t mtu;
    uint16_t chunk_seq;
} peer_t;

static peer_t s_peers[MAX_BLE_CLIENTS];
static uint16_t s_control_val_handle = 0;
static uint16_t s_data_val_handle = 0;
static uint16_t s_last_mtu = 0;

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

static esp_err_t tx_text(void *ctx, const char *text)
{
    peer_t *p = (peer_t *)ctx;
    if (!p || !p->used) return ESP_FAIL;

    /* Control frames are sent whole. A frame longer than the negotiated MTU
     * cannot be notified in one go, and rather than invent a second chunking
     * scheme for JSON, the session's frames are kept small enough — the roster
     * and stats are the biggest and both fit in a 517-byte MTU. If one ever does
     * not, this says so instead of truncating silently. */
    size_t len = strlen(text);
    uint16_t room = (uint16_t)((p->mtu ? p->mtu : 23) - 3);
    if (len > room) {
        ESP_LOGW(TAG, "control frame of %u bytes exceeds the %u-byte MTU payload — dropped",
                 (unsigned)len, room);
        return ESP_ERR_INVALID_SIZE;
    }

    struct os_mbuf *om = ble_hs_mbuf_from_flat(text, len);
    if (!om) return ESP_ERR_NO_MEM;
    int rc = ble_gatts_notify_custom(p->conn, s_control_val_handle, om);
    return rc == 0 ? ESP_OK : ESP_FAIL;
}

static esp_err_t tx_bin(void *ctx, const uint8_t *data, size_t len)
{
    peer_t *p = (peer_t *)ctx;
    if (!p || !p->used) return ESP_FAIL;

    /* §4 chunk framing. `N = negotiated_mtu - 9` is the payload budget: 3 bytes
     * of ATT overhead plus this 6-byte header. Whole records only, so a chunk
     * carries floor(N / 32) of them and never a fragment. */
    const uint16_t att = (uint16_t)((p->mtu ? p->mtu : 23) - 3);
    const uint16_t budget = (att > 6) ? (uint16_t)(att - 6) : 0;
    const uint16_t per_chunk = (uint16_t)((budget / LG_RECORD_BYTES) * LG_RECORD_BYTES);
    if (per_chunk == 0) {
        ESP_LOGW(TAG, "mtu %u leaves no room for a whole 32-byte record", p->mtu);
        return ESP_ERR_INVALID_SIZE;
    }

    size_t sent = 0;
    while (sent < len) {
        uint16_t n = (uint16_t)((len - sent) > per_chunk ? per_chunk : (len - sent));
        uint8_t frame[6 + 512];
        if ((size_t)n + 6 > sizeof(frame)) return ESP_ERR_INVALID_SIZE;

        frame[0] = (uint8_t)(p->chunk_seq & 0xff);
        frame[1] = (uint8_t)(p->chunk_seq >> 8);
        frame[2] = (uint8_t)(n & 0xff);
        frame[3] = (uint8_t)(n >> 8);
        memcpy(&frame[4], data + sent, n);
        uint16_t crc = crc16_ccitt(frame, (size_t)n + 4);
        frame[4 + n] = (uint8_t)(crc & 0xff);
        frame[5 + n] = (uint8_t)(crc >> 8);

        struct os_mbuf *om = ble_hs_mbuf_from_flat(frame, (size_t)n + 6);
        if (!om) return ESP_ERR_NO_MEM;
        int rc = ble_gatts_notify_custom(p->conn, s_data_val_handle, om);
        if (rc != 0) {
            /* Notification queue full is the documented failure here, and looping
             * blindly on it is the "silent chunk loss under fast sync" trap (§8).
             * Reporting it lets the session drop the client rather than pretend. */
            ESP_LOGW(TAG, "notify failed (rc %d) at chunk %u", rc, p->chunk_seq);
            return ESP_FAIL;
        }
        p->chunk_seq++;
        sent += n;
    }
    return ESP_OK;
}

static const lg_tx_t BLE_TX = {
    .name = "ble",
    .send_text = tx_text,
    .send_bin = tx_bin,
};

/* ── GATT access ─────────────────────────────────────────────────────────── */

static int on_control_write(uint16_t conn, struct ble_gatt_access_ctxt *ctxt)
{
    peer_t *p = peer_by_conn(conn);
    if (!p) return BLE_ATT_ERR_UNLIKELY;

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
    peer_t *p = NULL;
    for (int i = 0; i < MAX_BLE_CLIENTS; i++) {
        if (!s_peers[i].used) { p = &s_peers[i]; break; }
    }
    if (!p) {
        ESP_LOGW(TAG, "no room for another BLE client");
        return;
    }
    memset(p, 0, sizeof(*p));
    p->used = true;
    p->conn = conn;
    p->mtu = mtu ? mtu : 23;

    /* The session decides whether there is room across *all* transports: the cap
     * is min(NimBLE, SoftAP) and a phone holds both (§3). */
    int id = lg_session_join(&BLE_TX, p);
    if (id < 0) {
        p->used = false;
        ESP_LOGW(TAG, "session full — dropping conn %u", conn);
        ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
        return;
    }
    p->id = id;
    ESP_LOGI(TAG, "ble client %d on conn %u, mtu %u", id, conn, p->mtu);
}

void ble_gatt_on_disconnect(uint16_t conn)
{
    peer_t *p = peer_by_conn(conn);
    if (!p) return;
    lg_session_leave(p->id);
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
    for (int i = 0; i < MAX_BLE_CLIENTS; i++) if (s_peers[i].used) n++;
    return n;
}

uint16_t ble_gatt_mtu(void)
{
    return s_last_mtu;
}

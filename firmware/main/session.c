/* Lokalgrid — proto 2, once, for every transport.
 *
 * The app already speaks this: the mock node (mock-node/src/server.js) has been
 * serving it since Phase 00. Everything here is the node's half of the same
 * contract, which is why the app needs no protocol change to point at real
 * hardware — only the URL, or a BLE connection instead of a socket.
 *
 * What is honest about this version, and stated rather than implied:
 *
 *  - `hello.mode` is **"synthetic"** until GNSS is wired. The records are a
 *    deterministic walk, and the app renders that word in its status line, so
 *    nobody mistakes a demo track for a fix. The moment the GNSS reader lands,
 *    the same field says "gnss".
 *  - **Nothing is ever `relayed`.** There is no SX1262 code (§1 safety rule 1:
 *    no TX path before an antenna flag), so a message is delivered to everyone
 *    on this node and no further. Claiming a radio hop that did not happen would
 *    break the one rule this project has about queue state (§6).
 *  - The **client is authoritative about what it has received** (§3): backlog is
 *    sent only in answer to a `cursor` frame, never pushed at connect. The mock
 *    learned that the hard way on 2026-07-26.
 */
#include "session.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#include "gnss.h"
#include "record.h"
#include "wifi_ap.h"

static const char *TAG = "session";

/* ── limits, from §3 ─────────────────────────────────────────────────────── */
#define MAX_CLIENTS      9        /* min(NimBLE 9, SoftAP 10) — the effective cap */
#define CHAT_RING        48       /* short backfill ring; the phone is the archive (§2) */
#define CHAT_TEXT_MAX    160
#define POS_RING         900      /* 15 minutes at 1 Hz, in PSRAM */
#define BACKLOG_CHUNK    60       /* bounded chunks, interleaved with live traffic (§3) */
#define LG_NAME_MAX      16   /* not NAME_MAX: that is POSIX's, and it is 255 */

/* Callsigns, never personal names, anywhere in this project. */
static const char *CALLSIGNS[] = {
    "alpha", "bravo", "charlie", "delta", "echo",
    "foxtrot", "golf", "hotel", "india",
};

typedef struct {
    bool     used;
    const lg_tx_t *tx;       /* how to reach this client */
    void    *ctx;            /* the transport's own handle for it */
    uint8_t  id;
    char     name[LG_NAME_MAX + 1];
    uint32_t msg_cursor;     /* last chat seq handed over */
    uint32_t pos_cursor;     /* last position seq handed over */
    uint16_t messages;
    /* last shared position, for the distance decimation (§3) */
    bool     has_pos;
    int32_t  lat_e7, lon_e7;
    uint8_t  hd;
    uint32_t epoch;
    int64_t  pos_at_us;
} client_t;

typedef struct {
    uint32_t seq;
    uint8_t  from;
    char     name[LG_NAME_MAX + 1];
    char     text[CHAT_TEXT_MAX + 1];
    uint32_t epoch;
    uint8_t  lane;
} chat_msg_t;

static client_t s_clients[MAX_CLIENTS];

static chat_msg_t s_chat[CHAT_RING];
static uint32_t s_chat_seq = 0;      /* last assigned; 0 means nothing yet */

static uint8_t *s_pos = NULL;        /* POS_RING × 32 bytes, PSRAM if there is any */
static uint32_t s_pos_newest = 0;    /* seq of the newest record, 0 = none */
static uint32_t s_pos_count = 0;

static int64_t s_boot_us = 0;
static uint16_t s_device_id = 0;

/* Config. Two keys are settable; the rest are refused *with the reason they are
 * not settings*, which is the §6 rule about locked config made literal. */
static int s_pos_interval_s = 1;
static int s_decimation_m = 50;

static bool s_gnss_live = false;     /* flips when the GNSS reader lands */

/* Guards every outbound frame — see send_text_c below. */
static SemaphoreHandle_t s_lock = NULL;

/* ── helpers ─────────────────────────────────────────────────────────────── */

static int64_t now_us(void) { return esp_timer_get_time(); }
static uint32_t uptime_s(void) { return (uint32_t)((now_us() - s_boot_us) / 1000000); }

static client_t *client_by_id(int id)
{
    if (id < 0 || id >= MAX_CLIENTS || !s_clients[id].used) return NULL;
    return &s_clients[id];
}

static uint8_t client_count(void)
{
    uint8_t n = 0;
    for (int i = 0; i < MAX_CLIENTS; i++) if (s_clients[i].used) n++;
    return n;
}

/* JSON escaping for the two strings that come from a user: callsign and message
 * text. Small on purpose — a control frame is not a place to be clever. */
static void json_escape(const char *in, char *out, size_t out_len)
{
    size_t o = 0;
    for (const char *p = in; *p && o + 7 < out_len; p++) {
        unsigned char c = (unsigned char)*p;
        if (c == '"' || c == '\\') {
            out[o++] = '\\'; out[o++] = (char)c;
        } else if (c == '\n') {
            out[o++] = '\\'; out[o++] = 'n';
        } else if (c < 0x20) {
            o += (size_t)snprintf(&out[o], out_len - o, "\\u%04x", c);
        } else {
            out[o++] = (char)c;
        }
    }
    out[o] = '\0';
}

/* One mutex for every outbound frame, whatever the transport: sends come from
 * the 1 Hz tick and from whichever task the transport's own callback runs on, and
 * two interleaved writes to one client produce a corrupt frame — which kills the
 * connection and reads exactly like a protocol bug. */
static esp_err_t send_text_c(client_t *c, const char *text)
{
    if (!c || !c->used || !c->tx || !c->tx->send_text) return ESP_FAIL;
    if (s_lock) xSemaphoreTake(s_lock, portMAX_DELAY);
    esp_err_t err = c->tx->send_text(c->ctx, text);
    if (s_lock) xSemaphoreGive(s_lock);
    return err;
}

static esp_err_t send_bin_c(client_t *c, const uint8_t *data, size_t len)
{
    if (!c || !c->used || !c->tx || !c->tx->send_bin) return ESP_FAIL;
    if (s_lock) xSemaphoreTake(s_lock, portMAX_DELAY);
    esp_err_t err = c->tx->send_bin(c->ctx, data, len);
    if (s_lock) xSemaphoreGive(s_lock);
    return err;
}

/* A send that fails means the wire is gone — drop the client rather than leaving
 * a roster entry nobody can reach.
 *
 * `tell_tx` is the half that was missing. Freeing the slot here is not enough:
 * the transport still holds a peer that believes it is client N, and N is handed
 * to the next phone that arrives. That peer's next control frame then arrives in
 * a stranger's mouth, and its eventual disconnect evicts the stranger. So when
 * the *session* gives up, it says so and the transport closes the wire; when the
 * *transport* is the one reporting a departure (lg_session_leave) it does not,
 * because there is nothing left to close. */
static void drop_client(client_t *c, const char *why, bool tell_tx)
{
    if (!c || !c->used) return;
    ESP_LOGI(TAG, "%s left (%s) — %u client%s", c->name, why,
             (unsigned)(client_count() - 1), client_count() == 2 ? "" : "s");
    const lg_tx_t *tx = c->tx;
    void *ctx = c->ctx;
    c->used = false;
    c->tx = NULL;
    c->ctx = NULL;
    if (tell_tx && tx && tx->on_drop) tx->on_drop(ctx);
}

static void broadcast_text(const char *text)
{
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (!s_clients[i].used) continue;
        esp_err_t err = send_text_c(&s_clients[i], text);
        /* Congestion is not a departure. A peer frame or a stats line lost to a
         * full notification pool costs nothing — the next one is a second away —
         * whereas evicting a working phone costs it the whole session. */
        if (err != ESP_OK && err != ESP_ERR_TIMEOUT) {
            drop_client(&s_clients[i], "send failed", true);
        }
    }
}

/* ── frames out ──────────────────────────────────────────────────────────── */

static uint32_t pos_oldest(void)
{
    if (s_pos_count == 0) return 0;
    return s_pos_newest - s_pos_count + 1;
}

static void send_roster(client_t *only)
{
    char buf[128 + MAX_CLIENTS * 64];
    int n = snprintf(buf, sizeof(buf), "{\"type\":\"roster\",\"cap\":%d,\"clients\":[", MAX_CLIENTS);
    bool first = true;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (!s_clients[i].used) continue;
        char safe[LG_NAME_MAX * 2 + 4];
        json_escape(s_clients[i].name, safe, sizeof(safe));
        n += snprintf(buf + n, sizeof(buf) - n,
                      "%s{\"id\":%u,\"name\":\"%s\",\"transport\":\"%s\"}",
                      first ? "" : ",", s_clients[i].id, safe,
                      s_clients[i].tx ? s_clients[i].tx->name : "wifi");
        first = false;
    }
    snprintf(buf + n, sizeof(buf) - n, "]}");
    if (only) send_text_c(only, buf); else broadcast_text(buf);
}

static void send_config(client_t *c)
{
    /* `locked` carries *why*, not just that it is locked: a greyed-out box with
     * no explanation is the spinner problem in another hat (§6). */
    char buf[900];
    snprintf(buf, sizeof(buf),
        "{\"type\":\"config\","
        "\"values\":{\"posIntervalS\":%d,\"decimationM\":%d,\"dutyPct\":\"1.0\","
                    "\"apIdleTimeoutS\":\"300\",\"maxClients\":\"%d\","
                    "\"loraLink\":\"absent — phase 06\"},"
        "\"locked\":{"
          "\"dutyPct\":\"enforced in firmware, not a setting — a toggle eventually gets left wrong\","
          "\"apIdleTimeoutS\":\"firmware limit; ~100 mA left on turns a week of runtime into a day\","
          "\"maxClients\":\"hardware ceiling: min(NimBLE 9, SoftAP 10)\","
          "\"loraLink\":\"no radio code exists yet — no TX path before an antenna flag\"},"
        "\"editable\":{"
          "\"posIntervalS\":{\"type\":\"int\",\"min\":1,\"max\":60,\"note\":\"how often a position goes out\"},"
          "\"decimationM\":{\"type\":\"int\",\"min\":10,\"max\":500,\"note\":\"positions decimate by distance, not time (§3)\"}}}",
        s_pos_interval_s, s_decimation_m, MAX_CLIENTS);
    send_text_c(c, buf);
}

/* The node's own last-known satellite count and HDOP, for the stats frame. */
static uint8_t node_sats(void)
{
    lg_fix_t f;
    gnss_get(&f);
    return f.sats;
}

static uint8_t node_hdop(void)
{
    lg_fix_t f;
    gnss_get(&f);
    return f.hdop_x10;
}

static void send_stats(client_t *only)
{
    char buf[256 + MAX_CLIENTS * 96];
    /* Airtime is genuinely zero: there is no radio yet. Reporting a made-up
     * figure here would make the whole airtime-economy feature a lie later. */
    int n = snprintf(buf, sizeof(buf),
        "{\"type\":\"stats\",\"uptimeS\":%lu,\"queueDepth\":0,\"airtimeMs\":0,"
        "\"dutyActualPct\":0.0,\"dutyUsedPct\":0.0,"
        "\"posOldest\":%lu,\"posNewest\":%lu,\"posHeld\":%lu,"
        /* The node's own fix, so a client can tell a stationary node from a node
         * that has stopped seeing satellites. Without this the two look alike. */
        "\"gnssSource\":\"%s\",\"gnssAgeS\":%ld,\"gnssSats\":%u,\"gnssHdop\":%u,"
        "\"clients\":[",
        (unsigned long)uptime_s(), (unsigned long)pos_oldest(),
        (unsigned long)s_pos_newest, (unsigned long)s_pos_count,
        s_gnss_live ? "gnss" : "synthetic", (long)gnss_age_s(),
        node_sats(), node_hdop());
    bool first = true;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (!s_clients[i].used) continue;
        char safe[LG_NAME_MAX * 2 + 4];
        json_escape(s_clients[i].name, safe, sizeof(safe));
        n += snprintf(buf + n, sizeof(buf) - n,
                      "%s{\"id\":%u,\"name\":\"%s\",\"airtimeMs\":0,\"messages\":%u,\"sharePct\":0}",
                      first ? "" : ",", s_clients[i].id, safe, s_clients[i].messages);
        first = false;
    }
    snprintf(buf + n, sizeof(buf) - n, "]}");
    if (only) send_text_c(only, buf); else broadcast_text(buf);
}

static void send_hello(client_t *c)
{
    char safe[LG_NAME_MAX * 2 + 4];
    json_escape(c->name, safe, sizeof(safe));
    char buf[384];
    snprintf(buf, sizeof(buf),
        "{\"type\":\"hello\",\"proto\":2,\"deviceId\":%u,\"recordBytes\":%d,"
        "\"hz\":%.2f,\"mode\":\"%s\",\"you\":{\"id\":%u,\"name\":\"%s\"},"
        "\"cap\":%d,\"duty\":0.01,"
        "\"posOldest\":%lu,\"posNewest\":%lu,\"posHeld\":%lu}",
        s_device_id, LG_RECORD_BYTES, 1.0 / (double)s_pos_interval_s,
        s_gnss_live ? "gnss" : "synthetic", c->id, safe, MAX_CLIENTS,
        (unsigned long)pos_oldest(), (unsigned long)s_pos_newest,
        (unsigned long)s_pos_count);
    send_text_c(c, buf);
}

static void send_rejected(client_t *c, const char *scope, const char *msg_id, const char *reason)
{
    char buf[320];
    if (msg_id) {
        snprintf(buf, sizeof(buf),
                 "{\"type\":\"rejected\",\"scope\":\"%s\",\"msgId\":\"%s\",\"reason\":\"%s\"}",
                 scope, msg_id, reason);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"type\":\"rejected\",\"scope\":\"%s\",\"reason\":\"%s\"}", scope, reason);
    }
    send_text_c(c, buf);
}

/* ── the position log ────────────────────────────────────────────────────── */

static uint8_t *pos_slot(uint32_t seq)
{
    return &s_pos[(size_t)((seq - 1) % POS_RING) * LG_RECORD_BYTES];
}

static bool pos_holds(uint32_t seq)
{
    return s_pos_count > 0 && seq >= pos_oldest() && seq <= s_pos_newest;
}

/* Log first, then broadcast: a position that went out but was never logged
 * cannot be replayed to a client that reconnects (§3, the node remembers). */
static void log_and_broadcast(const lg_record_t *r)
{
    if (!s_pos) return;
    s_pos_newest++;
    if (s_pos_count < POS_RING) s_pos_count++;
    lg_record_encode(r, pos_slot(s_pos_newest));

    for (int i = 0; i < MAX_CLIENTS; i++) {
        client_t *c = &s_clients[i];
        if (!c->used) continue;
        /* A client still catching up must not be handed live records out of
         * order — its cursor advances through the backlog first. */
        if (c->pos_cursor + 1 != s_pos_newest) continue;
        esp_err_t err = send_bin_c(c, pos_slot(s_pos_newest), LG_RECORD_BYTES);
        /* Congestion is not a departure: leave the cursor where it is and this
         * record goes out as backlog on the next tick. */
        if (err == ESP_ERR_TIMEOUT) continue;
        if (err != ESP_OK) {
            drop_client(c, "record send failed", true);
            continue;
        }
        c->pos_cursor = s_pos_newest;
    }
}

typedef enum { PUMP_OK, PUMP_BLOCKED, PUMP_GONE } pump_t;

/**
 * Hand a catching-up client its next slice of backlog.
 *
 * **In runs, not one record at a time.** This used to call send_bin_c() once per
 * 32-byte record, which on BLE meant sixty separate notifications — sixty §4
 * chunks each carrying a single record, against a notification pool that holds a
 * dozen. The pool emptied a few records in, the send failed, and the client was
 * dropped mid-backfill: the phone stayed connected over GATT while the node had
 * quietly forgotten it. It also wasted the chunk framing entirely, since a chunk
 * sized from a 517-byte MTU has room for fifteen records.
 *
 * A run is what is contiguous *in the ring*, so the transport gets one buffer it
 * can split into full chunks. The runs stop at the ring's wrap, at the newest
 * record, and at BACKLOG_CHUNK — bounded chunks interleaved with live traffic is
 * still the rule (§3).
 */
static pump_t pump_client(client_t *c)
{
    uint32_t sent = 0;
    while (c->pos_cursor < s_pos_newest && sent < BACKLOG_CHUNK) {
        uint32_t seq = c->pos_cursor + 1;
        if (!pos_holds(seq)) {
            /* It aged out underneath us — resume from what the node still has. */
            c->pos_cursor = pos_oldest() - 1;
            continue;
        }
        uint32_t run = POS_RING - (uint32_t)((seq - 1) % POS_RING);   /* to the wrap */
        uint32_t left = s_pos_newest - seq + 1;
        if (run > left) run = left;
        if (run > BACKLOG_CHUNK - sent) run = BACKLOG_CHUNK - sent;

        esp_err_t err = send_bin_c(c, pos_slot(seq), (size_t)run * LG_RECORD_BYTES);
        if (err == ESP_ERR_TIMEOUT) return PUMP_BLOCKED;   /* nothing went out */
        if (err != ESP_OK) {
            drop_client(c, "backlog send failed", true);
            return PUMP_GONE;
        }
        c->pos_cursor = seq + run - 1;
        sent += run;
    }
    return PUMP_OK;
}

/* Where the client got to, and how much is still owed — a number, never a
 * spinner (§6). Sent after every pump so the app can render progress. */
static void send_backlog_tail(client_t *c)
{
    char buf[160];
    if (c->pos_cursor >= s_pos_newest) {
        snprintf(buf, sizeof(buf), "{\"type\":\"backlogDone\",\"cursor\":%lu,\"live\":true}",
                 (unsigned long)c->pos_cursor);
    } else {
        snprintf(buf, sizeof(buf), "{\"type\":\"backlogChunk\",\"cursor\":%lu,\"remaining\":%lu}",
                 (unsigned long)c->pos_cursor,
                 (unsigned long)(s_pos_newest - c->pos_cursor));
    }
    send_text_c(c, buf);
}

/* Answer a cursor: say what is owed and what aged out *before* sending it. */
static void resume_positions(client_t *c, uint32_t from_client)
{
    uint32_t oldest = pos_oldest();
    uint32_t want = from_client + 1;
    uint32_t lost = 0;
    const char *reason = NULL;

    if (s_pos_count == 0) {
        want = 1;
    } else if (want < oldest) {
        lost = oldest - want;
        want = oldest;
        reason = "positions aged out of the node before you asked — the track has a gap";
    }

    uint32_t count = (s_pos_count == 0 || want > s_pos_newest) ? 0 : (s_pos_newest - want + 1);

    char buf[420];
    int n = snprintf(buf, sizeof(buf),
        "{\"type\":\"backlog\",\"from\":%lu,\"to\":%lu,\"count\":%lu,\"lost\":%lu,"
        "\"oldest\":%lu,\"newest\":%lu,\"held\":%lu",
        (unsigned long)want, (unsigned long)s_pos_newest, (unsigned long)count,
        (unsigned long)lost, (unsigned long)oldest, (unsigned long)s_pos_newest,
        (unsigned long)s_pos_count);
    if (reason) {
        n += snprintf(buf + n, sizeof(buf) - n, ",\"reason\":\"%s\"", reason);
    }
    snprintf(buf + n, sizeof(buf) - n, "}");
    send_text_c(c, buf);

    c->pos_cursor = want - 1;

    /* The records themselves go out from the tick (pump_backlog), not from here.
     * This runs on whichever task delivered the client's frame — for BLE that is
     * the NimBLE host task, and a hundred notifications pushed from the task that
     * is *supposed* to be draining them is how the pool empties in the first
     * place. Bounded chunks interleaved with live traffic (§3) is the same rule
     * seen from the other side, and the wait is at most one tick.
     *
     * A client that is already up to date gets its answer now, because
     * pump_backlog will skip it and it would otherwise wait for a `backlogDone`
     * that never comes. */
    if (c->pos_cursor >= s_pos_newest) send_backlog_tail(c);
}

/* Keep feeding a catching-up client between live records. */
static void pump_backlog(void)
{
    for (int i = 0; i < MAX_CLIENTS; i++) {
        client_t *c = &s_clients[i];
        if (!c->used || c->pos_cursor >= s_pos_newest) continue;
        if (pump_client(c) != PUMP_GONE) send_backlog_tail(c);
    }
}

/* ── chat ────────────────────────────────────────────────────────────────── */

static void send_chat_frame(client_t *c, const chat_msg_t *m, const char *msg_id)
{
    char safe_name[LG_NAME_MAX * 2 + 4], safe_text[CHAT_TEXT_MAX * 2 + 8];
    json_escape(m->name, safe_name, sizeof(safe_name));
    json_escape(m->text, safe_text, sizeof(safe_text));

    char buf[CHAT_TEXT_MAX * 2 + 256];
    int n = snprintf(buf, sizeof(buf),
        "{\"type\":\"chat\",\"seq\":%lu,\"from\":%u,\"name\":\"%s\",\"text\":\"%s\","
        "\"epoch\":%lu,\"lane\":%u",
        (unsigned long)m->seq, m->from, safe_name, safe_text,
        (unsigned long)m->epoch, m->lane);
    if (msg_id) {
        n += snprintf(buf + n, sizeof(buf) - n, ",\"msgId\":\"%s\"", msg_id);
    }
    snprintf(buf + n, sizeof(buf) - n, "}");
    send_text_c(c, buf);
}

static void resume_chat(client_t *c, uint32_t from_seq)
{
    uint32_t oldest = (s_chat_seq > CHAT_RING) ? s_chat_seq - CHAT_RING + 1 : 1;
    for (uint32_t seq = (from_seq + 1 > oldest ? from_seq + 1 : oldest); seq <= s_chat_seq; seq++) {
        const chat_msg_t *m = &s_chat[(seq - 1) % CHAT_RING];
        if (m->seq != seq) continue;
        send_chat_frame(c, m, NULL);
    }
    c->msg_cursor = s_chat_seq;
}

/* ── incoming frames ─────────────────────────────────────────────────────── */

/* A deliberately small JSON reader: find "key": and copy the string or number
 * that follows. The control frames are four shapes with flat fields, and a full
 * parser would be more code than the protocol it parses. Protobuf replaces all
 * of this at Phase 05 (§6) — until then, hand-written on both sides is the point.
 */
static bool json_str(const char *body, const char *key, char *out, size_t out_len)
{
    char pat[40];
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    const char *p = strstr(body, pat);
    if (!p) return false;
    p = strchr(p + strlen(pat), ':');
    if (!p) return false;
    p++;
    while (*p == ' ') p++;
    if (*p != '"') return false;
    p++;
    size_t o = 0;
    while (*p && *p != '"' && o + 1 < out_len) {
        if (*p == '\\' && p[1]) {
            p++;
            switch (*p) {
            case 'n': out[o++] = '\n'; break;
            case 't': out[o++] = '\t'; break;
            case 'r': break;
            default:  out[o++] = *p; break;
            }
            p++;
            continue;
        }
        out[o++] = *p++;
    }
    out[o] = '\0';
    return true;
}

static bool json_num(const char *body, const char *key, long *out)
{
    char pat[40];
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    const char *p = strstr(body, pat);
    if (!p) return false;
    p = strchr(p + strlen(pat), ':');
    if (!p) return false;
    *out = strtol(p + 1, NULL, 10);
    return true;
}

/* Metres between two positions. Equirectangular rather than haversine: at the
 * distances decimation cares about (tens of metres) the difference is
 * millimetres, and this costs one cosine. */
static double distance_m(int32_t lat1_e7, int32_t lon1_e7, int32_t lat2_e7, int32_t lon2_e7)
{
    const double to_rad = M_PI / 180.0 / 1e7;
    double lat_m = (double)(lat2_e7 - lat1_e7) * to_rad * 6371000.0;
    double lon_m = (double)(lon2_e7 - lon1_e7) * to_rad * 6371000.0 *
                   cos((double)lat1_e7 / 1e7 * M_PI / 180.0);
    return sqrt(lat_m * lat_m + lon_m * lon_m);
}

static void handle_pos(client_t *c, const char *body)
{
    long lat = 0, lon = 0, hd = 0, epoch = 0;
    if (!json_num(body, "latE7", &lat) || !json_num(body, "lonE7", &lon)) {
        send_rejected(c, "position", NULL, "position is not a number");
        return;
    }
    json_num(body, "hd", &hd);
    json_num(body, "epoch", &epoch);

    if (lat < -900000000L || lat > 900000000L || lon < -1800000000L || lon > 1800000000L) {
        send_rejected(c, "position", NULL, "position out of range");
        return;
    }

    if (c->has_pos) {
        double moved = distance_m(c->lat_e7, c->lon_e7, (int32_t)lat, (int32_t)lon);
        if (moved < (double)s_decimation_m) {
            /* Decimate by distance, not time (§3) — and answer with the reason,
             * because a silent skip looks exactly like a dead GPS. */
            char buf[220];
            snprintf(buf, sizeof(buf),
                     "{\"type\":\"peerSkip\",\"movedM\":%d,\"reason\":\"%d m from your last "
                     "shared position — decimating below %d m\"}",
                     (int)moved, (int)moved, s_decimation_m);
            send_text_c(c, buf);
            return;
        }
    }

    c->has_pos = true;
    c->lat_e7 = (int32_t)lat;
    c->lon_e7 = (int32_t)lon;
    c->hd = (uint8_t)(hd < 0 ? 0 : (hd > 255 ? 255 : hd));
    c->epoch = (uint32_t)epoch;
    c->pos_at_us = now_us();

    char safe[LG_NAME_MAX * 2 + 4];
    json_escape(c->name, safe, sizeof(safe));
    char buf[300];
    snprintf(buf, sizeof(buf),
        "{\"type\":\"peer\",\"id\":%u,\"name\":\"%s\",\"latE7\":%ld,\"lonE7\":%ld,"
        "\"hd\":%u,\"epoch\":%lu,\"ageS\":0,\"movedM\":0}",
        c->id, safe, lat, lon, c->hd, (unsigned long)c->epoch);
    broadcast_text(buf);
    ESP_LOGI(TAG, "%s shared a position", c->name);
}

static void handle_send(client_t *c, const char *body)
{
    char text[CHAT_TEXT_MAX + 1] = {0};
    char msg_id[40] = {0};
    long lane = 2;
    json_str(body, "msgId", msg_id, sizeof(msg_id));
    json_num(body, "lane", &lane);

    if (!json_str(body, "text", text, sizeof(text)) || text[0] == '\0') {
        send_rejected(c, "message", msg_id[0] ? msg_id : NULL, "empty message");
        return;
    }

    s_chat_seq++;
    chat_msg_t *m = &s_chat[(s_chat_seq - 1) % CHAT_RING];
    m->seq = s_chat_seq;
    m->from = c->id;
    m->epoch = (uint32_t)time(NULL);
    m->lane = (uint8_t)(lane < 0 ? 2 : (lane > 3 ? 3 : lane));
    strncpy(m->name, c->name, LG_NAME_MAX);
    m->name[LG_NAME_MAX] = '\0';
    strncpy(m->text, text, CHAT_TEXT_MAX);
    m->text[CHAT_TEXT_MAX] = '\0';
    c->messages++;

    /* The sender gets its msgId echoed so it can reconcile the pending bubble;
     * everyone else gets the same message without it. */
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (!s_clients[i].used) continue;
        bool mine = (&s_clients[i] == c);
        send_chat_frame(&s_clients[i], m, mine && msg_id[0] ? msg_id : NULL);
        s_clients[i].msg_cursor = s_chat_seq;
    }

    /* No `queued`, no `relayed`: there is no radio. The message exists on this
     * node and reached everyone attached to it — nothing more is claimed. */
    ESP_LOGI(TAG, "%s: %s (seq %lu, lane %u)", c->name, m->text,
             (unsigned long)m->seq, m->lane);
}

static void handle_name(client_t *c, const char *body)
{
    char want[LG_NAME_MAX + 1] = {0};
    if (!json_str(body, "name", want, sizeof(want)) || want[0] == '\0') {
        send_rejected(c, "name", NULL, "a callsign cannot be empty");
        return;
    }
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (s_clients[i].used && &s_clients[i] != c && strcmp(s_clients[i].name, want) == 0) {
            char reason[96];
            snprintf(reason, sizeof(reason), "%s is already on this node", want);
            send_rejected(c, "name", NULL, reason);
            return;
        }
    }
    ESP_LOGI(TAG, "%s is now %s", c->name, want);
    strncpy(c->name, want, LG_NAME_MAX);
    c->name[LG_NAME_MAX] = '\0';
    send_roster(NULL);   /* the roster is the node's answer about who you are (§3) */
}

static void handle_config(client_t *c, const char *body)
{
    char applied[160] = {0};
    char refused[420] = {0};
    long v = 0;

    if (json_num(body, "posIntervalS", &v)) {
        if (v >= 1 && v <= 60) {
            s_pos_interval_s = (int)v;
            snprintf(applied + strlen(applied), sizeof(applied) - strlen(applied),
                     "%s\"posIntervalS\":\"%ld\"", applied[0] ? "," : "", v);
        } else {
            snprintf(refused + strlen(refused), sizeof(refused) - strlen(refused),
                     "%s{\"key\":\"posIntervalS\",\"reason\":\"1..60 seconds\"}",
                     refused[0] ? "," : "");
        }
    }
    if (json_num(body, "decimationM", &v)) {
        if (v >= 10 && v <= 500) {
            s_decimation_m = (int)v;
            snprintf(applied + strlen(applied), sizeof(applied) - strlen(applied),
                     "%s\"decimationM\":\"%ld\"", applied[0] ? "," : "", v);
        } else {
            snprintf(refused + strlen(refused), sizeof(refused) - strlen(refused),
                     "%s{\"key\":\"decimationM\",\"reason\":\"10..500 metres\"}",
                     refused[0] ? "," : "");
        }
    }
    /* Locked keys are refused with the reason they are not settings. */
    static const char *locked[][2] = {
        {"dutyPct", "the 1% duty cycle is enforced in firmware, not configured"},
        {"apIdleTimeoutS", "a firmware limit — a toggle eventually gets left wrong"},
        {"maxClients", "hardware ceiling: min(NimBLE 9, SoftAP 10)"},
    };
    for (size_t i = 0; i < sizeof(locked) / sizeof(locked[0]); i++) {
        if (strstr(body, locked[i][0])) {
            snprintf(refused + strlen(refused), sizeof(refused) - strlen(refused),
                     "%s{\"key\":\"%s\",\"reason\":\"%s\"}",
                     refused[0] ? "," : "", locked[i][0], locked[i][1]);
        }
    }

    char buf[700];
    snprintf(buf, sizeof(buf),
             "{\"type\":\"configResult\",\"applied\":{%s},\"refused\":[%s]}", applied, refused);
    send_text_c(c, buf);
    if (applied[0]) {
        ESP_LOGI(TAG, "%s wrote config: {%s}", c->name, applied);
        send_config(c);
    }
}

static void handle_frame(client_t *c, const char *body)
{
    char type[24] = {0};
    if (!json_str(body, "type", type, sizeof(type))) {
        send_rejected(c, "protocol", NULL, "frame has no type");
        return;
    }

    if (strcmp(type, "send") == 0)        handle_send(c, body);
    else if (strcmp(type, "pos") == 0)    handle_pos(c, body);
    else if (strcmp(type, "name") == 0)   handle_name(c, body);
    else if (strcmp(type, "config") == 0) handle_config(c, body);
    else if (strcmp(type, "cursor") == 0) {
        long seq = 0, pos_seq = 0;
        json_num(body, "seq", &seq);
        json_num(body, "posSeq", &pos_seq);
        resume_chat(c, (uint32_t)(seq < 0 ? 0 : seq));
        resume_positions(c, (uint32_t)(pos_seq < 0 ? 0 : pos_seq));
    } else if (strcmp(type, "reset") == 0) {
        /* The mock restarts its synthetic walk here. On real hardware the track
         * is whatever the GNSS says, so this is refused with the reason. */
        send_rejected(c, "reset", NULL,
                      "this node logs what the GNSS gives it — there is no track to restart");
    } else {
        char reason[80];
        snprintf(reason, sizeof(reason), "unknown frame \"%s\"", type);
        send_rejected(c, "protocol", NULL, reason);
    }
}

/* ── the synthetic track, until GNSS lands ───────────────────────────────── */

/* A slow walk with breathing HDOP, so the app has real uncertainty to render
 * (§6: never a crisp dot). Mirrors the mock's generator closely enough that the
 * two look alike on screen; `hello.mode` says which one you are watching. */
static void synth_tick(void)
{
    static double lat = 22.1050, lon = 82.1860;
    static double bearing = 1.2;
    static uint32_t n = 0;
    n++;

    bearing += ((double)(esp_random() % 1000) / 1000.0 - 0.5) * 0.25;
    const double step_m = 1.4;   /* walking pace at 1 Hz */
    lat += cos(bearing) * step_m / 111320.0;
    lon += sin(bearing) * step_m / (111320.0 * cos(lat * M_PI / 180.0));

    /* HDOP that breathes between 0.8 and 3.4, with the satellite count falling
     * as it rises — the honest pairing, and it makes the ring move. */
    double hdop = 2.1 + 1.3 * sin((double)n / 37.0);
    uint8_t sv = (uint8_t)(12 - (int)(hdop * 1.5));
    bool fix_3d = (n % 240) > 20;   /* a 2D drop every four minutes */

    lg_record_t r = {
        .epoch = (uint32_t)time(NULL),
        .lat_e7 = (int32_t)lround(lat * 1e7),
        .lon_e7 = (int32_t)lround(lon * 1e7),
        .alt = (int16_t)(263 + (int)(3.0 * sin((double)n / 90.0))),
        .baro = LG_BARO_ABSENT,     /* the BME280 is fitted but not read yet */
        .spd = (uint16_t)(step_m * 100),
        .hdg = (uint16_t)(fmod(bearing * 180.0 / M_PI + 360.0, 360.0) * 100),
        .sv = sv,
        .hd = (uint8_t)lround(hdop * 10),
        .bat = 0,                   /* the AXP2101 is not read yet — 0, not a guess */
        .tmp = LG_TEMP_ABSENT,
        .flags = lg_pack_flags(
            (uint8_t)(LG_FLAG_TIME_VALID | LG_FLAG_MOTION |
                      (fix_3d ? LG_FLAG_FIX_3D : 0) | (n == 1 ? LG_FLAG_TRIP_START : 0)),
            0, (uint16_t)(s_pos_newest + 1)),
    };
    log_and_broadcast(&r);
}

/* ── the session API ─────────────────────────────────────────────────────── */

esp_err_t lg_session_init(void)
{
    s_boot_us = now_us();
    s_lock = xSemaphoreCreateMutex();
    if (!s_lock) return ESP_ERR_NO_MEM;

    uint8_t mac[6] = { 0 };
    esp_read_mac(mac, ESP_MAC_WIFI_SOFTAP);
    s_device_id = (uint16_t)((mac[4] << 8) | mac[5]);

    /* The position ring in PSRAM: 900 records is a quarter hour at 1 Hz, and the
     * phone is the durable archive anyway (§2 — the node is a relay you might
     * leave behind). Internal RAM would work too, at the cost of the heap the
     * transports need for their own buffers. */
    s_pos = heap_caps_malloc((size_t)POS_RING * LG_RECORD_BYTES, MALLOC_CAP_SPIRAM);
    if (!s_pos) {
        s_pos = calloc(POS_RING, LG_RECORD_BYTES);
        ESP_LOGW(TAG, "position ring is in internal RAM — no PSRAM available");
    }
    if (!s_pos) return ESP_ERR_NO_MEM;

    ESP_LOGI(TAG, "proto 2 ready — mode %s, up to %d clients across all transports",
             s_gnss_live ? "gnss" : "synthetic", MAX_CLIENTS);
    return ESP_OK;
}

int lg_session_join(const lg_tx_t *tx, void *ctx)
{
    client_t *c = NULL;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (!s_clients[i].used) { c = &s_clients[i]; break; }
    }
    if (!c) {
        ESP_LOGW(TAG, "refusing a %s client: %d already attached",
                 tx ? tx->name : "?", MAX_CLIENTS);
        return -1;
    }

    uint8_t id = (uint8_t)(c - s_clients);
    memset(c, 0, sizeof(*c));
    c->used = true;
    c->id = id;
    c->tx = tx;
    c->ctx = ctx;
    strncpy(c->name, CALLSIGNS[id % (sizeof(CALLSIGNS) / sizeof(CALLSIGNS[0]))], LG_NAME_MAX);

    ESP_LOGI(TAG, "%s joined over %s — %u client%s", c->name, tx ? tx->name : "?",
             client_count(), client_count() == 1 ? "" : "s");

    send_hello(c);
    send_config(c);
    send_roster(NULL);
    send_stats(c);

    /* Deliberately no chat or position backfill here: the client states its
     * cursors next, and the node answers that. Pushing history as well is how the
     * mock ended up sending everything twice (2026-07-26). */
    for (int i = 0; i < MAX_CLIENTS; i++) {
        client_t *p = &s_clients[i];
        if (!p->used || p == c || !p->has_pos) continue;
        char safe[LG_NAME_MAX * 2 + 4];
        json_escape(p->name, safe, sizeof(safe));
        char buf[300];
        snprintf(buf, sizeof(buf),
            "{\"type\":\"peer\",\"id\":%u,\"name\":\"%s\",\"latE7\":%ld,\"lonE7\":%ld,"
            "\"hd\":%u,\"epoch\":%lu,\"ageS\":%lu,\"movedM\":0}",
            p->id, safe, (long)p->lat_e7, (long)p->lon_e7, p->hd,
            (unsigned long)p->epoch,
            (unsigned long)((now_us() - p->pos_at_us) / 1000000));
        send_text_c(c, buf);
    }
    return id;
}

void lg_session_leave(int id)
{
    client_t *c = client_by_id(id);
    if (!c) return;
    /* The transport is the one telling us, so there is nothing left to close. */
    drop_client(c, "left", false);
    send_roster(NULL);
}

void lg_session_frame(int id, const char *body)
{
    client_t *c = client_by_id(id);
    if (c) handle_frame(c, body);
}

/* A real fix, straight into the 32-byte record (§4). Absent sensors write
 * sentinels — the BME280 is fitted on this unit but not read yet, and the PMU
 * reports no cell on USB, so both say so rather than inventing a number. */
static void gnss_to_record(void)
{
    lg_fix_t f;
    gnss_get(&f);

    /* Only a *fresh* fix becomes a record. A stale one must not be re-logged
     * every second: the position would advance in sequence numbers while
     * standing still on the map, which reads as a live node that is not moving
     * rather than a node that has lost its fix. The staleness goes into `stats`
     * instead, where the app can render it as an age. */
    if (!gnss_fresh()) {
        static int64_t complained_at = 0;
        int64_t now = now_us();
        if (now - complained_at > 15 * 1000000LL) {
            complained_at = now;
            ESP_LOGW(TAG, "gnss fix is %ld s old — logging nothing rather than "
                          "repeating a position we can no longer see",
                     (long)gnss_age_s());
        }
        return;
    }

    lg_record_t r = {
        .epoch = f.epoch,
        .lat_e7 = f.lat_e7,
        .lon_e7 = f.lon_e7,
        .alt = f.alt_m,
        .baro = LG_BARO_ABSENT,
        .spd = f.speed_cms,
        .hdg = f.course_cdeg,
        .sv = f.sats,
        .hd = f.hdop_x10,
        .bat = 0,
        .tmp = LG_TEMP_ABSENT,
        .flags = lg_pack_flags(
            (uint8_t)((f.epoch ? LG_FLAG_TIME_VALID : 0) |
                      (f.fix_3d ? LG_FLAG_FIX_3D : 0) |
                      (f.speed_cms > 30 ? LG_FLAG_MOTION : 0) |
                      (s_pos_newest == 0 ? LG_FLAG_TRIP_START : 0)),
            0, (uint16_t)(s_pos_newest + 1)),
    };
    log_and_broadcast(&r);
}

void lg_session_tick(void)
{
    static uint32_t since_stats = 0;
    /* The moment the receiver has a fix, the records are real and `hello.mode`
     * says "gnss". Until then they are synthetic and it says "synthetic" — the
     * one field that keeps a demo track from being mistaken for a position. */
    s_gnss_live = gnss_live();
    if (s_gnss_live) gnss_to_record();
    else synth_tick();
    pump_backlog();
    if (++since_stats >= 5) {
        since_stats = 0;
        send_stats(NULL);
    }
}

uint8_t lg_session_clients(void)
{
    return client_count();
}

bool lg_session_gnss_live(void)
{
    return s_gnss_live;
}

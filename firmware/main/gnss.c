#include "gnss.h"
#include "board_pins.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "driver/uart.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "gnss";

#define UART_NUM      UART_NUM_1
#define RX_BUF        2048
#define LINE_MAX      128

/* Candidates, most likely first. LilyGO's own header for the T-Beam S3 Supreme
 * gives 9/8; the reversed pair is here because a swapped RX/TX is the single most
 * common way to wire a UART wrong, and the L76K and MAX-M10S variants disagree
 * about the default baud. Probing costs a second at boot and buys certainty. */
typedef struct { int rx, tx; } pins_t;
static const pins_t CANDIDATE_PINS[] = {
    { LG_GNSS_RX, LG_GNSS_TX },      /* verified on this unit, 2026-07-26 */
    { LG_GNSS_TX, LG_GNSS_RX },      /* swapped, the usual wiring mistake */
};
static const int CANDIDATE_BAUD[] = { LG_GNSS_BAUD, 38400, 115200 };

static lg_fix_t s_fix;
static bool s_live = false;
static uint32_t s_sentences = 0;
static uint32_t s_fixes = 0;

/* ── NMEA ────────────────────────────────────────────────────────────────── */

/* "ddmm.mmmm" → degrees ×1e7. The minutes are the whole trick: this is the one
 * conversion that silently produces a position 40 km out when it is wrong, so it
 * is written out rather than compressed. */
static int32_t nmea_coord(const char *field, const char *hemi)
{
    if (!field || !*field) return 0;
    double raw = atof(field);
    int deg = (int)(raw / 100.0);
    double minutes = raw - (deg * 100.0);
    double value = deg + minutes / 60.0;
    if (hemi && (*hemi == 'S' || *hemi == 'W')) value = -value;
    return (int32_t)(value * 1e7);
}

/* Split on commas in place. NMEA fields are frequently empty ("...,,,"), and an
 * empty field means "not known", never zero — hence pointers, not values. */
static int split(char *line, char *out[], int max)
{
    int n = 0;
    char *p = line;
    out[n++] = p;
    while (*p && n < max) {
        if (*p == ',') { *p = '\0'; out[n++] = p + 1; }
        p++;
    }
    return n;
}

/* $--GGA — position, fix quality, satellites, HDOP, altitude. */
static void parse_gga(char *line)
{
    char *f[16];
    int n = split(line, f, 16);
    if (n < 10) return;

    const int quality = atoi(f[6]);
    if (quality <= 0) {
        /* Talking but no fix. Recorded rather than ignored: "the receiver is
         * alive and has nothing yet" is a different state from "no receiver",
         * and a cold start legitimately takes a minute. */
        s_fix.valid = false;
        return;
    }

    s_fix.lat_e7 = nmea_coord(f[2], f[3]);
    s_fix.lon_e7 = nmea_coord(f[4], f[5]);
    s_fix.sats = (uint8_t)atoi(f[7]);
    double hdop = atof(f[8]);
    s_fix.hdop_x10 = (uint8_t)(hdop <= 0 ? 0 : (hdop > 25.5 ? 255 : hdop * 10));
    s_fix.alt_m = (int16_t)atof(f[9]);
    s_fix.valid = true;
    s_fix.at_us = esp_timer_get_time();
    s_fixes++;
    if (!s_live) {
        ESP_LOGI(TAG, "first fix: %ld.%07ld, %ld.%07ld · %u sats · hdop %.1f",
                 (long)(s_fix.lat_e7 / 10000000), (long)labs(s_fix.lat_e7 % 10000000),
                 (long)(s_fix.lon_e7 / 10000000), (long)labs(s_fix.lon_e7 % 10000000),
                 s_fix.sats, s_fix.hdop_x10 / 10.0);
        s_live = true;
    }
}

/* $--RMC — validity, speed, course, and the date, which GGA does not carry. */
static void parse_rmc(char *line)
{
    char *f[16];
    int n = split(line, f, 16);
    if (n < 10) return;
    if (f[2][0] != 'A') return;           /* V = warning, i.e. no fix */

    /* knots → cm/s, and degrees → centidegrees. */
    s_fix.speed_cms = (uint16_t)(atof(f[7]) * 51.444);
    s_fix.course_cdeg = (uint16_t)(atof(f[8]) * 100.0);

    /* hhmmss.ss + ddmmyy → epoch. Done by hand because the alternative is
     * pulling in a time library for two sscanf calls, and because the *absence*
     * of a date has to stay visible: without one, `time_valid` stays clear and
     * the client repairs the timestamp (§4). */
    const char *t = f[1], *d = f[9];
    if (strlen(t) >= 6 && strlen(d) >= 6) {
        struct tm tm = { 0 };
        tm.tm_hour = (t[0] - '0') * 10 + (t[1] - '0');
        tm.tm_min  = (t[2] - '0') * 10 + (t[3] - '0');
        tm.tm_sec  = (t[4] - '0') * 10 + (t[5] - '0');
        tm.tm_mday = (d[0] - '0') * 10 + (d[1] - '0');
        tm.tm_mon  = (d[2] - '0') * 10 + (d[3] - '0') - 1;
        tm.tm_year = 100 + (d[4] - '0') * 10 + (d[5] - '0');   /* 20xx */
        time_t e = mktime(&tm);
        if (e > 0) s_fix.epoch = (uint32_t)e;
    }
}

static void parse_gsa(char *line)
{
    char *f[20];
    int n = split(line, f, 20);
    if (n < 3) return;
    /* Field 2: 1 = no fix, 2 = 2D, 3 = 3D. A 2D fix has a fictional altitude and
     * the record's flag says so, which is what stops the map drawing it as real. */
    s_fix.fix_3d = (atoi(f[2]) == 3);
}

static void handle_line(char *line)
{
    if (line[0] != '$' || strlen(line) < 7) return;
    s_sentences++;
    /* Talker-agnostic: GP, GN, GL, GA all appear depending on the constellation
     * and the module, and matching only "$GP" is why half of a working receiver
     * looks broken. */
    const char *type = line + 3;
    if (!strncmp(type, "GGA", 3)) parse_gga(line);
    else if (!strncmp(type, "RMC", 3)) parse_rmc(line);
    else if (!strncmp(type, "GSA", 3)) parse_gsa(line);
}

/* ── the probe ───────────────────────────────────────────────────────────── */

static bool looks_like_gnss(const uint8_t *buf, size_t len)
{
    for (size_t i = 0; i + 6 < len; i++) {
        if (buf[i] == '$' && (buf[i + 1] == 'G' || buf[i + 1] == 'P')) return true;
        if (buf[i] == 0xb5 && buf[i + 1] == 0x62) return true;   /* UBX preamble */
    }
    return false;
}

static bool try_combo(int rx, int tx, int baud)
{
    uart_config_t cfg = {
        .baud_rate = baud,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    if (uart_is_driver_installed(UART_NUM)) uart_driver_delete(UART_NUM);
    if (uart_driver_install(UART_NUM, RX_BUF, 0, 0, NULL, 0) != ESP_OK) return false;
    if (uart_param_config(UART_NUM, &cfg) != ESP_OK) return false;
    if (uart_set_pin(UART_NUM, tx, rx, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE) != ESP_OK) return false;

    uart_flush_input(UART_NUM);
    uint8_t buf[256];
    /* 1.2 s: a 1 Hz receiver emits its burst once a second, so anything shorter
     * can miss a working combination entirely. */
    int len = uart_read_bytes(UART_NUM, buf, sizeof(buf), pdMS_TO_TICKS(1200));
    if (len > 0 && looks_like_gnss(buf, (size_t)len)) {
        ESP_LOGI(TAG, "answered on rx=%d tx=%d at %d baud (%d bytes)", rx, tx, baud, len);
        return true;
    }
    if (len > 0) {
        ESP_LOGD(TAG, "rx=%d tx=%d %d baud: %d bytes, not NMEA", rx, tx, baud, len);
    }
    return false;
}

static void gnss_task(void *arg)
{
    char line[LINE_MAX];
    size_t at = 0;
    uint8_t byte;

    while (true) {
        int n = uart_read_bytes(UART_NUM, &byte, 1, pdMS_TO_TICKS(1000));
        if (n <= 0) continue;
        if (byte == '\n' || byte == '\r') {
            if (at > 0) {
                line[at] = '\0';
                handle_line(line);
                at = 0;
            }
        } else if (at + 1 < sizeof(line)) {
            line[at++] = (char)byte;
        } else {
            at = 0;   /* overlong garbage: drop it rather than truncate into a parse */
        }
    }
}

bool gnss_start(void)
{
    memset(&s_fix, 0, sizeof(s_fix));

    for (size_t p = 0; p < sizeof(CANDIDATE_PINS) / sizeof(CANDIDATE_PINS[0]); p++) {
        for (size_t b = 0; b < sizeof(CANDIDATE_BAUD) / sizeof(CANDIDATE_BAUD[0]); b++) {
            if (try_combo(CANDIDATE_PINS[p].rx, CANDIDATE_PINS[p].tx, CANDIDATE_BAUD[b])) {
                xTaskCreate(gnss_task, "lg_gnss", 4096, NULL, 5, NULL);
                return true;
            }
        }
    }

    ESP_LOGW(TAG, "nothing that looks like NMEA on any candidate pin/baud. Either the "
                  "GNSS rail is still off, or board_pins.h has the wrong pins — those "
                  "are the two possibilities, and the rail is the one to check first");
    if (uart_is_driver_installed(UART_NUM)) uart_driver_delete(UART_NUM);
    return false;
}

void gnss_get(lg_fix_t *out)
{
    *out = s_fix;
}

bool gnss_live(void)
{
    return s_live;
}

void gnss_counters(uint32_t *sentences, uint32_t *fixes)
{
    if (sentences) *sentences = s_sentences;
    if (fixes) *fixes = s_fixes;
}

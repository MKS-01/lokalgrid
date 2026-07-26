/* Lokalgrid — the WebSocket transport.
 *
 * Thin on purpose: the protocol lives in session.c, and this file only knows how
 * to get bytes onto an `esp_http_server` WebSocket and hand incoming ones back.
 * The BLE transport is the same shape (ble_gatt.c), which is what keeps one
 * `proto 2` from quietly becoming two.
 */
#include "net_ws.h"

#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "esp_http_server.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "session.h"

static const char *TAG = "ws";

#define MAX_SOCKETS 10        /* 9 clients + one so a refusal can be delivered */

static httpd_handle_t s_server = NULL;

/* fd → session client id. The session does not care about sockets and httpd does
 * not care about clients, so the mapping lives here, where both are visible. */
static int s_id_of_fd[CONFIG_LWIP_MAX_SOCKETS];

static int id_of(int fd)
{
    if (fd < 0 || fd >= CONFIG_LWIP_MAX_SOCKETS) return -1;
    return s_id_of_fd[fd] - 1;      /* stored +1 so 0 means "not a client" */
}

static void set_id(int fd, int id)
{
    if (fd >= 0 && fd < CONFIG_LWIP_MAX_SOCKETS) s_id_of_fd[fd] = id + 1;
}

static esp_err_t tx_text(void *ctx, const char *text)
{
    httpd_ws_frame_t f = {
        .final = true,
        .type = HTTPD_WS_TYPE_TEXT,
        .payload = (uint8_t *)text,
        .len = strlen(text),
    };
    return httpd_ws_send_frame_async(s_server, (int)(intptr_t)ctx, &f);
}

static esp_err_t tx_bin(void *ctx, const uint8_t *data, size_t len)
{
    httpd_ws_frame_t f = {
        .final = true,
        .type = HTTPD_WS_TYPE_BINARY,
        .payload = (uint8_t *)data,
        .len = len,
    };
    return httpd_ws_send_frame_async(s_server, (int)(intptr_t)ctx, &f);
}

static const lg_tx_t WS_TX = {
    .name = "wifi",
    .send_text = tx_text,
    .send_bin = tx_bin,
};

static esp_err_t ws_handler(httpd_req_t *req)
{
    int fd = httpd_req_to_sockfd(req);

    if (req->method == HTTP_GET) {
        /* The handshake. A client past the cap is refused rather than accepted
         * and then starved (§3, admission control). */
        int id = lg_session_join(&WS_TX, (void *)(intptr_t)fd);
        if (id < 0) {
            ESP_LOGW(TAG, "refusing fd %d — the node is full", fd);
            return ESP_FAIL;
        }
        set_id(fd, id);
        return ESP_OK;
    }

    httpd_ws_frame_t frame = { 0 };
    esp_err_t err = httpd_ws_recv_frame(req, &frame, 0);
    if (err != ESP_OK) return err;

    if (frame.type == HTTPD_WS_TYPE_CLOSE) {
        lg_session_leave(id_of(fd));
        set_id(fd, -1);
        return ESP_OK;
    }
    if (frame.len == 0 || frame.type != HTTPD_WS_TYPE_TEXT) {
        /* Clients never push binary: records come from the node (§4). */
        return ESP_OK;
    }
    if (frame.len > 1024) {
        ESP_LOGW(TAG, "control frame of %u bytes ignored", (unsigned)frame.len);
        return ESP_OK;
    }

    uint8_t *body = calloc(1, frame.len + 1);
    if (!body) return ESP_ERR_NO_MEM;
    frame.payload = body;
    err = httpd_ws_recv_frame(req, &frame, frame.len);
    if (err == ESP_OK) {
        lg_session_frame(id_of(fd), (const char *)body);
    }
    free(body);
    return err;
}

/* httpd closes sockets without telling the handler, so the roster is reconciled
 * here — otherwise a client that walked away lingers on everyone's Clients tab. */
static void on_close(httpd_handle_t hd, int sockfd)
{
    lg_session_leave(id_of(sockfd));
    set_id(sockfd, -1);
    close(sockfd);
}

/* The clock for the whole session. It lives with a transport rather than in
 * session.c so the session stays a pure protocol object with no task of its own. */
static void tick_task(void *arg)
{
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        /* Ticks even with nobody attached: the node logs regardless, so a client
         * that arrives has history to resume from. That is the whole point of
         * something staying awake and remembering (§2). */
        lg_session_tick();
    }
}

esp_err_t net_ws_start(void)
{
    for (int i = 0; i < CONFIG_LWIP_MAX_SOCKETS; i++) s_id_of_fd[i] = 0;

    httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
    cfg.max_open_sockets = MAX_SOCKETS;
    cfg.lru_purge_enable = true;
    cfg.close_fn = on_close;
    cfg.stack_size = 6144;

    esp_err_t err = httpd_start(&s_server, &cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "http server would not start: %s", esp_err_to_name(err));
        return err;
    }

    static const httpd_uri_t ws_uri = {
        .uri = "/ws",
        .method = HTTP_GET,
        .handler = ws_handler,
        .is_websocket = true,
    };
    ESP_ERROR_CHECK(httpd_register_uri_handler(s_server, &ws_uri));

    xTaskCreate(tick_task, "lg_tick", 4096, NULL, 4, NULL);

    ESP_LOGI(TAG, "proto 2 on ws://192.168.4.1/ws");
    return ESP_OK;
}

uint8_t net_ws_clients(void)
{
    return lg_session_clients();
}

bool net_ws_gnss_live(void)
{
    return lg_session_gnss_live();
}

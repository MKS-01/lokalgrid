idf_component_register(
    SRCS
        "main.c"
        "board.c"
        "wifi_ap.c"
        "ble_adv.c"
    INCLUDE_DIRS "."
    REQUIRES
        driver          # i2c_master — the new driver, not the legacy i2c
        esp_event
        esp_netif
        esp_timer
        esp_wifi
        nvs_flash
        bt              # NimBLE
)

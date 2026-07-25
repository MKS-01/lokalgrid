# firmware — the node

ESP-IDF v5.3.1 + CMake, target `esp32s3` (T-Beam Supreme / S3-Core v3.0,
868/915 MHz SX1262 — in India keep the radio inside **865–867 MHz**, PROJECT.md
section 1).

## One-time setup

Everything lives under `Desktop/C0D3`, toolchain included — nothing lands in the
home directory:

```sh
cd ~/Desktop/C0D3
git clone -b v5.3.1 --depth 1 --recursive --shallow-submodules \
    https://github.com/espressif/esp-idf.git
export IDF_TOOLS_PATH=~/Desktop/C0D3/esp-tools    # ~2 GB of compilers go here
cd esp-idf && ./install.sh esp32s3
```

Then in every new shell:

```sh
export IDF_TOOLS_PATH=~/Desktop/C0D3/esp-tools
source ~/Desktop/C0D3/esp-idf/export.sh
```

`IDF_TOOLS_PATH` must be set **before** `export.sh`, or it goes looking in
`~/.espressif` and reports the toolchain as missing.

## Build / flash / logs

```sh
cd ~/Desktop/C0D3/lokalgrid/firmware
idf.py set-target esp32s3     # once; reads sdkconfig.defaults
idf.py build
idf.py flash monitor          # over the USB-C cable; Ctrl-] exits monitor
```

The board appears as `/dev/cu.usbmodem*` when the cable is in the USB-C port on
the **S3-Core module** — the built-in USB-JTAG, which also carries logs and the
debugger. If nothing appears, try the other port and swap the cable: a
charge-only cable looks exactly like a dead board.

## What a good first boot says

Verified on this unit, 2026-07-26:

```
I lokalgrid: lokalgrid boot
I board: scanning i2c0 (sda=17 scl=18)
I board:   i2c0 0x1c  QMC6310 magnetometer (parked)
I board:   i2c0 0x3c  OLED 128x64
I board:   i2c0 0x77  BME280 baro/temp/humidity
I board: scanning i2c1 (sda=42 scl=41)
I board:   i2c1 0x34  AXP2101 PMU
I board:   i2c1 0x51  PCF8563 RTC
I board: 5 devices across both buses
I board: no QMI8658: the motion gate falls back to GNSS speed alone
I oled: display up on i2c0 at 0x3c, 128x64, 21 chars per line
I lokalgrid: littlefs mounted: 8 KiB used of 2816 KiB
I ble: advertising as "lokalgrid" — visible in nRF Connect
I wifi: AP up: ssid "lokalgrid" ch 6, up to 10 stations
I lokalgrid: ap up · 0 stations · ble advertising · heap 8453616
```

The display shows the same facts for someone standing over the node with no
phone attached — ssid, phones connected, BLE state, uptime. No battery line
until the AXP2101 is actually read: a made-up percentage is worse than none.

**If the I²C scan finds nothing, suspect `board_pins.h` before the chips.** Both
pin pairs are now verified on this unit, but that is exactly how the second bus
was found: bus 0 alone reported no PMU on a board that runs off a battery.

**Ports renumber.** The board enumerates as `/dev/cu.usbmodem101` in download
mode and may come back as `usbmodem1101` after a reset, which makes a hardcoded
`-p` fail with "Failed to connect". Let idf.py pick, or:

```sh
idf.py -p "$(ls -t /dev/cu.usbmodem* | head -1)" flash
```

**Getting into download mode.** The factory firmware owns the USB port, so
esptool's automatic reset cannot reach the ROM: unplug the cable, hold **BOOT**,
plug in, release. The device changes from `LilyGo TBeam_S3_Core` to
`USB JTAG_serial debug unit`. Once this firmware is on the board its console is
the built-in USB-Serial-JTAG, and later flashes auto-reset without the button.

The AP is deliberately not permanent: down after 10 minutes if nobody ever
connects, or 5 minutes after the last phone leaves. Both limits are compiled in
(`wifi_ap.c`) because ~100 mA left on turns a week of runtime into a day, and a
config toggle eventually gets left wrong. Until BLE can bring it back
(Phase 04), reset the board.

The littlefs `storage` partition holds track logs + manifest; it formats itself
on first mount (`format_if_mount_failed`).

## Debugger — set this up before you need it (PROJECT.md section 5)

The S3 has USB-JTAG built in; same cable, no probe:

```sh
idf.py openocd          # terminal 1
idf.py gdb              # terminal 2 — then: b app_main / c
```

## Where Phase 03 is

| Step | State |
|---|---|
| Toolchain + first `idf.py build` | done |
| Flashed and booting | done — 2026-07-26 |
| LittleFS mounted | done (formatted over the factory image on first mount) |
| I²C inventory of this variant's chips | done — 5 chips across two buses, no IMU |
| SoftAP `lokalgrid` + NimBLE advertising | done — AP at 192.168.4.1, advertising |
| OLED showing real status | done — ssid, phones, BLE, uptime |
| Breakpoint in `app_main` over USB-JTAG | not yet |
| AXP2101: battery %, rails for GNSS/LoRa | next — the IMU may be behind a rail |
| WebSocket serving `proto 2` to the Android app | next |
| GNSS on UART1 → real 32-byte records | next |
| BLE GATT sync path (the un-mockable one, §6) | after that |
| SX1262 | Phase 06, and not before an antenna flag exists |

## Layout

```
CMakeLists.txt        project shell
partitions.csv        8 MB table (PROJECT.md section 5) — littlefs 'storage' partition
sdkconfig.defaults    pinned config; sdkconfig itself is gitignored
main/
  main.c              boot order, the heartbeat, and the status page
  board_pins.h        pin map — both I²C buses, verified on this unit
  board.c/.h          I²C scan — what this variant actually has
  oled.c/.h           SH1106/SSD1306, 6 lines of 5x7 text, hand-written
  wifi_ap.c/.h        SoftAP + the firmware-enforced idle timeout
  ble_adv.c/.h        NimBLE advertising (no GATT service yet, on purpose)
```

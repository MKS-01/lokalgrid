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

```
I lokalgrid: lokalgrid boot
I board: scanning i2c0 (sda=17 scl=18)
I board:   0x34  AXP2101 PMU
I board:   0x3c  OLED 128x64
I board:   0x51  PCF8563 RTC
I board:   0x6b  QMI8658 IMU
I board: 4 devices on i2c0
I lokalgrid: littlefs mounted: 0 KiB used of 2816 KiB
I ble: advertising as "lokalgrid" — visible in nRF Connect
I wifi: AP up: ssid "lokalgrid" ch 6, up to 10 stations
I lokalgrid: ap up · 0 stations · ble advertising · heap ...
```

**If the I²C scan finds nothing, suspect `board_pins.h` before the chips.** Those
pin numbers come from LilyGO's `utilities.h` and are unverified against this
unit; the scan exists to prove or disprove them, which is why it names every
address that answers rather than just failing.

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
| Toolchain + first `idf.py build` | in progress |
| Breakpoint in `app_main` over USB-JTAG | not yet |
| LittleFS mounted | code in place, unrun |
| I²C inventory of this variant's chips | code in place, unrun |
| SoftAP `lokalgrid` + NimBLE advertising | code in place, unrun |
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
  main.c              boot order and the heartbeat
  board_pins.h        pin map, with its provenance and its warning
  board.c/.h          I²C scan — what this variant actually has
  wifi_ap.c/.h        SoftAP + the firmware-enforced idle timeout
  ble_adv.c/.h        NimBLE advertising (no GATT service yet, on purpose)
```

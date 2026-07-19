# firmware — the node

ESP-IDF v5.x + CMake, target `esp32s3` (T-Beam Supreme / S3-Core v3.0).

## One-time setup (no ESP-IDF on this machine yet)

```sh
mkdir -p ~/esp && cd ~/esp
git clone -b v5.3.1 --recursive https://github.com/espressif/esp-idf.git
cd esp-idf && ./install.sh esp32s3
```

Then in every new shell (or alias it as `get_idf`):

```sh
source ~/esp/esp-idf/export.sh
```

## Build / flash / logs

```sh
cd firmware
idf.py set-target esp32s3     # once; reads sdkconfig.defaults
idf.py build
idf.py flash monitor          # over the USB-C cable; Ctrl-] exits monitor
```

The littlefs `storage` partition holds track logs + manifest; it formats
itself on first mount (`format_if_mount_failed`).

## Debugger — set up before you need it (PROJECT.md section 5)

The S3 has USB-JTAG built in; same cable, no probe:

```sh
idf.py openocd          # terminal 1
idf.py gdb              # terminal 2 — then: b app_main / c
```

Phase 01 is done when a breakpoint in `app_main` hits, LittleFS mounts,
and the phone sees `lokalgrid` in its WiFi list and in nRF Connect
(SoftAP + NimBLE advertising).

## Layout

```
CMakeLists.txt        project shell
partitions.csv        8 MB table (PROJECT.md section 5) — littlefs 'storage' partition
sdkconfig.defaults    pinned config; sdkconfig itself is gitignored
main/                 app code; sole component for now
```

# Build log

## 2026-07-20 — named, structured, pushed (Phase 01 started)

**Tried:** Concept rethink against the 2026 landscape → shared field node reconfirmed, chat scoped to one text-only channel. Project named **Lokalgrid** (after ~40 rejected candidates; GitHub-unique). Docs merged into one dark-mode `lokalgrid-master-plan.html` (§14 holds the rethink record). Repo pushed to github.com/MKS-01/lokalgrid. Project structure laid: `firmware/` (CMake shell, section-5 partitions.csv + sdkconfig.defaults, minimal `app_main` that mounts LittleFS, fsroot/index.html baked into the image via joltwallet/littlefs), placeholder READMEs for `web/`, `mock-node/`, `schema/` with their phase gates written down.

**Surprised:** Every plain-English project name in this space is taken on GitHub (localnode: 51 repos, waybeam: 13); the German spelling trick (lokal-) cleared it instantly. Also IntelliJ kept resurrecting the old `unamed-gps/` dir by writing workspace.xml to the dead path — close the IDE window before renaming a project folder.

**Later same session — PWA removed:** Client pivoted to a **native Android app** (Kotlin/Compose/MapLibre Android/Room, foreground-service BLE sync). Rationale recorded as a superseding decision in PROJECT.md §2: background sync with the screen off beats zero-install for a personal build. `web/` and `firmware/fsroot/` deleted, `android/` placeholder added, mock-node repurposed for app development, §6 rewritten (two languages now: C + Kotlin), Phase 01 milestone changed from "phone loads a page" to "phone sees SoftAP SSID + BLE advertising in nRF Connect".

**Next:** ESP-IDF not installed yet. Run the one-time setup in `firmware/README.md` (clone v5.3.1, `./install.sh esp32s3`), then `idf.py set-target esp32s3 && idf.py build flash monitor` — expect "littlefs mounted" and the heartbeat log. Then USB-JTAG breakpoint in `app_main`, then SoftAP + esp_http_server serving fsroot. Board still needs the silkscreen band check (433 vs 868) before any antenna purchase — does not block this.

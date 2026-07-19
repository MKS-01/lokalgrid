# Lokalgrid — shared field node, project handoff

> **Project name: Lokalgrid** *(2026-07-20)* — *lokal* (German: local) + grid: your own tiny infrastructure grid in the field, no carrier, no internet. Verified unique on GitHub at naming time (0 repos). Repo/dir name: `lokalgrid`.

> Context file for resuming this project from a CLI agent session.
> Drop this at the repo root. Companion doc: `lokalgrid-master-plan.html` (same content, rendered, with wireframes and diagrams, plus the §14 concept-rethink record).

---

## 0. How to use this file

This is a **decision record**, not a tutorial. It exists so that a fresh session — human or agent — can pick up without re-deriving three weeks of conversation.

If you are an agent reading this: sections 1–6 are binding constraints. Section 2 lists things that were **explicitly rejected** — do not re-propose them without reading the rationale. Section 9 is where work resumes.

**Project status:** planning complete, no code written yet. Next action is Phase 01 (section 7).

---

## 1. Hardware

### Confirmed

**LilyGO T-Beam Supreme**, purchased from robu.in (~₹6018).
The silkscreen reads **T-Beam S3-Core v3.0** — this is expected. The Supreme *is* the S3-Core module seated in a baseboard. Do not treat these as different boards.

| Subsystem | Part | Bus |
|---|---|---|
| MCU | ESP32-S3FN8, dual core, 8 MB flash, 8 MB octal PSRAM | — |
| LoRa | SX1262 | SPI2 (dedicated) |
| GNSS | L76K (some units ship u-blox MAX-M10S) | UART1 |
| PMU | AXP2101, with coulomb counter | I²C0 |
| IMU | QMI8658 | I²C0 |
| Magnetometer | QMC6310 | I²C0 |
| Baro/temp/humidity | BME280 (absent on some variants) | I²C0 |
| RTC | PCF8563 | I²C0 |
| Display | 1.3" OLED 128×64 | I²C0 |
| Storage | microSD | SPI3 (dedicated) |
| Power | 18650 holder on baseboard | — |

### Open question — RESOLVE BEFORE BUYING AN ANTENNA

**LoRa band is unconfirmed.** The robu.in listing title says 433 MHz. The user believes the module is 868/915. These cannot both be right.

To resolve:
- Read the silkscreen beside the SMA connector — LilyGO marks it `433M`, `868M`, or `915M`
- Or compare the supplied whip length: an 868/915 antenna is roughly half the length of a 433 one

Why it matters:
- **433 MHz** — delicensed in India (433.05–434.79). Outside the Meshtastic `IN` region, but *ideal* for TinyGS satellite reception, since most LoRa satellites are on 433.
- **868 MHz** — must stay within 865–867, which is India's delicensed ISM allocation and the Meshtastic `IN` region. Do not use the full EU 868 range.

Either way: design to a **1% hourly duty cycle**, enforced as a hard limit in firmware, not as a config setting.

### Additions needed

| Part | ~Cost | Note |
|---|---|---|
| Band-matched SMA whip | ₹200 | Must match silkscreen. Wrong band = bad SWR = slow PA damage. |
| Active GNSS antenna, u.FL | ₹300 | Biggest single improvement to fix quality and TTFF. Do before any filtering work. |
| Flat-top 18650, 3500 mAh | ₹600 | Holder is built to original 18650 spec — protected/button-top cells (~70 mm) will not fit. |

### Hardware safety rules

1. **Never TX without an antenna attached.** Every hardware-damage path on this board runs through the SX1262 PA.
2. **Never burn the eFuse.** Irreversible. Not needed for this project (see section 2).
3. Do not unseat the core module from the baseboard while powered.
4. 3.3 V max on any GPIO.

---

## 2. Decision log

### The project pivoted three times. Current state is the fourth.

1. **BLE GPS logger** — device logs, phone syncs over custom GATT. Good, but single-user.
2. **Advanced asset tracker** — added geofencing, LoRa beacon, tamper, encryption. Rejected as over-engineered: features came from the datasheet, not from a need.
3. **Meshtastic-like, but one node serving multiple phones** — the actual novel idea.
4. **Scoped as a hobby build** — current. See "hobby constraints" below.

### DECIDED

| Decision | Rationale |
|---|---|
| **Product = shared field node**, not hidden asset tracker | The two want opposite designs (months of battery + stealth vs. active use + multi-client). Cannot be both. |
| **Max 3 clients** | Airtime arbitration is interesting at 3; admission control complexity explodes beyond it, with no learning gain. |
| **Native Android app (Kotlin) is the client** *(2026-07-20, supersedes "PWA is primary")* | Background BLE sync via a foreground service — positions and chat backfilling with the screen off — is the feature a browser can never provide, and it matters more here than the zero-install pitch. The install barrier is accepted: this is a personal build for a known group, not a product for strangers. The PWA plan (Vite/Svelte/Workbox, node-served assets) is removed; a browser client may return later as an optional extra, not as the primary. |
| **WiFi SoftAP on demand, BLE always-on underneath** | AP draws ~100 mA vs BLE ~2 mA. AP-by-default turns a week of runtime into a day. |
| **AP idle timeout enforced in firmware** | Not a setting. A config toggle will eventually be left wrong. |
| **ESP-IDF v5.x + CMake directly** | Not Arduino (hides sleep/NimBLE/power APIs). Not PlatformIO (IDF support lags on S3). |
| **Hand-write the codec first, generate later** | Codegen is introduced in Phase 05, *after* drift has caused a real bug. Understanding beats compliance. |
| **Uncertainty is rendered, always** | Error ellipses from HDOP, dashed interpolated segments, ages on stale positions. No consumer tracker does this; it is the product's honesty and its identity. |
| **Chat = one shared channel, text only** *(2026-07-20)* | No DMs, no channels, no media at launch. One room mirrors the product (one node, one group in the field) and keeps Phase 03 small. Revisit only after Phase 03 is alive. |
| **Concept reconfirmed after 2026-07-20 rethink** | Alternatives surveyed against the 2026 landscape (MeshCore, Reticulum, TinyGS, sonde/APRS firmware) — see `lokalgrid-master-plan.html` §14. Shared field node stands: one node, multiple phones, everyone on one map. (The client later moved from PWA to native Android — see the superseding row above.) |

### REJECTED — do not re-propose

| Rejected | Why |
|---|---|
| eFuse key burn / AES-GCM log encryption | Irreversible, solves a threat model that does not exist for a hobby build. |
| Tamper detection (reed switch, free-fall) | Needs a real theft scenario to design against. There isn't one. |
| Solar charging | Adds a variable while fixed things are still being debugged. |
| OTA update | Nobody depends on this device. Flash over USB. |
| IP65 enclosure / field hardening | Product work, not learning work. |
| microSD cold archive | Internal LittleFS holds ~a week. Deferred, not needed. |
| Magnetometer heading | Course-over-ground from GNSS is sufficient. |
| PostGIS / cloud backend / fleet mode | No server. The node is the authority. |
| Copying Meshtastic firmware code | GPL. Reading for architecture ideas is fine; copying binds the licence. Writing from the protocol spec is cleaner and more instructive. |

### Hobby constraints (these override everything else)

- Success = staying interesting, not shipping.
- **Phase 03 is a complete project.** Stopping there is a success, not an abandonment.
- Reorder always for earliest visible milestone.
- If an adjacent project sounds better one Saturday, build that instead.

---

## 3. Architecture

### Transports — pick per job

| Transport | Throughput | Node draw | Range | Job |
|---|---|---|---|---|
| BLE | 15–25 KB/s (2M PHY) | ~2 mA avg | ~20 m | Always-on presence, status, alerts. The idle state. |
| WiFi SoftAP | 2–8 Mbit/s | ~100 mA | ~30 m | On demand. Bulk history sync and live WebSocket stream to the Android app, 3 clients at once. |
| LoRa | ~1 kbit/s | 110 mA in TX | km | The link out. Positions + short messages. Duty-cycle bound. |

**Known Android behaviours to design around:**
- Android flags an AP with no internet route and may silently fall back to mobile data mid-session. The app must bind its socket to the WiFi network explicitly (`ConnectivityManager.bindProcessToNetwork` / per-socket `Network.bindSocket`); BLE stays up underneath; client resumes from its cursor.
- Target device is a **Samsung Galaxy S25**. One UI "Deep sleeping apps" and Adaptive Battery kill background work silently — the app needs an onboarding screen that walks through exempting it, and a foreground service (`connectedDevice` type) for background BLE sync.

### Multi-client arbitration — the core engineering

One radio, three clients. This is what does not exist in Meshtastic's 1:1 model.

**Priority lanes:**
```
0  emergency  → pre-empts everything, ignores fairness
1  position   → aggregated, decimated, one packet per window
2  message    → deficit round-robin across clients
3  bulk       → only when the budget is otherwise idle
```

**Per-client budget:** each client gets 1/N of airtime remaining after lanes 0 and 1. Unused allocation **decays rather than banks** — an absent client must not hoard then flood.

**Admission control:** reject at enqueue with a renderable reason. Never silently drop. UI shows "queued, 40 s — ravi ahead of you".

**Client state:**
```c
struct client {
  uint8_t  id;              // 0..2, assigned at pairing
  uint32_t msg_cursor;      // last message seq delivered
  uint32_t pos_cursor;      // last position seq delivered
  uint32_t airtime_credit;  // deficit round-robin, in ms
  uint8_t  key[32];         // per-client, from pairing ECDH
  transport_t active;       // BLE | WIFI | NONE
};
```

**Backlog:** a client returning after an hour needs ~200 messages and must not block the two that are live. Serve backlog in bounded chunks interleaved with live traffic.

**Backpressure:** 3 phones at 1 Hz saturates LoRa instantly. Node aggregates into one packet per window, decimates by *distance* not time (50 m default).

**Degradation:** 4th client → refuse with a reason. Battery <15% → drop AP, keep BLE + LoRa, tell everyone why.

### Ownership rule

> The **node** is authoritative about *what exists*. Each **client** is authoritative about *what it has received* (its cursor). Neither infers the other's state — it asks.

Almost every duplicate/lost-record bug in this class of system violates this.

### FreeRTOS task decomposition

| Task | Prio | Core | Responsibility |
|---|---|---|---|
| `gnss_task` | 5 | 0 | UART reads, NMEA parse, emit `FixEvent`. Never touches storage. |
| `policy_task` | 6 | 0 | Consumes fixes + IMU/GNSS-speed motion. Decides log, geofence, beacon, sleep. |
| `storage_task` | 4 | 1 | **Sole owner** of LittleFS and SD. Serialises all writes. |
| `net_task` | 4 | 1 | HTTP server, WebSocket per client, NimBLE GATT. |
| `sched_task` | 5 | 1 | Airtime scheduler, lanes, per-client credit, admission. |
| `lora_task` | 3 | 1 | Beacon TX, duty-cycle enforcement, DIO1 IRQ. |
| `power_task` | 2 | 0 | AXP2101 polling, coulomb accounting, sleep entry. |

**Single-owner rule:** exactly one task touches the filesystem. All others request via queue. Eliminates the corruption class where a transfer races a log write, and makes sleep entry trivially safe.

---

## 4. Wire formats

### Track record — 32 bytes, fixed width

Fixed width is the whole trick: `offset = index * 32`. Seeking is arithmetic, not parsing.

| Offset | Field | Type | Note |
|---|---|---|---|
| 0 | epoch | u32 | GPS time when `time_valid` |
| 4 | lat_e7 | i32 | ×10⁷, ≈1.1 cm |
| 8 | lon_e7 | i32 | ×10⁷ |
| 12 | alt | i16 | metres, GNSS |
| 14 | baro | i16 | `0x8000` if no BME280 |
| 16 | spd | u16 | cm/s |
| 18 | hdg | u16 | centidegrees |
| 20 | sv | u8 | satellite count |
| 21 | hd | u8 | HDOP ×10 |
| 22 | bat | u8 | battery % |
| 23 | tmp | i8 | °C, `0x80` if absent |
| 24 | flags | u32 | see below |
| 28 | crc32 | u32 | trailer |

Little-endian throughout. **The layout must not change per build** — absent sensors write sentinels, they do not shrink the record.

**Flags word:**

| Bits | Field | Purpose |
|---|---|---|
| 0 | time_valid | Clear when logged without a fix — client must repair the timestamp |
| 1 | fix_3d | 2D fix has fictional altitude |
| 2 | motion | IMU or GNSS-speed said moving |
| 3 | trip_start | First point after wake — segments trips without heuristics |
| 4 | tamper | *(unused, reserved)* |
| 5 | charging | From PMU |
| 6–7 | reserved | Leave zero, validate on read |
| 8–15 | zone_mask | Which of first 8 geofences contained this point |
| 16–31 | seq_lo | Low 16 bits of monotonic counter — detects gaps |

### Chunk framing (BLE Data characteristic)

```
+--------+--------+---------------------+---------+
| seq u16| len u16|    payload (N B)    |crc16 u16|
+--------+--------+---------------------+---------+

N = negotiated_mtu - 9
payload holds WHOLE RECORDS ONLY, never split
crc16 CCITT over seq + len + payload
```

Whole-records-per-chunk wastes a few bytes and eliminates an entire reassembly bug class. Take the trade.

### Sync handshake

1. Client connects, negotiates max MTU, subscribes.
2. `LIST` → node replies with manifest.
3. Client picks first gap: `START(day, offset)`.
4. Node streams chunks as fast as the interval allows.
5. Every 32 chunks: `ACK(last_good_seq)`. Node advances window.
6. On CRC failure or `seq` gap: client stops acking, re-issues `START` from last verified offset.
7. Zero-length chunk ends transfer.

### LoRa beacon — 18 bytes

```
device_id u16   lat_e7 i32   lon_e7 i32
epoch     u32   flags  u16   battery u8   hdop u8
+ 4-byte AES-CCM tag → 22 bytes on air
```

~370 ms airtime at SF10/125 kHz. At 1% duty cycle that allows ~97/hour — far more than a 5-minute alert interval needs.

### Manifest (on device)

```c
struct manifest_entry {   // 16 bytes, one per file
  uint32_t day;           // YYYYMMDD as integer
  uint32_t record_count;  // node-authoritative
  uint32_t synced_upto;   // last offset the client acknowledged
  uint32_t reserved;
};
```

Written atomically: `manifest.tmp` then rename. Below ~15% free space, delete oldest file where `synced_upto == record_count`; if none qualify, delete oldest anyway and set `data_lost` so the UI can surface it.

---

## 5. Firmware stack

| Layer | Component | Note |
|---|---|---|
| SDK | ESP-IDF v5.x | Pin exact version in `idf_component.yml` |
| BLE | NimBLE (bundled) | Much smaller than Bluedroid; supports multi-connection cleanly |
| LoRa | RadioLib as IDF component | Respect BUSY line; DIO1 as real IRQ, not poll |
| HTTP + WS | `esp_http_server` | WebSocket built in |
| FS | `esp_littlefs` | Power-loss safe, unlike SPIFFS |
| SD | `esp_vfs_fat` over SDSPI | Mount lazily, unmount before sleep |
| Tests | Unity on target + host build of pure logic | Scheduler and codec compile for host with stub HAL → CI without hardware |

### Partition table, 8 MB

```
nvs      , data, nvs      , 0x9000 , 0x6000
otadata  , data, ota      , 0xf000 , 0x2000
app0     , app , ota_0    , 0x10000, 0x280000
app1     , app , ota_1    ,        , 0x280000
storage  , data, littlefs ,        , 0x2C0000   # track logs + manifest
```

### sdkconfig — the lines that matter

```
CONFIG_BT_NIMBLE_MAX_CONNECTIONS=3      # default is 1 — this is the multi-client switch
CONFIG_ESP_WIFI_SOFTAP_MAX_STA=4        # 3 clients + rejoin headroom
CONFIG_SPIRAM_MODE_OCT=y                # S3 module is octal PSRAM
CONFIG_PM_ENABLE=y                      # dynamic frequency scaling
CONFIG_FREERTOS_USE_TICKLESS_IDLE=y     # biggest idle-power win
CONFIG_ESP_TASK_WDT_TIMEOUT_S=10
CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y
CONFIG_HTTPD_WS_SUPPORT=y
```

### Debugging — set this up in Phase 01

The **ESP32-S3 has a built-in USB-JTAG bridge**. No external probe, no wiring — real breakpoints, watchpoints, single-stepping over the same USB-C cable used for flashing, via OpenOCD + GDB.

This is the most underused feature of the chip. Configure it before you need it, not after three days of `printf`.

Also: `esp_log` with per-tag levels (turn the scheduler chatty without drowning in WiFi logs), task watchdog that names the offending task, crash handler writing backtrace to LittleFS.

### BLE connection setup (the client's core path)

```kotlin
gatt.requestMtu(517)
gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
gatt.setPreferredPhy(PHY_LE_2M_MASK, PHY_LE_2M_MASK, PHY_OPTION_NO_PREFERRED)
```

Default 1M PHY gives 5–8 KB/s. Tuned gives 15–25 KB/s on the S25. Surface the measured value in Diagnostics so regressions are visible.

Manifest permissions (Android 12+):
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<service android:name=".SyncService" android:foregroundServiceType="connectedDevice" />
```

`neverForLocation` lets you skip location permission entirely — honest here because position arrives over GATT after connecting, never from scan results.

---

## 6. Android client stack

*(This section replaced the web/PWA client stack on 2026-07-20 — see the superseding decision in section 2.)*

| Concern | Choice | Note |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose | Single module to start; protocol code in a plain-Kotlin module for CLI/test reuse |
| Map | MapLibre Android SDK + `pmtiles` | Basemap tiles pulled from the node over WiFi once, cached on the phone |
| Local store | Room (SQLite) | Holds cursor + history → reconnect is a delta |
| Background sync | Foreground service, `connectedDevice` type | BLE sync with the screen off — the reason the app exists |
| Transports | BLE GATT (always) + OkHttp WebSocket over SoftAP (bulk/live) | Bind sockets to the WiFi `Network` object so Android's mobile-data fallback cannot steal the session |
| Tests | JUnit (codec vs golden vectors), instrumented test against the mock node | Protocol module runs on JVM — no device needed for codec work |

**Onboarding screen is not optional.** One UI's "Deep sleeping apps" and Adaptive Battery silently kill background sync on the S25 — walk the user through exempting the app on first run, and detect + warn when it happens anyway.

### Build the mock node on day one

~200 lines of Node.js (or Ktor) serving the same WebSocket protocol from a captured session, plus a replay mode. Lets you build the entire app with hardware unplugged, iterate in seconds instead of flash cycles, and run instrumented tests deterministically. BLE cannot be mocked this way — develop the GATT path against the real board, everything else against the mock.

Highest-leverage code in the project. Do not skip it.

### UI rules

- Five tabs, flat: **Live · Map · Chat · Clients · Config**. Diagnostics via long-press on the title, not a sixth tab.
- **Every position carries its uncertainty.** Error ellipse from HDOP. Interpolated segments dashed. Stale positions labelled with age. Never a crisp dot implying precision the GNSS did not deliver.
- Queue state visible: "queued 40 s, ravi ahead of you" — not a spinner.
- Failure states name the failure and offer the next action.
- Config staged locally, written explicitly. Never silently reconfigure mid-edit.

### Two languages, one wire format (Phase 05, not before)

Firmware is C, app/CLI is Kotlin. Hand-writing the codec twice still guarantees drift eventually — that drift bug is the Phase 05 trigger.

```
schema/
  records.yaml      # field name, type, scale, unit
  control.proto
  gen.py            # emits both targets
    → firmware/gen/records.h    (packed structs + static_asserts on size)
    → shared/gen/Records.kt     (ByteBuffer reader/writer — app, CLI, tests)
```

| Message class | Format | Why |
|---|---|---|
| Track records | Fixed-width struct, generated | 32 B, random access. Protobuf costs ~40% more and kills seeking. |
| Control, config, chat | Protobuf (nanopb / protobuf-kotlin) | Schema evolution matters, volume is low |
| LoRa beacon | Hand-packed 18 B | Every byte is airtime |

**Generate golden vectors too** — one hex fixture file both implementations decode in their own suite. If C and Kotlin agree on the same 40 bytes, the format is one thing rather than two hopeful ones.

---

## 7. Roadmap

Ordered for earliest visible milestone. The original plan front-loaded a week of pure Kotlin with nothing to look at — that is how side projects die.

### Phase 01 — Blink, then be seen · *one evening*
ESP-IDF toolchain up. **USB-JTAG debugger working with a real breakpoint.** LittleFS mounted. SoftAP with a fixed SSID + BLE advertising — the phone sees `lokalgrid` in its WiFi list and in nRF Connect. Nothing else.

### Phase 02 — One phone, live position · *one weekend*
Minimal Android app: connect to the node's AP, NMEA parsed → WebSocket → MapLibre map with accuracy ellipse. **Hand-write the codec** (C and Kotlin). No schema, no generation. Get the dot on the map.

### Phase 03 — Three phones, shared state · *two weekends*
Per-client cursors, message history, chat (one channel, text only), everyone seeing everyone.
**⚑ NATURAL STOPPING POINT.** This is a complete project. Stopping here is success.

### Phase 04 — Make it fight · *two weekends*
Deficit round-robin, priority lanes, admission control, backlog interleaving. Test by writing a client that deliberately floods. Most transferable engineering in the project.

### Phase 05 — Fix the drift · *one weekend*
By now the hand-written codec has bitten you. *That* is when the schema and codegen arrive.

### Phase 06 — Optional: the link out
LoRa under the scheduler, position aggregation, duty-cycle ceiling, BLE presence layer. Only if there is somewhere to take it where WiFi range genuinely runs out.

### Phase 07 — Optional: background sync polish and CLI
Foreground-service BLE sync hardening (One UI battery exemptions, resume-from-cursor soak tests); JVM CLI (Clikt + jSerialComm) over USB serial reusing the shared Kotlin codec. A browser client could also return here as an extra, never as the primary.

CLI surface sketch:
```
node devices | watch | pull --since | config get|set
node decode dump.bin --format gpx|csv|json
node replay dump.bin --speed 60x     # impersonate a node
node bench --duration 60s
```

### Keeping it alive

Hobby projects die from **lost context**, not difficulty.

- Dated build log in the repo. One entry per session: what you tried, what surprised you, what's next.
- Commit the broken state. `wip-scheduler-confused` + a log note beats a clean repo you cannot re-enter.
- Stop at a milestone, not mid-refactor. Tests green is a gift to you-in-a-month.

---

## 8. Traps

| Trap | Symptom | Mitigation |
|---|---|---|
| Wrong-band antenna | Poor range that reads exactly like a firmware bug; PA degrades over weeks | Match the whip to the silkscreen |
| TX with no antenna | Damaged PA | Firmware refuses TX until a post-assembly flag is set |
| AP left up by default | Runtime collapses from a week to a day | Idle timeout in firmware, not config. Log why the AP came up. |
| Android drops the AP | Client vanishes mid-session | Bind app sockets to the WiFi `Network`; BLE underneath; client resumes from cursor. |
| One client floods | Others silently starve | Deficit round-robin + visible queue state. Never drop silently. |
| MTU assumed not read | Works on one phone, truncates on another | Size chunks from negotiated value at runtime |
| Notification queue overflow | Silent chunk loss under fast sync | Respect NimBLE notify-complete callback; never loop blindly |
| SPI bus shared | Corrupt writes only when a beacon fires mid-write | SX1262 and SD on separate buses — non-negotiable |
| FS write during sleep entry | Corrupt LittleFS | Single storage owner: drain, flush, then signal ready-to-sleep |
| GNSS cold start | First minute of every trip missing | Keep backup rail powered from PMU for warm starts |
| Blocking on a sensor at init | Hangs at boot when one I²C device fails or a variant omits it | Probe with timeout, log what answered, branch |
| Stale BLE bonds | Connection failures that look exactly like firmware bugs | Do not bond during development |
| Geofence jitter | Hundreds of events overnight from a parked node | Streak counter (3 fixes) + 30 m dead band |

---

## 9. Resume here

**Immediate blocker:** confirm the LoRa band from the silkscreen (section 1). Does not block Phases 01–03, which use no LoRa at all.

**Next action:** Phase 01. The skeleton already exists under `firmware/` (CMake shell, partitions.csv, sdkconfig.defaults, LittleFS mount in `app_main`). Remaining —

1. Install ESP-IDF v5.x (`firmware/README.md` has the exact commands), `idf.py set-target esp32s3`
2. First `idf.py build flash monitor` — expect "littlefs mounted" and the heartbeat log
3. Get OpenOCD + GDB attached over built-in USB-JTAG, set a breakpoint in `app_main`, confirm it hits
4. SoftAP up with a fixed SSID + NimBLE advertising
5. Phone sees `lokalgrid` in its WiFi list and in nRF Connect. Done.

**Then:** Phase 02 (minimal Android app, dot on the map), and keep the build log going.

### Adjacent projects deliberately parked

Not a backlog — genuinely optional alternatives if this stops being fun. The hardware supports all of them with no additions:

- **TinyGS satellite ground station** — receive LoRa cubesat telemetry. RX-only, no licence, board is supported. Especially good if the band turns out to be 433, since most LoRa satellites are there.
- **Stratum-1 NTP server** from the GNSS 1PPS pin. Hard timing problem, genuinely useful afterwards.
- **Vibration analyser** — IMU at a few hundred Hz + FFT + trend to SD. Predictive maintenance on a ceiling fan or pump.
- **GNSS interferometric reflectometry** — SNR oscillation from `GSV` sentences gives water level / soil moisture.
- **WiFi CSI presence sensing** — the ESP32 exposes channel state information; detect motion through walls using only the onboard radio.
- **433 MHz OOK decoder** — rtl_433-style, decode *your own* doorbell/weather station/TPMS.

> If one of these sounds better than the LoRa layer on a given Saturday, build it. The hardware does not care and neither does anyone else. The only real failure mode is grinding through a phase you stopped enjoying because a plan said so.

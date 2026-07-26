# Lokalgrid — shared field node, project handoff

> **Project name: Lokalgrid** *(2026-07-20)* — *lokal* (German: local) + grid: your own tiny infrastructure grid in the field, no carrier, no internet. Verified unique on GitHub at naming time (0 repos). Repo/dir name: `lokalgrid`.

> Context file for resuming this project from a CLI agent session.
> Drop this at the repo root. Companion doc: `lokalgrid-master-plan.html` — the **reference spec** (hardware · app · protocol · state). That file carries no rationale and no history; this one carries no step-by-step detail. When a decision here changes something the spec asserts, update both.

---

## 0. How to use this file

This is a **decision record**, not a tutorial. It exists so that a fresh session — human or agent — can pick up without re-deriving three weeks of conversation.

If you are an agent reading this: sections 1–6 are binding constraints. Section 2 lists things that were **explicitly rejected** — do not re-propose them without reading the rationale. Section 9 is where work resumes.

**Project status:** Phase 00 done (mock node + golden vectors), Phase 01 code on disk (app decodes the stream, map screen built), Phase 02 started — chat and the forward flow are wired end to end against the mock. Still no hardware; the T-Beam stays boxed until Phase 03.

---

## 1. Hardware

### Confirmed

**LilyGO T-Beam Supreme**, purchased from robu.in (~₹6018).
The silkscreen reads **T-Beam S3-Core v3.0** — this is expected. The Supreme *is* the S3-Core module seated in a baseboard. Do not treat these as different boards.

| Subsystem | Part | Bus |
|---|---|---|
| MCU | ESP32-S3FN8, dual core, 8 MB flash, **8 MB quad PSRAM** *(not octal — verified 2026-07-26, see below)* | — |
| LoRa | SX1262, **868/915 MHz** variant | SPI2 (dedicated) |
| GNSS | L76K (some units ship u-blox MAX-M10S) | **UART1, rx 9 / tx 8, 9600 baud, behind ALDO4** |
| PMU | AXP2101, with coulomb counter | **I²C1** (SDA 42, SCL 41) |
| IMU | QMI8658 — **not fitted on this unit** | *no answer on either bus with all rails on (2026-07-26)* |
| Magnetometer | QMC6310 | I²C0 (SDA 17, SCL 18) |
| Baro/temp/humidity | BME280 — **present on this unit** at 0x77 | I²C0 |
| RTC | PCF8563 | **I²C1** |
| Display | 1.3" OLED 128×64, SH1106 at 0x3c | I²C0 |
| Storage | microSD | SPI3 (dedicated) |
| Power | 18650 holder on baseboard | — |

### LoRa band — RESOLVED *(2026-07-26)*

**868 MHz, constrained to 865–867.** The board is the **868/915 MHz SX1262** variant — the India-compatible one, confirmed by the owner (the unit is the SoftRF edition of the T-Beam Supreme). This closes the question that stood from the first session; the 433 MHz figure in the robu.in listing title was wrong for this unit. 915 is the same module's other setting and is **not** usable here — it belongs to the US/AU allocations.

What follows from it, and is now binding:

- **Centre the beacon inside 865–867 MHz** — India's delicensed ISM allocation and the Meshtastic `IN` region. **Do not use the full EU 868 range**; part of it is licensed here.
- Buy an **865–868 MHz** SMA whip. An 868 antenna is roughly half the length of a 433 one, which is the quick sanity check on what arrives.
- TinyGS satellite reception is **out** — most LoRa satellites are on 433. That was only ever an aside, and this settles it.
- The **1% hourly duty cycle** stands regardless, enforced as a hard limit in firmware rather than a config setting.

Still worth thirty seconds with the board in hand before the first transmission: read the silkscreen beside the SMA connector (LilyGO marks it `433M`, `868M` or `915M`) and confirm it says `868M`. A band mismatch reads exactly like a firmware bug and degrades the PA over weeks — cheap to verify, expensive to assume. It does not block Phases 01–03, which transmit nothing.

### What the board actually answered *(2026-07-26, first boot)*

Two claims in the table above were wrong, and both were found in the first five minutes of running real firmware rather than by reading:

- **PSRAM is quad, not octal.** `CONFIG_SPIRAM_MODE_OCT=y` produced `octal_psram: PSRAM ID read error: 0x00000000` and then `abort()`, in a boot loop. Quad mode initialises all 8 MB (`Adding pool of 8192K of PSRAM memory to heap allocator`). `CONFIG_SPIRAM_IGNORE_NOTFOUND=y` is now set as well: PSRAM that does not answer must degrade to internal RAM with a warning, never take the node down.
- **There are two I²C buses**, and the split is not the one assumed. `i2c0` (SDA 17, SCL 18) carries the OLED, BME280 and magnetometer; `i2c1` (SDA 42, SCL 41) carries the **AXP2101** and the **PCF8563**. The first scan of bus 0 alone found no PMU on a board that runs off a battery, which is what pointed at a second bus — this is the I²C scan doing the job it exists for.
- **The QMI8658 answered on neither bus.** Either this variant omits it or it sits behind a PMU rail that is still off. Not concluded; the motion gate falls back to GNSS speed until the AXP2101 is driven and the scan is repeated.
- The BME280 **is** fitted on this unit, so `baro` and `tmp` can carry real values rather than sentinels — it is not read yet, so they still write sentinels until it is.

**The rail map, read before anything was written** (`0x90 = 0x17` at power-up):

| Rail | Voltage | At power-up | What it is |
|---|---|---|---|
| ALDO1 | 1800 mV | on | — |
| ALDO2 | 3300 mV | on | — |
| ALDO3 | 3300 mV | on | — |
| **ALDO4** | 3300 mV | **off** | **GNSS** — confirmed by switching it on and getting NMEA |
| BLDO1 | 1800 mV | on | — |
| BLDO2 | 3300 mV | off | unidentified; the SX1262 is the obvious candidate |
| DLDO1 | 2300 mV | off | — |
| DLDO2 | 3300 mV | off | — |

A rail programmed to a sensible voltage and left switched off is the signature of a peripheral the factory firmware powers on demand. Enabling one is **one bit in `0x90` and no voltage register** — that is the whole safety argument, and it is why `axp_enable_rail()` cannot change a voltage even if asked. **The GNSS receiver was not dead, it was unpowered**, which is worth remembering: on this board a silent peripheral is a power question before it is a pin question.

**Powered from USB, not a cell** *(2026-07-26)*: a Mac, a phone or a power bank. The PMU reports `no cell`, so there is no battery percentage to show and the power ladder (§2, Phase 06) has nothing to measure — both say so rather than inventing a number. It also means every GNSS start is a cold one, since nothing keeps the almanac alive when the cable comes out.

### What each peripheral is actually for *(2026-07-25)*

Most of the board was doing nothing. Every part now has a job or is explicitly parked, so a future session doesn't have to guess whether an idle chip is an oversight or a decision.

| Part | Job | Phase |
|---|---|---|
| **OLED 1.3"** | **The node is usable with zero phones connected.** Pages cycled by the user button: roster + client count, queue depth and duty used, battery and hours left per power rung, last message sender, pairing code, emergency state. This is what makes it a *device* rather than a headless box. | 03 minimal, 04 full |
| **User button** | **Physical emergency.** Hold 2 s → lane-0 beacon with position, regardless of whether any phone is attached. Short press cycles OLED pages; long press enters pairing. A walkie-talkie has a button on the box. | 04, LoRa half in 06 |
| **AXP2101 + coulomb counter** | The power ladder's numbers: hours remaining per rung, and the thresholds that shed services. | 06 |
| **BME280** *(when fitted)* | Already in the record as `baro`/`tmp` with sentinels. Used for a **pressure trend** — falling pressure over 3 h is worth telling a walking group about — not for altitude. | 06b |
| **PCF8563 RTC** | Holds time across deep sleep with GNSS powered down, so timestamps stay valid on a node that is conserving battery. Disciplined by GNSS when a fix is available; the fallback source for the phone time service. | 06b |
| **QMI8658 IMU** | Motion gate for logging and beacon interval — a stationary node beacons less. **Not** tamper/free-fall detection, which stays rejected. | 04 |
| **8 MB PSRAM** | Backlog chunk buffers, the position ring, and tile serving. The reason a client can catch up without the node stalling. | in use |
| **microSD** | **Purpose changed:** map tiles. 8 MB of internal flash cannot hold a PMTiles basemap; an SD card can. Still rejected as a *cold log archive* — see §2, that rationale is unchanged. Stays on SPI3, never shares a bus with the SX1262. | 06b, optional |
| **QMC6310 magnetometer** | Nothing. Heading comes from course-over-ground; rejected in §2 and still rejected. | — |

### The board as a range booster *(2026-07-25)*

A second board is the cheapest way to double useful coverage, and it is what makes the *building* case work at all.

**The physics that matters:** sub-GHz LoRa goes through concrete floors and walls that 2.4 GHz WiFi and BLE simply do not. So indoors, one node per floor — phones attach over WiFi/BLE to whichever node is nearest, and the nodes link to each other over LoRa. Outdoors it is the same trick over a ridge instead of a slab.

**Topology: a star of cells, never a mesh.**

```
        phones ──WiFi/BLE──┐
                           ├── BOOSTER (floor 3) ──LoRa──┐
        phones ──WiFi/BLE──┘                             │
                                                         ├── PRIMARY ── clients, log, roster
        phones ──WiFi/BLE──── BOOSTER (basement) ──LoRa──┘
```

- Exactly **one hop**. A booster talks to the primary and to nobody else — no booster-to-booster path, so there is no routing table, no loop detection, and no duplicate suppression to get wrong.
- The **primary owns the log and the roster**; boosters are relays with their own local clients, and forward a summary upward. One authority, as §3 requires.
- **Airtime is the real ceiling, not the node count.** Every relayed message is paid for twice — once on each hop — against a 1 % duty cycle, so 2–3 boosters at conversational traffic is the honest practical limit. The UI must show which cell a message crossed and what that cost, or the whole airtime-economy feature quietly starts lying.
- A booster with no clients still logs GNSS and beacons, so it doubles as a coverage marker.

*(This supersedes the earlier "exactly two boards, statically paired" note from the same day: the constraint that matters is the single hop, not the board count.)*

### Additions needed

| Part | ~Cost | Note |
|---|---|---|
| **865–868 MHz** SMA whip | ₹200 | Band confirmed 868 (section 1). Wrong band = bad SWR = slow PA damage. |
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
| **Client cap = hardware ceiling (9), not 3** *(2026-07-23, supersedes "Max 3 clients")* | Original cap was 3, chosen because airtime arbitration is interesting at 3 while admission-control complexity was judged to explode beyond it. Raised to the hardware ceiling: NimBLE tops out at **9** concurrent BLE connections (`CONFIG_BT_NIMBLE_MAX_CONNECTIONS` max), WiFi SoftAP at 10 stations. The scheduler must now generalise beyond N=3 — see the hardware-vs-chosen limits note in section 3. The real bottleneck was never the connection count but LoRa airtime: 3 phones at 1 Hz already saturate the link, so 9 clients means aggressive position decimation and honest queue reasons for everyone past the airtime budget. |
| **Native Android app (Kotlin) is the client** *(2026-07-20, supersedes "PWA is primary")* | Background BLE sync via a foreground service — positions and chat backfilling with the screen off — is the feature a browser can never provide, and it matters more here than the zero-install pitch. The install barrier is accepted: this is a personal build for a known group, not a product for strangers. The PWA plan (Vite/Svelte/Workbox, node-served assets) is removed; a browser client may return later as an optional extra, not as the primary. |
| **WiFi SoftAP on demand, BLE always-on underneath** | AP draws ~100 mA vs BLE ~2 mA. AP-by-default turns a week of runtime into a day. |
| **AP idle timeout enforced in firmware** | Not a setting. A config toggle will eventually be left wrong. |
| **ESP-IDF v5.x + CMake directly** | Not Arduino (hides sleep/NimBLE/power APIs). Not PlatformIO (IDF support lags on S3). |
| **Hand-write the codec first, generate later** | Codegen is introduced in Phase 05, *after* drift has caused a real bug. Understanding beats compliance. |
| **Uncertainty is rendered, always** | Error ellipses from HDOP, dashed interpolated segments, ages on stale positions. No consumer tracker does this; it is the product's honesty and its identity. |
| **Chat = one shared channel, text only** *(2026-07-20, DM half superseded 2026-07-25)* | No DMs, no channels, no media at launch. One room mirrors the product (one node, one group in the field) and keeps Phase 03 small. Revisit only after Phase 03 is alive. |
| **Product framing: a modern walkie-talkie** *(2026-07-25)* | Encrypted messaging for whoever is inside a building, or within radio range of the node. "Walkie-talkie" is the *metaphor* — group, range-bound, immediate, no dialling and no accounts — **not** voice: it stays text (media is still rejected). Range is enforced by physics rather than policy, which is the honest version of a geofence: if you can reach the node you are in the group, and if you walk out you are not. |
| **Messages are encrypted end to end; the node relays ciphertext** *(2026-07-25)* | Per-pair keys from an X25519 exchange at pairing, confirmed by a short code **shown on the node's OLED** — authenticated pairing with no server and no shared secret typed twice. The node can schedule and queue a message from its length and addressee alone, so it never needs the body. **This does not reverse the rejected item below:** encrypting the *track log at rest* with an eFuse-burned key is still rejected. Message confidentiality in flight is a different threat model, and §3 already carried a per-client `key[32]` from pairing. Metadata — who talks to whom, when, how long — remains visible to the node, and the UI must not imply otherwise. |
| **One session, many transports** *(2026-07-26)* | `proto 2` is identical over WiFi and BLE, so the protocol lives once in `firmware/main/session.c` behind a transport interface and each wire is a thin adapter (`net_ws.c`, `ble_gatt.c`). A copy per transport would guarantee they drift — the same bug this project already expects between C and Kotlin (§6), invited a third time on purpose. A client is a client whichever wire it arrived on: one roster, one cap of 9, and the roster names each client's transport. |
| **The app is 64-bit only** *(2026-07-26)* | 16 KB page support is a 64-bit feature, and the 32-bit MapLibre libraries are still linked at 4 KB alignment. Carrying them means shipping ~20 MB that cannot run on either target device (a Galaxy S25, an arm64/x86_64 emulator) while muddying any alignment check. `abiFilters` is arm64-v8a + x86_64. |
| **Two basemaps, and offline downloads are explicit** *(2026-07-26)* | Satellite and topo were there because they were free, not because the product needs them, and every style is another tile pyramid to fetch for offline use. Streets and Dark stay. The download is user-initiated, shows the tile count and rough size *before* it starts, and says to do it on a network with internet — a few hundred megabytes over someone's mobile data is not a decision the app gets to make. Node-served PMTiles (Phase 06b) is the better answer later. |
| **Location permission is asked for at the tap, not at first run** *(2026-07-26, narrows the §5 "no location permission at all" note)* | The phone's own GNSS is now what "share my position" sends, so `ACCESS_FINE_LOCATION` (plus `COARSE`, because Android lets the user grant only that) is requested — but **only when the user taps share**, where the reason is on screen. This does not weaken `neverForLocation` on `BLUETOOTH_SCAN`: finding the node still never needs location, and the app never derives a position from a scan result. Refusing it leaves the app fully usable: the node's own fix goes instead and is **labelled as the node's**, so a position never travels under a source nobody stated. Background location is not requested — sharing happens with the app open, and the foreground service arrives with BLE. |
| **Each user's messages live on their own device** *(2026-07-25, supersedes "no DMs")* | Durable history is a **Room (SQLite)** database on the phone; the node keeps only a short ring for backfill plus undelivered dead-drop traffic, and holds those as ciphertext it cannot read. This supersedes the "no DMs" half of the 2026-07-20 chat decision — addressed messages are the point of a walkie-talkie with named handsets — while the *shared channel* stays exactly one, text only. Rationale: the node is a relay you might leave behind, so the copy that matters belongs to the person who sent or received it. |
| **Concept reconfirmed after 2026-07-20 rethink** | Alternatives surveyed against the 2026 landscape (MeshCore, Reticulum, TinyGS, sonde/APRS firmware) — see `lokalgrid-master-plan.html` §14. Shared field node stands: one node, multiple phones, everyone on one map. (The client later moved from PWA to native Android — see the superseding row above.) |

### REJECTED — do not re-propose

| Rejected | Why |
|---|---|
| eFuse key burn / AES-GCM log encryption | Irreversible, solves a threat model that does not exist for a hobby build. |
| Tamper detection (reed switch, free-fall) | Needs a real theft scenario to design against. There isn't one. |
| Solar charging | Adds a variable while fixed things are still being debugged. |
| OTA update | Nobody depends on this device. Flash over USB. |
| IP65 enclosure / field hardening | Product work, not learning work. |
| microSD cold archive | Internal LittleFS holds ~a week. Deferred, not needed. *(Still rejected as an archive. The card gets a different job in §1 — holding PMTiles, which internal flash cannot fit. Different purpose, not a reversal.)* |
| Magnetometer heading | Course-over-ground from GNSS is sufficient. |
| PostGIS / cloud backend / fleet mode | No server. The node is the authority. |
| Copying Meshtastic firmware code | GPL. Reading for architecture ideas is fine; copying binds the licence. Writing from the protocol spec is cleaner and more instructive. |

### What makes this one different *(2026-07-25)*

Group messaging with no infrastructure is a solved problem — phones can mesh among themselves and need nothing bought. If chat were the goal there would be no reason to build this. So the node has to earn its place by being what a pile of phones structurally cannot be: **infrastructure you carry**. Five pillars, each impossible for a phone-only mesh:

| Pillar | What it means | Why a phone mesh cannot |
|---|---|---|
| **Memory** | The node logs every fix with a monotonic seq and serves any client its exact delta on return; each phone keeps its own durable copy in Room. Two tiers: the node is the relay and the dead-drop, the device is the archive. | A mesh remembers only what a present device holds; nothing stays awake to hand you what you missed. |
| **Authority** | One place decides what exists — seq, roster, admission — and states its reasons. Clients own only their cursors. | Peers with equal claim to truth must reconcile it: consensus, in a walking group. |
| **Range** | Licence-free LoRa measured in kilometres, from a fixed point, nobody needed in between. | Mesh reach is crowd density. Two people over a ridge have no path. |
| **Truth** | GNSS position *and* GNSS time from the constellation, uncertainty rendered on every point. | A phone with no signal has neither a trustworthy clock nor a fix to share. |
| **Endurance** | A stated power ladder: services shed in a fixed order, each step announced with its reason. | Every phone guessing its own battery policy gives the group no predictable behaviour. |

**The three features that make it itself** — cut these and it becomes a generic tracker:

1. **The airtime economy, made visible** *(Phase 04)* — the cost of a message in ms *before* sending, each client's share of the hour, a queue you can cancel or promote, and a name rather than a spinner when you are behind. Every other radio product hides this; hiding it is why they feel broken on a slow link.
2. **The encrypted dead-drop** *(Phase 04b)* — the node holds sealed messages for people who are not here *yet* and hands them over on arrival, plus a *last seen* answer from its own log. It can queue and schedule them without being able to read them. Only possible because something stayed awake and remembered.
3. **The power ladder** *(Phase 06)* — serve everything → BLE only → beacon only → sleep, each rung at a stated threshold with a coulomb-counter estimate. A group can plan around a node that says what it is about to stop doing.

Also planned, both falling out of hardware already on the board: **time service** from the GNSS 1PPS pin (trustworthy timestamps with no internet, and timeline repair for records logged without a fix) and **node-served PMTiles** so a phone that has never been to this valley still gets a basemap *(Phase 06b)*. And a **two-node LoRa link** — exactly two boards, statically paired, never a mesh, because the airtime arithmetic stops being explainable past one hop *(Phase 06)*.

> The rule underneath all of it: **the constraint is the interface.** Duty cycle, battery, storage and range are not problems to hide from the user — they are the only genuinely interesting content this product has, so they get rendered rather than smoothed over.

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
| WiFi SoftAP | 2–8 Mbit/s | ~100 mA | ~30 m | On demand. Bulk history sync and live WebSocket stream to the Android app, up to the client cap at once. |
| LoRa | ~1 kbit/s | 110 mA in TX | km | The link out. Positions + short messages. Duty-cycle bound. |

**Known Android behaviours to design around:**
- Android flags an AP with no internet route and may silently fall back to mobile data mid-session. The app must bind its socket to the WiFi network explicitly (`ConnectivityManager.bindProcessToNetwork` / per-socket `Network.bindSocket`); BLE stays up underneath; client resumes from its cursor.
- Target device is a **Samsung Galaxy S25**. One UI "Deep sleeping apps" and Adaptive Battery kill background work silently — the app needs an onboarding screen that walks through exempting it, and a foreground service (`connectedDevice` type) for background BLE sync.

### Client limit — hardware ceiling vs. chosen cap

Two different numbers, do not confuse them:

| Bound | Value | Set by |
|---|---|---|
| **BLE concurrent connections** | **9** | NimBLE build cap (`CONFIG_BT_NIMBLE_MAX_CONNECTIONS`, max 9). The ESP32-S3 controller advertises ~10 ACL links; NimBLE is the tighter limit. |
| **WiFi SoftAP stations** | **10** | ESP-IDF cap (`ESP_WIFI_SOFTAP_MAX_STA`, max 10). |
| **Effective client cap** | **9** | min(BLE, WiFi) — each client holds both a BLE link and a WiFi station. |
| **Practical stable** | ~4–6 | RAM per client (connection state + ATT buffers + 32-byte key + cursors) and shared throughput erode past this. |

The cap is now **9** (was 3). The true bottleneck is not the socket count but the **single LoRa radio**: 3 phones at 1 Hz already saturate ~1 kbit/s of airtime, so every client beyond the airtime budget gets decimated positions and a renderable queue reason — the arbitration is a physics problem, not a connection-count one.

### Multi-client arbitration — the core engineering

One radio, up to nine clients. This is what does not exist in Meshtastic's 1:1 model.

**Priority lanes:**
```
0  emergency  → pre-empts everything, ignores fairness
1  position   → aggregated, decimated, one packet per window
2  message    → deficit round-robin across clients
3  bulk       → only when the budget is otherwise idle
```

**Per-client budget:** each client gets 1/N of airtime remaining after lanes 0 and 1. Unused allocation **decays rather than banks** — an absent client must not hoard then flood.

**Admission control:** reject at enqueue with a renderable reason. Never silently drop. UI shows "queued, 40 s — bravo ahead of you".

**Client state:**
```c
struct client {
  uint8_t  id;              // 0..8, assigned at pairing
  uint32_t msg_cursor;      // last message seq delivered
  uint32_t pos_cursor;      // last position seq delivered
  uint32_t airtime_credit;  // deficit round-robin, in ms
  uint8_t  key[32];         // per-client, from pairing ECDH
  transport_t active;       // BLE | WIFI | NONE
};
```

**Backlog:** a client returning after an hour needs ~200 messages and must not block the two that are live. Serve backlog in bounded chunks interleaved with live traffic.

**Backpressure:** 3 phones at 1 Hz saturates LoRa instantly. Node aggregates into one packet per window, decimates by *distance* not time (50 m default).

**Degradation:** client past the cap (10th connection) → refuse with a reason. Battery <15% → drop AP, keep BLE + LoRa, tell everyone why.

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

### Partition table, 8 MB *(corrected 2026-07-26 — the first real build rejected the old one)*

```
nvs      , data, nvs      , 0x9000 , 0x4000
otadata  , data, ota      , 0xd000 , 0x2000
app0     , app , ota_0    , 0x10000, 0x280000
app1     , app , ota_1    ,        , 0x280000
storage  , data, littlefs ,        , 0x2C0000   # track logs + manifest
```

The version carried here until now gave `nvs` 0x6000 and put `otadata` straight
after it, which ends at 0x11000 and overlaps `app0` at 0x10000 — the app has to
start there. `gen_esp32part` refused it the first time this was built for real
(*"Partitions overlap"*). 16 KiB of NVS is ample: WiFi calibration plus this
project's settings.

**Open question, not yet decided:** `app1` costs 2.5 MB for OTA, which §2
rejects outright ("nobody depends on this device — flash over USB"). Dropping it
would nearly double the space for track logs and PMTiles. Left in place for now
because rollback protection uses the same machinery; worth settling before the
log or the basemap starts running out of room.

### sdkconfig — the lines that matter

```
CONFIG_BT_NIMBLE_MAX_CONNECTIONS=9      # default is 1; 9 is NimBLE's hard ceiling — the multi-client switch
CONFIG_LWIP_DHCPS_MAX_STATION_NUM=10    # DHCP leases; the AP station cap itself is runtime, see below
CONFIG_SPIRAM_MODE_QUAD=y               # quad on this module — octal aborts in a boot loop
CONFIG_SPIRAM_IGNORE_NOTFOUND=y         # absent PSRAM costs the backlog buffers, never the node
CONFIG_LWIP_MAX_SOCKETS=16              # esp_http_server needs sockets-3 >= its own cap
CONFIG_PM_ENABLE=y                      # dynamic frequency scaling
CONFIG_FREERTOS_USE_TICKLESS_IDLE=y     # biggest idle-power win
CONFIG_ESP_TASK_WDT_TIMEOUT_S=10
CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y
CONFIG_HTTPD_WS_SUPPORT=y
```

**`CONFIG_ESP_WIFI_SOFTAP_MAX_STA` does not exist** in ESP-IDF v5.3 — it was
listed here and was silently ignored (the build warns about unknown symbols,
which is how it surfaced). The SoftAP station limit is a *runtime* field,
`wifi_config_t.ap.max_connection`, capped at 10 by the API and set in
`wifi_ap.c`. The Kconfig line that does matter is the DHCP server's lease table
above: without it the tenth phone associates and then never gets an address,
which looks like a broken node rather than a full one.

### What the firmware actually is *(2026-07-26)*

```
firmware/main/
  main.c          boot order, heartbeat, the status page
  board_pins.h    pin map — both I²C buses and the GNSS UART, verified on this unit
  board.c         sweeps both buses, names every address, reuses a live bus
  axp2101.c       PMU: reads everything, and switches exactly one bit when asked
  gnss.c          probes for the receiver, parses GGA/RMC/GSA into a fix
  oled.c          SH1106/SSD1306, six lines of 5x7 text, hand-written
  session.c       proto 2 — the whole protocol, once, transport-agnostic
  net_ws.c        the WebSocket transport (esp_http_server)
  ble_gatt.c      the BLE transport (NimBLE GATT + §4 chunk framing)
  ble_adv.c       GAP: advertising, and the connection events GATT needs
  record.c        the 32-byte codec, C half — host-testable
firmware/test/     builds record.c for the host, checks it against the golden vectors
```

The BLE service is `6f6b616c-6772-6964-0000-000000000001`, with `…0002` for
control frames (write + notify) and `…0003` for records (notify). Written
byte-reversed in the firmware because NimBLE takes them little-endian.

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

`neverForLocation` means *finding the node* needs no location permission — honest here because position arrives over GATT after connecting, never from scan results.

Location is still requested, for one thing only: the phone's own GNSS behind "share my position", asked for at the tap (§2, 2026-07-26).

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

---

## 6. Android client stack

*(This section replaced the web/PWA client stack on 2026-07-20 — see the superseding decision in section 2.)*

| Concern | Choice | Note |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose | Single module to start; protocol code in a plain-Kotlin module for CLI/test reuse |
| Map | MapLibre Android SDK + `pmtiles` | Basemap tiles pulled from the node over WiFi once, cached on the phone |
| Local store | **Room (SQLite)** | The durable copy of *this user's* messages and track (2026-07-25). Lightweight, bundled with Android, no server. Holds cursors so reconnect is a delta. |
| Background sync | Foreground service, `connectedDevice` type | BLE sync with the screen off — the reason the app exists |
| Transports | BLE GATT (always) + OkHttp WebSocket over SoftAP (bulk/live) | Bind sockets to the WiFi `Network` object so Android's mobile-data fallback cannot steal the session |
| Tests | JUnit (codec vs golden vectors), instrumented test against the mock node | Protocol module runs on JVM — no device needed for codec work |
| Test/CI harness | **Android CLI + Journeys** (Google's agentic Android tools) | Terminal-driven build/install/UI-validation for the mock-first phases. **Not for BLE** — see caveat below. |

**Onboarding screen is not optional.** One UI's "Deep sleeping apps" and Adaptive Battery silently kill background sync on the S25 — walk the user through exempting the app on first run, and detect + warn when it happens anyway.

### Build the mock node on day one

~200 lines of Node.js (or Ktor) serving the same WebSocket protocol from a captured session, plus a replay mode. Lets you build the entire app with hardware unplugged, iterate in seconds instead of flash cycles, and run instrumented tests deterministically. BLE cannot be mocked this way — develop the GATT path against the real board, everything else against the mock.

Highest-leverage code in the project. Do not skip it.

### Agentic tooling — Android CLI + Journeys *(2026-07-23)*

Google's agentic Android tools (`developer.android.com/tools/agents`) are the test/CI harness for the **mock-first phases (01–02)**, where they fit cleanly:

- **Android CLI** — terminal-driven build/install/test, CI/CD-friendly. Matches the §5 goal of "CI without hardware": JVM codec + golden-vector tests and the instrumented UI tests run headless.
- **Journeys** — natural-language UI validation ("open Map, confirm the accuracy ellipse renders; send a chat message, confirm it appears"). This is the concrete form of the "instrumented test against the mock node" test row — the five tabs (Live · Map · Chat · Clients · Config) validated against the mock, deterministically, board unplugged.

**BLE caveat — the reason this is scoped, not blanket:** these tools accelerate only the *mockable* half of the app. **Journeys is UI validation; it cannot exercise a real BLE GATT connection to the T-Beam, and none of these tools touch the Phase 03 hardware path.** The §6 rule still governs: BLE and the foreground-service background sync — the features the app exists for — are developed and tested against the **real board**, never a Journey. Do not let a green Journeys run stand in for BLE coverage.

Practical notes: parts of the suite were still rolling out at adoption time (the CLI showed "Not Available" on some devices) — confirm it installs on macOS before committing to it. It is a *tool*, not a competing agent host; using it under Claude Code is fine, no need to move into Antigravity/Gemini to get the CLI and Journeys. Android Skills (app distribution) is out of scope — this is a personal build, not a published product (§2).

> **Screenshots of the built app** live in `docs/screens/` and are laid out in `lokalgrid-master-plan.html` §03. Captured 2026-07-25, Phase 02 against the mock.

### Local store and crypto *(2026-07-25)*

The phone holds the copy that matters; the node is a relay you might walk away from.

```
Room (SQLite), app-private storage
  message   id, peerId|CHANNEL, dir, seqNode, body, epoch, lane, state, airtimeMs
  peer      clientId, callsign, pubKey, lastSeen, lastLat/Lon/hdop
  track     seq PRIMARY KEY, 32-byte record blob        ← survives a reinstall of the node
  cursor    nodeId, posSeq, msgSeq                      ← one row per node, keyed by URL/id
  outbox    msgId, ciphertext, addressee, queuedAt, reason
```

Room, not SQLDelight or a file format: it ships with Android, it is the §6 choice already, and a hobby build should not carry a persistence framework it has to think about. Message bodies are stored decrypted in app-private storage — a phone-level threat is out of scope, and pretending otherwise with a passphrase nobody types would be theatre. SQLCipher stays a later option if that changes.

**Key model.** X25519 at pairing → a per-pair shared secret; the shared channel gets a group key issued by the node and rotated when the roster changes. Bodies are sealed with an AEAD (XChaCha20-Poly1305 — Monocypher on the ESP32, Tink on Android), so the node schedules from `{addressee, length}` and never sees plaintext.

**Pairing is confirmed on the node's OLED.** A six-digit code appears on the 1.3" display; you confirm it on the phone. That is authenticated key exchange with no server, no QR, and no secret typed twice — and it uses a screen the board already has, which is the sort of thing only a project with hardware can do.

**Stated honestly in the UI:** the node cannot read message bodies, but it *does* see who talks to whom, when, and how long the message is. Traffic analysis is not defended against. A "secure" badge that implies otherwise would break the §6 honesty rule harder than any spinner.

### UI rules

- Five tabs, flat: **Live · Map · Chat · Clients · Config**. Diagnostics via long-press on the title, not a sixth tab.
- **Every position carries its uncertainty.** Error ellipse from HDOP. Interpolated segments dashed. Stale positions labelled with age. Never a crisp dot implying precision the GNSS did not deliver.
- Queue state visible: "queued 40 s, bravo ahead of you" — not a spinner.
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

*Restructured mock-first on 2026-07-23 (superseded the hardware-first order below).* Still ordered for **earliest visible milestone** — the change is *which* milestone comes first. The old order opened with hardware (blink, an SSID in a WiFi list) to avoid front-loading "a week of pure Kotlin with nothing to look at." Mock-first keeps that guarantee a different way: the §6 mock node means a **real dot moving on a real map screen** lands in the first sitting or two, with the T-Beam still in its box. A running app beats a blinking LED as a first thing to look at.

**The rule that shapes the order:** everything except the BLE GATT path can be built against the mock node (§6). So the app comes first against the mock; hardware enters exactly where the mock can't follow — the BLE always-on layer and real radio/GNSS/power timing.

### Phase 00 — Mock node + dummy data · *one evening*
The §6 day-one task, now literally day one. ~200 lines (Node.js or Ktor) serving the **same WebSocket protocol** the real node will, from a synthetic session plus a replay mode. Golden vectors for the codec live here too. No hardware, no ESP-IDF. This is the highest-leverage code in the project — it makes every phase below buildable on a weekday evening with the board unplugged.

### Phase 01 — App against the mock: dot on the map · *one weekend*
Minimal Android app: connect to the mock's WebSocket, NMEA/records → **MapLibre map with accuracy ellipse**. **Hand-write the codec** (Kotlin now; C when hardware arrives). No schema, no generation. Get the dot on the map — entirely against the mock.

### Phase 02 — App against the mock: three phones, shared state · *two weekends*
Per-client cursors, message history, chat (one channel, text only), everyone seeing everyone — all driven by the mock replaying multiple clients. The multi-client *logic* (cursors, admission reasons, even a first cut of the scheduler) is built and flood-tested here, against fake data, before any silicon. Demoable, but not yet the *complete* thing — that needs the real node.

### Phase 03 — Hardware: real node, BLE for real · *two weekends* — **substantially done (2026-07-26)**

Done: toolchain, first flash, both I²C buses inventoried, PMU read, the GNSS rail identified and switched on, **real GNSS fixes** parsed into 32-byte records, LittleFS, SoftAP, BLE advertising **with a GATT service**, `proto 2` served over both transports, and the OLED showing status with no phone attached. Outstanding: a breakpoint in `app_main` over USB-JTAG, the app's BLE path verified against the node, and the BME280 read.

Now the board comes out. ESP-IDF toolchain up, **USB-JTAG debugger working with a real breakpoint**, LittleFS mounted, SoftAP with a fixed SSID + BLE advertising — the phone sees `lokalgrid` in its WiFi list and in nRF Connect. Then the two swaps the mock couldn't give you: **point the app's WebSocket at the real SoftAP**, and **build the BLE GATT path against the real board** (§6: BLE cannot be mocked). The app you already have lights up on real hardware serving real fixes.
One cheap addition while the board is on the bench: **the OLED shows something real** — SSID, client count, fix state, battery. Two hours of work, and it is the difference between a dev board with a blinking LED and a device you can put on a table and read.
**⚑ NATURAL STOPPING POINT.** Three phones on a shared map, served by the real T-Beam. This is a complete project. Stopping here is success.

### Phase 04 — Make it fight, and show the price · *two weekends*
Deficit round-robin, priority lanes, admission control, backlog interleaving — hardened against **real** airtime timing (the mock could exercise the logic; only hardware exercises the clock). Test by writing a client that deliberately floods. Most transferable engineering in the project.

Same phase, same data, one more step: the **airtime economy as a feature** — cost in ms shown *before* sending, per-client share of the hour, a queue you can cancel or promote to lane 0 (and see what that costs), and a name rather than a spinner when you are behind.

The board earns its keep here too (§1): **OLED status pages** cycled by the user button — roster, queue depth, duty used, hours left — and the **physical emergency button**, hold 2 s for a lane-0 alert with position, working whether or not any phone is attached. The IMU becomes the motion gate for logging and beacon interval.

### Phase 04b — Encryption, the device store, and the dead-drop · *two weekends*
Three things that only make sense together, and the phase where the walkie-talkie framing (§2, 2026-07-25) becomes real:

1. **Pairing and keys** — X25519 at pairing, six-digit confirmation on the node's OLED, per-pair secrets plus a node-issued group key for the shared channel.
2. **Room on the phone** — the durable archive of this user's messages and track, so history survives the node being switched off, reflashed or left behind.
3. **The dead-drop** — the node holds *sealed* messages addressed to a callsign that is not here yet, plus the stretch of track they missed, and hands them over on arrival. It schedules from `{addressee, length}` and never sees a body. Plus *last seen* from its own log ("charlie, 40 min ago, 300 m NE").

Wire additions are small: an addressee, a hold-until rule, and a sealed body. Everything else — cursors, backlog, queue reasons — already exists and works on ciphertext unchanged.

### Phase 05 — Fix the drift · *one weekend*
By now the codec, hand-written twice (C on the node, Kotlin in the app), has bitten you. *That* is when the schema and codegen arrive.

### Phase 06 — Optional: the link out
LoRa under the scheduler, position aggregation, duty-cycle ceiling, BLE presence layer. Only if there is somewhere to take it where WiFi range genuinely runs out.

Two extensions belong here because both need the radio first. **The power ladder**: serve everything → BLE only → beacon only → sleep, each rung at a stated battery threshold, announced with its reason and a coulomb-counter estimate of how long it lasts — beacon-only matters most, since a nearly flat node should still say where it is. **The booster role** (§1): a second board relaying over one LoRa hop, so coverage spans a ridge outdoors or a concrete floor indoors — sub-GHz goes through slabs that 2.4 GHz does not. A star of cells around one primary, never a mesh; the airtime cost of each relayed message is paid twice and must be shown.

### Phase 06b — Optional: time and the map, from the node
Two services that fall out of hardware already on the board. **Time**: the GNSS 1PPS pin makes the node a trustworthy clock with no internet and no cell, so phones take time from it — which is what makes timestamps comparable across the group and lets records logged without a fix have their timeline repaired. **The map**: PMTiles served from the node over the SoftAP, so a phone that has never been to this valley still gets a basemap from the thing on the table.

### Phase 07 — Optional: background sync polish and CLI
Foreground-service BLE sync hardening (One UI battery exemptions, resume-from-cursor soak tests); JVM CLI (Clikt + jSerialComm) over USB serial reusing the shared Kotlin codec. A browser client could also return here as an extra, never as the primary.

CLI surface sketch:
```
node devices | watch | pull --since | config get|set
node decode dump.bin --format gpx|csv|json
node replay dump.bin --speed 60x     # impersonate a node
node bench --duration 60s
```

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
| Node pushes history *and* answers the cursor | Every backfilled message arrives twice; a `LazyColumn` keyed on the node's id crashes outright | One source of backfill: the client states its cursor, the node answers. Never both (§3). Row keys are local and monotonic, so a repeating node renders oddly instead of killing the app. |
| A rail programmed but switched off | A peripheral that looks dead on correct pins — indistinguishable from a wiring error | Read the PMU's enable register before suspecting anything else. On this board ALDO4 (GNSS) powers up off, so the receiver is silent until it is switched on. |
| Creating an I²C bus twice | `ESP_ERR_INVALID_STATE` *and* the existing handle stops working, so an unrelated device (the display) goes dead | `board_scan_i2c()` reuses a bus that is already up. Re-scanning after switching a rail is a normal thing to want. |
| BLE advertisement over 31 bytes | NimBLE returns a bare `rejected: 4` and the node never advertises at all | Flags + name + tx power + a 128-bit UUID is 35 bytes. The service UUID goes in the **scan response**; Android merges it into the same `ScanRecord`, so service filtering still works. |
| Session joined at GAP connect | The phone connects, discovers the service, subscribes — and then nothing ever arrives. No error on either side; the link is up and mute | A notification to an unsubscribed characteristic is discarded, so a `hello` sent at connect is thrown away and the client never learns to state its cursors. Join the session on `BLE_GAP_EVENT_SUBSCRIBE`, once **both** characteristics are subscribed — which also guarantees the MTU is negotiated first. |
| A GATT step that fails silently | Same symptom as above from the app's side: connected forever, no data, no retry | Every step that can refuse — `requestMtu`, the CCCD write, a missing descriptor — must either carry on or **end the flow**. A callback that is simply never called is a hang the backoff cannot see. |
| 128-bit UUID byte order | The app filters for a service the node is advertising and never finds it, with no error anywhere | `BLE_UUID128_INIT` takes the sixteen bytes **little-endian** — the printed string reversed. Both sides carry the value with the order documented. |
| `httpd` socket ceiling | `httpd_start` returns `ESP_ERR_INVALID_ARG` and nothing explains it | `max_open_sockets` must be ≤ `CONFIG_LWIP_MAX_SOCKETS` − 3. The default ceiling of 10 caps the server at 7, below the 9-client cap; LWIP is at 16. |
| A socket that never retries | The app looks connected-in-progress forever and only a restart fixes it — fatal for a node you walk up to | The event flow **ends** on failure, the caller retries with bounded backoff, and a WiFi change forces an immediate attempt. Joining the node's AP after launch is the normal case, not the exception. |
| 16 KB page sizes | A prebuilt `.so` linked at 4 KB fails to load on Android 15+ 64-bit devices — the map dies, not the code that called it | Two checks, both needed: APK entries 16 KB-zipaligned (AGP 8.7 + `useLegacyPackaging = false`), and every shipped `.so` linked with `p_align ≥ 0x4000` — read it out of the ELF headers rather than trusting `zipalign -c`, which only inspects the zip. MapLibre is 16 KB-aligned from **11.8.8**; the app is 64-bit only. |

---

## 9. Resume here

**No blockers.** The band is settled (868, kept inside 865–867). The board runs the firmware in this repo and serves the app.

**What runs on hardware today** *(2026-07-26)*: boot inventories both I²C buses and names every chip that answers; the PMU is read before anything is written to it; ALDO4 is switched on and the **GNSS delivers real fixes** (rx 9 / tx 8, 9600) which become 32-byte records with position, satellites, HDOP, altitude, speed, course and the 2D/3D flag; `hello.mode` reports `gnss` or `synthetic` so no client can confuse a demo track with a position; LittleFS mounts; the OLED shows ssid, clients, BLE state, power source and GNSS state; BLE advertises with a GATT service; the SoftAP serves `proto 2` at `ws://192.168.4.1/ws`. Three hand-written codecs (JS, Kotlin, C) reproduce the same golden vectors byte for byte.

**What runs in the app**: five tabs, first-run setup, the phone's own GNSS behind "share my position" with the node's fix as a labelled fallback, per-client cursors and backlog resume, chat with node-assigned seq, staged-then-explicit config, sockets pinned to the WiFi network and reconnecting by themselves, two basemaps with an explicit offline download, and a shared component set for error/waiting/empty states.

**Next, in order:**

1. **Verify the app's BLE path against the node.** The firmware half is running and untested from the app — scan, connect, subscribe, and confirm records arrive in the §4 chunk framing with the negotiated MTU.
2. **A breakpoint in `app_main` over the built-in USB-JTAG** (`idf.py openocd` / `idf.py gdb`). Set it up before it is needed, not after three days of `printf`.
3. **Read the BME280** so `baro` and `tmp` carry values instead of sentinels — it is fitted on this unit.
4. **Room for the track** on the phone: the cursor survives a restart, the history does not.
5. **Two clients side by side, one deliberately flooding** — the Phase 02 pressure test, now possible against real hardware.

**Naming rule (2026-07-25):** clients are **callsigns** (`alpha`, `bravo`, `charlie`, … — NATO alphabet), never personal names, anywhere in code, tests, docs or wireframes.

### If this stops being fun

The board supports plenty of unrelated projects (satellite RX, a stratum-1 NTP server, vibration analysis, WiFi CSI sensing). Building one of those instead is a legitimate outcome, not an abandonment — but they are not a backlog and are not tracked here.

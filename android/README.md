# android — the Lokalgrid app

Kotlin + Jetpack Compose, MapLibre, OkHttp WebSocket, with the codec in a shared
plain-Kotlin module (PROJECT.md §6). Replaced the PWA plan on 2026-07-20 (§2).

Two Gradle modules:

- **`:protocol`** — plain Kotlin/JVM. The 32-byte track-record codec (§4), the
  Kotlin half of "one wire format, two hand-written codecs", plus `Control.kt` —
  the JSON control frames that carry the forward flow (chat up, echo + queue
  reason down). JVM-testable with no device: the codec cross-checks against the
  mock node's golden vectors, the control frames against strings copied verbatim
  out of `mock-node/src/server.js`.
- **`:app`** — the Compose app: five tabs over a MapLibre map, talking to the
  node over **either** a WebSocket (SoftAP) or **BLE GATT**, with the transport a
  runtime choice rather than a build one.

**64-bit only** (`arm64-v8a`, `x86_64`). 16 KB page support is a 64-bit feature
and the 32-bit MapLibre libraries are still linked at 4 KB alignment, so carrying
them would ship ~20 MB that cannot run on either target while muddying any
alignment check.

## Build & test

```
# codec only — no SDK/emulator needed:
./gradlew :protocol:test

# the app APK:
./gradlew :app:assembleDebug
```

`local.properties` (git-ignored) points at the Android SDK. The Gradle wrapper is
committed; first run downloads Gradle 8.11.1 + AGP.

## Run the app

**Against the mock:** start it (`cd ../mock-node && npm start`), then run `:app`
on an emulator — it connects to `ws://10.0.2.2:8787`, the emulator's alias for
the dev machine. On a real phone, set the URL in the setup step to your machine's
LAN IP. Expect a moving dot with an accuracy ring that breathes as the mock
varies HDOP, and a status line with age / sats / 2D-3D.

**Against the board, over WiFi:** join the node's `lokalgrid` AP and use
`ws://192.168.4.1/ws`. Sockets are pinned to the WiFi `Network` object
(`WifiBinding`), because Android marks an AP with no internet route unvalidated
and will silently route new sockets over mobile data instead — the socket then
goes nowhere while the phone cheerfully shows itself connected to `lokalgrid`.

**Against the board, over BLE** — the better path for everyday use: leave the
phone on whatever WiFi it is already on, tap the status bar → Link → step 3 →
*Scan for nodes*. The node's AP has no internet behind it, so joining it costs
you the internet; BLE carries the identical session and does not. The choice is
persisted, so it survives a restart.

## First run: splash → setup → app

Cold start shows the app's mark — a fix inside its accuracy rings, the §6 rule as
a logo — then a boot screen naming the node. Two mechanisms, because the platform
changed: Android 12+ draws the *system* splash from `windowSplashScreen*` theme
attributes plus the launcher icon, while 11 and below only have `windowBackground`.
Both are set, to the same colour and mark.

The boot screen holds until the **first connection attempt resolves**, capped at a
1200 ms grace — so it covers real work rather than a timer, and a node that is
down costs you a second and an error line, never the app.

First launch goes through four setup steps; every launch after goes straight to
Live. Config → **Open setup again** re-enters it. (Onboarding is a persisted flag,
so reinstalling does *not* bring it back — `adb shell pm clear dev.lokalgrid.app`
does.)

1. **What this is** — one node, everyone on one map.
2. **Permissions** — `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (+ `POST_NOTIFICATIONS`
   on 13+), each with the reason it is asked for, and an explicit *not asked for:
   location — never* row. `neverForLocation` makes that honest (§5).
3. **Battery** — the One UI "Deep sleeping apps" trap (§3). Detects the exemption
   state and opens the right settings screen. The app cannot prevent this, only
   notice it, and the step says so.
4. **The node** — the WebSocket URL, with emulator/LAN presets. Persisted, so the
   `10.0.2.2`-on-a-real-phone dead end is now a field instead of a mystery.

**No BLE is opened during setup** — the permissions are collected there, and the
connection itself is made from the Link screen when you ask for it. Location is
never used to find the node: `BLUETOOTH_SCAN` carries `neverForLocation`, and the
app never derives a position from a scan result. `ACCESS_FINE_LOCATION` is asked
for at the tap on "share my position", where the reason is on screen, and
refusing it leaves the app fully usable — the node's own fix goes instead, and is
labelled as the node's.

## The forward flow (Phase 02) — every tab sends something

Through Phase 01 the app only listened. Now each tab has an action, and each
action has a visible, node-authored answer:

| Tab | You do | The node answers |
|---|---|---|
| **Live** | share position · rename · reset track | peer fan-out or a skip reason; new roster; fresh track |
| **Map** | share position | your dot + everyone else's, each with its ring and age |
| **Chat** | send · send as emergency (lane 0) | `seq` echo, then live queue state until `relayed` |
| **Clients** | rename on the roster | roster + per-client airtime meters, duty used, queue depth |
| **Config** | stage edits, write explicitly | applied and refused, key by key, with reasons |
| **Link** (tap the status bar) | grant permissions, reconnect, change node | an ordered flow: permissions · wifi · ble · session |

The Chat bubble appears immediately as **pending** (an optimistic echo, not a
claim), becomes `seq N` when the node acknowledges it, then carries a live line
about the link out — `queued 56 s, bravo ahead of you` — until it is `relayed`.
Local delivery and LoRa relay are two different truths and are shown as two.

Config never writes as you type: edits stage locally and go as one patch when you
press the button (§6). `dutyPct` and `apIdleTimeoutS` are not editable and say
why — they are enforced in firmware, not offered as settings (§2).

Run `npm start -- --ghosts 2` in `mock-node/` to put synthetic peers on the map
with a single phone; `CAP=2 npm start` makes the node-full refusal visible.

## Resume, not restart

The app keeps a position cursor per node and states it on every connect, so
reopening after an hour fetches a delta instead of re-streaming everything. The
node answers with what it owes and what it lost; the recovered history draws as a
track line on the map, and the Live tab shows cursor, log range and any gap.

This is why the app is not gated behind a live connection: the Link screen (tap
the status bar) shows connection state as a flow, but every tab stays readable
with the node unreachable, and reconnecting resumes from the cursor.

## The BLE transport

`BleClient` emits the same `NodeClient.Event` stream as the WebSocket path, so
everything above it — cursors, backlog, chat, the whole UI — is transport-
agnostic and cannot drift between the two. Two characteristics, matching
`firmware/main/ble_gatt.c`:

```
control  6f6b616c-6772-6964-0000-000000000002   write + notify, JSON frames
data     6f6b616c-6772-6964-0000-000000000003   notify, records in §4 framing
```

Three things here are hard-won rather than obvious, and each has a trap row in
PROJECT.md §8:

- **Control frames are fragmented.** One leading byte says whether more follows.
  A Galaxy S22 answers a 517-byte MTU request with 256, and `stats` and `config`
  are both larger than the 253 bytes that leaves — "our JSON always fits the
  MTU" was a bet, and a handset called it. Fragments carry *bytes*, so they are
  joined and decoded once at the end: a UTF-8 sequence can be split across two.
- **Every GATT step that can refuse must either carry on or end the flow.** A
  callback that is simply never called is a hang the retry backoff cannot see,
  and it looks identical to a working link with a silent node.
- **Each connection attempt claims a token.** The retry loop starts the next
  attempt without waiting for the old one to tear down, and Android keeps
  delivering the old callback until its `BluetoothGatt` closes — so without one,
  a stale teardown clears the live link's characteristic and every send returns
  false with nothing wrong on the wire.

## Not here yet

- **The foreground sync service** — `POST_NOTIFICATIONS` is requested for it, but
  `SyncService` is still to come. The manifest deliberately does not declare a
  service class that doesn't exist yet.
- **Room** — the position cursor persists in SharedPreferences, keyed by node URL;
  the track itself is still in memory only. Room is what makes the phone the
  durable archive (§2), and it is the next substantial piece of app work.
- **Node-served PMTiles** (Phase 06b). Today there are two online basemaps —
  Streets and Dark — with an explicit, user-initiated offline download that shows
  the tile count and rough size *before* it starts.
- One UI battery-exemption onboarding is detected and explained, but cannot be
  fixed from inside the app; the soak testing behind it waits on the service.

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
- **`:app`** — the Compose app. Phase 01 = a live dot on a MapLibre map with its
  HDOP accuracy ring, fed by the mock node over a WebSocket.

## Build & test

```
# codec only — no SDK/emulator needed:
./gradlew :protocol:test

# the app APK:
./gradlew :app:assembleDebug
```

`local.properties` (git-ignored) points at the Android SDK. The Gradle wrapper is
committed; first run downloads Gradle 8.11.1 + AGP.

## Run the app (Phase 01)

1. Start the mock node: `cd ../mock-node && npm start`
2. Run `:app` on an emulator from Android Studio — it connects to
   `ws://10.0.2.2:8787` (the emulator's alias for the dev machine). For a real
   device, change the URL in `LiveViewModel` to the machine's LAN IP.
3. Expect a dot near the start coordinates, moving, with an accuracy ring that
   breathes as the mock varies HDOP, and a status card with age / sats / 2D-3D.

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

**No BLE is opened anywhere in this flow.** The permissions exist so the path is
built and testable; the GATT connection is Phase 03 against the real board and
cannot be mocked (§6). Config shows `ble link · phase 03 · needs the board`
rather than implying a link that isn't there.

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

## Not here yet

- **BLE** — permissions and onboarding exist; the GATT connection itself is
  developed against the real board in Phase 03 and cannot be mocked (§6).
- **The foreground sync service** — `POST_NOTIFICATIONS` is requested for it, but
  `SyncService` arrives with BLE. The manifest deliberately does not declare a
  service class that doesn't exist yet.
- **Real phone GPS** — "share my position" currently offers the node's own fix.
  Wiring the handset's location needs a runtime permission and is a separate step.
- **Room** — the position cursor persists in SharedPreferences, keyed by node URL;
  the track itself is still in memory only.
- Offline PMTiles basemap — Phase 01 uses the keyless MapLibre demo style.
- One UI battery-exemption onboarding (target: Galaxy S25) — before background sync.

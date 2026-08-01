# lokalgrid

**A shared field node.** One LilyGO T-Beam Supreme serves live position, a map
and chat to a small group of phones over its own WiFi and BLE — no internet, no
server, no cell coverage. LoRa is the link *out* when WiFi range runs out.

The interesting part is not the tracker. It is that **one radio serves up to nine
clients**, so airtime has to be arbitrated: priority lanes, deficit round-robin,
a 1% duty cycle enforced in firmware, and a queue whose state is shown as a
*reason* — "queued 56 s, bravo ahead of you" — never a spinner. That, and the
rule that **every rendered position carries its uncertainty**: an accuracy ring
from HDOP, dashed interpolated segments, an age label on a stale fix. Never a
crisp dot implying precision the GNSS did not deliver.

Personal hobby build. Success is staying interesting, not shipping.

---

## State of play

| | |
|---|---|
| **Phase 00** — mock node + golden vectors | done |
| **Phase 01** — app against the mock, dot on the map | done |
| **Phase 02** — multi-client: chat, forward flow, cursors | done, bar the two-client flood test |
| **Phase 03** — the board comes out, BLE for real | substantially done · **natural stopping point** |
| 04–07 — scheduler hardening, codegen, LoRa, background sync | later, optional |

The roadmap was **mock-first**: the whole app was built against a ~500-line mock
node speaking the real wire protocol, so the T-Beam stayed in its box until
Phase 03. Everything except the BLE GATT path could be developed with no
hardware — and that exception is exactly where the interesting bugs turned out
to live.

**Running on the board today**: both I²C buses inventoried at boot with every
answering chip named, the PMU read before anything is written to it, the GNSS
rail identified and switched on, **real GNSS fixes** parsed into 32-byte
records, LittleFS mounted, an OLED that makes the node readable with no phone
attached, BLE advertising with a GATT service, and the SoftAP serving `proto 2`
at `ws://192.168.4.1/ws`.

**Running in the app**: five tabs (Live · Map · Chat · Clients · Config), a live
dot with its accuracy ring on a MapLibre map, one shared chat channel with
lane-0 emergency, the phone's own GNSS behind "share my position" with the
node's fix as a labelled fallback, roster and rename, staged-then-explicit
config writes, per-client cursors with backlog resume, two basemaps with an
explicit offline download, sockets pinned to the WiFi network and reconnecting
by themselves, and a BLE transport carrying the same session.

**Not yet proven:** the BLE path end to end against a handset. It connects,
discovers, negotiates its MTU, subscribes and joins the session — the remaining
work is confirming records flow in the §4 chunk framing and stay flowing. A
breakpoint over the built-in USB-JTAG is also still unset.

| | | | |
|---|---|---|---|
| <img src="docs/screens/02-live.png" width="180"> | <img src="docs/screens/03-map.png" width="180"> | <img src="docs/screens/04-chat.png" width="180"> | <img src="docs/screens/07-link.png" width="180"> |
| **Live** — fix, uncertainty, people, cursor | **Map** — everyone, each with its ring and age | **Chat** — delivered *and* the link-out queue reason | **Link** — permissions · wifi · ble · session |

All twelve screens, and what changed between wireframe and build, are in
[§03 of the spec](lokalgrid-master-plan.html) · raw files in
[`docs/screens/`](docs/screens).

> Captured 2026-07-25 against the mock node. The Link and Config screens have
> changed since — they now report the BLE transport as a live state rather than
> as a phase that has not arrived — so these are a fair picture of the app's
> shape and a stale one of its wording. Recapture is pending a phone and a
> verified BLE session.

## Run it

**Against the mock**, no hardware needed:

```bash
# 1. the fake node — synthetic track at 1 Hz, plus two wandering peers
cd mock-node && npm install && npm start -- --ghosts 2

# 2. the app, on an emulator (10.0.2.2 is its alias for your machine)
cd android && ./gradlew :app:installDebug
```

On a real phone, set the URL in the app's setup step to your machine's LAN IP —
`10.0.2.2` only resolves on an emulator.

**Against the board:**

```bash
cd firmware && idf.py -p /dev/cu.usbmodem* flash
```

Then either join the node's `lokalgrid` WiFi and point the app at
`ws://192.168.4.1/ws`, or — better — leave the phone on its normal WiFi and use
**BLE**: tap the status bar → Link → *Scan for nodes*. The node's AP has no
internet behind it, so joining it costs you the internet; BLE carries the same
session and does not.

Reading the console on macOS has a trap worth knowing before it costs you an
hour: `cat /dev/cu.usbmodem*` returns **zero bytes**. The USB-Serial-JTAG console
only emits once the host CDC looks connected, and the `cu.` device deliberately
does not assert modem control. Open the port and assert DTR and RTS together.

Tests need neither device nor board:

```bash
cd mock-node && npm test        # codec, airtime queue, config, decimation
cd android  && ./gradlew :protocol:test   # the Kotlin half of the same codec
```

## Layout

| Path | What |
|---|---|
| `PROJECT.md` | **The decision record.** Binding constraints, rejected ideas and why, the wire format, the roadmap. Read this first. |
| `lokalgrid-master-plan.html` | **The reference spec** — hardware, app, and the protocol where they meet, plus what runs today. Current material only; start a coding session here. |
| `BUILDLOG.md` | Dated entry per session: what was tried, what surprised, what's next. Hobby projects die from lost context, not difficulty. |
| `mock-node/` | The fake node (Node.js). Serves the real protocol from a synthetic or replayed session. The highest-leverage code in the project. |
| `android/` | The client. `:protocol` is plain-Kotlin (codec + control frames, JVM-testable); `:app` is Compose + MapLibre. |
| `firmware/` | The node. ESP-IDF v5.3 + CMake: board bring-up, PMU, GNSS, OLED, LittleFS, and `proto 2` served over both a WebSocket and BLE GATT from one transport-agnostic session. |
| `schema/` | Empty until Phase 05, when hand-written codecs have drifted and codegen earns its place. |

## The wire

Positions are a **32-byte fixed-width little-endian record** — `offset = index * 32`,
so seeking is arithmetic rather than parsing. The layout never shrinks per build;
absent sensors write sentinels. Everything else (hello, roster, chat, queue state,
config, stats, refusals) is one JSON object per text frame, in both directions,
until protobuf arrives at Phase 05.

The codec is **hand-written three times on purpose** — JS in the mock, Kotlin in
the app, C on the node — cross-checked against a shared golden-vector fixture
that all three reproduce byte for byte. When they eventually disagree, that drift
bug is the trigger for codegen. Full field table in PROJECT.md §4; frame-by-frame
protocol in `mock-node/README.md`.

**One protocol, two transports.** `proto 2` lives once in
`firmware/main/session.c` behind a transport interface; the WebSocket and BLE
GATT adapters are thin. A client is a client whichever wire it arrived on — one
roster, one cap of nine, and the roster names each client's transport.

## Hardware

LilyGO T-Beam Supreme (ESP32-S3, SX1262 LoRa, L76K GNSS, AXP2101 PMU, 1.3" OLED,
BME280, PCF8563 RTC). Two rules that matter before anything else:

- **Never transmit without an antenna** — every hardware-damage path runs through
  the SX1262 PA. Nothing before Phase 06 transmits.
- **The band is 868**, and on this build it is kept inside **865–867 MHz**: that
  is India's delicensed ISM allocation, not the full EU 868 range, part of which
  is licensed here. Confirm `868M` on the silkscreen beside the SMA connector
  before the first transmission — a mismatched whip degrades the PA over weeks
  and reads exactly like a firmware bug.

Three things the datasheets got wrong for this unit, all found in the first five
minutes of running real firmware rather than by reading: **PSRAM is quad, not
octal** (octal boot-loops); there are **two I²C buses**, and the PMU and RTC are
on the second one; and **no QMI8658 is fitted**, so the motion gate falls back to
GNSS speed. The BME280 *is* fitted. The board runs from **USB, not a cell**, so
there is no battery percentage to report and the power ladder has nothing to
measure — both say so rather than inventing a number.

## Licence

**MIT** — see [`LICENSE`](LICENSE). Use it, fork it, build one for yourself.

Two notes on other people's code, since this touches a licence-sensitive corner:

- Meshtastic firmware is **GPL**. Reading it for architecture is fine; copying it
  is not, and this firmware is written from the protocol spec instead.
- The mock node ships a neutral default start position (Greenwich). Set `LAT0`
  and `LON0` to somewhere you know — a repo should not carry your home town.

Issues and pull requests are welcome, but this is a personal hobby build with no
roadmap promises and no support commitment.

## Conventions

- Client identities are **NATO callsigns** (`alpha`, `bravo`, `charlie`, …), never
  personal names — in code, tests, docs and wireframes alike.
- Failure states name the failure and offer the next action. Nothing is dropped
  silently; a refusal always carries a reason worth rendering.
- Commit the broken state with a log note. Stop at a milestone, not mid-refactor.

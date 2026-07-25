# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Binding context

@PROJECT.md

Three documents, three jobs — they are no longer mirrors of each other (rebuilt 2026-07-25):

- **`PROJECT.md`** — the *decision record*. Why each choice was made, what was rejected and why, which decisions supersede which. Sections 1–6 are binding constraints; section 9 is where work resumes. Read before re-proposing anything.
- **`lokalgrid-master-plan.html`** — the *reference spec*, in three parts: the hardware, the app, and the protocol where they meet, plus a state section listing what runs today and what is next. Only current, decided material — no history, no narrative.
- **`BUILDLOG.md`** — the *history*. Dated entry per session.

When a decision changes, record it in PROJECT.md **and** update whatever the spec asserts about it. The spec never carries rationale; PROJECT.md never carries step-by-step detail. The project is named **Lokalgrid**.

**Section 2 lists explicitly rejected features** (eFuse key burn, tamper detection, solar, OTA, enclosure, microSD archive, magnetometer heading, cloud backend). Do not re-propose them without reading the rationale first.

## Project state

The roadmap was restructured **mock-first** (2026-07-23, section 7): the app is built against a mock node before the hardware comes out. **Phase 00 is done** (`mock-node/`, `proto 2`, golden vectors) and Phase 01's app decodes the live stream. **Phase 02 is in progress:** the forward flow is wired on **all five tabs** and verified on an emulator against the mock — chat (send/emergency), position sharing with distance decimation, roster rename, staged-then-explicit config writes, node-computed airtime stats. The **first-run flow** also landed (splash → permissions → battery → node URL, re-enterable from Config), with BLE permissions declared per §5. **Per-client position cursors and backlog resume landed 2026-07-25**: positions get a node-assigned seq before broadcast, the client states its cursor on connect (`cursor {seq, posSeq}`), and the node answers with a `backlog` frame naming what it owes *and what aged out*. Catch-up streams in bounded chunks so a returning client never blocks the live ones. **The phone's own GPS landed 2026-07-26** (`LocationManager`, not the fused provider; location permission requested at the tap on "share my position", never at first run; the node's fix stays a labelled fallback). Room persistence for the track and the `SyncService` are what remain.

The app is **not gated behind a live connection** — the Link screen (tap the status bar) shows permissions · wifi · ble · session as a flow, but every tab stays readable offline and reconnecting resumes from the cursor. Do not add a connect gate; it would hide exactly the data the resume design exists to preserve.

**BLE is still Phase 03.** Permissions and onboarding exist; nothing opens a GATT connection. Do not add UI that implies a live BLE link before the board is in hand — the app says `ble link · phase 03 · needs the board` on purpose. No hardware until **Phase 03**, where the toolchain, USB-JTAG breakpoint, SoftAP + BLE advertising, and the un-mockable BLE GATT path all land. The firmware skeleton under `firmware/` (partitions, sdkconfig.defaults, LittleFS mount in `app_main`) is parked until then; ESP-IDF not yet installed on this machine. Repo: github.com/MKS-01/lokalgrid.

## Product framing — a modern walkie-talkie *(2026-07-25)*

Encrypted text for whoever is inside the building or within radio range of the node. **Metaphor, not voice** — media is still rejected. Two decisions follow, both recorded in PROJECT.md §2:

- **Messages are end to end encrypted**; the node relays and dead-drops ciphertext and schedules from `{addressee, length}` alone. Keys from X25519 at pairing, confirmed by a six-digit code on the node's OLED. This does *not* reverse the rejected eFuse/AES-GCM **log-at-rest** encryption — different threat model.
- **Each user's durable copy lives on their own phone**, in **Room (SQLite)**; the node keeps only a short backfill ring plus undelivered traffic. Addressed messages supersede the "no DMs" half of the 2026-07-20 chat decision; the shared channel is still exactly one, text only.
- Say plainly in the UI that metadata (who, when, how long) is visible to the node. No "secure" badge implying more.

The identity features, all Phase 04+: **the airtime economy made visible**, **the encrypted dead-drop**, **the power ladder**.

**Use the board** (PROJECT.md §1, 2026-07-25): every peripheral has a stated job or is explicitly parked. Headline ones — the **OLED** makes the node usable with zero phones connected (roster, queue, duty, hours left; pages cycled by the button), and the **user button** is a physical hold-2-s emergency that works with no phone attached. IMU = motion gate only (not tamper). microSD = PMTiles storage (**not** the rejected cold archive — different purpose). Magnetometer stays unused.

**Booster role:** a second board relays over **exactly one LoRa hop** — a star of cells around one primary, never a mesh, because one hop means no routing table, no loops, no duplicate suppression. Sub-GHz through concrete is what makes the indoor/per-floor case work. Every relayed message costs airtime on both hops and the UI must show it. The rule underneath: *the constraint is the interface* — duty cycle, battery, storage and range get rendered, never smoothed over.

## Stack — already decided, do not relitigate

- Firmware: **ESP-IDF v5.x + CMake directly**. Not Arduino, not PlatformIO.
- BLE: NimBLE. LoRa: RadioLib as an IDF component. FS: `esp_littlefs`.
- Client: **native Android app (Kotlin)**. The PWA/web-client plan was **removed 2026-07-20** (superseded decision in section 2) — background BLE sync via a foreground service is the feature a browser cannot do. Do not reintroduce a web client without reading that entry.
- Codec is **hand-written until Phase 05**. Do not introduce schema/codegen early — the drift bug is the point.
- Wire split: positions are **binary** 32-byte records; control (hello, roster, chat, queue state, refusals) is **one JSON object per text frame**, both directions. Protobuf replaces the JSON at Phase 05, not before.

## Hard invariants

- Track record is **32 bytes, fixed width, little-endian** (section 4). Absent sensors write sentinels; the layout never shrinks per build. `offset = index * 32` is the whole design.
- **Exactly one task owns the filesystem** (`storage_task`). Everything else requests via queue.
- SX1262 and microSD stay on separate SPI buses. Non-negotiable.
- LoRa duty cycle is **1%, enforced in firmware**, not a config setting. Same for the SoftAP idle timeout.
- **Never write TX code that can fire without an antenna flag set** — it damages the PA.
- Do not copy Meshtastic firmware source (GPL). Reading for architecture is fine; write from the protocol spec.
- BLE chunks carry **whole records only**, sized from the negotiated MTU at runtime.

## No real names anywhere in this repo

Clients are **callsigns from the NATO alphabet** (`alpha`, `bravo`, `charlie`, …) in code, tests, docs, wireframes and screenshots. Never a personal first name, even as sample data — an earlier pass used real-sounding names and they were replaced project-wide on 2026-07-25.

## UI rule that is easy to get wrong

Every rendered position carries its uncertainty — error ellipse from HDOP, dashed interpolated segments, age labels on stale fixes. Never a crisp dot. Queue state is shown as a reason ("queued 40 s, bravo ahead of you"), never a spinner.

## Working style for this repo

This is a hobby build. Success is staying interesting, not shipping. Reorder for the earliest visible milestone. Phase 03 is a complete project — stopping there is success. Prefer the smallest change that produces something visible on the phone.

Keep a dated entry in `BUILDLOG.md` per working session (`/build-log`) — lost context, not difficulty, is what kills this project.

## Open blocker

LoRa band (433 vs 868) is unconfirmed — read the silkscreen before buying an antenna. Does not block Phases 01–03, which use no LoRa.

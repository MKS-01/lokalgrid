# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Binding context

@PROJECT.md

`PROJECT.md` is a decision record, not documentation. Sections 1–6 are binding constraints; section 9 is where work resumes. `lokalgrid-master-plan.html` is the same content rendered (plus the §14 rethink record) — if you change one, change both. The project is named **Lokalgrid**.

**Section 2 lists explicitly rejected features** (eFuse key burn, tamper detection, solar, OTA, enclosure, microSD archive, magnetometer heading, cloud backend). Do not re-propose them without reading the rationale first.

## Project state

Firmware skeleton exists under `firmware/` (partitions, sdkconfig.defaults, LittleFS mount in `app_main`); ESP-IDF not yet installed on this machine. Next action is Phase 01 (section 7): toolchain install, USB-JTAG breakpoint working, SoftAP + BLE advertising up, phone sees the node. Repo: github.com/MKS-01/lokalgrid.

## Stack — already decided, do not relitigate

- Firmware: **ESP-IDF v5.x + CMake directly**. Not Arduino, not PlatformIO.
- BLE: NimBLE. LoRa: RadioLib as an IDF component. FS: `esp_littlefs`.
- Client: **native Android app (Kotlin)**. The PWA/web-client plan was **removed 2026-07-20** (superseded decision in section 2) — background BLE sync via a foreground service is the feature a browser cannot do. Do not reintroduce a web client without reading that entry.
- Codec is **hand-written until Phase 05**. Do not introduce schema/codegen early — the drift bug is the point.

## Hard invariants

- Track record is **32 bytes, fixed width, little-endian** (section 4). Absent sensors write sentinels; the layout never shrinks per build. `offset = index * 32` is the whole design.
- **Exactly one task owns the filesystem** (`storage_task`). Everything else requests via queue.
- SX1262 and microSD stay on separate SPI buses. Non-negotiable.
- LoRa duty cycle is **1%, enforced in firmware**, not a config setting. Same for the SoftAP idle timeout.
- **Never write TX code that can fire without an antenna flag set** — it damages the PA.
- Do not copy Meshtastic firmware source (GPL). Reading for architecture is fine; write from the protocol spec.
- BLE chunks carry **whole records only**, sized from the negotiated MTU at runtime.

## UI rule that is easy to get wrong

Every rendered position carries its uncertainty — error ellipse from HDOP, dashed interpolated segments, age labels on stale fixes. Never a crisp dot. Queue state is shown as a reason ("queued 40 s, ravi ahead of you"), never a spinner.

## Working style for this repo

This is a hobby build. Success is staying interesting, not shipping. Reorder for the earliest visible milestone. Phase 03 is a complete project — stopping there is success. Prefer the smallest change that produces something visible on the phone.

Keep a dated entry in `BUILDLOG.md` per working session (`/build-log`) — lost context, not difficulty, is what kills this project.

## Open blocker

LoRa band (433 vs 868) is unconfirmed — read the silkscreen before buying an antenna. Does not block Phases 01–03, which use no LoRa.

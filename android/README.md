# android — the Lokalgrid app

Phase 02+. Kotlin + Jetpack Compose, MapLibre Android, Room, foreground-service
BLE sync (PROJECT.md section 6). This replaced the PWA plan on 2026-07-20 —
see the superseding decision in PROJECT.md section 2.

Not scaffolded yet — deliberately. Phase 01 needs no app (nRF Connect and the
phone's WiFi list are the Phase 01 "client"). When Phase 02 starts:

- Android Studio project here; protocol/codec in a plain-Kotlin module so the
  CLI and JVM tests reuse it
- BLE setup and manifest permissions are already written down in PROJECT.md §5
- build the **mock node first** (see `../mock-node/`) — iterate against it,
  not against flash cycles
- target device: Samsung Galaxy S25; the One UI battery-exemption onboarding
  screen is not optional

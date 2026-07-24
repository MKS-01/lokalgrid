# android — the Lokalgrid app

Kotlin + Jetpack Compose, MapLibre, OkHttp WebSocket, with the codec in a shared
plain-Kotlin module (PROJECT.md §6). Replaced the PWA plan on 2026-07-20 (§2).

Two Gradle modules:

- **`:protocol`** — plain Kotlin/JVM. The 32-byte track-record codec (§4), the
  Kotlin half of "one wire format, two hand-written codecs". JVM-testable with no
  device: cross-checks against the mock node's golden vectors.
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

## Not here yet

- **BLE** — developed against the real board in Phase 03; cannot be mocked (§6).
- **Chat, multiple clients, cursors** — Phase 02.
- Offline PMTiles basemap — Phase 01 uses the keyless MapLibre demo style.
- One UI battery-exemption onboarding (target: Galaxy S25) — before background sync.

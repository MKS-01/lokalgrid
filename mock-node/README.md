# mock-node — the fake Lokalgrid

Phase 02, built on day one of app work. ~200 lines of Node.js (or Ktor) serving
the same WebSocket protocol as the firmware, replaying a captured session — so
the Android app can be developed with the hardware unplugged, and instrumented
tests run deterministically.

BLE cannot be mocked here — develop the GATT path against the real board,
everything else against this.

PROJECT.md section 6 calls this the highest-leverage code in the project.
Do not skip it.

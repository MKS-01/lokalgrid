# mock-node — the fake Lokalgrid

**Phase 00** (mock-first roadmap, PROJECT.md §7). Node.js serving the same
WebSocket protocol as the firmware — so the Android app develops with the
hardware unplugged, and tests run deterministically. The highest-leverage code
in the project (§6). BLE cannot be mocked here — that path is developed against
the real board in Phase 03.

## Run

```
npm install
npm start            # ws://localhost:8787, live synthetic track at 1 Hz
```

Env overrides: `PORT`, `HZ`, `LAT0`, `LON0`, `DEVICE_ID`.
For an Android emulator, the app reaches this via `ws://10.0.2.2:8787`.

## Protocol (Phase 00, deliberately tiny)

- on connect → one **text** frame: `{"type":"hello", proto, deviceId, recordBytes, hz, mode}`
- then → **binary** frames, each exactly 32 bytes = one track record (§4)
- client text `{"type":"reset"}` restarts the synthetic track

Backlog, cursors and chat are Phase 02 — a late joiner just sees the live stream.

## Codec + golden vectors

`src/record.js` is the JS half of the "one wire format, two hand-written codecs"
plan. `npm run golden` writes `golden/vectors.json` — the shared fixture the
Kotlin app (`android/protocol`) decodes in its own suite. If JS, Kotlin and (later)
C all reproduce the same hex, the format is one thing rather than three.

```
npm test             # round-trip, CRC, layout, golden vectors
npm run golden       # (re)generate golden/vectors.json
node src/decode-cli.js <hex>   # inspect a raw 32-byte record
```

> Day-one lesson already banked: a hand-rolled CRC-32 here was subtly wrong and
> disagreed with Java/zlib; the golden cross-check caught it immediately. We use
> Node's built-in `zlib.crc32` (== `esp_crc32_le`) so all three codecs agree.

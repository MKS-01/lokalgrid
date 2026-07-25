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

## Protocol (proto 2, deliberately tiny)

Positions come **down** as binary. Everything else is one JSON object per text
frame, and it runs **both ways** — that second direction is the *forward flow*.

Node → client (text):

| frame | payload | meaning |
|---|---|---|
| `hello` | `proto, deviceId, recordBytes, hz, mode, you:{id,name}, cap, duty` | who the node thinks you are, and its limits |
| `roster` | `clients:[{id,name,transport}], cap` | who is attached, resent on every join/leave |
| `chat` | `seq, from, name, text, epoch, lane, msgId?` | the one shared channel. `seq` is node-assigned |
| `queued` | `msgId, seq, reason, etaMs, ahead, lane, airtimeMs` | renderable queue state for the link out |
| `relayed` | `msgId, airtimeMs, lane` | it actually went out over LoRa |
| `peer` | `id, name, latE7, lonE7, hd, epoch, ageS, movedM` | where someone else says they are |
| `peerSkip` | `reason, movedM` | your position was decimated, and by how much |
| `config` | `values, locked, editable` | the config in force, and what it refuses to make settable |
| `configResult` | `applied, refused[{key,reason}]` | what a write did — both halves |
| `stats` | `uptimeS, queueDepth, dutyUsedPct, clients[], posOldest/Newest/Held` | airtime accounting + what the log holds |
| `backlog` | `from, to, count, lost, reason, oldest, newest, held` | what you are owed on resume, stated before it is sent |
| `backlogChunk` | `cursor, remaining` | progress while catching up |
| `backlogDone` | `cursor, live` | you are current; this cursor is authoritative |
| `rejected` | `scope, msgId?, reason` | admission control said no, with a reason |

Node → client (binary): exactly 32 bytes = one track record (§4).

Client → node (text): `send {msgId, text, lane?}` · `name {name}` ·
`pos {latE7, lonE7, hd, epoch}` · `config {patch}` · `cursor {seq, posSeq}` · `reset {}`.
Lane 2 is a normal message, lane 0 is emergency and pre-empts everything (§3).

Every one of those has a visible answer: a chat gets a node-assigned `seq`, a
position gets fanned out or skipped **with the distance that caused the skip**, a
config write comes back key-by-key. Nothing the client sends disappears quietly.

### What is not a setting

`config` ships `locked` alongside `values`: `dutyPct`, `apIdleTimeoutS` and
`maxClients` are reported so the UI can show them, and refused on write with the
reason (§2 — a config toggle gets left wrong eventually). The Config tab renders
the reason instead of a greyed-out box with no explanation.

### Callsigns, not names

Clients are auto-named from the NATO alphabet — `alpha`, `bravo`, `charlie`, … —
never personal names. A callsign is what a field radio hands out, it reads
unambiguously in a queue reason, and no real person's name ends up in the repo.
`name {name}` renames yourself; a clash is refused with a reason.

### Two deliveries, one message

Chat among phones on *this* node is local — WiFi/BLE fan-out, immediate, and the
`chat` echo carries the node-assigned `seq`. The scarce thing is the link **out**:
one SX1262, ~1 kbit/s, 1% duty cycle. So the same message is also queued for LoRa
relay in `src/relay.js` — priority lanes, deficit round-robin per client, and an
admission ceiling — and that queue is what produces
`"queued 56 s, bravo ahead of you"`. A reason with a name in it, never a spinner (§6).

At a real 1% duty cycle one 26-byte message locks the radio for ~56 s, which is
the honest number. `DUTY=0.25 npm start` loosens it for a demo; the app renders
whatever reason it is handed either way. `CAP=2 npm start` exercises the
node-full refusal path.

`npm start -- --ghosts 2` adds synthetic peers wandering near the start point, so
the Map tab has company with a single phone on the desk. They take roster slots
with transport `ghost`, and the app labels them — a mock peer must never be
passed off as a real client.

### Cursors and resume

Every position is appended to a log and given a monotonic seq *before* it is
broadcast, so a record a client missed can always be fetched again by number. On
connect the client states its own cursor — `cursor {seq, posSeq}`, chat and
positions separately — and the node answers with a `backlog` frame saying exactly
what it owes, **including how many records aged out of the log first**. A gap is
named, never drawn through as continuous track.

The catch-up then streams in bounded chunks (`BACKLOG_CHUNK`, default 60, one per
250 ms) so a client returning after an hour does not block the ones that are live
(§3). `HISTORY=3600` sets how many records the log keeps — one hour at 1 Hz;
lower it (`HISTORY=50`) to exercise the aged-out path.

## Codec + golden vectors

`src/record.js` is the JS half of the "one wire format, two hand-written codecs"
plan. `npm run golden` writes `golden/vectors.json` — the shared fixture the
Kotlin app (`android/protocol`) decodes in its own suite. If JS, Kotlin and (later)
C all reproduce the same hex, the format is one thing rather than three.

```
npm test             # codec: round-trip, CRC, layout, golden vectors
                     # forward flow: lanes, round-robin, duty cycle, refusals
npm run golden       # (re)generate golden/vectors.json
node src/decode-cli.js <hex>   # inspect a raw 32-byte record
```

> Day-one lesson already banked: a hand-rolled CRC-32 here was subtly wrong and
> disagreed with Java/zlib; the golden cross-check caught it immediately. We use
> Node's built-in `zlib.crc32` (== `esp_crc32_le`) so all three codecs agree.

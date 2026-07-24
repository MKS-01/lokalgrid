// Phase 00 mock node. One global synthetic track advances at `hz` and is
// broadcast as raw 32-byte binary frames to every connected client — so a late
// joiner sees the live stream from now (backlog/cursors are Phase 02, not here).
//
// Protocol, deliberately tiny:
//   • on connect  → one TEXT frame:  {"type":"hello", proto, deviceId, recordBytes, hz, mode}
//   • then         → BINARY frames, each exactly 32 bytes = one track record (§4)
//   • client TEXT {"type":"reset"} restarts the track from the start point
//
// This is not the BLE path — BLE cannot be mocked (§6). It stands in for the
// SoftAP WebSocket only. Run: `npm start`  (PORT, LAT0, LON0, HZ overridable).

import { WebSocketServer } from 'ws';
import { readFileSync } from 'node:fs';
import { TrackGenerator } from './track.js';
import { encodeRecord, RECORD_BYTES } from './record.js';

const PORT = Number(process.env.PORT ?? 8787);
const HZ = Number(process.env.HZ ?? 1);
const LAT0 = Number(process.env.LAT0 ?? 22.1018771); // Brilliant Public School, Bahatarai, Bilaspur
const LON0 = Number(process.env.LON0 ?? 82.191203);
const DEVICE_ID = Number(process.env.DEVICE_ID ?? 41000);

// --replay <file.ndjson>: broadcast pre-recorded field objects instead of
// generating. Loops when it reaches the end. Handy for deterministic demos.
const replayIdx = process.argv.indexOf('--replay');
const replayFile = replayIdx !== -1 ? process.argv[replayIdx + 1] : null;
const replay = replayFile
  ? readFileSync(replayFile, 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  : null;

const MODE = replay ? 'replay' : 'synthetic';
const wss = new WebSocketServer({ port: PORT });
const clients = new Set();

let gen = newGenerator();
let replayPos = 0;

function newGenerator() {
  return new TrackGenerator({ lat0: LAT0, lon0: LON0, seed: 1, deviceId: DEVICE_ID });
}

function nextFrame() {
  if (replay) {
    const rec = replay[replayPos % replay.length];
    replayPos++;
    return encodeRecord(rec);
  }
  return gen.nextEncoded();
}

wss.on('connection', (ws, req) => {
  clients.add(ws);
  const who = req.socket.remoteAddress;
  console.log(`[+] client ${who} — ${clients.size} connected`);
  ws.send(
    JSON.stringify({
      type: 'hello',
      proto: 1,
      deviceId: DEVICE_ID,
      recordBytes: RECORD_BYTES,
      hz: HZ,
      mode: MODE,
    })
  );
  ws.on('message', (data, isBinary) => {
    if (isBinary) return; // clients don't push records in Phase 00
    try {
      const msg = JSON.parse(data.toString());
      if (msg.type === 'reset' && !replay) {
        gen = newGenerator();
        console.log('[~] track reset');
      }
    } catch {
      /* ignore malformed control frames */
    }
  });
  ws.on('close', () => {
    clients.delete(ws);
    console.log(`[-] client ${who} — ${clients.size} connected`);
  });
  ws.on('error', () => clients.delete(ws));
});

// Drive the world clock regardless of who's listening, so all clients stay in
// lockstep on the same track — closer to "one node, everyone sees the same".
setInterval(() => {
  const frame = nextFrame();
  for (const ws of clients) {
    if (ws.readyState === ws.OPEN) ws.send(frame, { binary: true });
  }
}, 1000 / HZ);

console.log(
  `lokalgrid mock node — ws://localhost:${PORT}  mode=${MODE} hz=${HZ} start=${LAT0},${LON0}`
);

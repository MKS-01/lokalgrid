// Phase 00/02 mock node. One global synthetic track advances at `hz` and is
// broadcast as raw 32-byte binary frames to every connected client; on top of
// that sits the *forward flow* — chat from a phone, through the node, out to
// everyone, with the airtime queue made visible.
//
// Protocol, deliberately tiny:
//   node → client, TEXT (JSON, one object per frame):
//     hello    {proto, deviceId, recordBytes, hz, mode, you:{id,name}, cap, duty}
//     roster   {clients:[{id,name,transport}]}          — on every join/leave
//     chat     {seq, from, name, text, epoch, lane}     — the shared channel
//     queued   {msgId, seq, reason, etaMs, ahead, lane} — renderable queue state
//     relayed  {msgId, airtimeMs}                       — it actually went out
//     peer     {id, name, latE7, lonE7, hd, epoch}      — where someone else is
//     peerSkip {reason, movedM}                         — your position was decimated
//     config   {values, locked, editable}               — the config in force
//     stats    {uptimeS, queueDepth, dutyUsedPct, clients:[…]} — airtime accounting
//     rejected {msgId, reason, scope}                   — admission control, never silent
//   node → client, BINARY: exactly 32 bytes = one track record (§4)
//   client → node, TEXT:
//     send     {msgId, text, lane?}   lane 2 = message (default), 0 = emergency
//     name     {name}                 rename yourself in the roster
//     pos      {latE7, lonE7, hd, epoch}  share where *you* are
//     config   {patch:{…}}            staged edit, written explicitly (§6)
//     cursor   {seq}                  "I have everything up to seq" → backfill delta
//     reset    {}                     restart the synthetic track
//
// This is not the BLE path — BLE cannot be mocked (§6). It stands in for the
// SoftAP WebSocket only. Run: `npm start`  (PORT, LAT0, LON0, HZ overridable).

import { WebSocketServer } from 'ws';
import { readFileSync } from 'node:fs';
import { TrackGenerator } from './track.js';
import { encodeRecord, RECORD_BYTES } from './record.js';
import { ChatHub } from './chat.js';
import { RelayQueue, LANE, airtimeMs } from './relay.js';
import { NodeConfig, Stats, Peers } from './nodestate.js';

const PORT = Number(process.env.PORT ?? 8787);
const HZ = Number(process.env.HZ ?? 1);
const LAT0 = Number(process.env.LAT0 ?? 22.1018771); // Brilliant Public School, Bahatarai, Bilaspur
const LON0 = Number(process.env.LON0 ?? 82.191203);
const DEVICE_ID = Number(process.env.DEVICE_ID ?? 41000);
const CAP = Number(process.env.CAP ?? 9); // NimBLE ceiling (§3)

// Real firmware pins the duty cycle at 1% and does not expose it (§2). The mock
// exposes it *only* so a demo isn't a minute per message — the app must render
// whatever reason it is handed either way, so leave it at 0.01 when testing the
// honest case and raise it (DUTY=0.25) when you just want to watch the flow.
const DUTY = Number(process.env.DUTY ?? 0.01);

// --replay <file.ndjson>: broadcast pre-recorded field objects instead of
// generating. Loops when it reaches the end. Handy for deterministic demos.
const replayIdx = process.argv.indexOf('--replay');
const replayFile = replayIdx !== -1 ? process.argv[replayIdx + 1] : null;
const replay = replayFile
  ? readFileSync(replayFile, 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  : null;

// --ghosts N: N synthetic peers wandering near the start point, so the Map tab
// has company without a second phone. They occupy roster slots and are labelled
// transport "ghost" — the app must never show them as real clients.
const ghostIdx = process.argv.indexOf('--ghosts');
const GHOSTS = ghostIdx !== -1 ? Number(process.argv[ghostIdx + 1] ?? 2) : 0;

const MODE = replay ? 'replay' : 'synthetic';
const wss = new WebSocketServer({ port: PORT });
const sockets = new Map(); // ws -> client record from the hub
const hub = new ChatHub({ cap: CAP });
const relay = new RelayQueue({ duty: DUTY });
const config = new NodeConfig({ dutyPct: DUTY * 100, maxClients: CAP });
const stats = new Stats();
const peers = new Peers();

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

const send = (ws, obj) => {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
};

function broadcast(obj) {
  for (const ws of sockets.keys()) send(ws, obj);
}

function broadcastRoster() {
  broadcast({ type: 'roster', clients: hub.roster(), cap: CAP });
}

function broadcastConfig() {
  broadcast({ type: 'config', ...config.snapshot() });
}

function broadcastStats() {
  broadcast({
    type: 'stats',
    ...stats.snapshot({
      clients: hub.roster(),
      queueDepth: relay.depth,
      dutyPct: config.values.dutyPct,
    }),
  });
}

/** ws for a given client id, so per-client acks reach only their sender. */
function socketFor(clientId) {
  for (const [ws, c] of sockets) if (c.id === clientId) return ws;
  return null;
}

wss.on('connection', (ws, req) => {
  const who = req.socket.remoteAddress;
  const client = hub.join({ transport: 'wifi' });

  // Degradation is a reason, not a silent failure (§3): the 10th client is told
  // why, then closed.
  if (!client) {
    send(ws, {
      type: 'rejected',
      scope: 'connection',
      reason: `node full — ${CAP} of ${CAP} clients connected`,
    });
    ws.close(1013, 'node full');
    console.log(`[x] client ${who} refused — node full`);
    return;
  }

  sockets.set(ws, client);
  console.log(`[+] ${client.name} (id ${client.id}) from ${who} — ${sockets.size} connected`);
  relay.addClient(client.id);

  send(ws, {
    type: 'hello',
    proto: 2,
    deviceId: DEVICE_ID,
    recordBytes: RECORD_BYTES,
    hz: HZ,
    mode: MODE,
    you: { id: client.id, name: client.name },
    cap: CAP,
    duty: DUTY,
  });
  // Backfill the shared channel from what the node still holds, then the state
  // the other tabs render: config in force, everyone's last known position.
  for (const m of hub.since(0)) send(ws, { type: 'chat', ...m });
  send(ws, { type: 'config', ...config.snapshot() });
  for (const p of peers.list()) send(ws, { type: 'peer', ...p });
  broadcastRoster();
  broadcastStats();

  ws.on('message', (data, isBinary) => {
    if (isBinary) return; // clients don't push records — positions come from the node
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      return; // ignore malformed control frames
    }
    handle(ws, client, msg);
  });

  const gone = () => {
    if (!sockets.has(ws)) return;
    sockets.delete(ws);
    hub.leave(client.id);
    relay.dropClient(client.id);
    console.log(`[-] ${client.name} left — ${sockets.size} connected`);
    broadcastRoster();
  };
  ws.on('close', gone);
  ws.on('error', gone);
});

function handle(ws, client, msg) {
  switch (msg.type) {
    case 'send': {
      const bad = hub.validate(msg.text);
      if (bad) {
        send(ws, { type: 'rejected', scope: 'message', msgId: msg.msgId, reason: bad });
        return;
      }
      const lane = msg.lane === LANE.EMERGENCY ? LANE.EMERGENCY : LANE.MESSAGE;
      const now = Date.now();

      // Local fan-out is immediate — phones on this node share a WiFi/BLE link,
      // no radio involved. Everyone (including the sender) sees it at once, and
      // the node stamps the seq: it is authoritative about what exists (§3).
      const posted = hub.post({ clientId: client.id, text: msg.text, lane, now });
      broadcast({ type: 'chat', ...posted, msgId: msg.msgId });

      // The link *out* is the scarce one: same message, queued for LoRa relay.
      const msgId = msg.msgId ?? `s${posted.seq}`;
      const adm = relay.enqueue({ clientId: client.id, msgId, text: posted.text, lane, now, name: client.name });
      if (!adm.accepted) {
        send(ws, { type: 'rejected', scope: 'relay', msgId, seq: posted.seq, reason: adm.reason });
        return;
      }
      const r = relay.reasonFor(msgId, now);
      if (r) send(ws, { type: 'queued', msgId, seq: posted.seq, airtimeMs: adm.item.cost, ...r });
      return;
    }
    case 'name': {
      const name = String(msg.name ?? '').trim().slice(0, 16);
      if (!name) {
        send(ws, { type: 'rejected', scope: 'name', reason: 'a callsign cannot be empty' });
        return;
      }
      if (hub.roster().some((c) => c.id !== client.id && c.name === name)) {
        send(ws, { type: 'rejected', scope: 'name', reason: `"${name}" is already on this node` });
        return;
      }
      client.name = name;
      hub.clients.get(client.id).name = name;
      const mine = peers.last.get(client.id);
      if (mine) mine.name = name;
      broadcastRoster();
      return;
    }

    // Where *you* are. The node decimates by distance, not time (§3), and says
    // so when it skips one — a silent drop would look like a broken GPS.
    case 'pos': {
      const r = peers.offer({
        clientId: client.id,
        name: client.name,
        latE7: Number(msg.latE7),
        lonE7: Number(msg.lonE7),
        hd: Number(msg.hd) || 0,
        epoch: Number(msg.epoch) || Math.floor(Date.now() / 1000),
        decimationM: config.values.decimationM,
      });
      if (!r.accepted) {
        send(ws, { type: 'peerSkip', reason: r.reason, movedM: r.movedM ?? 0 });
        return;
      }
      broadcast({ type: 'peer', ...r.peer, ageS: 0 });
      return;
    }

    // Config is staged on the client and written explicitly (§6). The node is
    // the one that decides: partial application is normal, and every refused
    // key comes back with the reason it was refused.
    case 'config': {
      const { applied, refused } = config.set(msg.patch ?? {});
      if (Object.keys(applied).length) {
        console.log(`[=] ${client.name} wrote config: ${JSON.stringify(applied)}`);
        broadcastConfig();
      }
      send(ws, { type: 'configResult', applied, refused });
      return;
    }
    case 'cursor': {
      // The client is authoritative about what it has received (§3) — it asks,
      // the node never infers.
      for (const m of hub.since(Number(msg.seq) || 0)) send(ws, { type: 'chat', ...m });
      return;
    }
    case 'reset': {
      if (!replay) {
        gen = newGenerator();
        console.log(`[~] track reset by ${client.name}`);
      }
      return;
    }
    default:
      send(ws, { type: 'rejected', scope: 'protocol', reason: `unknown frame "${msg.type}"` });
  }
}

// Drive the world clock regardless of who's listening, so all clients stay in
// lockstep on the same track — closer to "one node, everyone sees the same".
setInterval(() => {
  const frame = nextFrame();
  for (const ws of sockets.keys()) {
    if (ws.readyState === ws.OPEN) ws.send(frame, { binary: true });
  }
}, 1000 / HZ);

// Airtime tick: release whatever the duty cycle now allows, then re-quote every
// message still waiting so the queue reasons on screen stay live and honest.
setInterval(() => {
  const now = Date.now();
  for (const item of relay.tick(now)) {
    const ws = socketFor(item.clientId);
    stats.record({ clientId: item.clientId, ms: item.cost, at: now });
    if (ws) send(ws, { type: 'relayed', msgId: item.msgId, airtimeMs: item.cost, lane: item.lane });
    console.log(
      `[>] relayed ${item.name}: "${item.text}" — ${item.cost} ms airtime, radio locked out ${Math.round(item.cost / DUTY / 1000)} s`
    );
  }
  for (const p of relay.plan(now)) {
    const ws = socketFor(p.clientId);
    const r = relay.reasonFor(p.msgId, now);
    if (ws && r) send(ws, { type: 'queued', msgId: p.msgId, airtimeMs: p.cost, ...r });
  }
}, 1000);

// Airtime accounting for the Clients tab. Cheap, so it just goes out on a timer
// rather than being asked for.
setInterval(broadcastStats, 5000);

// --ghosts: synthetic peers so the Map has company with one phone on the desk.
// They join the roster as transport "ghost" so the UI can never pass them off as
// real clients, and they move far enough to clear the decimation threshold.
const ghosts = [];
for (let i = 0; i < GHOSTS; i++) {
  const c = hub.join({ transport: 'ghost' });
  if (!c) break;
  relay.addClient(c.id);
  ghosts.push({
    client: c,
    lat: LAT0 + (i + 1) * 0.0012,
    lon: LON0 - (i + 1) * 0.0009,
    hdg: 40 + i * 90,
  });
}
if (ghosts.length) {
  setInterval(() => {
    const epoch = Math.floor(Date.now() / 1000);
    for (const g of ghosts) {
      g.hdg = (g.hdg + (Math.random() - 0.5) * 30 + 360) % 360;
      const step = 18; // metres per tick — comfortably past a 50 m decimation in ~3
      g.lat += (Math.cos((g.hdg * Math.PI) / 180) * step) / 111_320;
      g.lon += (Math.sin((g.hdg * Math.PI) / 180) * step) /
        (111_320 * Math.cos((g.lat * Math.PI) / 180));
      const r = peers.offer({
        clientId: g.client.id,
        name: g.client.name,
        latE7: Math.round(g.lat * 1e7),
        lonE7: Math.round(g.lon * 1e7),
        hd: 8 + Math.round(Math.random() * 20),
        epoch,
        decimationM: config.values.decimationM,
      });
      if (r.accepted) broadcast({ type: 'peer', ...r.peer, ageS: 0 });
    }
  }, 3000);
  broadcastRoster();
}

console.log(
  `lokalgrid mock node — ws://localhost:${PORT}  mode=${MODE} hz=${HZ} cap=${CAP} duty=${DUTY * 100}% start=${LAT0},${LON0}`
);
if (ghosts.length) console.log(`  ghosts: ${ghosts.map((g) => g.client.name).join(', ')}`);
console.log(
  `  a 26-char message costs ${airtimeMs('x'.repeat(26))} ms airtime → one every ~${Math.round(airtimeMs('x'.repeat(26)) / DUTY / 1000)} s at this duty cycle`
);

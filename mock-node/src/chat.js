// One shared channel, text only (§2) — plus the roster the channel needs so a
// queue reason can say a *name* ("bravo ahead of you") instead of a client id.
//
// Ownership rule (§3): the node is authoritative about what exists — it assigns
// the seq, the timestamp and the client id. The client is authoritative only
// about what it has received (its cursor). So the app's optimistic bubble is a
// *pending* thing until the node echoes it back with a seq; it never invents one.

export const MAX_TEXT = 200; //   bytes; longer is refused with a reason, not truncated
export const HISTORY = 200; //    messages kept for backfill on join

// Nine callsigns for nine client slots (the NimBLE ceiling, §3). Deliberately
// *not* personal names — a callsign is what a field radio hands out, it reads
// unambiguously in a queue reason, and nobody's actual name ends up in a repo.
const NAMES = ['alpha', 'bravo', 'charlie', 'delta', 'echo', 'foxtrot', 'golf', 'hotel', 'india'];

export class ChatHub {
  constructor({ cap = 9 } = {}) {
    this.cap = cap;
    this.clients = new Map(); // id -> {id, name, joinedAt, transport}
    this.log = []; // {seq, from, name, text, epoch, lane}
    this.seq = 0;
    this.nextId = 0;
  }

  /** null when the node is full — the caller refuses the connection with a reason. */
  join({ now = Date.now(), transport = 'wifi', name = null } = {}) {
    if (this.clients.size >= this.cap) return null;
    const id = this.nextId++ % 0xff;
    const client = {
      id,
      name: name || NAMES[id % NAMES.length],
      joinedAt: now,
      transport,
    };
    this.clients.set(id, client);
    return client;
  }

  leave(id) {
    this.clients.delete(id);
  }

  roster() {
    return [...this.clients.values()].map((c) => ({ id: c.id, name: c.name, transport: c.transport }));
  }

  /** Validation is admission control too — refuse with something renderable. */
  validate(text) {
    if (typeof text !== 'string') return 'message must be text';
    const trimmed = text.trim();
    if (!trimmed) return 'empty message';
    if (Buffer.byteLength(trimmed, 'utf8') > MAX_TEXT) {
      return `too long — ${MAX_TEXT} bytes max on this link`;
    }
    return null;
  }

  /**
   * Commit a message to the shared channel. Local delivery to phones attached to
   * this node is immediate (WiFi/BLE fan-out); the LoRa relay of the same message
   * is queued separately — see relay.js.
   */
  post({ clientId, text, lane = 2, now = Date.now() }) {
    const from = this.clients.get(clientId);
    const msg = {
      seq: ++this.seq,
      from: clientId,
      name: from ? from.name : 'node',
      text: text.trim(),
      epoch: Math.floor(now / 1000),
      lane,
    };
    this.log.push(msg);
    if (this.log.length > HISTORY) this.log.shift();
    return msg;
  }

  /** Backfill for a joining client, or a delta for one resuming from a cursor. */
  since(cursor = 0) {
    return this.log.filter((m) => m.seq > cursor);
  }
}

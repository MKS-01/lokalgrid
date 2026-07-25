// The position log, and the cursor arithmetic that makes resume work.
//
// The ownership rule (§3) is the whole design here: the **node** is
// authoritative about what exists — it assigns each record a monotonic seq and
// knows what it has evicted. The **client** is authoritative about what it has
// received — it names its cursor, and the node never guesses. Almost every
// duplicate/lost-record bug in this class of system violates that.
//
// Storage is a ring buffer standing in for LittleFS: fixed capacity, oldest
// evicted first. When a client asks for a cursor older than what survives, the
// node does not silently start elsewhere — it says how many records are gone,
// the same `data_lost` honesty the manifest rule requires (§4).

import { RECORD_BYTES } from './record.js';

export class PositionLog {
  /**
   * @param {object} o
   * @param {number} o.capacity how many records to keep. 3600 ≈ one hour at 1 Hz;
   *                            the real node holds about a week on internal flash.
   */
  constructor({ capacity = 3600 } = {}) {
    this.capacity = capacity;
    this.records = []; // [{seq, buf}], oldest first
    this.seq = 0; //      last assigned seq; seqs start at 1
    this.evicted = 0; //  how many have fallen off the back, ever
  }

  /** Append an encoded 32-byte record; returns its node-assigned seq. */
  append(buf) {
    if (buf.length !== RECORD_BYTES) {
      throw new RangeError(`record must be ${RECORD_BYTES} bytes, got ${buf.length}`);
    }
    const seq = ++this.seq;
    this.records.push({ seq, buf });
    while (this.records.length > this.capacity) {
      this.records.shift();
      this.evicted++;
    }
    return seq;
  }

  get oldestSeq() {
    return this.records.length ? this.records[0].seq : this.seq + 1;
  }

  get newestSeq() {
    return this.seq;
  }

  get count() {
    return this.records.length;
  }

  /**
   * What a client with `cursor` is owed, without sending anything yet.
   *
   * @returns {{from:number, to:number, count:number, lost:number, reason:string|null}}
   *   `lost` is how many records fell off the back before this cursor could be
   *   served — a gap the client must be told about rather than shown as a
   *   seamless track.
   */
  plan(cursor = 0) {
    const cur = Math.max(0, Math.min(cursor, this.seq));
    const first = Math.max(cur + 1, this.oldestSeq);
    const lost = Math.max(0, this.oldestSeq - (cur + 1));
    const count = Math.max(0, this.seq - first + 1);
    return {
      from: count ? first : this.seq + 1,
      to: this.seq,
      count,
      lost,
      reason: lost
        ? `${lost} position${lost === 1 ? '' : 's'} aged out of the node before you asked — the track has a gap`
        : null,
    };
  }

  /**
   * One bounded chunk from `cursor`. Bounded is the point: a client returning
   * after an hour must not block the two that are live (§3), so the caller
   * pumps this a chunk per tick instead of draining in one go.
   *
   * @returns {{records: Buffer[], cursor: number, remaining: number}}
   */
  chunk(cursor = 0, max = 64) {
    const cur = Math.max(0, Math.min(cursor, this.seq));
    const out = [];
    let next = cur;
    for (const r of this.records) {
      if (r.seq <= cur) continue;
      out.push(r.buf);
      next = r.seq;
      if (out.length >= max) break;
    }
    return { records: out, cursor: next, remaining: Math.max(0, this.seq - next) };
  }
}

/**
 * Per-client cursors. Deliberately a separate object from the log: the log is
 * what exists, this is what each client has acknowledged, and conflating the two
 * is exactly the bug the ownership rule exists to prevent.
 */
export class Cursors {
  constructor() {
    this.pos = new Map(); //     clientId -> last position seq the client has
    this.backlog = new Map(); // clientId -> {cursor, target} while catching up
  }

  add(clientId, cursor = 0) {
    this.pos.set(clientId, cursor);
  }

  drop(clientId) {
    this.pos.delete(clientId);
    this.backlog.delete(clientId);
  }

  get(clientId) {
    return this.pos.get(clientId) ?? 0;
  }

  /** The client says how far it got. Never moves backwards, never past what exists. */
  set(clientId, cursor, newestSeq) {
    const prev = this.get(clientId);
    this.pos.set(clientId, Math.max(prev, Math.min(cursor, newestSeq)));
    return this.pos.get(clientId);
  }

  /** Begin catching a client up to `target`; live records go out as normal. */
  startBacklog(clientId, from, target) {
    if (target > from) this.backlog.set(clientId, { cursor: from, target });
  }

  catchingUp(clientId) {
    return this.backlog.get(clientId) ?? null;
  }

  finishBacklog(clientId) {
    this.backlog.delete(clientId);
  }
}

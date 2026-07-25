// The forward flow's honest half: the airtime queue.
//
// Chat between phones attached to *this* node is local — WiFi/BLE fan-out, no
// radio involved, delivered immediately. What is scarce is the link *out*: one
// SX1262, ~1 kbit/s, hard 1% duty cycle (§1). So every outbound message is also
// enqueued for LoRa relay, and that queue is where "queued 40 s, bravo ahead of
// you" comes from (§6 UI rule: queue state is a reason, never a spinner).
//
// Pure logic, no sockets, no wall clock — every method takes `now` in ms. That
// keeps it testable and is the same shape `sched_task` will have in C (§3).

export const LANE = {
  EMERGENCY: 0, // pre-empts everything, ignores fairness
  POSITION: 1, // aggregated/decimated by the node itself
  MESSAGE: 2, // deficit round-robin across clients  ← chat lives here
  BULK: 3, // only when the budget is otherwise idle
};

// LoRa cost model. ~1 kbit/s payload at SF10/125 kHz, plus preamble + header.
// Deliberately not a config knob for duty: 1% is enforced, not configured (§2).
const HEADER_MS = 350;
const BYTE_MS = 8;

export const airtimeMs = (text) => HEADER_MS + BYTE_MS * Buffer.byteLength(text, 'utf8');

export class RelayQueue {
  /**
   * @param {object} o
   * @param {number} o.duty      duty cycle ceiling, 0..1. Real firmware pins this
   *                             at 0.01; the mock allows loosening it *only* so a
   *                             demo isn't 60 s per message. The app must not care.
   * @param {number} o.quantumMs deficit round-robin quantum per client per round
   * @param {number} o.perClientDepth admission ceiling — beyond this, refuse with a reason
   */
  constructor({ duty = 0.01, quantumMs = 600, perClientDepth = 6 } = {}) {
    this.duty = duty;
    this.quantumMs = quantumMs;
    this.perClientDepth = perClientDepth;
    this.emergency = []; // lane 0, one shared FIFO, fairness does not apply
    this.lanes = new Map(); // clientId -> {credit, queue:[item]}
    this.order = []; // round-robin order of clientIds, stable by join order
    this.radioFreeAt = 0; // ms — duty-cycle lockout ends here
  }

  addClient(id) {
    if (!this.lanes.has(id)) {
      this.lanes.set(id, { credit: 0, queue: [] });
      this.order.push(id);
    }
  }

  dropClient(id) {
    // Unused allocation decays rather than banks (§3) — the credit dies with the
    // lane, so an absent client cannot come back and flood.
    this.lanes.delete(id);
    this.order = this.order.filter((x) => x !== id);
    this.emergency = this.emergency.filter((m) => m.clientId !== id);
  }

  /**
   * Admission control. Returns {accepted:true, item} or {accepted:false, reason}.
   * Never silently drops — the reason is renderable text (§3).
   */
  enqueue({ clientId, msgId, text, lane = LANE.MESSAGE, now = 0, name = '?' }) {
    const lane0 = lane === LANE.EMERGENCY;
    const item = { clientId, msgId, text, lane, name, cost: airtimeMs(text), enqueuedAt: now };

    if (lane0) {
      this.emergency.push(item);
      return { accepted: true, item };
    }

    const l = this.lanes.get(clientId);
    if (!l) return { accepted: false, reason: 'not a known client — rejoin first' };
    if (l.queue.length >= this.perClientDepth) {
      return {
        accepted: false,
        reason: `your relay queue is full (${this.perClientDepth} waiting) — the link out is ${Math.round(this.duty * 100)}% duty-cycled`,
      };
    }
    l.queue.push(item);
    return { accepted: true, item };
  }

  /** Total messages waiting, all lanes. */
  get depth() {
    let n = this.emergency.length;
    for (const l of this.lanes.values()) n += l.queue.length;
    return n;
  }

  /**
   * Service order without mutating anything: the sequence this queue *would*
   * transmit in, given current credits. Emergency first, then deficit
   * round-robin over the message lane. Used for both `tick` and ETA reasons —
   * one function, so the reason shown can never disagree with what happens.
   */
  plan(now) {
    const out = [];
    let clock = Math.max(now, this.radioFreeAt);
    for (const item of this.emergency) {
      out.push({ ...item, txAt: clock });
      clock += item.cost / this.duty;
    }
    // Snapshot the DRR state so planning is side-effect free, then run exactly
    // the loop `#peekNext` runs.
    const sim = new Map();
    for (const [id, l] of this.lanes) sim.set(id, { credit: l.credit, i: 0, q: l.queue });
    for (let guard = 0; guard < 10_000; guard++) {
      let pending = false;
      let picked = null;
      for (const id of this.order) {
        const s = sim.get(id);
        if (!s || s.i >= s.q.length) continue;
        pending = true;
        if (s.credit >= s.q[s.i].cost) {
          picked = s;
          break;
        }
      }
      if (!pending) break;
      if (picked) {
        const item = picked.q[picked.i++];
        picked.credit -= item.cost;
        out.push({ ...item, txAt: clock });
        clock += item.cost / this.duty;
      } else {
        for (const s of sim.values()) if (s.i < s.q.length) s.credit += this.quantumMs;
      }
    }
    return out;
  }

  /**
   * Release everything whose transmit slot has arrived. Mutates: pops the sent
   * items, spends the credit, and pushes the duty-cycle lockout forward.
   */
  tick(now) {
    const sent = [];
    for (;;) {
      if (now < this.radioFreeAt) break;
      const next = this.#peekNext();
      if (!next) break;
      this.#pop(next);
      this.radioFreeAt = Math.max(now, this.radioFreeAt) + next.cost / this.duty;
      sent.push({ ...next, sentAt: now });
    }
    return sent;
  }

  /**
   * The renderable queue reason for one pending message: how long, and who is
   * ahead. "queued 40 s, bravo ahead of you" — a reason, not a spinner (§6).
   */
  reasonFor(msgId, now) {
    const plan = this.plan(now);
    const idx = plan.findIndex((p) => p.msgId === msgId);
    if (idx === -1) return null;
    const mine = plan[idx];
    const waitMs = Math.max(0, mine.txAt - now);
    const ahead = plan.slice(0, idx);
    const others = [...new Set(ahead.filter((a) => a.clientId !== mine.clientId).map((a) => a.name))];
    const wait = `${Math.round(waitMs / 1000)} s`;
    const now_ = waitMs < 1000;
    // When nothing is ahead of you and you are *still* waiting, the queue is not
    // the reason — the radio is. Say that, rather than an unexplained countdown.
    let reason;
    if (mine.lane === LANE.EMERGENCY) {
      if (ahead.length === 0) {
        reason = now_ ? 'emergency — going out now' : `emergency — first in line, ${wait} of duty-cycle lockout`;
      } else reason = `emergency — ${wait}, ${ahead.length} ahead in lane 0`;
    } else if (others.length === 0) {
      if (ahead.length === 0) reason = now_ ? 'going out now' : `queued ${wait} — radio duty-cycled`;
      else reason = `queued ${wait}, ${ahead.length} of yours ahead`;
    } else if (others.length === 1) {
      reason = `queued ${wait}, ${others[0]} ahead of you`;
    } else {
      reason = `queued ${wait}, ${others.slice(0, -1).join(', ')} and ${others.at(-1)} ahead of you`;
    }
    return { reason, etaMs: waitMs, ahead: ahead.length, lane: mine.lane };
  }

  // Deficit round-robin: hand every client with something waiting one quantum
  // per round until someone can afford their head-of-line message.
  #peekNext() {
    if (this.emergency.length) return this.emergency[0];
    for (let guard = 0; guard < 10_000; guard++) {
      let pending = false;
      for (const id of this.order) {
        const l = this.lanes.get(id);
        if (!l || !l.queue.length) continue;
        pending = true;
        if (l.credit >= l.queue[0].cost) return l.queue[0];
      }
      if (!pending) return null;
      for (const id of this.order) {
        const l = this.lanes.get(id);
        if (l && l.queue.length) l.credit += this.quantumMs;
      }
    }
    return null;
  }

  #pop(item) {
    if (item.lane === LANE.EMERGENCY) {
      this.emergency.shift();
      return;
    }
    const l = this.lanes.get(item.clientId);
    l.credit -= item.cost;
    l.queue.shift();
  }
}

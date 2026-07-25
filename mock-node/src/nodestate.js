// Node-owned state that the *other* tabs act on: config, airtime accounting,
// and where each client says it is.
//
// The ownership rule (§3) decides every argument here: the node is authoritative
// about what exists — the config in force, the airtime spent, the set of peer
// positions it has accepted. A client proposes; the node validates, decides, and
// tells everyone. It never trusts a client's copy of the truth.

const EARTH_R = 6_371_000;

// ---------------------------------------------------------------- config ----

/**
 * Some settings are not settings. Duty cycle and the AP idle timeout are
 * enforced in firmware precisely so they cannot be left wrong (§2) — they are
 * reported so the UI can show them, and refused on write with the reason why.
 */
export const LOCKED = {
  dutyPct: 'enforced in firmware, not a setting — a config toggle gets left wrong eventually (§2)',
  apIdleTimeoutS: 'enforced in firmware — an AP left up turns a week of runtime into a day (§8)',
  maxClients: 'build-time NimBLE ceiling (CONFIG_BT_NIMBLE_MAX_CONNECTIONS), not runtime',
};

const EDITABLE = {
  nodeName: { type: 'string', max: 16, note: 'SoftAP SSID and the name on the roster' },
  posIntervalS: { type: 'int', min: 1, max: 60, note: 'how often a position goes out' },
  decimationM: { type: 'int', min: 10, max: 500, note: 'positions decimate by distance, not time (§3)' },
  chatHistory: { type: 'int', min: 20, max: 500, note: 'messages kept on the node for backfill' },
};

export class NodeConfig {
  constructor(overrides = {}) {
    this.values = {
      nodeName: 'lokalgrid',
      posIntervalS: 1,
      decimationM: 50,
      chatHistory: 200,
      // reported, never writable:
      dutyPct: 1,
      apIdleTimeoutS: 300,
      maxClients: 9,
      ...overrides,
    };
  }

  /** Shape for the wire: values, plus which keys are locked and why. */
  snapshot() {
    return {
      values: { ...this.values },
      locked: LOCKED,
      editable: EDITABLE,
    };
  }

  /**
   * Apply a staged edit. Returns {applied:{}, refused:[{key,reason}]} — partial
   * success is normal and both halves are reported, so the UI can show exactly
   * which fields took and which did not.
   */
  set(patch = {}) {
    const applied = {};
    const refused = [];
    for (const [key, raw] of Object.entries(patch)) {
      if (LOCKED[key]) {
        refused.push({ key, reason: LOCKED[key] });
        continue;
      }
      const spec = EDITABLE[key];
      if (!spec) {
        refused.push({ key, reason: `unknown setting "${key}"` });
        continue;
      }
      if (spec.type === 'string') {
        const v = String(raw).trim();
        if (!v) refused.push({ key, reason: 'cannot be empty' });
        else if (v.length > spec.max) refused.push({ key, reason: `longer than ${spec.max} characters` });
        else applied[key] = v;
        continue;
      }
      const n = Number(raw);
      if (!Number.isFinite(n) || !Number.isInteger(n)) {
        refused.push({ key, reason: 'must be a whole number' });
      } else if (n < spec.min || n > spec.max) {
        refused.push({ key, reason: `outside ${spec.min}–${spec.max}` });
      } else {
        applied[key] = n;
      }
    }
    Object.assign(this.values, applied);
    return { applied, refused };
  }
}

// ----------------------------------------------------------------- stats ----

/** Airtime accounting, per client, over a trailing window. Feeds the meters. */
export class Stats {
  constructor({ windowMs = 3_600_000, startedAt = Date.now() } = {}) {
    this.windowMs = windowMs;
    this.startedAt = startedAt;
    this.spend = []; // {clientId, ms, at}
    this.messages = new Map(); // clientId -> count
  }

  record({ clientId, ms, at = Date.now() }) {
    this.spend.push({ clientId, ms, at });
    this.messages.set(clientId, (this.messages.get(clientId) ?? 0) + 1);
  }

  prune(now = Date.now()) {
    const cutoff = now - this.windowMs;
    while (this.spend.length && this.spend[0].at < cutoff) this.spend.shift();
  }

  /** Duty cycle actually used, and who used it. */
  snapshot({ now = Date.now(), clients = [], queueDepth = 0, dutyPct = 1 } = {}) {
    this.prune(now);
    const byClient = new Map();
    let total = 0;
    for (const s of this.spend) {
      byClient.set(s.clientId, (byClient.get(s.clientId) ?? 0) + s.ms);
      total += s.ms;
    }
    const elapsed = Math.max(1, Math.min(this.windowMs, now - this.startedAt));
    const actualPct = (total / elapsed) * 100; //           time actually spent transmitting
    const ofCeiling = dutyPct > 0 ? (actualPct / dutyPct) * 100 : 0; // 100% = duty cycle saturated
    return {
      uptimeS: Math.round((now - this.startedAt) / 1000),
      queueDepth,
      airtimeMs: total,
      dutyActualPct: Number(actualPct.toFixed(3)),
      dutyUsedPct: Number(ofCeiling.toFixed(1)),
      clients: clients.map((c) => ({
        id: c.id,
        name: c.name,
        airtimeMs: byClient.get(c.id) ?? 0,
        messages: this.messages.get(c.id) ?? 0,
        sharePct: total ? Math.round(((byClient.get(c.id) ?? 0) / total) * 100) : 0,
      })),
    };
  }
}

// ----------------------------------------------------------------- peers ----

/**
 * Where each client last said it was. Positions decimate by **distance, not
 * time** (§3) — a parked phone must not spend the link's budget repeating
 * itself — and a skipped position is answered with a reason, never dropped.
 */
export class Peers {
  constructor({ staleAfterS = 120 } = {}) {
    this.staleAfterS = staleAfterS;
    this.last = new Map(); // clientId -> peer
  }

  drop(clientId) {
    this.last.delete(clientId);
  }

  /**
   * @returns {{accepted:boolean, peer?:object, reason?:string, movedM?:number}}
   */
  offer({ clientId, name, latE7, lonE7, hd = 0, epoch, decimationM = 50 }) {
    if (!Number.isFinite(latE7) || !Number.isFinite(lonE7)) {
      return { accepted: false, reason: 'position is not a number' };
    }
    if (Math.abs(latE7) > 900_000_000 || Math.abs(lonE7) > 1_800_000_000) {
      return { accepted: false, reason: 'position out of range' };
    }
    const prev = this.last.get(clientId);
    const peer = { id: clientId, name, latE7, lonE7, hd, epoch };
    if (prev) {
      const moved = distanceM(prev.latE7 / 1e7, prev.lonE7 / 1e7, latE7 / 1e7, lonE7 / 1e7);
      const aged = epoch - prev.epoch;
      if (moved < decimationM && aged < this.staleAfterS) {
        return {
          accepted: false,
          movedM: Math.round(moved),
          reason: `${Math.round(moved)} m from your last shared position — decimating below ${decimationM} m`,
        };
      }
      peer.movedM = Math.round(moved);
    }
    this.last.set(clientId, peer);
    return { accepted: true, peer };
  }

  list(now = Math.floor(Date.now() / 1000)) {
    return [...this.last.values()].map((p) => ({ ...p, ageS: Math.max(0, now - p.epoch) }));
  }
}

export function distanceM(lat1, lon1, lat2, lon2) {
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return EARTH_R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

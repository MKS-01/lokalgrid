// Synthetic track generator. Produces a plausible walking track so the app has
// a dot that moves, an accuracy ellipse that breathes, and stale/2D fixes to
// render honestly (the §6 UI rule: uncertainty is always shown, never a crisp
// dot). Deterministic given a seed, so replays and tests are reproducible.

import { FLAG, packFlags, encodeRecord, BARO_ABSENT, TEMP_ABSENT } from './record.js';

const EARTH_R = 6_378_137; // metres, WGS-84 equatorial
const E7 = 1e7;

// Small deterministic PRNG (mulberry32) — no dependency, reproducible tracks.
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export class TrackGenerator {
  /**
   * @param {object} o
   * @param {number} o.lat0   start latitude, degrees
   * @param {number} o.lon0   start longitude, degrees
   * @param {number} o.epoch0 start GPS epoch, seconds
   * @param {number} o.seed   PRNG seed → reproducible track
   * @param {number} o.deviceId u16 node id (metadata, not in the record)
   */
  constructor({ lat0 = 51.4779, lon0 = -0.0015, epoch0, seed = 1, deviceId = 41000 } = {}) {
    this.lat = lat0;
    this.lon = lon0;
    this.epoch = epoch0 ?? Math.floor(Date.now() / 1000);
    this.deviceId = deviceId & 0xffff;
    this.rand = mulberry32(seed);
    this.headingDeg = this.rand() * 360; // initial course
    this.speedMs = 1.4; // brisk walk
    this.seq = 0;
    this.bat = 100;
    this.t = 0; // seconds elapsed, drives the HDOP breathing
  }

  /** Advance one second and return the next record's field object. */
  next() {
    // Wander the heading and speed a little so the track looks alive.
    this.headingDeg = (this.headingDeg + (this.rand() - 0.5) * 25 + 360) % 360;
    this.speedMs = clamp(this.speedMs + (this.rand() - 0.5) * 0.4, 0, 3.0);

    // Integrate position: metres north/east → degrees.
    const hdgRad = (this.headingDeg * Math.PI) / 180;
    const dNorth = Math.cos(hdgRad) * this.speedMs; // 1 s step
    const dEast = Math.sin(hdgRad) * this.speedMs;
    this.lat += (dNorth / EARTH_R) * (180 / Math.PI);
    this.lon += (dEast / (EARTH_R * Math.cos((this.lat * Math.PI) / 180))) * (180 / Math.PI);

    // HDOP breathes 0.8..3.4 so the ellipse visibly grows and shrinks.
    const hdop = 2.1 + 1.3 * Math.sin(this.t / 12);
    const sv = Math.round(11 - 4 * Math.max(0, Math.sin(this.t / 12))); // fewer sats when HDOP is high
    const moving = this.speedMs > 0.3;
    // Every ~40 s, drop to a 2D fix for a few seconds — exercises fix_3d clear.
    const is2d = this.t % 40 >= 37;

    this.bat = Math.max(0, this.bat - 0.002); // slow drain
    const first = this.seq === 0;

    const flags = packFlags({
      bits:
        FLAG.TIME_VALID |
        (is2d ? 0 : FLAG.FIX_3D) |
        (moving ? FLAG.MOTION : 0) |
        (first ? FLAG.TRIP_START : 0),
      zoneMask: 0,
      seq: this.seq & 0xffff,
    });

    const rec = {
      epoch: this.epoch + this.t,
      lat_e7: Math.round(this.lat * E7),
      lon_e7: Math.round(this.lon * E7),
      alt: is2d ? 0 : Math.round(265 + 5 * Math.sin(this.t / 30)), // gentle altitude wander
      baro: BARO_ABSENT, // pretend this unit has no BME280 — sentinel path
      spd: Math.round(this.speedMs * 100), // cm/s
      hdg: Math.round(this.headingDeg * 100), // centidegrees
      sv,
      hd: Math.round(hdop * 10), // HDOP ×10
      bat: Math.round(this.bat),
      tmp: 27, // °C
      flags,
    };

    this.seq++;
    this.t++;
    return rec;
  }

  /** Next record, already encoded to its 32-byte wire buffer. */
  nextEncoded() {
    return encodeRecord(this.next());
  }
}

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

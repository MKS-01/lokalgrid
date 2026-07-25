// Config, airtime accounting, and position decimation — the forward flow behind
// the Live, Map, Clients and Config tabs.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { NodeConfig, Stats, Peers, LOCKED, distanceM } from '../src/nodestate.js';

test('editable settings apply; out-of-range ones come back with the range', () => {
  const c = new NodeConfig();
  const { applied, refused } = c.set({ decimationM: 120, posIntervalS: 999 });
  assert.equal(applied.decimationM, 120);
  assert.equal(c.values.decimationM, 120);
  assert.equal(refused.length, 1);
  assert.equal(refused[0].key, 'posIntervalS');
  assert.match(refused[0].reason, /outside 1–60/);
  assert.equal(c.values.posIntervalS, 1, 'a refused key must not be half-applied');
});

test('the duty cycle is not a setting, and says why when you try', () => {
  const c = new NodeConfig();
  const { applied, refused } = c.set({ dutyPct: 50 });
  assert.deepEqual(applied, {});
  assert.equal(c.values.dutyPct, 1);
  assert.match(refused[0].reason, /enforced in firmware/);
  // Same for the AP idle timeout and the build-time client ceiling.
  assert.ok(LOCKED.apIdleTimeoutS && LOCKED.maxClients);
});

test('a partial write is normal: good keys land, bad keys are named', () => {
  const c = new NodeConfig();
  const { applied, refused } = c.set({ nodeName: 'ridge-node', chatHistory: 5, nope: 1 });
  assert.equal(applied.nodeName, 'ridge-node');
  assert.deepEqual(refused.map((r) => r.key).sort(), ['chatHistory', 'nope']);
});

test('airtime is attributed per client and measured against the ceiling', () => {
  const s = new Stats({ startedAt: 0 });
  s.record({ clientId: 0, ms: 600, at: 1000 });
  s.record({ clientId: 1, ms: 400, at: 2000 });
  const snap = s.snapshot({
    now: 100_000,
    clients: [{ id: 0, name: 'alpha' }, { id: 1, name: 'bravo' }],
    dutyPct: 1,
  });
  assert.equal(snap.airtimeMs, 1000);
  assert.equal(snap.clients[0].sharePct, 60);
  assert.equal(snap.clients[1].sharePct, 40);
  // 1000 ms out of 100 s is 1% — exactly the ceiling, so the budget is spent.
  assert.equal(snap.dutyActualPct, 1);
  assert.equal(snap.dutyUsedPct, 100);
});

test('airtime older than the window stops counting', () => {
  const s = new Stats({ windowMs: 1000, startedAt: 0 });
  s.record({ clientId: 0, ms: 500, at: 0 });
  const snap = s.snapshot({ now: 5000, clients: [{ id: 0, name: 'alpha' }] });
  assert.equal(snap.airtimeMs, 0);
});

test('positions decimate by distance, not time, and the skip has a reason', () => {
  const p = new Peers();
  const base = { clientId: 0, name: 'alpha', latE7: 221_018_771, lonE7: 821_912_030, epoch: 1000 };
  assert.equal(p.offer(base).accepted, true);

  // ~11 m north: inside the 50 m threshold, so it is skipped *with* a reason.
  const small = p.offer({ ...base, latE7: base.latE7 + 1000, epoch: 1010 });
  assert.equal(small.accepted, false);
  assert.match(small.reason, /decimating below 50 m/);
  assert.ok(small.movedM < 50);

  // ~111 m north: past the threshold, accepted.
  const big = p.offer({ ...base, latE7: base.latE7 + 10_000, epoch: 1020 });
  assert.equal(big.accepted, true);
  assert.ok(big.peer.movedM > 100);
});

test('a parked client still reports eventually, so it does not look lost', () => {
  const p = new Peers({ staleAfterS: 60 });
  const base = { clientId: 0, name: 'alpha', latE7: 221_018_771, lonE7: 821_912_030, epoch: 1000 };
  p.offer(base);
  assert.equal(p.offer({ ...base, epoch: 1030 }).accepted, false, 'still fresh: skip');
  assert.equal(p.offer({ ...base, epoch: 1100 }).accepted, true, 'gone stale: send it anyway');
});

test('nonsense positions are refused rather than plotted', () => {
  const p = new Peers();
  assert.equal(p.offer({ clientId: 0, latE7: NaN, lonE7: 0, epoch: 1 }).accepted, false);
  assert.match(p.offer({ clientId: 0, latE7: 999_000_000, lonE7: 0, epoch: 1 }).reason, /out of range/);
});

test('distance is the real thing, not a flat approximation', () => {
  // One degree of latitude is ~111.2 km anywhere.
  assert.ok(Math.abs(distanceM(0, 0, 1, 0) - 111_195) < 500);
  assert.equal(Math.round(distanceM(22.1, 82.19, 22.1, 82.19)), 0);
});

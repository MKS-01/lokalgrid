// Per-client position cursors and backlog resume — the ownership rule (§3) made
// executable: the node owns what exists, each client owns what it has received,
// and neither infers the other's state.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PositionLog, Cursors } from '../src/history.js';
import { TrackGenerator } from '../src/track.js';

const fill = (log, n, seed = 3) => {
  const gen = new TrackGenerator({ epoch0: 1_800_000_000, seed });
  for (let i = 0; i < n; i++) log.append(gen.nextEncoded());
  return log;
};

test('the node assigns every record a monotonic seq, starting at 1', () => {
  const log = new PositionLog();
  const gen = new TrackGenerator({ epoch0: 1_800_000_000, seed: 1 });
  assert.equal(log.append(gen.nextEncoded()), 1);
  assert.equal(log.append(gen.nextEncoded()), 2);
  assert.equal(log.newestSeq, 2);
  assert.equal(log.oldestSeq, 1);
});

test('a record must be exactly 32 bytes to enter the log', () => {
  const log = new PositionLog();
  assert.throws(() => log.append(Buffer.alloc(31)), RangeError);
});

test('a fresh client is owed everything the node still holds', () => {
  const log = fill(new PositionLog(), 100);
  const plan = log.plan(0);
  assert.equal(plan.from, 1);
  assert.equal(plan.to, 100);
  assert.equal(plan.count, 100);
  assert.equal(plan.lost, 0);
  assert.equal(plan.reason, null);
});

test('a caught-up client is owed nothing, and is not told to start over', () => {
  const log = fill(new PositionLog(), 50);
  const plan = log.plan(50);
  assert.equal(plan.count, 0);
  assert.equal(plan.lost, 0);
});

test('a returning client gets only its delta', () => {
  const log = fill(new PositionLog(), 500);
  const plan = log.plan(480);
  assert.equal(plan.from, 481);
  assert.equal(plan.count, 20);
});

test('a cursor ahead of the node is clamped, never negative work', () => {
  const log = fill(new PositionLog(), 10);
  const plan = log.plan(9999);
  assert.equal(plan.count, 0);
  assert.equal(plan.lost, 0);
});

test('records that aged out are reported as a gap, not skipped silently', () => {
  const log = fill(new PositionLog({ capacity: 100 }), 250);
  assert.equal(log.oldestSeq, 151);
  // This client last saw seq 20; 130 records vanished behind it.
  const plan = log.plan(20);
  assert.equal(plan.lost, 130);
  assert.equal(plan.from, 151);
  assert.equal(plan.count, 100);
  assert.match(plan.reason, /aged out .* the track has a gap/);
});

test('backlog is served in bounded chunks, so nobody blocks the live clients', () => {
  const log = fill(new PositionLog(), 200);
  let cursor = 0;
  let chunks = 0;
  let total = 0;
  for (;;) {
    const c = log.chunk(cursor, 60);
    if (!c.records.length) break;
    assert.ok(c.records.length <= 60, 'a chunk must respect its bound');
    total += c.records.length;
    cursor = c.cursor;
    chunks++;
    if (c.remaining === 0) break;
  }
  assert.equal(total, 200);
  assert.equal(cursor, 200);
  assert.equal(chunks, 4);
});

test('chunks resume exactly where the last one stopped — no gap, no repeat', () => {
  const log = fill(new PositionLog(), 25);
  const first = log.chunk(0, 10);
  const second = log.chunk(first.cursor, 10);
  assert.equal(first.cursor, 10);
  assert.equal(second.cursor, 20);
  assert.equal(second.remaining, 5);
  // Byte-identical to what a single drain would have produced.
  const whole = log.chunk(0, 100).records;
  assert.deepEqual([...first.records, ...second.records], whole.slice(0, 20));
});

test('a cursor never moves backwards, and never past what exists', () => {
  const cursors = new Cursors();
  cursors.add(1, 0);
  assert.equal(cursors.set(1, 40, 100), 40);
  assert.equal(cursors.set(1, 12, 100), 40, 'a late ack must not rewind the cursor');
  assert.equal(cursors.set(1, 500, 100), 100, 'clamped to what the node has');
});

test('cursors are per client — one catching up does not move another', () => {
  const cursors = new Cursors();
  cursors.add(1, 0);
  cursors.add(2, 0);
  cursors.set(1, 90, 100);
  assert.equal(cursors.get(2), 0);
  cursors.startBacklog(2, 0, 100);
  assert.deepEqual(cursors.catchingUp(2), { cursor: 0, target: 100 });
  assert.equal(cursors.catchingUp(1), null);
  cursors.drop(2);
  assert.equal(cursors.catchingUp(2), null);
  assert.equal(cursors.get(1), 90, 'a departing client takes only its own state');
});

test('no backlog is started when there is nothing to catch up on', () => {
  const cursors = new Cursors();
  cursors.add(1, 100);
  cursors.startBacklog(1, 100, 100);
  assert.equal(cursors.catchingUp(1), null);
});

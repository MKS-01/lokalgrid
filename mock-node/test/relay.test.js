// Forward-flow tests: the airtime queue and the shared channel. No sockets and
// no wall clock — `now` is passed in, so the duty cycle is exercised in a few
// microseconds instead of a few minutes.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RelayQueue, LANE, airtimeMs } from '../src/relay.js';
import { ChatHub, MAX_TEXT } from '../src/chat.js';

const q = (opts = {}) => {
  const r = new RelayQueue({ duty: 0.01, quantumMs: 600, ...opts });
  r.addClient(0);
  r.addClient(1);
  return r;
};

test('a lone message goes out immediately, then locks the radio for 99× its airtime', () => {
  const r = q();
  const text = 'reached the ridge';
  r.enqueue({ clientId: 0, msgId: 'a', text, now: 0, name: 'bravo' });
  const sent = r.tick(0);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].msgId, 'a');
  // 1% duty: cost on air, 100× cost before the next transmit may start.
  assert.equal(r.radioFreeAt, airtimeMs(text) / 0.01);
  assert.equal(r.tick(1000).length, 0, 'duty cycle must hold the radio down');
});

test('a queued message names who is ahead of you', () => {
  const r = q();
  r.enqueue({ clientId: 0, msgId: 'a', text: 'first', now: 0, name: 'bravo' });
  r.enqueue({ clientId: 1, msgId: 'b', text: 'second', now: 0, name: 'alpha' });
  const mine = r.reasonFor('b', 0);
  assert.match(mine.reason, /^queued \d+ s, bravo ahead of you$/);
  assert.ok(mine.etaMs > 0);
  assert.equal(mine.ahead, 1);
});

test('the reason the sender sees is the order the queue actually transmits in', () => {
  const r = q();
  r.enqueue({ clientId: 0, msgId: 'a', text: 'aaa', now: 0, name: 'bravo' });
  r.enqueue({ clientId: 1, msgId: 'b', text: 'bbb', now: 0, name: 'alpha' });
  r.enqueue({ clientId: 0, msgId: 'c', text: 'ccc', now: 0, name: 'bravo' });
  const planned = r.plan(0).map((p) => p.msgId);
  const sent = [];
  for (let t = 0; t <= 200_000 && sent.length < 3; t += 500) {
    for (const s of r.tick(t)) sent.push(s.msgId);
  }
  assert.deepEqual(sent, planned);
});

test('one client flooding does not starve another — round-robin interleaves', () => {
  const r = q();
  for (let i = 0; i < 5; i++) {
    r.enqueue({ clientId: 0, msgId: `flood${i}`, text: 'spam spam spam', now: 0, name: 'bravo' });
  }
  r.enqueue({ clientId: 1, msgId: 'quiet', text: 'water source dry', now: 0, name: 'alpha' });
  const order = r.plan(0).map((p) => p.msgId);
  // alpha sent one message last; it must not sit behind all five of bravo's.
  assert.ok(order.indexOf('quiet') <= 1, `expected early service, got ${order.join(',')}`);
});

test('emergency pre-empts everything already waiting', () => {
  const r = q();
  r.enqueue({ clientId: 0, msgId: 'a', text: 'chatter', now: 0, name: 'bravo' });
  r.enqueue({ clientId: 0, msgId: 'b', text: 'more chatter', now: 0, name: 'bravo' });
  r.enqueue({ clientId: 1, msgId: 'sos', text: 'leg broken, need help', lane: LANE.EMERGENCY, now: 0, name: 'alpha' });
  assert.equal(r.plan(0)[0].msgId, 'sos');
  assert.equal(r.reasonFor('sos', 0).reason, 'emergency — going out now');
});

test('a reason never claims "now" while the radio is locked out', () => {
  const r = q();
  r.enqueue({ clientId: 0, msgId: 'first', text: 'opening transmission', now: 0, name: 'bravo' });
  r.tick(0); // spends the budget; radio is now down for 99× the airtime
  r.enqueue({ clientId: 1, msgId: 'sos', text: 'need help', lane: LANE.EMERGENCY, now: 1000, name: 'alpha' });
  const sos = r.reasonFor('sos', 1000);
  assert.ok(sos.etaMs > 1000);
  // Nothing is ahead of it — so the reason must name the duty cycle, not imply a queue.
  assert.match(sos.reason, /^emergency — first in line, \d+ s of duty-cycle lockout$/);
});

test('a full per-client queue is refused with a reason, never silently dropped', () => {
  const r = q({ perClientDepth: 2 });
  assert.equal(r.enqueue({ clientId: 0, msgId: '1', text: 'one', now: 0 }).accepted, true);
  assert.equal(r.enqueue({ clientId: 0, msgId: '2', text: 'two', now: 0 }).accepted, true);
  const third = r.enqueue({ clientId: 0, msgId: '3', text: 'three', now: 0 });
  assert.equal(third.accepted, false);
  assert.match(third.reason, /queue is full/);
  // …and the other client is unaffected by their neighbour's backlog.
  assert.equal(r.enqueue({ clientId: 1, msgId: '4', text: 'four', now: 0 }).accepted, true);
});

test('a departing client takes its credit and its backlog with it', () => {
  const r = q();
  r.enqueue({ clientId: 0, msgId: 'a', text: 'gone soon', now: 0, name: 'bravo' });
  r.dropClient(0);
  assert.equal(r.depth, 0);
  assert.equal(r.reasonFor('a', 0), null);
});

test('the node assigns seq and name; the client only supplies text', () => {
  const hub = new ChatHub({ cap: 2 });
  const a = hub.join({ now: 0 });
  const b = hub.join({ now: 0 });
  assert.equal(hub.join({ now: 0 }), null, 'third client past a cap of 2 is refused');

  const m1 = hub.post({ clientId: a.id, text: '  reached the ridge  ', now: 1_800_000_000_000 });
  const m2 = hub.post({ clientId: b.id, text: 'copy', now: 1_800_000_001_000 });
  assert.equal(m1.seq, 1);
  assert.equal(m2.seq, 2);
  assert.equal(m1.text, 'reached the ridge', 'trimmed, not truncated');
  assert.equal(m1.name, a.name);
  assert.equal(m1.epoch, 1_800_000_000);
});

test('backfill is a delta from the client cursor', () => {
  const hub = new ChatHub();
  const a = hub.join({});
  for (let i = 0; i < 5; i++) hub.post({ clientId: a.id, text: `m${i}` });
  assert.equal(hub.since(0).length, 5);
  assert.deepEqual(hub.since(3).map((m) => m.text), ['m3', 'm4']);
});

test('empty and oversized messages are refused with a reason', () => {
  const hub = new ChatHub();
  assert.match(hub.validate('   '), /empty/);
  assert.match(hub.validate('x'.repeat(MAX_TEXT + 1)), /too long/);
  assert.equal(hub.validate('fine'), null);
});

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const logic = require('../src/pkjs/logic.js');

function memoryStorage() {
  const items = new Map();
  return {
    getItem: (k) => (items.has(k) ? items.get(k) : null),
    setItem: (k, v) => items.set(k, String(v)),
    removeItem: (k) => items.delete(k),
    items,
  };
}

test('server addresses are tidied, and anything that isn\'t one is refused', () => {
  assert.equal(logic.normaliseServer('health.example.com'), 'https://health.example.com');
  assert.equal(logic.normaliseServer('  https://health.example.com/// '), 'https://health.example.com');
  assert.equal(logic.normaliseServer('HTTP://10.0.0.5:8090/'), 'http://10.0.0.5:8090');
  assert.equal(logic.normaliseServer('https://example.com/health/'), 'https://example.com/health');
  assert.equal(logic.normaliseServer('ftp://example.com'), null);
  assert.equal(logic.normaliseServer(''), null);
  assert.equal(logic.normaliseServer(null), null);
});

test('the server\'s settings page is asked to come back to the watch', () => {
  assert.equal(
    logic.configPageUrl('https://health.example.com'),
    'https://health.example.com/pebble/config?return_to=pebblejs%3A%2F%2Fclose',
  );
});

test('the built-in settings page shows the current server, safely', () => {
  const url = logic.setupPageUrl('https://h.example.com/"><script>', true);
  assert.ok(url.startsWith('data:text/html;charset=utf-8,'));
  const html = decodeURIComponent(url.slice('data:text/html;charset=utf-8,'.length));
  assert.ok(html.includes('value="https://h.example.com/&quot;&gt;&lt;script&gt;"'));
  assert.ok(html.includes('Disconnect this watch'));
  assert.ok(!logic.setupPageUrl(null, false).includes('Disconnect'));
});

test('what the settings page sends back is understood in each form it may take', () => {
  const json = JSON.stringify({ server: 'https://health.example.com/', token: 'abc123' });
  const expected = { server: 'https://health.example.com', token: 'abc123' };
  assert.deepEqual(logic.parseConfigResponse(encodeURIComponent(json)), expected);
  assert.deepEqual(logic.parseConfigResponse('#' + encodeURIComponent(json)), expected);
  assert.deepEqual(logic.parseConfigResponse(encodeURIComponent(encodeURIComponent(json))), expected);
  assert.deepEqual(logic.parseConfigResponse(json), expected);
  assert.deepEqual(logic.parseConfigResponse('server=https%3A%2F%2Fhealth.example.com&token=abc123'), expected);
  // The server's address from a URL, or the one the watch already knew.
  assert.deepEqual(
    logic.parseConfigResponse(encodeURIComponent(JSON.stringify({ token: 'abc123', url: 'https://health.example.com/api/watch/today' }))),
    expected,
  );
  assert.deepEqual(logic.parseConfigResponse(encodeURIComponent('{"token":"abc123"}'), 'health.example.com'), expected);
});

test('closing the settings without connecting changes nothing; disconnecting is understood', () => {
  assert.equal(logic.parseConfigResponse('CANCELLED'), null);
  assert.equal(logic.parseConfigResponse(''), null);
  assert.equal(logic.parseConfigResponse(undefined), null);
  assert.equal(logic.parseConfigResponse(encodeURIComponent('{}'), 'https://h.example.com'), null);
  assert.equal(logic.parseConfigResponse(encodeURIComponent('{"token":"abc"}')), null);
  assert.equal(logic.parseConfigResponse('not json at all'), null);
  assert.deepEqual(logic.parseConfigResponse(encodeURIComponent('{"disconnect":true}')), { disconnect: true });
});

const today = {
  date: '2026-11-12',
  readiness: 84,
  sleep: null,
  activity: 91.4,
  steps: 6240,
  stepGoal: 8000,
  waterMl: 1250,
  waterGoal: 2000,
  modules: ['ACTIVITY', 'SLEEP', 'NUTRITION', 'MEDICATIONS', 'HABITS', 'CONTINENCE', 'MIND'],
  doses: [
    { medicationId: 'm1', scheduledFor: 1794470400000, name: 'Estradiol', time: '8:00', taken: true },
    { medicationId: 'm2', scheduledFor: 1794513600000, name: 'Vitamin D\tdaily\nwith food, a very long label indeed', time: '20:00', taken: false },
  ],
  habits: [
    { id: 'h1', name: 'Stretch', done: false },
    { id: 'h2', name: 'Walk', done: true },
  ],
  stress: 'RELAXED',
};

test('today becomes one message for the watch, with which row is which dose and habit', () => {
  const { message, sent } = logic.todayToMessage(today);
  assert.equal(message.Status, logic.STATUS.OK);
  const B = logic.MODULE_BITS;
  // Water is on unless the server says it's off (older servers don't say).
  assert.equal(message.Modules, B.NUTRITION | B.MEDICATIONS | B.HABITS | B.CONTINENCE | B.MIND | B.WATER);
  assert.equal(message.Readiness, 84);
  assert.equal(message.Sleep, -1);
  assert.equal(message.Activity, 91);
  assert.equal(message.Steps, 6240);
  assert.equal(message.Water, 1250);
  assert.equal(message.Stress, 2);
  const rows = message.Doses.split('\n').map((line) => line.split('\t'));
  assert.equal(rows.length, 2);
  assert.deepEqual(rows[0], ['8:00', 'Estradiol', '1']);
  assert.equal(rows[1].length, 3, 'tabs and line breaks in names never split a row');
  assert.equal([...rows[1][1]].length, 20);
  assert.ok(rows[1][1].endsWith('…'));
  assert.equal(message.Habits, 'Stretch\t0\nWalk\t1');
  assert.deepEqual(sent, { doses: ['m1@1794470400000', 'm2@1794513600000'], habits: ['h1', 'h2'] });
  assert.equal(logic.todayToMessage({ ...today, stress: null }).message.Stress, -1);
});

test('with water turned off, the watch is told to hide it and gets no water figures', () => {
  const B = logic.MODULE_BITS;
  const off = logic.todayToMessage({ ...today, water: false }).message;
  assert.equal(off.Modules & B.WATER, 0);
  assert.equal(off.Modules & B.NUTRITION, B.NUTRITION, 'coffee stays');
  assert.equal(off.Water, 0);
  assert.equal(off.WaterGoal, 0);
  // Turned back on, it all returns.
  const on = logic.todayToMessage({ ...today, water: true }).message;
  assert.equal(on.Modules & B.WATER, B.WATER);
  assert.equal(on.Water, 1250);
  assert.equal(on.WaterGoal, 2000);
  // A feature called WATER from the server never sets the bit by itself.
  assert.equal(logic.todayToMessage({ ...today, water: false, modules: ['WATER', 'NUTRITION'] }).message.Modules, B.NUTRITION);
  // Each bit is its own.
  const bits = Object.values(B);
  assert.equal(new Set(bits).size, bits.length);
});

test('the watch and the phone agree on feature bits and message keys', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const root = path.join(__dirname, '..');
  const header = fs.readFileSync(path.join(root, 'src/c/app.h'), 'utf8');
  const defined = {};
  header.replace(/#define MODULE_([A-Z_]+) (\d+)/g, (_, name, value) => { defined[name] = Number(value); });
  assert.deepEqual(defined, logic.MODULE_BITS);
  // Every key the C code reads or the phone sends is declared in package.json.
  const keys = new Set(require(path.join(root, 'package.json')).pebble.messageKeys);
  const c = fs.readdirSync(path.join(root, 'src/c')).map((f) => fs.readFileSync(path.join(root, 'src/c', f), 'utf8')).join('\n');
  for (const [, key] of c.matchAll(/MESSAGE_KEY_(\w+)/g)) assert.ok(keys.has(key), key);
  for (const key of Object.keys(logic.todayToMessage(today).message)) assert.ok(keys.has(key), key);
});

test('long lists are cut to what the watch can hold', () => {
  const many = Array.from({ length: 25 }, (_, i) => ({ id: 'h' + i, name: 'Habit ' + i, done: false }));
  const { message, sent } = logic.todayToMessage({ ...today, habits: many });
  assert.equal(message.Habits.split('\n').length, logic.MAX_ITEMS);
  assert.equal(sent.habits.length, logic.MAX_ITEMS);
});

test('taps become WatchLog bodies', () => {
  const { sent } = logic.todayToMessage(today);
  const K = (name) => logic.LOG_KINDS.indexOf(name);
  const now = 1794500000000;
  assert.deepEqual(logic.logFromAction(K('MOOD'), 4, 0, sent, now), { kind: 'MOOD', value: 4, at: now });
  assert.deepEqual(logic.logFromAction(K('WATER'), 250, 0, sent, now), { kind: 'WATER', value: 250, at: now });
  assert.deepEqual(logic.logFromAction(K('COFFEE'), 240, 0, sent, now), { kind: 'COFFEE', value: 240, at: now });
  assert.deepEqual(logic.logFromAction(K('LEAK'), 0, 0, sent, now), { kind: 'LEAK', at: now });
  assert.deepEqual(logic.logFromAction(K('DOSE_TAKEN'), 0, 1, sent, now), { kind: 'DOSE_TAKEN', ref: 'm2@1794513600000', at: now });
  assert.deepEqual(logic.logFromAction(K('HABIT'), 0, 0, sent, now), { kind: 'HABIT', value: 1, ref: 'h1', at: now });
  assert.deepEqual(logic.logFromAction(K('HOT_FLASH'), 3, 0, sent, now), { kind: 'HOT_FLASH', value: 3, at: now });
  assert.deepEqual(logic.logFromAction(K('SYMPTOM'), 7, 0, sent, now), { kind: 'SYMPTOM', value: 7, ref: 'headache', at: now });
  // Rows that aren't there, and values out of range, are refused.
  assert.equal(logic.logFromAction(K('DOSE_TAKEN'), 0, 5, sent, now), null);
  assert.equal(logic.logFromAction(K('HABIT'), 0, 9, { doses: [], habits: [] }, now), null);
  assert.equal(logic.logFromAction(K('MOOD'), 6, 0, sent, now), null);
  assert.equal(logic.logFromAction(K('SYMPTOM'), 11, 0, sent, now), null);
  assert.equal(logic.logFromAction(99, 1, 0, sent, now), null);
  assert.equal(logic.buildLog('NOT_A_KIND', 1), null);
});

test('responses are sorted into done, try again, signed out, or give up', () => {
  assert.equal(logic.classifyStatus(200), 'ok');
  assert.equal(logic.classifyStatus(204), 'ok');
  assert.equal(logic.classifyStatus(0), 'retry');
  assert.equal(logic.classifyStatus(503), 'retry');
  assert.equal(logic.classifyStatus(429), 'retry');
  assert.equal(logic.classifyStatus(401), 'auth');
  assert.equal(logic.classifyStatus(400), 'drop');
  assert.equal(logic.classifyStatus(403), 'drop');
  assert.equal(logic.errorMessage('{"error":"Turn on Medications first"}', 'x'), 'Turn on Medications first');
  assert.equal(logic.errorMessage('<html>', 'fallback'), 'fallback');
});

test('taps wait in the queue and go in order once the server is back', async () => {
  const storage = memoryStorage();
  const now = 1794500000000;
  logic.enqueue(storage, { kind: 'MOOD', value: 3, at: now - 2000 }, now - 2000);
  logic.enqueue(storage, { kind: 'WATER', value: 250, at: now - 1000 }, now - 1000);
  logic.enqueue(storage, { kind: 'LEAK', at: now }, now);
  assert.equal(logic.readQueue(storage, now).length, 3);

  // The server is down: nothing is lost.
  const down = await new Promise((resolve) => logic.drain(storage, (log, done) => done('retry'), now, resolve));
  assert.deepEqual(down, { sent: 0, dropped: 0, left: 3, stopped: 'retry' });

  // It's back, but refuses the water: that one is dropped, the rest are sent in order.
  const seen = [];
  const back = await new Promise((resolve) =>
    logic.drain(storage, (log, done) => {
      seen.push(log.kind);
      done(log.kind === 'WATER' ? 'drop' : 'ok');
    }, now, resolve),
  );
  assert.deepEqual(seen, ['MOOD', 'WATER', 'LEAK']);
  assert.deepEqual(back, { sent: 2, dropped: 1, left: 0, stopped: null });
  assert.equal(storage.getItem(logic.QUEUE_KEY), null);
});

test('the queue keeps the newest taps and forgets ones too old to matter', () => {
  const storage = memoryStorage();
  const now = 1794500000000;
  logic.enqueue(storage, { kind: 'MOOD', value: 1 }, now - logic.QUEUE_MAX_AGE_MS - 1);
  for (let i = 0; i < logic.QUEUE_MAX + 5; i++) logic.enqueue(storage, { kind: 'WATER', value: i + 1 }, now);
  const queue = logic.readQueue(storage, now);
  assert.equal(queue.length, logic.QUEUE_MAX);
  assert.equal(queue[0].log.value, 6);
  assert.ok(queue.every((item) => item.log.kind === 'WATER'));
  storage.setItem(logic.QUEUE_KEY, 'not json');
  assert.deepEqual(logic.readQueue(storage, now), []);
});

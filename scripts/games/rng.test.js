// Seeded randomness: the same seed, the same sequence. node scripts/games/rng.test.js
'use strict';
const assert = require('assert');
const { test, done, engine } = require('./harness');
const Rng = engine('rng.js');

test('одно зерно — одна последовательность', () => {
  const a = Rng.make(42);
  const b = Rng.make(42);
  const xs = [];
  for (let i = 0; i < 1000; i++) {
    const x = a.next();
    assert.strictEqual(x, b.next());
    assert.ok(x >= 0 && x < 1);
    xs.push(x);
  }
  assert.notStrictEqual(Rng.make(43).next(), Rng.make(42).next());
  // The fixed values: a change of the generator would change every game's offline and chests.
  const c = Rng.make(12345);
  assert.deepStrictEqual([c.next(), c.next(), c.next()].map((x) => Math.round(x * 1e9)), golden);
});

// mulberry32 of 12345 (the published generator gives 0.97972826776…).
const golden = [979728268, 306752264, 484205422];

test('продолжение с сохранённого состояния', () => {
  const a = Rng.make(7);
  for (let i = 0; i < 50; i++) a.next();
  const saved = a.state();
  const b = Rng.make(saved);
  for (let i = 0; i < 50; i++) assert.strictEqual(a.next(), b.next());
});

test('int, range, chance, pick, weighted, shuffle', () => {
  const r = Rng.make(1);
  const seen = {};
  for (let i = 0; i < 2000; i++) {
    const d = r.int(6);
    assert.ok(d >= 0 && d <= 5 && d % 1 === 0);
    seen[d] = true;
    const e = r.int(1, 6);
    assert.ok(e >= 1 && e <= 6);
    const x = r.range(0.5, 2);
    assert.ok(x >= 0.5 && x < 2);
  }
  assert.strictEqual(Object.keys(seen).length, 6);
  let hits = 0;
  for (let i = 0; i < 10000; i++) if (r.chance(0.1)) hits++;
  assert.ok(hits > 850 && hits < 1150, 'chance(0.1): ' + hits);
  assert.strictEqual(r.pick([]), undefined);
  const counts = { a: 0, b: 0, c: 0 };
  for (let i = 0; i < 6000; i++) counts[r.weighted([{ id: 'a', w: 1 }, { id: 'b', w: 2 }, { id: 'c', w: 0 }], 'w').id]++;
  assert.strictEqual(counts.c, 0);
  assert.ok(counts.b > counts.a * 1.6 && counts.b < counts.a * 2.4, JSON.stringify(counts));
  assert.strictEqual(r.weighted(['x'], [0]), undefined);
  const list = [1, 2, 3, 4, 5, 6, 7, 8];
  const s1 = Rng.make(9).shuffle(list.slice());
  const s2 = Rng.make(9).shuffle(list.slice());
  assert.deepStrictEqual(s1, s2);
  assert.deepStrictEqual(s1.slice().sort(), list);
});

test('fork не трогает основной генератор', () => {
  const a = Rng.make(100);
  const b = Rng.make(100);
  const f1 = a.fork('chest');
  const f2 = Rng.make(100).fork('chest');
  assert.strictEqual(f1.next(), f2.next());
  assert.strictEqual(a.next(), b.next());
  assert.notStrictEqual(Rng.make(100).fork('a').next(), Rng.make(100).fork('b').next());
});

test('hash стабилен', () => {
  assert.strictEqual(Rng.hash(''), 0x811c9dc5);
  assert.strictEqual(Rng.hash('a'), 0xe40c292c);
  assert.strictEqual(Rng.hash('2026-11-14'), Rng.hash('2026-11-14'));
  const s = Rng.seed();
  assert.ok(s >= 0 && s < 4294967296 && s % 1 === 0);
});

done('rng');

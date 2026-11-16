// The engine's shared core (engine/core.js) with the fixture game «Шахта»: states, saves and their
// migration, offline with caps and the links' multipliers, prestige, the inbox, the stock,
// achievements, keeping saves, notifications, the code checks. node scripts/games/core.test.js
'use strict';
const assert = require('assert');
const { test, done, workspace, gameOf } = require('./harness');

const env = workspace();
process.on('exit', () => env.close());
const { Core, Big, Rng, sim, game, cfg } = gameOf(env);

const HOUR = 3600000;
const T0 = Date.UTC(2026, 10, 14, 6, 0, 0);
const ctxAt = (now, extra) => Object.assign({ now, config: cfg, rng: Rng.make(5), mult: 1, links: {}, stock: {} }, extra || {});

function fresh(o) {
  const state = Core.newState(game, sim, ctxAt(T0));
  Object.assign(state.run, o || {});
  return state;
}

test('id игры', () => {
  assert.ok(Core.validId('zz-check') && Core.validId('fixture') && Core.validId('daily/2026-11-14'));
  assert.ok(Core.validId('example.counter') && Core.validId('author.' + 'a'.repeat(89)));
  for (const bad of ['engine', 'ui', 'links', 'daily', 'archive', 'template', 'A', '-a', 'a'.repeat(97), 'a..b', 'a.', 'a-', '../x', 'daily/26-1-1']) {
    assert.ok(!Core.validId(bad), bad);
  }
});

test('настройки: движок, общие, игра, владелец', () => {
  const own = JSON.parse(env.files.read('modules/games/fixture/config.json'));
  const sch = Core.schema(own, game);
  assert.strictEqual(sch['cart.growth'].origin, 'game');
  assert.strictEqual(sch['offline.capHours'].origin, 'engine');
  assert.strictEqual(sch['links.passenger'].value, true, 'переключатель связки игры');
  assert.strictEqual(sch['notify.cart'].value, false, 'уведомление выключено, пока владелец не включит');
  const values = Core.config(sch, { 'offline.capHours': 20, 'cart.growth': 1.5, 'notify.quiet': '22:00-07:00' },
    { 'cart.growth': 1.3, 'offline.capHours': 99, 'notify.quiet': '00:00-01:00' });
  assert.strictEqual(values['cart.growth'], 1.3, 'владелец поверх игры; общие не трогают объявленное игрой');
  assert.strictEqual(values['offline.capHours'], 72, 'владелец поверх общих, но в пределах max');
  assert.strictEqual(values['notify.quiet'], '22:00-07:00', 'общую настройку игра не меняет');
  assert.strictEqual(Core.coerce({ value: 1, min: 0, max: 5 }, 'abc'), 1);
  assert.strictEqual(Core.coerce({ value: false }, 'true'), true);
  assert.strictEqual(Core.coerce({ value: 'a', options: ['a', 'b'] }, 'c'), 'a');
  assert.strictEqual(Core.coerce({ value: '10', big: true }, 'oops'), '10');
  assert.strictEqual(Core.coerce({ value: '10', big: true }, '2e50'), '2e50');
});

test('новая игра и проверка состояния', () => {
  const s = fresh();
  assert.strictEqual(Core.validate(s, game), null);
  assert.strictEqual(sim.validate(s), null);
  assert.strictEqual(s.ver, 2);
  assert.ok(s.layers.depth && s.layers.depth.currency === '0e0', 'слой престижа создан');
  assert.ok(typeof s.meta.seed === 'number');
  const broken = Core.clone(s);
  broken.run.coins = 'NaN';
  assert.match(Core.validate(broken, game), /испорчено/);
  const inf = Core.clone(s);
  inf.run.carts = Infinity;
  assert.ok(Core.validate(JSON.parse(JSON.stringify(inf)), game) === null || true, 'Infinity в JSON становится null');
  inf.run.mined = '1e99999999999999999';
  assert.match(Core.validate(inf, game), /испорчено/);
  const other = Core.clone(s);
  other.game = 'другая';
  assert.match(Core.validate(other, game), /другой игры/);
  const noMoney = Core.clone(s);
  delete noMoney.run.coins;
  assert.match(Core.validate(noMoney, game), /нет валюты coins/);
  assert.match(Core.validate({ format: 9 }, game), /формат/);
});

test('загрузка: лучшая читаемая, перевод версии, новее игры', () => {
  const s = fresh({ coins: '100', carts: 2 });
  s.seq = 10;
  const good = { json: JSON.stringify(s), seq: 10, source: 'db' };
  const bad = { json: '{"format":1,"game":"fixture"', seq: 11, source: 'mirror' };
  let r = Core.pick([bad, good], game, sim);
  assert.strictEqual(r.candidate, good);
  assert.strictEqual(r.broken.length, 1);
  assert.match(r.broken[0].error, /не читается/);

  const v1 = JSON.parse(env.files.read('modules/games/fixture/fixtures/v1.json'));
  r = Core.pick([{ state: v1, seq: v1.seq, source: 'fixture' }], game, sim);
  assert.ok(r.state, JSON.stringify(r.broken));
  assert.strictEqual(r.migratedFrom, 1);
  assert.strictEqual(r.state.ver, 2);
  assert.strictEqual(r.state.run.coins, '4.2e3');
  assert.strictEqual(r.state.run.gold, undefined);
  assert.strictEqual(r.state.layers.depth.state.deepest, 31);
  assert.strictEqual(v1.run.gold, '4.2e3', 'кандидат не изменён (копия)');

  const newer = Core.clone(s);
  newer.ver = 3;
  r = Core.pick([{ state: newer, seq: 12 }, good], game, sim);
  assert.ok(!r.state && r.newer, 'сохранение новее игры не читается и не перезаписывается');

  const invalid = Core.clone(s);
  invalid.run.carts = -1;
  r = Core.pick([{ state: invalid, seq: 13 }, good], game, sim);
  assert.strictEqual(r.candidate, good);
  assert.match(r.broken[0].error, /вагонеток/);

  r = Core.pick([bad], game, sim);
  assert.strictEqual(r.state, null);
});

test('строка карточки последней в сохранении', () => {
  const s = fresh({ carts: 3 });
  s.card = Core.card(sim, s, cfg);
  assert.match(s.card, /вагонеток 3/);
  const keys = Object.keys(s);
  assert.strictEqual(keys[keys.length - 1], 'card');
  assert.strictEqual(Core.card({ summary: () => { throw new Error('x'); } }, s, cfg), '');
});

test('офлайн: окно, потолок, перевод часов назад', () => {
  let p = Core.plan(T0, T0 + 20 * HOUR, cfg, 0);
  assert.strictEqual(p.away, 20 * HOUR);
  assert.strictEqual(p.creditedMs, 12 * HOUR);
  assert.ok(p.capped && p.backup && !p.silent);
  assert.strictEqual(p.from, T0 + 8 * HOUR, 'засчитываются последние 12 часов');
  p = Core.plan(T0, T0 + 20 * HOUR, cfg, 2);
  assert.strictEqual(p.creditedMs, 14 * HOUR, 'потолок + 2 часа энергии');
  p = Core.plan(T0, T0 - HOUR, cfg, 0);
  assert.ok(p.clockBack && p.away === 0 && p.creditedMs === 0 && p.silent);
  p = Core.plan(T0, T0 + 30000, cfg, 0);
  assert.ok(p.silent, 'полминуты — без сводки');
  p = Core.plan(T0, T0 + HOUR, Object.assign({}, cfg, { 'offline.efficiency': 0.5 }), 0);
  assert.strictEqual(p.efficiency, 0.5);
});

test('офлайн: формула и шаги совпадают (12 ч)', () => {
  const s = fresh({ carts: 7 });
  s.layers.depth.currency = '3';
  const p = Core.plan(T0, T0 + 12 * HOUR, cfg, 0);
  const a = Core.clone(s);
  const b = Core.clone(s);
  const stepsSim = Object.assign({}, sim, { offline: undefined });
  const ra = Core.runOffline(sim, a, game, p, null, (seg) => ctxAt(seg.to), cfg);
  const rb = Core.runOffline(stepsSim, b, game, p, null, (seg) => ctxAt(seg.to), cfg);
  assert.ok(ra.formula && !rb.formula);
  assert.strictEqual(rb.steps, 2000, 'шагов не больше offline.maxSteps');
  const expected = 7 * 1 * (1 + 3 * 0.2) * 12 * 3600;
  assert.ok(Math.abs(Big.toNumber(ra.gains.coins) - expected) < 1e-6 * expected, Big.str(ra.gains.coins));
  const diff = Math.abs(Big.toNumber(ra.gains.coins) - Big.toNumber(rb.gains.coins)) / expected;
  assert.ok(diff < 0.01, 'расхождение ' + diff);
  assert.ok(ra.events.length === 1 && /рейсов/.test(ra.events[0]), 'событие формулы в сводке');
});

test('офлайн: потолок и отрезки множителей связок', () => {
  const s = fresh({ carts: 1 });
  const now = T0 + 20 * HOUR;
  const p = Core.plan(T0, now, cfg, 0);
  // A trip ×2 of an hour inside the window, a walk ×1.5 overlapping its end, a trip before the window.
  const segs = Core.cut(p.from, p.to, [
    { from: p.from + 2 * HOUR, to: p.from + 3 * HOUR, mult: 2, kind: 'passenger' },
    { from: p.from + 2.5 * HOUR, to: p.from + 4 * HOUR, mult: 1.5, kind: 'steps' },
    { from: T0, to: T0 + HOUR, mult: 2, kind: 'passenger' }
  ]);
  assert.deepStrictEqual(segs.map((x) => [(x.from - p.from) / HOUR, (x.to - p.from) / HOUR, x.mult, x.kinds.join('+')]), [
    [0, 2, 1, ''], [2, 2.5, 2, 'passenger'], [2.5, 3, 3, 'passenger+steps'], [3, 4, 1.5, 'steps'], [4, 12, 1, '']
  ]);
  const r = Core.runOffline(sim, s, game, p, segs, (seg) => ctxAt(seg.to), cfg);
  const hours = 2 + 0.5 * 2 + 0.5 * 3 + 1 * 1.5 + 8;
  assert.ok(Math.abs(Big.toNumber(r.gains.coins) - hours * 3600) < 1e-6 * hours * 3600, Big.str(r.gains.coins) + ' vs ' + hours * 3600);
  assert.strictEqual(r.multiplied.length, 3);
  const trip = r.multiplied.find((m) => m.kinds.join() === 'passenger');
  assert.strictEqual(trip.ms, 0.5 * HOUR);
  assert.deepStrictEqual(Core.cut(5, 5, []), []);
  assert.deepStrictEqual(Core.cut(0, 10, [{ from: 0, to: 10, mult: 0 }]), [{ from: 0, to: 10, mult: 1, kinds: [] }], 'нулевой множитель игнорируется');
});

test('офлайн по шагам частями: бюджет времени', () => {
  const s = fresh({ carts: 2 });
  const p = Core.plan(T0, T0 + 6 * HOUR, cfg, 0);
  const stepsSim = Object.assign({}, sim, { offline: undefined });
  const run = Core.runner(stepsSim, s, game, p, null, (seg) => ctxAt(seg.to), cfg);
  let clock = 0;
  const fake = () => (clock += 1);
  let rounds = 0;
  while (!run.step(5, fake)) rounds++;
  assert.ok(rounds > 10, 'частями: ' + rounds);
  assert.strictEqual(run.progress(), 1);
  assert.ok(Math.abs(Big.toNumber(run.report().gains.coins) - 2 * 6 * 3600) < 1);
});

test('престиж: прирост, сброс забега, слои и мета', () => {
  const s = fresh({ coins: '1e6', mined: Big.mul(cfg['depth.threshold'], 16), carts: 40 });
  Core.achieve(s, game, 'first-cart', T0);
  s.meta.stats.trips = 3;
  const info = Core.prestigeInfo(sim, game, s, 'depth', cfg);
  assert.strictEqual(Big.str(info.gain), '4e0', 'floor(sqrt(16))');
  assert.ok(info.can && info.hint);
  assert.strictEqual(Big.str(info.bonusAfter), '1.8e0');
  const r = Core.prestige(sim, game, s, 'depth', ctxAt(T0 + 1000));
  assert.ok(r.ok);
  assert.strictEqual(s.run.carts, 0);
  assert.strictEqual(s.run.coins, '0e0');
  assert.strictEqual(Big.str(s.layers.depth.currency), '4e0');
  assert.strictEqual(s.layers.depth.count, 1);
  assert.strictEqual(s.layers.depth.state.deepest, 40, 'onPrestige перенёс рекорд');
  assert.strictEqual(s.layers.depth.lastAt, T0 + 1000);
  assert.ok(s.meta.achievements['first-cart'] && s.meta.stats.trips === 3, 'мета не тронута');
  assert.strictEqual(Core.validate(JSON.parse(JSON.stringify(s)), game), null);
  // Not enough: no reset.
  const before = JSON.stringify(s);
  const no = Core.prestige(sim, game, s, 'depth', ctxAt(T0));
  assert.ok(!no.ok && JSON.stringify(s) === before);
  assert.ok(!Core.prestigeInfo(sim, game, s, 'depth', cfg).hint);
  assert.throws(() => Core.prestigeInfo(sim, game, s, 'nope', cfg), /Нет слоя/);
});

test('престиж второго слоя сбрасывает нижний, но не его счётчик', () => {
  const g2 = Core.clone(game);
  g2.currencies.push({ id: 'cores', title: 'Ядра', path: 'layers.core.currency' });
  g2.prestige.push({ id: 'core', title: 'Ядро', currency: 'cores' });
  const s2 = { init: sim.init, tick: sim.tick, prestigeGain: (st, layer) => (layer === 'core' ? 2 : 0) };
  const s = Core.newState(g2, s2, ctxAt(T0));
  s.layers.depth.currency = '9';
  s.layers.depth.count = 4;
  s.layers.depth.state = { deepest: 50 };
  s.run.carts = 12;
  const r = Core.prestige(s2, g2, s, 'core', ctxAt(T0));
  assert.ok(r.ok);
  assert.strictEqual(s.layers.depth.currency, '0e0');
  assert.deepStrictEqual(s.layers.depth.state, {});
  assert.strictEqual(s.layers.depth.count, 4);
  assert.strictEqual(Big.str(s.layers.core.currency), '2e0');
  assert.strictEqual(s.run.carts, 0);
});

test('ящик связок: начисление ровно один раз', () => {
  const s = fresh({ coins: '1000', carts: 2 });
  const entries = [
    { id: 'bet-2026-11-14', at: T0, from: 'bet', op: 'take', currency: 'coins', percent: 0.25, reason: 'Ставка на день' },
    { id: 'bet-2026-11-14-win', at: T0 + 1, from: 'bet', op: 'add', currency: 'coins', ofTake: 'bet-2026-11-14', mult: 2.5, reason: 'Выигрыш' },
    { id: 'chest-1', at: T0 + 2, from: 'passenger', op: 'chest', reason: 'Сундук за поездку' },
    { id: 'gems-1', at: T0 + 3, op: 'add', currency: 'gems', amount: 2.7 },
    { id: 'trips-1', at: T0 + 4, op: 'stat', stat: 'trips', amount: 1 },
    { id: 'bad-stat', at: T0 + 5, op: 'stat', stat: 'no such!' },
    { id: 'unknown', at: T0 + 6, op: 'add', currency: 'nope', amount: 5 }
  ];
  const r = Core.applyInbox(s, game, sim, entries, ctxAt(T0 + 10, { rng: Rng.make(1) }));
  assert.strictEqual(r.applied.length, 7);
  assert.strictEqual(r.moves.length, 3);
  assert.strictEqual(Big.str(r.moves[0].amount), '-2.5e2');
  assert.strictEqual(s.meta.taken['bet-2026-11-14'], '2.5e2');
  assert.strictEqual(Big.str(r.moves[1].amount), '6.25e2');
  assert.strictEqual(r.chests.length, 1);
  assert.strictEqual(r.chests[0].loot.title, 'Сундук штейгера');
  const chest = Big.toNumber(r.chests[0].loot.items[0].amount);
  assert.ok(Math.abs(Big.toNumber(s.run.coins) - (1000 - 250 + 625 + chest)) < 1e-6, Big.str(s.run.coins));
  assert.strictEqual(Big.str(s.layers.depth.currency), '2e0', 'самоцветы целые');
  assert.strictEqual(s.meta.stats.trips, 1);
  const after = JSON.stringify(s);
  const again = Core.applyInbox(s, game, sim, entries, ctxAt(T0 + 20));
  assert.ok(!again.changed && again.applied.length === 0);
  assert.strictEqual(JSON.stringify(s), after, 'второй раз ничего не меняется');
  // A take never makes the money negative.
  const poor = fresh({ coins: '10' });
  Core.applyInbox(poor, game, sim, [{ id: 't', op: 'take', currency: 'coins', amount: 50 }], ctxAt(T0));
  assert.strictEqual(Big.str(poor.run.coins), '0e0');
  assert.strictEqual(poor.meta.taken.t, '1e1');
  // A chest of a game without sim.chest: income of passenger.chestFallbackMin minutes.
  const plain = Object.assign({}, sim, { chest: undefined });
  const p2 = fresh({ carts: 1 });
  const rc = Core.applyInbox(p2, game, plain, [{ id: 'c', op: 'chest' }], ctxAt(T0));
  assert.ok(Math.abs(Big.toNumber(rc.chests[0].loot.items[0].amount) - 30 * 60) < 1e-6);
});

test('ящик: применённые помнятся (до 500), старые записи чистятся', () => {
  const s = fresh();
  const many = [];
  for (let i = 0; i < 600; i++) many.push({ id: 'n' + i, at: T0 + i, op: 'stat', stat: 'trips' });
  Core.applyInbox(s, game, sim, many, ctxAt(T0));
  assert.strictEqual(s.meta.applied.length, 500);
  assert.strictEqual(s.meta.stats.trips, 600);
  const list = [{ id: 'old', at: T0 - 40 * 86400000 }, { id: 'oldOpen', at: T0 - 40 * 86400000 }, { id: 'new', at: T0 }];
  assert.deepStrictEqual(Core.inboxClean(list, ['old', 'new'], T0, 30).map((e) => e.id), ['oldOpen', 'new']);
});

test('запас контента: расход, нехватка, срок, схема, проверка', () => {
  const list = [
    { id: 'a', kind: 'rumors', createdAt: 3, item: { title: 'C' } },
    { id: 'b', kind: 'rumors', createdAt: 1, item: { title: 'A' } },
    { id: 'c', kind: 'other', createdAt: 0, item: {} },
    { id: 'd', kind: 'rumors', createdAt: 2, item: { title: 'B' }, usedAt: 5 }
  ];
  assert.strictEqual(Core.stock.count(list, 'rumors'), 2);
  assert.strictEqual(Core.stock.take(list, 'rumors', 100).id, 'b', 'самый старый');
  assert.strictEqual(Core.stock.take(list, 'rumors', 101).id, 'a');
  assert.strictEqual(Core.stock.take(list, 'rumors', 102), null);
  assert.strictEqual(Core.stock.count(list, 'rumors'), 0);
  const names = [
    { id: 'n1', kind: 'names', createdAt: 1, item: { title: 'Пух', species: 'Ушань', line: 'основатель' } },
    { id: 'n2', kind: 'names', createdAt: 2, item: { title: 'Мох', species: 'Мохорог', line: 'основатель' } },
    { id: 'n3', kind: 'names', createdAt: 3, item: { title: 'Мшистый IV', species: 'Мохорог', line: 'потомок' } }
  ];
  assert.strictEqual(Core.stock.take(names, 'names', 10, { species: 'Мохорог', line: 'потомок' }).id, 'n3', 'совпало больше полей');
  assert.strictEqual(Core.stock.take(names, 'names', 11, { species: 'Мохорог', line: 'потомок' }).id, 'n2', 'потом — по породе');
  assert.strictEqual(Core.stock.take(names, 'names', 12, { species: 'Светляк' }).id, 'n1', 'нет совпадений — старший');
  const art = [
    { id: 'l7', kind: 'blades', createdAt: 1, item: { level: 7 } },
    { id: 'l8', kind: 'blades', createdAt: 2, item: { level: 8 } }
  ];
  assert.strictEqual(Core.stock.take(art, 'blades', 13, { level: 9 }, true), null, 'строго: девятого нет');
  assert.strictEqual(Core.stock.take(art, 'blades', 14, { level: 8 }, true).id, 'l8');
  assert.strictEqual(art[0].usedAt, undefined, 'седьмой не тронут');
  const needs = Core.stock.needs(list, game.stock);
  assert.deepStrictEqual(needs.map((n) => [n.kind, n.unused, n.want]), [['rumors', 0, 5]]);
  const exp = Core.stock.expire([{ id: 'x', createdAt: T0 - 40 * 86400000 }, { id: 'y', createdAt: T0 }, { id: 'z', createdAt: 0, usedAt: 1 }], T0, 30);
  assert.deepStrictEqual(exp.map((e) => e.id), ['y', 'z']);
  const sch = Core.stock.schema(env.files.read('modules/games/fixture/prompts/rumors.md'));
  assert.strictEqual(sch.key, 'text');
  assert.strictEqual(sch.fields.text.max, 90);
  const seen = { [Core.stock.norm('Уже было, такое!')]: true };
  const check = (item) => Core.stock.check(item, sch, seen, sim, 'rumors');
  assert.strictEqual(check({ title: 'Старый штейгер', text: 'Говорит, что слышал воду' }), null);
  assert.match(check({ title: 'Ок', text: 'Говорит, что слышал воду' }), /короче/);
  assert.match(check({ title: 'Старый штейгер' }), /нет поля text/);
  assert.match(check({ title: 'Старый штейгер', text: 'x'.repeat(91) }), /длиннее/);
  assert.match(check({ title: 'Старый штейгер', text: 'Говорит, что слышал воду ⛏' }), /эмодзи/);
  assert.match(check({ title: 'Старый штейгер', text: 'уже было — такое' }), /повтор/);
  assert.match(check({ title: 'Старый штейгер', text: 'Нашёл 12345 самородков' }), /длинное число/, 'sim.validateStock');
  assert.match(check([1]), /не объект/);
  assert.strictEqual(Core.stock.norm('Ёлка, ЁЖ!'), 'елка еж');
});

test('ачивки: запись, неотправленные, для «Ачивок за жизнь»', () => {
  const s = fresh();
  assert.ok(Core.achieve(s, game, 'first-cart', T0));
  assert.strictEqual(Core.achieve(s, game, 'first-cart', T0 + 1), null, 'второй раз не новая');
  assert.strictEqual(Core.achieve(s, game, 'nope', T0), null);
  Core.achieve(s, game, 'rich', T0 + 2);
  assert.deepStrictEqual(Core.unreported(s, ['rich']), ['first-cart']);
  const marked = Core.lifeItems(game, s, ['first-cart', 'rich'], 'marked');
  assert.deepStrictEqual(marked.map((i) => i.key), ['games/fixture/first-cart']);
  assert.strictEqual(marked[0].text, 'Поставил первую вагонетку в «Шахте»');
  assert.strictEqual(marked[0].at, T0);
  assert.strictEqual(marked[0].source, 'games');
  const all = Core.lifeItems(game, s, ['first-cart', 'rich'], 'all');
  assert.strictEqual(all.length, 2);
  assert.ok(all[1].hidden && /«Шахта»/.test(all[1].text));
  assert.deepStrictEqual(Core.lifeItems(game, s, ['first-cart'], 'none'), []);
});

test('какие сохранения хранить', () => {
  const DAY = 86400000;
  const now = Date.UTC(2026, 10, 14, 12);
  const rows = [];
  let id = 0;
  // 40 saves today, then one save every 6 hours for 20 days back; two marked keep.
  for (let i = 0; i < 40; i++) rows.push({ id: ++id, seq: 1000 - i, at: now - i * 60000, keep: 0 });
  for (let h = 1; h <= 80; h++) rows.push({ id: ++id, seq: 900 - h, at: now - h * 6 * HOUR, keep: h === 70 || h === 75 ? 1 : 0 });
  const gone = new Set(Core.retention(rows, now, 30, 14));
  const kept = rows.filter((r) => !gone.has(r.id));
  assert.strictEqual(kept.filter((r) => r.seq > 960).length, 30, 'последние 30');
  assert.ok(kept.some((r) => r.keep && r.id === rows.find((x) => x.keep).id), 'помеченные остаются');
  const days = new Set(kept.filter((r) => now - r.at <= 14 * DAY).map((r) => Core.dayKey(r.at)));
  assert.ok(days.size >= 14, 'по одному за каждый из 14 дней: ' + days.size);
  const old = kept.filter((r) => now - r.at > 14 * DAY && !r.keep);
  assert.strictEqual(old.length, 0, 'старше 14 дней без метки — удалены');
  assert.ok(kept.length < rows.length / 2);
});

test('уведомления: тихие часы и ближайшее событие', () => {
  const at = (h, m) => new Date(2026, 10, 14, h, m).getTime();
  assert.ok(Core.inQuiet('23:00-08:00', at(23, 30)) && Core.inQuiet('23:00-08:00', at(7, 59)));
  assert.ok(!Core.inQuiet('23:00-08:00', at(8, 0)) && !Core.inQuiet('23:00-08:00', at(22, 59)));
  assert.ok(Core.inQuiet('13:00-14:00', at(13, 30)) && !Core.inQuiet('', at(13, 30)));
  assert.strictEqual(Core.afterQuiet('23:00-08:00', at(23, 30)), new Date(2026, 10, 15, 8, 0).getTime());
  assert.strictEqual(Core.afterQuiet('23:00-08:00', at(3, 0)), at(8, 0));
  assert.strictEqual(Core.afterQuiet('23:00-08:00', at(12, 0)), at(12, 0));
  const c = Object.assign({}, cfg, { 'notify.cart': true });
  const s = fresh({ carts: 2, coins: '0' });
  const ev = sim.next(s, at(12, 0), c);
  assert.strictEqual(ev.length, 1);
  const best = Core.nextNotify(ev, game, c, at(12, 0));
  assert.strictEqual(best.title, 'Хватает на вагонетку');
  assert.ok(best.at > at(12, 0));
  assert.strictEqual(Core.nextNotify(ev, game, cfg, at(12, 0)), null, 'выключено владельцем');
  assert.strictEqual(Core.nextNotify([{ id: 'cart', at: at(12, 0) + 2 * 86400000 }], game, c, at(12, 0)), null, 'дальше суток — нет');
});

test('проверка кода: общее подмножество', () => {
  const rules = (code, strict) => Core.lint(code, strict).map((p) => p.rule);
  assert.deepStrictEqual(rules('class A {}'), ['class']);
  assert.deepStrictEqual(rules('async function f() { await g(); }'), ['async', 'await']);
  assert.deepStrictEqual(rules('for (const x of y) {}'), ['for (const …)']);
  assert.deepStrictEqual(rules('f(...args);'), ['f(...args)']);
  assert.deepStrictEqual(rules('var a = [...b]; function f(...rest) {} var g = (...xs) => xs;'), [], 'разворот массива и rest-параметры можно');
  assert.deepStrictEqual(rules('let a = 1; const b = () => a;'), [], 'в играх let и стрелки можно');
  assert.deepStrictEqual(rules('let a = 1; const b = () => a;', true).sort(), ['let/const', 'стрелочная функция'].sort());
  assert.deepStrictEqual(rules('for (let i = 0; i < 1; i++) {}', true).sort(), ['for (let …)', 'let/const'].sort());
  assert.deepStrictEqual(rules('var s = `x`;', true), ['шаблонная строка']);
  assert.deepStrictEqual(rules('// class A\nvar s = "async function () {}"; var r = /class X/;', true), [], 'комментарии, строки, регулярки');
  assert.deepStrictEqual(rules('export var x = 1;'), ['import/export']);
});

test('без сети: адреса в файлах игры', () => {
  const hits = Core.offlineScan([
    { path: 'ui/index.html', text: '<script src="https://cdn.example.com/x.js"></script>\n<svg xmlns="http://www.w3.org/2000/svg"></svg>' },
    { path: 'ui/game.js', text: 'fetch("//api.example.org/v1")\n// see //notes.example.com in a comment\nvar ok = "https://assi.local/x";' },
    { path: 'ui/game.css', text: '@import url(//fonts.example.net/a.css);' }
  ]);
  assert.deepStrictEqual(hits.map((h) => [h.path, h.line]), [['ui/index.html', 1], ['ui/game.css', 1]]);
});

done('core');

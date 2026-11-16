// The three life links: «Пассажир», «Шаги — топливо», «Ставка на день». Their pure logic, then
// the whole way through the hub in a workspace (a bet placed, settled and paid through the
// inbox; steps from the base becoming energy). node scripts/games/links.test.js
'use strict';
const assert = require('assert');
const { test, done, workspace, gameOf } = require('./harness');

const env = workspace({ features: [] });
process.on('exit', () => env.close());
const { Core, Big, Rng, sim, game, cfg } = gameOf(env);
const passenger = env.require('modules/games/links/passenger.js');
const steps = env.require('modules/games/links/steps.js');
const bet = env.require('modules/games/links/bet.js');
const sensors = env.require('modules/games/links/sensors.js');
const templates = JSON.parse(env.files.read('modules/games/links/bet-templates.json'));
const tpl = (id) => templates.find((t) => t.id === id);

const MIN = 60000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;
const T = new Date(2026, 10, 14, 8, 0, 0).getTime();

// ---- «Пассажир» ----

test('пассажир: окна акселерометра', () => {
  const c = (w) => passenger.classify(w, cfg);
  assert.strictEqual(c({ rms: 0.01, freq: 0, steps: 0 }), 'still');
  assert.strictEqual(c({ rms: 0.3, freq: 0.4, steps: 4 }), 'walk', 'шаги датчика');
  assert.strictEqual(c({ rms: 1.4, freq: 1.9, steps: 0 }), 'walk', 'ритм шага');
  assert.strictEqual(c({ rms: 0.2, freq: 0.6, steps: 0 }), 'train');
  assert.strictEqual(c({ rms: 2.5, freq: 0.3, steps: 0 }), 'unknown');
  assert.strictEqual(c(null), 'unknown');
  // From raw samples: a phone on a table and a phone in a carriage.
  const still = [];
  const ride = [];
  for (let i = 0; i < 200; i++) {
    const t = i * 50;
    still.push([t, 0.001 * Math.sin(i), 0.001 * Math.cos(i), 9.81]);
    ride.push([t, 0.25 * Math.sin(i * 0.9) + 0.1 * Math.sin(i * 2.3), 0.2 * Math.cos(i * 1.7), 9.81 + 0.15 * Math.sin(i * 0.5)]);
  }
  assert.strictEqual(c(sensors.measure(still, true, 0)), 'still');
  assert.strictEqual(c(sensors.measure(ride, true, 0)), 'train');
  assert.strictEqual(sensors.measure(still.slice(0, 8), true, 0), null, 'мало данных');
});

test('пассажир: спуск под землю по барометру', () => {
  const p = [[T - 5 * MIN, 1000.0], [T + 2 * MIN, 1001.4], [T + 6 * MIN, 1002.6], [T + 30 * MIN, 1001.0]];
  assert.strictEqual(passenger.descent(p, T, cfg), 2.6);
  assert.strictEqual(passenger.descent(p, T + 3 * HOUR, cfg), 0, 'далеко от потери сети');
  assert.strictEqual(passenger.descent([[T, 1000]], T, cfg), 0);
  assert.strictEqual(passenger.descent(p, null, cfg), 0);
});

test('пассажир: нет сети + вагон → поездка, сеть 2 мин → конец', () => {
  const st = passenger.blank();
  const run = (min, ev) => passenger.step(st, Object.assign({ now: T + min * MIN }, ev), cfg).map((e) => e.type);
  assert.deepStrictEqual(run(0, { online: false }), []);
  assert.deepStrictEqual(run(1.5, { window: 'train' }), ['maybe']);
  assert.strictEqual(st.since, T, 'поездка с потери сети');
  assert.deepStrictEqual(run(2, { window: 'train' }), [], 'ещё не 3 минуты');
  assert.deepStrictEqual(run(3, { window: 'train' }), ['trip']);
  assert.deepStrictEqual(run(20, { window: 'train' }), []);
  assert.deepStrictEqual(run(40, { online: true }), []);
  assert.deepStrictEqual(run(41, {}), [], 'сеть ещё не 2 минуты');
  assert.deepStrictEqual(run(42, {}), ['end']);
  const trip = st.trips[0];
  assert.strictEqual(trip.start, T);
  assert.strictEqual(trip.end, T + 40 * MIN, 'конец — когда сеть вернулась');
  assert.strictEqual(trip.min, 40);
  assert.deepStrictEqual(trip.signals, ['offline', 'train']);
  assert.strictEqual(st.state, 'none');
  assert.strictEqual(st.total, 1);
});

test('пассажир: без признаков поездки — «не еду», и тот же провал сети не будит', () => {
  const st = passenger.blank();
  const run = (min, ev) => passenger.step(st, Object.assign({ now: T + min * MIN }, ev), cfg).map((e) => e.type);
  run(0, { online: false });
  assert.deepStrictEqual(run(2, { window: 'unknown' }), ['maybe']);
  assert.deepStrictEqual(run(11, { window: 'unknown' }), ['none'], 'maybeMaxMin без признаков');
  assert.deepStrictEqual(run(12, { window: 'train' }), [], 'тот же провал отклонён');
  run(13, { online: true });
  run(14, { online: false });
  assert.deepStrictEqual(run(16, {}), ['maybe'], 'новый провал — снова «похоже»');
  // Lying still on a table without network: no «maybe» at all.
  const desk = passenger.blank();
  passenger.step(desk, { now: T, online: false, window: 'still' }, cfg);
  assert.deepStrictEqual(passenger.step(desk, { now: T + 5 * MIN, window: 'still' }, cfg), []);
});

test('пассажир: спуск или Wi-Fi метро подтверждают, ходьба — нет; покой 15 мин — конец', () => {
  const a = passenger.blank();
  passenger.step(a, { now: T, online: false }, cfg);
  passenger.step(a, { now: T + 2 * MIN, descent: 2.5 }, cfg);
  assert.strictEqual(a.state, 'maybe');
  assert.deepStrictEqual(passenger.step(a, { now: T + 3 * MIN }, cfg).map((e) => e.type), ['trip']);
  passenger.step(a, { now: T + 20 * MIN, window: 'still' }, cfg);
  assert.deepStrictEqual(passenger.step(a, { now: T + 34 * MIN, window: 'still' }, cfg).map((e) => e.type), []);
  assert.deepStrictEqual(passenger.step(a, { now: T + 35 * MIN, window: 'still' }, cfg).map((e) => e.type), ['end']);
  assert.strictEqual(a.trips[0].end, T + 20 * MIN, 'конец — с начала покоя');
  assert.ok(a.trips[0].signals.indexOf('descent') >= 0 || a.trips[0].signals.indexOf('offline') >= 0);

  const w = passenger.blank();
  passenger.step(w, { now: T, online: false }, cfg);
  passenger.step(w, { now: T + 2 * MIN, ssid: 'MT_FREE', walking: true }, cfg);
  assert.deepStrictEqual(passenger.step(w, { now: T + 4 * MIN, walking: true }, cfg).map((e) => e.type), [], 'идёт пешком');
  assert.deepStrictEqual(passenger.step(w, { now: T + 5 * MIN, walking: false }, cfg).map((e) => e.type), ['trip']);
});

test('пассажир: «Я еду» / «Я не еду», предел ручной поездки', () => {
  const st = passenger.blank();
  assert.deepStrictEqual(passenger.step(st, { now: T, manual: 'on' }, cfg).map((e) => e.type), ['trip']);
  assert.ok(st.manual);
  assert.deepStrictEqual(passenger.step(st, { now: T + 30 * MIN, manual: 'off' }, cfg).map((e) => e.type), ['end', 'none']);
  assert.deepStrictEqual([st.trips[0].min, st.trips[0].manual], [30, true]);
  const long = passenger.blank();
  passenger.step(long, { now: T, manual: 'on' }, cfg);
  assert.deepStrictEqual(passenger.step(long, { now: T + 121 * MIN }, cfg).map((e) => e.type), ['end']);
  assert.strictEqual(long.trips[0].end, T + 120 * MIN);
});

test('пассажир: отрезки множителя и надписи', () => {
  const st = passenger.blank();
  st.trips = [{ start: T, end: T + HOUR }, { start: T - 5 * HOUR, end: T - 4 * HOUR }];
  st.state = 'trip';
  st.since = T + 2 * HOUR;
  const segs = passenger.pureSegments(st, cfg, T - HOUR, T + 10 * HOUR, T + 3 * HOUR);
  assert.deepStrictEqual(segs.map((s) => [(s.from - T) / HOUR, (s.to - T) / HOUR, s.mult, s.kind]), [[0, 1, 2, 'passenger'], [2, 3, 2, 'passenger']]);
  assert.strictEqual(passenger.text(st, cfg, T + 2 * HOUR + 14 * MIN, '×2'), 'В пути 14 мин, доход ×2');
  const maybe = passenger.blank();
  maybe.state = 'maybe';
  assert.strictEqual(passenger.text(maybe, cfg, T, '×2'), 'Похоже, вы едете…');
  assert.strictEqual(passenger.text(passenger.blank(), cfg, T, '×2'), 'Поездка: нет');
  const s2 = passenger.blank();
  s2.signals.offlineSince = T;
  s2.signals.windows = ['train', 'train'];
  s2.signals.descentHpa = 2.6;
  s2.signals.ssid = 'MT_FREE';
  assert.deepStrictEqual(passenger.signs(s2, cfg, T + 16 * MIN), ['нет сети 16 мин', 'трясёт как в вагоне', 'спуск на 22 м', 'видна сеть «MT_FREE»']);
});

// ---- «Шаги — топливо» ----

test('шаги: отметки счётчика, перезагрузка, полночь', () => {
  const d = steps.blank();
  assert.strictEqual(steps.addMark(d, T, 10000), 0, 'первая отметка — начало отсчёта');
  assert.strictEqual(steps.addMark(d, T + HOUR, 10500), 500);
  assert.strictEqual(steps.addMark(d, T + HOUR, 11000), 0, 'то же время — пропуск');
  assert.strictEqual(steps.addMark(d, T + 2 * HOUR, 300), 300, 'перезагрузка: счёт с нуля');
  assert.strictEqual(d.today.steps, 800);
  const evening = new Date(2026, 10, 14, 23, 0).getTime();
  steps.addMark(d, evening, 1300);
  const morning = new Date(2026, 10, 15, 1, 0).getTime();
  const added = steps.addMark(d, morning, 2300);
  assert.strictEqual(added, 500, 'половина тысячи — уже завтра');
  assert.strictEqual(d.days['2026-11-14'], 800 + 1000 + 500);
  assert.strictEqual(d.today.date, '2026-11-15');
  assert.strictEqual(steps.dayStart('2026-11-15'), new Date(2026, 10, 15).getTime());
  assert.ok(isNaN(steps.dayStart('15.11.2026')));
});

test('шаги: энергия — за тысячу, предел дня, запас, сгорание, траты старых первыми', () => {
  const d = steps.blank();
  steps.setToday(d, T, 3400);
  assert.strictEqual(steps.grant(d, cfg, T), 3);
  assert.strictEqual(steps.grant(d, cfg, T + 1), 0, 'уже начислено');
  steps.setToday(d, T + HOUR, 40000);
  assert.strictEqual(steps.grant(d, cfg, T + HOUR), 12, 'до 15 в день');
  assert.strictEqual(steps.energyOf(d), 15);
  assert.strictEqual(steps.toNext(d, cfg), 0);
  steps.givePure(d, 20, T + 2 * HOUR);
  assert.strictEqual(steps.energyOf(d), 35, 'выигрыш — сверх запаса');
  let r = steps.spendPure(d, cfg, T + 3 * HOUR, 4, 'fixture', 'cap');
  assert.deepStrictEqual([r.ok, r.left], [true, 31]);
  assert.strictEqual(d.energy[0].left, 0 + d.energy[0].left, 'самая старая порция тратится первой');
  assert.strictEqual(d.energy.find((e) => e.at === T), undefined, 'утренние 3 потрачены');
  r = steps.spendPure(d, cfg, T + 3 * HOUR, 100, 'fixture', 'cap');
  assert.deepStrictEqual([r.ok, r.need], [false, 69]);
  steps.spendPure(d, cfg, T + 3 * DAY, 0, null, 'look');
  assert.strictEqual(steps.energyOf(d), 0, 'через 2 дня сгорает');
  const capped = steps.blank();
  capped.energy = [{ at: T, left: 29 }];
  steps.setToday(capped, T, 5000);
  assert.strictEqual(steps.grant(capped, cfg, T), 1, 'не выше запаса 30');
  const d2 = steps.blank();
  steps.setToday(d2, T, 1650);
  assert.strictEqual(steps.toNext(d2, cfg), 350);
});

test('шаги: заряд прогулки и его отрезки', () => {
  const marks = [[T, 0], [T + 10 * MIN, 400], [T + 30 * MIN, 1200]];
  assert.strictEqual(steps.walkedByMarks(marks, T + 5 * MIN, T + 30 * MIN), 200 + 800);
  assert.strictEqual(steps.walkedByMarks([[T, 5000], [T + 10 * MIN, 100]], T, T + 10 * MIN), 100, 'перезагрузка');
  const d = steps.blank();
  assert.strictEqual(steps.charge(d, cfg, T, 500), null);
  const c = steps.charge(d, cfg, T + 30 * MIN, 900);
  assert.deepStrictEqual([c.from, c.to, c.mult], [T + 30 * MIN, T + 60 * MIN, 1.5]);
  steps.charge(d, cfg, T + 50 * MIN, 1000);
  assert.strictEqual(d.charges.length, 1);
  assert.strictEqual(d.charges[0].to, T + 80 * MIN, 'идёте дальше — заряд продлевается');
  // The walk ended at T+50: later marks still see 800 steps in the window, but the charge stays
  // 30 minutes from the walk's end, not from each mark.
  steps.charge(d, cfg, T + 70 * MIN, 1000, T + 50 * MIN);
  assert.strictEqual(d.charges[0].to, T + 80 * MIN, 'шаги затихли — заряд не продлевается');
  assert.strictEqual(steps.lastStepAt([[T, 0], [T + 10 * MIN, 900], [T + 15 * MIN, 905], [T + 20 * MIN, 910]], 20), T + 10 * MIN);
  assert.strictEqual(steps.lastStepAt([[T, 0], [T + 10 * MIN, 5]], 20), null);
  const segs = steps.pureSegments(d, cfg, T, T + 70 * MIN);
  assert.deepStrictEqual(segs.map((s) => [(s.from - T) / MIN, (s.to - T) / MIN, s.mult, s.kind]), [[30, 70, 1.5, 'steps']]);
});

// ---- «Ставка на день» ----

function source(o) {
  return {
    steps: (date) => (o.steps || {})[date] === undefined ? null : o.steps[date],
    sleepStart: (date) => (o.sleep || {})[date] === undefined ? null : o.sleep[date],
    screenMs: (date) => (o.screen || {})[date] === undefined ? null : o.screen[date],
    appMs: (date, packages) => (o.apps || {})[date] === undefined ? null : o.apps[date],
    expenses: (date, tag) => (o.expenses || {})[date] === undefined ? null : o.expenses[date],
    habit: (date) => (o.habits || {})[date] === undefined ? null : o.habits[date],
    workoutMin: (date) => (o.workout || {})[date] === undefined ? null : o.workout[date]
  };
}

test('ставка: проверка условий по данным', () => {
  const day = '2026-11-14';
  const src = source({
    steps: { [day]: 8200 }, sleep: { [day]: new Date(2026, 10, 15, 0, 20).getTime() }, screen: { [day]: 95 * MIN },
    apps: { [day]: 25 * MIN }, expenses: { [day]: 0 }, habits: { [day]: true }, workout: { [day]: 20 }
  });
  const ev = (id, params) => bet.evaluate(tpl(id), params, day, src);
  assert.deepStrictEqual([ev('steps_at_least', { n: 8000 }).ok, ev('steps_at_least', { n: 9000 }).ok], [true, false]);
  assert.strictEqual(ev('steps_at_least', { n: 9000 }).text, '8 200 из 9 000 шагов');
  assert.deepStrictEqual([ev('bed_before', { time: '00:30' }).ok, ev('bed_before', { time: '00:00' }).ok], [true, false]);
  assert.strictEqual(ev('bed_before', { time: '00:30' }).text, 'легли в 00:20');
  assert.deepStrictEqual([ev('screen_time_below', { min: 120 }).ok, ev('screen_time_below', { min: 90 }).ok], [true, false]);
  assert.strictEqual(ev('screen_time_below', { min: 90 }).text, 'экран 1 ч 35 мин');
  assert.strictEqual(ev('app_time_below', { app: 'Соцсети', packages: ['x'], min: 30 }).ok, true);
  assert.strictEqual(ev('no_expense_tag', { tag: 'кафе' }).ok, true);
  assert.strictEqual(ev('habit_done', { habit: 'зарядка' }).ok, true);
  assert.deepStrictEqual([ev('workout', { min: 15 }).ok, ev('workout', { min: 30 }).ok], [true, false]);
  const none = bet.evaluate(tpl('steps_at_least'), { n: 1 }, '2026-11-13', src);
  assert.deepStrictEqual([none.ok, none.text], [null, 'нет данных о шагах за этот день']);
  assert.strictEqual(bet.evaluate({ id: 'fly' }, {}, day, src).ok, null);
});

test('ставка: шансы, коэффициент, три предложения', () => {
  const days = [];
  const data = {};
  for (let i = 1; i <= 10; i++) {
    const date = '2026-11-' + String(i).padStart(2, '0');
    days.push(date);
    data[date] = i <= 7 ? 9000 : 3000;
  }
  days.push('2026-10-01');
  const o = bet.odds(tpl('steps_at_least'), { n: 8000 }, days, source({ steps: data }));
  assert.deepStrictEqual(o, { p: 0.7, days: 10, won: 7 });
  assert.strictEqual(bet.kOf(null, 0, cfg), 2);
  assert.strictEqual(bet.kOf(0.5, 0, cfg), 2);
  assert.strictEqual(bet.kOf(0.7, 0, cfg), 1.4);
  assert.strictEqual(bet.kOf(1, 0, cfg), 1.1, 'не ниже kMin');
  assert.strictEqual(bet.kOf(0.05, 0, cfg), 5, 'не выше kMax');
  assert.strictEqual(bet.kOf(0, 0, cfg), 5);
  assert.strictEqual(bet.kOf(0.5, 3, cfg), 2.2, 'три выигрыша подряд: +10 %');
  const cands = [
    { template: 'steps_at_least', params: { n: 5000 }, p: 0.9 },
    { template: 'steps_at_least', params: { n: 8000 }, p: 0.7 },
    { template: 'steps_at_least', params: { n: 10000 }, p: 0.4 },
    { template: 'bed_before', params: { time: '23:30' }, p: 0.45 },
    { template: 'steps_at_least', params: { n: 15000 }, p: 0.1 },
    { template: 'workout', params: { min: 15 }, p: null }
  ];
  const picked = bet.pickOffers(cands);
  assert.deepStrictEqual(picked.map((c) => c.template + ':' + JSON.stringify(c.params)),
    ['steps_at_least:{"n":8000}', 'bed_before:{"time":"23:30"}', 'steps_at_least:{"n":15000}']);
});

test('ставка: сон по экрану, время расчёта, подписи', () => {
  const at = (d, h, m) => new Date(2026, 10, d, h, m).getTime();
  const log = [[at(14, 22, 0), 'unlocked'], [at(14, 23, 10), 'off'], [at(14, 23, 30), 'unlocked'], [at(14, 23, 50), 'off'], [at(15, 7, 10), 'unlocked']];
  assert.strictEqual(bet.sleepFromScreen(log, '2026-11-14'), at(14, 23, 50));
  assert.strictEqual(bet.sleepFromScreen([[at(14, 23, 0), 'off'], [at(15, 1, 0), 'unlocked']], '2026-11-14'), null, 'проснулся через 2 часа — не сон');
  assert.strictEqual(bet.settleTime({ date: '2026-11-14', settleAt: 'sleep' }, cfg), at(15, 10, 0));
  assert.strictEqual(bet.settleTime({ date: '2026-11-14', settleAt: 'evening' }, cfg), at(14, 23, 30));
  assert.strictEqual(bet.bedline('2026-11-14', '00:30'), at(15, 0, 30));
  assert.strictEqual(bet.bedline('2026-11-14', '23:00'), at(14, 23, 0));
  assert.strictEqual(bet.title(tpl('steps_at_least'), { n: 8000 }), 'Пройду 8 000 шагов');
  assert.strictEqual(bet.title(tpl('app_time_below'), { app: 'Видео', min: 30 }, 'plate'), 'Видео меньше 30 мин');
});

// ---- the whole way through the hub ----

test('ставка целиком: предложения → ставка → расчёт → выплата через ящик', () => {
  const main = env.require('modules/games/main.js');
  const base = env.require('lib/base');
  const now = Date.now();
  main.setSetting(null, 'bet.deadline', '23:59');
  // After 23:30 an evening bet settles at once, so the steps below (stake taken, then paid) only
  // hold earlier in the day.
  if (now > new Date().setHours(23, 30, 0, 0)) return;
  for (let i = 1; i <= 10; i++) base.add({ kind: 'steps', at: now - i * DAY, amount: i <= 7 ? 9000 : 3000, unit: 'steps', source: 'test' });
  base.add({ kind: 'steps', at: now - 1000, amount: 10000, unit: 'steps', source: 'test' });
  const st = Core.newState(game, sim, { now, config: cfg, rng: Rng.make(1) });
  st.run.coins = '1000';
  st.seq = 1;
  main.save('fixture', 1, JSON.stringify(st), 'start');

  const offers = main.bet('offers');
  assert.ok(offers.open, 'ставки открыты');
  assert.ok(offers.offers.length >= 1 && offers.offers.every((o) => o.template === 'steps_at_least'), 'данные есть только о шагах');
  assert.deepStrictEqual(offers.games.map((g) => [g.id, g.currencies.map((c) => c.id + '=' + c.balance).join()]), [['fixture', 'coins=1e3']]);

  const placed = main.bet('place', { template: 'steps_at_least', params: { n: 8000 }, p: 0.7, k: 1.4, game: 'fixture', currency: 'coins', percent: 0.5 });
  assert.strictEqual(placed.estimate, '5e2');
  assert.throws(() => main.bet('place', { template: 'steps_at_least', params: { n: 1 }, game: 'fixture', currency: 'coins', percent: 0.1 }), /уже сделана/);
  assert.throws(() => main.bet('place', { template: 'nope', game: 'fixture', percent: 0.1 }), /уже сделана|Нет такого/);
  let s = Core.clone(st);
  let r = Core.applyInbox(s, game, sim, main.inbox('fixture'), { now, config: cfg, rng: Rng.make(1) });
  assert.strictEqual(Big.str(s.run.coins), '5e2', 'ставка списана в игре');
  assert.strictEqual(r.moves[0].reason, 'Ставка: Пройду 8 000 шагов');

  const settled = main.bet('settle', 'evening');
  assert.strictEqual(settled.result, 'won', JSON.stringify(settled));
  assert.match(settled.text, /^Ставка выиграна: 10 000 шагов\. \+700 монет|^Ставка выиграна: 10 000 шагов\. \+700 золото/);
  r = Core.applyInbox(s, game, sim, main.inbox('fixture'), { now, config: cfg, rng: Rng.make(1) });
  assert.strictEqual(Big.str(s.run.coins), '1.2e3', '500 + 500 × 1,4');
  assert.strictEqual(r.applied.length, 1, 'ставка второй раз не списана');
  assert.strictEqual(main.bet('settle', 'evening'), null, 'дважды не платится');
  assert.throws(() => main.bet('place', { template: 'steps_at_least', params: { n: 1 }, game: 'fixture', currency: 'coins', percent: 0.1 }), /уже сделана/, 'после расчёта вторая ставка за день не принимается');
  assert.strictEqual(main.bet('offers').open, false, 'и предложения закрыты');
  const data = env.files.readJson('data/games/links/bet.json');
  assert.deepStrictEqual([data.history.length, data.history[0].result, data.streak, data.today], [1, 'won', 1, null]);
  assert.ok(env.files.readJson('data/games/achievements-outbox.json').some((a) => a.key === 'games/bet/all-in'), 'ачивка «Ва-банк»');
  assert.strictEqual(base.find({ kind: 'bet' }).length, 1);
  assert.ok(env.shell.notified.some((n) => n.title === 'Ставка на день' && n.open === 'modules/games/ui/index.html#bet'));
});

test('шаги из базы становятся энергией; ставка энергией без данных — возврат', () => {
  // The steps in the base come from the test above, which runs only before 23:30.
  if (Date.now() > new Date().setHours(23, 30, 0, 0)) return;
  const main = env.require('modules/games/main.js');
  main.catalog();
  assert.ok(env.flush() >= 1, 'отметка шагов в фоне при открытии каталога');
  const status = main.links('fixture').steps;
  assert.strictEqual(status.source, 'base');
  assert.strictEqual(status.energy, 10, '10 000 шагов сегодня');
  // One bet a day: today's bet from the test above is cleared, so this one is the day's first.
  env.files.writeJson('data/games/links/bet.json', {});
  const placed = main.bet('place', { template: 'habit_done', params: { habit: 'зарядка' }, p: null, k: 2, game: '__energy', percent: 0.5 });
  assert.strictEqual(placed.energy, 5);
  assert.strictEqual(main.links('fixture').steps.energy, 5);
  const r = main.bet('settle', 'evening');
  assert.strictEqual(r.result, 'refund');
  assert.strictEqual(main.links('fixture').steps.energy, 10, 'энергия вернулась');
});

done('links');

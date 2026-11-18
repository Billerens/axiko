// The engine side of the hub (modules/games/main.js) in a temporary workspace: saves in SQLite
// and their rules, loading, the catalog, settings, the inbox, the stock, achievements to «Ачивки
// за жизнь», sessions, notifications, the life links' glue. node scripts/games/engine.test.js
'use strict';
const assert = require('assert');
const { test, done, workspace, gameOf } = require('./harness');

const env = workspace({ features: [] });
process.on('exit', () => env.close());
const main = env.require('modules/games/main.js');
const { Core, Big, Rng, sim, game, cfg } = gameOf(env);
const ID = 'fixture';
const HOUR = 3600000;

function stateWith(o, at) {
  const t = at || Date.now();
  const s = Core.newState(game, sim, { now: t, config: cfg, rng: Rng.make(3), mult: 1 });
  Object.assign(s.run, o || {});
  return s;
}

function save(seq, state, reason, keep) {
  const s = state || stateWith();
  s.seq = seq;
  s.card = Core.card(sim, s, cfg);
  return main.save(ID, seq, JSON.stringify(s), reason || 'auto', keep);
}

const rows = () => env.db('games').query('select id, seq, reason, keep from saves where game = ? order by seq desc', [ID]);

test('сохранение: seq растёт, старый и равный отвергаются', () => {
  assert.deepStrictEqual(save(1, null, 'start'), { ok: true, seq: 1 });
  assert.deepStrictEqual(save(1), { ok: false, stale: true, lastSeq: 1 });
  assert.deepStrictEqual(save(0), { ok: false, stale: true, lastSeq: 1 });
  assert.deepStrictEqual(save(3, stateWith({ carts: 4 })), { ok: true, seq: 3 });
  assert.deepStrictEqual(save(2), { ok: false, stale: true, lastSeq: 3 }, 'вторая копия игры отстала');
  const list = main.saves(ID);
  assert.deepStrictEqual(list.map((r) => r.seq), [3, 1]);
  assert.match(list[0].card, /вагонеток 4/, 'строка карточки без разбора всего JSON');
  assert.throws(() => main.save('../x', 9, '{}'), /Нет такой игры/);
});

test('уникальный индекс (игра, seq) в базе', () => {
  assert.throws(() => env.db('games').exec('insert into saves (game, seq, at, ver, reason, size, state) values (?, ?, ?, ?, ?, ?, ?)',
    [ID, 3, Date.now(), 2, 'x', 2, '{}']));
});

test('хранение: последние save.keep, помеченные остаются', () => {
  for (let seq = 4; seq <= 70; seq++) save(seq, stateWith({ carts: seq }), seq === 10 ? 'before-prestige' : 'auto', seq === 10);
  main.prune(ID);
  const left = rows();
  assert.strictEqual(left.filter((r) => !r.keep).length, 30, 'сегодняшние: последние 30');
  assert.ok(left.some((r) => r.seq === 10 && r.keep === 1), 'копия перед престижем цела');
  assert.strictEqual(left[0].seq, 70);
});

test('boot: всё для экрана одним вызовом', () => {
  const b = main.boot(ID);
  assert.strictEqual(b.game.id, ID);
  assert.strictEqual(b.lastSeq, 70);
  assert.strictEqual(b.rows.length, 6);
  assert.strictEqual(b.config['cart.growth'], 1.12);
  assert.strictEqual(b.schema['cart.growth'].origin, 'game');
  assert.deepStrictEqual(b.stock, { rumors: 0 });
  assert.ok(b.links.passenger && b.links.steps && b.links.bet && b.links.mult === 1);
  assert.deepStrictEqual(b.features, []);
  assert.ok(env.files.readJson('data/games/catalog.json').opened[ID] > 0, 'отмечено, когда открыта');
});

test('повреждённое, восстановление, начать заново', () => {
  main.markBroken(ID, 70, 'проверка');
  let r = rows();
  assert.ok(r[0].keep === 1 && /повреждено/.test(r[0].reason));
  assert.match(JSON.stringify(main.journal(ID)), /seq 70 повреждено/);
  const older = main.older(ID, 70, 3);
  assert.deepStrictEqual(older.map((x) => x.seq), [69, 68, 67]);
  const res = main.restore(ID, older[1].id);
  assert.deepStrictEqual(res, { ok: true, seq: 72 });
  r = rows();
  assert.deepStrictEqual([r[0].seq, r[0].reason, r[1].seq, r[1].reason, r[1].keep], [72, 'restore', 71, 'before-restore', 1]);
  assert.strictEqual(JSON.parse(env.db('games').query('select state from saves where game = ? and seq = 72', [ID])[0].state).run.carts, 68);
  const reset = main.reset(ID);
  assert.strictEqual(reset.seq, 74);
  const fresh = JSON.parse(env.db('games').query('select state from saves where game = ? and seq = 74', [ID])[0].state);
  assert.strictEqual(fresh.run.carts, 0);
  assert.strictEqual(Core.validate(fresh, game), null);
  assert.strictEqual(rows()[1].reason, 'before-reset');
});

test('экспорт и импорт, старая версия переводится', () => {
  const ex = main.exportSave(ID);
  assert.ok(env.files.exists(ex.path) && ex.text && ex.size === ex.text.length);
  const v1 = env.files.read('modules/games/fixture/fixtures/v1.json');
  const im = main.importSave(ID, v1);
  assert.strictEqual(im.seq, 76);
  const got = JSON.parse(env.db('games').query('select state from saves where game = ? and seq = 76', [ID])[0].state);
  assert.strictEqual(got.ver, 2);
  assert.strictEqual(got.run.coins, '4.2e3');
  assert.strictEqual(got.seq, 76);
  const newer = JSON.parse(v1);
  newer.ver = 9;
  assert.throws(() => main.importSave(ID, JSON.stringify(newer)), /новее/);
  assert.throws(() => main.importSave(ID, '{"format":1'), /не подходит/);
});

test('каталог: карточка, «накопилось», без игр — пусто', () => {
  const old = stateWith({ carts: 5 }, Date.now() - 3 * HOUR);
  save(80, old);
  env.db('games').exec('update saves set at = ? where game = ? and seq = 80', [Date.now() - 3 * HOUR, ID]);
  const cat = main.catalog();
  const item = cat.games.find((g) => g.id === ID);
  assert.ok(item, 'игра в каталоге');
  assert.strictEqual(item.title, 'Шахта');
  assert.match(item.card, /вагонеток 5/);
  assert.ok(item.badge, 'метка «накопилось» после часа');
  assert.match(item.accrued.text, /^\+/);
  assert.ok(Math.abs(Number(item.accrued.amount.replace(/e.*/, '')) * Math.pow(10, Number(item.accrued.amount.split('e')[1])) - 5 * 3 * 3600) < 60);
  assert.ok(item.offline.ok);
  assert.match(item.icon, /^<svg/);
  assert.strictEqual(item.entry, 'modules/games/fixture/ui/index.html');
  assert.ok(cat.links.passenger && cat.links.steps && cat.links.bet);
  main.catalogSet('pin', ID, true);
  const nav = env.files.readJson('ui/nav.json');
  assert.deepStrictEqual(nav.items, [{ title: 'Шахта', icon: 'play', open: 'modules/games/fixture/ui/index.html' }]);
  main.catalogSet('pin', ID, false);
  assert.deepStrictEqual(env.files.readJson('ui/nav.json').items, []);
  main.catalogSet('archive', ID, true);
  assert.ok(main.catalog().games.find((g) => g.id === ID).archived);
  main.catalogSet('archive', ID, false);
  assert.throws(() => main.catalogSet('fly', ID, 1), /Не знаю/);

  const empty = workspace({ fixture: false, features: [] });
  try {
    const m = empty.require('modules/games/main.js');
    const c = m.catalog();
    assert.deepStrictEqual(c.games, [], 'папки движка не игры');
    assert.ok(c.links);
  } finally {
    empty.close();
  }
});

test('без сети: адрес в файле игры находится', () => {
  env.files.write('modules/games/fixture/ui/bad.js', 'var x = "https://cdn.example.com/lib.js";');
  const r = main.offlineCheck(ID);
  assert.ok(!r.ok);
  assert.deepStrictEqual(r.hits.map((h) => [h.path, h.line]), [['fixture/ui/bad.js', 1]]);
  env.files.remove('modules/games/fixture/ui/bad.js');
  assert.ok(main.offlineCheck(ID).ok);
});

test('настройки игры и общие', () => {
  const s = main.settings(ID);
  assert.strictEqual(s.game.title, 'Шахта');
  assert.strictEqual(s.values['cart.growth'], 1.12);
  const after = main.setSetting(ID, 'cart.growth', 5);
  assert.strictEqual(after.values['cart.growth'], 2, 'в пределах max');
  assert.deepStrictEqual(env.files.readJson('data/games/fixture/config.json'), { 'cart.growth': 2 });
  assert.ok(env.shell.emitted.some((e) => e.name === 'games.config' && e.value.game === ID));
  main.setSetting(ID, 'cart.growth', null);
  assert.deepStrictEqual(env.files.readJson('data/games/fixture/config.json'), {});
  assert.throws(() => main.setSetting(ID, 'notify.quiet', '00:00-01:00'), /общая настройка/);
  assert.throws(() => main.setSetting(ID, 'nope', 1), /Нет такой настройки/);
  main.setSetting(null, 'catalog.badgeAfterMin', 5);
  assert.strictEqual(main.settings(null).values['catalog.badgeAfterMin'], 5);
  main.setSetting(null, 'catalog.badgeAfterMin', null);
});

test('ящик связок: запись один раз', () => {
  assert.strictEqual(main.inboxAdd(ID, { id: 'gift-1', op: 'add', currency: 'coins', amount: 100 }), true);
  assert.strictEqual(main.inboxAdd(ID, { id: 'gift-1', op: 'add', currency: 'coins', amount: 999 }), false);
  assert.strictEqual(main.inbox(ID).length, 1);
  assert.ok(env.shell.emitted.some((e) => e.name === 'games.inbox' && e.value.game === ID));
  assert.throws(() => main.inboxAdd(ID, { id: 'x' }), /нужны id и op/);
  const box = main.inbox(ID);
  box[0].at = Date.now() - 40 * 86400000;
  env.files.writeJson('data/games/fixture/inbox.json', box);
  assert.strictEqual(main.inboxClean(ID, ['gift-1']), 0);
});

test('запас контента: берётся самый старый, кончился — «готовится»', () => {
  const t = Date.now();
  env.files.writeJson('data/games/fixture/stock.json', [
    { id: 's2', kind: 'rumors', createdAt: t - 1000, item: { title: 'Второй', text: 'Слух номер два из запаса' } },
    { id: 's1', kind: 'rumors', createdAt: t - 5000, item: { title: 'Первый', text: 'Слух номер один из запаса' } }
  ]);
  assert.deepStrictEqual(main.stockStatus(ID), { rumors: 2 });
  let r = main.stockTake(ID, 'rumors');
  assert.deepStrictEqual([r.id, r.item.title, r.left], ['s1', 'Первый', 1]);
  r = main.stockTake(ID, 'rumors');
  assert.deepStrictEqual([r.id, r.left], ['s2', 0]);
  r = main.stockTake(ID, 'rumors');
  assert.deepStrictEqual(r, { item: null, id: null, left: 0, preparing: false });
  const used = env.files.readJson('data/games/fixture/stock.json');
  assert.ok(used.every((x) => x.usedAt > 0), 'отмечены использованными');
  main.setSetting(null, 'stock.local', true);
  const spawned = env.shell.spawned.length;
  r = main.stockTake(ID, 'rumors');
  assert.ok(r.preparing && env.shell.spawned.length === spawned + 1, 'модель на телефоне готовит в фоне');
  env.shell.spawned.length = spawned;
  main.setSetting(null, 'stock.local', null);
});

test('запас: пополнение моделью с проверкой каждого элемента', () => {
  const stock = env.require('modules/games/stock.js');
  const hubOf = () => {
    // The helpers main.js passes to links and the stock (the same object, reached via a link).
    let hub = null;
    const passenger = env.require('modules/games/links/passenger.js');
    const orig = passenger.status;
    passenger.status = (h, c, g) => {
      hub = h;
      return orig(h, c, g);
    };
    main.links(ID);
    passenger.status = orig;
    return hub;
  };
  const hub = hubOf();
  env.shell.ai = (request) => {
    assert.match(request.system + request.prompt, /слух/i);
    return [
      { title: 'Старый штейгер', text: 'Слышал, как под землёй журчит ручей' },
      { title: 'Старый штейгер', text: 'Слышал, как под землёй журчит ручей' },
      { title: 'Ок', text: 'Слишком короткое имя у рассказчика' },
      { title: 'Взрывник Пётр', text: 'Нашёл 123456 крупинок' },
      { title: 'Рыжий откатчик', text: 'Видел, как вагонетка поехала сама' }
    ];
  };
  const r = stock.fill(hub, main.game(ID), 'rumors', {});
  env.shell.ai = null;
  assert.strictEqual(r.added, 2, JSON.stringify(r));
  assert.strictEqual(r.rejected, 3);
  assert.strictEqual(main.stockStatus(ID).rumors, 2);
});

test('ачивки уходят в «Ачивки за жизнь»: через ящик, ровно один раз', () => {
  env.files.remove('data/games/reported.json');
  env.files.remove('data/games/achievements-outbox.json');
  const s = stateWith({ carts: 1 });
  Core.achieve(s, game, 'first-cart', 1763000000000);
  Core.achieve(s, game, 'rich', 1763000001000);
  save(100, s, 'achievement');
  let box = env.files.readJson('data/games/achievements-outbox.json');
  assert.deepStrictEqual(box.map((x) => x.key), ['games/fixture/first-cart'], 'только помеченные life');
  assert.strictEqual(box[0].text, 'Поставил первую вагонетку в «Шахте»');
  assert.strictEqual(box[0].at, 1763000000000);
  assert.deepStrictEqual(env.files.readJson('data/games/reported.json'), { fixture: ['first-cart', 'rich'] });
  save(101, s, 'achievement');
  main.report(ID);
  box = env.files.readJson('data/games/achievements-outbox.json');
  assert.strictEqual(box.length, 1, 'повторно не отправлено');
  // The module «Ачивки» with grant(item): items go to it, not to the outbox.
  env.files.remove('data/games/reported.json');
  env.files.remove('data/games/achievements-outbox.json');
  env.files.write('modules/achievements/main.js',
    'var files = require("lib/axiko").files;\nexports.grant = function (item) { var l = files.readJson("data/granted.json", []); l.push(item.key); files.writeJson("data/granted.json", l); };');
  main.setSetting(null, 'achievements.share', 'all');
  save(102, s, 'exit');
  assert.deepStrictEqual(env.files.readJson('data/granted.json'), ['games/fixture/first-cart', 'games/fixture/rich']);
  assert.ok(!env.files.exists('data/games/achievements-outbox.json'));
  main.setSetting(null, 'achievements.share', null);
  env.files.remove('modules/achievements');
});

test('сессия игры: в «Пассажира» и в общую базу', () => {
  const t = Date.now();
  assert.strictEqual(main.session(ID, 5000, t - 5000, t), false, 'короче минуты в базу не идёт');
  assert.strictEqual(main.session(ID, 25 * 60000, t - 25 * 60000, t), true);
  const plays = env.files.readJson('data/games/links/plays.json');
  assert.strictEqual(plays.length, 2);
  const base = env.require('lib/base');
  const rec = base.find({ kind: 'play' });
  assert.strictEqual(rec.length, 1);
  assert.deepStrictEqual([rec[0].title, rec[0].amount, rec[0].unit, rec[0].data.game], ['Шахта', 25, 'min', ID]);
});

test('уведомление: ближайшее событие, задача, срабатывание', () => {
  main.setSetting(ID, 'notify.cart', true);
  main.setSetting(null, 'notify.quiet', '');
  save(110, stateWith({ carts: 2, coins: '0' }), 'auto');
  const plan = main.planNotify();
  assert.strictEqual(plan.game, ID);
  assert.strictEqual(plan.id, 'cart');
  assert.ok(plan.at > Date.now() && plan.at < Date.now() + 60000);
  const job = env.shell.jobs['games:notify'];
  assert.ok(job && /^\d\d:\d\d$/.test(job.spec.at), 'без job.once — по часам и минутам');
  env.files.writeJson('data/games/notify.json', Object.assign({}, plan, { at: Date.now() - 1000 }));
  job.fn();
  const sent = env.shell.notified[env.shell.notified.length - 1];
  assert.strictEqual(sent.title, 'Хватает на вагонетку');
  assert.strictEqual(sent.open, 'modules/games/fixture/ui/index.html');
  main.setSetting(ID, 'notify.cart', false);
  main.planNotify();
  assert.ok(!env.shell.jobs['games:notify'], 'выключено — задачи нет');
  main.setSetting(null, 'notify.quiet', null);

  const once = workspace({ features: ['job.once'] });
  try {
    const m = once.require('modules/games/main.js');
    m.setSetting(ID, 'notify.cart', true);
    m.setSetting(null, 'notify.quiet', '');
    const g = gameOf(once);
    const st = Core.newState(g.game, g.sim, { now: Date.now(), config: g.cfg, rng: Rng.make(1) });
    st.run.carts = 3;
    st.seq = 1;
    m.save(ID, 1, JSON.stringify(st), 'hide');
    assert.ok(once.shell.jobs['games:notify'].spec.once > Date.now(), 'с job.once — на точный момент; план после выхода');
  } finally {
    once.close();
  }
});

test('«Пассажир» вручную: множитель, сундук и поездка в ящик', () => {
  const t = Date.now();
  main.passenger(true);
  let links = main.links(ID);
  assert.strictEqual(links.passenger.state, 'trip');
  assert.strictEqual(links.mult, 2);
  const segs = main.segments(ID, t - HOUR, t + 1000);
  assert.strictEqual(segs.length, 1);
  assert.strictEqual(segs[0].mult, 2);
  // A trip of 30 minutes: end it, and the chest goes to the game played during it.
  const file = 'data/games/links/passenger.json';
  const st = env.files.readJson(file);
  st.since = t - 30 * 60000;
  env.files.writeJson(file, st);
  env.files.writeJson('data/games/links/plays.json', [{ game: ID, from: t - 20 * 60000, to: t - 60000 }]);
  main.passenger(false);
  links = main.links(ID);
  assert.strictEqual(links.passenger.state, 'none');
  const inbox = main.inbox(ID);
  const chest = inbox.find((e) => e.op === 'chest');
  assert.ok(chest && /^chest-/.test(chest.id) && /30\sмин/.test(chest.reason), JSON.stringify(inbox));
  assert.ok(inbox.some((e) => e.op === 'stat' && e.stat === 'trips'));
  assert.strictEqual(env.require('lib/base').find({ kind: 'trip' }).length, 1);
  const s = stateWith({ carts: 1 });
  const r = Core.applyInbox(s, game, sim, inbox, { now: t, config: cfg, rng: Rng.make(2) });
  assert.strictEqual(r.chests.length, 1);
  assert.strictEqual(s.meta.stats.trips, 1);
});

test('«Шаги»: энергия тратится по объявленным тратам', () => {
  let r = main.spend(ID, 'cap');
  assert.deepStrictEqual([r.ok, r.need], [false, 1]);
  assert.throws(() => main.spend(ID, 'unknown'), /не объявила/);
  const steps = env.require('modules/games/links/steps.js');
  const d = steps.blank();
  steps.setToday(d, Date.now(), 3400);
  steps.grant(d, cfg, Date.now());
  env.files.writeJson('data/games/links/steps.json', d);
  r = main.spend(ID, 'cap');
  assert.deepStrictEqual([r.ok, r.left], [true, 2]);
  assert.strictEqual(main.links(ID).steps.energy, 2);
});

test('удаление игры: папка уходит, данные остаются', () => {
  main.catalogSet('pin', ID, true);
  const r = main.remove(ID);
  assert.ok(r.ok);
  assert.ok(!env.files.exists('modules/games/fixture/game.json'));
  assert.ok(!main.catalog().games.some((g) => g.id === ID));
  assert.deepStrictEqual(env.files.readJson('ui/nav.json').items, []);
  assert.ok(main.saves(ID).length > 0, 'сохранения не тронуты');
});

done('engine');

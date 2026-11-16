// The hub's own device checks (modules/games/checks.js) without the phone: what does not need a
// screen runs for real, the screens get a stand-in. Above all: the copy zz-check is installed and
// everything it leaves is cleaned away. node scripts/games/hub-checks.test.js
'use strict';
const assert = require('assert');
const { test, done, workspace, gameOf } = require('./harness');

const env = workspace();
process.on('exit', () => env.close());
const checks = env.require('modules/games/checks.js');
const main = env.require('modules/games/main.js');
const ID = 'zz-check';

function helper() {
  const page = { eval: () => null, text: () => null, count: () => 0, click: () => {}, type: () => {} };
  return {
    ok: (c, m) => { if (!c) throw new Error(m || 'условие'); },
    equal: (a, b, m) => assert.strictEqual(JSON.stringify(a), JSON.stringify(b), m),
    throws: (fn) => assert.throws(fn),
    skip: (why) => { const e = new Error(why); e.why = why; throw e; },
    wait: () => {},
    screen: () => page
  };
}

const names = Object.keys(checks);

test('порядок: подготовка первой, уборка последней', () => {
  assert.strictEqual(names[0], 'подготовка: копия шаблона zz-check');
  assert.strictEqual(names[names.length - 1], 'уборка');
  assert.ok(names.length >= 10);
});

test('подготовка ставит копию шаблона без связей с другими модулями', () => {
  checks[names[0]](helper());
  const g = main.game(ID);
  assert.strictEqual(g.title, 'Проверка Игротеки');
  assert.deepStrictEqual(g.stock, []);
  assert.deepStrictEqual(g.notify, []);
  assert.ok(g.achievements.every((a) => !a.life));
  assert.ok(env.files.exists('modules/games/zz-check/sim.js') && env.files.exists('modules/games/zz-check/ui/game.js'));
});

test('проверки без экрана проходят на копии', () => {
  const x = gameOf(env, ID);
  const st = x.Core.newState(x.game, x.sim, { now: Date.now(), config: x.cfg, rng: x.Rng.make(1) });
  st.seq = 5;
  main.save(ID, 5, JSON.stringify(st), 'start');
  for (const name of ['старый seq не принимается', 'файлы Игротеки без адресов в сети', 'стандартные проверки игры (на копии шаблона)']) {
    checks[name](helper());
  }
});

test('обход проверок установленных игр (здесь — «Шахта»)', () => {
  // The fixture's screen check needs the phone: the stand-in page gives nothing, so it fails alone.
  try {
    checks['проверки каждой установленной игры'](helper());
    assert.fail('проверка экрана без телефона должна была не пройти');
  } catch (error) {
    const parts = String(error.message).split('; ');
    assert.ok(parts.length === 1 && /^fixture \/ экран/.test(parts[0]), error.message);
  }
});

test('уборка не оставляет ничего', () => {
  // What a run on the phone leaves behind.
  main.boot(ID);
  main.catalogSet('pin', ID, true);
  main.session(ID, 120000, Date.now() - 120000, Date.now());
  env.files.writeJson('data/games/reported.json', { [ID]: ['first-forge'], other: ['x'] });
  env.files.writeJson('data/games/achievements-outbox.json', [{ key: 'games/zz-check/first-forge' }, { key: 'games/other/x' }]);
  env.files.writeJson('data/games/notify.json', { game: ID, at: Date.now() + 1000 });
  main.inboxAdd(ID, { id: 'x', op: 'stat', stat: 'trips' });
  const base = env.require('lib/base');
  assert.strictEqual(base.find({ kind: 'play' }).length, 1);
  checks['уборка'](helper());
  assert.ok(!env.files.exists('modules/games/zz-check'));
  assert.ok(!env.files.exists('data/games/zz-check'));
  assert.strictEqual(env.db('games').query('select count(*) as n from saves where game = ?', [ID])[0].n, 0);
  const cat = env.files.readJson('data/games/catalog.json');
  assert.ok(!(ID in (cat.opened || {})) && cat.pinned.indexOf(ID) < 0);
  assert.deepStrictEqual(env.files.readJson('data/games/reported.json'), { other: ['x'] });
  assert.deepStrictEqual(env.files.readJson('data/games/achievements-outbox.json'), [{ key: 'games/other/x' }]);
  assert.deepStrictEqual(env.files.readJson('data/games/notify.json'), {});
  assert.deepStrictEqual(env.files.readJson('data/games/links/plays.json'), []);
  assert.deepStrictEqual(env.files.readJson('ui/nav.json').items, []);
  assert.strictEqual(base.find({ kind: 'play' }).length, 0);
  assert.ok(!main.games().some((g) => g.id === ID));
});

done('hub-checks');

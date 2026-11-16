// Catalog startup must stay independent of game simulations, source scans and module hooks.
// Run with: node scripts/games/catalog.test.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const { test, done, workspace, gameOf } = require('./harness');

const HOUR = 3600000;

test('fast catalog returns playable cards without heavy work or full save payloads', () => {
  const env = workspace({ features: [] });
  try {
    const main = env.require('modules/games/main.js');
    const { Core, game, sim, cfg, Rng } = gameOf(env);
    const old = Date.now() - 3 * HOUR;
    const state = Core.newState(game, sim, { now: old, config: cfg, rng: Rng.make(9), mult: 1 });
    state.seq = 1;
    state.run.carts = 5;
    state.run.largeHistory = 'x'.repeat(100000);
    state.card = '5 вагонеток';
    main.save('fixture', 1, JSON.stringify(state), 'auto');
    env.db('games').exec('update saves set at=? where game=?', [old, 'fixture']);
    env.files.writeJson('modules/extra-game/module.json', { title: 'Другой модуль', games: { card: 'card' } });
    env.files.write('modules/extra-game/main.js', "exports.card = function () { native('test.card'); return {text:'Готово'}; };");
    env.files.write('modules/games/fixture/ui/remote.js', "var remote = 'https://example.com/game.js';");
    let hooks = 0, simulations = 0, sourceReads = 0, configReads = 0;
    env.shell.natives['test.card'] = () => { hooks++; };
    const offline = sim.offline;
    sim.offline = function (...args) { simulations++; return offline.apply(this, args); };
    const read = env.files.read;
    env.files.read = (p) => {
      if (/\.(js|html|css)$/.test(p)) sourceReads++;
      if (p === 'modules/games/fixture/config.json') configReads++;
      return read(p);
    };
    const query = env.db('games').query;
    let largestSave = 0;
    env.db('games').query = (sql, args) => {
      const rows = query(sql, args);
      for (const row of rows) if (typeof row.state === 'string') largestSave = Math.max(largestSave, row.state.length);
      return rows;
    };

    const quick = main.catalog({ details: false });
    const item = quick.games.find(g => g.id === 'fixture');
    assert.strictEqual(item.card, state.card);
    assert.strictEqual(item.seq, 1);
    assert.strictEqual(item.savedAt, old);
    assert.ok(item.badge);
    assert.strictEqual(item.offline, null, 'unscanned sources are unknown, not offline-safe');
    assert.strictEqual(item.detailsPending, true);
    assert.deepStrictEqual([simulations, sourceReads, configReads, hooks], [0, 0, 0, 0]);
    assert.ok(largestSave <= 600, 'only the bounded card tail crosses the database bridge');
    assert.deepStrictEqual(quick.modules, []);
    assert.strictEqual(env.shell.spawned.length, 0, 'step collection waits for the extras request');

    const detail = main.catalogDetails('fixture');
    assert.deepStrictEqual([detail.id, detail.seq, detail.savedAt, detail.detailsPending], ['fixture', 1, old, false]);
    assert.ok(detail.accrued && Number(detail.accrued.amount.split('e')[0]) > 0);
    assert.strictEqual(detail.offline.ok, false);
    assert.ok(detail.offline.hits.some(hit => hit.path.endsWith('remote.js')));
    assert.strictEqual(hooks, 0, 'one game detail never loads another module');
    assert.ok(simulations > 0 && sourceReads > 0);
    const saved = query('select state from saves where game=? order by seq desc limit 1', ['fixture'])[0].state;
    assert.strictEqual(saved, JSON.stringify(state), 'preview must not advance the actual save');

    const extras = main.catalogExtras();
    assert.strictEqual(hooks, 1);
    assert.strictEqual(extras.modules[0].text, 'Готово');
    assert.ok(extras.links.steps && extras.links.passenger && extras.links.bet);
    assert.ok(env.shell.spawned.length > 0);
  } finally { env.close(); }
});

test('source scan cache follows edits, additions and removals; explicit check is always fresh', () => {
  const env = workspace({ features: [] });
  try {
    const main = env.require('modules/games/main.js');
    const read = env.files.read;
    let sources = 0;
    env.files.read = (p) => { if (/\.(js|html|css)$/.test(p)) sources++; return read(p); };
    assert.ok(main.catalogDetails('fixture').offline.ok);
    assert.ok(sources > 0);
    sources = 0;
    assert.ok(main.catalogDetails('fixture').offline.ok);
    assert.strictEqual(sources, 0, 'unchanged source files are not read again');

    const file = 'modules/games/fixture/ui/remote.js';
    env.files.write(file, "var remote = 'https://first.example/game.js';");
    assert.strictEqual(main.catalogDetails('fixture').offline.ok, false, 'added source is checked');
    const absolute = env.files.path(file);
    const before = fs.statSync(absolute);
    env.files.write(file, 'var remote = null;');
    fs.utimesSync(absolute, before.atime, new Date(before.mtimeMs + 2000));
    sources = 0;
    assert.strictEqual(main.catalogDetails('fixture').offline.ok, true, 'modified source is checked');
    assert.strictEqual(sources, 1, 'only the changed source is reread');

    const sameTime = fs.statSync(absolute);
    env.files.write(file, "var remote = 'https://next.example/game.js';");
    fs.utimesSync(absolute, sameTime.atime, sameTime.mtime);
    assert.strictEqual(main.offlineCheck('fixture').ok, false, 'manual check bypasses timestamps');
    env.files.remove(file);
    assert.strictEqual(main.catalogDetails('fixture').offline.ok, true, 'deleted source leaves no stale hit');
  } finally { env.close(); }
});

test('unreadable sources stay unknown and deleted games cannot return stale details', () => {
  const env = workspace({ features: [] });
  try {
    const main = env.require('modules/games/main.js');
    const read = env.files.read;
    env.files.read = (p) => { if (p.endsWith('/ui/index.html')) throw new Error('Cannot read source'); return read(p); };
    const detail = main.catalogDetails('fixture');
    assert.strictEqual(detail.offline.ok, null);
    assert.match(detail.offline.error, /Cannot read source/);
    env.files.remove('modules/games/fixture');
    assert.throws(() => main.catalogDetails('fixture'), /не установлена/);
  } finally { env.close(); }
});

done('catalog');


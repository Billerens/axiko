// The standard checks of a game (engine/checks-kit.js) run by lib/check.js as module_check runs
// them on the phone: for the fixture «Шахта» and for a copy of the template «Искры» — the pace
// bot, offline formula against steps, old saves, no network. The screen check needs the phone
// (ui.openWait), so here it is expected to fail on that alone. node scripts/games/kit.test.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { test, done, workspace, gameOf, HUB } = require('./harness');

const env = workspace();
process.on('exit', () => env.close());
fs.cpSync(path.join(HUB, 'engine', 'template'), path.join(env.root, 'modules', 'games', 'sparks'), { recursive: true });
const check = env.require('lib/check.js');
const kit = env.require('modules/games/engine/checks-kit.js');

function run(id) {
  const out = check.run('games/' + id);
  const bad = out.results.filter((r) => !r.ok && !/экран/.test(r.name));
  assert.ok(!out.missing, 'нет checks.js');
  assert.deepStrictEqual(bad.map((r) => r.name + ': ' + r.error), []);
  const screen = out.results.find((r) => /экран/.test(r.name));
  assert.ok(screen && !screen.ok && /ui\.openWait/.test(screen.error), 'проверка экрана без телефона: ' + JSON.stringify(screen));
  return out;
}

test('стандартные проверки «Шахты»', () => {
  const out = run('fixture');
  assert.ok(out.results.length >= 7, out.results.map((r) => r.name).join(', '));
  assert.ok(out.results.find((r) => /fixtures/.test(r.name)).ok, 'v1 читается');
});

test('стандартные проверки копии шаблона и его собственные', () => {
  const out = run('sparks');
  assert.ok(out.results.some((r) => r.name === 'перековка: уголь растёт, забег с нуля' && r.ok));
});

test('темп: числа бота для обеих игр', () => {
  for (const id of ['fixture', 'sparks']) {
    const x = gameOf(env, id);
    const r = kit.bot(x.game, x.sim, x.cfg, id === 'fixture' ? {
      play: (state, ctx, sim, taps) => {
        for (let i = 0; i < taps; i++) sim.dig(state, ctx.config, ctx.mult);
        let n = 0;
        while (n < 50 && sim.buy(state, ctx.config)) n++;
        return n;
      }
    } : {});
    console.log('  ' + id + ': первая покупка на ' + r.firstBuySec + ' с, престижи ' + JSON.stringify(r.prestiges.slice(0, 4)) + ' из ' + r.prestiges.length);
    assert.ok(r.firstBuySec <= 10);
    assert.ok(r.prestiges.length && r.prestiges[0].trip >= 3 && r.prestiges[0].trip <= 6);
  }
});

test('id игры из папки', () => {
  assert.strictEqual(kit.idOf('/data/user/0/app.assi/files/workspace/modules/games/zz-check'), 'zz-check');
  assert.strictEqual(kit.idOf('modules/games/fixture/'), 'fixture');
  assert.strictEqual(kit.idOf('C:\\ws\\modules\\games\\daily\\2026-11-14'), 'daily/2026-11-14');
  assert.throws(() => kit.idOf('/somewhere/else'), /Не папка игры/);
});

done('kit');

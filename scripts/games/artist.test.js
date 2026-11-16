// The model's artist end to end: a game declares "artist" on a stock kind, the stock asks with the
// bases and references, the screen takes the pictures of the seen numbers out of the stock; and
// lib/art for any module outside the games. node scripts/games/artist.test.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { test, done, workspace } = require('./harness');

const env = workspace();
const ID = 'fixture';
fs.copyFileSync(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'factory', 'lib', 'art.js'), path.join(env.root, 'lib', 'art.js'));
const g = env.files.readJson('modules/games/fixture/game.json');
g.stock.push({ kind: 'carts', title: 'Рисунки вагонеток', min: 2, target: 4, batch: 2, prompt: 'prompts/carts.md',
  artist: { key: 'level', base: 'art-base.js#base', content: 'content/carts.json', noun: 'Вагонетка' } });
env.files.writeJson('modules/games/fixture/game.json', g);
env.files.writeJson('modules/games/fixture/content/carts.json', { names: ['Тачка', 'Бочка'] });
env.files.write('modules/games/fixture/art-base.js',
  'exports.base = function (n, c) { return { svg: \'<svg viewBox="0 0 64 64"><rect x="8" y="20" width="48" height="24" fill="#8a7458" data-n="\' + n + \'"/></svg>\', title: c.names[n - 1] || null }; };');
env.files.write('modules/games/fixture/prompts/carts.md', 'Нарисуй {count} вагонеток, темы меняй смело.\n\n```schema\n' +
  '{"key": "level", "fields": {"level": {"type": "number", "min": 1, "max": 1000}, "title": {"type": "string", "min": 2, "max": 40},' +
  ' "text": {"type": "string", "min": 3, "max": 100}, "svg": {"type": "svg", "size": 64, "max": 4000, "shapes": 40, "animate": true, "animations": 4}}}\n```\n');
const main = env.require('modules/games/main.js');

function cart(level, title, extra) {
  return {
    level, title, text: 'Едет сама по рельсам',
    svg: '<svg viewBox="0 0 64 64"><rect x="8" y="20" width="48" height="24" fill="#a0522d">' +
      '<animate attributeName="opacity" values="1;.6;1" dur="2s" repeatCount="indefinite"/></rect>' + (extra || '') + '</svg>'
  };
}

function hubOf() {
  let hub = null;
  const passenger = env.require('modules/games/links/passenger.js');
  const orig = passenger.status;
  passenger.status = (h, c, gg) => {
    hub = h;
    return orig(h, c, gg);
  };
  main.links(ID);
  passenger.status = orig;
  return hub;
}

test('настройки «Рисунки» появляются у игры с художником', () => {
  const s = main.settings(ID);
  assert.strictEqual(s.values['art.carts.on'], true);
  assert.strictEqual(s.values['art.carts.ahead'], 6);
  assert.strictEqual(s.schema['art.carts.on'].group, 'Рисунки');
});

test('запрос в запас: номера с базами и готовыми именами, перед схемой; рисунки проверяются', () => {
  const stock = env.require('modules/games/stock.js');
  let seen = null;
  env.shell.ai = (request) => {
    seen = request;
    return [cart(1, 'Тачка'), cart(2, 'Бочка'), { level: 3, title: 'Плохая', text: 'Со скриптом внутри', svg: '<svg viewBox="0 0 64 64"><script>x()</script></svg>' }];
  };
  const r = stock.fill(hubOf(), main.game(ID), 'carts', {});
  env.shell.ai = null;
  assert.ok(/Вагонетка 1 — «Тачка» \(имя готово/.test(seen.prompt), seen.prompt);
  assert.ok(/Вагонетка 2 — «Бочка»/.test(seen.prompt));
  assert.ok(/База: <svg[^>]*><rect[^>]*data-n="1"/.test(seen.prompt));
  assert.ok(/Можно оживить/.test(seen.system), 'правила анимации в запросе');
  assert.strictEqual(r.added, 2, JSON.stringify(r));
  assert.strictEqual(r.rejected, 1);
});

test('экран: рисунки увиденных номеров уходят из запаса в art-<вид>.json, ровно один раз', () => {
  let r = main.seriesSync(ID, 'carts', 1, [1]);
  assert.strictEqual(r.on, true);
  assert.deepStrictEqual(Object.keys(r.items), ['1']);
  assert.strictEqual(r.items[1].title, 'Тачка');
  assert.ok(/<animate/.test(r.items[1].svg));
  assert.strictEqual(main.stockStatus(ID).carts, 1, 'номер 2 ещё ждёт в запасе');
  r = main.seriesSync(ID, 'carts', 2, [1, 2, 5]);
  assert.deepStrictEqual(Object.keys(r.items).sort(), ['1', '2']);
  assert.strictEqual(main.stockStatus(ID).carts, 0);
  const kept = env.files.readJson('data/games/fixture/art-carts.json');
  assert.strictEqual(kept.best, 2);
  assert.deepStrictEqual(Object.keys(kept.items).sort(), ['1', '2']);
  const st = main.seriesStatus(ID, 'carts');
  assert.deepStrictEqual(st.next, [3, 4, 5]);
});

test('следующий запрос рисует дальше и даёт образцом рисунок предыдущего номера', () => {
  const stock = env.require('modules/games/stock.js');
  let seen = null;
  env.shell.ai = (request) => {
    seen = request;
    return [cart(3, 'Карп-вагонетка'), cart(4, 'Дубовая')];
  };
  stock.fill(hubOf(), main.game(ID), 'carts', {});
  env.shell.ai = null;
  assert.ok(/Вагонетка 3 — имя придумай сам/.test(seen.prompt), seen.prompt);
  assert.ok(/Рисунок предыдущего 2 «Бочка»/.test(seen.prompt));
});

test('выключенный художник ничего не отдаёт', () => {
  main.setSetting(ID, 'art.carts.on', false);
  const r = main.seriesSync(ID, 'carts', 3, [1, 2, 3]);
  assert.strictEqual(r.on, false);
  assert.deepStrictEqual(r.items, {});
  main.setSetting(ID, 'art.carts.on', null);
});

test('lib/art вне Игротеки: разовый рисунок и серия в файле модуля', () => {
  const art = env.require('lib/art');
  env.shell.ai = (request) => {
    assert.ok(/Значки привычек/.test(request.prompt));
    return { items: [{ title: 'Вода', svg: '<svg viewBox="0 0 64 64"><circle cx="32" cy="32" r="20" fill="#3a8ee6"/></svg>' }, { title: 'Пусто', svg: '<div/>' }] };
  };
  const one = art.draw({ brief: 'Значки привычек', count: 2, svg: { size: 64 } });
  assert.strictEqual(one.length, 1);
  assert.strictEqual(one[0].title, 'Вода');

  const s = art.series({
    file: 'data/habits/art-ranks.json', brief: 'Знаки званий, каждый богаче', noun: 'Ранг',
    base: (n) => ({ svg: '<svg viewBox="0 0 64 64"><rect x="10" y="10" width="44" height="44" fill="#777777"/></svg>', title: n === 1 ? 'Новичок' : null }),
    svg: { size: 64, animate: true }
  });
  let asked = null;
  env.shell.ai = (request) => {
    asked = request;
    return [Object.assign(cart(1, 'Свой'), { n: 1 }), Object.assign(cart(2, 'Старшина'), { n: 2 }), Object.assign(cart(9, 'Лишний'), { n: 9 })];
  };
  const got = s.ahead(1, { ahead: 1, want: 2 });
  env.shell.ai = null;
  assert.ok(/Ранг 1 — «Новичок»/.test(asked.prompt), asked.prompt);
  assert.deepStrictEqual(Object.keys(got).sort(), ['1', '2']);
  assert.strictEqual(got[1].title, 'Новичок', 'готовое имя важнее');
  assert.deepStrictEqual(Object.keys(s.get([1, 2, 3])).sort(), ['1', '2']);
  assert.deepStrictEqual(s.status().next, [3, 4, 5]);
  assert.strictEqual(typeof art.sprite, 'function');
  assert.ok(/<svg/.test(art.svg(art.sprite({ seed: 'ёж', mask: 'beast', hue: 30 }))));
});

done('artist');

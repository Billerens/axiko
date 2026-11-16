// A series the model's artist draws (engine/series.js): which numbers come next, the request with
// the bases and references, the stock, what a number keeps, the settings a game gets.
// node scripts/games/series.test.js
'use strict';
const assert = require('assert');
const { test, done, engine } = require('./harness');
const Series = engine('series.js');
const Core = engine('core.js');

test('сначала номера рядом с рекордом, потом ранние, потом дальше', () => {
  const drawn = (set) => (n) => set.indexOf(n) >= 0;
  assert.deepStrictEqual(Series.todo(drawn([]), { best: 20, ahead: 3, recent: 2, want: 3 }), [18, 19, 20]);
  assert.deepStrictEqual(Series.todo(drawn([18, 19, 20, 21]), { best: 20, ahead: 3, recent: 2, want: 3 }), [1, 22, 23]);
  const near = [];
  for (let n = 1; n <= 26; n++) near.push(n);
  assert.deepStrictEqual(Series.todo(drawn(near), { best: 20, ahead: 6, recent: 2, want: 2 }), [27, 28]);
});

test('запрос: база каждого номера, имя, рисунок предыдущего — перед схемой', () => {
  const md = 'Нарисуй {count}.\n\n```schema\n{"key": "level"}\n```\n';
  const out = Series.prompt({
    base: md, numbers: [5, 6, 9], noun: 'Уровень', previous: 1,
    baseOf: (n) => ({ svg: '<svg data-n="' + n + '"/>', title: n === 5 ? 'Бронза' : null }),
    pictureOf: (n) => (n === 4 || n === 8 ? { title: 'Был ' + n, theme: 'рыбный', svg: '<svg data-was="' + n + '"/>' } : null)
  });
  assert.ok(out.indexOf('```schema') > out.indexOf('Уровень 9'), out);
  assert.ok(/Уровень 5 — «Бронза» \(имя готово/.test(out));
  assert.ok(/Уровень 6 — имя придумай сам/.test(out));
  assert.ok(/data-was="4"/.test(out), 'образец для 5');
  assert.ok(/Предыдущий — тот, что ты рисуешь выше/.test(out), '6 растёт из 5 этого же ответа');
  assert.ok(!/data-was="8"/.test(out), 'образцов не больше previous');
  assert.ok(/тема: рыбный/.test(out));
});

test('запас: по номеру, неиспользованный важнее использованного', () => {
  const list = [
    { kind: 'blades', usedAt: 5, item: { level: 3, svg: 'a' } },
    { kind: 'blades', usedAt: null, item: { level: 3, svg: 'b' } },
    { kind: 'blades', usedAt: null, item: { level: 2.5, svg: 'x' } },
    { kind: 'monsters', usedAt: null, item: { level: 4 } }
  ];
  const s = Series.inStock(list, 'blades', 'level');
  assert.deepStrictEqual(Object.keys(s), ['3']);
  assert.strictEqual(s[3].item.svg, 'b');
  assert.strictEqual(s[3].used, false);
});

test('что хранит номер: готовое имя базы важнее имени художника', () => {
  const k = Series.keep({ level: 3, title: 'Свой', text: 'т', theme: 'деревянный', svg: '<svg/>', extra: 1 }, { title: 'Бронза' });
  assert.deepStrictEqual(k, { title: 'Бронза', text: 'т', theme: 'деревянный', svg: '<svg/>' });
  assert.strictEqual(Series.keep({ title: 'Карп-клинок', svg: 's' }, { title: null }).title, 'Карп-клинок');
});

test('игра с художником получает настройки «Рисунки», config.json игры их перекрывает', () => {
  const game = { id: 'x', stock: [{ kind: 'blades', title: 'Клинки', artist: { key: 'level', base: 'sim.js#artBase' } }, { kind: 'monsters' }] };
  const sch = Core.schema({ 'art.blades.ahead': { value: 9, title: 'своё', min: 1, max: 20 } }, game);
  assert.strictEqual(sch['art.blades.on'].value, true);
  assert.strictEqual(sch['art.blades.on'].group, 'Рисунки');
  assert.strictEqual(sch['art.blades.ahead'].value, 9);
  assert.ok(!sch['art.monsters.on']);
  const cfg = Core.config(sch, {}, { 'art.blades.animate': false });
  const o = Series.options(cfg, 'blades');
  assert.deepStrictEqual(o, { on: true, animate: false, ahead: 9, recent: 10, nods: false });
});

test('направление художник придумывает сам; бывшие темы — в запросе, свежие первыми, без повторов', () => {
  const recent = Series.recentThemes({ 1: { theme: 'Рыбный' }, 2: { theme: 'деревянный' }, 3: { theme: 'рыбный' }, 4: {}, 9: { theme: 'аниме-меха' } }, 15);
  assert.deepStrictEqual(recent, ['аниме-меха', 'рыбный', 'деревянный']);
  const out = Series.prompt({ base: '', numbers: [1, 2], recent, baseOf: (n) => ({ svg: '<svg/>', title: n === 1 ? 'Нож' : null }) });
  assert.ok(/Недавние темы \(для разнообразия; вернуться к теме можно, если по-новому\): аниме-меха, рыбный, деревянный\./.test(out), out);
  assert.ok(!/«Нож» \(имя готово, повтори его\)\.\nНаправление/.test(out), 'у готового имени направление не навязывается');
  assert.ok(/Номер 2 — имя придумай сам\.\nНаправление придумай сам/.test(out), out);
});

done('series');

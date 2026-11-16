// The instructions of a module (ui/kit/guide.js render): sections, nested lists, ticks, tables
// with alignment, notes, diagrams, safe links and pictures. node scripts/games/guide.test.js
'use strict';
const assert = require('assert');
const path = require('path');
const { test, done } = require('./harness');

global.window = {};
require(path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'factory', 'ui', 'kit', 'guide.js'));
const render = window.__guide.render;

const MD = [
  '# Кузница',
  'Вступление с `кодом <b>`, **жирным** и [ссылкой](#как-играть).',
  '',
  '## Как играть',
  '1. Перетащите',
  '   - на такой же',
  '2. Тап',
  '- [x] горн',
  '- [ ] волна 60',
  '',
  '> **Совет:** держите клетку свободной.',
  '',
  '## Цифры',
  '| Что | Сколько | Где |',
  '|:--|--:|:-:|',
  '| Урон | 2,35× | клинок |',
  '',
  '```mermaid',
  'flowchart TD',
  '  A[Горн] --> B[Наковальня]',
  '```',
  '---',
  '![кадр](shots/a.png) ![чужое](javascript:alert(1)) ![выше](../x.png) [сайт](https://example.org) [плохая](javascript:x)'
].join('\n');

test('название, разделы — оглавление, вложенные списки и галочки', () => {
  const r = render(MD, '/modules/games/merge-forge/');
  assert.strictEqual(r.title, 'Кузница');
  assert.deepStrictEqual(r.sections.map((s) => s.title), ['Как играть', 'Цифры']);
  assert.ok(/<ol><li>Перетащите<ul><li>на такой же<\/li><\/ul><\/li><li>Тап<\/li><\/ol><ul><li class="gd-task done">/.test(r.html), r.html);
  assert.ok(/<li class="gd-task"><span class="gd-tick"><\/span>волна 60/.test(r.html));
});

test('заметки, таблица с выравниванием, схема, код экранируется', () => {
  const r = render(MD, '/m/');
  assert.ok(/<blockquote class="gd-note tip"><p><b>Совет:<\/b>/.test(r.html));
  assert.ok(/<th style="text-align:right">Сколько<\/th><th style="text-align:center">Где<\/th>/.test(r.html));
  assert.ok(/<div class="gd-diagram" data-src="flowchart TD\n  A\[Горн\] --&gt; B\[Наковальня\]">/.test(r.html), r.html);
  assert.ok(/<code>кодом &lt;b&gt;<\/code>/.test(r.html));
  assert.ok(/<a href="#как-играть" data-anchor>ссылкой<\/a>/.test(r.html));
  assert.ok(/<hr>/.test(r.html));
});

test('картинки и ссылки — только свои файлы рядом и https', () => {
  const r = render(MD, '/modules/x/');
  assert.ok(/<img class="gd-img" src="\/modules\/x\/shots\/a.png"/.test(r.html));
  assert.ok(!/javascript:|\.\.\/x\.png/.test(r.html), r.html);
  assert.ok(/<a href="https:\/\/example.org">сайт<\/a>/.test(r.html));
  assert.ok(/ плохая/.test(r.html));
});

done('guide');

// Pictures of the model made safe (engine/art.js clean): shapes, gradients, and — when a game
// allows them — animation and a glow. node scripts/games/art.test.js
'use strict';
const assert = require('assert');
const { test, done, engine } = require('./harness');
const Art = engine('art.js');

const BLADE = '<svg viewBox="0 0 64 64">' +
  '<defs><linearGradient id="m"><stop offset="0" stop-color="#ddeeff"><animate attributeName="stop-color" values="#ddeeff;#aaccff;#ddeeff" dur="3s" repeatCount="indefinite"/></stop>' +
  '<stop offset="1" stop-color="#8899aa"/></linearGradient>' +
  '<filter id="glow"><feGaussianBlur stdDeviation="2" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>' +
  '<path d="M10 54L50 14l4 0 0 4L14 58z" fill="url(#m)" filter="url(#glow)"/>' +
  '<circle cx="50" cy="14" r="2" fill="#ffffff"><animate attributeName="opacity" values="0;1;0" dur="1.5s" repeatCount="indefinite"/></circle>' +
  '<g><animateTransform attributeName="transform" type="rotate" values="0 32 32;360 32 32" dur="8s" repeatCount="indefinite"/><rect x="2" y="2" width="4" height="4" fill="#ffcc00"/></g>' +
  '</svg>';

test('без разрешения анимация и свечение выбрасываются, рисунок остаётся', () => {
  const r = Art.clean(BLADE, { size: 64 });
  assert.ok(r.svg, r.error);
  assert.ok(!/animate|filter|feGaussian/.test(r.svg), r.svg);
  assert.strictEqual(r.shapes, 3);
  assert.strictEqual(r.animations, 0);
});

test('с разрешением анимация и свечение проходят', () => {
  const r = Art.clean(BLADE, { size: 64, animate: true, effects: true });
  assert.ok(r.svg, r.error);
  assert.strictEqual(r.animations, 3);
  assert.ok(/<animateTransform attributeName="transform" type="rotate"/.test(r.svg));
  assert.ok(/<filter id="glow"><feGaussianBlur stdDeviation="2" result="b"\/>/.test(r.svg));
  assert.ok(/filter="url\(#glow\)"/.test(r.svg));
});

test('still убирает анимацию, фигуры остаются', () => {
  const r = Art.clean(BLADE, { size: 64, animate: true, effects: true });
  const s = Art.still(r.svg);
  assert.ok(!/animate/.test(s));
  assert.ok(/<circle/.test(s) && /<rect/.test(s) && /<\/g>/.test(s));
});

test('мерцание быстрее 0,3 с, лишние анимации и чужие атрибуты — отказ', () => {
  const fast = '<svg viewBox="0 0 64 64"><circle cx="1" cy="1" r="1" fill="#fff"><animate attributeName="opacity" values="0;1" dur="100ms" repeatCount="indefinite"/></circle></svg>';
  assert.ok(/быстрее/.test(Art.clean(fast, { size: 64, animate: true }).error));
  let many = '<svg viewBox="0 0 64 64"><circle cx="1" cy="1" r="1" fill="#fff">';
  for (let i = 0; i < 5; i++) many += '<animate attributeName="r" values="1;2;1" dur="2s" repeatCount="indefinite"/>';
  many += '</circle></svg>';
  assert.ok(/анимаций/.test(Art.clean(many, { size: 64, animate: true, animations: 4 }).error));
  const href = '<svg viewBox="0 0 64 64"><circle cx="1" cy="1" r="1"><animate href="#x" attributeName="r" values="1;2" dur="2s"/></circle></svg>';
  assert.ok(/запрещённый атрибут/.test(Art.clean(href, { size: 64, animate: true }).error));
  const what = '<svg viewBox="0 0 64 64"><circle cx="1" cy="1" r="1"><animate attributeName="href" values="a;b" dur="2s"/></circle></svg>';
  assert.ok(/плохое значение/.test(Art.clean(what, { size: 64, animate: true }).error));
  const outside = '<svg viewBox="0 0 64 64"><feGaussianBlur stdDeviation="2"/><circle cx="1" cy="1" r="1"/></svg>';
  assert.ok(/вне <filter>/.test(Art.clean(outside, { size: 64, effects: true }).error));
  const script = '<svg viewBox="0 0 64 64"><circle cx="1" cy="1" r="1" fill="#fff"><animate attributeName="opacity" values="javascript:1" dur="2s"/></circle></svg>';
  assert.ok(Art.clean(script, { size: 64, animate: true }).error);
});

test('цвета анимации проверяются по палитре', () => {
  const pic = '<svg viewBox="0 0 64 64"><circle cx="1" cy="1" r="1" fill="#ffffff"><animate attributeName="fill" values="#ffffff;#ff0000" dur="2s" repeatCount="indefinite"/></circle></svg>';
  assert.ok(Art.clean(pic, { size: 64, animate: true, palette: ['#ffffff', '#ff0000'] }).svg);
  assert.ok(/плохое значение/.test(Art.clean(pic, { size: 64, animate: true, palette: ['#ffffff'] }).error));
});

test('правила для модели говорят про анимацию, только когда она разрешена', () => {
  assert.ok(!/animate/.test(Art.svgRules({ size: 64 })));
  const rules = Art.svgRules({ size: 64, animate: true, effects: true, max: 6000, shapes: 60 });
  assert.ok(/animateTransform/.test(rules) && /feGaussianBlur/.test(rules) && /6000 знаков/.test(rules) && /60 фигур/.test(rules));
});

test('градиент может бежать: анимация gradientTransform и концов градиента', () => {
  const svg = '<svg viewBox="0 0 64 64"><defs><linearGradient id="c" x1="0" y1="0" x2="1" y2="1">' +
    '<animateTransform attributeName="gradientTransform" type="rotate" values="0 .5 .5;360 .5 .5" dur="4s" repeatCount="indefinite"/>' +
    '<animate attributeName="x2" values="1;.6;1" dur="3s" repeatCount="indefinite"/>' +
    '<stop offset="0" stop-color="#ff88aa"/><stop offset="1" stop-color="#ffffff"/></linearGradient></defs>' +
    '<path d="M10 54L50 14l4 4-40 40z" fill="url(#c)"/></svg>';
  const r = Art.clean(svg, { size: 64, animate: true });
  assert.ok(r.svg, r.error);
  assert.strictEqual(r.animations, 2);
});

done('art');

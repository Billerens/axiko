// Russian number formatting: thresholds, rounding modes, units, words. node scripts/games/fmt.test.js
'use strict';
const assert = require('assert');
const { test, done, engine } = require('./harness');
const Fmt = engine('fmt.js');

// The output uses a no-break space before units (~ here) and a narrow one in groups (^ here).
const show = (s) => s.replace(/ /g, '~').replace(/ /g, '^');
const eq = (actual, expected, why) => assert.strictEqual(show(actual), expected, why);

test('малые числа: до трёх значащих, вниз', () => {
  eq(Fmt.num(0), '0');
  eq(Fmt.num(7), '7');
  eq(Fmt.num(2.5), '2,5');
  eq(Fmt.num(9.999), '9,99');
  eq(Fmt.num(12.5), '12,5');
  eq(Fmt.num(99.99), '99,9');
  eq(Fmt.num(123.456), '123');
  eq(Fmt.num(999.9), '999');
  eq(Fmt.num(-0.5), '−0,5');
});

test('тысячи группами до миллиона', () => {
  eq(Fmt.num(1000), '1^000');
  eq(Fmt.num(12345), '12^345');
  eq(Fmt.num(999999), '999^999');
  eq(Fmt.num(999999.9), '999^999');
  eq(Fmt.num(-1234), '−1^234');
});

test('слова от миллиона, наука от 1e15', () => {
  eq(Fmt.num(1e6), '1~млн');
  eq(Fmt.num(1234567), '1,23~млн');
  eq(Fmt.num(9.999e6), '9,99~млн');
  eq(Fmt.num(1e9), '1~млрд');
  eq(Fmt.num(1.5e12), '1,5~трлн');
  eq(Fmt.num(1e14), '100~трлн');
  eq(Fmt.num(9.995e14), '999~трлн', 'вниз — ещё не 1e15');
  eq(Fmt.num(1e15), '1e15');
  eq(Fmt.num('4.5e42'), '4,5e42');
  eq(Fmt.num(4.5e42), '4,5e42');
  eq(Fmt.num(1e20, { sciFrom: 24 }), '100~квинтлн');
  eq(Fmt.num(1e33, { mode: 'words-long' }), '1~децлн');
  eq(Fmt.num(1e36, { mode: 'words-long' }), '1e36', 'слов дальше нет');
});

test('цена округляется вверх: «хватает» значит хватает', () => {
  eq(Fmt.price(9.999), '10');
  eq(Fmt.price(99.99), '100');
  eq(Fmt.price(123.456), '124');
  eq(Fmt.price(999.9), '1^000');
  eq(Fmt.price(999.95), '1^000');
  eq(Fmt.price(999999.9), '1~млн');
  eq(Fmt.price(1234567), '1,24~млн');
  eq(Fmt.price(9.999e6), '10~млн');
  eq(Fmt.price(9.995e14), '1e15');
  eq(Fmt.price(10.01), '10,1');
  eq(Fmt.price(0.001), '0,01');
});

test('живой счётчик держит нули, чтобы не прыгал', () => {
  eq(Fmt.live(7), '7,00');
  eq(Fmt.live(2.5), '2,50');
  eq(Fmt.live(1e6), '1,00~млн');
  eq(Fmt.live('1.5e6'), '1,50~млн');
  eq(Fmt.live(4.5e42), '4,50e42');
});

test('режимы sci, eng, digits, язык en', () => {
  eq(Fmt.num(1234567, { mode: 'sci' }), '1,23e6');
  eq(Fmt.num(12345678, { mode: 'eng' }), '12,3e6');
  eq(Fmt.num(1.23456e9, { digits: 5 }), '1,2345~млрд');
  eq(Fmt.num(1234567, { lang: 'en' }), '1.23~M');
  eq(Fmt.num(1234.5, { lang: 'en' }), '1,234');
  Fmt.setup({ lang: 'en' });
  eq(Fmt.time(185), '3~min 05~s');
  Fmt.setup({ lang: 'ru' });
  eq(Fmt.time(185), '3~мин 05~с');
});

test('доход, множитель, проценты', () => {
  eq(Fmt.rate(1234567), '+1,23~млн/с');
  eq(Fmt.rate(-5), '−5/с');
  eq(Fmt.mult(2.5), '×2,5');
  eq(Fmt.pct(0.15), '15~%');
  eq(Fmt.pct(1), '100~%');
});

test('время и «назад»', () => {
  eq(Fmt.time(0), '0~с');
  eq(Fmt.time(45), '45~с');
  eq(Fmt.time(180), '3~мин');
  eq(Fmt.time(185), '3~мин 05~с');
  eq(Fmt.time(8040), '2~ч 14~мин');
  eq(Fmt.time(3 * 86400 + 4 * 3600), '3~д 4~ч');
  eq(Fmt.ago(0, 30000), 'только что');
  eq(Fmt.ago(0, 5 * 60000), '5~мин назад');
  eq(Fmt.ago(0, 2 * 3600000), '2~ч назад');
});

test('склонения', () => {
  const p = (n) => Fmt.plural(n, 'монета', 'монеты', 'монет');
  assert.deepStrictEqual([1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111, 112].map(p),
    ['монета', 'монеты', 'монеты', 'монет', 'монет', 'монет', 'монет', 'монета', 'монеты', 'монет', 'монета', 'монет', 'монет']);
  assert.strictEqual(p(1.5), 'монеты');
  assert.strictEqual(p('1e20'), 'монет');
  eq(Fmt.count(5, 'монета', 'монеты', 'монет'), '5~монет');
});

test('для голоса — словами', () => {
  assert.strictEqual(Fmt.say(21), 'двадцать один');
  assert.strictEqual(Fmt.say(1234), 'одна тысяча двести тридцать четыре');
  assert.strictEqual(Fmt.say(0.5), 'половина');
  assert.strictEqual(Fmt.say(-3), 'минус три');
  assert.strictEqual(Fmt.say(1e6), 'один миллион');
  assert.strictEqual(Fmt.say(2e6), 'два миллиона');
  assert.strictEqual(Fmt.say(5e9), 'пять миллиардов');
  assert.strictEqual(Fmt.say(1.23e6), 'одна целая двадцать три сотых миллиона');
  assert.strictEqual(Fmt.say(4.5e6), 'четыре с половиной миллиона');
  assert.strictEqual(Fmt.say('4.5e42'), 'четыре с половиной на десять в сорок второй');
  assert.strictEqual(Fmt.cardinal(1002001), 'один миллион две тысячи один');
  assert.strictEqual(Fmt.ordinal(42), 'сорок второй');
});

test('неверное значение не роняет экран', () => {
  assert.strictEqual(Fmt.num('мусор'), '?');
  assert.strictEqual(Fmt.num(undefined), '?');
});

done('fmt');

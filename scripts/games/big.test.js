// Big numbers: arithmetic, comparison, rounding, JSON. node scripts/games/big.test.js
'use strict';
const assert = require('assert');
const { test, done, engine } = require('./harness');
const Big = engine('big.js');

const S = (x) => Big.str(x);
const near = (a, b, rel) => {
  const x = Big.toNumber(a);
  assert.ok(Math.abs(x - b) <= Math.abs(b) * (rel || 1e-12), x + ' ≈ ' + b);
};

test('чтение и нормализация', () => {
  assert.strictEqual(S('4.512e9'), '4.512e9');
  assert.strictEqual(S('123.45'), '1.2345e2');
  assert.strictEqual(S('0.00012'), '1.2e-4');
  assert.strictEqual(S('-2.5e3'), '-2.5e3');
  assert.strictEqual(S('9.99e1234567'), '9.99e1234567');
  assert.strictEqual(S('0'), '0e0');
  assert.strictEqual(S('000.000'), '0e0');
  assert.strictEqual(S(1e300 * 10), '1e301');
  assert.strictEqual(S({ m: 25, e: 3 }), '2.5e4');
  assert.throws(() => Big.of('abc'));
  assert.throws(() => Big.of(NaN));
  assert.throws(() => Big.of(Infinity));
  assert.throws(() => Big.of(undefined));
  assert.strictEqual(S(Big.of(undefined, 7)), '7e0');
  assert.ok(Big.valid('1e5') && !Big.valid('NaN') && !Big.valid('1e5x'));
});

test('сложение и вычитание, в том числе разных порядков', () => {
  assert.strictEqual(S(Big.add(2, 3)), '5e0');
  assert.strictEqual(S(Big.add('9e0', '1e0')), '1e1');
  assert.strictEqual(S(Big.sub(5, 5)), '0e0');
  assert.strictEqual(S(Big.sub(3, 5)), '-2e0');
  assert.strictEqual(S(Big.add('1e100', '1e80')), '1e100', 'меньшее на 20 порядков отбрасывается');
  assert.strictEqual(S(Big.add('1e80', '1e100')), '1e100');
  near(Big.add('1e15', 1), 1e15 + 1);
  assert.strictEqual(S(Big.add(0.1, 0.2)), '3e-1', 'двенадцать знаков в JSON скрывают шум');
});

test('умножение, деление, степени, корни, логарифм', () => {
  assert.strictEqual(S(Big.mul('2e300', '5e300')), '1e601');
  assert.strictEqual(S(Big.div('1e601', '2e300')), '5e300');
  assert.throws(() => Big.div(1, 0));
  assert.strictEqual(S(Big.pow(2, 10)), '1.024e3');
  assert.strictEqual(S(Big.pow(10, 1000)), '1e1000');
  near(Big.pow(1.15, 50), Math.pow(1.15, 50), 1e-12);
  near(Big.pow('1e10', 0.5), 1e5, 1e-12);
  assert.strictEqual(S(Big.pow(2, -1)), '5e-1');
  assert.strictEqual(S(Big.pow(-2, 3)), '-8e0');
  assert.throws(() => Big.pow(-2, 0.5));
  assert.strictEqual(S(Big.sqrt('1e100')), '1e50');
  assert.strictEqual(S(Big.sqrt('1e101')), S(Big.mul(Math.sqrt(10), '1e50')));
  assert.strictEqual(Big.log10('1e1000'), 1000);
  assert.strictEqual(Big.log10(0), -Infinity);
  assert.strictEqual(S(Big.pow10(2.5)), S(Math.pow(10, 2.5)));
});

test('огромные порядки не переполняются и не виснут', () => {
  const huge = Big.pow('1e1000000', 1e9);
  assert.ok(huge.e > 1e14, 'порядок ' + huge.e);
  const t = Date.now();
  Big.pow('1.0000001', 1e12);
  assert.ok(Date.now() - t < 50, 'степень по логарифмам');
  const top = Big.mul('9e9000000000000000', '9e9000000000000000');
  assert.strictEqual(top.e, Big.MAX_E, 'выше MAX_E — потолок, не Infinity');
  assert.strictEqual(S(Big.mul('1e-9000000000000000', '1e-9000000000000000')), '0e0');
  assert.strictEqual(Big.toNumber('1e400'), Infinity);
});

test('сравнение', () => {
  assert.strictEqual(Big.cmp('1e10', '9e9'), 1);
  assert.strictEqual(Big.cmp('-1e10', '-9e9'), -1);
  assert.strictEqual(Big.cmp(0, '-1e-100'), 1);
  assert.strictEqual(Big.cmp('5e3', 5000), 0);
  assert.ok(Big.gte(5, 5) && Big.gt(6, 5) && Big.lt(4, 5) && Big.lte(5, 5) && Big.eq('1e0', 1));
  assert.strictEqual(S(Big.max('1e5', '2e4')), '1e5');
  assert.strictEqual(S(Big.min('1e5', '2e4')), '2e4');
  assert.ok(Big.isZero('0e0') && Big.sign('-3') === -1 && Big.sign(0) === 0);
});

test('округление до целого терпит шум последних знаков', () => {
  assert.strictEqual(S(Big.floor(17.999999999999996)), '1.8e1');
  assert.strictEqual(S(Big.floor(2.7)), '2e0');
  assert.strictEqual(S(Big.ceil(2.1)), '3e0');
  assert.strictEqual(S(Big.round(2.5)), '3e0');
  assert.strictEqual(S(Big.floor('-2.5')), '-3e0');
  assert.strictEqual(S(Big.floor('1e-5')), '0e0');
  assert.strictEqual(S(Big.ceil('1e-5')), '1e0');
  assert.strictEqual(S(Big.floor('1.5e20')), '1.5e20', 'большое уже целое');
});

test('JSON: туда и обратно', () => {
  const state = { a: Big.of('1.23456789012e345'), b: Big.of(-0.5), c: Big.ZERO, list: [Big.of(7)] };
  const text = JSON.stringify(state);
  assert.strictEqual(text, '{"a":"1.23456789012e345","b":"-5e-1","c":"0e0","list":["7e0"]}');
  const back = JSON.parse(text);
  assert.ok(Big.eq(back.a, state.a) && Big.eq(back.b, -0.5) && Big.isZero(back.c) && Big.eq(back.list[0], 7));
  for (const x of ['3.14159265359e0', '9.99999999999e99', '1e-300', '-4.2e1000000']) assert.strictEqual(S(Big.of(S(x))), S(x));
  assert.strictEqual(S('9.9999999999999e5'), '1e6', 'мантисса 9.9999… округляется в 1e+1');
});

test('накопитель addTo', () => {
  const acc = Big.acc(0);
  for (let i = 0; i < 1000; i++) Big.addTo(acc, 0.5);
  assert.strictEqual(S(acc), '5e2');
  assert.throws(() => Big.addTo(Big.ZERO, 1), /накопитель/);
  assert.strictEqual(S(Big.ZERO), '0e0', 'общий ноль не испорчен');
});

test('цены: costGeom и maxBuy', () => {
  near(Big.costGeom(10, 1.15, 0, 1), 10);
  near(Big.costGeom(10, 1.15, 3, 1), 10 * Math.pow(1.15, 3));
  near(Big.costGeom(10, 1.15, 0, 5), 10 * (Math.pow(1.15, 5) - 1) / 0.15, 1e-12);
  near(Big.costGeom(10, 1, 4, 3), 30);
  assert.strictEqual(Big.maxBuy(9, 10, 1.15, 0), 0);
  assert.strictEqual(Big.maxBuy(10, 10, 1.15, 0), 1);
  const budget = Big.costGeom(10, 1.15, 7, 12);
  assert.strictEqual(Big.maxBuy(budget, 10, 1.15, 7), 12, 'ровно на 12');
  assert.strictEqual(Big.maxBuy(Big.sub(budget, '1e-6'), 10, 1.15, 7), 11);
  assert.strictEqual(Big.maxBuy(100, 10, 1, 0), 10);
  assert.ok(Big.maxBuy('1e1000', 10, 1.15, 0) > 16000);
});

done('big');

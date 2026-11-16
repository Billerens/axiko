// Big numbers for the games of the hub: a value is a pair {m, e} meaning m * 10^e, with
// 1 <= |m| < 10 (or m = 0, e = 0) and an integer exponent between -9e15 and 9e15.
// Precision is that of a double (15 significant digits); "without loss" means no overflow to
// Infinity and no hang on huge exponents: when exponents differ by more than 15, the smaller
// addend is dropped.
//
// Shared subset of JavaScript: the same file runs on a screen (window.Big), in the engine
// (Rhino, require('modules/games/engine/big.js')) and in node. ES5 only: no class, no arrows,
// no async, no spread.
//
// Values are immutable; every operation takes Big objects, numbers or strings ("4.512e9") and
// returns a new Big. In JSON a Big is the string "<mantissa>e<exponent>" (toJSON does it), so a
// state with Big objects in it is saved as plain JSON and read back as strings, which every
// operation accepts again. For hot loops: var acc = Big.acc(0); Big.addTo(acc, x).
(function (root, make) {
  var api = make();
  if (typeof exports !== 'undefined') { for (var k in api) exports[k] = api[k]; } else { root.Big = api; }
})(this, function () {
  'use strict';

  var MAX_E = 9e15;
  var MIN_E = -9e15;
  var DROP = 15;

  // Exact powers of ten where a double has them (up to 1e22); the rest correctly rounded.
  var P10 = [];
  for (var i = -340; i <= 308; i++) P10[i + 340] = Number('1e' + i);

  function p10(k) { return k >= -340 && k <= 308 ? P10[k + 340] : Math.pow(10, k); }

  var lg = Math.log10 || function (x) { return Math.log(x) / Math.LN10; };

  function B(m, e) {
    this.m = m;
    this.e = e;
  }
  B.prototype.toJSON = function () { return str(this); };
  B.prototype.toString = function () { return str(this); };

  var ZERO = new B(0, 0);
  var ONE = new B(1, 0);

  function bad(text) { return new Error('Big: ' + text); }

  /** m * 10^e as a normalized Big (new object). */
  function make(m, e) {
    if (m === 0) return ZERO;
    if (m !== m || m === Infinity || m === -Infinity) throw bad('не число');
    if (e !== e) throw bad('порядок не число');
    var a = m < 0 ? -m : m;
    if (a >= 10 || a < 1) {
      var k = Math.floor(lg(a));
      if (a < 1e-300) {
        // Subnormal doubles: lift first so the power of ten stays representable.
        m = m * 1e300;
        e -= 300;
        a = m < 0 ? -m : m;
        k = Math.floor(lg(a));
      }
      m = k > 0 ? m / p10(k) : m * p10(-k);
      e += k;
      a = m < 0 ? -m : m;
      if (a >= 10) { m /= 10; e += 1; } else if (a < 1) { m *= 10; e -= 1; }
    }
    if (e > MAX_E) return new B(m < 0 ? -9.99999999999999 : 9.99999999999999, MAX_E);
    if (e < MIN_E) return ZERO;
    return new B(m, e);
  }

  var NUM = /^\s*([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?\s*$/;

  /** Reads "4.512e9", "-2.5e3", "9.99e1234567", "123.45", "1e0"; throws on anything else. */
  function parse(text) {
    var t = String(text);
    var r = NUM.exec(t);
    if (!r || (!r[2] && !r[3])) throw bad('не число «' + t.slice(0, 40) + '»');
    var whole = r[2] || '';
    var frac = r[3] || '';
    var digits = whole + frac;
    var lead = 0;
    while (lead < digits.length && digits.charAt(lead) === '0') lead++;
    if (lead === digits.length) return ZERO;
    var significant = digits.slice(lead);
    var exp = r[4] ? parseInt(r[4], 10) : 0;
    var e = whole.length - lead - 1 + exp;
    var m = parseFloat(significant.charAt(0) + '.' + significant.slice(1, 17));
    return make(r[1] === '-' ? -m : m, e);
  }

  function fromNumber(x) {
    if (x !== x || x === Infinity || x === -Infinity) throw bad('не конечное число ' + x);
    return x === 0 ? ZERO : make(x, 0);
  }

  /** Any value as a Big: a Big, a number, a string, a plain {m, e}. `fallback` for null/undefined. */
  function of(x, fallback) {
    if (x instanceof B) return x;
    if (typeof x === 'number') return fromNumber(x);
    if (typeof x === 'string') return parse(x);
    if (x === null || x === undefined) {
      if (arguments.length > 1) return of(fallback);
      throw bad('нет значения');
    }
    if (typeof x === 'object' && typeof x.m === 'number' && typeof x.e === 'number') return make(x.m, Math.round(x.e));
    return parse(String(x));
  }

  /** true when the value reads as a finite Big. */
  function valid(x) {
    try {
      var b = of(x);
      return b.m === b.m && b.e === b.e;
    } catch (error) {
      return false;
    }
  }

  function isBig(x) { return x instanceof B; }

  /** The JSON form: up to 12 significant digits of mantissa, "e", the exponent. */
  function str(x) {
    x = of(x);
    if (x.m === 0) return '0e0';
    var t = x.m.toPrecision(12);
    var e = x.e;
    var a = parseFloat(t);
    if (a >= 10 || a <= -10) {
      t = a < 0 ? '-1' : '1';
      e += 1;
    } else {
      t = t.replace(/\.?0+$/, '');
    }
    return t + 'e' + e;
  }

  function toNumber(x) {
    x = of(x);
    if (x.m === 0) return 0;
    if (x.e > 308) return x.m < 0 ? -Infinity : Infinity;
    if (x.e < -340) return 0;
    return x.e >= 0 ? x.m * p10(x.e) : x.m / p10(-x.e);
  }

  function neg(x) { x = of(x); return x.m === 0 ? ZERO : new B(-x.m, x.e); }

  function abs(x) { x = of(x); return x.m < 0 ? new B(-x.m, x.e) : x; }

  function add(a, b) {
    a = of(a);
    b = of(b);
    if (a.m === 0) return b;
    if (b.m === 0) return a;
    var d = a.e - b.e;
    if (d > DROP) return a;
    if (d < -DROP) return b;
    if (d >= 0) return make(a.m + b.m / p10(d), a.e);
    return make(a.m / p10(-d) + b.m, b.e);
  }

  function sub(a, b) { return add(a, neg(b)); }

  function mul(a, b) {
    a = of(a);
    b = of(b);
    if (a.m === 0 || b.m === 0) return ZERO;
    return make(a.m * b.m, a.e + b.e);
  }

  function div(a, b) {
    a = of(a);
    b = of(b);
    if (b.m === 0) throw bad('деление на ноль');
    if (a.m === 0) return ZERO;
    return make(a.m / b.m, a.e - b.e);
  }

  /** x^n for a number n; whole n are multiplied out (exact for small results), others by logarithms. */
  function pow(x, n) {
    x = of(x);
    n = Number(n);
    if (n !== n) throw bad('степень не число');
    if (n === 0) return ONE;
    if (x.m === 0) {
      if (n < 0) throw bad('деление на ноль');
      return ZERO;
    }
    if (n === 1) return x;
    var negative = false;
    if (x.m < 0) {
      if (n % 1 !== 0) throw bad('дробная степень отрицательного числа');
      negative = n % 2 !== 0;
    }
    var an = n < 0 ? -n : n;
    var base = x.m < 0 ? new B(-x.m, x.e) : x;
    var r;
    if (an % 1 === 0 && an <= 1048576) {
      r = ONE;
      var k = an;
      while (k > 0) {
        if (k % 2 === 1) r = mul(r, base);
        k = Math.floor(k / 2);
        if (k > 0) base = mul(base, base);
      }
    } else {
      var whole = base.e * an;
      var ie = Math.floor(whole);
      var f = (whole - ie) + lg(base.m) * an;
      var fi = Math.floor(f);
      r = make(Math.pow(10, f - fi), ie + fi);
    }
    if (n < 0) r = div(ONE, r);
    return negative ? neg(r) : r;
  }

  /** 10^n for a number n (may be fractional). */
  function pow10(n) {
    n = Number(n);
    var whole = Math.floor(n);
    return make(Math.pow(10, n - whole), whole);
  }

  function sqrt(x) {
    x = of(x);
    if (x.m < 0) throw bad('корень из отрицательного числа');
    if (x.m === 0) return ZERO;
    if (x.e % 2 === 0) return make(Math.sqrt(x.m), x.e / 2);
    return make(Math.sqrt(x.m * 10), (x.e - 1) / 2);
  }

  /** log10 as a plain number (-Infinity for 0, NaN for negatives). */
  function log10(x) {
    x = of(x);
    if (x.m === 0) return -Infinity;
    if (x.m < 0) return NaN;
    return x.e + lg(x.m);
  }

  function cmp(a, b) {
    a = of(a);
    b = of(b);
    if (a.m === 0) return b.m === 0 ? 0 : (b.m > 0 ? -1 : 1);
    if (b.m === 0) return a.m > 0 ? 1 : -1;
    if ((a.m > 0) !== (b.m > 0)) return a.m > 0 ? 1 : -1;
    var sign = a.m > 0 ? 1 : -1;
    if (a.e !== b.e) return (a.e > b.e ? 1 : -1) * sign;
    if (a.m === b.m) return 0;
    return a.m > b.m ? 1 : -1;
  }

  function gte(a, b) { return cmp(a, b) >= 0; }
  function gt(a, b) { return cmp(a, b) > 0; }
  function lt(a, b) { return cmp(a, b) < 0; }
  function lte(a, b) { return cmp(a, b) <= 0; }
  function eq(a, b) { return cmp(a, b) === 0; }
  function max(a, b) { return cmp(a, b) >= 0 ? of(a) : of(b); }
  function min(a, b) { return cmp(a, b) <= 0 ? of(a) : of(b); }
  function isZero(x) { return of(x).m === 0; }
  function sign(x) { var m = of(x).m; return m > 0 ? 1 : m < 0 ? -1 : 0; }

  // Rounding to whole numbers tolerates the last digits' noise: 17.999999999999996 is 18.
  function clean(v) { return Number(v.toPrecision(14)); }

  function whole(x, how) {
    x = of(x);
    if (x.m === 0 || x.e >= 15) return x;
    if (x.e < -1) {
      if (how === 'floor') return x.m < 0 ? fromNumber(-1) : ZERO;
      if (how === 'ceil') return x.m > 0 ? ONE : ZERO;
      return ZERO;
    }
    return fromNumber(Math[how](clean(toNumber(x))));
  }

  function floor(x) { return whole(x, 'floor'); }
  function ceil(x) { return whole(x, 'ceil'); }
  function round(x) { return whole(x, 'round'); }

  /** A mutable accumulator for addTo (never pass a shared value to addTo). */
  function acc(x) {
    var b = of(x === undefined ? 0 : x);
    return new B(b.m, b.e);
  }

  /** a += b in place, no allocation when b is a Big; returns a. */
  function addTo(a, b) {
    if (!(a instanceof B) || a === ZERO || a === ONE) throw bad('addTo: нужен свой накопитель — Big.acc()');
    b = of(b);
    if (b.m === 0) return a;
    if (a.m === 0) { a.m = b.m; a.e = b.e; return a; }
    var d = a.e - b.e;
    var m;
    var e;
    if (d > DROP) return a;
    if (d < -DROP) { a.m = b.m; a.e = b.e; return a; }
    if (d >= 0) { m = a.m + b.m / p10(d); e = a.e; } else { m = a.m / p10(-d) + b.m; e = b.e; }
    var n = make(m, e);
    a.m = n.m;
    a.e = n.e;
    return a;
  }

  /** Price of `count` items when the first costs base * r^owned and each next r times more. */
  function costGeom(base, r, owned, count) {
    r = Number(r);
    count = count === undefined ? 1 : Number(count);
    if (count <= 0) return ZERO;
    var first = mul(base, pow(r, owned || 0));
    if (count === 1) return first;
    if (r === 1) return mul(first, count);
    return mul(first, div(sub(pow(r, count), 1), r - 1));
  }

  /** How many items the budget buys (a number), by logarithms, no loop over items. */
  function maxBuy(budget, base, r, owned) {
    r = Number(r);
    budget = of(budget);
    var first = mul(base, pow(r, owned || 0));
    if (lt(budget, first)) return 0;
    var n;
    if (r === 1) {
      n = Math.floor(clean(toNumber(div(budget, first))));
    } else {
      var x = add(div(mul(budget, r - 1), first), 1);
      n = Math.floor(clean(log10(x) / lg(r)));
    }
    if (n < 1) n = 1;
    // Logarithms may miss the edge by one either way: settle it by the price itself.
    if (n < 1e15) {
      while (n > 1 && gt(costGeom(base, r, owned, n), budget)) n--;
      if (lte(costGeom(base, r, owned, n + 1), budget)) n++;
    }
    return n;
  }

  return {
    of: of, parse: parse, valid: valid, isBig: isBig, str: str, toNumber: toNumber,
    add: add, sub: sub, mul: mul, div: div, pow: pow, pow10: pow10, sqrt: sqrt, log10: log10,
    neg: neg, abs: abs, cmp: cmp, gte: gte, gt: gt, lt: lt, lte: lte, eq: eq, max: max, min: min,
    isZero: isZero, sign: sign, floor: floor, ceil: ceil, round: round,
    acc: acc, addTo: addTo, costGeom: costGeom, maxBuy: maxBuy,
    ZERO: ZERO, ONE: ONE, MAX_E: MAX_E
  };
});

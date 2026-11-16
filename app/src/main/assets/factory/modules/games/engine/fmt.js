// Numbers, time and words for the games, Russian first: Fmt.num(1234567) -> "1,23 млн".
// Shared subset (screen: window.Fmt, engine and node: require). ES5 only.
//
//   Fmt.num(x)        what you have: rounded down; "7", "2,5", "12,5", "12 345", "1,23 млн", "4,5e42"
//   Fmt.price(x)      a price: rounded up, so "enough" on the screen always means enough
//   Fmt.live(x)       a counter that changes: trailing zeros kept ("4,50e42"), so it does not jump
//   Fmt.rate(x)       "+1,23 млн/с"      Fmt.mult(2.5) "×2,5"      Fmt.pct(0.15) "15 %"
//   Fmt.time(sec)     "45 с", "3 мин 05 с", "2 ч 14 мин", "3 д 4 ч"
//   Fmt.ago(ms, now)  "2 ч назад"
//   Fmt.plural(n, 'монета', 'монеты', 'монет'), Fmt.count(n, ...) -> "5 монет"
//   Fmt.say(x)        for the voice: "одна целая двадцать три сотых миллиона"
//   Fmt.setup({mode, digits, sciFrom, lang})   modes: words, words-long, sci, eng
(function (root, make) {
  var Big = typeof require === 'function' && typeof exports !== 'undefined' ? require('./big.js') : root.Big;
  var api = make(Big);
  if (typeof exports !== 'undefined') { for (var k in api) exports[k] = api[k]; } else { root.Fmt = api; }
})(this, function (Big) {
  'use strict';

  var NBSP = ' ';
  var LOCALES = {
    ru: {
      dec: ',', group: ' ', minus: '−',
      words: ['млн', 'млрд', 'трлн', 'квадрлн', 'квинтлн', 'секстлн', 'септлн', 'октлн', 'нонлн', 'децлн'],
      s: 'с', min: 'мин', h: 'ч', d: 'д', now: 'только что', ago: 'назад'
    },
    en: {
      dec: '.', group: ',', minus: '−',
      words: ['M', 'B', 'T', 'Qa', 'Qi', 'Sx', 'Sp', 'Oc', 'No', 'Dc'],
      s: 's', min: 'min', h: 'h', d: 'd', now: 'just now', ago: 'ago'
    }
  };

  var S = { mode: 'words', digits: 3, sciFrom: 15, lang: 'ru' };

  /** Changes the defaults: {mode, digits, sciFrom, lang}; returns them. */
  function setup(options) {
    options = options || {};
    if (options.mode) S.mode = String(options.mode);
    if (options.digits) S.digits = Math.max(1, Math.min(12, Math.round(Number(options.digits))));
    if (options.sciFrom) S.sciFrom = Math.max(6, Math.round(Number(options.sciFrom)));
    if (options.lang) S.lang = LOCALES[options.lang] ? options.lang : 'en';
    return { mode: S.mode, digits: S.digits, sciFrom: S.sciFrom, lang: S.lang };
  }

  function loc(o) { return LOCALES[(o && o.lang) || S.lang] || LOCALES.ru; }

  function p10(k) { return Math.pow(10, k); }
  function clean(v) { return Number(v.toPrecision(12)); }

  function roundTo(v, decimals, up) {
    var f = p10(decimals);
    var s = clean(v * f);
    return (up ? Math.ceil(s) : Math.floor(s)) / f;
  }

  /** The first `digits` significant digits as a whole number r, value = r * 10^(e - digits + 1). */
  function roundSig(b, digits, up) {
    var s = clean(b.m * p10(digits - 1));
    var r = up ? Math.ceil(s) : Math.floor(s);
    var e = b.e;
    if (r >= p10(digits)) {
      r = r / 10;
      e += 1;
    }
    return { r: r, e: e };
  }

  function fixed(v, decimals, keepZeros, L) {
    var t = v.toFixed(Math.max(0, decimals));
    if (!keepZeros && t.indexOf('.') >= 0) t = t.replace(/\.?0+$/, '');
    return t.replace('.', L.dec);
  }

  function grouped(n, L) {
    var t = String(Math.floor(n));
    var out = '';
    while (t.length > 3) {
      out = L.group + t.slice(-3) + out;
      t = t.slice(0, -3);
    }
    return t + out;
  }

  function sci(b, digits, up, live, L, eng) {
    var rs = roundSig(b, digits, up);
    if (eng) {
      var e3 = Math.floor(rs.e / 3) * 3;
      var shift = rs.e - e3;
      return fixed(rs.r / p10(digits - 1 - shift), digits - 1 - shift, true, L) + 'e' + e3;
    }
    return fixed(rs.r / p10(digits - 1), digits - 1, live, L) + 'e' + rs.e;
  }

  function magnitude(b, o) {
    var L = loc(o);
    var mode = o.mode || S.mode;
    var digits = o.digits || S.digits;
    var up = !!o.up;
    var live = !!o.live;
    var limit = mode === 'sci' || mode === 'eng' ? 6 : mode === 'words-long' ? 36 : (o.sciFrom || S.sciFrom);
    if (b.e < 3) {
      var v = Big.toNumber(b);
      var decimals = Math.max(0, Math.min(digits - 1 - b.e, v < 10 ? 2 : 1));
      var r = roundTo(v, decimals, up);
      if (r < 1000) {
        // Rounding up may cross into the next range: 9,999 -> 10.
        if (r >= 10 && decimals > 1) decimals = Math.max(0, Math.min(digits - 2, 1));
        if (r >= 100 && decimals > 0) decimals = Math.max(0, Math.min(digits - 3, 1));
        return fixed(r, decimals, live, L);
      }
      b = Big.of(r);
    }
    if (b.e < 6 && b.e < limit) {
      var n = Big.toNumber(b);
      var whole = up ? Math.ceil(clean(n)) : Math.floor(clean(n));
      if (whole < 1e6) return grouped(whole, L);
      b = Big.of(whole);
    }
    if (b.e >= limit) return sci(b, digits, up, live, L, mode === 'eng');
    var rs = roundSig(b, digits, up);
    if (rs.e >= limit) return sci(Big.of(rs.r + 'e' + (rs.e - digits + 1)), digits, up, live, L, mode === 'eng');
    var unit = Math.floor(rs.e / 3);
    var word = L.words[unit - 2];
    if (!word) return sci(b, digits, up, live, L, false);
    var shift = rs.e - unit * 3;
    return fixed(rs.r / p10(digits - 1 - shift), digits - 1 - shift, live, L) + NBSP + word;
  }

  /** A number as people read it; o: {up, live, mode, digits, sciFrom, lang}. */
  function num(x, o) {
    o = o || {};
    var b;
    try {
      b = Big.of(x);
    } catch (error) {
      return '?';
    }
    if (b.m === 0) return '0';
    var negative = b.m < 0;
    if (negative) b = Big.neg(b);
    return (negative ? loc(o).minus : '') + magnitude(b, o);
  }

  function extend(o, key, value) {
    var out = {};
    for (var k in o || {}) out[k] = o[k];
    out[key] = value;
    return out;
  }

  function price(x, o) { return num(x, extend(o, 'up', true)); }
  function live(x, o) { return num(x, extend(o, 'live', true)); }

  /** Income per second: "+1,23 млн/с". */
  function rate(x, o) {
    var b = Big.of(x, 0);
    return (Big.sign(b) >= 0 ? '+' : '') + num(b, o) + '/' + loc(o).s;
  }

  /** A multiplier: "×2,5". */
  function mult(x, o) { return '×' + num(x, extend(o, 'digits', (o && o.digits) || 3)); }

  /** A share as percent: pct(0.15) -> "15 %". */
  function pct(fraction, o) { return num(Big.mul(fraction, 100), o) + NBSP + '%'; }

  function pad2(n) { return n < 10 ? '0' + n : String(n); }

  /** A duration in seconds: "45 с", "3 мин 05 с", "2 ч 14 мин", "3 д 4 ч". */
  function time(sec, o) {
    var L = loc(o);
    var t = Math.max(0, Math.floor(Number(Big.isBig(sec) ? Big.toNumber(sec) : sec) || 0));
    if (t < 60) return t + NBSP + L.s;
    if (t < 3600) {
      var s = t % 60;
      return Math.floor(t / 60) + NBSP + L.min + (s ? ' ' + pad2(s) + NBSP + L.s : '');
    }
    if (t < 86400) {
      var m = Math.floor(t % 3600 / 60);
      return Math.floor(t / 3600) + NBSP + L.h + (m ? ' ' + pad2(m) + NBSP + L.min : '');
    }
    var h = Math.floor(t % 86400 / 3600);
    return Math.floor(t / 86400) + NBSP + L.d + (h ? ' ' + h + NBSP + L.h : '');
  }

  /** "2 ч назад": the time since `ms` in its largest unit. */
  function ago(ms, now, o) {
    var L = loc(o);
    var sec = Math.max(0, Math.floor(((now === undefined ? Date.now() : now) - ms) / 1000));
    if (sec < 60) return L.now;
    if (sec < 3600) return Math.floor(sec / 60) + NBSP + L.min + ' ' + L.ago;
    if (sec < 86400) return Math.floor(sec / 3600) + NBSP + L.h + ' ' + L.ago;
    return Math.floor(sec / 86400) + NBSP + L.d + ' ' + L.ago;
  }

  /** The Russian form for n: plural(n, 'монета', 'монеты', 'монет'). */
  function plural(n, one, few, many) {
    var v;
    if (Big.isBig(n) || typeof n === 'string') {
      var b = Big.of(n);
      if (b.e >= 15) return many;
      v = Big.toNumber(b);
    } else {
      v = Number(n);
    }
    v = Math.abs(v);
    if (v % 1 !== 0) return few;
    var n10 = v % 10;
    var n100 = v % 100;
    if (n10 === 1 && n100 !== 11) return one;
    if (n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14)) return few;
    return many;
  }

  /** "5 монет". */
  function count(n, one, few, many, o) { return num(n, o) + NBSP + plural(n, one, few, many); }

  // ---- for the voice ---------------------------------------------------------------------------

  var ONES = ['ноль', 'один', 'два', 'три', 'четыре', 'пять', 'шесть', 'семь', 'восемь', 'девять'];
  var ONES_F = ['ноль', 'одна', 'две', 'три', 'четыре', 'пять', 'шесть', 'семь', 'восемь', 'девять'];
  var TEENS = ['десять', 'одиннадцать', 'двенадцать', 'тринадцать', 'четырнадцать', 'пятнадцать',
    'шестнадцать', 'семнадцать', 'восемнадцать', 'девятнадцать'];
  var TENS = ['', '', 'двадцать', 'тридцать', 'сорок', 'пятьдесят', 'шестьдесят', 'семьдесят', 'восемьдесят', 'девяносто'];
  var HUNDREDS = ['', 'сто', 'двести', 'триста', 'четыреста', 'пятьсот', 'шестьсот', 'семьсот', 'восемьсот', 'девятьсот'];
  var ORD = ['', 'первой', 'второй', 'третьей', 'четвёртой', 'пятой', 'шестой', 'седьмой', 'восьмой', 'девятой'];
  var ORD_TEENS = ['десятой', 'одиннадцатой', 'двенадцатой', 'тринадцатой', 'четырнадцатой', 'пятнадцатой',
    'шестнадцатой', 'семнадцатой', 'восемнадцатой', 'девятнадцатой'];
  var ORD_TENS = ['', '', 'двадцатой', 'тридцатой', 'сороковой', 'пятидесятой', 'шестидесятой', 'семидесятой',
    'восьмидесятой', 'девяностой'];
  var ORD_HUNDREDS = ['', 'сотой', 'двухсотой', 'трёхсотой', 'четырёхсотой', 'пятисотой', 'шестисотой',
    'семисотой', 'восьмисотой', 'девятисотой'];
  var UNITS = [
    ['миллион', 'миллиона', 'миллионов'], ['миллиард', 'миллиарда', 'миллиардов'], ['триллион', 'триллиона', 'триллионов'],
    ['квадриллион', 'квадриллиона', 'квадриллионов'], ['квинтиллион', 'квинтиллиона', 'квинтиллионов'],
    ['секстиллион', 'секстиллиона', 'секстиллионов'], ['септиллион', 'септиллиона', 'септиллионов'],
    ['октиллион', 'октиллиона', 'октиллионов'], ['нониллион', 'нониллиона', 'нониллионов'],
    ['дециллион', 'дециллиона', 'дециллионов']
  ];

  function upTo999(n, feminine) {
    var out = [];
    var h = Math.floor(n / 100);
    var rest = n % 100;
    if (h) out.push(HUNDREDS[h]);
    if (rest >= 10 && rest < 20) {
      out.push(TEENS[rest - 10]);
    } else {
      if (rest >= 20) out.push(TENS[Math.floor(rest / 10)]);
      if (rest % 10) out.push((feminine ? ONES_F : ONES)[rest % 10]);
    }
    return out;
  }

  /** A whole number 0..999 999 999 999 in words. */
  function cardinal(n, feminine) {
    n = Math.floor(n);
    if (n === 0) return 'ноль';
    var out = [];
    var groups = [[1e9, ['миллиард', 'миллиарда', 'миллиардов'], false],
      [1e6, ['миллион', 'миллиона', 'миллионов'], false], [1e3, ['тысяча', 'тысячи', 'тысяч'], true]];
    for (var i = 0; i < groups.length; i++) {
      var part = Math.floor(n / groups[i][0]) % 1000;
      if (part) out = out.concat(upTo999(part, groups[i][2]), [plural(part, groups[i][1][0], groups[i][1][1], groups[i][1][2])]);
    }
    if (n % 1000) out = out.concat(upTo999(n % 1000, feminine));
    return out.join(' ');
  }

  function ordinal(n) {
    var h = Math.floor(n / 100);
    var rest = n % 100;
    if (rest === 0) return ORD_HUNDREDS[h];
    var out = h ? [HUNDREDS[h]] : [];
    if (rest < 10) out.push(ORD[rest]);
    else if (rest < 20) out.push(ORD_TEENS[rest - 10]);
    else if (rest % 10 === 0) out.push(ORD_TENS[rest / 10]);
    else out.push(TENS[Math.floor(rest / 10)], ORD[rest % 10]);
    return out.join(' ');
  }

  /** A short number (a mantissa) in words: "четыре с половиной", "одна целая двадцать три сотых". */
  function decimalWords(v, decimals) {
    var f = p10(decimals);
    var scaled = Math.round(v * f);
    var whole = Math.floor(scaled / f);
    var part = scaled % f;
    if (!part) return { text: cardinal(whole), whole: true, n: whole };
    if (decimals === 1 && part === 5 || decimals === 2 && part === 50) {
      return { text: whole ? cardinal(whole) + ' с половиной' : 'половина', whole: false };
    }
    if (decimals === 2 && part % 10 === 0) {
      part = part / 10;
      decimals = 1;
    }
    var unit = decimals === 1 ? ['десятая', 'десятых', 'десятых'] : ['сотая', 'сотых', 'сотых'];
    return {
      text: cardinal(whole, true) + ' ' + plural(whole, 'целая', 'целых', 'целых') + ' ' + cardinal(part, true) + ' ' +
        plural(part, unit[0], unit[1], unit[2]),
      whole: false
    };
  }

  /** The number for the voice, in words. */
  function say(x, o) {
    o = o || {};
    var b = Big.of(x, 0);
    if (loc(o) !== LOCALES.ru) return num(b, o);
    if (b.m === 0) return 'ноль';
    var prefix = b.m < 0 ? 'минус ' : '';
    if (b.m < 0) b = Big.neg(b);
    var digits = o.digits || S.digits;
    var limit = (o.mode || S.mode) === 'words-long' ? 36 : (o.sciFrom || S.sciFrom);
    if (b.e < 6) {
      var v = Big.toNumber(b);
      return prefix + decimalWords(roundTo(v, v < 10 ? 2 : v < 1000 ? 1 : 0, false), v < 10 ? 2 : 1).text;
    }
    var rs = roundSig(b, digits, false);
    if (rs.e < limit && rs.e < 36) {
      var unit = Math.floor(rs.e / 3);
      var shift = rs.e - unit * 3;
      var mant = rs.r / p10(digits - 1 - shift);
      var said = decimalWords(mant, Math.max(1, Math.min(2, digits - 1 - shift)));
      var forms = UNITS[unit - 2];
      return prefix + said.text + ' ' + (said.whole ? plural(said.n, forms[0], forms[1], forms[2]) : forms[1]);
    }
    var m = decimalWords(rs.r / p10(digits - 1), Math.min(2, digits - 1)).text;
    var power = rs.e <= 999 ? ordinal(rs.e) : 'степени ' + cardinal(Math.min(rs.e, 999999999999));
    return prefix + m + ' на десять в ' + power;
  }

  return {
    setup: setup, num: num, price: price, live: live, rate: rate, mult: mult, pct: pct, time: time, ago: ago,
    plural: plural, count: count, say: say, cardinal: cardinal, ordinal: ordinal, LOCALES: LOCALES
  };
});

// The files of a series the model's artist draws (the logic is engine/series.js): the request for
// the stock and the pictures a game has taken. Called from main.js (the hub object) and stock.js.
//   data/games/<id>/art-<kind>.json  {best, items: {n: {title, text, theme, svg}}}
var Series = require('./engine/series.js');

function file(id, kind) { return 'data/games/' + id + '/art-' + kind + '.json'; }

function declOf(g, kind) {
  var decl = null;
  (g.stock || []).forEach(function (d) { if (d && d.kind === kind && d.artist) decl = d; });
  if (!decl) throw new Error('Игра не объявила художника для ' + kind);
  return decl;
}

/** The game's base for number n: (n) → {svg, title?}; null when the game has none. */
function baseOf(hub, g, decl) {
  var r = Series.ref(decl.artist.base, 'artBase');
  var fn = null;
  try {
    fn = require('modules/games/' + g.id + '/' + r.file)[r.fn];
  } catch (error) {
    hub.log(g.id, 'Художник «' + decl.kind + '»: нет базы ' + decl.artist.base + ': ' + (error && error.message ? error.message : error));
  }
  var content = decl.artist.content ? hub.readJson('modules/games/' + g.id + '/' + decl.artist.content, null) : null;
  return function (n) {
    if (typeof fn !== 'function') return null;
    try {
      return fn(n, content) || null;
    } catch (error) {
      return null;
    }
  };
}

function stockList(hub, id) { return hub.readJson('data/games/' + id + '/stock.json', []); }

function load(hub, id, kind) {
  var a = hub.readJson(file(id, kind), {});
  if (!a.items || typeof a.items !== 'object') a.items = {};
  a.best = Math.max(1, Number(a.best) || 1);
  return a;
}

/** Every picture drawn so far, kept or waiting in the stock: {n: picture}. */
function drawnAll(a, stock) {
  var all = {};
  for (var n in stock) all[n] = stock[n].item;
  for (var k in a.items) all[k] = a.items[k];
  return all;
}

/**
 * The request for the stock (stock.js promptOf): the prompt file's text with the numbers to draw.
 * want — how many; md — the file's text.
 */
exports.prompt = function (hub, g, decl, want, md) {
  var key = decl.artist.key || 'level';
  var o = Series.options(hub.configOf(g.id), decl.kind);
  var a = load(hub, g.id, decl.kind);
  var stock = Series.inStock(stockList(hub, g.id), decl.kind, key);
  var numbers = Series.todo(function (n) { return !!(a.items[n] || stock[n]); }, { best: a.best, ahead: o.ahead, recent: o.recent, want: want });
  var base = baseOf(hub, g, decl);
  return Series.prompt({
    base: md,
    numbers: numbers,
    noun: decl.artist.noun,
    previous: decl.artist.previous,
    recent: Series.recentThemes(drawnAll(a, stock)),
    baseOf: base,
    pictureOf: function (n) { return a.items[n] || (stock[n] && stock[n].item) || null; }
  });
};

/**
 * The screen: the record and the numbers it shows → their pictures. A shown number without its
 * picture takes it out of the stock when the stock has exactly that number (takeStrict is
 * main.js stockTake with strict). {on, animate, items: {n: picture}, ready: drawn above the record}.
 */
exports.sync = function (hub, g, kind, best, seen, takeStrict) {
  var decl = declOf(g, kind);
  var key = decl.artist.key || 'level';
  var o = Series.options(hub.configOf(g.id), kind);
  if (!o.on) return { on: false, animate: o.animate, items: {}, ready: 0 };
  var numbers = (Array.isArray(seen) ? seen : []).map(Number).filter(function (n) { return n >= 1 && n === Math.floor(n); }).slice(0, 2000);
  var stock = Series.inStock(stockList(hub, g.id), kind, key);
  var base = null;
  var fresh = {};
  numbers.forEach(function (n) {
    if (!stock[n] || stock[n].used) return;
    var r = takeStrict(g.id, kind, n);
    if (!r || !r.item) return;
    base = base || baseOf(hub, g, decl);
    fresh[n] = Series.keep(r.item, base(n), decl.artist.keep);
  });
  var a = hub.update(file(g.id, kind), {}, function (v) {
    if (!v.items || typeof v.items !== 'object') v.items = {};
    v.best = Math.max(1, Number(v.best) || 1, Math.floor(Number(best) || 1));
    for (var n in fresh) if (!v.items[n]) v.items[n] = fresh[n];
    return v;
  });
  var out = {};
  numbers.forEach(function (n) { if (a.items[n]) out[n] = a.items[n]; });
  var ready = 0;
  for (var k in stock) if (!stock[k].used && Number(k) > a.best) ready++;
  return { on: true, animate: o.animate, items: out, ready: ready };
};

/** For the agent and the checks: {best, kept, ready, next}. */
exports.status = function (hub, g, kind) {
  var decl = declOf(g, kind);
  var key = decl.artist.key || 'level';
  var o = Series.options(hub.configOf(g.id), kind);
  var a = load(hub, g.id, kind);
  var stock = Series.inStock(stockList(hub, g.id), kind, key);
  var ready = 0;
  for (var k in stock) if (!stock[k].used) ready++;
  return {
    on: o.on, best: a.best, kept: Object.keys(a.items).length, ready: ready,
    next: Series.todo(function (n) { return !!(a.items[n] || stock[n]); }, { best: a.best, ahead: o.ahead, recent: o.recent, want: 3 })
  };
};

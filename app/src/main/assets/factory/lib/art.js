// Pictures for any module, not only games: the generators of the game engine
// (modules/games/engine/art.js — sprites from a seed, a look the model chose, the model's own SVG
// checked and rebuilt) and the model's artist (modules/games/engine/series.js — a series of
// pictures by number that never ends, each grown from the one before).
//
//   var art = require('lib/art');
//   art.sprite({seed, mask, hue, frames}), art.svg(sprite, o), art.fromLook(look, name),
//   art.clean(markup, o), art.still(markup), art.url(markup), art.hsl(h, s, l), art.masks
//
//   art.draw({brief, count, fields, svg, references, role, nods}) → [{…fields, svg}]
//     nods: true — references to films, games, anime allowed (an homage), not only originals;
//     one request to the model: `brief` — what to draw and in what spirit; `fields` — the other
//     fields of each picture {name: 'what it is'}; `svg` — {size, palette, max, shapes, style,
//     animate, effects, animations} (the rules go into the request, the answer is checked by them);
//     `references` — SVG pictures to start from. Only pictures that pass the check come back.
//
//   var s = art.series({file: 'data/<модуль>/art-<вид>.json', brief, base: function (n) {…}, svg})
//     base(n) → {svg, title?}: the module's own simple picture for number n (and a fixed name);
//     the artist invents each number's direction itself; the themes already drawn go with the
//     request so it does not repeat them;
//     s.get([1, 2, 3]) → {n: {title, text, theme, svg}} — what is drawn and kept;
//     s.draw([4, 5]) → the same for these numbers, drawn now (one request; call it from a job);
//     s.ahead(best, {ahead: 6, recent: 10, want: 3}) → draws the next undrawn numbers near best;
//     s.status() → {kept, best, next}.
//
// On a screen: <script src="/modules/games/engine/art.js"></script> gives window.GameArt with
// GameArt.el(svg, {cls, size}) — an <img> (no id clashes between pictures), GameArt.still(svg).
var assi = require('lib/assi');
var Art = require('modules/games/engine/art.js');
var Series = require('modules/games/engine/series.js');

['sprite', 'svg', 'draw', 'hsl', 'lookCheck', 'fromLook', 'lookRules', 'clean', 'still', 'svgRules', 'url', 'MASKS', 'masks']
  .forEach(function (k) { if (k !== 'draw') exports[k] = Art[k]; });
/** A sprite straight onto a canvas (the engine's GameArt.draw). */
exports.drawSprite = Art.draw;
exports.Series = Series;

var ROLE = 'art.draw';

function ai() { return require('lib/ai'); }

function text(v, max) { return String(v === undefined || v === null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, max || 200); }

function svgOptions(o) {
  o = o || {};
  return { size: o.size || 64, palette: o.palette, max: o.max || 4000, shapes: o.shapes || 50, style: o.style, animate: !!o.animate, effects: !!o.effects, animations: o.animations || 8, minDur: o.minDur };
}

/** The request: the system part with the rules, the prompt with the brief. */
function request(o, count, fields, extra) {
  var so = svgOptions(o.svg);
  var names = Object.keys(fields);
  // nods: references to films, games, anime, books are welcome (an homage), not only originals.
  var system = (o.nods
    ? 'Ты художник. Рисуешь картинки в SVG. Отсылки к известным фильмам, играм, аниме, книгам можно — узнаваемой деталью, цветом, формой; в названии можно назвать источник. Без логотипов и текста на рисунке.\n'
    : 'Ты художник. Рисуешь оригинальные картинки в SVG, без чужих персонажей и логотипов, без текста на рисунке.\n') +
    'Ответ — JSON: {"items": [ … ]} — ' + count + ' элементов, каждый — объект с полями: ' +
    names.map(function (f) { return '"' + f + '": ' + fields[f]; }).concat(['"svg": строка с SVG (правила ниже)']).join('; ') + '.\n' +
    Art.svgRules(so);
  var refs = (o.references || []).filter(Boolean).slice(0, 3).map(function (r, i) { return 'Образец ' + (i + 1) + ': ' + r; });
  var prompt = String(o.brief || 'Нарисуй ' + count + ' картинок.') + (extra ? '\n' + extra : '') + (refs.length ? '\n\n' + refs.join('\n') : '');
  return { system: system, prompt: prompt, svg: so };
}

function ask(o, req) {
  var answer = ai().ask({ role: o.role || ROLE, system: req.system, prompt: req.prompt, json: true });
  return Array.isArray(answer) ? answer : answer && Array.isArray(answer.items) ? answer.items : [];
}

/** Each answer item with its svg checked and rebuilt; the rest dropped. */
function checked(items, so, fields) {
  var out = [];
  items.forEach(function (item) {
    if (!item || typeof item !== 'object') return;
    var c = Art.clean(String(item.svg || ''), so);
    if (!c || c.error) return;
    var one = { svg: c.svg };
    Object.keys(fields).forEach(function (f) { if (item[f] !== undefined && item[f] !== null) one[f] = typeof item[f] === 'number' ? item[f] : text(item[f]); });
    out.push(one);
  });
  return out;
}

/** One request to the model: the pictures that pass the check (may be fewer than asked). */
exports.draw = function (o) {
  o = o || {};
  var count = Math.max(1, Math.min(12, Number(o.count) || 1));
  var fields = o.fields || { title: 'короткое название, по-русски' };
  var req = request(o, count, fields, '');
  return checked(ask(o, req), req.svg, fields);
};

function readJson(path, fallback) {
  try {
    if (!assi.files.exists(path)) return fallback;
    var t = String(assi.files.read(path));
    return t.trim() ? JSON.parse(t) : fallback;
  } catch (error) {
    return fallback;
  }
}

/** A series of pictures by number kept in a file of the module (see the head of the file). */
exports.series = function (o) {
  o = o || {};
  if (!o.file) throw new Error('art.series: нужен file — где хранить рисунки');
  var fields = o.fields || { title: 'название (для номера с готовым именем — повтори его)', text: 'одна фраза, чем рисунок необычен', theme: 'тема одним-двумя словами' };
  function load() {
    var a = readJson(o.file, {});
    if (!a.items || typeof a.items !== 'object') a.items = {};
    a.best = Math.max(1, Number(a.best) || 1);
    return a;
  }
  function save(a) { assi.files.write(o.file, JSON.stringify(a)); }
  function baseOf(n) {
    try {
      return o.base ? o.base(n) || null : null;
    } catch (error) {
      return null;
    }
  }
  function get(numbers) {
    var a = load();
    var out = {};
    (numbers || []).forEach(function (n) { if (a.items[n]) out[n] = a.items[n]; });
    return out;
  }
  function draw(numbers) {
    var a = load();
    numbers = (numbers || []).map(Number).filter(function (n) { return n >= 1 && n === Math.floor(n) && !a.items[n]; }).slice(0, 6);
    if (!numbers.length) return {};
    var f = { n: 'номер из списка (число)' };
    for (var k in fields) f[k] = fields[k];
    var list = Series.prompt({
      base: '', numbers: numbers, noun: o.noun, previous: o.previous, recent: Series.recentThemes(a.items),
      baseOf: baseOf, pictureOf: function (n) { return a.items[n] || null; }
    });
    var req = request(o, numbers.length, f, list);
    var got = checked(ask(o, req), req.svg, f);
    a = load();
    var out = {};
    got.forEach(function (item) {
      var n = Number(item.n);
      if (numbers.indexOf(n) < 0 || a.items[n]) return;
      var keep = Series.keep(item, baseOf(n), o.keep);
      a.items[n] = keep;
      out[n] = keep;
    });
    save(a);
    return out;
  }
  function ahead(best, p) {
    p = p || {};
    var a = load();
    a.best = Math.max(a.best, Math.floor(Number(best) || 1));
    save(a);
    var numbers = Series.todo(function (n) { return !!a.items[n]; }, { best: a.best, ahead: p.ahead || 6, recent: p.recent || 0, want: p.want || 3 });
    return draw(numbers);
  }
  function status() {
    var a = load();
    return { kept: Object.keys(a.items).length, best: a.best, next: Series.todo(function (n) { return !!a.items[n]; }, { best: a.best, ahead: 6, recent: 0, want: 3 }) };
  }
  return { get: get, draw: draw, ahead: ahead, status: status };
};

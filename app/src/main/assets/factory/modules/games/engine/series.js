// A series of pictures by number that never ends: the blades of a merge game, ranks, floors,
// ships of a fleet, creatures of an evolution. Each number has a base the game draws itself
// (a simple procedural SVG: its shape, its colour); the model's artist redraws it as it likes
// — any theme, details, a glow, calm animation — and each next number grows from the one before.
//
// A game declares it on a stock kind in game.json:
//   {"kind": "blades", "title": "Рисунки клинков", "min": 3, "target": 6, "batch": 3,
//    "prompt": "prompts/blades.md",
//    "artist": {"key": "level", "base": "sim.js#artBase", "content": "content/blades.json"}}
//   key      the item's field with the number (the prompt's schema has it as a number field)
//   base     file#function of the game: (n, content) → {svg, title?} — the reference picture and,
//            when the number has a fixed name, that name (the artist repeats it, the game keeps it)
//   content  a file of the game given to base (optional)
//   keep     the fields a drawn number keeps (default: title, text, theme, svg)
//   previous how many earlier pictures go with a request as references (default 2)
//   nods     true — references to films, games, anime, books allowed by default (the setting
//            «art.<kind>.nods» switches it; it lifts the stock's rule «only original»)
// The artist invents each number's direction itself; the themes already drawn go with the
// request as a hint for variety (a theme may come back, drawn anew).
// The engine then:
//   - adds the settings «art.<kind>.on / .animate / .ahead / .recent / .nods» (group «Рисунки»);
//   - builds the request itself: the prompt file with the list of numbers to draw put in before
//     its schema — each with its base and the picture of the number before (stock.js);
//   - keeps the stock a few numbers ahead of the owner's record;
//   - on g.art.sync(kind, best, seen) on the screen takes the pictures of the seen numbers out of
//     the stock into data/games/<id>/art-<kind>.json (outside the save) and returns them.
// The pure part is here (node tests it); the files are main.js and artist.js. Shared subset, ES5.
(function (root, make) {
  var api = make();
  if (typeof exports !== 'undefined') { for (var k in api) exports[k] = api[k]; } else { root.Series = api; }
})(this, function () {
  'use strict';

  var KEEP = ['title', 'text', 'theme', 'svg'];

  /** The settings of an artist kind, for Core.schema: {key: item}. */
  function settings(decl) {
    var k = 'art.' + decl.kind + '.';
    var what = decl.title || decl.kind;
    var out = {};
    out[k + 'on'] = { value: true, title: what + ': рисует модель-художник', group: 'Рисунки' };
    out[k + 'animate'] = { value: true, title: what + ': анимация на рисунках', group: 'Рисунки' };
    out[k + 'ahead'] = { value: 6, title: what + ': рисовать впрок, номеров выше рекорда', min: 1, max: 50, group: 'Рисунки' };
    out[k + 'recent'] = { value: 10, title: what + ': сначала номера не ниже рекорда минус', min: 0, max: 1000, group: 'Рисунки' };
    out[k + 'nods'] = { value: !!(decl.artist && decl.artist.nods), title: what + ': отсылки к фильмам, играм, аниме', group: 'Рисунки' };
    return out;
  }

  /** The values of an artist kind from a game's config: {on, animate, ahead, recent, nods}. */
  function options(cfg, kind) {
    var k = 'art.' + kind + '.';
    cfg = cfg || {};
    return {
      on: cfg[k + 'on'] !== false,
      animate: cfg[k + 'animate'] !== false,
      ahead: Math.max(1, Number(cfg[k + 'ahead']) || 6),
      recent: Math.max(0, Number(cfg[k + 'recent']) || 0),
      nods: cfg[k + 'nods'] === true
    };
  }

  function isNumber(n) { return typeof n === 'number' && n >= 1 && n === Math.floor(n); }

  /** The pictures of a kind in a stock list: {n: {item, used}}; an unused one wins over a used. */
  function inStock(list, kind, key) {
    var out = {};
    (list || []).forEach(function (s) {
      if (!s || s.kind !== kind || !s.item || !isNumber(s.item[key])) return;
      var n = s.item[key];
      if (!out[n] || (out[n].used && !s.usedAt)) out[n] = { item: s.item, used: !!s.usedAt };
    });
    return out;
  }

  /**
   * The next numbers to draw: from (best − recent) to (best + ahead) first — the ones the owner
   * sees now and will see next — then the earlier ones from 1. drawn(n) says a number is done.
   * o = {best, ahead, recent, want}.
   */
  function todo(drawn, o) {
    var best = Math.max(1, Math.floor(Number(o.best) || 1));
    var want = Math.max(1, Number(o.want) || 3);
    var top = best + Math.max(1, Number(o.ahead) || 6);
    var from = Math.max(1, best - Math.max(0, Number(o.recent) || 0));
    var out = [];
    var n;
    for (n = from; n <= top && out.length < want; n++) if (!drawn(n)) out.push(n);
    for (n = 1; n < from && out.length < want; n++) if (!drawn(n)) out.push(n);
    // Everything near is drawn: the numbers right after the far end.
    for (n = top + 1; out.length < want && n < top + 1 + want * 4; n++) if (!drawn(n)) out.push(n);
    return out.sort(function (x, y) { return x - y; });
  }

  /**
   * The request's list: the numbers with their bases and references, put in before the schema of
   * the prompt file. o = {base: the file's text, numbers, baseOf(n) → {svg, title?},
   * pictureOf(n) → {title, theme, svg} | null, previous: how many references at most, noun,
   * recent: the themes drawn lately (recentThemes), a hint for variety}.
   */
  function prompt(o) {
    var base = String(o.base || '');
    var noun = o.noun || 'Номер';
    var numbers = o.numbers || [];
    var previous = o.previous === undefined ? 2 : o.previous;
    var used = 0;
    var lines = [''];
    if (o.recent && o.recent.length) lines.push('Недавние темы (для разнообразия; вернуться к теме можно, если по-новому): ' + o.recent.join(', ') + '.', '');
    lines.push('Номера этого заказа (' + numbers.length + '):');
    numbers.forEach(function (n, i) {
      var b = o.baseOf(n) || {};
      lines.push('');
      lines.push(noun + ' ' + n + ' — ' + (b.title ? '«' + b.title + '» (имя готово, повтори его)' : 'имя придумай сам') + '.');
      if (!b.title) lines.push('Направление придумай сам; пусть отличается от соседних номеров.');
      if (b.svg) lines.push('База: ' + b.svg);
      if (n > 1 && i > 0 && numbers[i - 1] === n - 1) {
        lines.push('Предыдущий — тот, что ты рисуешь выше в этом же ответе.');
        return;
      }
      var before = n > 1 && o.pictureOf ? o.pictureOf(n - 1) : null;
      if (before && before.svg && used < previous) {
        used++;
        lines.push('Рисунок предыдущего ' + (n - 1) + (before.title ? ' «' + before.title + '»' : '') +
          (before.theme ? ' (тема: ' + before.theme + ')' : '') + ': ' + before.svg);
      }
    });
    var list = lines.join('\n') + '\n\n';
    var at = base.indexOf('```schema');
    return at >= 0 ? base.slice(0, at) + list + base.slice(at) : base + list;
  }

  /** What a drawn number keeps: the fields of `keep`; a fixed name (the base's) wins. */
  function keep(item, b, fields) {
    var out = {};
    (fields || KEEP).forEach(function (f) { if (item[f] !== undefined && item[f] !== null) out[f] = typeof item[f] === 'object' ? item[f] : String(item[f]); });
    if (b && b.title) out.title = String(b.title);
    return out;
  }

  /**
   * The themes drawn lately, the latest numbers first, each once: a hint for variety that goes
   * with the request — the artist invents the direction itself and may come back to one. pictures = {n: {theme}}; up to max.
   */
  function recentThemes(pictures, max) {
    var seen = {};
    var out = [];
    Object.keys(pictures || {}).map(Number).sort(function (a, b) { return b - a; }).forEach(function (n) {
      var t = pictures[n] && pictures[n].theme ? String(pictures[n].theme).trim().toLowerCase() : '';
      if (!t || seen[t] || out.length >= (max || 15)) return;
      seen[t] = true;
      out.push(t);
    });
    return out;
  }

  /** "file.js#fn" → {file, fn}. */
  function ref(text, fallback) {
    var at = String(text || '').split('#');
    return { file: at[0], fn: at[1] || fallback };
  }

  return { KEEP: KEEP, settings: settings, options: options, inStock: inStock, todo: todo, prompt: prompt, keep: keep, ref: ref, recentThemes: recentThemes };
});

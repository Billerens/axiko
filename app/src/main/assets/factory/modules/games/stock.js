// The content stock for trips («Движок: запас контента на поездку»): while there is network, the
// model prepares what a game shows in the metro — recipes, floors, events — and the game takes
// it from data/games/<id>/stock.json without network. Called from main.js (the hub object).

var CALLS = 'data/games/stock-calls.json';
var DAY = 86400000;

function data(id) { return 'data/games/' + id + '/stock.json'; }

/** The usual starts of trips (minutes of the day) from the trips of «Пассажир»: up to two. */
function usualTrips(trips, now) {
  var since = now - 30 * DAY;
  var buckets = {};
  (trips || []).forEach(function (t) {
    if (t.start < since || t.manual) return;
    var d = new Date(t.start);
    var m = d.getHours() * 60 + d.getMinutes();
    var b = Math.floor(m / 60);
    (buckets[b] = buckets[b] || []).push(m);
  });
  var list = Object.keys(buckets).map(function (b) { return buckets[b]; }).filter(function (l) { return l.length >= 3; });
  list.sort(function (a, b) { return b.length - a.length; });
  return list.slice(0, 2).map(function (l) {
    l.sort(function (a, b) { return a - b; });
    return l[Math.floor(l.length / 2)];
  });
}
exports.usualTrips = usualTrips;

function hhmm(minutes) {
  var m = ((minutes % 1440) + 1440) % 1440;
  var h = Math.floor(m / 60);
  var r = m % 60;
  return (h < 10 ? '0' : '') + h + ':' + (r < 10 ? '0' : '') + r;
}

/** The text sent to the model: the engine's frame and the game's own prompt with its schema. */
function promptOf(hub, g, decl, want, names, local) {
  var md = '';
  try {
    md = require('lib/axiko').files.read('modules/games/' + g.id + '/' + (decl.prompt || 'prompts/' + decl.kind + '.md'));
  } catch (error) {
    md = '';
  }
  // A series the model's artist draws (`"artist": {...}`, engine/series.js): the numbers to draw
  // with their bases and references go in before the schema.
  if (decl.artist) {
    try {
      md = require('./artist.js').prompt(hub, g, decl, want, md);
    } catch (error) {
      hub.log(g.id, 'Запас «' + decl.kind + '»: художник не собрал запрос: ' + (error && error.message ? error.message : error));
    }
  }
  // A prompt the game builds itself, from what is happening (`"promptFrom": "art.js#prompt"`):
  // gets {kind, want, local, base: the file's text} and returns the text in the same format.
  if (decl.promptFrom) {
    var at = String(decl.promptFrom).split('#');
    try {
      var built = require('modules/games/' + g.id + '/' + at[0])[at[1] || 'prompt']({ kind: decl.kind, want: want, local: !!local, base: md });
      if (built) md = String(built);
    } catch (error) {
      hub.log(g.id, 'Запас «' + decl.kind + '»: свой запрос не собрался: ' + (error && error.message ? error.message : error));
    }
  }
  var schema = hub.Core.stock.schema(md);
  var own = md.replace(/```schema[\s\S]*?```/g, '').replace(/\{count\}/g, String(want)).trim();
  var art = require('./engine/art.js');
  var pictures = [];
  var fields = Object.keys(schema.fields).map(function (f) {
    var s = schema.fields[f];
    if (s.type === 'look') {
      if (pictures.indexOf('look') < 0) pictures.push('look');
      return '"' + f + '": облик (объект, правила ниже)';
    }
    if (s.type === 'svg') {
      pictures.push(art.svgRules({ size: s.size, palette: s.palette, shapes: s.shapes, max: s.max, style: s.style, animate: s.animate, effects: s.effects, animations: s.animations }));
      return '"' + f + '": строка с SVG (правила ниже)' + (s.required === false ? ', не обязательно' : '');
    }
    return '"' + f + '": ' + (s.type || 'string') + (s.max ? ' (до ' + s.max + (s.type === 'number' ? '' : ' знаков') + ')' : '') + (s.oneOf ? ' одно из: ' + s.oneOf.join(', ') : '');
  });
  var rulesOfPictures = pictures.map(function (p) { return p === 'look' ? art.lookRules() : p; })
    .filter(function (p, i, all) { return all.indexOf(p) === i; });
  // A series whose owner allows references (art.<kind>.nods): the originality rule gives way.
  var nods = false;
  if (decl.artist) {
    try {
      nods = !!require('./engine/series.js').options(hub.configOf(g.id), decl.kind).nods;
    } catch (ignored) { /* no references */ }
  }
  var system = 'Ты пишешь контент для игры «' + (g.title || g.id) + '». ' + (g.about || '') + '\n' +
    'Правила:\n' +
    (nods ? '- можно отсылки к известным фильмам, играм, аниме, книгам, сериалам — как оммаж: узнаваемая деталь, цвет, форма; ' +
      'в названии можно прямо назвать источник; рисуй сам, без логотипов и текста на картинке;\n'
      : '- только оригинальное: никаких названий и персонажей из чужих игр, книг, фильмов;\n') +
    '- по-русски; без эмодзи;\n' +
    '- не повторяй уже существующее (список ниже).\n' +
    'Ответ — JSON: {"items": [ … ]} — ' + want + ' элементов, каждый — объект с полями: ' + (fields.join('; ') || 'как в задании') + '.' +
    (rulesOfPictures.length ? '\n' + rulesOfPictures.join('\n') : '');
  var list = names.slice(-(local ? 30 : 150));
  var prompt = (own || 'Придумай ' + want + ' новых элементов вида «' + (decl.title || decl.kind) + '».') +
    (list.length ? '\n\nУже есть: ' + list.join('; ') : '');
  return { system: system, prompt: prompt, schema: schema };
}

function callsToday(hub) {
  var c = hub.readJson(CALLS, {});
  return c.date === hub.Core.dayKey(hub.now()) ? c.count || 0 : 0;
}

function countCall(hub) {
  var today = hub.Core.dayKey(hub.now());
  hub.update(CALLS, {}, function (c) {
    if (c.date !== today) {
      c.date = today;
      c.count = 0;
    }
    c.count++;
  });
}

/** One request to the model for one kind of one game: {added, rejected, error?}. */
exports.fill = function (hub, g, kind, opts) {
  opts = opts || {};
  var decl = null;
  (g.stock || []).forEach(function (d) { if (d.kind === kind) decl = d; });
  if (!decl) throw new Error('Игра не объявила запас ' + kind);
  var cfg = hub.config();
  if (!opts.local && callsToday(hub) >= cfg['stock.maxCallsPerDay']) return { added: 0, rejected: 0, error: 'лимит запросов на сегодня' };
  var list = hub.readJson(data(g.id), []);
  var unused = hub.Core.stock.count(list, kind);
  var want = Math.max(1, Math.min(Number(decl.batch) || 10, (Number(decl.target) || 30) - unused));
  if (opts.local) want = Math.min(want, 5);
  var names = [];
  var p = promptOf(hub, g, decl, want, names, opts.local);
  var key = p.schema.key || 'title';
  list.forEach(function (s) {
    if (s.kind === kind && s.item && s.item[key] !== undefined) names.push(String(s.item[key]));
  });
  // The names are part of the prompt: built again with them.
  p = promptOf(hub, g, decl, want, names, opts.local);
  var answer;
  if (!opts.local) countCall(hub);
  try {
    answer = require('lib/ai').ask({ role: opts.local ? 'games.local' : 'games.stock', system: p.system, prompt: p.prompt, json: true });
  } catch (error) {
    hub.log(g.id, 'Запас «' + kind + '»: модель не ответила: ' + (error && error.message ? error.message : error));
    return { added: 0, rejected: 0, error: String(error && error.message ? error.message : error) };
  }
  var items = Array.isArray(answer) ? answer : answer && Array.isArray(answer.items) ? answer.items : [];
  var sim = null;
  try {
    sim = require('modules/games/' + g.id + '/sim.js');
  } catch (ignored) { /* the checks without the game's own */ }
  var seen = {};
  names.forEach(function (n) { seen[hub.Core.stock.norm(n)] = true; });
  var t = hub.now();
  var fresh = [];
  var rejected = 0;
  items.slice(0, want * 2).forEach(function (item, i) {
    var why = hub.Core.stock.check(item, p.schema, seen, sim, kind);
    if (why) {
      rejected++;
      hub.log(g.id, 'Запас «' + kind + '»: отвергнуто (' + why + '): ' + JSON.stringify(item).slice(0, 160));
      return;
    }
    seen[hub.Core.stock.norm(item[p.schema.key || 'title'])] = true;
    fresh.push({ id: kind + '-' + t.toString(36) + '-' + i, kind: kind, createdAt: t, usedAt: null, item: item, by: opts.local ? 'local' : 'stock' });
  });
  if (fresh.length) {
    hub.update(data(g.id), [], function (all) { return all.concat(fresh); });
    hub.emit('games.stock', { game: g.id, kind: kind, added: fresh.length });
  }
  return { added: fresh.length, rejected: rejected };
};

/** Fills every kind below its minimum (force: below its target) while there is network. */
exports.fillAll = function (hub, force) {
  var cfg = hub.config();
  var online = null;
  try {
    online = require('./links/sensors.js').online();
  } catch (ignored) { /* node */ }
  if (online === false) return { skipped: 'нет сети' };
  var out = { calls: 0, added: 0, games: [] };
  hub.games().forEach(function (g) {
    if (!g.stock || !g.stock.length) return;
    var list = hub.update(data(g.id), [], function (all) { return hub.Core.stock.expire(all, hub.now(), cfg['stock.expireDays']); });
    var needs = force
      ? g.stock.filter(function (d) { return hub.Core.stock.count(list, d.kind) < (Number(d.target) || 30); }).map(function (d) { return { kind: d.kind }; })
      : hub.Core.stock.needs(list, g.stock);
    // A kind that fell below its minimum is filled up to its target, portion by portion: one
    // request a portion, stopping at the target, at the day's limit or when a portion adds nothing.
    needs.forEach(function (n) {
      var decl = null;
      g.stock.forEach(function (d) { if (d.kind === n.kind) decl = d; });
      var target = Number(decl && decl.target) || 30;
      var batch = Math.max(1, Number(decl && decl.batch) || 10);
      var rounds = Math.ceil(target / batch) + 1;
      for (var i = 0; i < rounds; i++) {
        if (callsToday(hub) >= cfg['stock.maxCallsPerDay']) return;
        if (hub.Core.stock.count(hub.readJson(data(g.id), []), n.kind) >= target) return;
        var r = exports.fill(hub, g, n.kind, {});
        out.calls++;
        out.added += r.added;
        if (r.added && out.games.indexOf(g.id) < 0) out.games.push(g.id);
        if (!r.added) return;
      }
    });
  });
  return out;
};

function fillLater(hub, force) {
  spawn(function () {
    try {
      exports.fillAll(hub, force);
    } catch (error) {
      console.warn('games: запас: ' + error);
    }
  });
}

exports.start = function (hub, cfg) {
  job('games:stock', { every: cfg['stock.everyMin'] }, function () { exports.fillAll(hub, false); });
  on('network', function (e) { if (e && e.online) fillLater(hub, false); }, 'games.stock.network');
  on('power', function (e) { if (e && e.charging) fillLater(hub, false); }, 'games.stock.power');
  var trips = (hub.readJson('data/games/links/passenger.json', {}) || {}).trips || [];
  var times = usualTrips(trips, hub.now());
  [0, 1].forEach(function (i) {
    var key = 'games:stock-trip-' + i;
    if (times[i] === undefined || cfg['stock.beforeTripMin'] <= 0) {
      unjob(key);
      return;
    }
    job(key, { at: hhmm(times[i] - cfg['stock.beforeTripMin']) }, function () { exports.fillAll(hub, true); });
  });
};

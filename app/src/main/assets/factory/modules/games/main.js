// The engine side of the hub «Игротека»: saves in SQLite, the catalog, settings, the inbox and
// the content stock of each game, the life links (links/*.js), achievements to «Ачивки за
// жизнь», notifications. Screens call it: axiko.call('modules/games/main.js', 'save', id, seq, json).
// The project rules are in docs/game-development.md; the runtime reference is AGENT.md next to this file.
var axiko = require('lib/axiko');
var Big = require('./engine/big.js');
var Fmt = require('./engine/fmt.js');
var Rng = require('./engine/rng.js');
var Core = require('./engine/core.js');

var HUB = 'modules/games';
var DATA = 'data/games';
var DAY = 86400000;
var REPORT_ON = { achievement: 1, hide: 1, exit: 1, prestige: 1, reset: 1, restore: 1 };

// ---- files ---------------------------------------------------------------------------------------

var LOCK = typeof java !== 'undefined' ? new java.util.concurrent.locks.ReentrantLock() : null;

/** Runs fn with the hub's data to itself: read-modify-write of a file is not interleaved. */
function locked(fn) {
  if (!LOCK) return fn();
  LOCK.lock();
  try {
    return fn();
  } finally {
    LOCK.unlock();
  }
}

function readJson(path, fallback) {
  try {
    if (!axiko.files.exists(path)) return fallback;
    var text = axiko.files.read(path);
    return text.trim() ? JSON.parse(text) : fallback;
  } catch (error) {
    console.warn('games: ' + path + ' не читается: ' + error);
    return fallback;
  }
}

/** Writes JSON whole or not at all: a temporary file renamed over the old one. */
function writeJson(path, value) {
  var text = JSON.stringify(value, null, 1);
  if (typeof java === 'undefined') {
    axiko.files.write(path, text);
    return;
  }
  axiko.files.write(path + '.tmp', text);
  var target = new java.io.File(axiko.files.path(path));
  var temp = new java.io.File(axiko.files.path(path + '.tmp'));
  if (!temp.renameTo(target)) {
    axiko.files.write(path, text);
    temp['delete']();
  }
}

/** Read, change, write under the lock: update(path, fallback, fn(value) -> value | undefined). */
function update(path, fallback, fn) {
  return locked(function () {
    var value = readJson(path, fallback);
    var result = fn(value);
    writeJson(path, result === undefined ? value : result);
    return result === undefined ? value : result;
  });
}

function check(id) {
  if (!Core.validId(id)) throw new Error('Нет такой игры: ' + id);
  return String(id);
}

function dir(id) { return HUB + '/' + id; }
function data(id) { return DATA + '/' + id; }

function now() { return Date.now(); }

// ---- the shell: what it can do ------------------------------------------------------------------

var FEATURES = null;

/** The shell's extra operations (docs/native-contracts.md); [] when it has none of them. */
function features() {
  if (FEATURES) return FEATURES;
  try {
    var list = native('shell.features');
    FEATURES = Array.isArray(list) ? list.map(String) : [];
  } catch (error) {
    FEATURES = [];
  }
  return FEATURES;
}

function feature(name) { return features().indexOf(name) >= 0; }

// ---- games and their settings ---------------------------------------------------------------------

/** game.json of an installed game (null when there is none). */
function game(id) {
  var g = readJson(dir(check(id)) + '/game.json', null);
  if (!g) return null;
  g.id = id;
  g.saveVersion = g.saveVersion || 1;
  g.currencies = g.currencies || [];
  g.prestige = g.prestige || [];
  g.achievements = g.achievements || [];
  g.links = g.links || {};
  g.stock = g.stock || [];
  g.notify = g.notify || [];
  return g;
}

function needGame(id) {
  var g = game(id);
  if (!g) throw new Error('Игра ' + id + ' не установлена');
  return g;
}

/** Every installed game: modules/games/<id>/game.json (reserved folders and daily games aside). */
function games() {
  var names = [];
  try {
    names = axiko.files.list(HUB);
  } catch (error) {
    return [];
  }
  var out = [];
  names.forEach(function (name) {
    name = String(name);
    if (name.charAt(name.length - 1) !== '/') return;
    var id = name.slice(0, -1);
    if (!Core.validId(id) || id.indexOf('daily/') === 0) return;
    if (!axiko.files.exists(dir(id) + '/game.json')) return;
    var g = game(id);
    if (g) out.push(g);
  });
  return out;
}

function sim(id) { return require(dir(check(id)) + '/sim.js'); }

function hubSettings() { return readJson(DATA + '/settings.json', {}); }

function schemaOf(id) {
  var g = needGame(id);
  return Core.schema(readJson(dir(id) + '/config.json', {}), g);
}

/** The values a game runs with (engine, hub, game, owner). */
function configOf(id) {
  return Core.config(schemaOf(id), hubSettings(), readJson(data(id) + '/config.json', {}));
}

/** The hub's own values (no game). */
function hubConfig() { return Core.config(Core.schema({}, null), hubSettings(), null); }

// ---- saves ------------------------------------------------------------------------------------------

var DB = null;

function db() {
  if (DB) return DB;
  var d = axiko.db('games');
  d.exec('create table if not exists saves (id integer primary key, game text, seq integer, at integer, ver integer, ' +
    'reason text, size integer, state text, keep integer default 0)');
  d.exec('create unique index if not exists saves_game on saves (game, seq)');
  DB = d;
  return d;
}

function lastSeq(id) {
  var row = db().query('select max(seq) as seq from saves where game = ?', [id])[0];
  return row && row.seq ? Number(row.seq) : 0;
}

function insert(id, seq, state, reason, keep) {
  var text = typeof state === 'string' ? state : JSON.stringify(state);
  var ver = typeof state === 'string' ? verOf(text) : state.ver;
  db().exec('insert into saves (game, seq, at, ver, reason, size, state, keep) values (?, ?, ?, ?, ?, ?, ?, ?)',
    [id, seq, now(), ver || 0, String(reason || 'auto'), text.length, text, keep ? 1 : 0]);
}

function verOf(text) {
  var m = /"ver":(\d+)/.exec(text.slice(0, 200));
  return m ? Number(m[1]) : 0;
}

/** The card line kept at the end of a saved state ("card": "..."), without parsing all of it. */
function cardOf(text) {
  var tail = String(text || '').slice(-600);
  var m = /"card":"((?:[^"\\]|\\.)*)"\}\s*$/.exec(tail);
  if (!m) return '';
  try {
    return JSON.parse('"' + m[1] + '"');
  } catch (error) {
    return '';
  }
}

/** The latest saved state of a game as an object (null when none reads). */
function latestState(id) {
  var rows = db().query('select state from saves where game = ? order by seq desc limit 3', [id]);
  for (var i = 0; i < rows.length; i++) {
    try {
      var state = JSON.parse(rows[i].state);
      if (!Core.validate(state, null)) return state;
    } catch (ignored) { /* the next one */ }
  }
  return null;
}

/**
 * Saves a state: (id, seq, json, reason, keep). A seq not above the last saved is refused —
 * then another copy of the game (another screen, the computer) is ahead: {ok: false, stale}.
 */
exports.save = function (id, seq, json, reason, keep) {
  check(id);
  seq = Number(seq);
  reason = String(reason || 'auto');
  var text = String(json);
  var result = locked(function () {
    var last = lastSeq(id);
    if (!(seq > last)) return { ok: false, stale: true, lastSeq: last };
    insert(id, seq, text, reason, keep);
    return { ok: true, seq: seq };
  });
  if (!result.ok) return result;
  try {
    var cfg = hubConfig();
    if (text.length > cfg['save.warnKb'] * 1024) log(id, 'Сохранение ' + Math.round(text.length / 1024) + ' КБ — больше ' + cfg['save.warnKb'] + ' КБ');
    if (REPORT_ON[reason] || seq % 25 === 0) prune(id);
    if (REPORT_ON[reason]) after(id, text, reason);
  } catch (error) {
    console.warn('games: после сохранения ' + id + ': ' + error);
  }
  return result;
};

/** What follows a save the owner may leave on: achievements out, notifications planned. */
function after(id, text, reason) {
  var state = JSON.parse(text);
  reportAchievements(id, state);
  if (reason === 'hide' || reason === 'exit') {
    try {
      planNotify();
    } catch (error) {
      console.warn('games: уведомления: ' + error);
    }
  }
}

/** Deletes saves beyond what is kept (save.keep latest, one per day for save.dailyDays, keep = 1). */
function prune(id) {
  var cfg = configOf(id);
  var rows = db().query('select id, seq, at, keep from saves where game = ? order by seq desc', [id]);
  var gone = Core.retention(rows, now(), cfg['save.keep'], cfg['save.dailyDays']);
  gone.forEach(function (rowId) { db().exec('delete from saves where id = ?', [rowId]); });
  return gone.length;
}
exports.prune = function (id) { return prune(check(id)); };

/** Older saves when the newest do not read: rows with seq below `before`. */
exports.older = function (id, before, limit) {
  check(id);
  return db().query('select id, seq, at, ver, reason, state from saves where game = ? and seq < ? order by seq desc limit ?',
    [id, Number(before), Number(limit) || 10]);
};

/** A save that does not read is kept for the agent to look at (keep = 1, marked). */
exports.markBroken = function (id, seq, error) {
  check(id);
  db().exec("update saves set keep = 1, reason = reason || ' (повреждено)' where game = ? and seq = ? and reason not like '%(повреждено)'", [id, Number(seq)]);
  log(id, 'Сохранение seq ' + seq + ' повреждено: ' + error);
  return true;
};

/** The list for the sheet «Сохранения»: time, reason, card line; no states. */
exports.saves = function (id) {
  check(id);
  return db().query('select id, seq, at, ver, reason, size, keep, substr(state, -600) as tail from saves where game = ? order by seq desc limit 200', [id])
    .map(function (row) {
      return { id: row.id, seq: row.seq, at: row.at, ver: row.ver, reason: row.reason, size: row.size, keep: row.keep, card: cardOf(row.tail) };
    });
};

function copyLatest(id, reason, seq) {
  var row = db().query('select state from saves where game = ? order by seq desc limit 1', [id])[0];
  if (!row) return false;
  var state = JSON.parse(row.state);
  state.seq = seq;
  insert(id, seq, state, reason, 1);
  return true;
}

/** Brings back an older save; the current one is first kept as a copy. */
exports.restore = function (id, rowId) {
  check(id);
  return locked(function () {
    var row = db().query('select state from saves where game = ? and id = ?', [id, Number(rowId)])[0];
    if (!row) throw new Error('Нет такого сохранения');
    var state = JSON.parse(row.state);
    var last = lastSeq(id);
    copyLatest(id, 'before-restore', last + 1);
    state.seq = last + 2;
    state.lastTick = now();
    insert(id, state.seq, state, 'restore', 0);
    return { ok: true, seq: state.seq };
  });
};

/** «Начать заново»: the current state kept as a copy, a fresh one saved over it. */
exports.reset = function (id) {
  var g = needGame(check(id));
  return locked(function () {
    var last = lastSeq(id);
    copyLatest(id, 'before-reset', last + 1);
    var cfg = configOf(id);
    var state = Core.newState(g, sim(id), { now: now(), config: cfg, rng: Rng.make(Rng.seed()), links: {}, mult: 1 });
    state.seq = last + 2;
    state.card = Core.card(sim(id), state, cfg);
    insert(id, state.seq, state, 'reset', 0);
    return { ok: true, seq: state.seq };
  });
};

/** «Экспорт сохранения»: data/games/exports/<id>-<date>.json; text too when under 100 KB. */
exports.exportSave = function (id) {
  check(id);
  var row = db().query('select state from saves where game = ? order by seq desc limit 1', [id])[0];
  if (!row) throw new Error('Сохранений ещё нет');
  var d = new Date();
  var stamp = Core.dayKey(d.getTime()) + '-' + ('0' + d.getHours()).slice(-2) + ('0' + d.getMinutes()).slice(-2);
  var path = DATA + '/exports/' + id + '-' + stamp + '.json';
  axiko.files.write(path, row.state);
  return { path: path, size: row.state.length, text: row.state.length < 100 * 1024 ? row.state : null };
};

/** Takes a state from JSON (an export) as the newest save; the current one is kept as a copy. */
exports.importSave = function (id, json) {
  var g = needGame(check(id));
  var picked = Core.pick([{ json: String(json), seq: 0, source: 'import' }], g, sim(id));
  if (picked.newer) throw new Error('Сохранение новее игры');
  if (!picked.state) throw new Error('Сохранение не подходит: ' + (picked.broken[0] ? picked.broken[0].error : 'не читается'));
  // An older version is translated now (sim.migrate), so the screen gets the current one.
  var state = picked.state;
  return locked(function () {
    var last = lastSeq(id);
    copyLatest(id, 'before-import', last + 1);
    state.seq = last + 2;
    state.lastTick = now();
    insert(id, state.seq, state, 'import', 0);
    return { ok: true, seq: state.seq };
  });
};

// ---- what a game screen gets at start ---------------------------------------------------------------

/** Everything the game screen needs in one call. */
exports.boot = function (id) {
  var g = needGame(check(id));
  var sch = schemaOf(id);
  var own = readJson(data(id) + '/config.json', {});
  var cfg = Core.config(sch, hubSettings(), own);
  var rows = db().query('select id, seq, at, ver, reason, state from saves where game = ? order by seq desc limit 6', [id]);
  update(DATA + '/catalog.json', {}, function (cat) {
    cat.opened = cat.opened || {};
    cat.opened[id] = now();
  });
  if (rows.length) {
    try {
      reportAchievements(id, JSON.parse(rows[0].state));
    } catch (ignored) { /* a broken save is the screen's to handle */ }
  }
  markSteps();
  return {
    game: g, schema: sch, config: cfg, overrides: own, rows: rows, lastSeq: rows.length ? rows[0].seq : 0,
    inbox: readJson(data(id) + '/inbox.json', []), links: linkStatus(g, cfg), stock: stockCounts(id, g),
    audio: audioState(), features: features(), charging: charging(), now: now()
  };
};

// ---- the catalog ------------------------------------------------------------------------------------

var previewCache = {};

/** What has accrued since the last save (sim.offline on a copy; cached 5 minutes). */
function accrued(g, row, cfg) {
  var key = g.id + ':' + row.seq;
  var hit = previewCache[g.id];
  if (hit && hit.key === key && now() - hit.at < 5 * 60000) return hit.value;
  var value = null;
  try {
    var s = sim(g.id);
    if (typeof s.offline === 'function') {
      var state = JSON.parse(row.state);
      var p = Core.plan(state.lastTick, now(), cfg, 0);
      var segs = Core.cut(p.from, p.to, segmentsFor(g, cfg, p.from, p.to));
      var report = Core.runOffline(s, state, g, p, segs, function (seg) {
        return { now: seg.to, config: cfg, rng: Rng.make(state.meta.seed), mult: seg.mult, links: {}, stock: {} };
      }, cfg);
      var first = g.currencies[0];
      if (first && report.gains[first.id] && Big.gt(report.gains[first.id], 0)) value = { currency: first.title, amount: Big.str(report.gains[first.id]), text: '+' + Fmt.num(report.gains[first.id]) };
    }
  } catch (error) {
    value = null;
  }
  previewCache[g.id] = { key: key, at: now(), value: value };
  return value;
}

function files(path, out) {
  out = out || [];
  var names = axiko.files.list(path);
  names.forEach(function (name) {
    name = String(name);
    if (name.charAt(name.length - 1) === '/') files(path + '/' + name.slice(0, -1), out);
    else out.push(path + '/' + name);
  });
  return out;
}

var offlineCache = {};

/** The same file timestamp used by require(): a changed or replaced file must be scanned again. */
function scanStamp(path) {
  if (typeof host === 'undefined' || typeof host.mtime !== 'function') return null;
  var absolute = axiko.files.path(path);
  var modified = Number(host.mtime(absolute));
  return modified > 0 ? absolute + ':' + modified : null;
}

/** The check «без сети»: addresses on the network in the game's .html, .js, .css. */
function offlineCheck(id, force) {
  var before = offlineCache[id] || {};
  var fresh = {};
  var hits = [];
  files(dir(id)).filter(function (path) { return /\.(html?|js|mjs|css)$/i.test(path); }).forEach(function (path) {
    var stamp = scanStamp(path);
    var old = before[path];
    var found;
    if (!force && stamp && old && old.stamp === stamp) found = old.hits;
    else found = Core.offlineScan([{ path: path.slice(HUB.length + 1), text: axiko.files.read(path) }]);
    // A file edited while it was being read is not kept under an obsolete timestamp.
    if (stamp && stamp === scanStamp(path)) fresh[path] = { stamp: stamp, hits: found };
    hits = hits.concat(found);
  });
  offlineCache[id] = fresh;
  return { ok: !hits.length, hits: hits };
}
// An explicit check is always fresh, including a rewrite which preserved the timestamp.
exports.offlineCheck = function (id) { return offlineCheck(check(id), true); };

function iconOf(id) {
  try {
    var text = axiko.files.read(dir(id) + '/icon.svg');
    return /^\s*<svg[\s>]/.test(text) && text.length < 20000 ? text : null;
  } catch (error) {
    return null;
  }
}

function moduleIcon(id) {
  try {
    var text = axiko.files.read('modules/' + id + '/icon.svg');
    return /^\s*<svg[\s>]/.test(text) && text.length < 20000 ? text : null;
  } catch (error) {
    return null;
  }
}

/**
 * Cards of other modules that are games too («Шляпа», «Приключение», «Игра дня»). A module asks
 * for one in its module.json: "games": true — a card from its title and description; or
 * "games": { "card": "<exported function of main.js>", "top": true } — the function returns
 * { title, text, open } (null — no card now), and top puts it above the sections.
 */
function moduleCards() {
  var out = [];
  var names = [];
  try {
    names = axiko.files.list('modules');
  } catch (error) {
    return out;
  }
  names.forEach(function (name) {
    var id = String(name).replace(/\/$/, '');
    if (id === 'games' || !/^[a-z0-9][a-z0-9.-]{0,95}$/.test(id) || id.indexOf('..') >= 0 || /[.-]$/.test(id)) return;
    var meta = readJson('modules/' + id + '/module.json', null);
    if (!meta || !meta.games) return;
    var ask = typeof meta.games === 'object' ? meta.games : {};
    var item = { module: id, label: meta.title || id, title: meta.title || id, text: meta.description || '', open: 'modules/' + id + '/' + (meta.entry || 'ui/index.html'), top: !!ask.top, icon: moduleIcon(id) };
    if (ask.card) {
      var made = null;
      try {
        var fn = require('modules/' + id + '/main.js')[ask.card];
        made = typeof fn === 'function' ? fn() : null;
      } catch (error) {
        console.warn('games: карточка модуля ' + id + ': ' + error);
        made = { text: 'Карточка не собралась: ' + (error && error.message || error) };
      }
      if (!made) return;
      if (made.title) item.title = String(made.title);
      var line = made.text || made.card || made.about;
      if (line) item.text = String(line);
      if (made.open || made.entry) item.open = String(made.open || made.entry);
      if (made.date) item.date = String(made.date);
    }
    out.push(item);
  });
  return out;
}
exports.moduleCards = moduleCards;

/** One game's optional card details. The first catalog paint must not run its simulation. */
function catalogDetails(g, row, hc, t) {
  var detail = { id: g.id, seq: row ? row.seq : 0, savedAt: row ? row.at : null,
    badge: !!(row && t - row.at > hc['catalog.badgeAfterMin'] * 60000), accrued: null,
    offline: null, detailsPending: false };
  if (detail.badge) detail.accrued = accrued(g, row, configOf(g.id));
  try {
    detail.offline = offlineCheck(g.id);
  } catch (error) {
    detail.offline = { ok: null, hits: [], error: String(error && error.message || error) };
  }
  return detail;
}

exports.catalogDetails = function (id) {
  var g = needGame(check(id));
  var row = db().query('select seq, at, state from saves where game = ? order by seq desc limit 1', [g.id])[0];
  return catalogDetails(g, row, hubConfig(), now());
};

/** Module hooks and life links load separately from the list of installed games. */
exports.catalogExtras = function () {
  markSteps();
  return { modules: moduleCards(), links: linkStatus(null, hubConfig()), now: now() };
};

/** Full catalog by default; {details:false} returns the list without simulations or source scans. */
exports.catalog = function (options) {
  var full = !options || options.details !== false;
  var cat = readJson(DATA + '/catalog.json', {});
  var hc = hubConfig();
  var t = now();
  var list = games().map(function (g) {
    // Only the card's tail crosses the SQLite/JavaScript bridge until its details are requested.
    var saved = full ? 'state' : 'substr(state, -600) as state';
    var row = db().query('select seq, at, ' + saved + ' from saves where game = ? order by seq desc limit 1', [g.id])[0];
    var item = {
      id: g.id, title: g.title || g.id, about: g.about || '', genre: g.genre || '', status: g.status || 'active',
      icon: iconOf(g.id), entry: dir(g.id) + '/' + (g.entry || 'ui/index.html'),
      card: row ? cardOf(row.state) : '', seq: row ? row.seq : 0, savedAt: row ? row.at : null, opened: (cat.opened || {})[g.id] || null,
      pinned: (cat.pinned || []).indexOf(g.id) >= 0, archived: (cat.archived || []).indexOf(g.id) >= 0,
      trial: g.status === 'trial', origin: g.origin || null,
      badge: !!(row && t - row.at > hc['catalog.badgeAfterMin'] * 60000), accrued: null,
      offline: null, detailsPending: !full,
      order: (cat.order || []).indexOf(g.id)
    };
    if (full) {
      var detail = catalogDetails(g, row, hc, t);
      item.accrued = detail.accrued;
      item.offline = detail.offline;
    }
    return item;
  });
  var extras = full ? exports.catalogExtras() : { modules: [], links: { mult: 1 } };
  return { games: list, modules: extras.modules, links: extras.links, now: t };
};

/** Catalog marks: ('pin' | 'archive' | 'order' | 'trial', id, value). 'pin' also edits the menu. */
exports.catalogSet = function (op, id, value) {
  if (op !== 'order') check(id);
  var cat = update(DATA + '/catalog.json', {}, function (c) {
    c.pinned = c.pinned || [];
    c.archived = c.archived || [];
    c.order = c.order || [];
    function toggle(list, on) {
      var at = list.indexOf(id);
      if (on && at < 0) list.push(id);
      if (!on && at >= 0) list.splice(at, 1);
    }
    if (op === 'pin') toggle(c.pinned, !!value);
    else if (op === 'archive') toggle(c.archived, !!value);
    else if (op === 'order') c.order = (value || []).filter(Core.validId);
    else if (op === 'trial') {
      c.trial = c.trial || {};
      if (value) c.trial[id] = { since: now() };
      else delete c.trial[id];
    } else throw new Error('Не знаю операции ' + op);
  });
  if (op === 'pin') pinInMenu(id, !!value);
  if (op === 'trial' && !value) {
    // «На пробу» → «Все игры»: the game's own status too.
    var g = readJson(dir(id) + '/game.json', null);
    if (g && g.status === 'trial') {
      g.status = 'active';
      axiko.files.write(dir(id) + '/game.json', JSON.stringify(g, null, 2));
    }
  }
  return cat;
};

function pinInMenu(id, on) {
  var g = needGame(id);
  var nav = readJson('ui/nav.json', { items: [] });
  nav.items = nav.items || [];
  var open = dir(id) + '/' + (g.entry || 'ui/index.html');
  nav.items = nav.items.filter(function (item) { return item.open !== open; });
  if (on) nav.items.push({ title: g.title || id, icon: 'play', open: open });
  axiko.files.write('ui/nav.json', JSON.stringify(nav, null, 2));
  try {
    native('nav.changed');
  } catch (ignored) { /* an older shell rereads the menu itself */ }
}

/** «Удалить»: the code goes to modules/games/archive/<id>-<date>/ (recoverable), the data stays. */
exports.remove = function (id) {
  check(id);
  if (!axiko.files.exists(dir(id) + '/game.json')) throw new Error('Нет такой игры');
  try {
    pinInMenu(id, false);
  } catch (ignored) { /* not pinned */ }
  var to = HUB + '/archive/' + id + '-' + Core.dayKey(now()) + '-' + now() % 100000;
  if (typeof java !== 'undefined') {
    var from = new java.io.File(axiko.files.path(dir(id)));
    var target = new java.io.File(axiko.files.path(to));
    target.getParentFile().mkdirs();
    if (!from.renameTo(target)) throw new Error('Не удалось убрать папку игры');
  } else {
    axiko.files.remove(dir(id));
  }
  update(DATA + '/catalog.json', {}, function (c) {
    ['pinned', 'archived', 'order'].forEach(function (k) { c[k] = (c[k] || []).filter(function (x) { return x !== id; }); });
  });
  return { ok: true, kept: to };
};

// ---- settings ---------------------------------------------------------------------------------------

/** The settings screen: (id) for a game, (null) for the hub. {schema, values, overrides}. */
exports.settings = function (id) {
  if (id) {
    var g = needGame(check(id));
    var own = readJson(data(id) + '/config.json', {});
    var sch = schemaOf(id);
    return { game: { id: g.id, title: g.title }, schema: sch, values: Core.config(sch, hubSettings(), own), overrides: own };
  }
  var values = hubSettings();
  var hs = Core.schema({}, null);
  return { game: null, schema: hs, values: Core.config(hs, values, null), overrides: values };
};

/** Sets one value (null removes the owner's value). (id | null, key, value). */
exports.setSetting = function (id, key, value) {
  var sch = id ? schemaOf(check(id)) : Core.schema({}, null);
  if (!sch[key]) throw new Error('Нет такой настройки: ' + key);
  if (id && sch[key].scope === 'hub') throw new Error('Это общая настройка Игротеки');
  var path = id ? data(id) + '/config.json' : DATA + '/settings.json';
  update(path, {}, function (values) {
    if (value === null || value === undefined) delete values[key];
    else values[key] = Core.coerce(sch[key], value);
  });
  hub.emit('games.config', { game: id || null, key: key });
  if (/^(stock|passenger|steps|bet)\./.test(key) && !id) {
    try {
      exports.start();
    } catch (error) {
      console.warn('games: перезапуск связок: ' + error);
    }
  }
  return exports.settings(id);
};

// ---- the life links ----------------------------------------------------------------------------------

function links(name) { return require('./links/' + name + '.js'); }

/** The helpers the links use (they do not require main.js back). */
var hub = {
  readJson: readJson, writeJson: writeJson, update: update, locked: locked, now: now, log: log,
  Core: Core, Big: Big, Fmt: Fmt, Rng: Rng, config: hubConfig, games: games, game: game, configOf: configOf,
  inboxAdd: inboxAdd, deliver: deliver, notify: notify, feature: feature, latestState: latestState,
  base: function () {
    try {
      return require('lib/base');
    } catch (error) {
      return null;
    }
  },
  plays: function () { return readJson(DATA + '/links/plays.json', []); },
  emit: function (name, value) {
    try {
      emit(name, value);
    } catch (ignored) { /* no screen open */ }
  }
};

function linkOn(g, cfg, name) {
  if (!cfg[name + '.on']) return false;
  if (!g) return true;
  return !!(g.links && g.links[name]) && cfg['links.' + name] !== false;
}

/** The plates of the links: {passenger, steps, bet, mult} — for one game or the catalog (g null). */
function linkStatus(g, cfg) {
  var out = { mult: 1 };
  ['passenger', 'steps', 'bet'].forEach(function (name) {
    try {
      out[name] = linkOn(g, cfg, name) ? links(name).status(hub, cfg, g) : { on: false };
    } catch (error) {
      out[name] = { on: false, error: String(error && error.message ? error.message : error) };
    }
  });
  if (out.passenger.on && out.passenger.state === 'trip' && (!g || (g.links.passenger && g.links.passenger.income !== false))) out.mult *= out.passenger.mult;
  if (out.steps.on && out.steps.charge) out.mult *= out.steps.charge.mult;
  return out;
}

exports.links = function (id) {
  if (id) return linkStatus(needGame(check(id)), configOf(id));
  return linkStatus(null, hubConfig());
};

/** The sheet of a link: ('passenger' | 'steps' | 'bet'). */
exports.linkSheet = function (name, id) {
  if (['passenger', 'steps', 'bet'].indexOf(name) < 0) throw new Error('Нет такой связки');
  return links(name).sheet(hub, hubConfig(), id ? needGame(check(id)) : null);
};

/** A mark of the step counter in the background (the catalog or a game opened). */
function markSteps() {
  try {
    spawn(function () {
      try {
        if (hubConfig()['steps.on']) links('steps').mark(hub, hubConfig());
      } catch (error) {
        console.warn('games: шаги: ' + error);
      }
    });
  } catch (ignored) { /* no spawn (node) */ }
}

/** The owner allows the step sensor from the sheet «Энергия»: true when granted. */
exports.stepsPermission = function () {
  var granted = axiko.permission(['ACTIVITY_RECOGNITION']);
  if (granted) links('steps').mark(hub, hubConfig());
  return granted;
};

/** «Я еду» (true) / «Я не еду» (false). */
exports.passenger = function (on) {
  var result = links('passenger').manual(hub, hubConfig(), !!on);
  hub.emit('games.links', exports.links());
  return result;
};

function segmentsFor(g, cfg, from, to) {
  var out = [];
  if (linkOn(g, cfg, 'passenger') && g.links.passenger.income !== false) out = out.concat(links('passenger').segments(hub, cfg, from, to));
  if (linkOn(g, cfg, 'steps')) out = out.concat(links('steps').segments(hub, cfg, from, to));
  return out;
}

/** Multiplier segments of the links for an absence: [{from, to, mult, kind}]. */
exports.segments = function (id, from, to) {
  var g = needGame(check(id));
  return segmentsFor(g, configOf(id), Number(from), Number(to));
};

/** A spend of energy declared in game.json (links.steps.spend): {ok, left} or {ok: false, need}. */
exports.spend = function (id, spendId) {
  var g = needGame(check(id));
  var cfg = configOf(id);
  if (!linkOn(g, cfg, 'steps')) return { ok: false, off: true, need: 0, left: 0 };
  var decl = Core.find((g.links.steps && g.links.steps.spend) || [], spendId);
  if (!decl) throw new Error('Игра не объявила трату ' + spendId);
  var cost = cfg['steps.cost.' + spendId] !== undefined ? Number(cfg['steps.cost.' + spendId]) : Number(decl.cost || 1);
  var result = links('steps').spend(hub, hubConfig(), id, spendId, cost);
  if (result.ok) hub.emit('games.links', exports.links());
  return result;
};

/** The bet: ('offers') | ('place', choice) | ('custom', text) | ('settle', 'evening' | 'sleep'). */
exports.bet = function (op, arg) {
  var bet = links('bet');
  var cfg = hubConfig();
  if (op === 'offers') return bet.offers(hub, cfg);
  if (op === 'place') {
    var placed = bet.place(hub, cfg, arg || {});
    hub.emit('games.links', exports.links());
    return placed;
  }
  if (op === 'custom') return bet.custom(hub, cfg, String(arg || ''));
  if (op === 'settle') return bet.settle(hub, cfg, arg || 'evening', true);
  throw new Error('Не знаю операции ставки ' + op);
};

// ---- the inbox ------------------------------------------------------------------------------------------

/** Puts an entry into the game's inbox (once per id); the open screen hears 'games.inbox'. */
function inboxAdd(id, entry) {
  check(id);
  var added = false;
  update(data(id) + '/inbox.json', [], function (list) {
    if (list.some(function (e) { return e.id === entry.id; })) return list;
    entry.at = entry.at || now();
    list.push(entry);
    added = true;
    return list;
  });
  if (added) hub.emit('games.inbox', { game: id });
  return added;
}
exports.inboxAdd = function (id, entry) {
  if (!entry || !entry.id || !entry.op) throw new Error('Запись ящика: нужны id и op');
  return inboxAdd(id, entry);
};

exports.inbox = function (id) { return readJson(data(check(id)) + '/inbox.json', []); };

/** Drops entries applied more than 30 days ago (the screen passes meta.applied). */
exports.inboxClean = function (id, applied) {
  check(id);
  return update(data(id) + '/inbox.json', [], function (list) { return Core.inboxClean(list, applied || [], now(), 30); }).length;
};

// ---- achievements -------------------------------------------------------------------------------------------

/**
 * Passes items to «Ачивки за жизнь»: require('modules/achievements/main.js').grant(item) when that
 * module has it, otherwise data/games/achievements-outbox.json, which the module takes at start.
 * This is the only place that knows the other side: if its interface differs, change it here.
 */
function deliver(items) {
  if (!items || !items.length) return 0;
  var grant = null;
  try {
    if (axiko.files.exists('modules/achievements/main.js')) {
      var mod = require('modules/achievements/main.js');
      if (typeof mod.grant === 'function') grant = mod.grant;
    }
  } catch (error) {
    console.warn('games: ачивки: ' + error);
  }
  var left = [];
  items.forEach(function (item) {
    if (grant) {
      try {
        grant(item);
        return;
      } catch (error) {
        console.warn('games: grant ' + item.key + ': ' + error);
      }
    }
    left.push(item);
  });
  if (left.length) {
    update(DATA + '/achievements-outbox.json', [], function (box) {
      left.forEach(function (item) {
        if (!box.some(function (old) { return old.key === item.key; })) box.push(item);
      });
      return box;
    });
  }
  return items.length;
}

function reportAchievements(id, state) {
  var g = game(id);
  if (!g || !state || !state.meta) return 0;
  var sent = 0;
  update(DATA + '/reported.json', {}, function (reported) {
    var ids = Core.unreported(state, reported[id]);
    if (!ids.length) return reported;
    var items = Core.lifeItems(g, state, ids, hubConfig()['achievements.share']);
    sent = deliver(items);
    reported[id] = (reported[id] || []).concat(ids);
    return reported;
  });
  return sent;
}

/** Sends the game's new achievements on (the screen calls it after g.achieve; save does too). */
exports.report = function (id) {
  check(id);
  var state = latestState(id);
  return state ? reportAchievements(id, state) : 0;
};

// ---- the content stock ------------------------------------------------------------------------------------

function stockCounts(id, g) {
  var list = readJson(data(id) + '/stock.json', []);
  var out = {};
  (g.stock || []).forEach(function (d) { out[d.kind] = Core.stock.count(list, d.kind); });
  return out;
}

exports.stockStatus = function (id) { return stockCounts(check(id), needGame(id)); };

/**
 * Takes one item of the kind for the game: {item, id, left} or {item: null, preparing} — then
 * the game uses sim.procedural, and (with stock.local on) the phone's model makes some.
 */
exports.stockTake = function (id, kind, prefer, strict) {
  var g = needGame(check(id));
  var taken = null;
  var left = 0;
  update(data(id) + '/stock.json', [], function (list) {
    taken = Core.stock.take(list, kind, now(), prefer && typeof prefer === 'object' ? prefer : null, !!strict);
    left = Core.stock.count(list, kind);
    return list;
  });
  if (taken) return { item: taken.item, id: taken.id, left: left };
  var preparing = false;
  if (hubConfig()['stock.local']) {
    preparing = true;
    spawn(function () {
      try {
        stock().fill(hub, g, kind, { local: true });
      } catch (error) {
        log(id, 'Запас на телефоне: ' + error);
      }
    });
  }
  return { item: null, id: null, left: 0, preparing: preparing };
};

function stock() { return require('./stock.js'); }

// ---- a series the model's artist draws (engine/series.js, artist.js) ---------------------------------

function artist() { return require('./artist.js'); }

/**
 * The screen of a game with an artist kind: the record and the numbers it shows → their pictures.
 * {on, animate, items: {n: {title, text, theme, svg}}, ready}.
 */
exports.seriesSync = function (id, kind, best, seen) {
  var g = needGame(check(id));
  return artist().sync(hub, g, String(kind), best, seen, function (gid, k, n) {
    var prefer = {};
    var decl = null;
    (g.stock || []).forEach(function (d) { if (d && d.kind === k) decl = d; });
    prefer[(decl && decl.artist && decl.artist.key) || 'level'] = n;
    var r = exports.stockTake(gid, k, prefer, true);
    return r && r.item ? r : null;
  });
};

/** What the artist of a kind has drawn and kept: {on, best, kept, ready, next}. */
exports.seriesStatus = function (id, kind) { return artist().status(hub, needGame(check(id)), String(kind)); };

/** Fills every game's stock while there is network (the job, the network coming back, charging). */
exports.stockFill = function (force) {
  return stock().fillAll(hub, !!force);
};

// ---- sessions, the journal, notifications ----------------------------------------------------------------

/** A finished session of play: into the base as a fact of life, and for «Пассажир». */
exports.session = function (id, ms, from, to) {
  var g = needGame(check(id));
  ms = Number(ms) || 0;
  from = Number(from) || now() - ms;
  to = Number(to) || now();
  update(DATA + '/links/plays.json', [], function (list) {
    list.push({ game: id, from: from, to: to });
    return list.slice(-200);
  });
  var cfg = hubConfig();
  if (!cfg['base.sessions'] || ms < 60000) return false;
  var trip = false;
  try {
    trip = links('passenger').segments(hub, cfg, from, to).length > 0;
  } catch (ignored) { /* no trip */ }
  var base = hub.base();
  if (base) base.add({ kind: 'play', title: g.title || id, amount: Math.round(ms / 60000), unit: 'min', source: 'games', data: { game: id, trip: trip } });
  return true;
};

/** The game's journal (data/games/<id>/log.json, the last 300 lines). */
function log(id, text) {
  if (!Core.validId(id)) return;
  update(data(id) + '/log.json', [], function (list) {
    list.push([now(), String(text)]);
    return list.slice(-300);
  });
}
exports.log = function (id, text) {
  log(check(id), text);
  return true;
};
exports.journal = function (id) { return readJson(data(check(id)) + '/log.json', []); };

function notify(options) {
  try {
    return axiko.notify(options);
  } catch (error) {
    console.warn('games: уведомление: ' + error);
    return null;
  }
}

function hhmm(ms) {
  var d = new Date(ms);
  return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
}

/** The nearest enabled event of all games (sim.next on the latest saves); one job waits for it. */
function planNotify() {
  var t = now();
  var best = null;
  games().forEach(function (g) {
    if (!g.notify.length) return;
    var cfg = configOf(g.id);
    if (!g.notify.some(function (n) { return cfg['notify.' + n.id]; })) return;
    var s = sim(g.id);
    if (typeof s.next !== 'function') return;
    var state = latestState(g.id);
    if (!state) return;
    var found = null;
    try {
      found = Core.nextNotify(s.next(state, t, cfg) || [], g, cfg, t);
    } catch (error) {
      log(g.id, 'sim.next: ' + error);
    }
    if (found && (!best || found.at < best.at)) best = { game: g.id, id: found.id, at: found.at, title: found.title, text: found.text };
  });
  writeJson(DATA + '/notify.json', best || {});
  armNotify(best);
  return best;
}
exports.planNotify = planNotify;

function armNotify(plan) {
  try {
    if (!plan || !plan.at) {
      unjob('games:notify');
      return;
    }
    if (feature('job.once')) job('games:notify', { once: plan.at }, fireNotify);
    else job('games:notify', { at: hhmm(plan.at) }, fireNotify);
  } catch (error) {
    console.warn('games: задача уведомления: ' + error);
  }
}

function fireNotify() {
  var plan = readJson(DATA + '/notify.json', {});
  if (plan && plan.at && now() >= plan.at - 90000) {
    var g = game(plan.game);
    notify({ title: plan.title || (g && g.title) || 'Игротека', text: plan.text || '', open: dir(plan.game) + '/' + ((g && g.entry) || 'ui/index.html') });
    writeJson(DATA + '/notify.json', {});
  }
  planNotify();
}

// ---- sound, vibration, the screen ------------------------------------------------------------------------

var HEADPHONES = [3, 4, 8, 11, 22, 23, 26, 27];

/** Headphones in (wired, USB, Bluetooth A2DP and LE) and whether the phone is silenced. */
function audioState() {
  try {
    var am = context.getSystemService('audio');
    var devices = am.getDevices(2);
    var on = false;
    for (var i = 0; i < devices.length; i++) if (HEADPHONES.indexOf(Number(devices[i].getType())) >= 0) on = true;
    return { headphones: on, silent: Number(am.getRingerMode()) !== 2 };
  } catch (error) {
    return { headphones: false, silent: false };
  }
}
exports.audio = function () { return audioState(); };

function charging() {
  try {
    return !!context.getSystemService('batterymanager').isCharging();
  } catch (error) {
    return false;
  }
}

/** Vibration by a pattern [on, off, on…] in ms (a waveform; one pulse on old shells). */
exports.vibrate = function (pattern) {
  var list = [].concat(pattern).map(function (x) { return Math.max(0, Math.round(Number(x) || 0)); });
  if (!list.length) return false;
  try {
    var v = context.getSystemService('vibrator');
    if (list.length === 1) {
      v.vibrate(android.os.VibrationEffect.createOneShot(Math.max(1, list[0]), -1));
      return true;
    }
    var timings = java.lang.reflect.Array.newInstance(java.lang.Long.TYPE, list.length + 1);
    timings[0] = 0;
    for (var i = 0; i < list.length; i++) timings[i + 1] = list[i];
    v.vibrate(android.os.VibrationEffect.createWaveform(timings, -1));
    return true;
  } catch (error) {
    var longest = Math.max.apply(null, list);
    axiko.vibrate(Math.max(1, longest));
    return true;
  }
};

/** Portrait lock and «не гасить экран» for the game screen: {portrait: true|false, keepOn: true|false}. */
exports.screenMode = function (options) {
  options = options || {};
  return ui(function () {
    var activity = host.activity;
    if (!activity) return false;
    if (options.portrait !== undefined && options.portrait !== null) activity.setRequestedOrientation(options.portrait ? 1 : -1);
    if (options.keepOn !== undefined && options.keepOn !== null) {
      var window = activity.getWindow();
      if (options.keepOn) window.addFlags(128);
      else window.clearFlags(128);
    }
    return true;
  });
};

// ---- start --------------------------------------------------------------------------------------------------

var audioCallback = null;

/** Runs at the app's start (module.json "autostart"): jobs, the links, the audio watch. */
exports.start = function () {
  var cfg = hubConfig();
  ['passenger', 'steps', 'bet'].forEach(function (name) {
    try {
      links(name).start(hub, cfg);
    } catch (error) {
      console.error('games: ' + name + ': ' + error);
    }
  });
  try {
    stock().start(hub, cfg);
  } catch (error) {
    console.error('games: запас: ' + error);
  }
  try {
    armNotify(readJson(DATA + '/notify.json', null));
  } catch (error) {
    console.warn('games: уведомления: ' + error);
  }
  try {
    if (!audioCallback && typeof context !== 'undefined' && context) {
      var am = context.getSystemService('audio');
      var changed = function () { hub.emit('games.audio', audioState()); };
      audioCallback = extend(android.media.AudioDeviceCallback, { onAudioDevicesAdded: changed, onAudioDevicesRemoved: changed });
      am.registerAudioDeviceCallback(audioCallback, null);
    }
  } catch (error) {
    console.warn('games: наушники: ' + error);
  }
  on('headset', function () { hub.emit('games.audio', audioState()); }, 'games.headset');
  return true;
};

exports.features = function () { return features(); };
exports.game = function (id) { return needGame(check(id)); };
exports.games = function () { return games().map(function (g) { return { id: g.id, title: g.title, status: g.status || 'active' }; }); };

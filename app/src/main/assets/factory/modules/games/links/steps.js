// «Шаги — топливо» (docs/game-development.md): the steps of the day become the shared energy of
// the games, and a walk before the metro charges the income for the trip.
//
// Steps come from the shared base (kind 'steps', written by «Здоровье») or, when it has none,
// from the phone's step counter: marks of the counter at screen events, at the opening of the
// catalog or a game and every steps.markEveryMin. The pure part (marks, energy, spends, charge,
// segments) is tested in node; data lives in data/games/links/steps.json.

var FILE = 'data/games/links/steps.json';
var MIN = 60000;
var DAY = 86400000;
// Fewer steps between two marks count as quiet: standing at a crossing is not the end of a walk.
var QUIET_STEPS = 20;

// ---- pure logic --------------------------------------------------------------------------------

function blank() {
  return { source: 'none', marks: [], today: { date: '', steps: 0, granted: 0 }, days: {}, energy: [], charges: [], log: [] };
}

function fill(d) {
  var b = blank();
  for (var k in b) if (d[k] === undefined || d[k] === null) d[k] = b[k];
  return d;
}

function pad(n) { return n < 10 ? '0' + n : String(n); }

function dayKey(ms) {
  var d = new Date(ms);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

/** The local midnight of "2026-11-14" (Date.parse of a date-time without a zone differs between engines). */
function dayStart(date) {
  var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(date));
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getTime() : NaN;
}

function midnight(ms) {
  var d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** A new day: yesterday's count goes to `days` (kept 60 days), today starts at zero. */
function rollDay(d, t) {
  var day = dayKey(t);
  if (d.today.date === day) return;
  if (d.today.date) d.days[d.today.date] = d.today.steps;
  d.today = { date: day, steps: 0, granted: 0 };
  var keys = Object.keys(d.days).sort();
  while (keys.length > 60) delete d.days[keys.shift()];
}

/**
 * A reading of the step counter (steps since the phone started). The difference to the last
 * mark is added to the day; a counter below the last mark means a reboot — it counts from zero.
 * A difference across midnight is shared between the days by time. Returns the steps added.
 */
function addMark(d, t, counter) {
  fill(d);
  var last = d.marks.length ? d.marks[d.marks.length - 1] : null;
  if (last && t <= last[0]) return 0;
  var delta = !last ? 0 : counter >= last[1] ? counter - last[1] : counter;
  var before = d.today.date;
  rollDay(d, t);
  if (delta > 0 && last && dayKey(last[0]) !== dayKey(t)) {
    var cut = midnight(t);
    var share = Math.max(0, Math.min(1, (cut - last[0]) / (t - last[0])));
    var old = Math.round(delta * share);
    var prev = dayKey(last[0]);
    if (prev === before || d.days[prev] !== undefined) d.days[prev] = (d.days[prev] || 0) + old;
    delta -= old;
  }
  d.today.steps += delta;
  d.marks.push([t, counter]);
  d.marks = d.marks.filter(function (m) { return m[0] > t - 26 * 3600000; });
  return delta;
}

/** Sets today's steps from the base (the base is the truth when it has the day). */
function setToday(d, t, steps) {
  fill(d);
  rollDay(d, t);
  d.today.steps = Math.max(0, Math.round(steps));
}

function expire(d, cfg, t) {
  var edge = t - cfg['steps.expireDays'] * DAY;
  d.energy = d.energy.filter(function (e) { return e.at >= edge && e.left > 0; });
}

function energy(d) {
  var n = 0;
  (d.energy || []).forEach(function (e) { n += e.left; });
  return n;
}

/** Energy for today's steps not granted yet: perSteps a unit, dailyMax a day, never above cap. */
function grant(d, cfg, t) {
  fill(d);
  rollDay(d, t);
  expire(d, cfg, t);
  var earned = Math.min(cfg['steps.dailyMax'], Math.floor(d.today.steps / cfg['steps.perSteps']));
  var due = earned - d.today.granted;
  if (due <= 0) return 0;
  d.today.granted = earned;
  var room = Math.max(0, cfg['steps.cap'] - energy(d));
  var add = Math.min(due, room);
  if (add > 0) d.energy.push({ at: t, left: add });
  return add;
}

/** Energy from elsewhere (a won bet): over the cap too. */
function give(d, amount, t) {
  fill(d);
  if (amount > 0) d.energy.push({ at: t, left: amount });
}

/** Spends: the oldest energy first. {ok, left} or {ok: false, need}. */
function spend(d, cfg, t, cost, game, id) {
  fill(d);
  expire(d, cfg, t);
  var have = energy(d);
  if (cost > have) return { ok: false, need: cost - have, left: have };
  d.energy.sort(function (a, b) { return a.at - b.at; });
  var rest = cost;
  d.energy.forEach(function (e) {
    var take = Math.min(e.left, rest);
    e.left -= take;
    rest -= take;
  });
  d.energy = d.energy.filter(function (e) { return e.left > 0; });
  d.log.push({ at: t, game: game || null, spend: id || null, cost: cost });
  if (d.log.length > 100) d.log = d.log.slice(-100);
  return { ok: true, left: energy(d), cost: cost };
}

/** Steps walked within [from, to] by the marks (positive differences; a reboot counts from zero). */
function walkedByMarks(marks, from, to) {
  var n = 0;
  for (var i = 1; i < marks.length; i++) {
    var a = marks[i - 1];
    var b = marks[i];
    if (b[0] <= from || a[0] >= to) continue;
    var delta = b[1] >= a[1] ? b[1] - a[1] : b[1];
    var span = b[0] - a[0];
    var inside = Math.min(b[0], to) - Math.max(a[0], from);
    n += span > 0 ? delta * Math.max(0, inside) / span : 0;
  }
  return Math.round(n);
}

/**
 * The time of the last steps: the end of the last stretch of marks that grew by more than
 * `quiet` steps — after it the steps went quiet. null when nothing was walked.
 */
function lastStepAt(marks, quiet) {
  for (var i = marks.length - 1; i > 0; i--) {
    var a = marks[i - 1];
    var b = marks[i];
    var delta = b[1] >= a[1] ? b[1] - a[1] : b[1];
    if (delta > (quiet || 0)) return b[0];
  }
  return null;
}

/**
 * The charge of a walk: chargeSteps within the last chargeWindowMin turns on ×chargeMult, lasting
 * chargeMin from the moment the walk ended (the steps went quiet); walking on extends it.
 * `walked` — steps in the window, `stepAt` — the time of the last steps (now when unknown).
 * Returns the charge.
 */
function charge(d, cfg, t, walked, stepAt) {
  fill(d);
  var last = d.charges.length ? d.charges[d.charges.length - 1] : null;
  if (walked >= cfg['steps.chargeSteps']) {
    var ended = stepAt === undefined || stepAt === null ? t : Math.min(t, stepAt);
    var until = ended + cfg['steps.chargeMin'] * MIN;
    if (last && last.to >= t) {
      last.to = Math.max(last.to, until);
    } else {
      d.charges.push({ from: t, to: until, mult: cfg['steps.chargeMult'] });
      last = d.charges[d.charges.length - 1];
    }
  }
  d.charges = d.charges.filter(function (c) { return c.to > t - 2 * DAY; });
  return last && last.to > t ? last : null;
}

function segments(d, cfg, from, to) {
  var out = [];
  (d.charges || []).forEach(function (c) {
    if (c.to > from && c.from < to) out.push({ from: Math.max(c.from, from), to: Math.min(c.to, to), mult: c.mult, kind: 'steps' });
  });
  return out;
}

/** Steps to the next unit: «ещё 340 шагов»; 0 when the day's maximum is reached. */
function toNext(d, cfg) {
  if (d.today.granted >= cfg['steps.dailyMax']) return 0;
  var per = cfg['steps.perSteps'];
  return per - (d.today.steps % per);
}

exports.blank = blank;
exports.addMark = addMark;
exports.setToday = setToday;
exports.grant = grant;
exports.givePure = give;
exports.spendPure = spend;
exports.energyOf = energy;
exports.walkedByMarks = walkedByMarks;
exports.charge = charge;
exports.lastStepAt = lastStepAt;
exports.pureSegments = segments;
exports.toNext = toNext;
exports.dayKey = dayKey;
exports.dayStart = dayStart;

// ---- the phone ---------------------------------------------------------------------------------

function read(hub) { return fill(hub.readJson(FILE, null) || blank()); }

/** Steps in the base: {today, fine (several records today — times are exact), byDay}. null without. */
function fromBase(hub, t) {
  var base = hub.base();
  if (!base) return null;
  try {
    var recent = base.find({ kind: 'steps', since: t - 2 * DAY, limit: 500 });
    if (!recent.length) return null;
    var today = 0;
    var todayCount = 0;
    var start = midnight(t);
    recent.forEach(function (r) {
      if (r.at >= start) {
        today += Number(r.amount) || 0;
        todayCount++;
      }
    });
    return { today: today, fine: todayCount >= 3, records: recent };
  } catch (error) {
    return null;
  }
}

/** A mark: steps of the day from the base or the counter, energy granted, the charge checked. */
exports.mark = function (hub, cfg) {
  var t = hub.now();
  var b = fromBase(hub, t);
  var counter = null;
  if (!b) counter = require('./sensors.js').stepCounter();
  var result = null;
  hub.update(FILE, blank(), function (d) {
    fill(d);
    var walked = 0;
    var stepAt = null;
    if (b) {
      d.source = 'base';
      setToday(d, t, b.today);
      if (b.fine) {
        b.records.forEach(function (r) {
          if (r.at < t - cfg['steps.chargeWindowMin'] * MIN) return;
          var n = Number(r.amount) || 0;
          walked += n;
          if (n > 0 && (stepAt === null || r.at > stepAt)) stepAt = r.at;
        });
      }
    } else if (counter !== null) {
      d.source = 'sensor';
      addMark(d, t, counter);
      walked = walkedByMarks(d.marks, t - cfg['steps.chargeWindowMin'] * MIN, t);
      stepAt = lastStepAt(d.marks, QUIET_STEPS);
    } else {
      d.source = 'none';
      rollDay(d, t);
    }
    var added = grant(d, cfg, t);
    var c = charge(d, cfg, t, walked, stepAt);
    result = { added: added, energy: energy(d), steps: d.today.steps, charge: c };
    return d;
  });
  if (result && result.added) hub.emit('games.links', { steps: result.energy });
  return result;
};

/** Steps of a past day (for «Ставка на день»): the base, else the marks' own count; null — no data. */
exports.stepsOn = function (hub, date) {
  var base = hub.base();
  if (base) {
    try {
      var start = dayStart(date);
      var rows = base.sum({ kind: 'steps', by: 'day', since: start, until: start + DAY });
      if (rows.length && rows[0].count) return rows[0].total;
    } catch (ignored) { /* no base */ }
  }
  var d = read(hub);
  if (d.today.date === date && d.source !== 'none') return d.today.steps;
  return d.days[date] === undefined ? null : d.days[date];
};

/** The steps of each recorded day: {date: steps} (the base's days over the link's own). */
exports.history = function (hub, days) {
  var d = read(hub);
  var out = {};
  for (var k in d.days) out[k] = d.days[k];
  if (d.today.date && d.source !== 'none') out[d.today.date] = d.today.steps;
  var base = hub.base();
  if (base) {
    try {
      base.sum({ kind: 'steps', by: 'day', since: hub.now() - (days || 30) * DAY }).forEach(function (r) { out[r.period] = r.total; });
    } catch (ignored) { /* no base */ }
  }
  return out;
};

exports.energy = function (hub) {
  var d = read(hub);
  return energy(d);
};

exports.give = function (hub, amount) {
  hub.update(FILE, blank(), function (d) { give(d, amount, hub.now()); });
  hub.emit('games.links', { steps: exports.energy(hub) });
};

exports.spend = function (hub, cfg, game, id, cost) {
  var result = null;
  hub.update(FILE, blank(), function (d) {
    result = spend(d, cfg, hub.now(), cost, game, id);
    return d;
  });
  if (result.ok) hub.log(game, 'Энергия: ' + id + ' за ' + cost + ', осталось ' + result.left);
  return result;
};

exports.segments = function (hub, cfg, from, to) {
  if (!cfg['steps.on']) return [];
  return segments(read(hub), cfg, from, to);
};

function plural(n, one, few, many) {
  var a = Math.abs(n) % 100;
  var b = a % 10;
  if (a > 10 && a < 20) return many;
  if (b > 1 && b < 5) return few;
  if (b === 1) return one;
  return many;
}

exports.status = function (hub, cfg) {
  var d = read(hub);
  var t = hub.now();
  expire(d, cfg, t);
  var e = energy(d);
  var last = d.charges.length ? d.charges[d.charges.length - 1] : null;
  var active = last && last.from <= t && last.to > t ? last : null;
  return {
    on: true, energy: e, steps: d.today.date === dayKey(t) ? d.today.steps : 0, source: d.source, toNext: toNext(d, cfg),
    text: 'Энергия ' + e,
    charge: active ? { mult: active.mult, until: active.to, min: Math.ceil((active.to - t) / MIN) } : null,
    chargeText: active ? 'Заряд прогулки ' + hub.Fmt.mult(active.mult) + ' — ' + Math.ceil((active.to - t) / MIN) + ' мин' : ''
  };
};

exports.sheet = function (hub, cfg) {
  var d = read(hub);
  var t = hub.now();
  var st = exports.status(hub, cfg);
  var permission = true;
  try {
    permission = context.checkSelfPermission('android.permission.ACTIVITY_RECOGNITION') === 0;
  } catch (ignored) { /* node or an old Android */ }
  var titles = {};
  var spends = [];
  hub.games().forEach(function (g) {
    titles[g.id] = g.title || g.id;
    var decl = g.links && g.links.steps && g.links.steps.spend;
    if (decl && decl.length && hub.configOf(g.id)['links.steps'] !== false) {
      spends.push({ game: g.id, title: g.title || g.id, items: decl.map(function (s) { return { id: s.id, title: s.title || s.id, cost: s.cost || 1 }; }) });
    }
  });
  var need = st.toNext;
  return {
    title: 'Шаги — топливо', steps: st.steps, granted: d.today.date === dayKey(t) ? d.today.granted : 0, dailyMax: cfg['steps.dailyMax'],
    energy: st.energy, cap: cfg['steps.cap'], perSteps: cfg['steps.perSteps'], toNext: need, expireDays: cfg['steps.expireDays'],
    toNextText: need ? 'ещё ' + need + ' ' + plural(need, 'шаг', 'шага', 'шагов') + ' до следующей' : 'на сегодня энергия набрана',
    source: d.source, permission: permission, charge: st.charge, chargeText: st.chargeText,
    chargeAvailable: d.source === 'sensor' || (d.source === 'base' && !!(fromBase(hub, t) || {}).fine),
    chargeRule: 'Заряд ' + hub.Fmt.mult(cfg['steps.chargeMult']) + ' на ' + cfg['steps.chargeMin'] + ' мин — от ' + cfg['steps.chargeSteps'] + ' шагов за ' + cfg['steps.chargeWindowMin'] + ' мин',
    spends: spends,
    log: d.log.slice(-10).reverse().map(function (l) { return { at: l.at, game: titles[l.game] || l.game, spend: l.spend, cost: l.cost }; })
  };
};

exports.start = function (hub, cfg) {
  function mark() {
    var c = hub.config();
    if (!c['steps.on']) return;
    try {
      exports.mark(hub, c);
    } catch (error) {
      console.warn('games: шаги: ' + error);
    }
  }
  job('games:steps', { every: cfg['steps.markEveryMin'] }, mark);
  job('games:steps-midnight', { at: '00:00' }, mark);
  on('screen', function (e) {
    if (e && (e.state === 'on' || e.state === 'unlocked')) spawn(mark);
  }, 'games.steps.screen');
  if (cfg['steps.on']) spawn(mark);
};

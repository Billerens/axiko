// «Ставка на день» (docs/game-development.md): in the morning the owner stakes game currency on a
// promise («пройду 8 000 шагов», «лягу до 00:30»); in the evening Axiko checks it by the data
// and pays through the game's inbox (the engine never edits a save).
//
// Conditions are templates with parameters (links/bet-templates.json) — data, not code. The odds
// k come from the history: the share p of past days when the condition would have held.
// The pure part (evaluate, odds, k, offers, sleep by the screen) is tested in node with a fake
// data source; data/games/links/bet.json keeps today's bet, the history, the streak, the screen log.

var FILE = 'data/games/links/bet.json';
var TEMPLATES = 'modules/games/links/bet-templates.json';
var MIN = 60000;
var HOUR = 3600000;
var DAY = 86400000;

// ---- pure logic --------------------------------------------------------------------------------

function blank() { return { today: null, waiting: [], history: [], streak: 0, screenLog: [], offers: null, achieved: {}, morningSent: null }; }

function fill(d) {
  var b = blank();
  for (var k in b) if (d[k] === undefined) d[k] = b[k];
  return d;
}

function pad(n) { return n < 10 ? '0' + n : String(n); }

function dayKey(ms) {
  var d = new Date(ms);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

function dayStart(date) {
  var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(date));
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getTime() : NaN;
}

/** A local moment of a day at "HH:MM" (the day of `date`, plus `nextDay` days). */
function at(date, hhmm, nextDay) {
  var m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm || ''));
  var d = new Date(dayStart(date));
  if (nextDay) d.setDate(d.getDate() + nextDay);
  if (m) d.setHours(Number(m[1]), Number(m[2]), 0, 0);
  return d.getTime();
}

/** The deadline of «лягу до 00:30» for the evening of `date`: before noon means the next day. */
function bedline(date, time) {
  var h = Number(String(time).split(':')[0]);
  return at(date, time, h < 12 ? 1 : 0);
}

function hhmm(ms) {
  var d = new Date(ms);
  return pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function group(n) {
  // 8000 -> "8 000" with the narrow no-break space, as fmt.js writes thousands.
  return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
}

function minutesText(ms) {
  var m = Math.round(ms / MIN);
  return m >= 60 ? Math.floor(m / 60) + ' ч ' + pad(m % 60) + ' мин' : m + ' мин';
}

/** The title of an offer: the template's words with the parameters put in. */
function title(tpl, params, key) {
  return String(tpl[key || 'title']).replace(/\{(\w+)\}/g, function (all, name) {
    var v = params[name];
    if (v === undefined) return all;
    return typeof v === 'number' && v >= 1000 ? group(v) : String(v);
  });
}

/**
 * Sleep by the screen when the base has none: the last «off» from 21:00 of `date` until 06:00
 * the next morning that is followed by three hours without «unlocked».
 */
function sleepFromScreen(log, date) {
  var from = at(date, '21:00');
  var to = at(date, '06:00', 1);
  var found = null;
  for (var i = 0; i < log.length; i++) {
    var e = log[i];
    if (e[1] !== 'off' || e[0] < from || e[0] > to) continue;
    var woke = false;
    for (var j = i + 1; j < log.length; j++) {
      if (log[j][0] > e[0] + 3 * HOUR) break;
      if (log[j][1] === 'unlocked') {
        woke = true;
        break;
      }
    }
    if (!woke) {
      found = e[0];
      break;
    }
  }
  return found;
}

/**
 * Checks a condition for a day by a data source:
 * src = {steps(date), sleepStart(date), screenMs(date, untilHHMM), appMs(date, packages, untilHHMM),
 *        expenses(date, tag), habit(date, name), workoutMin(date)} — each null when there is no data.
 * Returns {ok: true | false | null (no data), value, text}.
 */
function evaluate(tpl, params, date, src) {
  var id = tpl.id;
  var v;
  if (id === 'steps_at_least') {
    v = src.steps(date);
    if (v === null || v === undefined) return { ok: null, text: 'нет данных о шагах за этот день' };
    return { ok: v >= params.n, value: v, text: v >= params.n ? group(v) + ' шагов' : group(v) + ' из ' + group(params.n) + ' шагов' };
  }
  if (id === 'bed_before') {
    v = src.sleepStart(date);
    if (v === null || v === undefined) return { ok: null, text: 'нет данных о сне' };
    return { ok: v <= bedline(date, params.time), value: v, text: 'легли в ' + hhmm(v) };
  }
  if (id === 'screen_time_below') {
    v = src.screenMs(date, params.until || null);
    if (v === null || v === undefined) return { ok: null, text: 'нет данных об экранном времени' };
    return { ok: v < params.min * MIN, value: v, text: 'экран ' + minutesText(v) };
  }
  if (id === 'app_time_below') {
    v = src.appMs(date, params.packages || [], params.until || null);
    if (v === null || v === undefined) return { ok: null, text: 'нет данных об экранном времени' };
    return { ok: v < params.min * MIN, value: v, text: (params.app || 'приложения') + ' ' + minutesText(v) };
  }
  if (id === 'no_expense_tag') {
    v = src.expenses(date, params.tag);
    if (v === null || v === undefined) return { ok: null, text: 'нет данных о тратах' };
    return { ok: v === 0, value: v, text: v === 0 ? 'трат «' + params.tag + '» не было' : 'трат «' + params.tag + '»: ' + v };
  }
  if (id === 'habit_done') {
    v = src.habit(date, params.habit);
    if (v === null || v === undefined) return { ok: null, text: 'нет данных о привычках' };
    return { ok: !!v, value: v ? 1 : 0, text: v ? 'привычка отмечена' : 'привычка не отмечена' };
  }
  if (id === 'workout') {
    v = src.workoutMin(date);
    if (v === null || v === undefined) return { ok: null, text: 'нет данных о тренировках' };
    return { ok: v >= params.min, value: v, text: v ? 'тренировка ' + Math.round(v) + ' мин' : 'тренировки не было' };
  }
  return { ok: null, text: 'неизвестное условие' };
}

/** The share of days with data when the condition held: {p (null without data), days, won}. */
function odds(tpl, params, dates, src) {
  var days = 0;
  var won = 0;
  dates.forEach(function (date) {
    var r = evaluate(tpl, params, date, src);
    if (r.ok === null) return;
    days++;
    if (r.ok) won++;
  });
  return { p: days ? won / days : null, days: days, won: won };
}

/** k = clamp(edge / p, kMin, kMax); no history — kNoHistory; three wins in a row — +streakBonus. */
function kOf(p, streak, cfg) {
  var k;
  if (p === null || p === undefined) k = cfg['bet.kNoHistory'];
  else k = p <= 0 ? cfg['bet.kMax'] : Math.max(cfg['bet.kMin'], Math.min(cfg['bet.kMax'], cfg['bet.edge'] / p));
  if (streak >= 3) k *= 1 + cfg['bet.streakBonus'];
  return Math.round(k * 10) / 10;
}

function hint(o) {
  if (o.p === null) return 'истории пока нет';
  return 'обычно получается: ' + Math.round(o.p * 10) + ' из 10';
}

/** Three offers from the candidates [{template, params, p}]: easy (~0.7), medium (~0.4), hard (~0.15). */
function pickOffers(candidates) {
  var chosen = [];
  [0.7, 0.4, 0.15].forEach(function (target) {
    var best = null;
    var bestScore = Infinity;
    candidates.forEach(function (c) {
      if (chosen.indexOf(c) >= 0) return;
      var p = c.p === null ? 0.5 : c.p;
      var score = Math.abs(p - target);
      if (chosen.some(function (x) { return x.template === c.template; })) score += 0.2;
      if (c.p === null) score += 0.3;
      if (score < bestScore) {
        best = c;
        bestScore = score;
      }
    });
    if (best) chosen.push(best);
  });
  return chosen;
}

/** The moment the bet is settled: the evening of its day, or the morning after for sleep. */
function settleTime(bet, cfg) {
  if (bet.settleAt === 'sleep') return at(bet.date, cfg['bet.sleepCheckAt'], 1);
  return at(bet.date, cfg['bet.eveningAt']);
}

exports.blank = blank;
exports.evaluate = evaluate;
exports.odds = odds;
exports.kOf = kOf;
exports.pickOffers = pickOffers;
exports.sleepFromScreen = sleepFromScreen;
exports.settleTime = settleTime;
exports.title = title;
exports.bedline = bedline;

// ---- the data of the phone ----------------------------------------------------------------------

function read(hub) { return fill(hub.readJson(FILE, null) || blank()); }

function templates(hub) { return hub.readJson(TEMPLATES, []); }

function usage() {
  try {
    var u = require('lib/usage');
    return u.access() ? u : null;
  } catch (error) {
    return null;
  }
}

/** The data source over the base, the steps link, the usage stats and the screen log. */
function source(hub) {
  var base = hub.base();
  var steps = require('./steps.js');
  var stepsDays = null;
  var log = read(hub).screenLog;
  var u = usage();
  var cache = {};
  function records(kind, date) {
    if (!base) return null;
    var key = kind + date;
    if (cache[key] !== undefined) return cache[key];
    var start = dayStart(date);
    try {
      cache[key] = base.find({ kind: kind, since: start, until: start + DAY, limit: 500 });
    } catch (error) {
      cache[key] = null;
    }
    return cache[key];
  }
  function any(kind, days) {
    if (!base) return false;
    try {
      return base.find({ kind: kind, since: hub.now() - days * DAY, limit: 1 }).length > 0;
    } catch (error) {
      return false;
    }
  }
  function appMs(date, packages, until) {
    if (!u) return null;
    var start = dayStart(date);
    var end = until ? at(date, until) : Math.min(start + DAY, hub.now());
    if (end <= start) return 0;
    try {
      var total = 0;
      u.daily(start, end).forEach(function (a) {
        if (!packages || packages.indexOf(a.package) >= 0) total += a.ms;
      });
      return total;
    } catch (error) {
      return null;
    }
  }
  return {
    steps: function (date) {
      if (!stepsDays) stepsDays = steps.history(hub, 60);
      return stepsDays[date] === undefined ? null : stepsDays[date];
    },
    sleepStart: function (date) {
      if (base) {
        try {
          var list = base.find({ kind: 'sleep', since: at(date, '18:00'), until: at(date, '06:00', 1), limit: 20, oldestFirst: true });
          if (list.length) return list[0].at;
        } catch (ignored) { /* no base */ }
      }
      // The screen log covers what was logged; a night without a single event has no data.
      var from = at(date, '21:00');
      var to = at(date, '06:00', 1);
      if (!log.some(function (e) { return e[0] >= from - HOUR && e[0] <= to; })) return null;
      return sleepFromScreen(log, date);
    },
    screenMs: function (date, until) { return appMs(date, null, until); },
    appMs: appMs,
    expenses: function (date, tag) {
      var list = records('expense', date);
      if (list === null || !any('expense', 30)) return null;
      return list.filter(function (r) { return (r.tags || []).indexOf(String(tag).toLowerCase()) >= 0; }).length;
    },
    habit: function (date, name) {
      var list = records('habit', date);
      if (list === null || !any('habit', 30)) return null;
      var n = String(name).toLowerCase();
      return list.some(function (r) { return String(r.title || '').toLowerCase() === n || String((r.data && r.data.habit) || '').toLowerCase() === n; });
    },
    workoutMin: function (date) {
      var list = records('workout', date);
      if (list === null || !any('workout', 30)) return null;
      var best = 0;
      list.forEach(function (r) {
        var m = r.unit === 'h' ? r.amount * 60 : r.unit === 's' ? r.amount / 60 : r.amount;
        if (Number(m) > best) best = Number(m);
      });
      return best;
    },
    // Is there data of this kind in the last 7 days (a template is shown only then)?
    has: function (kind) {
      var now = hub.now();
      if (kind === 'steps') {
        for (var i = 0; i < 7; i++) if (this.steps(dayKey(now - i * DAY)) !== null) return true;
        return false;
      }
      if (kind === 'sleep') {
        if (any('sleep', 7)) return true;
        return log.some(function (e) { return e[0] > now - 7 * DAY; });
      }
      if (kind === 'usage') return !!u;
      return any(kind, 7);
    },
    topTags: function () {
      if (!base) return [];
      var count = {};
      try {
        base.find({ kind: 'expense', since: hub.now() - 30 * DAY, limit: 1000 }).forEach(function (r) {
          (r.tags || []).forEach(function (t) { count[t] = (count[t] || 0) + 1; });
        });
      } catch (ignored) { /* no base */ }
      return Object.keys(count).sort(function (a, b) { return count[b] - count[a]; }).slice(0, 3);
    },
    habits: function () {
      if (!base) return [];
      var names = {};
      try {
        base.find({ kind: 'habit', since: hub.now() - 30 * DAY, limit: 500 }).forEach(function (r) {
          var n = (r.data && r.data.habit) || r.title;
          if (n) names[n] = (names[n] || 0) + 1;
        });
      } catch (ignored) { /* no base */ }
      return Object.keys(names).sort(function (a, b) { return names[b] - names[a]; }).slice(0, 3);
    },
    packages: function () {
      if (!u) return [];
      try {
        return u.daily(hub.now() - 7 * DAY, hub.now()).slice(0, 30).map(function (a) { return a.package + ' (' + a.app + ')'; });
      } catch (error) {
        return [];
      }
    }
  };
}

function pastDates(now, days) {
  var out = [];
  for (var i = 1; i <= days; i++) out.push(dayKey(now - i * DAY));
  return out;
}

/** Every template × parameter with its odds over the history. */
function candidates(hub, cfg, src) {
  var now = hub.now();
  var dates = pastDates(now, cfg['bet.historyDays']);
  var out = [];
  templates(hub).forEach(function (tpl) {
    if (!src.has(tpl.data)) return;
    var variants = [];
    if (tpl.id === 'no_expense_tag') variants = src.topTags().map(function (t) { return { tag: t }; });
    else if (tpl.id === 'habit_done') variants = src.habits().map(function (n) { return { habit: n }; });
    else if (tpl.id === 'app_time_below') {
      (tpl.groups || []).forEach(function (g) {
        tpl.params.min.forEach(function (m) { variants.push({ app: g.app, packages: g.packages, min: m }); });
      });
    } else {
      var names = Object.keys(tpl.params || {});
      if (names.length === 1 && Array.isArray(tpl.params[names[0]])) {
        tpl.params[names[0]].forEach(function (v) {
          var p = {};
          p[names[0]] = v;
          variants.push(p);
        });
      }
    }
    variants.forEach(function (params) {
      var o = odds(tpl, params, dates, src);
      out.push({ template: tpl.id, params: params, p: o.p, days: o.days });
    });
  });
  return out;
}

function offerOf(hub, cfg, d, tpl, params, p) {
  var k = kOf(p, d.streak, cfg);
  return {
    template: tpl.id, params: params, title: title(tpl, params), plate: title(tpl, params, 'plate'), settleAt: tpl.settle || 'evening',
    p: p, k: k, kText: hub.Fmt.mult(k), hint: hint({ p: p })
  };
}

/** The games one can stake in: [{id, title, currencies: [{id, title, balance, text}]}] and the energy. */
function stakes(hub) {
  var out = [];
  hub.games().forEach(function (g) {
    var b = g.links && g.links.bet;
    if (!b || hub.configOf(g.id)['links.bet'] === false) return;
    var state = hub.latestState(g.id);
    var list = (g.currencies || []).filter(function (c) { return c.bettable || c.id === b.currency; }).map(function (c) {
      var v = state ? hub.Core.get(state, c.path) : null;
      var balance = v !== undefined && v !== null && hub.Big.valid(v) ? hub.Big.str(v) : '0e0';
      return { id: c.id, title: c.title || c.id, balance: balance, text: hub.Fmt.num(balance) };
    }).filter(function (c) {
      // Nothing to stake yet (coals before the first reforge): the currency is not offered.
      return hub.Big.sign(c.balance) > 0;
    });
    if (list.length) out.push({ id: g.id, title: g.title || g.id, currencies: list });
  });
  return out;
}

function deadline(hub, cfg) { return at(dayKey(hub.now()), cfg['bet.deadline']); }

/** The sheet of the bet: offers, where to stake, today's bet and the last results. */
exports.offers = function (hub, cfg) {
  var d = read(hub);
  var t = hub.now();
  var today = dayKey(t);
  var list = null;
  if (d.offers && d.offers.date === today && t - d.offers.at < HOUR && d.offers.streak === d.streak) {
    list = d.offers.list;
  } else {
    var src = source(hub);
    var byId = {};
    templates(hub).forEach(function (tpl) { byId[tpl.id] = tpl; });
    list = pickOffers(candidates(hub, cfg, src)).map(function (c) { return offerOf(hub, cfg, d, byId[c.template], c.params, c.p); });
    hub.update(FILE, blank(), function (x) {
      x.offers = { date: today, at: t, streak: d.streak, list: list };
    });
  }
  var placed = d.today && d.today.date === today ? d.today : null;
  return {
    title: 'Ставка на день', offers: list, games: stakes(hub), energy: require('./steps.js').energy(hub),
    energyOn: !!cfg['steps.on'], stakes: String(cfg['bet.stakes']).split(',').map(Number).filter(function (x) { return x > 0 && x <= 1; }),
    today: placed, open: t <= deadline(hub, cfg) && !madeOn(d, today), deadline: cfg['bet.deadline'], streak: d.streak,
    history: d.history.slice(-7).reverse()
  };
};

/**
 * Whether a bet was already made on `today`: still open, waiting for its data, or already
 * settled (an evening bet settles at 23:30, before the 23:59 deadline, and must not open the day
 * for a second one).
 */
function madeOn(d, today) {
  function on(b) { return b && b.date === today; }
  return on(d.today) || (d.waiting || []).some(on) || (d.history || []).some(on);
}

/** Places today's bet: {template, params, title, p, k, game, currency, percent}. game '__energy' — energy. */
exports.place = function (hub, cfg, arg) {
  var t = hub.now();
  var today = dayKey(t);
  if (t > deadline(hub, cfg)) throw new Error('Ставки принимаются до ' + cfg['bet.deadline']);
  exports.settle(hub, cfg, 'any', false);
  var d = read(hub);
  if (madeOn(d, today)) throw new Error('Сегодня ставка уже сделана');
  var tpl = null;
  templates(hub).forEach(function (x) { if (x.id === arg.template) tpl = x; });
  if (!tpl) throw new Error('Нет такого условия');
  var percent = Number(arg.percent);
  if (!(percent > 0 && percent <= 1)) throw new Error('Доля ставки — от 0 до 1');
  var id = 'bet-' + today;
  var bet = {
    id: id, date: today, template: tpl.id, params: arg.params || {}, title: arg.title || title(tpl, arg.params || {}),
    plate: title(tpl, arg.params || {}, 'plate'), p: arg.p === undefined ? null : arg.p, k: Number(arg.k) || kOf(arg.p, d.streak, cfg),
    game: arg.game, currency: arg.currency || null, percent: percent, placedAt: t, settleAt: tpl.settle || 'evening'
  };
  if (arg.game === '__energy') {
    var have = require('./steps.js').energy(hub);
    var amount = Math.max(1, Math.floor(have * percent));
    var spent = require('./steps.js').spend(hub, hub.config(), null, 'bet', amount);
    if (!spent.ok) throw new Error('Не хватает энергии: нужно ' + amount);
    bet.energy = amount;
    bet.estimate = String(amount);
  } else {
    var g = hub.game(arg.game);
    if (!g || !g.links || !g.links.bet) throw new Error('В этой игре ставок нет');
    var cur = hub.Core.find(g.currencies, bet.currency);
    if (!cur || !(cur.bettable || cur.id === g.links.bet.currency)) throw new Error('Эту валюту ставить нельзя');
    var state = hub.latestState(g.id);
    var balance = state ? hub.Core.get(state, cur.path) : 0;
    if (!(hub.Big.sign(hub.Big.of(balance, 0)) > 0)) throw new Error('Ставить пока нечего: ' + (cur.title || cur.id) + ' — 0');
    bet.estimate = hub.Big.str(hub.Big.mul(hub.Big.of(balance, 0), percent));
    bet.currencyTitle = cur.title || cur.id;
    bet.gameTitle = g.title || g.id;
    hub.inboxAdd(g.id, { id: id, at: t, from: 'bet', op: 'take', currency: cur.id, percent: percent, reason: 'Ставка: ' + bet.title });
  }
  hub.update(FILE, blank(), function (x) {
    fill(x);
    // A bet of an earlier day not settled yet (sleep is checked in the morning) waits on.
    if (x.today && x.today.date !== today) x.waiting = (x.waiting || []).concat([x.today]);
    x.today = bet;
  });
  return bet;
};

/** Settles bets whose time has come ('evening' | 'sleep' | 'any'); force — now, whatever the time. */
exports.settle = function (hub, cfg, kind, force) {
  var d = read(hub);
  var list = (d.waiting || []).concat(d.today ? [d.today] : []);
  var out = [];
  list.forEach(function (bet) {
    if (!force && hub.now() < settleTime(bet, cfg)) return;
    if (!force && kind !== 'any' && kind !== bet.settleAt) return;
    out.push(settleOne(hub, cfg, bet));
  });
  return out.length ? out[out.length - 1] : null;
};

/** Settles one bet under the hub's lock: a job and the catch-up at start never pay it twice. */
function settleOne(hub, cfg, bet) {
  return hub.locked(function () {
    var cur = read(hub);
    var still = (cur.today && cur.today.id === bet.id) || (cur.waiting || []).some(function (w) { return w.id === bet.id; });
    return still ? pay(hub, cfg, bet) : null;
  });
}

function pay(hub, cfg, bet) {
  var tpl = null;
  templates(hub).forEach(function (x) { if (x.id === bet.template) tpl = x; });
  var r;
  try {
    r = tpl ? evaluate(tpl, bet.params, bet.date, source(hub)) : { ok: null, text: 'условие удалено' };
  } catch (error) {
    r = { ok: null, text: 'данные не прочитались' };
  }
  var result = r.ok === null ? 'refund' : r.ok ? 'won' : 'lost';
  var steps = require('./steps.js');
  if (bet.game === '__energy') {
    if (result === 'won') steps.give(hub, Math.round(bet.energy * bet.k));
    if (result === 'refund') steps.give(hub, bet.energy);
  } else if (result !== 'lost') {
    hub.inboxAdd(bet.game, {
      id: bet.id + (result === 'won' ? '-win' : '-back'), at: hub.now(), from: 'bet', op: 'add', currency: bet.currency,
      ofTake: bet.id, mult: result === 'won' ? bet.k : 1, reason: result === 'won' ? 'Ставка выиграна: ' + r.text : 'Ставка возвращена: ' + r.text
    });
  }
  var where = bet.game === '__energy' ? 'энергии' : (bet.currencyTitle || '').toLowerCase() + ' в «' + (bet.gameTitle || bet.game) + '»';
  var win = bet.game === '__energy' ? Math.round(bet.energy * bet.k) : hub.Big.mul(bet.estimate || 0, bet.k);
  var text = result === 'won' ? 'Ставка выиграна: ' + r.text + '. +' + hub.Fmt.num(win) + ' ' + where + ' (' + hub.Fmt.mult(bet.k) + ')'
    : result === 'lost' ? 'Ставка проиграна: ' + r.text + '. Завтра — новая'
      : 'Ставка возвращена: ' + r.text + ' за ' + (bet.date === dayKey(hub.now()) ? 'сегодня' : bet.date);
  var streak = 0;
  var fresh = [];
  hub.update(FILE, blank(), function (x) {
    fill(x);
    var waiting = (x.waiting || []).filter(function (w) { return w.id !== bet.id; });
    var mine = (x.today && x.today.id === bet.id) || waiting.length !== (x.waiting || []).length;
    if (!mine) return x;
    x.waiting = waiting;
    if (x.today && x.today.id === bet.id) x.today = null;
    x.history.push({ id: bet.id, date: bet.date, title: bet.title, result: result, k: bet.k, percent: bet.percent, value: r.value === undefined ? null : r.value, text: r.text });
    x.history = x.history.slice(-Math.max(60, cfg['bet.historyDays'] * 2));
    if (result === 'won') x.streak = (x.streak || 0) + 1;
    if (result === 'lost') x.streak = 0;
    streak = x.streak;
    x.offers = null;
    x.achieved = x.achieved || {};
    if (result === 'won' && x.streak >= 7 && !x.achieved.word) {
      x.achieved.word = hub.now();
      fresh.push({ id: 'word', title: 'Хозяин слова', text: 'Семь ставок на день выиграно подряд' });
    }
    if (result === 'won' && bet.percent >= 0.5 && !x.achieved['all-in']) {
      x.achieved['all-in'] = hub.now();
      fresh.push({ id: 'all-in', title: 'Ва-банк', text: 'Выиграна ставка в половину' });
    }
    return x;
  });
  var base = hub.base();
  if (base) {
    try {
      base.add({ kind: 'bet', title: bet.title, amount: bet.k, source: 'games', data: { result: result, template: bet.template, params: bet.params } });
    } catch (error) {
      console.warn('games: ставка: база: ' + error);
    }
  }
  if (fresh.length && hub.config()['achievements.share'] !== 'none') {
    hub.deliver(fresh.map(function (a) {
      return { key: 'games/bet/' + a.id, source: 'games', category: 'Игротека', title: a.title, text: a.text, at: hub.now(), hidden: false, icon: 'modules/games/icon.svg' };
    }));
  }
  hub.notify({ title: 'Ставка на день', text: text, open: 'modules/games/ui/index.html#bet' });
  hub.emit('games.links', { bet: result });
  return { id: bet.id, result: result, text: text, streak: streak };
}

/** «Своё условие…»: the model picks a template and parameters, or says it cannot be checked. */
exports.custom = function (hub, cfg, words) {
  if (!String(words).trim()) throw new Error('Скажите, на что ставите');
  var src = source(hub);
  var list = templates(hub);
  var names = list.map(function (t) { return t.id; });
  var packages = src.packages();
  var answer;
  try {
    answer = require('lib/ai').ask({
      role: 'games.bet', json: true,
      system: 'Ты переводишь обещание владельца в проверяемое условие. Отвечай только JSON.',
      prompt: 'Владелец хочет поставить на условие: «' + String(words).slice(0, 300) + '».\n' +
        'Подбери шаблон из списка и параметры. Если ни один не подходит — {"template": null, "why": "…"}.\n' +
        'Шаблоны: steps_at_least{n}, bed_before{time}, screen_time_below{min}, ' +
        'app_time_below{packages, min, until?}, no_expense_tag{tag}, habit_done{habit}, workout{min}.\n' +
        (packages.length ? 'Пакеты приложений на телефоне: ' + packages.join(', ') + '\n' : '') +
        'Время — ЧЧ:ММ, минуты — числом. Ответ JSON: {"template": "…", "params": {…}, "title": "коротко, как обещание от первого лица"}'
    });
  } catch (error) {
    return { template: null, why: 'Модель не ответила: ' + (error && error.message ? error.message : error) };
  }
  if (!answer || !answer.template || names.indexOf(answer.template) < 0) {
    return { template: null, why: (answer && answer.why) || 'Такое пока не проверить по данным' };
  }
  var tpl = list[names.indexOf(answer.template)];
  if (!src.has(tpl.data)) return { template: null, why: 'Такое пока не проверить по данным: нет данных за последнюю неделю' };
  var params = answer.params || {};
  if (tpl.id === 'app_time_below') {
    params.packages = [].concat(params.packages || []).map(function (p) { return String(p).replace(/\s*\(.*\)$/, ''); });
    if (!params.app) params.app = 'приложения';
  }
  ['n', 'min'].forEach(function (k) { if (params[k] !== undefined) params[k] = Number(params[k]); });
  var o = odds(tpl, params, pastDates(hub.now(), cfg['bet.historyDays']), src);
  var offer = offerOf(hub, cfg, read(hub), tpl, params, o.p);
  if (answer.title) offer.title = String(answer.title).slice(0, 80);
  return offer;
};

exports.status = function (hub, cfg) {
  var d = read(hub);
  var t = hub.now();
  var bet = d.today;
  if (!bet) return { on: true, today: null, text: 'Ставка: нет' };
  var text = 'Ставка: ' + (bet.plate || bet.title);
  if (bet.template === 'steps_at_least' && bet.date === dayKey(t)) {
    var now = require('./steps.js').stepsOn(hub, bet.date);
    if (now !== null) text += ' — пока ' + group(now);
  }
  return { on: true, today: bet, text: text };
};

exports.sheet = function (hub, cfg) { return exports.offers(hub, cfg); };

exports.start = function (hub, cfg) {
  function settle(kind) {
    return function () {
      var c = hub.config();
      if (!c['bet.on']) return;
      try {
        exports.settle(hub, c, kind, false);
      } catch (error) {
        console.error('games: ставка: ' + error);
      }
    };
  }
  // «Ставка на день» comes only when yesterday's bet is settled: a sleep bet waits for
  // `sleepCheckAt`, so the notification waits with it and comes right after its settling.
  function morning() {
    var c = hub.config();
    if (!c['bet.on'] || !c['bet.notifyMorning']) return;
    var today = dayKey(hub.now());
    var d = read(hub);
    if (d.morningSent === today) return;
    if (d.today && d.today.date === today) return;
    var pending = (d.waiting || []).concat(d.today ? [d.today] : []).some(function (b) { return b.date !== today; });
    if (pending) return;
    hub.update(FILE, blank(), function (x) { x.morningSent = today; });
    var games = stakes(hub);
    if (!games.length && !c['steps.on']) return;
    var first = null;
    try {
      first = exports.offers(hub, c).offers[0];
    } catch (ignored) { /* no offers */ }
    hub.notify({ title: 'Ставка на день', text: first ? 'Например: ' + first.title + ' — ' + first.kText : 'Что поставите сегодня?', open: 'modules/games/ui/index.html#bet' });
  }
  job('games:bet-evening', { at: cfg['bet.eveningAt'] }, settle('evening'));
  job('games:bet-sleep', { at: cfg['bet.sleepCheckAt'] }, function () {
    settle('sleep')();
    if (hub.now() >= at(dayKey(hub.now()), hub.config()['bet.morningAt'])) morning();
  });
  job('games:bet-morning', { at: cfg['bet.morningAt'] }, function () {
    settle('any')();
    morning();
  });
  on('screen', function (e) {
    if (!e || !e.state) return;
    var t = hub.now();
    hub.update(FILE, blank(), function (x) {
      fill(x);
      x.screenLog.push([t, e.state]);
      x.screenLog = x.screenLog.filter(function (s) { return s[0] > t - 3 * DAY; });
    });
  }, 'games.bet.screen');
  // The phone was off in the evening: the bet is settled now, by the data of its day.
  if (cfg['bet.on']) spawn(settle('any'));
};

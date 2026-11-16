// The standard checks of a game of «Игротека», for its own checks.js (run by module_check
// with 'games/<id>'). Engine side (Rhino); node runs it in scripts/games tests too.
//
//   var kit = require('../engine/checks-kit.js');
//   var checks = kit.standard(__dirname, { play: function (state, ctx, sim, tapsPerSec) { … } });
//   for (var name in checks) exports[name] = checks[name];
//
// What they check (docs/modules/games.md, «Как агент делает новую игру»): a new game and a save
// round trip pass validate; old saves of fixtures/ read; 12 hours of offline by the formula and
// by steps agree within 1 %; a bot plays three days of two 25-minute trips and the pace fits
// (the first purchase within 10 s, the first prestige within trips 3–6); no network addresses;
// the screen opens at 344 and 690 points.
var Big = require('./big.js');
var Rng = require('./rng.js');
var Core = require('./core.js');

var HOUR = 3600000;
var MIN = 60000;

function idOf(dir) {
  // __dirname is the folder's full path on the phone; a workspace path works as well.
  var m = /(?:^|\/)modules\/games\/(daily\/\d{4}-\d{2}-\d{2}|[^\/]+)$/.exec(String(dir).replace(/\\/g, '/').replace(/\/+$/, ''));
  if (!m) throw new Error('Не папка игры: ' + dir);
  return m[1];
}

function readJson(path) {
  var assi = require('lib/assi');
  return JSON.parse(assi.files.read(path));
}

/** The values a game runs with, without the owner's own (checks use the game's defaults). */
function configOf(game, dir) {
  var own = {};
  try {
    own = readJson(dir + '/config.json');
  } catch (ignored) { /* no config.json */ }
  return Core.config(Core.schema(own, game), {}, null);
}

function ctxOf(cfg, rng, now) {
  return { now: now, config: cfg, rng: rng, mult: 1, links: {}, stock: {}, offline: false, capBonusHours: 0 };
}

/**
 * The bot's default play for one second: `taps` strikes (sim.hammer, sim.tap or sim.click when
 * the game has one), then buying while there is enough (sim.buy). Returns how many it bought.
 * A game whose play is different passes its own `play` with the same contract.
 */
function defaultPlay(state, ctx, sim, taps) {
  var tap = sim.hammer || sim.tap || sim.click;
  if (tap) for (var i = 0; i < taps; i++) tap(state, ctx.config, ctx.mult);
  var bought = 0;
  if (sim.buy) while (bought < 50 && sim.buy(state, ctx.config)) bought++;
  return bought;
}

/**
 * Plays days × trips: each trip `tripMin` minutes of play by seconds (`hz` ticks a second), the
 * gaps between trips by offline progress (the formula or steps). It resets when the engine would
 * hint to (Core.prestigeInfo(...).hint). Returns {firstBuySec, prestiges: [{trip, gain}], trips, state}.
 */
function bot(game, sim, cfg, o) {
  o = o || {};
  var days = o.days || 3;
  var tripMin = o.tripMin || 25;
  var play = o.play || defaultPlay;
  var taps = o.taps === undefined ? 3 : o.taps;
  var hz = o.hz || 1;
  var gaps = o.gaps || [9 * HOUR, 15 * HOUR - 2 * tripMin * MIN];
  var rng = Rng.make(12345);
  var now = Date.UTC(2026, 10, 2, 5, 0, 0);
  var state = Core.newState(game, sim, ctxOf(cfg, rng, now));
  var layer = game.prestige && game.prestige[0];
  var firstBuySec = null;
  var prestiges = [];
  var trip = 0;
  for (var d = 0; d < days; d++) {
    for (var k = 0; k < 2; k++) {
      trip++;
      for (var s = 0; s < tripMin * 60; s++) {
        var bought = play(state, ctxOf(cfg, rng, now), sim, taps);
        for (var t = 0; t < hz; t++) sim.tick(state, 1 / hz, ctxOf(cfg, rng, now + (t + 1) * 1000 / hz));
        now += 1000;
        if (firstBuySec === null && trip === 1 && bought) firstBuySec = s + 1;
        if (layer && s % 30 === 29) {
          var info = Core.prestigeInfo(sim, game, state, layer.id, cfg);
          if (info.can && info.hint) {
            var r = Core.prestige(sim, game, state, layer.id, ctxOf(cfg, rng, now));
            if (r.ok) prestiges.push({ trip: trip, gain: Big.str(r.gain) });
          }
        }
      }
      state.lastTick = now;
      var p = Core.plan(now, now + gaps[k % gaps.length], cfg, 0);
      now = p.to;
      Core.runOffline(sim, state, game, p, null, function (seg) { return ctxOf(cfg, rng, seg.to); }, cfg);
      state.lastTick = now;
    }
  }
  return { firstBuySec: firstBuySec, prestiges: prestiges, trips: trip, state: state };
}

/** 12 hours by sim.offline against the same by steps: the first currency of both. */
function offlineAgree(game, sim, cfg, setupSec, play) {
  var rng = Rng.make(777);
  var now = Date.UTC(2026, 10, 2, 8, 0, 0);
  var base = Core.newState(game, sim, ctxOf(cfg, rng, now));
  var setup = setupSec === undefined ? 300 : setupSec;
  for (var s = 0; s < setup; s++) {
    (play || defaultPlay)(base, ctxOf(cfg, rng, now), sim, 3);
    sim.tick(base, 1, ctxOf(cfg, rng, now));
    now += 1000;
  }
  base.lastTick = now;
  var p = Core.plan(now, now + 12 * HOUR, cfg, 0);
  var a = Core.clone(base);
  var b = Core.clone(base);
  var stepsSim = {};
  for (var k in sim) if (k !== 'offline') stepsSim[k] = sim[k];
  var mk = function (seg) { return ctxOf(cfg, Rng.make(1), seg.to); };
  var ra = Core.runOffline(sim, a, game, p, null, mk, cfg);
  var rb = Core.runOffline(stepsSim, b, game, p, null, mk, cfg);
  var cur = game.currencies[0];
  var va = Big.of(Core.get(a, cur.path), 0);
  var vb = Big.of(Core.get(b, cur.path), 0);
  var diff = Big.isZero(va) && Big.isZero(vb) ? 0 : Big.toNumber(Big.div(Big.abs(Big.sub(va, vb)), Big.max(Big.abs(va), Big.abs(vb))));
  return { formula: Big.str(va), steps: Big.str(vb), diff: diff, stepsRun: rb.steps, formulaUsed: ra.formula };
}

/** The standard checks of the game in `dir` (its __dirname): {name: fn(t)}. */
function standard(dir, o) {
  o = o || {};
  var id = idOf(dir);
  var path = 'modules/games/' + id;
  function load() {
    var game = readJson(path + '/game.json');
    game.id = id;
    game.currencies = game.currencies || [];
    game.prestige = game.prestige || [];
    game.achievements = game.achievements || [];
    return { game: game, sim: require(path + '/sim.js'), cfg: configOf(game, path) };
  }
  var checks = {};

  checks['новая игра проходит проверку'] = function (t) {
    var x = load();
    var state = Core.newState(x.game, x.sim, ctxOf(x.cfg, Rng.make(1), Date.now()));
    t.equal(Core.validate(state, x.game), null, 'validate новой игры');
    if (x.sim.validate) t.equal(x.sim.validate(state), null, 'sim.validate новой игры');
    t.ok(Core.card(x.sim, state, x.cfg) !== undefined, 'summary');
  };

  checks['сохранение туда и обратно'] = function (t) {
    var x = load();
    var state = Core.newState(x.game, x.sim, ctxOf(x.cfg, Rng.make(2), Date.now()));
    (o.play || defaultPlay)(state, ctxOf(x.cfg, Rng.make(2), Date.now()), x.sim, 20);
    x.sim.tick(state, 5, ctxOf(x.cfg, Rng.make(2), Date.now()));
    var json = JSON.stringify(state);
    var picked = Core.pick([{ json: json, seq: 1, source: 'check' }], x.game, x.sim);
    t.ok(picked.state, 'сохранение не читается: ' + JSON.stringify(picked.broken));
    t.equal(JSON.stringify(picked.state.run), JSON.stringify(JSON.parse(json).run), 'run после чтения');
  };

  checks['старые сохранения читаются (fixtures)'] = function (t) {
    var x = load();
    var assi = require('lib/assi');
    var names = [];
    try {
      names = assi.files.list(path + '/fixtures').filter(function (n) { return /\.json$/.test(n); });
    } catch (ignored) { /* none */ }
    if (!names.length) t.skip('нет fixtures/*.json');
    names.forEach(function (name) {
      var state = readJson(path + '/fixtures/' + name);
      state.game = id;
      var picked = Core.pick([{ state: state, seq: state.seq, source: 'fixture' }], x.game, x.sim);
      t.ok(picked.state, name + ' не читается: ' + JSON.stringify(picked.broken) + (picked.newer ? ' (новее игры)' : ''));
    });
  };

  checks['офлайн 12 ч: формула и шаги совпадают'] = function (t) {
    var x = load();
    if (typeof x.sim.offline !== 'function') t.skip('у игры нет sim.offline — считается шагами');
    var r = offlineAgree(x.game, x.sim, x.cfg, o.setupSec, o.play);
    t.ok(!Big.isZero(Big.of(r.steps)) || o.zeroOffline, 'за 12 часов ничего не накопилось — бот ничего не купил? (play)');
    t.ok(r.diff <= 0.01, 'расхождение ' + (r.diff * 100).toFixed(2) + ' %: формула ' + r.formula + ', шаги ' + r.steps);
  };

  checks['темп: три дня по две поездки'] = function (t) {
    var x = load();
    if (o.pace === false) t.skip('темп проверяет своя проверка игры');
    var r = bot(x.game, x.sim, x.cfg, o);
    var firstBuy = o.firstBuySec || 10;
    t.ok(r.firstBuySec !== null || !x.sim.buy, 'бот ничего не купил за первую поездку');
    if (r.firstBuySec !== null) t.ok(r.firstBuySec <= firstBuy, 'первая покупка на ' + r.firstBuySec + ' с, нужно не позже ' + firstBuy);
    if (x.game.prestige.length) {
      var range = o.prestigeTrips || [3, 6];
      t.ok(r.prestiges.length > 0, 'за ' + r.trips + ' поездок ни одного престижа');
      var first = r.prestiges[0].trip;
      t.ok(first >= range[0] && first <= range[1], 'первый престиж в поездке ' + first + ', нужно ' + range[0] + '–' + range[1]);
    }
  };

  checks['без сети'] = function (t) {
    var r = require('modules/games/main.js').offlineCheck(id);
    t.ok(r.ok, 'адреса в сети: ' + r.hits.map(function (h) { return h.path + ':' + h.line + ' ' + h.url; }).join('; '));
  };

  checks['экран открывается при 344 и 690'] = function (t) {
    var page = t.screen('modules/games/ui/preview.html?game=' + encodeURIComponent(id) + '&w=both');
    var ok = false;
    var errors = '';
    for (var i = 0; i < 40 && !ok; i++) {
      t.wait(250);
      var r = page.eval('(function(){var f=document.querySelectorAll("iframe");var n=0,e=[];for(var i=0;i<f.length;i++){var w=f[i].contentWindow;' +
        'if(w&&w.__game)n++;var bad=w?w.document.querySelectorAll(".g-error"):[];for(var j=0;j<bad.length;j++){var x=(bad[j].textContent||"").trim();if(x&&!bad[j].closest("[hidden]"))e.push(x)}}return {n:n,all:f.length,errors:e}})()', 0);
      ok = r && r.n === r.all && r.all === 2;
      errors = r && r.errors && r.errors.length ? r.errors.join('; ') : '';
      if (errors) break;
    }
    t.ok(!errors, 'ошибка на экране: ' + errors);
    t.ok(ok, 'экран игры не запустился в рамке 344 и 690');
  };

  return checks;
}

exports.standard = standard;
exports.bot = bot;
exports.offlineAgree = offlineAgree;
exports.defaultPlay = defaultPlay;
exports.configOf = configOf;
exports.idOf = idOf;

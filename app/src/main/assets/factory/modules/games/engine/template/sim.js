// The logic of «Искры» — the template of a game for «Игротека». Shared subset of JavaScript: it
// runs on the screen (window.GameSim), in the engine (Rhino, require) and in node. No class, no
// async, no f(...args), no for (const …); randomness only from ctx.rng / the rng given.
//
// Copy the folder to modules/games/<id>/ — the paths ../engine/… below are right from there.
// Every number is a setting of config.json: here it comes as ctx.config or cfg.
(function (root, make) {
  var node = typeof require === 'function' && typeof exports !== 'undefined';
  var api = node ? make(require('../engine/big.js'), require('../engine/fmt.js')) : make(root.Big, root.Fmt);
  if (typeof exports !== 'undefined') { for (var k in api) exports[k] = api[k]; } else { root.GameSim = api; }
})(this, function (Big, Fmt) {
  'use strict';

  var LAYER = 'ember';

  // ---- helpers the screen and the checks call too ----

  /** Sparks a second from the forges, with the bonus of the embers (before the links' multiplier). */
  function income(state, cfg) {
    var embers = Big.of(state.layers[LAYER] ? state.layers[LAYER].currency : 0, 0);
    var bonus = Big.add(1, Big.mul(embers, cfg['ember.bonus']));
    return Big.mul(Big.mul(state.run.forges, cfg['forge.income']), bonus);
  }

  /** The price of the next forge (prices round up on the screen: Fmt.price). */
  function cost(state, cfg) {
    return Big.costGeom(cfg['forge.baseCost'], cfg['forge.growth'], state.run.forges, 1);
  }

  function gain(state, amount) {
    state.run.sparks = Big.add(state.run.sparks, amount);
    state.run.earned = Big.add(state.run.earned, amount);
  }

  /** A strike of the hammer: sparks now, more with every forge; mult — the links' multiplier. */
  function hammer(state, cfg, mult) {
    var amount = Big.mul(cfg['hammer.sparks'] + cfg['hammer.perForge'] * state.run.forges, mult || 1);
    gain(state, amount);
    state.run.strikes++;
    return amount;
  }

  /** Buys a forge when there is enough; true when bought. */
  function buy(state, cfg) {
    var price = cost(state, cfg);
    if (Big.lt(state.run.sparks, price)) return false;
    state.run.sparks = Big.sub(state.run.sparks, price);
    state.run.forges++;
    return true;
  }

  function boosted(state, now) { return state.run.boostUntil > now; }

  // ---- the functions the engine calls ----

  function init(ctx) {
    return {
      run: { sparks: '0e0', earned: '0e0', forges: 0, strikes: 0, boostUntil: 0 },
      layers: {},
      meta: {}
    };
  }

  function tick(state, dt, ctx) {
    var cfg = ctx.config;
    var m = (ctx.mult || 1) * (boosted(state, ctx.now) ? cfg['boost.mult'] : 1);
    if (state.run.forges > 0) gain(state, Big.mul(income(state, cfg), dt * m));
  }

  /** The same as tick for `sec` seconds at once (the engine compares them in checks). */
  function offline(state, sec, ctx) {
    var cfg = ctx.config;
    if (state.run.forges <= 0) return { events: [] };
    // The part of the absence under the bellows' boost (it ends by the clock).
    var start = ctx.now - sec * 1000;
    var boostedSec = Math.max(0, Math.min(sec, (state.run.boostUntil - start) / 1000));
    var effective = (sec - boostedSec) + boostedSec * cfg['boost.mult'];
    gain(state, Big.mul(income(state, cfg), effective * (ctx.mult || 1)));
    return { events: [] };
  }

  function prestigeGain(state, layerId, cfg) {
    var ratio = Big.div(state.run.earned, cfg['ember.threshold']);
    if (Big.lt(ratio, 1)) return Big.ZERO;
    return Big.floor(Big.pow(ratio, cfg['ember.power']));
  }

  /** The bonus the layer's currency gives: shown in the prestige sheet as ×1,40 → ×1,52. */
  function bonus(state, layerId, amount, cfg) {
    return Big.add(1, Big.mul(amount, cfg['ember.bonus']));
  }

  function onPrestige(state, layerId, before, ctx) {
    var l = state.layers[LAYER];
    l.state.bestForges = Math.max(l.state.bestForges || 0, before.run.forges);
  }

  function migrate(state, fromVer) {
    // Version 1 is the first: nothing to translate yet. A new version adds a case here and a
    // fixtures/v<old>.json with a save of the old version.
    return state;
  }

  function validate(state) {
    var r = state.run;
    if (typeof r.forges !== 'number' || r.forges < 0 || r.forges % 1 !== 0) return 'горнов не целое число: ' + r.forges;
    if (!Big.valid(r.sparks) || Big.sign(r.sparks) < 0) return 'искр меньше нуля';
    return null;
  }

  function summary(state, cfg) {
    var parts = [Fmt.rate(income(state, cfg)), 'горнов ' + state.run.forges];
    var l = state.layers[LAYER];
    if (l && l.count) parts.push('перековок ' + l.count);
    return { line: parts.join(' · ') };
  }

  /** When there will be enough for the next forge (the notification «Хватает на новый горн»). */
  function next(state, now, cfg) {
    var rate = income(state, cfg);
    if (Big.isZero(rate)) return [];
    var need = Big.sub(cost(state, cfg), state.run.sparks);
    if (Big.sign(need) <= 0) return [];
    var sec = Big.toNumber(Big.div(need, rate));
    if (!(sec < 86400)) return [];
    return [{ id: 'forge', at: now + Math.ceil(sec) * 1000, text: 'Искр хватает на горн №' + (state.run.forges + 1) }];
  }

  /** The chest of «Пассажир»: income of chest.minutes, and as much again by luck. */
  function chest(state, rng, cfg) {
    var minutes = cfg['chest.minutes'] * (1 + rng.int(3) * 0.5);
    var amount = Big.max(Big.mul(income(state, cfg), minutes * 60), Big.mul(cfg['hammer.sparks'], 100));
    gain(state, amount);
    return { title: 'Сундук кузнеца', items: [{ title: 'Искры', amount: amount }] };
  }

  var WORDS = {
    a: ['Старый', 'Северный', 'Тихий', 'Рыжий', 'Весёлый', 'Хмурый'],
    b: ['кузнец', 'подмастерье', 'мех', 'горн', 'молот', 'уголёк'],
    c: ['любит, когда искры летят вверх', 'не терпит сырого угля', 'поёт в такт ударам', 'просыпается раньше всех', 'бережёт каждую окалину']
  };

  /** A tip made without the model, when the stock ran out in the metro. */
  function procedural(kind, rng) {
    return { title: rng.pick(WORDS.a) + ' ' + rng.pick(WORDS.b), text: rng.pick(WORDS.c), procedural: true };
  }

  function validateStock(kind, item) {
    if (kind === 'tips' && /https?:/.test(item.text || '')) return 'ссылка в тексте';
    return null;
  }

  return {
    init: init, tick: tick, offline: offline, prestigeGain: prestigeGain, bonus: bonus, onPrestige: onPrestige,
    migrate: migrate, validate: validate, summary: summary, next: next, chest: chest,
    procedural: procedural, validateStock: validateStock,
    income: income, cost: cost, hammer: hammer, buy: buy, LAYER: LAYER
  };
});

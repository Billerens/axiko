// «Шахта» — the fixture game of the node tests of «Игротека» (scripts/games). The tests copy it to
// modules/games/fixture/ of a temporary workspace, so ../engine/ below is the hub's engine.
// An idle clicker: gold by the pick and by carts, one prestige layer (gems), an offline formula,
// two achievements, a stock kind, save version 2 (version 1 kept gold in run.gold).
(function (root, make) {
  var node = typeof require === 'function' && typeof exports !== 'undefined';
  var api = node ? make(require('../engine/big.js'), require('../engine/fmt.js')) : make(root.Big, root.Fmt);
  if (typeof exports !== 'undefined') { for (var k in api) exports[k] = api[k]; } else { root.GameSim = api; }
})(this, function (Big, Fmt) {
  'use strict';

  var LAYER = 'depth';

  function gems(state) { return Big.of(state.layers[LAYER] ? state.layers[LAYER].currency : 0, 0); }

  function income(state, cfg) {
    var bonus = Big.add(1, Big.mul(gems(state), cfg['depth.bonus']));
    return Big.mul(Big.mul(state.run.carts, cfg['cart.rate']), bonus);
  }

  function cost(state, cfg) { return Big.costGeom(cfg['cart.cost'], cfg['cart.growth'], state.run.carts, 1); }

  function gain(state, amount) {
    state.run.coins = Big.add(state.run.coins, amount);
    state.run.mined = Big.add(state.run.mined, amount);
  }

  /** A strike of the pick. */
  function dig(state, cfg, mult) {
    var amount = Big.mul(cfg['pick.coins'], mult || 1);
    gain(state, amount);
    state.run.digs++;
    return amount;
  }

  function buy(state, cfg) {
    var price = cost(state, cfg);
    if (Big.lt(state.run.coins, price)) return false;
    state.run.coins = Big.sub(state.run.coins, price);
    state.run.carts++;
    return true;
  }

  function init(ctx) {
    return { run: { coins: '0e0', mined: '0e0', carts: 0, digs: 0 }, layers: {}, meta: {} };
  }

  function tick(state, dt, ctx) {
    if (state.run.carts > 0) gain(state, Big.mul(income(state, ctx.config), dt * (ctx.mult || 1)));
  }

  function offline(state, sec, ctx) {
    if (state.run.carts <= 0) return { events: [] };
    var amount = Big.mul(income(state, ctx.config), sec * (ctx.mult || 1));
    gain(state, amount);
    var trips = Math.floor(sec / 600) * state.run.carts;
    return { events: trips > 0 ? ['Вагонетки сделали рейсов: ' + Fmt.num(trips)] : [] };
  }

  function prestigeGain(state, layerId, cfg) {
    var ratio = Big.div(state.run.mined, cfg['depth.threshold']);
    if (Big.lt(ratio, 1)) return Big.ZERO;
    return Big.floor(Big.sqrt(ratio));
  }

  function bonus(state, layerId, amount, cfg) { return Big.add(1, Big.mul(amount, cfg['depth.bonus'])); }

  function onPrestige(state, layerId, before, ctx) {
    var l = state.layers[LAYER];
    l.state.deepest = Math.max(l.state.deepest || 0, before.run.carts);
  }

  /** Version 1 kept gold in run.gold and had no run.mined. */
  function migrate(state, fromVer) {
    if (fromVer < 2) {
      state.run.coins = state.run.gold === undefined ? '0e0' : state.run.gold;
      delete state.run.gold;
      if (state.run.mined === undefined) state.run.mined = state.run.coins;
      if (state.run.digs === undefined) state.run.digs = 0;
    }
    return state;
  }

  function validate(state) {
    var r = state.run;
    if (typeof r.carts !== 'number' || r.carts < 0 || r.carts % 1 !== 0) return 'вагонеток не целое число: ' + r.carts;
    if (!Big.valid(r.coins) || Big.sign(r.coins) < 0) return 'золота меньше нуля';
    if (!Big.valid(r.mined)) return 'нет добытого за забег';
    return null;
  }

  function summary(state, cfg) {
    var parts = [Fmt.rate(income(state, cfg)), 'вагонеток ' + state.run.carts];
    var g = gems(state);
    if (!Big.isZero(g)) parts.push('самоцветов ' + Fmt.num(g));
    return { line: parts.join(' · ') };
  }

  function next(state, now, cfg) {
    var rate = income(state, cfg);
    if (Big.isZero(rate)) return [];
    var need = Big.sub(cost(state, cfg), state.run.coins);
    if (Big.sign(need) <= 0) return [];
    var sec = Big.toNumber(Big.div(need, rate));
    if (!(sec < 86400)) return [];
    return [{ id: 'cart', at: now + Math.ceil(sec) * 1000, text: 'Золота хватает на вагонетку №' + (state.run.carts + 1) }];
  }

  function chest(state, rng, cfg) {
    var amount = Big.max(Big.mul(income(state, cfg), cfg['chest.minutes'] * 60 * (1 + rng.int(2))), 50);
    gain(state, amount);
    return { title: 'Сундук штейгера', items: [{ title: 'Золото', amount: amount }] };
  }

  var WHO = ['Старый штейгер', 'Рыжий откатчик', 'Молчаливый взрывник', 'Новенький крепильщик'];
  var WHAT = ['слышал стук под третьим штреком', 'нашёл жилу и потерял её снова', 'клянётся, что видел подземное озеро'];

  function procedural(kind, rng) { return { title: rng.pick(WHO), text: rng.pick(WHAT), procedural: true }; }

  function validateStock(kind, item) {
    if (kind === 'rumors' && /\d{4,}/.test(item.text || '')) return 'в слухе длинное число';
    return null;
  }

  return {
    init: init, tick: tick, offline: offline, prestigeGain: prestigeGain, bonus: bonus, onPrestige: onPrestige,
    migrate: migrate, validate: validate, summary: summary, next: next, chest: chest, procedural: procedural,
    validateStock: validateStock, income: income, cost: cost, dig: dig, buy: buy, LAYER: LAYER
  };
});

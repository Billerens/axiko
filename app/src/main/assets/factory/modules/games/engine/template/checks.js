// The checks of the game (module_check with 'games/<id>'): the engine's standard ones, then the
// game's own. A copy of the template keeps this file as it is: the id comes from the folder.
var kit = require('../engine/checks-kit.js');
var Big = require('../engine/big.js');
var Rng = require('../engine/rng.js');
var Core = require('../engine/core.js');

var checks = kit.standard(__dirname, { firstBuySec: 10, prestigeTrips: [3, 6] });
for (var name in checks) exports[name] = checks[name];

function load() {
  var id = kit.idOf(__dirname);
  var axiko = require('lib/axiko');
  var game = JSON.parse(axiko.files.read('modules/games/' + id + '/game.json'));
  game.id = id;
  var sim = require('modules/games/' + id + '/sim.js');
  return { game: game, sim: sim, cfg: kit.configOf(game, 'modules/games/' + id) };
}

exports['перековка: уголь растёт, забег с нуля'] = function (t) {
  var x = load();
  var rng = Rng.make(5);
  var ctx = { now: Date.now(), config: x.cfg, rng: rng, mult: 1 };
  var state = Core.newState(x.game, x.sim, ctx);
  state.run.earned = Big.mul(x.cfg['ember.threshold'], 9);
  state.run.forges = 30;
  var r = Core.prestige(x.sim, x.game, state, 'ember', ctx);
  t.ok(r.ok, 'перековка не прошла');
  t.equal(Big.str(state.layers.ember.currency), Big.str(Big.of(3)), 'уголь после перековки');
  t.equal(state.run.forges, 0, 'горны после перековки');
  t.equal(state.layers.ember.state.bestForges, 30, 'лучший забег запомнен');
};

exports['сундук «Пассажира» даёт искры'] = function (t) {
  var x = load();
  var ctx = { now: Date.now(), config: x.cfg, rng: Rng.make(9), mult: 1 };
  var state = Core.newState(x.game, x.sim, ctx);
  state.run.forges = 5;
  var c = x.sim.chest(state, Rng.make(3), x.cfg);
  t.ok(c.items.length && Big.gt(c.items[0].amount, 0), 'пустой сундук');
  t.ok(Big.gt(state.run.sparks, 0), 'искры не прибавились');
};

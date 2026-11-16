// The checks of «Шахта»: the engine's standard ones (engine/checks-kit.js).
var kit = require('../engine/checks-kit.js');
var checks = kit.standard(__dirname, {
  firstBuySec: 10,
  prestigeTrips: [3, 6],
  play: function (state, ctx, sim, taps) {
    for (var i = 0; i < taps; i++) sim.dig(state, ctx.config, ctx.mult);
    var bought = 0;
    while (bought < 50 && sim.buy(state, ctx.config)) bought++;
    return bought;
  }
});
for (var name in checks) exports[name] = checks[name];

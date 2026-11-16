// Seeded randomness for the games: mulberry32. The same seed gives the same sequence on a
// screen, in the engine (Rhino) and in node, so offline progress and checks are reproducible.
// Shared subset (screen: window.Rng). ES5 only.
//
//   var rng = Rng.make(state.meta.seed);
//   rng.next()            [0, 1)
//   rng.int(6)            0..5          rng.int(1, 6)   1..6
//   rng.range(0.5, 2)     [0.5, 2)      rng.chance(0.1)
//   rng.pick(list)        rng.weighted(list, 'weight' | function (item) | [weights])
//   rng.shuffle(list)     in place      rng.state()    the generator's state to keep
//   Rng.hash('text')      a 32-bit number for a seed      Rng.seed()  a fresh random seed
(function (root, make) {
  var api = make();
  if (typeof exports !== 'undefined') { for (var k in api) exports[k] = api[k]; } else { root.Rng = api; }
})(this, function () {
  'use strict';

  var imul = Math.imul || function (a, b) {
    var ah = (a >>> 16) & 0xffff;
    var al = a & 0xffff;
    var bh = (b >>> 16) & 0xffff;
    var bl = b & 0xffff;
    return ((al * bl) + (((ah * bl + al * bh) << 16) >>> 0)) | 0;
  };

  /** FNV-1a of a text: a seed derived from a name, a date, a game id. */
  function hash(text) {
    var h = 0x811c9dc5;
    text = String(text);
    for (var i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i);
      h = imul(h, 0x01000193);
    }
    return h >>> 0;
  }

  function seed() {
    return (Math.floor(Math.random() * 4294967296) ^ (Date.now() % 4294967296)) >>> 0;
  }

  function make(start) {
    var s = Number(start) >>> 0;

    function next() {
      s = (s + 0x6D2B79F5) | 0;
      var t = s;
      t = imul(t ^ (t >>> 15), t | 1);
      t ^= t + imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    }

    var rng = {
      next: next,
      int: function (a, b) {
        if (b === undefined) {
          b = a - 1;
          a = 0;
        }
        return a + Math.floor(next() * (b - a + 1));
      },
      range: function (a, b) { return a + next() * (b - a); },
      chance: function (p) { return next() < p; },
      pick: function (list) { return list.length ? list[Math.floor(next() * list.length)] : undefined; },
      weighted: function (list, weight) {
        var weights = [];
        var total = 0;
        for (var i = 0; i < list.length; i++) {
          var w = Array.isArray(weight) ? weight[i] : typeof weight === 'function' ? weight(list[i], i)
            : typeof weight === 'string' ? list[i][weight] : 1;
          w = w > 0 ? Number(w) : 0;
          weights.push(w);
          total += w;
        }
        if (total <= 0) return undefined;
        var x = next() * total;
        for (var j = 0; j < list.length; j++) {
          x -= weights[j];
          if (x < 0) return list[j];
        }
        return list[list.length - 1];
      },
      shuffle: function (list) {
        for (var i = list.length - 1; i > 0; i--) {
          var j = Math.floor(next() * (i + 1));
          var t = list[i];
          list[i] = list[j];
          list[j] = t;
        }
        return list;
      },
      state: function () { return s >>> 0; },
      /** An independent generator for a side task (a chest, a day's run) that leaves this one as it is. */
      fork: function (label) { return make((hash(String(label)) ^ (s >>> 0)) >>> 0); }
    };
    return rng;
  }

  return { make: make, hash: hash, seed: seed };
});

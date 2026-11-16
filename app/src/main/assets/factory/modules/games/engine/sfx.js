// Sounds made by Web Audio — oscillators and noise with an envelope, no files, so nothing to
// fetch in the metro: window.Sfx.
//   Sfx.play('buy', 0.6)          tap buy deny level chest achievement prestige alarm
//   Sfx.add('drill', [{type: 'sawtooth', f: [90, 60], d: 0.3}])     a sound of the game's own
//   Sfx.add('bell', 'https://assi.local/data/games/<id>/assets/bell.ogg')   or a file in data/
// A voice: {type: sine|square|triangle|sawtooth|noise, f: [from, to] Hz, d: seconds, at: start,
// v: loudness 0..1, a: attack seconds}. The engine (g.sfx) decides whether to sound at all.
(function () {
  var ctx = null;
  var files = {};

  var SOUNDS = {
    tap: [{ type: 'triangle', f: [900, 700], d: 0.035, v: 0.35 }],
    buy: [{ type: 'triangle', f: [660, 880], d: 0.07, v: 0.5 }, { at: 0.06, type: 'triangle', f: [990, 1320], d: 0.09, v: 0.45 }],
    deny: [{ type: 'square', f: [220, 170], d: 0.09, v: 0.22 }, { at: 0.11, type: 'square', f: [200, 150], d: 0.11, v: 0.22 }],
    level: [{ type: 'triangle', f: [523, 523], d: 0.08 }, { at: 0.08, type: 'triangle', f: [659, 659], d: 0.08 }, { at: 0.16, type: 'triangle', f: [784, 784], d: 0.14 }],
    chest: [{ type: 'noise', f: [1, 1], d: 0.18, v: 0.25 }, { at: 0.1, type: 'sine', f: [880, 1760], d: 0.25, v: 0.4 }, { at: 0.22, type: 'sine', f: [1320, 1320], d: 0.3, v: 0.3 }],
    achievement: [{ type: 'sine', f: [784, 784], d: 0.16, v: 0.5 }, { at: 0.13, type: 'sine', f: [1175, 1175], d: 0.32, v: 0.45 }],
    prestige: [{ type: 'sawtooth', f: [110, 880], d: 0.6, v: 0.18, a: 0.05 }, { at: 0.5, type: 'triangle', f: [523, 523], d: 0.5, v: 0.4 },
      { at: 0.5, type: 'triangle', f: [659, 659], d: 0.5, v: 0.35 }, { at: 0.5, type: 'triangle', f: [784, 784], d: 0.6, v: 0.35 }],
    alarm: [{ type: 'square', f: [988, 988], d: 0.12, v: 0.3 }, { at: 0.2, type: 'square', f: [988, 988], d: 0.12, v: 0.3 },
      { at: 0.4, type: 'square', f: [988, 988], d: 0.12, v: 0.3 }]
  };

  function audio() {
    var Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) return null;
    if (!ctx) ctx = new Ctor({ latencyHint: 'interactive' });
    if (ctx.state === 'suspended') ctx.resume();
    return ctx;
  }

  var noiseBuffer = null;
  function noise(ac) {
    if (noiseBuffer) return noiseBuffer;
    noiseBuffer = ac.createBuffer(1, ac.sampleRate * 0.5, ac.sampleRate);
    var data = noiseBuffer.getChannelData(0);
    for (var i = 0; i < data.length; i++) data[i] = Math.random() * 2 - 1;
    return noiseBuffer;
  }

  function voice(ac, out, v, start) {
    var t0 = start + (v.at || 0);
    var d = v.d || 0.1;
    var gain = ac.createGain();
    var peak = Math.max(0.0001, v.v === undefined ? 0.4 : v.v);
    var attack = v.a || 0.005;
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.exponentialRampToValueAtTime(peak, t0 + attack);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + d);
    gain.connect(out);
    var src;
    if (v.type === 'noise') {
      src = ac.createBufferSource();
      src.buffer = noise(ac);
    } else {
      src = ac.createOscillator();
      src.type = v.type || 'sine';
      var f = v.f || [440, 440];
      src.frequency.setValueAtTime(f[0], t0);
      if (f[1] && f[1] !== f[0]) src.frequency.exponentialRampToValueAtTime(f[1], t0 + d);
    }
    src.connect(gain);
    src.start(t0);
    src.stop(t0 + d + 0.02);
  }

  function play(name, volume) {
    var ac = audio();
    if (!ac) return false;
    var out = ac.createGain();
    out.gain.value = volume === undefined ? 0.6 : Math.max(0, Math.min(1, volume));
    out.connect(ac.destination);
    var sound = SOUNDS[name];
    if (typeof sound === 'string') {
      var buffer = files[sound];
      if (!buffer) {
        load(sound);
        return false;
      }
      var src = ac.createBufferSource();
      src.buffer = buffer;
      src.connect(out);
      src.start();
      return true;
    }
    if (!sound) return false;
    var start = ac.currentTime + 0.005;
    sound.forEach(function (v) { voice(ac, out, v, start); });
    return true;
  }

  function load(url) {
    var ac = audio();
    if (!ac || files[url] === null) return;
    files[url] = null;
    fetch(url).then(function (r) { return r.arrayBuffer(); }).then(function (data) {
      return new Promise(function (resolve, reject) { ac.decodeAudioData(data, resolve, reject); });
    }).then(function (buffer) { files[url] = buffer; }).catch(function () { delete files[url]; });
  }

  function add(name, sound) {
    SOUNDS[name] = sound;
    if (typeof sound === 'string') load(sound);
  }

  // The first touch unlocks audio in the WebView (a context may only start after a gesture).
  window.addEventListener('pointerdown', function unlock() {
    audio();
    window.removeEventListener('pointerdown', unlock, true);
  }, true);

  window.Sfx = { play: play, add: add, names: Object.keys(SOUNDS) };
})();

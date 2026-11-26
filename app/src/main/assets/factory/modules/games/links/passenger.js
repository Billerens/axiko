// «Пассажир» (docs/game-development.md): Axiko notices that the owner rides without network, and
// while the trip lasts the income of the games is multiplied; a long trip brings a chest.
//
// The automaton none → maybe → trip → none runs on signs: no network, the shaking of a carriage
// (accelerometer windows), a descent under ground (barometer), a metro Wi-Fi in sight, the
// buttons «Я еду» / «Я не еду». The pure part (classify, descent, step, segments, text) is tested
// in node; the rest listens to the phone and keeps data/games/links/passenger.json.

var FILE = 'data/games/links/passenger.json';
var MIN = 60000;
var M_PER_HPA = 8.4;

// ---- pure logic --------------------------------------------------------------------------------

function blank() {
  return {
    state: 'none', since: null, manual: false, wasOffline: false, dismissed: null,
    signals: { offlineSince: null, onlineSince: null, lastOffline: null, windows: [], stillSince: null, descentHpa: 0, ssid: null, walking: false },
    pressure: [], counter: [], trips: [], chestsToday: { date: '', count: 0 }, total: 0, achieved: {}
  };
}

/** The class of an accelerometer window {rms, freq, steps}: still | walk | train | unknown. */
function classify(w, cfg) {
  if (!w) return 'unknown';
  var still = cfg['passenger.stillRms'];
  var walk = cfg['passenger.walkRms'];
  if (w.rms < still) return 'still';
  var rhythm = w.freq >= 1.5 && w.freq <= 2.3;
  // A walk: steps in the window (more than 2 — one or two are a shift in the seat), or a step's rhythm.
  if (w.steps > 2 || (w.rms > walk && rhythm)) return 'walk';
  if (rhythm) return 'unknown';
  if (w.rms >= cfg['passenger.trainMinRms'] && w.rms <= cfg['passenger.trainMaxRms']) return 'train';
  return 'unknown';
}

/**
 * How much the pressure rose around the loss of network (hPa): the largest p(b) − p(a) where a
 * lies within descentMin before or after the loss and b after a. A rise of 2 hPa is about 17 m
 * down — an escalator. Also works after a long sleep in a pocket: the reading below is higher.
 */
function descent(pressure, offlineSince, cfg) {
  if (!offlineSince || !pressure || pressure.length < 2) return 0;
  var win = cfg['passenger.descentMin'] * MIN;
  var best = 0;
  for (var i = 0; i < pressure.length; i++) {
    var a = pressure[i];
    if (a[0] < offlineSince - win || a[0] > offlineSince + win) continue;
    for (var j = i + 1; j < pressure.length; j++) {
      var b = pressure[j];
      if (b[0] <= a[0]) continue;
      if (b[1] - a[1] > best) best = b[1] - a[1];
    }
  }
  return Math.round(best * 10) / 10;
}

function trainCount(windows) {
  var last = windows.slice(-3);
  var n = 0;
  last.forEach(function (w) { if (w === 'train') n++; });
  return n;
}

/** What made the trip: ['offline', 'train', 'descent', 'wifi', 'manual']. */
function reasons(st, cfg) {
  var out = [];
  if (st.manual) out.push('manual');
  if (st.signals.offlineSince || st.wasOffline) out.push('offline');
  if (trainCount(st.signals.windows) >= 2) out.push('train');
  if (st.signals.descentHpa >= cfg['passenger.descentHpa']) out.push('descent');
  if (st.signals.ssid) out.push('wifi');
  return out;
}

function endTrip(st, end, cfg, events) {
  var start = st.since;
  end = Math.max(start, end);
  var trip = { start: start, end: end, min: Math.round((end - start) / MIN), signals: reasons(st, cfg), manual: !!st.manual };
  st.trips.push(trip);
  if (st.trips.length > 200) st.trips = st.trips.slice(-200);
  st.total = (st.total || 0) + 1;
  events.push({ type: 'end', trip: trip });
  st.state = 'none';
  st.since = null;
  st.manual = false;
  st.wasOffline = false;
  st.signals.windows = [];
  st.signals.descentHpa = 0;
  st.signals.ssid = null;
  st.signals.stillSince = null;
}

/**
 * One step of the automaton. ev: {now, online?, window?, walking?, descent?, ssid?, manual?:
 * 'on'|'off'}; fields left out did not change. Returns the events: [{type: maybe|trip|none|end,
 * trip?}]. st is changed in place.
 */
function step(st, ev, cfg) {
  var now = ev.now;
  var s = st.signals;
  var events = [];
  if (ev.online === true) {
    if (s.offlineSince !== null) s.lastOffline = now;
    s.offlineSince = null;
    if (s.onlineSince === null) s.onlineSince = now;
  } else if (ev.online === false) {
    if (s.offlineSince === null) s.offlineSince = now;
    s.onlineSince = null;
    if (st.state === 'trip') st.wasOffline = true;
  }
  if (ev.window) {
    var cls = ev.walking ? 'walk' : ev.window;
    s.windows.push(cls);
    if (s.windows.length > 6) s.windows = s.windows.slice(-6);
    if (cls === 'still') {
      if (s.stillSince === null) s.stillSince = now;
    } else {
      s.stillSince = null;
    }
  }
  if (ev.walking !== undefined) s.walking = !!ev.walking;
  if (ev.descent !== undefined && ev.descent > s.descentHpa) s.descentHpa = ev.descent;
  if (ev.ssid) s.ssid = ev.ssid;

  if (ev.manual === 'on') {
    if (st.state !== 'trip') {
      st.state = 'trip';
      st.since = now;
      st.manual = true;
      st.wasOffline = s.offlineSince !== null;
      events.push({ type: 'trip' });
    } else {
      st.manual = true;
    }
    return events;
  }
  if (ev.manual === 'off') {
    if (st.state === 'trip') endTrip(st, now, cfg, events);
    st.state = 'none';
    st.since = null;
    st.dismissed = s.offlineSince;
    events.push({ type: 'none' });
    return events;
  }

  var offlineFor = s.offlineSince !== null ? now - s.offlineSince : 0;
  var lastWindow = s.windows.length ? s.windows[s.windows.length - 1] : null;

  if (st.state === 'none') {
    if (s.offlineSince !== null && s.offlineSince !== st.dismissed && offlineFor >= cfg['passenger.minOfflineSec'] * 1000 && lastWindow !== 'still') {
      st.state = 'maybe';
      st.since = s.offlineSince;
      events.push({ type: 'maybe' });
    }
  }
  if (st.state === 'maybe') {
    if (s.offlineSince === null) {
      st.state = 'none';
      st.since = null;
      s.windows = [];
      s.descentHpa = 0;
      s.ssid = null;
      events.push({ type: 'none' });
    } else {
      var sure = trainCount(s.windows) >= 2 || s.descentHpa >= cfg['passenger.descentHpa'] || !!s.ssid;
      if (offlineFor >= cfg['passenger.confirmMin'] * MIN && sure && !s.walking) {
        st.state = 'trip';
        st.since = s.offlineSince;
        st.manual = false;
        st.wasOffline = true;
        events.push({ type: 'trip' });
      } else if (offlineFor > cfg['passenger.maybeMaxMin'] * MIN) {
        // Not confirmed in `maybeMaxMin` (a sign holds, but the owner walks on): not a trip.
        st.state = 'none';
        st.since = null;
        st.dismissed = s.offlineSince;
        events.push({ type: 'none' });
      }
    }
  }
  if (st.state === 'trip') {
    var online = s.onlineSince !== null && now - s.onlineSince >= cfg['passenger.endOnlineMin'] * MIN;
    if (st.manual) {
      if (now - st.since >= cfg['passenger.manualMaxMin'] * MIN) endTrip(st, st.since + cfg['passenger.manualMaxMin'] * MIN, cfg, events);
      else if (online && st.wasOffline) endTrip(st, s.lastOffline || now, cfg, events);
    } else if (online) {
      endTrip(st, s.lastOffline || s.onlineSince, cfg, events);
    } else if (s.stillSince !== null && now - s.stillSince >= cfg['passenger.stillEndMin'] * MIN) {
      endTrip(st, s.stillSince, cfg, events);
    }
  }
  return events;
}

/** The multiplier pieces of the trips within [from, to]: [{from, to, mult, kind: 'passenger'}]. */
function segments(st, cfg, from, to, now) {
  var mult = cfg['passenger.mult'];
  var out = [];
  (st.trips || []).forEach(function (t) {
    if (t.end > from && t.start < to) out.push({ from: Math.max(t.start, from), to: Math.min(t.end, to), mult: mult, kind: 'passenger' });
  });
  if (st.state === 'trip' && st.since !== null) {
    var end = Math.min(to, now);
    if (end > from && st.since < to) out.push({ from: Math.max(st.since, from), to: end, mult: mult, kind: 'passenger' });
  }
  return out;
}

function minutesText(ms) { return Math.max(0, Math.round(ms / MIN)) + ' мин'; }

/** The plate: «Поездка: нет», «Похоже, вы едете…», «В пути 14 мин, доход ×2». */
function text(st, cfg, now, multText) {
  if (st.state === 'trip') return 'В пути ' + minutesText(now - st.since) + ', доход ' + multText;
  if (st.state === 'maybe') return 'Похоже, вы едете…';
  return 'Поездка: нет';
}

/** What the sheet lists as signs: «нет сети 16 мин», «трясёт как в вагоне», «спуск на 22 м». */
function signs(st, cfg, now) {
  var s = st.signals;
  var out = [];
  if (st.manual) out.push('включено кнопкой «Я еду»');
  if (s.offlineSince !== null) out.push('нет сети ' + minutesText(now - s.offlineSince));
  else out.push('сеть есть');
  if (trainCount(s.windows) >= 2) out.push('трясёт как в вагоне');
  var last = s.windows.length ? s.windows[s.windows.length - 1] : null;
  if (last === 'still') out.push('телефон лежит неподвижно');
  if (last === 'walk' || s.walking) out.push('похоже, идёте пешком');
  if (s.descentHpa >= cfg['passenger.descentHpa']) out.push('спуск на ' + Math.round(s.descentHpa * M_PER_HPA) + ' м');
  if (s.ssid) out.push('видна сеть «' + s.ssid + '»');
  return out;
}

exports.blank = blank;
exports.classify = classify;
exports.descent = descent;
exports.step = step;
exports.pureSegments = segments;
exports.text = text;
exports.signs = signs;

// ---- the phone ---------------------------------------------------------------------------------

function read(hub) {
  var st = hub.readJson(FILE, null) || blank();
  var b = blank();
  for (var k in b) if (st[k] === undefined) st[k] = b[k];
  for (var s in b.signals) if (st.signals[s] === undefined) st.signals[s] = b.signals[s];
  return st;
}

function sensors() { return require('./sensors.js'); }

/** Applies an event to the stored state, then what follows from it (chests, the base, achievements). */
function apply(hub, cfg, ev) {
  var events = [];
  var st = hub.update(FILE, blank(), function (value) {
    var fresh = read({ readJson: function () { return value; } });
    events = step(fresh, ev, cfg);
    return fresh;
  });
  events.forEach(function (e) {
    if (e.type === 'end') {
      try {
        finish(hub, cfg, e.trip);
      } catch (error) {
        console.error('games: пассажир: конец поездки: ' + error);
      }
    }
  });
  if (events.length) hub.emit('games.links', { passenger: st.state });
  return st;
}

function eligible(hub, cfg) {
  return hub.games().filter(function (g) {
    var p = g.links && g.links.passenger;
    if (!p) return false;
    return hub.configOf(g.id)['links.passenger'] !== false;
  });
}

/** The game that gets the chest: the one played most during the trip, else the last opened. */
function chestGame(hub, list, trip) {
  var withChest = list.filter(function (g) { return g.links.passenger.chest !== false; });
  if (!withChest.length) return null;
  var ids = withChest.map(function (g) { return g.id; });
  var played = {};
  hub.plays().forEach(function (p) {
    if (ids.indexOf(p.game) < 0) return;
    var overlap = Math.min(p.to, trip.end) - Math.max(p.from, trip.start);
    if (overlap > 0) played[p.game] = (played[p.game] || 0) + overlap;
  });
  var best = null;
  for (var id in played) if (!best || played[id] > played[best]) best = id;
  if (best) return best;
  var opened = (hub.readJson('data/games/catalog.json', {}).opened) || {};
  ids.sort(function (a, b) { return (opened[b] || 0) - (opened[a] || 0); });
  return ids[0];
}

var LIFE = [
  { id: 'hundred', title: 'Сто поездок', text: 'Сто поездок, которые заметил «Пассажир»', test: function (st) { return st.total >= 100; } },
  { id: 'offline-2h', title: 'Без сети два часа', text: 'Поездка без сети дольше двух часов', test: function (st, trip) { return trip.min >= 120 && trip.signals.indexOf('offline') >= 0; } },
  { id: 'ring', title: 'Кольцевая', text: 'Поездка дольше часа', test: function (st, trip) { return trip.min > 60; } }
];

function finish(hub, cfg, trip) {
  var list = eligible(hub, cfg);
  var day = hub.Core.dayKey(trip.end);
  var chestTo = null;
  if (trip.min >= cfg['passenger.chestMin']) {
    hub.update(FILE, blank(), function (st) {
      st.chestsToday = st.chestsToday && st.chestsToday.date === day ? st.chestsToday : { date: day, count: 0 };
      if (st.chestsToday.count < cfg['passenger.chestsPerDay']) {
        chestTo = chestGame(hub, list, trip);
        if (chestTo) {
          st.chestsToday.count++;
          var last = st.trips[st.trips.length - 1];
          if (last && last.start === trip.start) last.chest = chestTo;
        }
      }
    });
  }
  if (chestTo) {
    hub.inboxAdd(chestTo, { id: 'chest-' + trip.start, at: trip.end, from: 'passenger', op: 'chest', reason: 'Сундук за поездку ' + trip.min + ' мин' });
  }
  list.forEach(function (g) {
    hub.inboxAdd(g.id, { id: 'trip-' + trip.start, at: trip.end, from: 'passenger', op: 'stat', stat: 'trips', amount: 1, reason: 'Поездка ' + trip.min + ' мин' });
  });
  var base = hub.base();
  if (base) {
    try {
      base.add({ kind: 'trip', at: trip.start, title: 'Поездка', amount: trip.min, unit: 'min', source: 'games', data: { signals: trip.signals, end: trip.end } });
    } catch (error) {
      console.warn('games: пассажир: база: ' + error);
    }
  }
  var share = hub.config()['achievements.share'];
  var fresh = [];
  hub.update(FILE, blank(), function (st) {
    st.achieved = st.achieved || {};
    LIFE.forEach(function (a) {
      if (!st.achieved[a.id] && a.test(st, trip)) {
        st.achieved[a.id] = trip.end;
        fresh.push(a);
      }
    });
  });
  if (fresh.length && share !== 'none') {
    hub.deliver(fresh.map(function (a) {
      return { key: 'games/passenger/' + a.id, source: 'games', category: 'Игротека', title: a.title, text: a.text, at: trip.end, hidden: false, icon: 'modules/games/icon.svg' };
    }));
  }
  list.forEach(function (g) { hub.log(g.id, 'Поездка ' + trip.min + ' мин (' + trip.signals.join(', ') + ')' + (chestTo === g.id ? ', сундук' : '')); });
}

function pressureNow(hub, cfg) {
  var p = sensors().pressure();
  if (p === null) return;
  var t = hub.now();
  hub.update(FILE, blank(), function (st) {
    st.pressure = (st.pressure || []).filter(function (x) { return x[0] > t - 60 * MIN; });
    st.pressure.push([t, Math.round(p * 100) / 100]);
  });
}

function counterNow(hub) {
  var n = sensors().stepCounter();
  if (n === null) return null;
  var t = hub.now();
  var walked = null;
  hub.update(FILE, blank(), function (st) {
    st.counter = (st.counter || []).filter(function (x) { return x[0] > t - 10 * MIN; });
    var old = null;
    st.counter.forEach(function (x) { if (x[0] <= t - 3 * MIN + 30000 && (!old || x[0] > old[0])) old = x; });
    st.counter.push([t, n]);
    if (old && n >= old[1]) walked = n - old[1];
  });
  return walked;
}

var looping = false;

/** While there is no network: a window of the accelerometer once a minute, the barometer, Wi-Fi. */
function loop(hub) {
  if (looping) return;
  looping = true;
  spawn(function () {
    try {
      for (var guard = 0; guard < 24 * 60; guard++) {
        var cfg = hub.config();
        if (!cfg['passenger.on']) break;
        var online = sensors().online();
        var ev = { now: hub.now() };
        if (online !== null) ev.online = online;
        var st = read(hub);
        var active = st.state !== 'none' || online === false;
        if (!active) {
          apply(hub, cfg, ev);
          break;
        }
        if (cfg['passenger.sensors']) {
          var w = sensors().window(cfg['passenger.sampleSec']);
          if (w) ev.window = classify(w, cfg);
          pressureNow(hub, cfg);
          var walked = counterNow(hub);
          if (walked !== null) ev.walking = walked >= cfg['passenger.walkSteps'];
          ev.descent = descent(read(hub).pressure, read(hub).signals.offlineSince, cfg);
        }
        var names = String(cfg['passenger.ssids'] || '').split(',').map(function (x) { return x.trim(); }).filter(Boolean);
        if (names.length) {
          var seen = sensors().wifiNames();
          for (var i = 0; i < names.length; i++) if (seen.indexOf(names[i]) >= 0) ev.ssid = names[i];
        }
        ev.now = hub.now();
        apply(hub, cfg, ev);
        sleep(Math.max(15000, cfg['passenger.sampleEveryMin'] * MIN - cfg['passenger.sampleSec'] * 1000));
      }
    } catch (error) {
      console.error('games: пассажир: ' + error);
    } finally {
      looping = false;
    }
  });
}

exports.start = function (hub, cfg) {
  on('network', function (e) {
    var c = hub.config();
    if (!c['passenger.on']) return;
    var online = !!(e && e.online);
    apply(hub, c, { now: hub.now(), online: online });
    if (!online) {
      spawn(function () {
        try {
          if (c['passenger.sensors']) pressureNow(hub, c);
        } catch (ignored) { /* no barometer */ }
      });
    }
    loop(hub);
  }, 'games.passenger.network');
  on('screen', function (e) {
    var c = hub.config();
    if (!c['passenger.on'] || !c['passenger.sensors']) return;
    if (e && (e.state === 'on' || e.state === 'unlocked')) {
      spawn(function () {
        try {
          pressureNow(hub, c);
        } catch (ignored) { /* no barometer */ }
      });
      if (read(hub).state !== 'none' || sensors().online() === false) loop(hub);
    }
  }, 'games.passenger.screen');
  // A safety net when the loop died with the process: the state moves on at least this often.
  job('games:passenger', { every: 15 }, function () {
    var c = hub.config();
    if (!c['passenger.on']) return;
    var st = read(hub);
    var online = sensors().online();
    if (st.state !== 'none' || online === false) loop(hub);
  });
  if (cfg['passenger.on']) {
    var online = sensors().online();
    if (online === false || read(hub).state !== 'none') loop(hub);
  }
};

exports.segments = function (hub, cfg, from, to) {
  if (!cfg['passenger.on']) return [];
  return segments(read(hub), cfg, from, to, hub.now());
};

exports.status = function (hub, cfg) {
  var st = read(hub);
  var t = hub.now();
  return {
    on: true, state: st.state, since: st.since, manual: !!st.manual, mult: cfg['passenger.mult'],
    min: st.since ? Math.round((t - st.since) / MIN) : 0, text: text(st, cfg, t, hub.Fmt.mult(cfg['passenger.mult']))
  };
};

exports.sheet = function (hub, cfg) {
  var st = read(hub);
  var t = hub.now();
  return {
    title: 'Пассажир', state: st.state, text: text(st, cfg, t, hub.Fmt.mult(cfg['passenger.mult'])), signs: signs(st, cfg, t),
    trips: st.trips.slice(-10).reverse(), total: st.total || 0, chestsToday: st.chestsToday && st.chestsToday.date === hub.Core.dayKey(t) ? st.chestsToday.count : 0,
    chestsPerDay: cfg['passenger.chestsPerDay'], chestMin: cfg['passenger.chestMin'], mult: cfg['passenger.mult'],
    barometer: sensors().has('pressure')
  };
};

exports.manual = function (hub, cfg, on) {
  var st = apply(hub, cfg, { now: hub.now(), manual: on ? 'on' : 'off' });
  if (on) loop(hub);
  return { state: st.state, since: st.since };
};

// Screen time and what apps did, from the phone's own accounting (UsageStatsManager): what was
// in front and how long, how often it was opened, when the screen was in use.
//   var usage = require('lib/usage');
//   usage.access()                  → true when the phone lets the app read it
//   usage.events(since, until)      → [{ package, app, type, time }]
//   usage.daily(since, until)       → [{ package, app, ms, opens }], the most used first
//   usage.sessions(since, until)    → [{ from, to, packages }]: the screen in use without a break
// Times are milliseconds since 1970, as Date.now() gives them; `until` is now when left out.
// daily and sessions also take the events a module already has as a third argument.
// Access is "usage access" in the system settings (Shizuku gives it too, see Settings).

// The kinds of events worth telling about; the phone gives "notification" not everywhere.
var TYPES = { 1: 'resumed', 2: 'paused', 12: 'notification', 15: 'screen-on', 16: 'screen-off', 18: 'unlock', 19: 'fg-start', 20: 'fg-stop' };

// An app already in front when the period starts came there before it: events are read this
// much earlier, and what they show is cut to the period.
var LOOKBACK = 6 * 3600 * 1000;

var labels = {};

/** The name of an app as the owner sees it. */
function label(name) {
  if (!(name in labels)) {
    var manager = context.getPackageManager();
    try {
      labels[name] = String(manager.getApplicationLabel(manager.getApplicationInfo(name, 0)));
    } catch (error) {
      labels[name] = name;
    }
  }
  return labels[name];
}

exports.access = function () {
  var mode = context.getSystemService('appops').unsafeCheckOpNoThrow('android:get_usage_stats', android.os.Process.myUid(), context.getPackageName());
  if (mode === android.app.AppOpsManager.MODE_DEFAULT) return context.checkSelfPermission('android.permission.PACKAGE_USAGE_STATS') === 0;
  return mode === android.app.AppOpsManager.MODE_ALLOWED;
};

exports.events = function (since, until) {
  if (!exports.access()) {
    throw new Error('Нет доступа к статистике использования: владелец даёт его в системных настройках («Доступ к истории использования» → Assi)');
  }
  var list = context.getSystemService('usagestats').queryEvents(since, until || Date.now());
  var event = new android.app.usage.UsageEvents.Event();
  var out = [];
  while (list.hasNextEvent()) {
    list.getNextEvent(event);
    var type = TYPES[event.getEventType()];
    if (!type) continue;
    var name = String(event.getPackageName());
    out.push({ package: name, app: label(name), type: type, time: Number(event.getTimeStamp()) });
  }
  return out;
};

/**
 * The stretches each app spent in front, one after another: an app is in front from its first
 * activity resumed until another app's is, or the screen goes off. Moving between screens of
 * the same app does not break its stretch.
 */
function stretches(events, until) {
  var out = [];
  var current = null;
  function close(at) {
    if (current) out.push({ package: current.package, from: current.from, to: current.paused === null ? at : Math.min(current.paused, at) });
    current = null;
  }
  events.forEach(function (event) {
    if (event.type === 'resumed') {
      if (current && current.package === event.package) {
        current.paused = null;
        return;
      }
      close(event.time);
      current = { package: event.package, from: event.time, paused: null };
    } else if (event.type === 'paused') {
      if (current && current.package === event.package && current.paused === null) current.paused = event.time;
    } else if (event.type === 'screen-off') {
      close(event.time);
    }
  });
  close(Math.min(until, Date.now()));
  return out;
}

exports.daily = function (since, until, events) {
  until = until || Date.now();
  var apps = {};
  stretches(events || exports.events(since - LOOKBACK, until), until).forEach(function (stretch) {
    var ms = Math.min(stretch.to, until) - Math.max(stretch.from, since);
    if (ms <= 0) return;
    var app = apps[stretch.package] = apps[stretch.package] || { package: stretch.package, app: label(stretch.package), ms: 0, opens: 0 };
    app.ms += ms;
    // Opened within the period; one that was already open when it began is not counted.
    if (stretch.from >= since) app.opens++;
  });
  return Object.keys(apps).map(function (name) { return apps[name]; }).sort(function (a, b) { return b.ms - a.ms; });
};

exports.sessions = function (since, until, events) {
  until = until || Date.now();
  var out = [];
  var open = null;
  function end(at) {
    if (!open) return;
    var from = Math.max(open.from, since);
    var to = Math.min(at, until);
    if (to > from) out.push({ from: from, to: to, packages: open.packages });
    open = null;
  }
  (events || exports.events(since - LOOKBACK, until)).forEach(function (event) {
    if (event.type === 'screen-on') {
      end(event.time);
      open = { from: event.time, packages: [] };
    } else if (event.type === 'screen-off') {
      end(event.time);
    } else if (event.type === 'resumed') {
      // The screen was on before the events begin.
      if (!open) open = { from: event.time, packages: [] };
      if (open.packages.indexOf(event.package) < 0) open.packages.push(event.package);
    }
  });
  end(Math.min(until, Date.now()));
  return out;
};

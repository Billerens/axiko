// Routines: things an agent does by itself — on a schedule, or when something happens on the
// phone. The owner describes one in plain words; it is kept as
//
//   { id, title, when, do, agent, enabled, last: { at, ok, text } }
//
//   when: { kind: 'daily', at: '08:00', days: [1, 2, 3, 4, 5] }   days: 1 = Monday … 7 = Sunday; none = every day
//         { kind: 'every', minutes: 60 }
//         { kind: 'notification', app: 'bank', contains: 'списание' }   a notification whose app or text has these words
//         { kind: 'event', name: 'power' | 'headset' | 'network' | 'screen', state: 'on' }
//   do:   what to do, as one would ask the agent in the chat
//
// When a routine fires, the agent gets `do` together with what set it off (the notification, the
// time), works in a conversation of its own, and its answer goes to the owner as a notification.
var axiko = require('lib/axiko');
var ai = require('lib/ai');

var PATH = 'data/routines.json';
var QUIET_MS = 30000;
var lastFired = {};
var listening = {};

function load() { return axiko.files.readJson(PATH, []); }
function save(list) { axiko.files.writeJson(PATH, list); emit('routines', null); }

exports.list = load;

function describe(when) {
  if (!when) return '';
  if (when.kind === 'daily') return 'каждый день в ' + when.at + (when.days && when.days.length && when.days.length < 7 ? ' (дни: ' + when.days.join(', ') + ')' : '');
  if (when.kind === 'every') return 'каждые ' + when.minutes + ' мин';
  if (when.kind === 'notification') return 'уведомление' + (when.app ? ' от «' + when.app + '»' : '') + (when.contains ? ' со словами «' + when.contains + '»' : '');
  if (when.kind === 'event') return 'событие ' + when.name + (when.state ? ': ' + when.state : '');
  return JSON.stringify(when);
}
exports.describe = describe;

/** Runs the routine now; cause says what set it off. Returns the agent's answer. */
function run(id, cause) {
  var routine = load().filter(function (item) { return item.id === id; })[0];
  if (!routine) throw new Error('Нет рутины ' + id);
  var task = 'Это рутина «' + routine.title + '»: вы выполняете её сами, владелец сейчас не в чате и ответить на вопросы не может.\n' +
    'Что её запустило: ' + (cause || 'расписание') + '.\n\nЧто сделать: ' + routine.do + '\n\n' +
    'Ваш ответ уйдёт владельцу уведомлением: напишите его коротко и по делу. Если сообщать нечего — ответьте одним словом «ничего».';
  var outcome = { at: Date.now(), ok: true, text: '' };
  try {
    outcome.text = String(ai.run(routine.agent || 'axiko', task) || '').trim();
  } catch (error) {
    outcome.ok = false;
    outcome.text = String(error && error.message ? error.message : error);
  }
  var list = load();
  list.forEach(function (item) { if (item.id === id) item.last = { at: outcome.at, ok: outcome.ok, text: outcome.text.slice(0, 600) }; });
  save(list);
  if (!outcome.ok) axiko.notify({ title: 'Рутина «' + routine.title + '» не выполнена', text: outcome.text, open: 'ui/routines/index.html' });
  else if (outcome.text && !/^ничего[.!]?$/i.test(outcome.text)) axiko.notify({ title: routine.title, text: outcome.text, open: 'ui/routines/index.html' });
  return outcome.text;
}
exports.run = run;

function fire(routine, cause) {
  var now = Date.now();
  if (now - (lastFired[routine.id] || 0) < QUIET_MS) return;
  lastFired[routine.id] = now;
  spawn(function () {
    try { run(routine.id, cause); } catch (error) { console.error('routine ' + routine.id + ': ' + error); }
  });
}

function has(text, words) {
  return !words || String(text || '').toLowerCase().indexOf(String(words).toLowerCase()) >= 0;
}

function today() {
  var day = new Date().getDay();
  return day === 0 ? 7 : day;
}

/** Arms what the routines need: timers for the scheduled ones, listeners for the rest. */
function arm() {
  var list = load();
  var wanted = {};
  list.forEach(function (routine) {
    var when = routine.when || {};
    var key = 'routine:' + routine.id;
    if (!routine.enabled || (when.kind !== 'daily' && when.kind !== 'every')) return;
    wanted[key] = true;
    job(key, when.kind === 'daily' ? { at: when.at } : { every: Math.max(5, Number(when.minutes) || 60) }, function () {
      var now = load().filter(function (item) { return item.id === routine.id; })[0];
      if (!now || !now.enabled) return;
      if (now.when.kind === 'daily' && now.when.days && now.when.days.length && now.when.days.indexOf(today()) < 0) return;
      fire(now, 'расписание, ' + describe(now.when));
    });
  });
  Object.keys(native('job.list')).forEach(function (key) {
    if (key.indexOf('routine:') === 0 && !wanted[key]) unjob(key);
  });

  if (!listening.notification) {
    listening.notification = true;
    on('notification', function (notice) {
      // The app's own notifications set nothing off: a routine must not answer itself.
      if (String(notice.package || '').indexOf(String(context.getPackageName())) === 0) return;
      load().forEach(function (routine) {
        var when = routine.when || {};
        if (!routine.enabled || when.kind !== 'notification') return;
        var from = (notice.app || '') + ' ' + (notice.package || '');
        var said = (notice.title || '') + ' ' + (notice.text || '');
        if (has(from, when.app) && has(said, when.contains)) {
          fire(routine, 'уведомление от «' + (notice.app || notice.package || '') + '»: ' + said.trim().slice(0, 800));
        }
      });
    }, 'routines');
  }
  ['power', 'headset', 'network', 'screen'].forEach(function (name) {
    if (listening[name]) return;
    listening[name] = true;
    on(name, function (data) {
      load().forEach(function (routine) {
        var when = routine.when || {};
        if (!routine.enabled || when.kind !== 'event' || when.name !== name) return;
        // One word for every event's state: the screen names its own, the rest are on or off.
        var flag = data.charging !== undefined ? data.charging : data.plugged !== undefined ? data.plugged : data.online;
        var state = data.state !== undefined ? data.state : flag === undefined ? '' : flag ? 'on' : 'off';
        if (when.state && String(state) !== String(when.state)) return;
        fire(routine, 'событие ' + name + ': ' + JSON.stringify(data));
      });
    }, 'routines');
  });
}

exports.start = arm;

/** Adds a routine or changes one (by id). Returns it. */
exports.set = function (routine) {
  if (!routine || !routine.title || !routine.do || !routine.when || !routine.when.kind) throw new Error('У рутины должны быть title, when и do');
  var when = routine.when;
  if (when.kind === 'daily' && !/^\d{1,2}:\d{2}$/.test(String(when.at || ''))) throw new Error('Время — в виде 08:30');
  if (when.kind === 'every' && !(Number(when.minutes) >= 5)) throw new Error('Не чаще, чем раз в 5 минут');
  if (['daily', 'every', 'notification', 'event'].indexOf(when.kind) < 0) throw new Error('when.kind: daily, every, notification или event');
  var list = load();
  var saved = null;
  if (routine.id) list.forEach(function (item) { if (item.id === routine.id) saved = item; });
  if (!saved) {
    saved = { id: routine.id || 'r' + Date.now().toString(36), enabled: true };
    list.push(saved);
  }
  saved.title = routine.title;
  saved.when = when;
  saved.do = routine.do;
  saved.agent = routine.agent || saved.agent || 'axiko';
  if (routine.enabled !== undefined) saved.enabled = Boolean(routine.enabled);
  save(list);
  arm();
  return saved;
};

exports.enable = function (id, on) {
  var list = load();
  list.forEach(function (item) { if (item.id === id) item.enabled = Boolean(on); });
  save(list);
  arm();
};

exports.remove = function (id) {
  save(load().filter(function (item) { return item.id !== id; }));
  arm();
};

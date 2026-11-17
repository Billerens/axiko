// What must hold on a device after every build. Run with scripts\check.ps1 — it sends this file
// to the app's engine and prints what passed. A check that needs something the device does not
// have (a model not downloaded, the mock provider not reachable) is skipped and says why.
//
// Written for the engine (Rhino): no classes, no async, no arrow-heavy tricks.

var P = Packages.app.assi;
var J = Packages.org.json;
var lines = [];
var counts = { pass: 0, fail: 0, skip: 0 };
var only = typeof ONLY === 'string' ? ONLY : '';

function Skip(why) { this.why = why; }
function skip(why) { throw new Skip(why); }
function expect(condition, what) { if (!condition) throw new Error(what); }

function check(name, body) {
  if (only && name.indexOf(only) < 0) return;
  var started = Date.now();
  try {
    var note = body();
    counts.pass++;
    lines.push('PASS  ' + name + (note ? '  — ' + note : '') + '  (' + (Date.now() - started) + ' мс)');
  } catch (error) {
    if (error instanceof Skip) {
      counts.skip++;
      lines.push('SKIP  ' + name + '  — ' + error.why);
    } else {
      counts.fail++;
      lines.push('FAIL  ' + name + '  — ' + (error && error.message ? error.message : error));
    }
  }
}

/** Checks that look at a screen need it lit: a sleeping phone does not run a page's timers. */
function needScreen() {
  if (!context.getSystemService('power').isInteractive()) skip('экран телефона выключен');
}

function text(stream) { return String(new java.lang.String(stream.readAllBytes(), 'UTF-8')); }
function served(path) { return text(P.core.Pages.INSTANCE.serve(path).getData()); }
function target(role) { return P.agent.Models.INSTANCE.resolve(role, true, null); }
function localModels() { return native('local.status').filter(function (m) { return m.installed && !m.embedding; }); }

/** Waits up to ms for what another thread (an event, an alarm, a page) brings about. */
function waitFor(condition, ms) {
  var until = Date.now() + ms;
  while (!condition() && Date.now() < until) sleep(100);
  return condition();
}

/** A notification in the shade by its tag (the id assi.notify gave it), of this app or of [pkg]. */
function shownNotification(tag, pkg) {
  var all = pkg ? P.sense.NoticeService.Companion.getInstance().getActiveNotifications() : context.getSystemService('notification').getActiveNotifications();
  for (var i = 0; i < all.length; i++) {
    if (String(all[i].getTag()) === tag && (!pkg || String(all[i].getPackageName()) === pkg)) return all[i];
  }
  return null;
}

/** The same once it is there: the system shows a posted notification a moment later. */
function postedNotification(tag, pkg) {
  return waitFor(function () { return shownNotification(tag, pkg); }, 3000);
}

/** The error text a call ends with, or '' when it does not fail. */
function failure(fn) {
  try {
    fn();
    return '';
  } catch (error) {
    return String(error && error.message ? error.message : error);
  }
}

// ---- the engine and the workspace ---------------------------------------------------------------

check('engine: Android from JavaScript', function () {
  var name = String(context.getPackageName());
  expect(name === 'app.axiko' || name === 'app.assi', 'нет контекста приложения: ' + name);
  return 'SDK ' + Packages.android.os.Build.VERSION.SDK_INT;
});

check('workspace: factory files', function () {
  var files = require('lib/assi').files;
  ['agent/system.md', 'agent/handbook.md', 'agent/api.md', 'ui/nav.json', 'ui/nav/index.html', 'ui/kit/kit.css',
    'themes/emerald.json', 'ui/settings/index.html'].forEach(function (path) {
    expect(files.exists(path), 'нет файла ' + path);
  });
});

check('engine: a module asked for from two threads at once loads whole for both', function () {
  var files = require('lib/assi').files;
  files.write('modules/zz-race/main.js', 'sleep(400); exports.answer = function () { return 42; };');
  try {
    var seen = [];
    var ask = function () {
      try { seen.push(typeof require('modules/zz-race/main.js').answer); } catch (error) { seen.push('error: ' + error); }
    };
    spawn(ask);
    sleep(100);
    spawn(ask);
    // A slow device needs its time; the answer itself is what is checked.
    var until = Date.now() + 15000;
    while (seen.length < 2 && Date.now() < until) sleep(100);
    expect(seen.length === 2 && seen[0] === 'function' && seen[1] === 'function', 'кто-то получил недогруженный модуль: ' + seen.join(', '));
  } finally {
    files.remove('modules/zz-race');
  }
});

// ---- the prompt -----------------------------------------------------------------------------------

check('prompt: template and parts', function () {
  var name = String(P.agent.Agent.INSTANCE.getName());
  var spec = P.agent.Agent.INSTANCE.spec('assi');
  var chat = P.agent.Agent.INSTANCE.chat('assi', '');
  var full = String(chat.systemPrompt(spec, false));
  var compact = String(chat.systemPrompt(spec, true));
  expect(full.indexOf('{{name}}') < 0, 'в промпте остался {{name}}');
  expect(full.indexOf('Вы — ' + name) >= 0, 'в промпте нет имени ' + name);
  expect(full.indexOf('# Руководство по приложению') > 0, 'нет руководства');
  expect(full.indexOf('Никаких эмодзи') < 0, 'остался запрет эмодзи');
  expect(compact.indexOf('# Руководство по приложению') < 0, 'в коротком промпте есть руководство');
  expect(compact.length < full.length / 3, 'короткий промпт не короткий');
  return 'полный ≈ ' + Math.round(full.length / 3) + ' ток., для модели на телефоне ≈ ' + Math.round(compact.length / 3);
});

check('prompt: the name is the owner\'s', function () {
  var before = native('wake.status').name;
  try {
    native('wake.name', 'Марфа');
    var prompt = String(native('agent.prompt', 'assi'));
    expect(prompt.indexOf('Вы — Марфа') >= 0, 'имя не подставилось');
    expect(native('agent.list')[0].title === 'Марфа', 'имя не дошло до списка агентов');
  } finally {
    native('wake.name', before);
  }
});

// ---- the look ---------------------------------------------------------------------------------------

check('theme: rides on the kit stylesheet', function () {
  var before = native('theme.list').filter(function (t) { return t.active; }).map(function (t) { return t.id; })[0] || '';
  try {
    native('theme.save', 'check-theme', { name: 'Проверка', tokens: { accent: '#123456' }, css: '' });
    native('theme.set', 'check-theme');
    expect(served('ui/kit/kit.css').indexOf('--accent: #123456') > 0, 'тема не попала в стили');
    native('theme.set', '');
    expect(served('ui/kit/kit.css').indexOf('#123456') < 0, 'исходный вид не вернулся');
  } finally {
    native('theme.remove', 'check-theme');
    native('theme.set', before);
  }
  return native('theme.list').length + ' тем';
});

check('menu: items', function () {
  var items = JSON.parse(served('ui/nav.json')).items;
  expect(items.length > 0, 'меню пустое');
  var files = require('lib/assi').files;
  items.forEach(function (item) {
    expect(item.title && (item.open || item.chat), 'пункт без названия или цели');
    if (item.open) expect(files.exists(item.open.replace(/[?#].*$/, '')), 'нет экрана ' + item.open);
  });
  return items.map(function (item) { return item.title; }).join(', ');
});

check('ui_eval: waits for a promise and for the screen', function () {
  needScreen();
  var chat = P.agent.Agent.INSTANCE.chat('assi', '');
  var run = function (code) { return String(P.agent.Tools.INSTANCE.run('ui_eval', JSON.stringify({ code: code }), chat, new java.util.ArrayList()).getText()); };
  expect(run('1 + 2') === '3', 'простое выражение: ' + run('1 + 2'));
  var later = run('new Promise(function (resolve) { setTimeout(function () { resolve("позже"); }, 300); })');
  expect(later === '"позже"', 'результат Promise не дождались: ' + later);
  run('window.__probe = 0; setTimeout(function () { window.__probe = 7; }, 150); "нажато"');
  expect(run('window.__probe') === '7', 'экран не успел отработать нажатие до следующего вызова');
  expect(run('throw new Error("сломано")').indexOf('сломано') >= 0, 'ошибка страницы не дошла');
});

check('own screen: a check in one step, at a width asked for, the owner\'s screen untouched', function () {
  var chat = P.agent.Agent.INSTANCE.chat('assi', '');
  var was = String(native('ui.where').path);
  var pictures = new java.util.ArrayList();
  var report = String(P.agent.Tools.INSTANCE.run('ui_check', JSON.stringify({
    path: 'ui/settings/index.html', width: 344, code: 'innerWidth + " " + document.visibilityState + " " + Boolean(window.assiProbe)',
  }), chat, pictures).getText());
  expect(report.indexOf('Экран открыт, ширина 344.') === 0, 'экран не открылся: ' + report);
  expect(report.indexOf('Код: "344 visible true"') > 0, 'страница не та, что просили: ' + report);
  expect(pictures.size() === 1, 'снимка нет: ' + report);
  var bytes = android.util.Base64.decode(String(pictures.get(0)), 0);
  var shot = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
  expect(shot.getWidth() > 300 && shot.getHeight() > shot.getWidth(), 'снимок не похож на экран: ' + shot.getWidth() + '×' + shot.getHeight());
  // One part of the screen is a smaller picture.
  pictures.clear();
  P.agent.Tools.INSTANCE.run('ui_screenshot', JSON.stringify({ selector: 'h1' }), chat, pictures);
  expect(pictures.size() === 1, 'части экрана на снимке нет');
  var part = android.util.Base64.decode(String(pictures.get(0)), 0);
  var cut = android.graphics.BitmapFactory.decodeByteArray(part, 0, part.length);
  expect(cut.getHeight() < shot.getHeight() / 3, 'снята не часть, а весь экран: ' + cut.getWidth() + '×' + cut.getHeight());
  // Opened again without a width, the screen is as wide as the owner's.
  var usual = String(P.agent.Tools.INSTANCE.run('ui_check', JSON.stringify({ path: 'ui/home/index.html', shot: 'none', code: 'innerWidth' }), chat, pictures).getText());
  expect(usual.indexOf('ширина 344') < 0, 'ширина осталась от прошлой проверки: ' + usual);
  expect(pictures.size() === 1, 'снимок сделан, хотя его не просили');
  expect(String(native('ui.where').path) === was, 'экран владельца ушёл с места: ' + native('ui.where').path);
  return shot.getWidth() + '×' + shot.getHeight() + ', часть ' + cut.getWidth() + '×' + cut.getHeight() + '; ' + usual.split('\n')[0];
});

check('screenshot: a WebGL scene is in the picture the agent gets', function () {
  needScreen();
  // A canvas over the whole window, drawn once the way three.js draws (the backing store is
  // devicePixelRatio times the CSS size): red on the left, blue on the right.
  var html = '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">' +
    '<style>html,body{margin:0;height:100%;background:#222;overflow:hidden}canvas{display:block;width:100vw;height:100vh}</style>' +
    '<canvas id="c"></canvas><script>var c=document.getElementById("c"),r=window.devicePixelRatio||1;c.width=innerWidth*r;c.height=innerHeight*r;' +
    'var gl=c.getContext("webgl");window.hasGl=Boolean(gl);if(gl){gl.enable(gl.SCISSOR_TEST);' +
    'gl.scissor(0,0,c.width/2,c.height);gl.clearColor(1,0,0,1);gl.clear(gl.COLOR_BUFFER_BIT);' +
    'gl.scissor(c.width/2,0,c.width/2,c.height);gl.clearColor(0,0,1,1);gl.clear(gl.COLOR_BUFFER_BIT);}<\/script>';
  var dir = new java.io.File(String(host.path('modules/check-gl/ui')));
  try {
    dir.mkdirs();
    var out = new java.io.FileOutputStream(new java.io.File(dir, 'index.html'));
    out.write(new java.lang.String(html).getBytes('UTF-8'));
    out.close();
    native('ui.openWait', 'modules/check-gl/ui/index.html', 1200);
    if (String(native('ui.eval', 'window.hasGl', 0)) !== 'true') skip('на этом устройстве нет WebGL');
    var bytes = android.util.Base64.decode(String(native('ui.shot')), 0);
    var shot = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    var at = function (x, y) {
      var colour = shot.getPixel(Math.floor(shot.getWidth() * x), Math.floor(shot.getHeight() * y));
      return [(colour >> 16) & 255, (colour >> 8) & 255, colour & 255];
    };
    var left = at(0.25, 0.5);
    var right = at(0.75, 0.5);
    var corner = at(0.9, 0.9);
    expect(left[0] > 200 && left[2] < 60, 'слева на снимке не красное: ' + left.join(','));
    expect(right[2] > 200 && right[0] < 60, 'справа на снимке не синее: ' + right.join(','));
    expect(corner[2] > 200 && corner[0] < 60, 'сцена на снимке меньше экрана: в правом нижнем углу ' + corner.join(','));
    return shot.getWidth() + '×' + shot.getHeight() + ', сцена во весь снимок';
  } finally {
    new java.io.File(dir, 'index.html')['delete']();
    dir['delete']();
    dir.getParentFile()['delete']();
  }
});

check('stage: a screen gets the whole display and gives it back', function () {
  needScreen();
  var activity = P.MainActivity.Companion.getCurrent();
  var page = activity.getPage();
  var files = require('lib/assi').files;
  files.write('data/check-stage.html', '<!doctype html><meta name="viewport" content="width=device-width"><body style="margin:0;background:#123"><script>assi.stage(true)</script>');
  try {
    page.openAndWait('data/check-stage.html', 8000);
    sleep(900);
    var staged = page.getWeb().getHeight();
    page.openAndWait('ui/home/index.html', 8000);
    sleep(900);
    var usual = page.getWeb().getHeight();
    expect(staged > usual, 'экран не получил весь дисплей: ' + staged + ' против ' + usual);
    return 'высота ' + usual + ' → ' + staged + ' точек и обратно';
  } finally {
    files.remove('data/check-stage.html');
  }
});

check('language: the interface follows the setting', function () {
  var before = native('lang.status');
  expect(before.languages.some(function (item) { return item.code === 'en'; }), 'нет английского словаря');
  try {
    native('lang.set', 'en');
    expect(served('ui/kit/kit.js').indexOf('window.__lang="en"') > 0, 'словарь не доехал до экранов');
    expect(String(P.core.I18n.INSTANCE.t('Безопасный режим')) === 'Safe mode', 'оболочка не перевела своё');
    expect(String(P.core.I18n.INSTANCE.t('Перед удалением модуля notes')).indexOf('notes') > 0, 'шаблон с подстановкой не сработал');
    expect(String(native('agent.prompt', 'assi')).indexOf('(en)') > 0, 'агент не знает язык интерфейса');
    native('lang.set', 'ru');
    expect(served('ui/kit/kit.js').indexOf('window.__i18n=null') > 0, 'русский не вернулся');
  } finally {
    native('lang.set', before.setting);
  }
  return before.languages.map(function (item) { return item.code; }).join(', ');
});

check('ask_owner: a form is shown, the answer comes back to the agent', function () {
  var tools = JSON.parse(String(P.agent.Tools.INSTANCE.schemas(null, false, true, false)));
  expect(tools.some(function (tool) { return tool.function.name === 'ask_owner'; }), 'инструмента нет в списке');
  var chat = P.agent.Agent.INSTANCE.chat('assi', '');
  var got = [null];
  spawn(function () {
    got[0] = String(chat.ask(new J.JSONObject(JSON.stringify({ title: 'Проверка', questions: [{ question: 'Цвет?', options: ['Красный', 'Синий'], recommended: 'Синий' }] }))));
  });
  sleep(700);
  var shown = JSON.parse(String(chat.display()));
  var form = shown[shown.length - 1];
  expect(form && form.type === 'ask', 'форма не дошла до чата');
  expect(chat.answer(form.id, 'Цвет?\n— Синий'), 'ответ не принят');
  sleep(900);
  expect(got[0] && got[0].indexOf('Синий') >= 0, 'ответ не вернулся агенту: ' + got[0]);
  expect(!chat.answer(form.id, 'ещё раз'), 'форма принимает ответ дважды');
});

check('plan: shown to the owner, approved, steps marked', function () {
  var chat = P.agent.Agent.INSTANCE.chat('assi', '');
  // The check draws its plan in the owner's current chat: whatever plan was there is put back.
  var before = chat.plan();
  before = before ? new J.JSONObject(String(before)) : null;
  try {
    planCheck(chat);
  } finally {
    chat.planRestore(before);
  }
});

function planCheck(chat) {
  var got = [null];
  spawn(function () { got[0] = String(chat.planPropose(new J.JSONObject(JSON.stringify({ title: 'Проверка плана', steps: ['Раз', 'Два', 'Три'] })))); });
  sleep(700);
  var shown = JSON.parse(String(chat.display()));
  var card = shown[shown.length - 1];
  expect(card && card.type === 'plan' && card.plan.steps.length === 3, 'план не дошёл до чата');
  expect(chat.answer(card.id, ''), 'решение не принято');
  sleep(900);
  expect(got[0] && got[0].indexOf('План принят') === 0, 'агент не узнал, что план принят: ' + got[0]);
  // One mark per step is enough: the next step becomes the one in hand by itself.
  chat.planStep(new J.JSONObject('{"step": 1, "status": "done"}'));
  var plan = native('agent.plan', 'assi');
  expect(plan.state === 'approved' && plan.steps[0].status === 'done' && plan.steps[1].status === 'doing', 'после сделанного шага следующий не стал текущим');
  // A model that forgot a mark and went on: what came before the marked step is done.
  chat.planStep(new J.JSONObject('{"step": 3, "status": "done"}'));
  plan = native('agent.plan', 'assi');
  expect(plan.steps[1].status === 'done' && plan.steps[2].status === 'done', 'шаги до отмеченного остались открытыми');
}

check('tasks: what runs and what ran', function () {
  var T = P.agent.Tasks.INSTANCE;
  var task = T.begin('assi', 'check-thread', 'Проверочная задача', null);
  var part = T.begin('assi', '', 'Её часть', task);
  T.progress(task, 2, 'fs_write x');
  var list = JSON.parse(String(T.list()));
  var mine = list.filter(function (item) { return item.key === String(task.getKey()); })[0];
  expect(mine && mine.state === 'run' && mine.step === 2, 'идущая задача не видна');
  expect(list.some(function (item) { return item.parent === String(task.getKey()); }), 'часть задачи не видна под ней');
  T.end(part, 'done', 'готово');
  T.end(task, 'done', 'Сделано');
  list = JSON.parse(String(T.list()));
  mine = list.filter(function (item) { return item.key === String(task.getKey()); })[0];
  expect(mine && mine.state === 'done' && mine.result === 'Сделано', 'законченная задача не осталась в недавних');
  T.forget(String(task.getKey()));
});

check('chat model: one chat runs on a model and an effort of its own', function () {
  var roles = native('models.roles');
  if (!roles.main || !roles.main.model) skip('основная модель не выбрана');
  var id = 'check' + Date.now().toString(36);
  try {
    var usual = native('agent.model', 'assi', id);
    expect(!usual.own && usual.model === roles.main.model, 'чат без своего выбора не на основной модели');
    var harder = native('agent.setModel', 'assi', id, { effort: 'low' });
    expect(harder.own && harder.model === usual.model && harder.effort === 'low', 'усилие чата не применилось: ' + JSON.stringify(harder));
    var other = native('agent.setModel', 'assi', id, { provider: usual.provider, model: 'another-model', vision: false });
    expect(other.model === 'another-model' && other.vision === false && other.effort === '', 'другая модель чата не применилась: ' + JSON.stringify(other));
    var kept = JSON.parse(text(new java.io.FileInputStream(P.agent.Threads.INSTANCE.file('assi', id))));
    expect(kept.model && kept.model.model === 'another-model', 'выбор чата не записан вместе с ним');
    expect(String(target('main').getModel()) === usual.model, 'выбор одного чата поменял основную модель');
    expect(!native('agent.threads', 'assi').some(function (thread) { return thread.id === id; }), 'пустой чат попал в список чатов');
    var back = native('agent.setModel', 'assi', id, null);
    expect(!back.own && back.model === usual.model && back.effort === usual.effort, 'чат не вернулся к обычной модели');
    return usual.model + ' → another-model → обратно';
  } finally {
    native('agent.deleteThread', 'assi', id);
  }
});

check('queue: what is said while the agent works joins the work or waits for its end', function () {
  var main = target('main');
  if (String(main.getBaseUrl()).indexOf('10.0.2.2') < 0) skip('нужна подставная модель: настоящая стоит денег и времени');
  var open = native('agent.thread', 'assi');
  var thread = native('agent.newThread', 'assi');
  var id = String(thread.id || thread);
  var wait = function (what, ms) {
    var until = Date.now() + ms;
    while (Date.now() < until) {
      if (what()) return true;
      sleep(100);
    }
    return false;
  };
  var shown = function () { return native('agent.history', 'assi', id); };
  try {
    native('agent.send', 'assi', 'проверка очереди', id);
    expect(wait(function () { return shown().some(function (item) { return item.type === 'tool'; }); }, 90000), 'агент не взялся за работу');
    native('agent.send', 'assi', 'сделай иначе', id, false, 'steer');
    native('agent.send', 'assi', 'проверка очереди: а это потом', id, false, 'after');
    native('agent.send', 'assi', 'это лишнее', id, false, 'steer');
    var queued = native('agent.queue', 'assi', id);
    expect(queued.length === 3 && queued[0].steer && !queued[1].steer, 'сообщения не ждут как сказано: ' + JSON.stringify(queued));
    var taken = native('agent.unqueue', 'assi', id, queued[2].id);
    expect(taken && taken.text === 'это лишнее' && native('agent.queue', 'assi', id).length === 2, 'сообщение не забрано из очереди');
    expect(wait(function () { return !native('agent.busy', 'assi', id); }, 120000), 'работа не закончилась');
    expect(native('agent.queue', 'assi', id).length === 0, 'в очереди что-то осталось');
    var words = shown().filter(function (item) { return item.type === 'user' || item.type === 'assistant'; }).map(function (item) { return item.type + ': ' + item.text; });
    var joined = words.indexOf('user: сделай иначе');
    var later = words.indexOf('user: проверка очереди: а это потом');
    expect(joined > 0 && later > joined, 'сообщения дошли не в том порядке: ' + words.join(' | '));
    expect(words.slice(joined, later).some(function (word) { return word.indexOf('assistant: Учёл: сделай иначе') === 0; }),
      'сказанное посреди работы не дошло до модели в этой же работе: ' + words.join(' | '));
    return 'вмешательство дошло между шагами, отложенное — после ответа';
  } finally {
    native('agent.stop', 'assi', id);
    native('agent.deleteThread', 'assi', id);
    native('agent.openThread', 'assi', open);
  }
});

check('steps: at the limit the work stops, says where it stands and waits for the owner', function () {
  var main = target('main');
  if (String(main.getBaseUrl()).indexOf('10.0.2.2') < 0) skip('нужна подставная модель: настоящая стоит денег и времени');
  var was = native('pref.get', 'agent.steps');
  var thread = native('agent.newThread', 'assi');
  var id = String(thread.id || thread);
  // Waits until the work on the latest request has stopped at the limit; tells what it saw on the way.
  // Only what follows the owner's latest message is counted: between two requests a long
  // conversation may be folded into a summary, and what came before is then gone from the list.
  var stopped = function (ms) {
    var seen = [];
    var started = Date.now();
    while (Date.now() < started + ms) {
      var items = native('agent.history', 'assi', id);
      var from = 0;
      items.forEach(function (item, index) { if (item.type === 'user') from = index; });
      var since = items.slice(from);
      var count = function (type) { return since.filter(function (item) { return item.type === type; }).length; };
      var said = since.filter(function (item) { return item.type === 'assistant'; }).pop();
      var now = { limits: count('limit'), tools: count('tool'), busy: Boolean(native('agent.busy', 'assi', id)), said: said ? String(said.text) : '' };
      var brief = now.limits + '/' + now.tools + '/' + now.busy;
      if (seen[seen.length - 1] !== brief) seen.push(brief);
      if (now.limits > 0 && !now.busy) return now;
      if (!now.busy && Date.now() - started > 5000) break;
      sleep(500);
    }
    var tail = native('agent.history', 'assi', id).slice(-3).map(function (item) { return JSON.stringify(item).slice(0, 260); }).join(' ## ');
    throw new Error('работа не остановилась на пределе за ' + Math.round((Date.now() - started) / 1000) + ' с (пределов/шагов/занят): ' + seen.slice(-10).join(' ') + ' ПОСЛЕДНЕЕ ' + tail + ' ЖУРНАЛ ' + String(native('log.engine', 12)));
  };
  try {
    native('pref.set', 'agent.steps', '20');
    native('agent.send', 'assi', 'проверка предела шагов', id);
    var first = stopped(300000);
    expect(first.tools === 20, 'шагов с инструментом ' + first.tools + ', а не 20');
    expect(first.said.indexOf('Остановился на пределе') === 0, 'итога нет: ' + first.said);
    // "Go on" is a new request: the count starts over.
    native('agent.send', 'assi', 'Продолжай: проверка предела шагов', id);
    var second = stopped(300000);
    expect(second.tools === 20, 'после продолжения шагов ' + second.tools + ', а не 20');
    expect(second.said.indexOf('Остановился на пределе') === 0, 'второго итога нет: ' + second.said);
    return '20 шагов, итог, ещё 20 после «Продолжай»';
  } finally {
    native('agent.stop', 'assi', id);
    native('pref.set', 'agent.steps', was === undefined || was === null ? null : String(was));
    native('agent.deleteThread', 'assi', id);
  }
});

check('subtasks: pieces of work run side by side', function () {
  var main = target('main');
  if (String(main.getBaseUrl()).indexOf('10.0.2.2') < 0) skip('нужна подставная модель: настоящая стоит денег и времени');
  var chat = P.agent.Agent.INSTANCE.chat('assi', '');
  var started = Date.now();
  var out = String(chat.parts(new J.JSONObject(JSON.stringify({ tasks: [{ title: 'Первая', task: 'Скажи привет' }, { title: 'Вторая', task: 'Скажи привет' }] }))));
  expect(out.indexOf('## Первая') >= 0 && out.indexOf('## Вторая') >= 0, 'нет отчётов обеих частей: ' + out.slice(0, 120));
  return Math.round((Date.now() - started) / 1000) + ' с на две части';
});

check('module checks: a module tests itself', function () {
  needScreen();
  var files = require('lib/assi').files;
  files.write('modules/zz-check/module.json', JSON.stringify({ title: 'Проверка проверок', description: '' }));
  files.write('modules/zz-check/main.js', 'var n = 0; exports.add = function () { return ++n; };');
  files.write('modules/zz-check/ui/index.html', '<!doctype html><meta charset="utf-8"><body><button id="b" onclick="this.textContent=\'нажато\'">жми</button></body>');
  files.write('modules/zz-check/checks.js', [
    "exports['логика считает'] = function (t) { var m = require('modules/zz-check/main.js'); t.equal(m.add() + 1, m.add()); };",
    "exports['экран отвечает на нажатие'] = function (t) { var p = t.screen('modules/zz-check/ui/index.html'); p.click('#b'); t.equal(p.text('#b'), 'нажато'); };",
    "exports['заведомо не проходит'] = function (t) { t.equal(1, 2, 'числа'); };",
  ].join('\n'));
  try {
    var report = String(P.agent.Tools.INSTANCE.run('module_check', JSON.stringify({ module: 'zz-check' }), P.agent.Agent.INSTANCE.chat('assi', ''), new java.util.ArrayList()).getText());
    expect(report.indexOf('прошло 2 из 3') > 0, 'итог не тот: ' + report.split('\n')[0]);
    expect(report.indexOf('прошло: экран отвечает на нажатие') > 0, 'проверка экрана не прошла: ' + report);
    expect(report.indexOf('НЕ ПРОШЛО: заведомо не проходит — числа') > 0, 'провал не показан');
  } finally {
    files.remove('modules/zz-check');
  }
});

check('base: personal facts across modules', function () {
  var base = require('lib/base');
  var ids = [];
  try {
    var day = 864e5;
    ids.push(base.add({ kind: 'zz-water', amount: 250, unit: 'ml', at: Date.now() - day, source: 'check' }));
    ids.push(base.add({ kind: 'zz-water', amount: 500, unit: 'ml', title: 'после бега', tags: ['Спорт'], source: 'check' }));
    ids.push(base.add({ kind: 'zz-sleep', amount: 6.5, unit: 'h', at: Date.now() - day, source: 'check' }));
    expect(base.find({ kind: 'zz-water' }).length === 2, 'записи не нашлись по виду');
    expect(base.find({ kind: 'zz-water', tag: 'спорт' }).length === 1, 'не нашлось по метке');
    expect(base.find({ text: 'после бега' })[0].amount === 500, 'не нашлось по словам');
    var sums = base.sum({ kind: 'zz-water', by: 'day' });
    expect(sums.length === 2 && sums[0].total + sums[1].total === 750, 'сумма по дням не та');
    var both = base.sql("select count(distinct kind) as kinds from records where kind like 'zz-%'");
    expect(both[0].kinds === 2, 'запрос поперёк видов не сработал');
    var thrown = false;
    try { base.sql('delete from records'); } catch (error) { thrown = true; }
    expect(thrown, 'база дала выполнить не select');
    var tool = String(P.agent.Tools.INSTANCE.run('base_find', JSON.stringify({ kind: 'zz-water', by: 'day' }), P.agent.Agent.INSTANCE.chat('assi', ''), new java.util.ArrayList()).getText());
    expect(tool.indexOf('сумма') > 0, 'инструмент агента не ответил: ' + tool);
  } finally {
    ids.forEach(function (id) { base.remove(id); });
  }
});

check('routines: set, armed, removed', function () {
  var routines = require('lib/routines');
  var made = routines.set({ title: 'Проверочная', when: { kind: 'daily', at: '03:33' }, do: 'ничего не делать' });
  try {
    expect(native('job.list')['routine:' + made.id], 'расписание не взведено');
    routines.enable(made.id, false);
    expect(!native('job.list')['routine:' + made.id], 'выключенная рутина осталась в расписании');
    var bad = false;
    try { routines.set({ title: 'x', when: { kind: 'every', minutes: 1 }, do: 'y' }); } catch (error) { bad = true; }
    expect(bad, 'принят интервал чаще пяти минут');
    var listed = String(P.agent.Tools.INSTANCE.run('routine_list', '{}', P.agent.Agent.INSTANCE.chat('assi', ''), new java.util.ArrayList()).getText());
    expect(listed.indexOf('Проверочная') >= 0, 'инструмент не показал рутину');
  } finally {
    routines.remove(made.id);
  }
  expect(!routines.list().some(function (item) { return item.id === made.id; }), 'рутина не удалилась');
});

check('drafts: a rework stays apart until taken', function () {
  var D = P.core.Drafts.INSTANCE;
  var files = require('lib/assi').files;
  var chat = P.agent.Agent.INSTANCE.chat('assi', '');
  var tool = function (name, args) { return String(P.agent.Tools.INSTANCE.run(name, JSON.stringify(args), chat, new java.util.ArrayList()).getText()); };
  files.write('modules/zz-draft/main.js', 'exports.say = function () { return "прежнее"; };');
  files.write('modules/zz-draft/old.txt', 'удалят');
  var id = String(D.create('Проверка черновика', 'assi', 'zz-none', ''));
  try {
    var inDraft = function (fn) { return D.within(id, new Packages.kotlin.jvm.functions.Function0({ invoke: fn })); };
    inDraft(function () {
      tool('fs_edit', { path: 'modules/zz-draft/main.js', old: 'прежнее', 'new': 'новое' });
      tool('fs_write', { path: 'modules/zz-draft/added.txt', content: 'добавлено' });
      tool('fs_delete', { path: 'modules/zz-draft/old.txt' });
      return null;
    });
    expect(String(files.read('modules/zz-draft/main.js')).indexOf('прежнее') > 0, 'черновик изменил само приложение');
    expect(files.exists('modules/zz-draft/old.txt') && !files.exists('modules/zz-draft/added.txt'), 'удаление или добавление дошло до приложения');
    expect(require('modules/zz-draft/main.js').say() === 'прежнее', 'приложение выполняет черновой код');
    var said = inDraft(function () { return String(Engine_eval('require("modules/zz-draft/main.js").say()')); });
    expect(String(said) === 'новое', 'в черновике выполняется не черновой код: ' + said);
    expect(served('~' + id + '/modules/zz-draft/main.js').indexOf('новое') > 0, 'экран черновика получает не черновой файл');
    // The app's own WebView asks for the same address already within the draft.
    // Checks of a module run in a draft open the draft's screen, not the app's.
    files.write('modules/zz-draft/page.html', '<!doctype html><title>app</title><body>приложение</body>');
    inDraft(function () { tool('fs_write', { path: 'modules/zz-draft/page.html', content: '<!doctype html><title>draft</title><body>черновик</body>' }); return null; });
    if (context.getSystemService('power').isInteractive()) {
      inDraft(function () { return native('ui.openWait', 'modules/zz-draft/page.html'); });
      var title = String(native('ui.eval', 'document.title', 0));
      native('ui.openWait', 'ui/home/index.html');
      expect(title.indexOf('draft') >= 0, 'в черновике открылся экран приложения: ' + title);
    }
    files.remove('modules/zz-draft/page.html');
    inDraft(function () { tool('fs_delete', { path: 'modules/zz-draft/page.html' }); return null; });
    var asScreen = inDraft(function () { return served('~' + id + '/modules/zz-draft/main.js'); });
    expect(String(asScreen).indexOf('новое') > 0, 'экран черновика не открывается из приложения: ' + String(asScreen).slice(0, 80));
    var kinds = JSON.parse(String(D.changes(id))).map(function (item) { return item.path.split('/').pop() + ':' + item.kind; }).sort().join(' ');
    expect(kinds === 'added.txt:added main.js:changed old.txt:removed', 'список изменений не тот: ' + kinds);
    expect(String(D.diff(id, 'modules/zz-draft/main.js')).indexOf('+exports.say') > 0, 'нет разницы по файлу');
    D.accept(id);
    expect(String(files.read('modules/zz-draft/main.js')).indexOf('новое') > 0 && files.exists('modules/zz-draft/added.txt') && !files.exists('modules/zz-draft/old.txt'),
      'принятый черновик не стал приложением');
  } finally {
    D.discard(id);
    files.remove('modules/zz-draft');
  }
});

function Engine_eval(code) { return P.core.Engine.INSTANCE.eval(code, 'check', 0); }

check('mcp: the phone serves its tools to agents elsewhere', function () {
  var lan = native('lan.status');
  if (!lan.running) skip('доступ с компьютера выключен');
  var http = require('lib/assi').http;
  var url = 'http://127.0.0.1:' + lan.port + '/mcp';
  var call = function (body, headers) { return http.request({ url: url, method: 'POST', json: body, headers: headers || { Authorization: 'Bearer ' + lan.code } }); };
  expect(call({ jsonrpc: '2.0', id: 1, method: 'tools/list' }, {}).status === 401, 'без кода пустило');
  var hello = call({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'check', version: '1' } } }).json();
  expect(hello.result && hello.result.serverInfo.name === 'assi-phone', 'нет приветствия');
  var tools = call({ jsonrpc: '2.0', id: 2, method: 'tools/list' }).json().result.tools.map(function (tool) { return tool.name; });
  expect(tools.indexOf('assi_ask') >= 0 && tools.indexOf('fs_read') >= 0 && tools.indexOf('phone_notifications') >= 0, 'нет ожидаемых инструментов');
  expect(tools.indexOf('ask_owner') < 0 && tools.indexOf('subtasks') < 0, 'наружу отданы инструменты, которым нужен владелец в чате');
  var read = call({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'fs_read', arguments: { path: 'ui/nav.json' } } }).json().result;
  expect(!read.isError && read.content[0].text.indexOf('items') >= 0, 'вызов инструмента не сработал');
  return tools.length + ' инструментов';
});

check('lan: the phone answers to its name, the owner\'s own key lets in', function () {
  var lan = native('lan.status');
  if (!lan.running) skip('доступ с компьютера выключен');
  var failedName = false;
  try { native('lan.setup', { name: 'мой телефон' }); } catch (error) { failedName = true; }
  expect(failedName, 'принято имя, которое сеть не поймёт');
  expect(native('lan.status').name === lan.name, 'негодное имя всё же записано');

  // The key: the owner's own lets in, the one before it no longer does.
  var http = require('lib/assi').http;
  var list = function (key) {
    return http.request({ url: 'http://127.0.0.1:' + lan.port + '/mcp', method: 'POST', json: { jsonrpc: '2.0', id: 1, method: 'tools/list' }, headers: { Authorization: 'Bearer ' + key } }).status;
  };
  try {
    expect(native('lan.setup', { code: 'my-own-key-1' }).code === 'my-own-key-1', 'свой ключ не записан');
    expect(list('my-own-key-1') === 200, 'свой ключ не пускает');
    expect(list(lan.code) === 401, 'прежний ключ всё ещё пускает');
    expect(http.request({ url: 'http://127.0.0.1:' + lan.port + '/?k=wrong' }).status === 401, 'страница открылась с неверным ключом');
  } finally {
    native('lan.setup', { code: lan.code });
  }

  // The name: a question to everyone on the network, as a computer asks it, gets the phone's address back.
  if (!lan.named) skip('ключ проверен; имя в сети не объявлено — телефон не в локальной сети');
  var address = String(lan.addresses[lan.addresses.length - 1]).replace(/^http:\/\/|:\d+$/g, '');
  var group = java.net.InetAddress.getByName('224.0.0.251');
  var network = java.net.NetworkInterface.getByInetAddress(java.net.InetAddress.getByName(address));
  var socket = new java.net.MulticastSocket(null);
  try {
    socket.setReuseAddress(true);
    socket.bind(new java.net.InetSocketAddress(5353));
    socket.joinGroup(new java.net.InetSocketAddress(group, 5353), network);
    socket.setNetworkInterface(network);
    socket.setSoTimeout(400);
    var out = new java.io.ByteArrayOutputStream();
    [0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0].forEach(function (byte) { out.write(byte); });
    [lan.name, 'local'].forEach(function (label) {
      var bytes = new java.lang.String(label).getBytes('UTF-8');
      out.write(bytes.length);
      out.write(bytes, 0, bytes.length);
    });
    [0, 0, 1, 0, 1].forEach(function (byte) { out.write(byte); });
    var question = out.toByteArray();
    var wanted = address.split('.').map(Number);
    var buffer = java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, 2000);
    var found = false;
    var until = Date.now() + 5000;
    var asked = 0;
    while (!found && Date.now() < until) {
      if (Date.now() - asked > 1000) {
        socket.send(new java.net.DatagramPacket(question, question.length, group, 5353));
        asked = Date.now();
      }
      var packet = new java.net.DatagramPacket(buffer, buffer.length);
      try { socket.receive(packet); } catch (quiet) { continue; }
      if ((buffer[2] & 0x80) === 0) continue;
      for (var i = 12; i + 4 <= packet.getLength() && !found; i++) {
        found = (buffer[i] & 255) === wanted[0] && (buffer[i + 1] & 255) === wanted[1] && (buffer[i + 2] & 255) === wanted[2] && (buffer[i + 3] & 255) === wanted[3];
      }
    }
    expect(found, 'на вопрос об имени ' + lan.name + '.local никто не ответил адресом ' + address);
  } finally {
    socket.close();
  }
  return lan.name + '.local → ' + address;
});

check('speech: recognizers and the live conversation are in place', function () {
  var status = native('speech.status');
  expect(status.gigaam !== undefined && status.parakeet !== undefined, 'нет сведений о моделях распознавания');
  expect(status.live && status.live.active === false, 'живой разговор числится идущим');
  return 'GigaAM ' + (status.gigaam ? 'есть' : 'нет') + ', Parakeet ' + (status.parakeet ? 'есть' : 'нет');
});

// ---- the name aloud ---------------------------------------------------------------------------------

check('wake: the name in what was heard', function () {
  var W = P.voice.Wake.INSTANCE;
  var cases = [
    ['Асси, который час', 'Асси', true], ['аси включи свет', 'Асси', true], ['Эй, Асси, привет', 'Асси', true],
    ['осси что нового', 'Асси', true], ['Такси приехало', 'Асси', false], ['Сегодня хорошая погода', 'Асси', false],
    ['Джарвис, открой заметки', 'Джарвис', true], ['жарвис открой заметки', 'Джарвис', false], ['Марфа', 'Марфа', true],
    ['расскажи про марфу', 'Марфа', false],
  ];
  cases.forEach(function (c) {
    var end = W.nameEnd(c[0], c[1]);
    expect((end !== null) === c[2], '«' + c[0] + '» для имени ' + c[1] + ': ожидалось ' + c[2]);
  });
  expect(String('Асси, который час').substring(W.nameEnd('Асси, который час', 'Асси')).indexOf('который') >= 0, 'просьба после имени потерялась');
});

check('voice on the phone: speaks', function () {
  if (!native('tts.local.status').ready) skip('голос не скачан');
  var samples = P.voice.LocalTts.INSTANCE.render('Привет, это проверка голоса.', 0);
  expect(samples.length > 16000, 'звука меньше секунды: ' + samples.length);
  return (samples.length / 16000).toFixed(1) + ' с звука';
});

check('wake: the light model hears the name', function () {
  var state = native('wake.status');
  if (!state.lightReady) skip('лёгкая модель не скачана');
  if (!native('tts.local.status').ready) skip('нечем произнести имя: голос не скачан');
  var W = P.voice.Wake.INSTANCE;
  var T = P.voice.LocalTts.INSTANCE;
  var heard = 0;
  var tried = 0;
  [0, 3, 6].forEach(function (voice) {
    tried++;
    if (W.spot(T.render('Марфа, который сейчас час?', voice), 'Марфа')) heard++;
  });
  var falsely = W.spot(T.render('Сегодня очень хорошая погода, пойдём гулять.', 0), 'Марфа');
  expect(heard >= 2, 'имя услышано ' + heard + ' раз из ' + tried);
  expect(!falsely, 'ложное срабатывание на постороннюю фразу');
  return heard + ' из ' + tried + ', без ложных';
});

// ---- models on the phone ------------------------------------------------------------------------------

function ask(model, messages, tools, context) {
  var pieces = 0;
  var reply = P.agent.LocalLlm.INSTANCE.generate(model, new J.JSONArray(JSON.stringify(messages)), new J.JSONArray(JSON.stringify(tools || [])),
    context || 0, function () { return false; }, function (piece) { pieces++; });
  var calls = [];
  for (var i = 0; i < reply.getCalls().size(); i++) calls.push(String(reply.getCalls().get(i).getName()) + ' ' + String(reply.getCalls().get(i).getArguments()));
  return { text: String(reply.getText()), calls: calls, pieces: pieces };
}

check('local model: answers a greeting', function () {
  var models = localModels();
  if (!models.length) skip('нет скачанных моделей');
  var model = models[0];
  var out = ask(model.id, [{ role: 'system', content: 'Отвечай коротко.' }, { role: 'user', content: 'Йоу' }]);
  expect(out.text.length > 0, 'пустой ответ');
  return model.id + ', окно ' + P.agent.LocalLlm.INSTANCE.getWindow() + ' из ' + model.context + ': «' + out.text.slice(0, 60).replace(/\n/g, ' ') + '»';
});

check('local model: the main agent\'s real prompt and tools', function () {
  var models = localModels();
  if (!models.length) skip('нет скачанных моделей');
  var spec = P.agent.Agent.INSTANCE.spec('assi');
  var chat = P.agent.Agent.INSTANCE.chat('assi', '');
  var as = new P.agent.Models.Target('local', 'local:', '', models[0].id, new J.JSONObject());
  var lean = chat.lean(spec, as);
  var prompt = String(chat.systemPrompt(spec, lean));
  var light = java.util.Arrays.asList('fs_list', 'fs_read', 'fs_write', 'fs_edit', 'js_eval', 'web_search', 'web_read', 'memory_save', 'memory_search');
  var tools = JSON.parse(String(P.agent.Tools.INSTANCE.schemas(lean ? light : null, false, false, false)));
  var started = Date.now();
  var out = ask(models[0].id, [{ role: 'system', content: prompt }, { role: 'user', content: 'Йоу' }], tools);
  var first = Date.now() - started;
  expect(out.text.length > 0 || out.calls.length > 0, 'пустой ответ');
  // The next message of the same conversation must not read the prompt again.
  started = Date.now();
  var again = ask(models[0].id, [{ role: 'system', content: prompt }, { role: 'user', content: 'Йоу' },
    { role: 'assistant', content: out.text }, { role: 'user', content: 'Сколько будет два плюс два? Ответь числом.' }], tools);
  var second = Date.now() - started;
  expect(again.text.length > 0 || again.calls.length > 0, 'пустой ответ на второе сообщение');
  return (lean ? 'короткий' : 'полный') + ' промпт ≈ ' + Math.round(prompt.length / 3) + ' ток., ' + tools.length + ' инструментов, окно '
    + P.agent.LocalLlm.INSTANCE.getWindow() + '; первый ответ ' + Math.round(first / 1000) + ' с, следующий ' + Math.round(second / 1000)
    + ' с: «' + again.text.slice(0, 40).replace(/\n/g, ' ') + '»';
});

check('local model: calls a tool and uses its result', function () {
  var models = localModels();
  if (!models.length) skip('нет скачанных моделей');
  var weather = [{ type: 'function', function: { name: 'get_weather', description: 'Текущая погода в городе', parameters: { type: 'object', properties: { city: { type: 'string', description: 'Город' } }, required: ['city'] } } }];
  var first = ask(models[0].id, [{ role: 'user', content: 'Какая сейчас погода в Париже? Узнай инструментом.' }], weather);
  expect(first.calls.length > 0, 'инструмент не вызван, ответ: ' + first.text.slice(0, 80));
  var second = ask(models[0].id, [
    { role: 'user', content: 'Какая сейчас погода в Париже? Узнай инструментом.' },
    { role: 'assistant', content: '', tool_calls: [{ id: 'c1', type: 'function', function: { name: 'get_weather', arguments: '{"city":"Париж"}' } }] },
    { role: 'tool', tool_call_id: 'c1', content: '{"temperature_c": 7, "sky": "дождь"}' },
  ], weather);
  expect(/7|дожд/i.test(second.text), 'результат инструмента не использован: ' + second.text.slice(0, 80));
  return first.calls[0];
});

check('local model: a long conversation is trimmed, not refused', function () {
  var models = localModels();
  if (!models.length) skip('нет скачанных моделей');
  var messages = [];
  var filler = new Array(120).join('Это длинная реплика из прошлого разговора. ');
  for (var i = 0; i < 12; i++) {
    messages.push({ role: 'user', content: 'Реплика ' + i + '. ' + filler });
    messages.push({ role: 'assistant', content: 'Ответ ' + i + '. ' + filler });
  }
  messages.push({ role: 'user', content: 'Скажи одно слово: готово.' });
  var out = ask(models[0].id, messages, [], 4096);
  expect(out.text.length > 0, 'пустой ответ');
  return '≈' + Math.round(JSON.stringify(messages).length / 2) + ' ток. в окно 4096';
});

check('embeddings on the phone', function () {
  var model = native('local.status').filter(function (m) { return m.installed && m.embedding; })[0];
  if (!model) skip('модель эмбеддингов не скачана');
  var vectors = P.agent.LocalLlm.INSTANCE.embed(model.id, new J.JSONArray(JSON.stringify(['кошка спит на диване', 'кот дремлет на софе', 'курс доллара вырос'])));
  var v = [0, 1, 2].map(function (i) { return JSON.parse(String(vectors.getJSONArray(i))); });
  var cos = function (a, b) {
    var dot = 0, na = 0, nb = 0;
    for (var i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
    return dot / Math.sqrt(na * nb);
  };
  expect(cos(v[0], v[1]) > cos(v[0], v[2]), 'близкие по смыслу фразы не ближе далёких');
  return v[0].length + ' чисел; близкие ' + cos(v[0], v[1]).toFixed(2) + ', далёкие ' + cos(v[0], v[2]).toFixed(2);
});

// ---- the window and folding -----------------------------------------------------------------------------

check('window: the role\'s setting', function () {
  var roles = native('models.roles');
  if (!roles.main) skip('основная модель не выбрана');
  var before = JSON.stringify(roles);
  try {
    roles.main.context = 0;
    native('models.setRoles', roles);
    var natural = target('main').getWindow();
    roles.main.context = 2345;
    native('models.setRoles', roles);
    expect(target('main').getWindow() === 2345, 'окно роли не применилось');
    return 'без настройки ' + natural + ', с настройкой 2345';
  } finally {
    native('models.setRoles', JSON.parse(before));
  }
});

check('folding: a conversation that outgrows the window is summarized', function () {
  var roles = native('models.roles');
  if (!roles.main) skip('основная модель не выбрана');
  var main = target('main');
  if (String(main.getBaseUrl()).indexOf('10.0.2.2') < 0 && !main.getOnPhone()) skip('тратить деньги облачной модели на проверку не стоит: нужна подставная или локальная');
  var before = JSON.stringify(roles);
  var open = native('agent.thread', 'assi');
  var thread = native('agent.newThread', 'assi');
  var id = String(thread.id || thread);
  try {
    roles.main.context = 3000;
    native('models.setRoles', roles);
    var chat = P.agent.Agent.INSTANCE.chat('assi', id);
    var filler = new Array(60).join('Подробности, которые занимают место в окне. ');
    var history = [];
    for (var i = 0; i < 8; i++) {
      history.push({ role: 'user', content: 'Вопрос ' + i + '. ' + filler });
      history.push({ role: 'assistant', content: 'Ответ ' + i + '. ' + filler });
    }
    chat.restore(new J.JSONArray(JSON.stringify(history)));
    var was = chat.size();
    chat.compact();
    var now = chat.size();
    expect(now < was, 'разговор не свёрнут: ' + was + ' → ' + now);
    expect(String(chat.first()).indexOf('Вопрос 0') < 0, 'самая старая реплика осталась дословно');
    expect(!/"content":"Вопрос \d/.test(String(chat.first())), 'старые реплики выброшены, а изложение не написано');
    return was + ' сообщений → ' + now;
  } finally {
    native('models.setRoles', JSON.parse(before));
    native('agent.deleteThread', 'assi', id);
    native('agent.openThread', 'assi', open);
  }
});

// ---- what the shell gives modules (docs/shell-changes.md) ------------------------------------------------

check('shell.features: what this shell can do', function () {
  var features = native('shell.features');
  ['notice.full', 'notice.removed', 'notice.open', 'notify.actions', 'dnd', 'off', 'job.once', 'vibrate.pattern', 'app.visible',
    'speech.stream', 'transcribe.segments', 'speech.render', 'theme.decor', 'widget.image', 'home.cards', 'usage'].forEach(function (name) {
    expect(features.indexOf(name) >= 0, 'не названо ' + name);
  });
  return features.length + ' возможностей';
});

check('notify: buttons call a module, a reply brings its text, cancel takes it away', function () {
  if (!context.getSystemService('notification').areNotificationsEnabled()) skip('уведомления Axiko выключены');
  var assi = require('lib/assi');
  var path = 'modules/zz-check-notify/main.js';
  assi.files.write(path, 'exports.got = []; exports.press = function () { exports.got.push(Array.prototype.slice.call(arguments)); };');
  var got = require(path).got;
  var buttons = [
    { title: 'Да', call: path, fn: 'press', args: { n: 1 } },
    { title: 'Ответить', call: path, fn: 'press', args: 'r', reply: true },
  ];
  try {
    var id = assi.notify({ id: 'check-notify', title: 'Проверка', text: 'Кнопки', ongoing: true, progress: { max: 0 }, actions: buttons });
    expect(id === 'check-notify', 'id не тот: ' + id);
    var shown = postedNotification(id);
    expect(shown && shown.getNotification().actions.length === 2, 'уведомления с двумя кнопками нет в шторке');
    shown.getNotification().actions[0].actionIntent.send();
    expect(waitFor(function () { return got.length === 1; }, 8000), 'функция модуля не вызвана');
    expect(JSON.stringify(got[0]) === '[{"n":1}]', 'не те аргументы: ' + JSON.stringify(got[0]));
    expect(shownNotification(id), 'постоянное уведомление пропало после нажатия');

    var reply = shownNotification(id).getNotification().actions[1];
    var results = new android.os.Bundle();
    results.putCharSequence(reply.getRemoteInputs()[0].getResultKey(), 'привет');
    var filled = new android.content.Intent();
    android.app.RemoteInput.addResultsToIntent(reply.getRemoteInputs(), filled, results);
    reply.actionIntent.send(context, 0, filled);
    expect(waitFor(function () { return got.length === 2; }, 8000), 'ответ не дошёл до модуля');
    expect(JSON.stringify(got[1]) === '["r","привет"]', 'ответ пришёл не так: ' + JSON.stringify(got[1]));
    expect(postedNotification(id), 'отвеченное постоянное уведомление не вернулось');

    assi.notify({ id: 'check-notify-once', text: 'Одна кнопка', actions: [buttons[0]] });
    expect(postedNotification('check-notify-once'), 'уведомления с одной кнопкой нет в шторке');
    shownNotification('check-notify-once').getNotification().actions[0].actionIntent.send();
    expect(waitFor(function () { return got.length === 3; }, 8000), 'кнопка обычного уведомления не вызвала функцию');
    expect(waitFor(function () { return !shownNotification('check-notify-once'); }, 3000), 'обычное уведомление осталось после нажатия кнопки');

    assi.notify.cancel(id);
    expect(waitFor(function () { return !shownNotification(id); }, 3000), 'cancel не убрал уведомление');
    expect(failure(function () { assi.notify({ text: 'x', actions: [{ title: 'x' }] }); }).indexOf('call и fn') >= 0, 'кнопка без call и fn принята');
    expect(failure(function () { assi.notify({ text: 'x', channel: 'loud' }); }).indexOf('Нет такого канала') >= 0, 'неизвестный канал принят');

    assi.notify({ id: 'check-alarm', title: 'Проверка', text: 'Будильник', channel: 'alarm', silent: true });
    expect(postedNotification('check-alarm'), 'будильника нет в шторке');
    var alarm = shownNotification('check-alarm').getNotification();
    expect(String(alarm.getChannelId()) === 'assi-alarm' && String(alarm.category) === 'alarm', 'не канал будильников: ' + alarm.getChannelId());
    var bypass = context.getSystemService('notification').getNotificationChannel('assi-alarm').canBypassDnd();
    return '3 нажатия дошли; канал будильников ' + (bypass ? 'звучит сквозь «Не беспокоить»' : 'без обхода «Не беспокоить»');
  } finally {
    ['check-notify', 'check-notify-once', 'check-alarm'].forEach(function (tag) { assi.notify.cancel(tag); });
    assi.files.remove('modules/zz-check-notify');
  }
});

check('notifications: a chat read whole — messages, whose, the conversation', function () {
  var manager = context.getSystemService('notification');
  if (!manager.areNotificationsEnabled()) skip('уведомления Axiko выключены');
  var Style = android.app.Notification.MessagingStyle;
  var me = new android.app.Person.Builder().setName('Я').setKey('me').build();
  var anna = new android.app.Person.Builder().setName('Аня').setKey('anna').build();
  var style = new Style(me).setConversationTitle('Семья').setGroupConversation(true);
  style.addMessage(new Style.Message('Привет', Date.now() - 1000, anna));
  style.addMessage(new Style.Message('И тебе', Date.now(), me));
  manager.createNotificationChannel(new android.app.NotificationChannel('assi', 'Axiko', 3));
  manager.notify('check-chat', 0, new android.app.Notification.Builder(context, 'assi')
    .setSmallIcon(context.getApplicationInfo().icon).setStyle(style).build());
  try {
    var shown = postedNotification('check-chat');
    expect(shown, 'уведомление не показано');
    var item = JSON.parse(String(P.sense.Notices.INSTANCE.read(shown)));
    expect(item.messages.length === 2, 'сообщений ' + item.messages.length);
    expect(item.messages[0].sender === 'Аня' && item.messages[0].text === 'Привет', 'чужое сообщение: ' + JSON.stringify(item.messages[0]));
    expect(item.messages[1].sender === null, 'своё сообщение с отправителем: ' + JSON.stringify(item.messages[1]));
    expect(item.conversationTitle === 'Семья' && item.isGroup === true, 'беседа: ' + item.conversationTitle + ', группа ' + item.isGroup);
    expect(item.tag === 'check-chat' && item.channel === 'assi' && item.shortcutId === null && typeof item.user === 'number', 'поля: ' + JSON.stringify(item));
    return 'профиль ' + item.user + ', ' + item.messages.length + ' сообщения';
  } finally {
    manager.cancel('check-chat', 0);
  }
});

check('notifications: another app\'s chat heard and its removal told', function () {
  if (!P.sense.Notices.INSTANCE.getOn()) skip('чтение уведомлений выключено');
  var shown = shownNotification('assi-check', 'com.android.shell');
  if (!shown) skip('нет уведомления от оболочки: его посылает scripts\\check.ps1 перед проверками');
  var item = JSON.parse(String(P.sense.Notices.INSTANCE.describe(shown)));
  expect(item.messages.length === 2 && item.messages[0].sender === 'Anna' && item.messages[1].sender === null, 'сообщения: ' + JSON.stringify(item.messages));
  expect(item.conversationTitle === 'Family', 'беседа: ' + item.conversationTitle);
  var gone = [];
  on('notification.removed', function (event) { if (event.key === item.key) gone.push(event); }, 'check-removed');
  try {
    expect(native('notices.dismiss', item.key) === true, 'не убрано');
    expect(waitFor(function () { return gone.length > 0; }, 5000), 'notification.removed не пришло');
    expect(gone[0].package === 'com.android.shell' && gone[0].reason === 'other' && gone[0].time > 0, 'событие: ' + JSON.stringify(gone[0]));
  } finally {
    off('notification.removed', 'check-removed');
  }
  // The shell gives its notifications nothing to open: there is nothing to remember either.
  expect(native('notices.open', item.key) === false, 'открыто то, чего не было');
  return 'причина ' + gone[0].reason;
});

check('notifications: a tap made from Axiko opens what the notification opens', function () {
  needScreen();
  if (!P.sense.Notices.INSTANCE.getOn()) skip('чтение уведомлений выключено');
  var assi = require('lib/assi');
  var was = native('ui.where').path;
  try {
    P.MainActivity.Companion.getCurrent().getPage().openAndWait('ui/home/index.html', 10000);
    var id = assi.notify({ id: 'check-open', text: 'Открыть настройки', open: 'ui/settings/index.html' });
    var shown = postedNotification(id, String(context.getPackageName()));
    expect(shown, 'слушатель не видит уведомление');
    expect(native('notices.open', String(shown.getKey())) === true, 'не открыто');
    expect(waitFor(function () { return native('ui.where').path.indexOf('ui/settings') >= 0; }, 6000), 'открыт не тот экран: ' + native('ui.where').path);
  } finally {
    assi.notify.cancel('check-open');
    native('ui.open', was || 'ui/home/index.html');
  }
});

check('dnd: the quiet mode read and switched', function () {
  var before = native('dnd.get');
  if (!before.access && !P.sense.Notices.INSTANCE.getOn()) skip('нет ни доступа к «Не беспокоить», ни чтения уведомлений');
  var other = before.filter === 'priority' ? 'alarms' : 'priority';
  try {
    expect(native('dnd.set', other) === true, 'не переключено');
    expect(native('dnd.get').filter === other, 'режим ' + native('dnd.get').filter + ' вместо ' + other);
    expect(failure(function () { native('dnd.set', 'loud'); }).indexOf('Нет такого режима') >= 0, 'неизвестный режим принят');
  } finally {
    native('dnd.set', before.filter);
  }
  expect(native('dnd.get').filter === before.filter, 'прежний режим не вернулся');
  return before.filter + ' → ' + other + ' → ' + before.filter + (before.access ? '' : ' (через чтение уведомлений)');
});

check('off: a subscription taken back by key and by function', function () {
  var got = [];
  var fire = function (n) { P.core.Engine.INSTANCE.fire('check.off', new J.JSONObject('{"n":' + n + '}')); };
  var fn = function (event) { got.push(event.n); };
  on('check.off', fn, 'check-off');
  fire(1);
  expect(waitFor(function () { return got.length === 1; }, 3000), 'событие не пришло');
  off('check.off', 'check-off');
  // Events are delivered on another thread: each must have had its chance before the next step.
  fire(2);
  sleep(800);
  expect(got.join() === '1', 'после off по ключу приходило: ' + got.join());
  on('check.off', fn);
  off('check.off', fn);
  fire(3);
  sleep(800);
  expect(got.join() === '1', 'после off по функции приходило: ' + got.join());
});

check('job: once at a moment, and not again', function () {
  var ran = [];
  var moment = Date.now() + 1500;
  var fn = function () { ran.push(Date.now()); };
  try {
    job('check-once', { once: moment }, fn);
    expect(native('job.list')['check-once'], 'задача не записана');
    expect(waitFor(function () { return ran.length === 1; }, 20000), 'задача не сработала за 20 с');
    var late = ran[0] - moment;
    expect(!native('job.list')['check-once'], 'исполненная задача осталась в списке');
    // A script registers its jobs on every start: the same moment does not ring twice.
    job('check-once', { once: moment }, fn);
    job('check-past', { once: Date.now() - 60000 }, fn);
    expect(waitFor(function () { return ran.length === 2; }, 20000), 'прошедший момент не сработал сразу');
    sleep(2000);
    expect(ran.length === 2, 'сработало ' + ran.length + ' раз');
    return 'опоздание ' + late + ' мс';
  } finally {
    unjob('check-once');
    unjob('check-past');
  }
});

check('vibrate: a pattern', function () {
  var assi = require('lib/assi');
  assi.vibrate([0, 60, 80, 60]);
  assi.vibrate(30);
});

check('app.visible: out of sight and back', function () {
  needScreen();
  if (!android.provider.Settings.canDrawOverlays(context)) skip('вернуться самому нельзя: нет разрешения «поверх других окон»');
  var activity = P.MainActivity.Companion.getCurrent();
  var seen = [];
  on('app.visible', function (event) { seen.push(event.on); }, 'check-visible');
  try {
    activity.evalInPage('window.__seen = []; document.addEventListener("visibilitychange", function () { __seen.push(document.visibilityState); }); 1', 3000);
    new android.os.Handler(android.os.Looper.getMainLooper()).post(function () { activity.moveTaskToBack(true); });
    expect(waitFor(function () { return seen.indexOf(false) >= 0; }, 5000), 'уход с экрана не замечен');
    context.startActivity(new android.content.Intent().setClassName(context, 'app.assi.MainActivity').addFlags(0x10000000 | 0x00020000));
    expect(waitFor(function () { return seen[seen.length - 1] === true; }, 5000), 'возвращение не замечено: ' + seen.join(' → '));
    var page = '';
    waitFor(function () { page = String(activity.evalInPage('__seen.join()', 3000)); return page === '"hidden,visible"'; }, 3000);
    expect(page === '"hidden,visible"', 'страница видела: ' + page);
    return 'движок: ' + seen.join(' → ') + '; страница: ' + page;
  } finally {
    off('app.visible', 'check-visible');
  }
});

check('theme: a decor over any theme', function () {
  var files = require('lib/assi').files;
  var kept = files.exists('themes/_decor.css') ? files.read('themes/_decor.css') : '';
  try {
    native('theme.decor', 'body { --check-decor: 1; }');
    expect(served('ui/kit/kit.css').indexOf('--check-decor: 1') > 0, 'украшение не попало в стили');
    native('theme.decor', '');
    expect(served('ui/kit/kit.css').indexOf('--check-decor') < 0, 'украшение не снялось');
  } finally {
    native('theme.decor', kept);
  }
});

check('widget: a module\'s picture beside its text', function () {
  var files = require('lib/assi').files;
  var path = 'modules/zz-check-widget/main.js';
  files.write(path, "exports.image = 'data/check-widget.png'; exports.widget = function () { return { title: 'Проверка', text: 'С картинкой', image: exports.image }; };");
  var picture = android.graphics.Bitmap.createBitmap(1200, 600, android.graphics.Bitmap.Config.ARGB_8888);
  var out = new java.io.FileOutputStream(new java.io.File(String(host.path('data/check-widget.png'))));
  picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
  out.close();
  var image = function () {
    var views = P.sense.Widgets.INSTANCE.views(0, 'zz-check-widget');
    var drawn = null;
    var done = new java.util.concurrent.CountDownLatch(1);
    new android.os.Handler(android.os.Looper.getMainLooper()).post(function () {
      try { drawn = views.apply(context, new android.widget.FrameLayout(context)); } finally { done.countDown(); }
    });
    done.await(5, java.util.concurrent.TimeUnit.SECONDS);
    return drawn.findViewById(context.getResources().getIdentifier('widget_image', 'id', String(context.getPackageName())));
  };
  try {
    var shown = image();
    expect(shown.getVisibility() === 0 && shown.getDrawable(), 'картинки нет');
    var width = shown.getDrawable().getIntrinsicWidth();
    expect(width <= 480, 'картинка не уменьшена: ' + width);
    require(path).image = '';
    expect(image().getVisibility() === 8, 'без картинки место осталось');
    return '1200×600 → ' + width + '×' + shown.getDrawable().getIntrinsicHeight();
  } finally {
    files.remove('modules/zz-check-widget');
    files.remove('data/check-widget.png');
  }
});

check('home: a module\'s card in a frame, hearing events', function () {
  needScreen();
  var files = require('lib/assi').files;
  files.write('modules/zz-check-card/module.json', JSON.stringify({ title: 'Проверка карточки', home: 'ui/card.html', homeHeight: 90 }));
  files.write('modules/zz-check-card/ui/index.html', '<!doctype html><meta charset="utf-8"><title>Проверка</title>');
  files.write('modules/zz-check-card/ui/card.html', '<!doctype html><meta charset="utf-8"><body><b id="n">0</b>' +
    '<script>assi.on("check.card", function (data) { document.getElementById("n").textContent = data.n; });</script>');
  var frame = 'document.querySelector(\'#cards iframe[src="/modules/zz-check-card/ui/card.html"]\')';
  var ask = function (code) { return String(native('ui.eval', code, 0)); };
  try {
    native('ui.openWait', 'ui/home/index.html');
    expect(waitFor(function () { return ask('Boolean(' + frame + ' && ' + frame + '.contentDocument.getElementById("n"))') === 'true'; }, 8000), 'карточки нет на главном экране');
    expect(ask(frame + '.getBoundingClientRect().height') === '90', 'высота карточки ' + ask(frame + '.getBoundingClientRect().height'));
    // The frame's page has said hello by now: give it a moment, then talk to it.
    sleep(500);
    emit('check.card', { n: 7 });
    expect(waitFor(function () { return ask(frame + '.contentDocument.getElementById("n").textContent === "7"') === 'true'; }, 5000), 'событие не дошло до карточки');
  } finally {
    files.remove('modules/zz-check-card');
    native('ui.openWait', 'ui/home/index.html');
  }
});

check('health: the screen Health Connect opens to say why', function () {
  needScreen();
  var asked = context.getPackageManager().getPackageInfo(String(context.getPackageName()), 4096).requestedPermissions;
  var wanted = ['android.permission.health.READ_STEPS', 'android.permission.health.READ_SLEEP', 'android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND'];
  var have = [];
  for (var i = 0; i < asked.length; i++) have.push(String(asked[i]));
  wanted.forEach(function (name) { expect(have.indexOf(name) >= 0, 'не заявлено ' + name); });
  var intent = new android.content.Intent('android.intent.action.VIEW_PERMISSION_USAGE').addCategory('android.intent.category.HEALTH_PERMISSIONS');
  var found = context.getPackageManager().queryIntentActivities(intent.setPackage(String(context.getPackageName())), 0);
  expect(found.size() === 1, 'Health Connect не найдёт экран объяснения: ' + found.size());
  var was = native('ui.where').path;
  try {
    // The system starts it holding START_VIEW_PERMISSION_USAGE; the app itself may too.
    context.startActivity(intent.setClassName(context, String(found.get(0).activityInfo.name)).addFlags(0x10000000));
    expect(waitFor(function () { return native('ui.where').path.indexOf('ui/health-rationale') >= 0; }, 6000), 'открыт не тот экран: ' + native('ui.where').path);
    return native('ui.where').title;
  } finally {
    native('ui.open', was || 'ui/home/index.html');
  }
});

check('usage: time in apps and the screen\'s sessions', function () {
  var usage = require('lib/usage');
  // Two stretches of "a" around one of "b", which left and came back without anyone between.
  var events = [
    { package: 'a', type: 'resumed', time: 1000 }, { package: 'a', type: 'paused', time: 5000 },
    { package: 'b', type: 'resumed', time: 5100 }, { package: 'b', type: 'paused', time: 9000 },
    { package: 'b', type: 'resumed', time: 9500 }, { package: 'b', type: 'paused', time: 12000 },
    { package: 'android', type: 'screen-off', time: 12500 }, { package: 'android', type: 'screen-on', time: 20000 },
    { package: 'a', type: 'resumed', time: 20500 }, { package: 'a', type: 'paused', time: 22000 },
    { package: 'android', type: 'screen-off', time: 23000 },
  ];
  var shape = function (list) { return JSON.stringify(list.map(function (app) { return [app.package, app.ms, app.opens]; })); };
  expect(shape(usage.daily(0, 30000, events)) === '[["b",6900,1],["a",5500,2]]', 'по приложениям: ' + shape(usage.daily(0, 30000, events)));
  expect(shape(usage.daily(3000, 30000, events)) === '[["b",6900,1],["a",3500,1]]', 'с середины: ' + shape(usage.daily(3000, 30000, events)));
  var sessions = JSON.stringify(usage.sessions(0, 30000, events));
  expect(sessions === '[{"from":1000,"to":12500,"packages":["a","b"]},{"from":20000,"to":23000,"packages":["a"]}]', 'сеансы: ' + sessions);
  if (!usage.access()) return 'на примере; к настоящей статистике доступа нет';
  var now = Date.now();
  var real = usage.daily(now - 3600000, now);
  expect(real.some(function (app) { return app.package === String(context.getPackageName()); }), 'Axiko нет среди приложений за час: ' + shape(real));
  return 'за час: ' + real.slice(0, 3).map(function (app) { return app.app + ' ' + Math.round(app.ms / 1000) + ' с'; }).join(', ') +
    '; сеансов ' + usage.sessions(now - 3600000, now).length;
});

check('speech: said into a file and its phrases found again', function () {
  if (!native('tts.local.status').ready) skip('голос не скачан');
  var files = require('lib/assi').files;
  var path = native('speech.render', 'Привет. Это проверка голоса в файле.', 'data/check-render.mp3', 'local');
  try {
    expect(path === 'data/check-render.wav', 'записано не туда: ' + path);
    var size = new java.io.File(String(host.path(path))).length();
    expect(size > 44 + 16000, 'звука почти нет: ' + size + ' байт');
    expect(failure(function () { native('speech.render', ' ', 'data/check-render.wav', 'local'); }).indexOf('Нечего озвучивать') >= 0, 'пустой текст принят');
    if (!native('speech.status').ready) return Math.round(size / 1024) + ' КБ; распознавать нечем: модель не скачана';
    var phrases = native('speech.transcribe', path, { segments: true });
    expect(phrases.length >= 1, 'фраз не найдено');
    phrases.forEach(function (phrase) { expect(phrase.endMs > phrase.startMs && phrase.text, 'фраза: ' + JSON.stringify(phrase)); });
    var heard = phrases.map(function (phrase) { return phrase.text; }).join(' | ');
    expect(/провер/i.test(heard), 'не узнано: ' + heard);
    return phrases.length + ' фраз: ' + heard;
  } finally {
    files.remove('data/check-render.wav');
  }
});

check('speech: said into a file by the cloud voice', function () {
  var tts = native('models.roles').tts;
  if (!tts || !tts.model) skip('облачный голос не назначен');
  if (String(target('tts').getBaseUrl()).indexOf('10.0.2.2') < 0) skip('тратить деньги облачного голоса на проверку не стоит: нужен подставной');
  var path = native('speech.render', 'Проверка облачного голоса.', 'data/check-cloud.ogg', 'cloud');
  try {
    var file = new java.io.File(String(host.path(path)));
    expect(file.length() > 44, 'файл пустой: ' + path);
    var input = new java.io.FileInputStream(file);
    var head = String(new java.lang.String(input.readNBytes(4), 'US-ASCII'));
    input.close();
    // Raw samples (what Gemini gives) are wrapped as WAV; an encoded answer keeps its own format.
    expect(path === 'data/check-cloud.wav' ? head === 'RIFF' : path !== 'data/check-cloud.ogg' || head === 'OggS', 'не тот файл: ' + path + ' начинается с ' + head);
    return path + ', ' + Math.round(file.length() / 1024) + ' КБ';
  } finally {
    require('lib/assi').files.remove(path);
  }
});

check('speech: the microphone kept open, then closed', function () {
  if (!native('speech.status').ready) skip('модель распознавания не скачана');
  if (context.checkSelfPermission('android.permission.RECORD_AUDIO') !== 0) skip('нет доступа к микрофону');
  var told = [];
  on('speech.stream', function (event) { told.push(event.active + (event.error ? ': ' + event.error : '')); }, 'check-stream');
  try {
    expect(native('speech.stream.start', { maxMinutes: 1 }) === true, 'не запущено');
    expect(native('speech.status').stream === true, 'в состоянии не видно');
    expect(failure(function () { native('speech.listen', 5); }).indexOf('Уже слушаю') >= 0, 'разовое слушание пустили поверх');
    sleep(1500);
    native('speech.stream.stop');
    expect(native('speech.status').stream === false, 'не остановлено');
    expect(waitFor(function () { return told.length >= 2; }, 3000), 'события: ' + told.join(', '));
    expect(told[0] === 'true' && told[told.length - 1] === 'false', 'события: ' + told.join(', '));
    return told.join(' → ');
  } finally {
    native('speech.stream.stop');
    off('speech.stream', 'check-stream');
  }
});

lines.join('\n') + '\n\nПрошло: ' + counts.pass + ', не прошло: ' + counts.fail + ', пропущено: ' + counts.skip;

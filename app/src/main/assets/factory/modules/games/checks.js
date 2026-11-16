// Checks of the hub «Игротека» on the phone (module_check 'games'; lib/check.js runs them in this
// order). They play a copy of the template, installed as modules/games/zz-check by the first
// check and removed by the last one with all it left: its saves, files, catalog marks, the
// mirror in the screen's storage. The copy's achievements are not for life and it asks for no
// stock or notifications, so nothing reaches the other modules.
var assi = require('lib/assi');
var files = assi.files;
var Core = require('./engine/core.js');
var Big = require('./engine/big.js');

var ID = 'zz-check';
var DIR = 'modules/games/' + ID;
var DATA = 'data/games/' + ID;
var TEMPLATE = 'modules/games/engine/template';

function main() { return require('./main.js'); }

function readJson(path, fallback) {
  try {
    return files.exists(path) ? JSON.parse(files.read(path)) : fallback;
  } catch (error) {
    return fallback;
  }
}

function writeJson(path, value) { files.write(path, JSON.stringify(value, null, 1)); }

/** Copies a folder of text files (the template has nothing else). */
function copyDir(from, to) {
  files.list(from).forEach(function (name) {
    name = String(name);
    if (name.charAt(name.length - 1) === '/') copyDir(from + '/' + name.slice(0, -1), to + '/' + name.slice(0, -1));
    else files.write(to + '/' + name, files.read(from + '/' + name));
  });
}

/** Everything the copy may have left, gone (also run first: a check that broke off earlier). */
function cleanup() {
  if (files.exists(DIR)) files.remove(DIR);
  if (files.exists(DATA)) files.remove(DATA);
  try {
    files.list('modules/games/archive').forEach(function (name) {
      name = String(name);
      if (name.indexOf(ID + '-') === 0) files.remove('modules/games/archive/' + name.replace(/\/$/, ''));
    });
  } catch (ignored) { /* no archive */ }
  // main.saves() makes the table when the hub has never saved anything.
  main().saves(ID);
  assi.db('games').exec('delete from saves where game = ?', [ID]);
  var cat = readJson('data/games/catalog.json', null);
  if (cat) {
    if (cat.opened) delete cat.opened[ID];
    if (cat.trial) delete cat.trial[ID];
    ['pinned', 'archived', 'order'].forEach(function (k) { if (cat[k]) cat[k] = cat[k].filter(function (x) { return x !== ID; }); });
    writeJson('data/games/catalog.json', cat);
  }
  var reported = readJson('data/games/reported.json', null);
  if (reported && reported[ID]) {
    delete reported[ID];
    writeJson('data/games/reported.json', reported);
  }
  var plays = readJson('data/games/links/plays.json', null);
  if (plays && plays.some(function (p) { return p.game === ID; })) writeJson('data/games/links/plays.json', plays.filter(function (p) { return p.game !== ID; }));
  var box = readJson('data/games/achievements-outbox.json', null);
  if (box && box.some(function (a) { return String(a.key).indexOf('games/' + ID + '/') === 0; })) {
    writeJson('data/games/achievements-outbox.json', box.filter(function (a) { return String(a.key).indexOf('games/' + ID + '/') !== 0; }));
  }
  var plan = readJson('data/games/notify.json', null);
  if (plan && plan.game === ID) writeJson('data/games/notify.json', {});
  var nav = readJson('ui/nav.json', null);
  if (nav && nav.items && nav.items.some(function (i) { return String(i.open).indexOf(DIR + '/') === 0; })) {
    nav.items = nav.items.filter(function (i) { return String(i.open).indexOf(DIR + '/') !== 0; });
    writeJson('ui/nav.json', nav);
  }
  try {
    var base = require('lib/base');
    base.find({ kind: 'play', since: Date.now() - 86400000, limit: 200 }).forEach(function (r) {
      var d = typeof r.data === 'string' ? readData(r.data) : r.data;
      if (d && d.game === ID) base.remove(r.id);
    });
  } catch (ignored) { /* no base */ }
}

function readData(text) {
  try {
    return JSON.parse(text);
  } catch (error) {
    return null;
  }
}

/** Waits until the code gives a truthy value in the screen (or fails with `message`). */
function until(t, page, code, message, tries) {
  var last = null;
  for (var i = 0; i < (tries || 40); i++) {
    last = page.eval(code, 0);
    if (last) return last;
    t.wait(250);
  }
  t.ok(false, message);
  return last;
}

function openGame(t) {
  var page = t.screen(DIR + '/ui/index.html');
  until(t, page, '!!(window.__game && window.__game.state)', 'игра не запустилась: нет window.__game');
  var error = page.text('.g-error');
  t.ok(!error, 'ошибка на экране игры: ' + error);
  return page;
}

exports['подготовка: копия шаблона zz-check'] = function (t) {
  cleanup();
  t.ok(files.exists(TEMPLATE + '/game.json'), 'нет шаблона ' + TEMPLATE);
  copyDir(TEMPLATE, DIR);
  var g = JSON.parse(files.read(DIR + '/game.json'));
  g.id = ID;
  g.title = 'Проверка Игротеки';
  g.stock = [];
  g.notify = [];
  g.status = 'active';
  g.achievements.forEach(function (a) { a.life = false; });
  files.write(DIR + '/game.json', JSON.stringify(g, null, 2));
  t.equal(main().game(ID).title, 'Проверка Игротеки', 'игра не видна движку');
};

exports['каталог открывается и показывает игру'] = function (t) {
  var page = t.screen('modules/games/ui/index.html');
  until(t, page, '!!document.querySelector(\'[data-game="' + ID + '"]\')', 'в каталоге нет карточки ' + ID);
  var title = page.text('[data-game="' + ID + '"]');
  t.ok(title && title.indexOf('Проверка Игротеки') >= 0, 'на карточке нет названия: ' + title);
  var cat = main().catalog();
  t.ok(cat.games.some(function (x) { return x.id === ID; }), 'catalog() не вернул игру');
  t.ok(Array.isArray(cat.modules), 'catalog() не вернул карточки других модулей');
  cat.modules.forEach(function (m) {
    t.ok(m.title && /^modules\//.test(m.open), 'карточка модуля ' + m.module + ' без названия или экрана: ' + JSON.stringify(m));
  });
};

exports['игра открывается'] = function (t) {
  var page = openGame(t);
  t.ok(page.count('#hammer') === 1, 'нет кнопки удара');
  t.ok(page.count('.g-actions button') >= 2, 'кнопок под пальцем меньше двух');
  var rows = main().saves(ID);
  t.ok(rows.length >= 1, 'новая игра не сохранилась');
};

exports['сохранение переживает перезагрузку'] = function (t) {
  var page = openGame(t);
  var before = page.eval('(async function(){for(var i=0;i<7;i++)document.querySelector("#hammer").click();' +
    'var r=await __game.saveNow("check");return {strikes:__game.state.run.strikes,ok:r.ok,seq:r.seq}})()', 0);
  t.ok(before && before.ok, 'сохранение не прошло: ' + JSON.stringify(before));
  t.ok(before.strikes >= 7, 'удары не засчитались: ' + before.strikes);
  page = openGame(t);
  var after = page.eval('__game.state.run.strikes', 0);
  t.equal(after, before.strikes, 'ударов после перезагрузки');
  var mirror = page.eval('(function(){var m=localStorage.getItem("games:' + ID + '");return m?JSON.parse(m).seq:0})()', 0);
  t.ok(mirror >= before.seq, 'запасная копия отстала: ' + mirror + ' < ' + before.seq);
};

exports['сводка «Пока вас не было» после пропуска'] = function (t) {
  var page = openGame(t);
  var r = page.eval('(async function(){__game.act(function(s){s.run.forges=3});var r=await __game.debug.offline(3);' +
    'var h=[].slice.call(document.querySelectorAll("h3")).map(function(x){return x.textContent});return {r:r,h:h,sparks:String(__game.state.run.sparks)}})()', 0);
  t.ok(r && r.r && !r.r.silent, 'пропуск не засчитан: ' + JSON.stringify(r));
  t.ok(r.r.gains > 0, 'за 3 часа ничего не накопилось');
  t.ok(r.h.some(function (x) { return x.indexOf('Пока вас не было') === 0; }), 'нет сводки, заголовки: ' + JSON.stringify(r.h));
  page.click('#g-offline-take');
};

exports['престиж: перековка'] = function (t) {
  var page = openGame(t);
  var r = page.eval('(async function(){var c=__game.config;__game.act(function(s){s.run.earned=Big.mul(c["ember.threshold"],4);s.run.forges=12});' +
    'var r=await __game.prestige("ember",{confirm:false});var s=__game.state;' +
    'return {ok:r.ok,gain:String(r.gain),embers:Big.str(s.layers.ember.currency),forges:s.run.forges,count:s.layers.ember.count,best:s.layers.ember.state.bestForges}})()', 0);
  t.ok(r && r.ok, 'перековка не прошла: ' + JSON.stringify(r));
  t.equal(r.embers, Big.str(Big.of(2)), 'уголь после перековки');
  t.equal(r.forges, 0, 'забег не сброшен');
  t.equal(r.best, 12, 'onPrestige не перенёс лучший забег');
  var reasons = main().saves(ID).slice(0, 4).map(function (x) { return x.reason; });
  t.ok(reasons.indexOf('prestige') >= 0 && reasons.indexOf('before-prestige') >= 0, 'нет сохранений престижа: ' + reasons.join(', '));
};

exports['старый seq не принимается'] = function (t) {
  var rows = main().saves(ID);
  t.ok(rows.length, 'нет сохранений');
  var r = main().save(ID, 1, '{"format":1}', 'check');
  t.ok(!r.ok && r.stale, 'seq 1 принят поверх ' + rows[0].seq);
  t.equal(main().saves(ID)[0].seq, rows[0].seq, 'последнее сохранение');
};

exports['файлы Игротеки без адресов в сети'] = function (t) {
  var list = [];
  function walk(path) {
    files.list(path).forEach(function (name) {
      name = String(name);
      var full = path + '/' + name.replace(/\/$/, '');
      if (name.charAt(name.length - 1) === '/') {
        // Games are checked by their own checks; the hub's code here.
        if (path === 'modules/games' && ['engine', 'ui', 'links'].indexOf(name.slice(0, -1)) < 0) return;
        walk(full);
      } else if (/\.(html?|js|css)$/.test(name)) {
        list.push({ path: full.slice('modules/games/'.length), text: files.read(full) });
      }
    });
  }
  walk('modules/games');
  var hits = Core.offlineScan(list);
  t.ok(!hits.length, 'адреса: ' + hits.map(function (h) { return h.path + ':' + h.line + ' ' + h.url; }).join('; '));
};

exports['картинки: спрайты по зерну, облик и SVG модели'] = function (t) {
  var Art = require('./engine/art.js');
  var a = Art.sprite({ seed: 'crawler', mask: 'invader', hue: 95, frames: 2 });
  var b = Art.sprite({ seed: 'crawler', mask: 'invader', hue: 95, frames: 2 });
  t.equal(Art.svg(a, { animate: true }), Art.svg(b, { animate: true }), 'одно зерно — один спрайт');
  t.ok(Art.svg(a, { frame: 0 }) !== Art.svg(a, { frame: 1 }), 'два кадра одинаковы');
  Art.masks.forEach(function (m) {
    var sp = Art.sprite({ seed: m, mask: m });
    t.ok(sp.w > 4 && sp.h > 4 && /<path fill="#[0-9a-f]{6}" d="M/.test(Art.svg(sp)), 'маска ' + m + ' пустая');
  });
  t.equal(Art.lookCheck({ mask: 'bug', hue: 200, light: 'pale' }), null, 'облик не принят');
  t.ok(Art.lookCheck({ mask: 'dragon', hue: 200 }), 'форма не из списка прошла');
  var good = Art.clean("<svg viewBox='0 0 64 64'><circle cx='32' cy='32' r='20' fill='#e0b04a'/></svg>", { size: 64, palette: ['#e0b04a'] });
  t.ok(good.svg && good.svg.indexOf('xmlns') > 0, 'чистый значок не прошёл: ' + good.error);
  ["<svg viewBox='0 0 64 64'><script>x()</script></svg>", "<svg viewBox='0 0 64 64'><circle r='3' onload='x()'/></svg>",
    "<svg viewBox='0 0 64 64'><image href='x.png'/></svg>", "<svg viewBox='0 0 64 64'><circle r='3' fill='url(x.png)'/></svg>",
    "<svg viewBox='0 0 64 64'><circle r='3' fill='#123456'/></svg>"].forEach(function (bad) {
    t.ok(Art.clean(bad, { size: 64, palette: ['#e0b04a'] }).error, 'прошло: ' + bad);
  });
  var sch = { key: 'title', fields: { title: { type: 'string' }, look: { type: 'look' }, icon: { type: 'svg', required: false, palette: ['#e0b04a'] } } };
  var item = { title: 'Клещ', look: { mask: 'bug', hue: 200 }, icon: "<svg viewBox='0 0 64 64'><rect x='1' y='1' width='9' height='9' fill='#e0b04a'/></svg>" };
  t.equal(Core.stock.check(item, sch, {}, null, 'x'), null, 'элемент с картинками отвергнут');
  t.ok(/^<svg xmlns=/.test(item.icon), 'значок не собран заново');
  var worse = { title: 'Жук', look: { mask: 'bug', hue: 20 }, icon: "<svg viewBox='0 0 64 64'><script/></svg>" };
  t.equal(Core.stock.check(worse, sch, {}, null, 'x'), null, 'плохой необязательный значок отверг весь элемент');
  t.ok(worse.icon === undefined, 'плохой значок остался');
  t.ok(Core.stock.check({ title: 'Оса', look: { mask: 'wasp', hue: 20 } }, sch, {}, null, 'x'), 'плохой облик прошёл');
};

exports['стандартные проверки игры (на копии шаблона)'] = function (t) {
  var checks = require(DIR + '/checks.js');
  var failed = [];
  Object.keys(checks).forEach(function (name) {
    if (typeof checks[name] !== 'function' || /экран/.test(name)) return;
    try {
      checks[name](t);
    } catch (error) {
      if (error && error.why !== undefined) return;
      failed.push(name + ': ' + (error && error.message ? error.message : error));
    }
  });
  t.ok(!failed.length, failed.join('; '));
};

exports['экран игры в рамке 344 и 690'] = function (t) {
  var page = t.screen('modules/games/ui/preview.html?game=' + ID + '&w=both');
  var r = until(t, page, '(function(){var f=document.querySelectorAll("iframe");var n=0;for(var i=0;i<f.length;i++){var w=f[i].contentWindow;if(w&&w.__game)n++}' +
    'return f.length===2&&n===2?{n:n,w:[].map.call(f,function(x){return x.clientWidth})}:null})()', 'игра не запустилась в обеих рамках');
  t.ok(r.w[0] >= 340 && r.w[0] <= 348, 'ширина узкой рамки ' + r.w[0]);
};

exports['проверки каждой установленной игры'] = function (t) {
  var failed = [];
  var ran = 0;
  main().games().forEach(function (g) {
    if (g.id === ID || !files.exists('modules/games/' + g.id + '/checks.js')) return;
    var checks;
    try {
      checks = require('modules/games/' + g.id + '/checks.js');
    } catch (error) {
      failed.push(g.id + ': checks.js не загрузился: ' + (error && error.message ? error.message : error));
      return;
    }
    Object.keys(checks).forEach(function (name) {
      if (typeof checks[name] !== 'function') return;
      ran++;
      try {
        checks[name](t);
      } catch (error) {
        if (error && error.why !== undefined) return;
        failed.push(g.id + ' / ' + name + ': ' + (error && error.message ? error.message : error));
      }
    });
  });
  if (!ran) t.skip('других игр с checks.js нет');
  t.ok(!failed.length, failed.join('; '));
};

exports['уборка'] = function (t) {
  var page = t.screen('modules/games/ui/index.html');
  page.eval('localStorage.removeItem("games:' + ID + '")', 0);
  cleanup();
  t.ok(!files.exists(DIR), 'папка копии осталась');
  t.ok(!files.exists(DATA), 'данные копии остались');
  t.equal(main().saves(ID).length, 0, 'сохранений копии');
  t.ok(!page.eval('localStorage.getItem("games:' + ID + '")', 0), 'запасная копия осталась');
};

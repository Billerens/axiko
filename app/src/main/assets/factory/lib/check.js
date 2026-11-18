// Checks of a module: does its logic work, do its screens open and respond.
//
// A module keeps them in modules/<id>/checks.js — an object of named functions, each given a
// helper:
//
//   exports['добавляет заметку'] = function (t) {
//     var notes = require('modules/notes/main.js');
//     var before = notes.list().length;
//     notes.add('проверка');
//     t.equal(notes.list().length, before + 1);
//   };
//   exports['экран показывает заметки'] = function (t) {
//     var page = t.screen('modules/notes/ui/index.html');   // fails if the screen has errors
//     page.type('#text', 'с экрана');
//     page.click('#add');
//     t.ok(page.count('.note') > 0, 'заметка не появилась');
//   };
//
// The tool module_check runs them; the app also runs them by itself after a turn that changed
// the module, and shows the outcome in the chat. Checks must clean up after themselves: what
// they add to the module's data, they remove.
var files = require('lib/axiko').files;

function Failure(message) { this.message = message; }
function Skipped(why) { this.why = why; }

function value(text) {
  text = String(text);
  if (text.indexOf('Ошибка: ') === 0) throw new Failure(text);
  try { return JSON.parse(text); } catch (error) { return text; }
}

function helper() {
  return {
    ok: function (condition, message) { if (!condition) throw new Failure(message || 'условие не выполнено'); },
    equal: function (actual, expected, message) {
      var a = JSON.stringify(actual);
      var b = JSON.stringify(expected);
      if (a !== b) throw new Failure((message ? message + ': ' : '') + 'ожидалось ' + b + ', получено ' + a);
    },
    throws: function (fn, message) {
      try { fn(); } catch (error) { return; }
      throw new Failure(message || 'ожидалась ошибка, а её не было');
    },
    skip: function (why) { throw new Skipped(why || ''); },
    wait: function (ms) { sleep(ms); },
    /** Opens a screen of the app; a screen that does not load or logs errors fails the check. */
    screen: function (path) {
      var opened = native('ui.openWait', path);
      if (!opened.loaded) throw new Failure('экран ' + path + ' не загрузился');
      if (opened.errors.length) throw new Failure('ошибки на экране: ' + opened.errors.join('; '));
      var page = {
        /** Runs code in the screen; a promise it returns is waited for. */
        eval: function (code, waitMs) { return value(native('ui.eval', String(code), waitMs === undefined ? 400 : waitMs)); },
        text: function (selector) {
          return page.eval('(function(){var n=document.querySelector(' + JSON.stringify(selector) + ');return n?n.textContent.trim():null})()', 0);
        },
        count: function (selector) { return page.eval('document.querySelectorAll(' + JSON.stringify(selector) + ').length', 0); },
        click: function (selector) {
          var found = page.eval('(function(){var n=document.querySelector(' + JSON.stringify(selector) + ');if(!n)return false;n.click();return true})()');
          if (!found) throw new Failure('на экране нет элемента ' + selector);
        },
        type: function (selector, text) {
          var found = page.eval('(function(){var n=document.querySelector(' + JSON.stringify(selector) + ');if(!n)return false;n.value=' +
            JSON.stringify(String(text)) + ';n.dispatchEvent(new Event("input",{bubbles:true}));n.dispatchEvent(new Event("change",{bubbles:true}));return true})()');
          if (!found) throw new Failure('на экране нет поля ' + selector);
        }
      };
      return page;
    }
  };
}

/** Runs the module's checks: {module, missing?, results: [{name, ok, skipped?, error?, ms}]}. */
exports.run = function (id) {
  var path = 'modules/' + id + '/checks.js';
  if (!files.exists(path)) return { module: id, missing: true, results: [] };
  var checks;
  try {
    checks = require(path);
  } catch (error) {
    return { module: id, results: [{ name: 'checks.js', ok: false, error: 'не загрузился: ' + (error && error.message ? error.message : error), ms: 0 }] };
  }
  var results = Object.keys(checks).filter(function (name) { return typeof checks[name] === 'function'; }).map(function (name) {
    var started = Date.now();
    try {
      checks[name](helper());
      return { name: name, ok: true, ms: Date.now() - started };
    } catch (error) {
      if (error instanceof Skipped) return { name: name, ok: true, skipped: true, error: error.why, ms: 0 };
      return { name: name, ok: false, error: error instanceof Failure ? error.message : String(error && error.message ? error.message : error), ms: Date.now() - started };
    }
  });
  return { module: id, results: results };
};

/** The outcome as text: for the agent, and for the owner in the chat. */
exports.report = function (outcome) {
  if (outcome.missing) return 'У модуля ' + outcome.module + ' нет проверок: напишите modules/' + outcome.module + '/checks.js (как — см. lib/check.js).';
  var failed = outcome.results.filter(function (item) { return !item.ok; });
  var lines = outcome.results.map(function (item) {
    if (item.skipped) return 'пропущено: ' + item.name + (item.error ? ' — ' + item.error : '');
    return item.ok ? 'прошло: ' + item.name : 'НЕ ПРОШЛО: ' + item.name + ' — ' + item.error;
  });
  return 'Проверки модуля ' + outcome.module + ': прошло ' + (outcome.results.length - failed.length) + ' из ' + outcome.results.length + '\n' + lines.join('\n');
};

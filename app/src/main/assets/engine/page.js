// Injected into every local page before its own scripts: window.assi, the link to the app.
(function () {
  if (window.assi) return;
  var seq = 0;
  var pending = {};
  var listeners = {};
  var queue = [];
  var port = null;

  function attach() {
    if (port) return true;
    if (!window.__assi) return false;
    port = window.__assi;
    port.onmessage = function (event) {
      var message = JSON.parse(event.data);
      if (message.event) {
        (listeners[message.event] || []).slice().forEach(function (fn) {
          try { fn(message.data); } catch (error) { console.error(error); }
        });
        return;
      }
      var waiting = pending[message.id];
      if (!waiting) return;
      delete pending[message.id];
      if (message.error) waiting[1](new Error(message.error));
      else waiting[0](message.result === undefined ? undefined : JSON.parse(message.result));
    };
    queue.splice(0).forEach(function (text) { port.postMessage(text); });
    return true;
  }

  function send(op, payload) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = [resolve, reject];
      payload.id = id;
      payload.op = op;
      var text = JSON.stringify(payload);
      if (attach()) port.postMessage(text);
      else queue.push(text);
    });
  }

  var rest = function (args, from) { return Array.prototype.slice.call(args, from); };

  window.assi = {
    /** Calls fn of a workspace module in the engine: assi.call('modules/notes/main.js', 'list'). */
    call: function (path, fn) {
      return send('call', { path: path, fn: fn, args: JSON.stringify(rest(arguments, 2)) });
    },
    /** Runs code in the engine (all of Android is there) and returns its value. */
    eval: function (code) { return send('eval', { code: code }); },
    /** A shell operation: assi.native('agent.send', 'assi', 'привет'). */
    native: function (method) {
      return send('native', { method: method, args: JSON.stringify(rest(arguments, 1)) });
    },
    on: function (event, fn) {
      (listeners[event] = listeners[event] || []).push(fn);
      return function () { window.assi.off(event, fn); };
    },
    off: function (event, fn) {
      listeners[event] = (listeners[event] || []).filter(function (item) { return item !== fn; });
    },
    open: function (path) { return window.assi.native('ui.open', path); },
    back: function () { return window.assi.native('ui.back'); },
    chat: function (open) { return window.assi.native('ui.chat', open !== false); },
    /** The whole display for this screen: no chat button, no menu, no system bars. Ends on "back" or another screen. */
    stage: function (on) { return window.assi.native('ui.stage', on !== false); }
  };

  // A screen that throws shows a small "mend" button: one tap hands the errors to the main agent.
  // It lives here, outside the workspace, so it works on any screen the agent wrote.
  var faults = [];

  function fault(text) {
    console.error(text);
    if (location.pathname.indexOf('/ui/chat/') === 0) return;
    faults.push(text);
    if (faults.length > 1 || !document.body) return;
    var button = document.createElement('button');
    button.textContent = 'Экран работает с ошибкой — починить';
    button.style.cssText = 'position:fixed;left:16px;bottom:22px;z-index:2147483647;max-width:calc(100% - 110px);' +
      'min-height:44px;padding:0 16px;border:0;border-radius:22px;background:#3A1F21;color:#FF7B72;' +
      'font:600 14px system-ui,sans-serif;text-align:left';
    button.onclick = function () {
      button.remove();
      window.assi.native('ui.fix', location.pathname.replace(/^\//, ''), faults.slice(0, 8).join('\n'));
    };
    document.body.appendChild(button);
  }

  window.addEventListener('error', function (event) {
    fault('Ошибка страницы: ' + event.message + ' (' + (event.filename || '').replace(location.origin + '/', '') + ':' + event.lineno + ')');
  });
  window.addEventListener('unhandledrejection', function (event) {
    var reason = event.reason;
    fault('Необработанная ошибка: ' + (reason && reason.message ? reason.message : reason));
  });

  if (!attach()) {
    var tries = 0;
    var timer = setInterval(function () {
      if (attach() || ++tries > 100) clearInterval(timer);
    }, 20);
  }
  send('hello', {});
})();

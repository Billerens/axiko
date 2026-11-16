// Browser equivalent of window.assi. Nested widgets share the surrounding hub's transport.
(function () {
  'use strict';
  if (window.assi) return;

  var listeners = Object.create(null);
  var unsubscribe = null;
  var connectionEpoch = 0;
  var disposed = false;
  var scriptPromise = null;
  var faults = [];
  var authNotice = null;
  var visibilityValues = new Map();
  var visibilityTimer = null;
  var forceVisibility = false;
  var rootPath = /^\/(?:ui\/desk\/(?:index\.html)?)?$/.test(location.pathname);
  if (rootPath) window.__assiTransportRoot = true;

  function ancestors() {
    var result = [], current = window;
    while (current) {
      try {
        // Reading location first prevents an inaccessible cross-origin ancestor from leaking through.
        void current.location.pathname;
        result.push(current);
        if (current.parent === current) break;
        current = current.parent;
      } catch (error) { break; }
    }
    return result;
  }

  function desk() {
    var chain = ancestors();
    for (var i = 0; i < chain.length; i++) {
      if (chain[i].assiDesk && typeof chain[i].assiDesk.rpc === 'function' && typeof chain[i].assiDesk.subscribe === 'function') return chain[i].assiDesk;
    }
    return null;
  }

  function available() {
    var host = desk();
    if (host) return host;
    var chain = ancestors();
    for (var i = 0; i < chain.length; i++) if (chain[i].AssiTransport) return chain[i].AssiTransport;
    return null;
  }

  function waitForHost(host) {
    return new Promise(function (resolve, reject) {
      var timer = null, deadline = null;
      function clear() {
        if (timer !== null) clearTimeout(timer);
        if (deadline !== null) clearTimeout(deadline);
        host.removeEventListener('assi-transport-ready', check);
      }
      function check() {
        var ready = available();
        if (ready) { clear(); resolve(ready); return; }
        if (disposed) { clear(); reject(new Error('Экран закрыт.')); return; }
        if (timer !== null) clearTimeout(timer);
        timer = setTimeout(check, 30);
      }
      host.addEventListener('assi-transport-ready', check);
      deadline = setTimeout(function () {
        clear();
        reject(new Error('Web-оболочка не подключилась. Обновите страницу или откройте заводской вариант.'));
      }, 15000);
      check();
    });
  }

  function provider() {
    var ready = available();
    if (ready) return Promise.resolve(ready);
    var chain = ancestors();
    for (var i = 0; i < chain.length; i++) {
      if (chain[i].__assiTransportRoot) return waitForHost(chain[i]);
    }
    // A module opened directly, outside the hub, still works and owns exactly one connection.
    if (!scriptPromise) {
      scriptPromise = new Promise(function (resolve, reject) {
        var script = document.createElement('script');
        script.src = '/ui/desk/transport.js';
        script.onload = function () {
          if (window.AssiTransport) resolve(window.AssiTransport);
          else { scriptPromise = null; reject(new Error('Транспорт Axiko не загрузился.')); }
        };
        script.onerror = function () { scriptPromise = null; reject(new Error('Не удалось подключиться к Axiko.')); };
        (document.head || document.documentElement).appendChild(script);
      });
    }
    return scriptPromise;
  }

  function draftOf(path) {
    var found = /^\/?~([a-z0-9]+)(?:\/|$)/.exec(String(path || ''));
    return found ? found[1] : null;
  }

  function where() {
    var host = desk();
    if (host && typeof host.where === 'function') return String(host.where() || '').replace(/^\//, '');
    return location.pathname.replace(/^\//, '');
  }

  function currentDraft() {
    var chain = ancestors();
    for (var i = 0; i < chain.length; i++) {
      var ownDraft = draftOf(chain[i].location.pathname);
      if (ownDraft) return ownDraft;
      if (chain[i].__assiTransportRoot) break;
    }
    // The chat refers to the selected preview. A live module keeps its own context even
    // when another pane is showing a draft; absolute widget URLs inherit their parent.
    var plain = location.pathname.replace(/^\/~[a-z0-9]+\//, '/');
    return rootPath || plain.indexOf('/ui/chat/') === 0 ? draftOf(where()) : null;
  }

  function contextualPath(path) {
    path = String(path || '').replace(/^\/+/, '');
    if (/^[a-z][a-z0-9+.-]*:/i.test(path)) throw new Error('Откройте внешний адрес обычной ссылкой.');
    var draft = currentDraft();
    return draft && path.charAt(0) !== '~' ? '~' + draft + '/' + path : path;
  }

  async function send(payload) {
    var out = Object.assign({}, payload);
    if (!out.where) out.where = where();
    if (!Object.prototype.hasOwnProperty.call(out, 'draft')) out.draft = currentDraft();
    return (await provider()).rpc(out);
  }

  function emit(name, data) {
    (listeners[name] || []).slice().forEach(function (listener) {
      try { listener(data); } catch (error) { console.error('Axiko page listener:', error); }
    });
  }

  // A browser game belongs to its visible browser pane, independently of the phone's Activity.
  function browserVisible() {
    if (disposed) return false;
    var current = window;
    while (current) {
      try {
        if (current.document.hidden || current.document.visibilityState === 'hidden') return false;
        var frame = current.frameElement;
        if (frame) {
          if (frame.hidden) return false;
          var rectangles = frame.getClientRects();
          var hasArea = false;
          for (var i = 0; i < rectangles.length; i++) {
            if (rectangles[i].width > 0 && rectangles[i].height > 0) { hasArea = true; break; }
          }
          if (!hasArea) return false;
          if (current.parent && typeof current.parent.getComputedStyle === 'function') {
            var style = current.parent.getComputedStyle(frame);
            if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return false;
          }
        }
        if (current.parent === current) break;
        current = current.parent;
      } catch (error) { break; }
    }
    return true;
  }

  function publishVisibility(force) {
    var on = browserVisible();
    (listeners['app.visible'] || []).slice().forEach(function (listener) {
      if (!force && visibilityValues.has(listener) && visibilityValues.get(listener) === on) return;
      visibilityValues.set(listener, on);
      try { listener({ on: on }); } catch (error) { console.error('Axiko visibility listener:', error); }
    });
  }

  function scheduleVisibility(force) {
    forceVisibility = forceVisibility || !!force;
    if (visibilityTimer !== null) return;
    // Run after the game's own document.visibilitychange handlers: when the browser tab
    // returns, a hidden Studio iframe must remain paused even if its document is visible.
    visibilityTimer = setTimeout(function () {
      visibilityTimer = null;
      var forced = forceVisibility;
      forceVisibility = false;
      publishVisibility(forced);
    }, 0);
  }

  function showAuth() {
    if (desk() || rootPath || authNotice) return;
    function draw() {
      if (!document.body || authNotice) return;
      authNotice = document.createElement('a');
      authNotice.href = '/';
      authNotice.target = '_top';
      authNotice.textContent = 'Нужно войти снова';
      authNotice.style.cssText = 'position:fixed;bottom:16px;left:16px;z-index:2147483647;padding:12px 18px;background:#252525;color:#ededed;border:1px solid #555;border-radius:12px;font:15px system-ui';
      document.body.appendChild(authNotice);
    }
    if (document.body) draw(); else document.addEventListener('DOMContentLoaded', draw, { once: true });
  }

  function connect() {
    var ticket = ++connectionEpoch;
    provider().then(function (host) {
      if (disposed || ticket !== connectionEpoch) return;
      if (unsubscribe) unsubscribe();
      unsubscribe = host.subscribe(function (event) {
        if (disposed || ticket !== connectionEpoch) return;
        if (event.event === 'app.visible') return;
        if (event.event === 'desk.visibility') publishVisibility(false);
        if (event.event === 'connection' && event.data && event.data.state === 'auth-required') showAuth();
        emit(event.event, event.data);
      });
    }).catch(function (error) {
      if (!disposed) console.warn('Axiko: ' + error.message);
    });
  }

  function openPath(path, preserveDraft) {
    var next = preserveDraft === false ? String(path).replace(/^\/+/, '') : contextualPath(path);
    var host = desk();
    if (host && typeof host.open === 'function') host.open(next);
    else location.href = '/' + next;
    return true;
  }

  function reportReady() {
    var host = desk();
    // DOM readiness makes the screen usable even while an optional image is still loading.
    // The hub validates the sending frame and its current route before clearing its timer.
    if (host && typeof host.ready === 'function') host.ready(window, location.pathname + location.search);
    return true;
  }

  var local = {
    'ui.open': function (path) { return openPath(path); },
    'ui.back': function () {
      var host = desk();
      if (host && typeof host.back === 'function') host.back(); else history.back();
      return true;
    },
    'ui.chat': function (visible, agent) {
      var host = desk();
      if (host && typeof host.chat === 'function') host.chat(visible !== false, agent);
      else if (visible !== false) {
        if (agent) localStorage.setItem('chat.agent', agent);
        location.href = '/ui/chat/index.html';
      }
      return true;
    },
    'ui.stage': function (on) {
      var host = desk();
      if (host && typeof host.stage === 'function') host.stage(!!on);
      else if (document.documentElement) document.documentElement.classList.toggle('assi-stage', !!on);
      return true;
    },
    'ui.ready': reportReady,
    'ui.reload': function () { location.reload(); return true; },
    'ui.where': function () {
      var path = where();
      var plain = path.replace(/^~[a-z0-9]+\//, '');
      if (plain.indexOf('modules/') !== 0) return Promise.resolve({ path: path, title: '' });
      var id = plain.split('/')[1];
      return send({
        op: 'eval',
        code: '(function(){var p=' + JSON.stringify(path) + ';try{var m=require("lib/assi").files.readJson(' +
          JSON.stringify('modules/' + id + '/module.json') + ',{});return {path:p,title:m.title||' +
          JSON.stringify(id) + '};}catch(e){return {path:p,title:' + JSON.stringify(id) + '};}})()'
      });
    },
    'draft.open': async function (id, path) {
      id = String(id);
      if (!/^[a-z0-9]+$/.test(id)) throw new Error('Некорректный черновик.');
      var all = await send({ op: 'native', method: 'draft.list', args: '[]' });
      var draft = (all || []).find(function (item) { return item.id === id; });
      if (!draft) throw new Error('Черновик больше не существует.');
      var entry = path || draft.open || ((draft.files || []).find(function (file) { return /\.html?$/i.test(file.path); }) || {}).path || 'ui/home/index.html';
      entry = String(entry).replace(/^\/+/, '').replace(/^~[a-z0-9]+\//, '');
      return openPath('~' + id + '/' + entry, false);
    }
  };

  window.assi = {
    protocol: 1,
    platform: 'web',
    call: function (path, fn) {
      return send({ op: 'call', path: path, fn: fn, args: JSON.stringify(Array.prototype.slice.call(arguments, 2)) });
    },
    eval: function (code) { return send({ op: 'eval', code: String(code) }); },
    native: function (method) {
      var args = Array.prototype.slice.call(arguments, 1);
      if (local[method]) {
        try { return Promise.resolve(local[method].apply(null, args)); } catch (error) { return Promise.reject(error); }
      }
      return send({ op: 'native', method: method, args: JSON.stringify(args) });
    },
    on: function (event, listener) {
      (listeners[event] || (listeners[event] = [])).push(listener);
      if (event === 'app.visible') scheduleVisibility(false);
      return function () { window.assi.off(event, listener); };
    },
    off: function (event, listener) {
      listeners[event] = (listeners[event] || []).filter(function (item) { return item !== listener; });
      if (event === 'app.visible') visibilityValues.delete(listener);
    },
    open: function (path) { return window.assi.native('ui.open', path); },
    back: function () { return window.assi.native('ui.back'); },
    chat: function (open) { return window.assi.native('ui.chat', open !== false); },
    stage: function (on) { return window.assi.native('ui.stage', on); }
  };
  window.__assiEmit = function (name, data) {
    if (name === 'app.visible' || name === 'desk.visibility') {
      publishVisibility(false);
      if (name === 'app.visible') return;
    }
    emit(name, data);
  };

  function fault(text) {
    console.error(text);
    if (location.pathname.indexOf('/ui/chat/') >= 0) return;
    faults.push(text);
    if (faults.length !== 1 || !document.body) return;
    var button = document.createElement('button');
    button.textContent = 'Экран работает с ошибкой — починить';
    button.style.cssText = 'position:fixed;left:16px;bottom:22px;z-index:2147483647;min-height:44px;padding:0 16px;border:0;' +
      'border-radius:22px;background:#3A1F21;color:#FF7B72;font:600 14px system-ui,sans-serif;cursor:pointer';
    button.onclick = function () {
      button.remove();
      var host = desk();
      if (host && host.chat) host.chat(true, 'assi');
      window.assi.native('ui.fix', location.pathname.replace(/^\//, ''), faults.slice(0, 8).join('\n')).catch(function (error) {
        console.warn(error.message);
      });
    };
    document.body.appendChild(button);
  }
  window.addEventListener('error', function (event) {
    fault('Ошибка страницы: ' + event.message + ' (' + (event.filename || '').replace(location.origin + '/', '') + ':' + event.lineno + ')');
  });
  window.addEventListener('unhandledrejection', function (event) {
    var reason = event.reason;
    if (reason && reason.code === 'AUTH_REQUIRED') { showAuth(); return; }
    fault('Необработанная ошибка: ' + (reason && reason.message ? reason.message : reason));
  });
  document.addEventListener('visibilitychange', function () { scheduleVisibility(true); });
  if (document.readyState === 'interactive' || document.readyState === 'complete') reportReady();
  else document.addEventListener('DOMContentLoaded', reportReady, { once: true });
  window.addEventListener('resize', function () { scheduleVisibility(false); });
  window.addEventListener('pagehide', function () {
    disposed = true;
    if (visibilityTimer !== null) clearTimeout(visibilityTimer);
    visibilityTimer = null; forceVisibility = false;
    publishVisibility(false);
    connectionEpoch++;
    if (unsubscribe) unsubscribe();
    unsubscribe = null;
  });
  window.addEventListener('pageshow', function (event) {
    if (event.persisted) { disposed = false; connect(); scheduleVisibility(true); reportReady(); }
  });
  connect();
})();

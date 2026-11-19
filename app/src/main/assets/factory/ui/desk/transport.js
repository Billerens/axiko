// One HTTP/SSE connection hub per browser workspace. Frames share it through axikoDesk.
(function () {
  'use strict';
  if (window.AxikoTransport) return;

  var subscribers = new Set();
  var statusListeners = new Set();
  var controllers = new Set();
  var source = null;
  var closed = false;
  var suspended = false;
  var opened = false;
  var sequence = 0;
  var status = { state: 'connecting', connected: false, since: Date.now(), reconnects: 0 };
  var DEFAULT_TIMEOUT = 60000;

  // The stream can die without a word: the phone's network blinks, the computer sleeps, and the
  // browser goes on holding a connection nobody writes to. The phone pings every `pingEvery`
  // (its hello says how often); a stream that has said nothing for a few pings is opened anew.
  // A phone that never said hello pings in a way a page cannot see: its stream is not watched.
  // A stream the browser gave up on (an error page instead of events) is not retried by the
  // browser itself: that is done here as well, a little later each time.
  var pingEvery = 0;
  var lastHeard = 0;
  var watchdog = null;
  var retryTimer = null;
  var refusals = 0;
  var STALE_PINGS = 2.5;

  function notify(event) {
    subscribers.forEach(function (listener) {
      try { listener(event); } catch (error) { console.error('Axiko event listener:', error); }
    });
  }

  function setStatus(state, error) {
    if (status.state === state && status.error === error) return;
    status = {
      state: state, connected: state === 'online', since: Date.now(),
      reconnects: status.reconnects, error: error || null
    };
    statusListeners.forEach(function (listener) {
      try { listener(Object.assign({}, status)); } catch (failure) { console.error('Axiko connection listener:', failure); }
    });
    notify({ event: 'connection', data: Object.assign({}, status) });
  }

  function stopEvents() {
    if (source) source.close();
    source = null;
    if (retryTimer !== null) clearTimeout(retryTimer);
    retryTimer = null;
  }

  function heard() { lastHeard = Date.now(); }

  /** Nothing from the stream for longer than `limit` (by default a few of its pings): it is gone. */
  function stale(limit) {
    if (!pingEvery) return false;
    return !source || Date.now() - lastHeard > (limit || pingEvery * STALE_PINGS);
  }

  function restart() {
    if (closed || suspended || status.state === 'auth-required') return;
    stopEvents();
    startEvents();
  }

  function watch() {
    if (watchdog !== null || closed) return;
    watchdog = setTimeout(function () {
      watchdog = null;
      if (closed) return;
      if (!suspended && status.state !== 'auth-required' && source && stale()) restart();
      watch();
    }, pingEvery ? Math.max(300, Math.min(5000, pingEvery / 3)) : 5000);
  }

  /** When the owner comes back (the tab shown, the window focused, the network up), a doubtful stream is opened anew at once. */
  function verify() {
    if (closed || suspended || status.state === 'auth-required') return;
    if (stale(pingEvery + Math.min(5000, pingEvery))) restart();
  }

  function startEvents() {
    if (closed || suspended || source || status.state === 'auth-required') return;
    setStatus('connecting');
    var active = new EventSource('/__axiko/events');
    source = active;
    // The attempt itself counts: a stream that never opens is caught by the same watch.
    heard();
    watch();
    active.addEventListener('hello', function (message) {
      if (source !== active) return;
      heard();
      try {
        var hello = JSON.parse(message.data);
        if (hello && hello.ping > 0) {
          pingEvery = Math.min(Math.max(Number(hello.ping), 1000), 120000);
          // The watch keeps pace with the period the phone named.
          if (watchdog !== null) clearTimeout(watchdog);
          watchdog = null;
          watch();
        }
      } catch (error) { /* an older phone: the usual period */ }
    });
    active.addEventListener('ping', function () { if (source === active) heard(); });
    active.onopen = function () {
      if (source !== active) return;
      heard();
      refusals = 0;
      var reconnect = opened;
      if (reconnect) status.reconnects++;
      opened = true;
      setStatus('online');
      if (reconnect) notify({
        event: 'desk.reconnected',
        data: { at: Date.now(), reconnects: status.reconnects }
      });
    };
    active.onerror = function () {
      if (source !== active || closed || suspended) return;
      // EventSource owns its retry policy for a lost connection. RPCs are never retried automatically.
      setStatus('offline', 'Связь с телефоном потеряна. Подключаюсь снова…');
      // A refused stream is closed for good by the browser: it is opened again from here.
      if (active.readyState === 2 && retryTimer === null) {
        var delay = Math.min(30000, 1000 * Math.pow(2, refusals++));
        retryTimer = setTimeout(function () {
          retryTimer = null;
          if (source === active) restart();
        }, delay);
      }
    };
    active.onmessage = function (message) {
      if (source !== active) return;
      heard();
      try {
        var event = JSON.parse(message.data);
        if (event && typeof event.event === 'string') notify(event);
      } catch (error) {
        console.warn('Axiko: не удалось прочитать событие:', error.message);
      }
    };
  }

  function failure(message, code, httpStatus) {
    var error = new Error(message);
    error.code = code;
    if (httpStatus) error.status = httpStatus;
    return error;
  }

  async function rpc(payload, options) {
    if (closed) throw failure('Соединение закрыто.', 'CLOSED');
    if (status.state === 'auth-required') throw failure('Нужно войти снова.', 'AUTH_REQUIRED', 401);
    options = options || {};
    var timeoutMs = options.timeoutMs === undefined ? DEFAULT_TIMEOUT : Number(options.timeoutMs);
    var controller = new AbortController();
    controllers.add(controller);
    var timeout = null;
    var timedOut = false;
    var body = Object.assign({}, payload);
    body.requestId = body.requestId || 'web-' + Date.now().toString(36) + '-' + (++sequence).toString(36);
    if (timeoutMs > 0) timeout = setTimeout(function () {
      timedOut = true;
      controller.abort();
    }, timeoutMs);
    try {
      var response = await fetch('/__axiko/rpc', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body), signal: controller.signal
      });
      if (response.status === 401) {
        stopEvents();
        setStatus('auth-required', 'Нужно войти снова.');
        throw failure('Сеанс доступа закончился. Войдите снова; несохранённый текст остаётся здесь.', 'AUTH_REQUIRED', 401);
      }
      if (!response.ok) throw failure('Телефон ответил с ошибкой HTTP ' + response.status + '.', 'HTTP_' + response.status, response.status);
      var answer;
      try { answer = await response.json(); } catch (error) {
        throw failure('Ответ телефона не удалось прочитать.', 'INVALID_RESPONSE', response.status);
      }
      if (answer.error) {
        var remoteError = failure(typeof answer.error === 'string' ? answer.error : answer.error.message || 'Запрос не выполнен.',
          answer.code || (answer.error && answer.error.code) || 'REMOTE_ERROR', response.status);
        if (answer.revision !== undefined) remoteError.revision = answer.revision;
        throw remoteError;
      }
      if (answer.result === undefined) return undefined;
      if (typeof answer.result !== 'string') return answer.result;
      try { return JSON.parse(answer.result); } catch (error) {
        throw failure('Телефон вернул некорректный результат.', 'INVALID_RESULT', response.status);
      }
    } catch (error) {
      if (timedOut) {
        var timeoutError = failure('Ответ не получен вовремя. Операция могла выполниться на телефоне; проверьте результат перед повтором.', 'TIMEOUT');
        timeoutError.mayHaveCompleted = true;
        timeoutError.requestId = body.requestId;
        throw timeoutError;
      }
      if (error && error.code) throw error;
      var networkError = failure(closed ? 'Соединение закрыто.' : 'Нет ответа от телефона. Проверьте соединение.', closed ? 'CLOSED' : 'NETWORK');
      networkError.mayHaveCompleted = true;
      networkError.requestId = body.requestId;
      throw networkError;
    } finally {
      if (timeout !== null) clearTimeout(timeout);
      controllers.delete(controller);
    }
  }

  var transport = {
    protocol: 1,
    rpc: rpc,
    native: function (method) {
      return rpc({ op: 'native', method: method, args: JSON.stringify(Array.prototype.slice.call(arguments, 1)) });
    },
    call: function (path, fn) {
      return rpc({ op: 'call', path: path, fn: fn, args: JSON.stringify(Array.prototype.slice.call(arguments, 2)) });
    },
    eval: function (code) { return rpc({ op: 'eval', code: String(code) }); },
    subscribe: function (listener) {
      subscribers.add(listener);
      return function () { subscribers.delete(listener); };
    },
    onStatus: function (listener) {
      statusListeners.add(listener);
      listener(Object.assign({}, status));
      return function () { statusListeners.delete(listener); };
    },
    reconnect: function () {
      if (closed || status.state === 'auth-required') return;
      stopEvents();
      startEvents();
    },
    /** Opens the stream anew if it has been quiet for longer than its pings allow. */
    verify: verify,
    // Explicit login keeps a failed request from repeatedly reloading unsaved editors.
    login: function () { window.location.assign('/'); },
    close: function () {
      if (closed) return;
      closed = true;
      stopEvents();
      if (watchdog !== null) clearTimeout(watchdog);
      watchdog = null;
      controllers.forEach(function (controller) { controller.abort(); });
      controllers.clear();
      setStatus('closed');
      subscribers.clear();
      statusListeners.clear();
    }
  };
  Object.defineProperty(transport, 'status', { get: function () { return Object.assign({}, status); } });
  window.AxikoTransport = transport;
  window.dispatchEvent(new CustomEvent('axiko-transport-ready'));
  startEvents();
  window.addEventListener('pagehide', function () {
    suspended = true;
    stopEvents();
  });
  window.addEventListener('pageshow', function (event) {
    if (event.persisted && !closed) {
      suspended = false;
      startEvents();
    }
  });
  window.addEventListener('online', verify);
  window.addEventListener('focus', verify);
  if (window.document && typeof window.document.addEventListener === 'function') {
    window.document.addEventListener('visibilitychange', function () {
      if (window.document.visibilityState !== 'hidden') verify();
    });
  }
})();

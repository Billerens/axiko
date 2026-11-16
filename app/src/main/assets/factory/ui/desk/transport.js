// One HTTP/SSE connection hub per browser workspace. Frames share it through assiDesk.
(function () {
  'use strict';
  if (window.AssiTransport) return;

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
  }

  function startEvents() {
    if (closed || suspended || source || status.state === 'auth-required') return;
    setStatus('connecting');
    var active = new EventSource('/__assi/events');
    source = active;
    active.onopen = function () {
      if (source !== active) return;
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
      // EventSource owns its retry policy. RPCs are never retried automatically.
      setStatus('offline', 'Связь с телефоном потеряна. Подключаюсь снова…');
    };
    active.onmessage = function (message) {
      if (source !== active) return;
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
      var response = await fetch('/__assi/rpc', {
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
    // Explicit login keeps a failed request from repeatedly reloading unsaved editors.
    login: function () { window.location.assign('/'); },
    close: function () {
      if (closed) return;
      closed = true;
      stopEvents();
      controllers.forEach(function (controller) { controller.abort(); });
      controllers.clear();
      setStatus('closed');
      subscribers.clear();
      statusListeners.clear();
    }
  };
  Object.defineProperty(transport, 'status', { get: function () { return Object.assign({}, status); } });
  window.AssiTransport = transport;
  window.dispatchEvent(new CustomEvent('assi-transport-ready'));
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
})();

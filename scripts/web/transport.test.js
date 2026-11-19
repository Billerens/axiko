// node --test scripts/web/transport.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const transportSource = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/factory/ui/desk/transport.js'), 'utf8');
const clientSource = fs.readFileSync(path.join(ROOT, 'app/src/main/assets/factory/ui/desk/client.js'), 'utf8');
const settle = () => new Promise(resolve => setImmediate(resolve));
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function world(options = {}) {
  const sources = [];
  const windows = [];
  const calls = [];
  const warnings = [];
  const response = (result, status = 200) => ({ status, ok: status >= 200 && status < 300, json: async () => result });
  let fetcher = async (_url, init) => {
    const body = JSON.parse(init.body);
    calls.push(body);
    return response({ result: JSON.stringify({ received: body }) });
  };

  function makeWindow(url, parent) {
    const events = new Map();
    const location = new URL(url);
    location.assignments = [];
    location.assign = function (next) { this.assignments.push(next); };
    location.reload = function () { this.assignments.push('reload'); };
    const scripts = [];
    const storage = new Map();
    const element = () => ({
      style: {}, children: [], attributes: {}, textContent: '',
      classList: { toggle() {} },
      appendChild(child) { this.children.push(child); },
      remove() { this.removed = true; }
    });
    const w = {
      location, parent: null, document: null, history: { back() { w.backCalled = true; } },
      console: { error: (...args) => warnings.push(args), warn: (...args) => warnings.push(args), log() {} },
      setTimeout, clearTimeout, AbortController, Promise, URL, Set, Map,
      CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init && init.detail; } },
      localStorage: { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, String(value)) },
      addEventListener(name, fn) {
        if (!events.has(name)) events.set(name, new Set());
        events.get(name).add(fn);
      },
      removeEventListener(name, fn) { if (events.has(name)) events.get(name).delete(fn); },
      dispatchEvent(event) {
        for (const fn of [...(events.get(event.type) || [])]) fn(event);
      },
      fetch: (...args) => fetcher(...args),
      EventSource: class {
        constructor(url) { this.url = url; this.closed = false; this.readyState = 0; this.named = new Map(); sources.push(this); }
        addEventListener(name, fn) { if (!this.named.has(name)) this.named.set(name, []); this.named.get(name).push(fn); }
        /** A named event of the stream, as the phone's `event: <name>` lines give it. */
        emit(name, data) { for (const fn of this.named.get(name) || []) fn({ type: name, data }); }
        close() { this.closed = true; this.readyState = 2; }
      }
    };
    w.window = w;
    w.parent = parent || w;
    w.top = parent ? parent.top : w;
    w.document = { body: element(), documentElement: element(), createElement: () => element(), addEventListener: w.addEventListener };
    w.document.head = {
      appendChild(script) {
        scripts.push(script);
        queueMicrotask(() => {
          if (script.src === '/ui/desk/transport.js') {
            vm.runInContext(transportSource, ctx);
            script.onload();
          } else if (script.onerror) script.onerror();
        });
      }
    };
    const ctx = vm.createContext(w);
    w.scripts = scripts;
    w.runTransport = () => vm.runInContext(transportSource, ctx);
    w.runClient = () => vm.runInContext(clientSource, ctx);
    windows.push(w);
    return w;
  }
  function close() {
    for (const w of windows) w.dispatchEvent({ type: 'pagehide' });
    for (const w of windows) if (w.AxikoTransport) w.AxikoTransport.close();
  }
  return { makeWindow, sources, calls, warnings, close, response, setFetch: fn => { fetcher = fn; } };
}

test('a module reports DOM readiness locally without waiting for images or sending an RPC', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  const ready = [];
  hub.axikoDesk = {
    rpc: hub.AxikoTransport.rpc, subscribe: hub.AxikoTransport.subscribe,
    ready: (source, route) => ready.push({ source, route })
  };
  const frame = env.makeWindow('http://axiko.local/modules/games/ui/index.html?view=all#games', hub);
  frame.document.readyState = 'loading';
  frame.runClient();
  assert.equal(ready.length, 0);
  frame.document.readyState = 'interactive';
  frame.dispatchEvent({ type: 'DOMContentLoaded' });
  assert.equal(ready.length, 1);
  assert.equal(ready[0].source.document, frame.document, 'the signal identifies its sending document');
  assert.equal(ready[0].route, '/modules/games/ui/index.html?view=all');
  await frame.axiko.native('ui.ready');
  assert.equal(ready.length, 2);
  assert.equal(env.calls.length, 0, 'ready belongs to the browser hub, not the phone UI');
});

test('a client injected after DOM readiness reports its screen immediately', t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  const ready = [];
  hub.axikoDesk = { rpc: hub.AxikoTransport.rpc, subscribe: hub.AxikoTransport.subscribe,
    ready: (_source, route) => ready.push(route) };
  const frame = env.makeWindow('http://axiko.local/ui/home/index.html', hub);
  frame.document.readyState = 'complete';
  frame.runClient();
  assert.deepEqual(ready, ['/ui/home/index.html']);
});

test('six nested widgets wait for one hub transport and receive events without their own SSE', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runClient(); // The LAN server injects the client before the hub's normal scripts.
  const home = env.makeWindow('http://axiko.local/ui/home/index.html', hub);
  home.runClient();
  const widgets = Array.from({ length: 6 }, (_, i) => {
    const frame = env.makeWindow('http://axiko.local/modules/widget-' + i + '/ui/card.html', home);
    frame.runClient();
    return frame;
  });
  const early = widgets[0].axiko.native('probe');
  assert.equal(env.sources.length, 0, 'waiting widgets do not exhaust connections while the hub boots');
  hub.runTransport();
  hub.axikoDesk = {
    rpc: hub.AxikoTransport.rpc,
    subscribe: hub.AxikoTransport.subscribe,
    where: () => 'ui/home/index.html',
    open() {}, back() {}, chat() {}, stage() {}
  };
  await early;
  await settle();
  assert.equal(env.sources.length, 1);
  assert.equal(widgets.reduce((n, w) => n + w.scripts.length, 0), 0);
  assert.equal(env.calls.length, 1);
  const observed = widgets.map(() => 0);
  widgets.forEach((frame, i) => frame.axiko.on('counter', () => observed[i]++));
  env.sources[0].onmessage({ data: JSON.stringify({ event: 'counter', data: { n: 1 } }) });
  assert.deepEqual(observed, [1, 1, 1, 1, 1, 1]);
  widgets[0].dispatchEvent({ type: 'pagehide' });
  env.sources[0].onmessage({ data: JSON.stringify({ event: 'counter', data: { n: 2 } }) });
  assert.deepEqual(observed, [1, 2, 2, 2, 2, 2], 'closed widgets release their event subscriptions');
});

test('nested widgets route to the main hub and keep draft context in calls and navigation', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  const opened = [], stages = [];
  hub.axikoDesk = {
    rpc: hub.AxikoTransport.rpc, subscribe: hub.AxikoTransport.subscribe,
    where: () => '~d123/modules/notes/ui/index.html',
    open: value => opened.push(value), stage: value => stages.push(value), back() {}, chat() {}
  };
  const home = env.makeWindow('http://axiko.local/~d123/ui/home/index.html', hub);
  const widget = env.makeWindow('http://axiko.local/~d123/modules/notes/ui/card.html', home);
  widget.runClient();
  await widget.axiko.call('modules/notes/main.js', 'list');
  assert.equal(env.calls[0].draft, 'd123');
  assert.equal(env.calls[0].where, '~d123/modules/notes/ui/index.html');
  await widget.axiko.open('modules/notes/ui/index.html');
  await widget.axiko.stage(true);
  assert.deepEqual(opened, ['~d123/modules/notes/ui/index.html']);
  assert.deepEqual(stages, [true]);
  assert.equal(env.calls.length, 1, 'stage and navigation never control the phone');
  assert.equal(widget.location.pathname, '/~d123/modules/notes/ui/card.html');

  const chat = env.makeWindow('http://axiko.local/ui/chat/index.html', hub);
  chat.runClient();
  await chat.axiko.native('agent.send', 'axiko', 'Проверь этот экран');
  assert.equal(env.calls[1].draft, 'd123', 'the chat sends the active preview context');
});

test('draft.open uses metadata and opens a browser preview, never the phone UI operation', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  const opened = [];
  hub.axikoDesk = {
    rpc: hub.AxikoTransport.rpc, subscribe: hub.AxikoTransport.subscribe,
    where: () => 'ui/home/index.html', open: value => opened.push(value)
  };
  env.setFetch(async (_url, options) => {
    const payload = JSON.parse(options.body); env.calls.push(payload);
    return env.response({ result: JSON.stringify([{ id: 'd42', open: 'modules/new/ui/index.html' }]) });
  });
  const chat = env.makeWindow('http://axiko.local/ui/chat/index.html', hub);
  chat.runClient();
  await chat.axiko.native('draft.open', 'd42');
  assert.deepEqual(opened, ['~d42/modules/new/ui/index.html']);
  assert.deepEqual(env.calls.map(c => c.method), ['draft.list']);
});

test('RPC preserves conflict/status and does not retry failed writes', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  let requests = 0;
  env.setFetch(async () => { requests++; return env.response({ error: 'Файл изменился', code: 'conflict', revision: 'new-revision' }); });
  await assert.rejects(hub.AxikoTransport.rpc({ op: 'desk.write', path: 'a.js', text: 'x', expectedRevision: 'old' }),
    error => error.code === 'conflict' && error.status === 200 && error.revision === 'new-revision');
  assert.equal(requests, 1);
  env.setFetch(async () => { requests++; return env.response(null, 503); });
  await assert.rejects(hub.AxikoTransport.native('probe'), error => error.code === 'HTTP_503' && error.status === 503);
  assert.equal(requests, 2);
});

test('401 keeps unsaved UI in place, stops SSE and rejects subsequent calls without a reload loop', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  let requests = 0, jsonCalls = 0;
  env.setFetch(async () => {
    requests++;
    return { status: 401, ok: false, json: async () => { jsonCalls++; throw Error('HTML login is not JSON'); } };
  });
  await assert.rejects(hub.AxikoTransport.native('probe'), error => error.code === 'AUTH_REQUIRED');
  await assert.rejects(hub.AxikoTransport.native('probe'), error => error.code === 'AUTH_REQUIRED');
  assert.equal(requests, 1);
  assert.equal(jsonCalls, 0);
  assert.equal(env.sources[0].closed, true);
  assert.equal(hub.AxikoTransport.status.state, 'auth-required');
  assert.equal(hub.location.assignments.length, 0);
  hub.AxikoTransport.login();
  assert.deepEqual(hub.location.assignments, ['/']);
});

test('timeout reports uncertain completion and never replays the operation', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  let requests = 0;
  env.setFetch((_url, options) => {
    requests++;
    return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true }));
  });
  await assert.rejects(hub.AxikoTransport.rpc({ op: 'desk.write', path: 'a.js' }, { timeoutMs: 10 }),
    error => error.code === 'TIMEOUT' && error.mayHaveCompleted && !!error.requestId);
  assert.equal(requests, 1);
});

test('connection state and reconnect notification use the same EventSource', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  const states = [], events = [];
  const release = hub.AxikoTransport.onStatus(state => states.push(state.state));
  hub.AxikoTransport.subscribe(event => events.push(event.event));
  const stream = env.sources[0];
  stream.onopen();
  stream.onerror();
  stream.onopen();
  assert.deepEqual(states, ['connecting', 'online', 'offline', 'online']);
  assert.equal(events.filter(name => name === 'desk.reconnected').length, 1);
  assert.equal(hub.AxikoTransport.status.reconnects, 1);
  assert.equal(env.sources.length, 1);
  release();
  stream.onerror();
  assert.equal(states.length, 4);
});

test('a standalone module loads one transport and safely resumes after bfcache', async t => {
  const env = world(); t.after(() => env.close());
  const page = env.makeWindow('http://axiko.local/modules/notes/ui/index.html');
  page.runClient();
  await page.axiko.native('probe');
  await settle();
  assert.equal(env.sources.length, 1);
  assert.equal(page.scripts.length, 1);
  const events = [];
  page.axiko.on('tick', value => events.push(value));
  env.sources[0].onmessage({ data: '{"event":"tick","data":1}' });
  page.dispatchEvent({ type: 'pagehide', persisted: true });
  assert(env.sources[0].closed);
  page.dispatchEvent({ type: 'pageshow', persisted: true });
  await settle();
  assert.equal(env.sources.length, 2);
  assert.equal(env.sources.filter(s => !s.closed).length, 1);
  env.sources[1].onmessage({ data: '{"event":"tick","data":2}' });
  assert.deepEqual(events, [1, 2]);
});



test('absolute widget URLs inherit a draft parent while live modules stay live beside another draft', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  hub.axikoDesk = {
    rpc: hub.AxikoTransport.rpc, subscribe: hub.AxikoTransport.subscribe,
    where: () => '~dother/modules/preview/ui/index.html', open() {}
  };
  const preview = env.makeWindow('http://axiko.local/~dparent/modules/example/ui/index.html', hub);
  const nested = env.makeWindow('http://axiko.local/modules/example/ui/card.html', preview);
  nested.runClient();
  await nested.axiko.call('modules/example/main.js', 'list');
  assert.equal(env.calls[0].draft, 'dparent');
  const live = env.makeWindow('http://axiko.local/modules/live/ui/index.html', hub);
  live.runClient();
  await live.axiko.call('modules/live/main.js', 'list');
  assert.equal(env.calls[1].draft, null, 'explicit null prevents server fallback to the other selected preview');
});


test('browser app.visible ignores phone lifecycle and follows the visible ancestor panes', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  const localSubscribers = new Set();
  const fanout = event => localSubscribers.forEach(listener => listener(event));
  hub.AxikoTransport.subscribe(fanout);
  hub.axikoDesk = {
    rpc: hub.AxikoTransport.rpc,
    subscribe: listener => { localSubscribers.add(listener); return () => localSubscribers.delete(listener); },
    where: () => 'modules/games/test/ui/index.html',
    open() {}
  };
  const home = env.makeWindow('http://axiko.local/ui/home/index.html', hub);
  let paneVisible = true;
  home.frameElement = { hidden: false, getClientRects: () => paneVisible ? [{ width: 700, height: 600 }] : [] };
  const game = env.makeWindow('http://axiko.local/modules/games/test/ui/index.html', home);
  game.frameElement = { hidden: false, getClientRects: () => [{ width: 300, height: 200 }] };
  game.document.visibilityState = 'visible';
  game.runClient();
  const values = [];
  game.axiko.on('app.visible', event => values.push(event.on));
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.deepEqual(values, [true], 'initial visibility is delivered when the listener subscribes');

  env.sources[0].onmessage({ data: '{"event":"app.visible","data":{"on":false}}' });
  assert.deepEqual(values, [true], 'minimizing the phone cannot pause the browser game');

  paneVisible = false;
  fanout({ event: 'desk.visibility' });
  fanout({ event: 'desk.visibility' });
  assert.deepEqual(values, [true, false], 'a hidden ancestor pane pauses once');

  paneVisible = true;
  fanout({ event: 'desk.visibility' });
  assert.deepEqual(values, [true, false, true]);

  hub.document.hidden = true;
  game.document.visibilityState = 'hidden';
  game.dispatchEvent({ type: 'visibilitychange' });
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(values.at(-1), false);

  hub.document.hidden = false;
  game.document.visibilityState = 'visible';
  game.dispatchEvent({ type: 'visibilitychange' });
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(values.at(-1), true, 'showing the browser resumes its visible game');
});

test('returning to a browser tab does not restart a game hidden behind Studio', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  hub.axikoDesk = { rpc: hub.AxikoTransport.rpc, subscribe: hub.AxikoTransport.subscribe, where: () => '' };
  const game = env.makeWindow('http://axiko.local/modules/games/test/ui/index.html', hub);
  game.frameElement = { hidden: false, getClientRects: () => [] };
  game.document.visibilityState = 'hidden';
  game.runClient();
  let running = true;
  game.axiko.on('app.visible', event => { running = event.on; });
  // This is the game engine's later document handler. The bridge must reassert pane visibility after it.
  game.document.addEventListener('visibilitychange', () => { running = game.document.visibilityState !== 'hidden'; });
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(running, false);
  game.document.visibilityState = 'visible';
  game.dispatchEvent({ type: 'visibilitychange' });
  assert.equal(running, true, 'the game itself reacts to the document before pane visibility is reapplied');
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(running, false, 'the hidden pane wins after document handlers finish');
});

test('a standalone game uses browser document visibility and stops on pagehide', async t => {
  const env = world(); t.after(() => env.close());
  const page = env.makeWindow('http://axiko.local/modules/games/test/ui/index.html');
  page.document.visibilityState = 'visible';
  page.runClient();
  const values = [];
  page.axiko.on('app.visible', event => values.push(event.on));
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(values.at(-1), true);
  env.sources[0].onmessage({ data: '{"event":"app.visible","data":{"on":false}}' });
  assert.equal(values.at(-1), true);
  page.document.visibilityState = 'hidden';
  page.dispatchEvent({ type: 'visibilitychange' });
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(values.at(-1), false);
  page.document.visibilityState = 'visible';
  page.dispatchEvent({ type: 'visibilitychange' });
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(values.at(-1), true);
  page.dispatchEvent({ type: 'pagehide' });
  assert.equal(values.at(-1), false);
});

test('a stream that falls silent is opened anew within a few of its pings, and one that pings is kept', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  const reconnected = [];
  hub.AxikoTransport.subscribe(event => { if (event.event === 'desk.reconnected') reconnected.push(event.data.reconnects); });
  const first = env.sources[0];
  first.emit('hello', '{"ping":1000}');
  first.onopen();
  for (let i = 0; i < 6; i++) { await sleep(500); first.emit('ping', '{}'); }
  assert.equal(env.sources.length, 1, 'a stream that pings is never replaced');
  // The phone's network blinked: the browser still holds the connection, nobody writes to it.
  const started = Date.now();
  while (env.sources.length === 1 && Date.now() - started < 5000) await sleep(50);
  const elapsed = Date.now() - started;
  assert.equal(env.sources.length, 2);
  assert.ok(first.closed, 'the silent stream is closed');
  assert.ok(elapsed >= 2000 && elapsed < 3500, 'after about two and a half pings: ' + elapsed + ' ms');
  env.sources[1].onopen();
  assert.deepEqual(reconnected, [1], 'pages hear of the reconnection and read what they missed');
});

test('a stream the browser gave up on is opened again from the hub, later each time', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  const first = env.sources[0];
  first.onopen();
  // A lost connection: the browser retries it by itself, the hub opens nothing else.
  first.readyState = 0;
  first.onerror();
  await sleep(1300);
  assert.equal(env.sources.length, 1);
  assert.equal(hub.AxikoTransport.status.state, 'offline');
  // An error page instead of events: the browser closes the stream for good.
  first.readyState = 2;
  first.onerror();
  await sleep(1300);
  assert.equal(env.sources.length, 2, 'opened again after a second');
  const second = env.sources[1];
  second.readyState = 2;
  second.onerror();
  await sleep(1300);
  assert.equal(env.sources.length, 2, 'the next try waits longer');
  await sleep(1000);
  assert.equal(env.sources.length, 3);
  env.sources[2].onopen();
  assert.equal(hub.AxikoTransport.status.state, 'online');
});

test('coming back to the page opens a quiet stream anew at once and leaves a fresh one alone', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  const first = env.sources[0];
  first.emit('hello', '{"ping":1000}');
  first.onopen();
  hub.dispatchEvent({ type: 'focus' });
  assert.equal(env.sources.length, 1, 'a stream heard a moment ago is kept');
  // Longer than a ping and its margin, shorter than the watch's own limit.
  await sleep(2200);
  hub.dispatchEvent({ type: 'visibilitychange' });
  assert.equal(env.sources.length, 2, 'a stream quiet for longer than its pings allow is opened anew');
});

test('a page that lost its events asks the hub to open the stream anew, without the phone', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  hub.axikoDesk = { rpc: hub.AxikoTransport.rpc, subscribe: hub.AxikoTransport.subscribe, reconnect: () => hub.AxikoTransport.reconnect() };
  env.sources[0].onopen();
  const chat = env.makeWindow('http://axiko.local/ui/chat/index.html', hub);
  chat.runClient();
  assert.equal(await chat.axiko.native('desk.reconnect'), true);
  assert.equal(env.sources.length, 2);
  assert.ok(env.sources[0].closed);
  assert.equal(env.calls.length, 0, 'it never reaches the phone as an operation');
});

test('a phone that never said hello pings in a way a page cannot see: its quiet stream is left alone', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://axiko.local/');
  hub.runTransport();
  env.sources[0].onopen();
  await sleep(1200);
  hub.dispatchEvent({ type: 'visibilitychange' });
  hub.dispatchEvent({ type: 'focus' });
  assert.equal(env.sources.length, 1);
  assert.equal(hub.AxikoTransport.status.state, 'online');
});

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
        constructor(url) { this.url = url; this.closed = false; sources.push(this); }
        close() { this.closed = true; }
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
    for (const w of windows) if (w.AssiTransport) w.AssiTransport.close();
  }
  return { makeWindow, sources, calls, warnings, close, response, setFetch: fn => { fetcher = fn; } };
}

test('a module reports DOM readiness locally without waiting for images or sending an RPC', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  const ready = [];
  hub.assiDesk = {
    rpc: hub.AssiTransport.rpc, subscribe: hub.AssiTransport.subscribe,
    ready: (source, route) => ready.push({ source, route })
  };
  const frame = env.makeWindow('http://assi.local/modules/games/ui/index.html?view=all#games', hub);
  frame.document.readyState = 'loading';
  frame.runClient();
  assert.equal(ready.length, 0);
  frame.document.readyState = 'interactive';
  frame.dispatchEvent({ type: 'DOMContentLoaded' });
  assert.equal(ready.length, 1);
  assert.equal(ready[0].source.document, frame.document, 'the signal identifies its sending document');
  assert.equal(ready[0].route, '/modules/games/ui/index.html?view=all');
  await frame.assi.native('ui.ready');
  assert.equal(ready.length, 2);
  assert.equal(env.calls.length, 0, 'ready belongs to the browser hub, not the phone UI');
});

test('a client injected after DOM readiness reports its screen immediately', t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  const ready = [];
  hub.assiDesk = { rpc: hub.AssiTransport.rpc, subscribe: hub.AssiTransport.subscribe,
    ready: (_source, route) => ready.push(route) };
  const frame = env.makeWindow('http://assi.local/ui/home/index.html', hub);
  frame.document.readyState = 'complete';
  frame.runClient();
  assert.deepEqual(ready, ['/ui/home/index.html']);
});

test('six nested widgets wait for one hub transport and receive events without their own SSE', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://assi.local/');
  hub.runClient(); // The LAN server injects the client before the hub's normal scripts.
  const home = env.makeWindow('http://assi.local/ui/home/index.html', hub);
  home.runClient();
  const widgets = Array.from({ length: 6 }, (_, i) => {
    const frame = env.makeWindow('http://assi.local/modules/widget-' + i + '/ui/card.html', home);
    frame.runClient();
    return frame;
  });
  const early = widgets[0].assi.native('probe');
  assert.equal(env.sources.length, 0, 'waiting widgets do not exhaust connections while the hub boots');
  hub.runTransport();
  hub.assiDesk = {
    rpc: hub.AssiTransport.rpc,
    subscribe: hub.AssiTransport.subscribe,
    where: () => 'ui/home/index.html',
    open() {}, back() {}, chat() {}, stage() {}
  };
  await early;
  await settle();
  assert.equal(env.sources.length, 1);
  assert.equal(widgets.reduce((n, w) => n + w.scripts.length, 0), 0);
  assert.equal(env.calls.length, 1);
  const observed = widgets.map(() => 0);
  widgets.forEach((frame, i) => frame.assi.on('counter', () => observed[i]++));
  env.sources[0].onmessage({ data: JSON.stringify({ event: 'counter', data: { n: 1 } }) });
  assert.deepEqual(observed, [1, 1, 1, 1, 1, 1]);
  widgets[0].dispatchEvent({ type: 'pagehide' });
  env.sources[0].onmessage({ data: JSON.stringify({ event: 'counter', data: { n: 2 } }) });
  assert.deepEqual(observed, [1, 2, 2, 2, 2, 2], 'closed widgets release their event subscriptions');
});

test('nested widgets route to the main hub and keep draft context in calls and navigation', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  const opened = [], stages = [];
  hub.assiDesk = {
    rpc: hub.AssiTransport.rpc, subscribe: hub.AssiTransport.subscribe,
    where: () => '~d123/modules/notes/ui/index.html',
    open: value => opened.push(value), stage: value => stages.push(value), back() {}, chat() {}
  };
  const home = env.makeWindow('http://assi.local/~d123/ui/home/index.html', hub);
  const widget = env.makeWindow('http://assi.local/~d123/modules/notes/ui/card.html', home);
  widget.runClient();
  await widget.assi.call('modules/notes/main.js', 'list');
  assert.equal(env.calls[0].draft, 'd123');
  assert.equal(env.calls[0].where, '~d123/modules/notes/ui/index.html');
  await widget.assi.open('modules/notes/ui/index.html');
  await widget.assi.stage(true);
  assert.deepEqual(opened, ['~d123/modules/notes/ui/index.html']);
  assert.deepEqual(stages, [true]);
  assert.equal(env.calls.length, 1, 'stage and navigation never control the phone');
  assert.equal(widget.location.pathname, '/~d123/modules/notes/ui/card.html');

  const chat = env.makeWindow('http://assi.local/ui/chat/index.html', hub);
  chat.runClient();
  await chat.assi.native('agent.send', 'assi', 'Проверь этот экран');
  assert.equal(env.calls[1].draft, 'd123', 'the chat sends the active preview context');
});

test('draft.open uses metadata and opens a browser preview, never the phone UI operation', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  const opened = [];
  hub.assiDesk = {
    rpc: hub.AssiTransport.rpc, subscribe: hub.AssiTransport.subscribe,
    where: () => 'ui/home/index.html', open: value => opened.push(value)
  };
  env.setFetch(async (_url, options) => {
    const payload = JSON.parse(options.body); env.calls.push(payload);
    return env.response({ result: JSON.stringify([{ id: 'd42', open: 'modules/new/ui/index.html' }]) });
  });
  const chat = env.makeWindow('http://assi.local/ui/chat/index.html', hub);
  chat.runClient();
  await chat.assi.native('draft.open', 'd42');
  assert.deepEqual(opened, ['~d42/modules/new/ui/index.html']);
  assert.deepEqual(env.calls.map(c => c.method), ['draft.list']);
});

test('RPC preserves conflict/status and does not retry failed writes', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  let requests = 0;
  env.setFetch(async () => { requests++; return env.response({ error: 'Файл изменился', code: 'conflict', revision: 'new-revision' }); });
  await assert.rejects(hub.AssiTransport.rpc({ op: 'desk.write', path: 'a.js', text: 'x', expectedRevision: 'old' }),
    error => error.code === 'conflict' && error.status === 200 && error.revision === 'new-revision');
  assert.equal(requests, 1);
  env.setFetch(async () => { requests++; return env.response(null, 503); });
  await assert.rejects(hub.AssiTransport.native('probe'), error => error.code === 'HTTP_503' && error.status === 503);
  assert.equal(requests, 2);
});

test('401 keeps unsaved UI in place, stops SSE and rejects subsequent calls without a reload loop', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  let requests = 0, jsonCalls = 0;
  env.setFetch(async () => {
    requests++;
    return { status: 401, ok: false, json: async () => { jsonCalls++; throw Error('HTML login is not JSON'); } };
  });
  await assert.rejects(hub.AssiTransport.native('probe'), error => error.code === 'AUTH_REQUIRED');
  await assert.rejects(hub.AssiTransport.native('probe'), error => error.code === 'AUTH_REQUIRED');
  assert.equal(requests, 1);
  assert.equal(jsonCalls, 0);
  assert.equal(env.sources[0].closed, true);
  assert.equal(hub.AssiTransport.status.state, 'auth-required');
  assert.equal(hub.location.assignments.length, 0);
  hub.AssiTransport.login();
  assert.deepEqual(hub.location.assignments, ['/']);
});

test('timeout reports uncertain completion and never replays the operation', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  let requests = 0;
  env.setFetch((_url, options) => {
    requests++;
    return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true }));
  });
  await assert.rejects(hub.AssiTransport.rpc({ op: 'desk.write', path: 'a.js' }, { timeoutMs: 10 }),
    error => error.code === 'TIMEOUT' && error.mayHaveCompleted && !!error.requestId);
  assert.equal(requests, 1);
});

test('connection state and reconnect notification use the same EventSource', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  const states = [], events = [];
  const release = hub.AssiTransport.onStatus(state => states.push(state.state));
  hub.AssiTransport.subscribe(event => events.push(event.event));
  const stream = env.sources[0];
  stream.onopen();
  stream.onerror();
  stream.onopen();
  assert.deepEqual(states, ['connecting', 'online', 'offline', 'online']);
  assert.equal(events.filter(name => name === 'desk.reconnected').length, 1);
  assert.equal(hub.AssiTransport.status.reconnects, 1);
  assert.equal(env.sources.length, 1);
  release();
  stream.onerror();
  assert.equal(states.length, 4);
});

test('a standalone module loads one transport and safely resumes after bfcache', async t => {
  const env = world(); t.after(() => env.close());
  const page = env.makeWindow('http://assi.local/modules/notes/ui/index.html');
  page.runClient();
  await page.assi.native('probe');
  await settle();
  assert.equal(env.sources.length, 1);
  assert.equal(page.scripts.length, 1);
  const events = [];
  page.assi.on('tick', value => events.push(value));
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
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  hub.assiDesk = {
    rpc: hub.AssiTransport.rpc, subscribe: hub.AssiTransport.subscribe,
    where: () => '~dother/modules/preview/ui/index.html', open() {}
  };
  const preview = env.makeWindow('http://assi.local/~dparent/modules/example/ui/index.html', hub);
  const nested = env.makeWindow('http://assi.local/modules/example/ui/card.html', preview);
  nested.runClient();
  await nested.assi.call('modules/example/main.js', 'list');
  assert.equal(env.calls[0].draft, 'dparent');
  const live = env.makeWindow('http://assi.local/modules/live/ui/index.html', hub);
  live.runClient();
  await live.assi.call('modules/live/main.js', 'list');
  assert.equal(env.calls[1].draft, null, 'explicit null prevents server fallback to the other selected preview');
});


test('browser app.visible ignores phone lifecycle and follows the visible ancestor panes', async t => {
  const env = world(); t.after(() => env.close());
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  const localSubscribers = new Set();
  const fanout = event => localSubscribers.forEach(listener => listener(event));
  hub.AssiTransport.subscribe(fanout);
  hub.assiDesk = {
    rpc: hub.AssiTransport.rpc,
    subscribe: listener => { localSubscribers.add(listener); return () => localSubscribers.delete(listener); },
    where: () => 'modules/games/test/ui/index.html',
    open() {}
  };
  const home = env.makeWindow('http://assi.local/ui/home/index.html', hub);
  let paneVisible = true;
  home.frameElement = { hidden: false, getClientRects: () => paneVisible ? [{ width: 700, height: 600 }] : [] };
  const game = env.makeWindow('http://assi.local/modules/games/test/ui/index.html', home);
  game.frameElement = { hidden: false, getClientRects: () => [{ width: 300, height: 200 }] };
  game.document.visibilityState = 'visible';
  game.runClient();
  const values = [];
  game.assi.on('app.visible', event => values.push(event.on));
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
  const hub = env.makeWindow('http://assi.local/');
  hub.runTransport();
  hub.assiDesk = { rpc: hub.AssiTransport.rpc, subscribe: hub.AssiTransport.subscribe, where: () => '' };
  const game = env.makeWindow('http://assi.local/modules/games/test/ui/index.html', hub);
  game.frameElement = { hidden: false, getClientRects: () => [] };
  game.document.visibilityState = 'hidden';
  game.runClient();
  let running = true;
  game.assi.on('app.visible', event => { running = event.on; });
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
  const page = env.makeWindow('http://assi.local/modules/games/test/ui/index.html');
  page.document.visibilityState = 'visible';
  page.runClient();
  const values = [];
  page.assi.on('app.visible', event => values.push(event.on));
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


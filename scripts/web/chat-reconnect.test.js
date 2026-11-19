// Exercises the actual reconciliation controller in the chat, with deterministic RPC/event ordering.
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../app/src/main/assets/factory/ui/chat/index.html'), 'utf8');
const start = source.indexOf('  // ---- reconnect reconciliation ');
const end = source.indexOf('  // ---- end reconnect reconciliation ', start);
assert(start >= 0 && end > start, 'chat reconciliation source markers');
const controller = source.slice(start, end);
const later = () => {
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  return { promise, resolve, reject };
};
const tick = () => new Promise(resolve => setImmediate(resolve));

function fixture() {
  const handlers = new Map(), documentHandlers = new Map(), timers = new Map();
  const rendered = [], calls = [], notices = [];
  let sequence = 0;
  let implementation = () => Promise.resolve(false);
  const loads = [];
  const scope = {
    agentId: 'axiko', threadId: 'thread-1', chatLoadVersion: 1, agentEventsVersion: 0,
    // The rest of the page the handlers read: what was dropped while hidden, the work, and a full load.
    unseen: false, busy: false, load: async () => { loads.push(true); },
    document: { visibilityState: 'visible', addEventListener: (name, fn) => documentHandlers.set(name, fn) },
    axiko: {
      on: (name, fn) => handlers.set(name, fn),
      native: (...args) => { calls.push(args); return implementation(...args); }
    },
    ui: { toast: message => notices.push(message) },
    renderHistory: (...args) => rendered.push(JSON.parse(JSON.stringify(args))),
    setTimeout: (fn, delay) => { const id = ++sequence; timers.set(id, { fn, delay }); return id; },
    clearTimeout: id => timers.delete(id)
  };
  vm.createContext(scope);
  vm.runInContext(controller, scope);
  return {
    scope, calls, timers, rendered, notices, loads,
    rpc: fn => { implementation = fn; },
    event: (name, data) => handlers.get(name)(data),
    agent: (type, extra = {}) => scope.noteAgentEvent(Object.assign({ type }, extra)),
    visibility: state => { scope.document.visibilityState = state; documentHandlers.get('visibilitychange')(); },
    run: async () => {
      const next = timers.entries().next().value;
      assert(next, 'expected a queued reconciliation');
      timers.delete(next[0]);
      return next[1].fn();
    },
    start: () => {
      const next = timers.entries().next().value;
      assert(next, 'expected a queued reconciliation');
      timers.delete(next[0]);
      return next[1].fn();
    },
    reset: () => scope.resetHistoryRefresh()
  };
}

test('reconnect during the work shows what was missed at once, then the complete reply after idle', async () => {
  const f = fixture();
  let busy = true;
  const sofar = [{ type: 'user', text: 'Запрос' }, { type: 'assistant', text: 'Начало отв', live: true }];
  const final = [{ type: 'user', text: 'Запрос' }, { type: 'assistant', text: 'Полный ответ, включая пропущенную середину.' }];
  f.rpc(async method => method === 'agent.busy' ? busy : busy ? sofar : final);
  f.event('desk.reconnected');
  await f.run();
  assert.equal(f.rendered.length, 1, 'the request and the answer so far are shown without waiting for the end');
  assert.deepEqual(f.rendered[0][0], sofar);
  assert.equal(f.rendered[0][1], true, 'shown as work in progress: the live answer goes on in its bubble');
  assert.deepEqual(f.calls.map(c => c[0]), ['agent.busy', 'agent.history']);
  await f.run();
  assert.equal(f.rendered.length, 1, 'the catch-up happens once; later polls only wait for the end');
  busy = false;
  f.agent('message');
  f.agent('idle', { more: false });
  await f.run();
  assert.equal(f.rendered.length, 2);
  assert.deepEqual(f.rendered[1][0], final);
  assert.equal(f.rendered[1][1], false);
  assert.equal(f.timers.size, 0);
});

test('a chat switched before the catch-up arrives keeps the newly selected conversation', async () => {
  const f = fixture(), history = later();
  f.rpc(async method => method === 'agent.busy' ? true : history.promise);
  f.event('desk.reconnected');
  const pending = f.start();
  await tick();
  f.scope.chatLoadVersion++;
  f.scope.threadId = 'thread-2';
  history.resolve([{ type: 'assistant', text: 'Чужой ответ', live: true }]);
  await pending;
  assert.equal(f.rendered.length, 0);
});

test('an idle missed during the outage is recovered without waiting for a new agent event', async () => {
  const f = fixture();
  f.rpc(async method => method === 'agent.busy' ? false : [{ type: 'assistant', text: 'Завершено без связи' }]);
  f.event('desk.reconnected');
  await f.run();
  assert.equal(f.rendered[0][0][0].text, 'Завершено без связи');
  assert.equal(f.timers.size, 0, 'successful reconciliation stops polling');
});

test('late SSE tail after a faster HTTP snapshot is reconciled once more on its terminal idle', async () => {
  const f = fixture();
  const final = [{ type: 'assistant', text: 'Целое сообщение' }];
  f.rpc(async method => method === 'agent.busy' ? false : final);
  f.event('desk.reconnected');
  await f.run();
  f.agent('delta', { text: 'сообщение' });
  f.agent('message');
  f.agent('idle', { more: false });
  await f.run();
  assert.equal(f.rendered.length, 2);
  assert.deepEqual(f.rendered.at(-1)[0], final, 'the canonical replacement removes any duplicated/truncated tail');
  f.agent('idle', { more: false });
  assert.equal(f.timers.size, 0, 'the terminal idle watch is released after reconciliation');
});

test('an agent event during history fetch prevents an obsolete snapshot from overwriting the new reply', async () => {
  const f = fixture(), history = later();
  let histories = 0;
  f.rpc(async method => {
    if (method === 'agent.busy') return false;
    return ++histories === 1 ? history.promise : [{ type: 'assistant', text: 'Новый полный ответ' }];
  });
  f.event('desk.reconnected');
  const pending = f.start();
  await tick();
  f.agent('delta', { text: 'new tail' });
  f.agent('idle', { more: false });
  history.resolve([{ type: 'user', text: 'Старый снимок' }]);
  await pending;
  assert.equal(f.rendered.length, 0);
  await f.run();
  assert.equal(f.rendered[0][0][0].text, 'Новый полный ответ');
});

test('a task that starts while history is fetched blocks applying the snapshot until it finishes', async () => {
  const f = fixture();
  let checks = 0, histories = 0;
  f.rpc(async method => method === 'agent.busy' ? ++checks === 2 : [{ type: 'assistant', text: ++histories === 1 ? 'old' : 'final' }]);
  f.event('desk.reconnected');
  await f.run();
  assert.equal(f.rendered.length, 0, 'the second busy check catches a concurrent task');
  f.agent('idle', { more: false });
  await f.run();
  assert.equal(f.rendered[0][0][0].text, 'final');
});

test('switching chat while a snapshot is in flight cannot change the newly selected conversation', async () => {
  const f = fixture(), history = later();
  f.rpc(async method => method === 'agent.busy' ? false : history.promise);
  f.event('desk.reconnected');
  const pending = f.start();
  await tick();
  f.reset();
  f.scope.chatLoadVersion++;
  f.scope.agentId = 'another';
  f.scope.threadId = 'thread-2';
  history.resolve([{ type: 'assistant', text: 'Чужой ответ' }]);
  await pending;
  assert.equal(f.rendered.length, 0);
  assert.equal(f.timers.size, 0);
});

test('offline and hidden pages defer reconciliation until connection and visibility return', async () => {
  const f = fixture();
  f.rpc(async method => method === 'agent.busy' ? false : [{ type: 'assistant', text: 'Восстановлено' }]);
  f.event('connection', { state: 'offline' });
  f.event('desk.reconnected');
  assert.equal(f.timers.size, 0);
  f.event('connection', { state: 'online' });
  f.visibility('hidden');
  await f.run();
  assert.equal(f.calls.length, 0);
  f.visibility('visible');
  await f.run();
  assert.equal(f.rendered.length, 1);
});

test('a page that dropped live events while hidden reads the conversation anew when seen again', async () => {
  const f = fixture();
  f.visibility('hidden');
  f.scope.unseen = true;
  f.visibility('visible');
  assert.equal(f.loads.length, 1);
  assert.equal(f.scope.unseen, false);
  f.visibility('hidden');
  f.visibility('visible');
  assert.equal(f.loads.length, 1, 'nothing was dropped the second time, and no work is going on');
});

test('temporary RPC failure keeps reconciliation pending without repeated error toasts', async () => {
  const f = fixture();
  let fail = true;
  f.rpc(async method => {
    if (fail) throw new Error('Связь прервалась');
    return method === 'agent.busy' ? false : [{ type: 'assistant', text: 'Готово' }];
  });
  f.event('desk.reconnected');
  await f.run();
  await f.run();
  assert.equal(f.notices.length, 1);
  assert.equal(f.rendered.length, 0);
  fail = false;
  await f.run();
  assert.equal(f.rendered.length, 1);
  assert.equal(f.timers.size, 0);
});

test('an idle with queued work does not finish reconciliation before the final queued turn', async () => {
  const f = fixture();
  let busy = true;
  f.rpc(async method => method === 'agent.busy' ? busy : [{ type: 'assistant', text: 'Все задания завершены' }]);
  f.event('desk.reconnected');
  await f.run();
  assert.equal(f.rendered.length, 1, 'the work in progress is shown once at once');
  f.agent('idle', { more: true });
  await f.run();
  assert.equal(f.rendered.length, 1, 'an idle with more work queued is not the end');
  busy = false;
  f.agent('idle', { more: false });
  await f.run();
  assert.equal(f.rendered.length, 2);
  assert.equal(f.rendered[1][0][0].text, 'Все задания завершены');
  assert.equal(f.rendered[1][1], false);
});

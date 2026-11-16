// Catalog scheduling against the real page script: first paint, visible previews, stale replies.
// Rendering is spied here; a browser smoke checks the actual layout and preserved card nodes.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const PAGE = path.join(__dirname, '../../app/src/main/assets/factory/modules/games/ui/index.html');
const script = [...fs.readFileSync(PAGE, 'utf8').matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].at(-1)[1];
const settle = async () => { for (let i = 0; i < 16; i++) await Promise.resolve(); };

function game(id, extra = {}) {
  return { id, seq: 3, title: id, order: -1, detailsPending: true, offline: null, accrued: null, badge: false, ...extra };
}

function catalog(games) {
  return { games, modules: [], links: { mult: 1 }, now: 1000000 };
}

function preview(id, extra = {}) {
  return { id, seq: 3, savedAt: 1000, badge: true, accrued: { text: '+10' }, offline: { ok: true, hits: [] }, detailsPending: false, ...extra };
}

function page() {
  const requests = [], frames = [], timers = [], observers = [];
  const renders = [], patches = [], moduleRenders = [], warnings = [];
  const events = {};
  const nodes = new Map();
  const document = {
    visibilityState: 'visible',
    getElementById(id) {
      if (!nodes.has(id)) nodes.set(id, { children: [], replaceChildren(...children) { this.children = children; } });
      return nodes.get(id);
    },
    querySelector() { return null; },
    addEventListener(name, fn) { events[name] = fn; }
  };
  class Observer {
    constructor(callback) { this.callback = callback; this.nodes = []; observers.push(this); }
    observe(node) { this.nodes.push(node); }
    unobserve() {}
    disconnect() { this.disconnected = true; }
    show(...ids) {
      this.callback(ids.map(id => ({ isIntersecting: true, target: this.nodes.find(n => n.getAttribute('data-game') === id) })));
    }
  }
  const context = vm.createContext({
    document, window: {}, location: { hash: '' }, navigator: {},
    ui: { h: (tag, attrs, ...children) => ({ tag, attrs, children }), toast() {} },
    Fmt: { setup() {} }, Game: { ui: {} }, GameIcons: {},
    console: { warn: (...args) => warnings.push(args), error() {} },
    IntersectionObserver: Observer,
    requestAnimationFrame(fn) { frames.push(fn); },
    setTimeout(fn) { timers.push(fn); return timers.length; }, clearTimeout() {},
    assi: {
      call(module, fn, ...args) {
        const request = { module, fn, args };
        requests.push(request);
        return new Promise((resolve, reject) => { request.resolve = resolve; request.reject = reject; });
      },
      on() {}, open() {}, chat() {}
    },
    recordRender: value => renders.push(value), recordPatch: (id, value) => patches.push({ id, value }),
    recordModules: value => moduleRenders.push(value)
  });
  vm.runInContext(script, context, { filename: PAGE });
  vm.runInContext(`
    render = function () {
      recordRender(data);
      cardNodes.clear();
      data.games.forEach(item => cardNodes.set(item.id, {
        getAttribute: function () { return item.id; },
        querySelector: function () { return { replaceChildren: function (...children) { recordPatch(item.id, children); } }; }
      }));
    };
    plates = function () {};
    renderModules = function () { recordModules(data.modules); };
    globalThis.api = { load, refresh, marks, data: () => data };
  `, context);
  return {
    requests, observers, renders, patches, moduleRenders, warnings, api: context.api,
    calls(fn) { return requests.filter(r => r.fn === fn); },
    async paint() {
      frames.splice(0).forEach(fn => fn());
      timers.splice(0).forEach(fn => fn());
      await settle();
    },
    visibility(state) { document.visibilityState = state; events.visibilitychange(); }
  };
}

test('cards render before extras; only visible previews run, once and serially', async () => {
  const p = page();
  assert.equal(p.requests.length, 1);
  assert.equal(p.requests[0].fn, 'catalog');
  assert.equal(p.requests[0].args[0].details, false);
  p.requests[0].resolve(catalog([game('a'), game('b'), game('offscreen')]));
  await settle();
  assert.equal(p.renders.length, 1);
  assert.equal(p.requests.length, 1, 'optional work waits for the first paint');
  await p.paint();
  assert.equal(p.calls('catalogExtras').length, 1);
  assert.equal(p.calls('catalogDetails').length, 0, 'offscreen cards do not trigger source scans');
  p.observers[0].show('a', 'b', 'a');
  assert.deepEqual(p.calls('catalogDetails').map(r => r.args[0]), ['a']);
  p.calls('catalogDetails')[0].resolve(preview('a'));
  await settle();
  assert.deepEqual(p.calls('catalogDetails').map(r => r.args[0]), ['a', 'b']);
  assert.equal(p.patches[0].id, 'a');
  p.calls('catalogDetails')[1].resolve(preview('b'));
  p.calls('catalogExtras')[0].resolve({ modules: [{ module: 'daily' }], links: {} });
  await settle();
  assert.equal(p.patches.length, 2);
  assert.equal(p.moduleRenders.length, 1);
  assert.equal(p.renders.length, 1, 'enrichment does not rebuild game buttons or move their focus');
});

test('hiding stops queued previews; stale replies cannot modify the resumed catalog', async () => {
  const p = page();
  p.requests[0].resolve(catalog([game('a'), game('b')]));
  await settle(); await p.paint();
  p.observers[0].show('a', 'b');
  p.visibility('hidden');
  p.calls('catalogDetails')[0].resolve(preview('a'));
  await settle();
  assert.equal(p.calls('catalogDetails').length, 1, 'no next preview after leaving the screen');
  assert.equal(p.patches.length, 0, 'hidden response is ignored');
  p.visibility('visible');
  p.calls('catalog')[1].resolve(catalog([game('a', { title: 'Updated', seq: 4 })]));
  await settle();
  p.calls('catalogExtras')[0].resolve({ modules: [{ module: 'stale' }], links: {} });
  await settle();
  assert.equal(p.moduleRenders.length, 0, 'old dynamic module cards are ignored');
  assert.equal(p.api.data().games[0].title, 'Updated');
});

test('repeated visibility events share a load; mutations queue one fresh snapshot', async () => {
  const p = page();
  p.visibility('visible'); p.visibility('visible');
  assert.equal(p.calls('catalog').length, 1);
  p.api.refresh(); p.api.refresh();
  assert.equal(p.calls('catalog').length, 1, 'mutations do not start parallel catalog scans');
  p.calls('catalog')[0].resolve(catalog([game('old')]));
  await settle();
  assert.equal(p.calls('catalog').length, 2);
  p.calls('catalog')[1].resolve(catalog([game('fresh')]));
  await settle(); await p.paint();
  assert.equal(p.api.data().games[0].id, 'fresh');
  assert.equal(p.calls('catalogExtras').length, 1, 'superseded first paint does not start optional work');
});

test('a failed preview or extras request leaves games usable and the preview queue moving', async () => {
  const p = page();
  p.requests[0].resolve(catalog([game('removed'), game('good')]));
  await settle(); await p.paint();
  p.observers[0].show('removed', 'good');
  p.calls('catalogDetails')[0].reject(new Error('Game removed'));
  p.calls('catalogExtras')[0].reject(new Error('Module card failed'));
  await settle();
  assert.deepEqual(p.calls('catalogDetails').map(r => r.args[0]), ['removed', 'good']);
  p.calls('catalogDetails')[1].resolve(preview('good'));
  await settle();
  assert.equal(p.renders.length, 1);
  assert.equal(p.patches[0].id, 'good');
  assert.equal(p.warnings.length, 2);
});

test('a preview from another save is ignored and unknown offline status never means network required', async () => {
  const p = page();
  p.requests[0].resolve(catalog([game('a')]));
  await settle(); await p.paint();
  p.observers[0].show('a');
  p.calls('catalogDetails')[0].resolve(preview('a', { seq: 9 }));
  await settle();
  assert.equal(p.patches.length, 0);
  assert.equal(p.api.data().games[0].seq, 3);
  assert.equal(p.api.marks(game('a')).length, 0);
  assert.equal(p.api.marks(game('a', { offline: { ok: null } })).length, 0);
  const badges = p.api.marks(game('a', { offline: { ok: false, hits: [] } }));
  assert.equal(badges.length, 1);
  assert.equal(badges[0].children[0], 'нужна сеть');
});

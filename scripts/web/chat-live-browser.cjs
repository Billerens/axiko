// Real browser regression: the web chat stays live when the phone's event stream dies.
// The real hub and chat run against a fake phone (SSE + RPC) that can let a stream fall silent
// without closing it, or refuse a reconnection with an error page.
//   PLAYWRIGHT_MODULE=… PLAYWRIGHT_CHROMIUM_EXECUTABLE=… node scripts/web/chat-live-browser.cjs
'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const factory = path.resolve(__dirname, '../../app/src/main/assets/factory');
const inject = html => html.replace(/(<!doctype[^>]*>)/i, '$1<script src="/__axiko/client.js"></script>');
const PING_MS = 1000;
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

// ---- the fake phone ------------------------------------------------------------------------------
const phone = {};
function resetPhone() {
  Object.assign(phone, { streams: new Set(), dead: new Set(), opened: 0, refuse: 0, current: 't1', threads: { t1: [] }, busy: {}, sent: [] });
}
function write(line) {
  for (const res of phone.streams) if (!phone.dead.has(res)) res.write(line);
}
function emit(event, data) { write('data: ' + JSON.stringify({ event, data }) + '\n\n'); }
const agentEvent = (thread, data) => emit('agent', Object.assign({ agent: 'axiko', thread }, data));
setInterval(() => write('event: ping\ndata: {}\n\n'), PING_MS).unref();

async function turn(thread, text, words) {
  const items = phone.threads[thread] || (phone.threads[thread] = []);
  phone.busy[thread] = true;
  items.push({ type: 'user', text });
  agentEvent(thread, { type: 'user', text });
  await wait(30);
  agentEvent(thread, { type: 'status', stage: 'request', step: 1, local: false });
  agentEvent(thread, { type: 'tool', id: 'c1', name: 'fs_read', status: 'run', summary: 'fs_read a.js' });
  await wait(200);
  agentEvent(thread, { type: 'tool', id: 'c1', name: 'fs_read', status: 'ok', summary: 'прочитано' });
  items.push({ type: 'tool', id: 'c1', name: 'fs_read', status: 'ok', summary: 'прочитано' });
  const live = { type: 'assistant', text: '', live: true };
  items.push(live);
  for (let i = 0; i < words; i++) {
    await wait(150);
    const word = 'слово' + i + ' ';
    live.text += word;
    agentEvent(thread, { type: 'delta', text: word });
  }
  delete live.live;
  agentEvent(thread, { type: 'message' });
  agentEvent(thread, { type: 'done', stopped: false });
  phone.busy[thread] = false;
  agentEvent(thread, { type: 'idle' });
}

function native(method, args) {
  switch (method) {
    case 'agent.list': return [{ id: 'axiko', title: 'Axiko', icon: 'spark', description: '' }];
    case 'speech.status': return { tts: false, ready: false };
    case 'agent.thread': return phone.current;
    case 'agent.threads': return [];
    case 'agent.history': return JSON.parse(JSON.stringify(phone.threads[args[1] || phone.current] || []));
    case 'agent.busy': return !!phone.busy[args[1] || phone.current];
    case 'agent.send': {
      const thread = args[2] || phone.current;
      phone.sent.push({ thread, text: args[1] });
      setTimeout(() => turn(thread, args[1], phone.words || 12), 5);
      return true;
    }
    case 'agent.queue': return [];
    case 'pref.get': return String(args[0]).startsWith('tours') ? '*' : null;
    default: return null;
  }
}

const server = http.createServer((req, res) => {
  const route = new URL(req.url, 'http://localhost').pathname;
  if (route === '/__axiko/events') {
    if (phone.refuse > 0) {
      phone.refuse--;
      res.writeHead(503, { 'Content-Type': 'text/plain', Connection: 'close' });
      res.end('later');
      return;
    }
    // As the phone does: HTTP/1.1 200 without a length, the hello first, written as events come.
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
    res.write('retry: 3000\nevent: hello\ndata: {"ping":' + PING_MS + '}\n\n');
    phone.opened++;
    phone.streams.add(res);
    req.on('close', () => { phone.streams.delete(res); phone.dead.delete(res); });
    return;
  }
  if (route === '/__axiko/rpc') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      const request = JSON.parse(body);
      let value = null;
      if (request.op === 'native') value = native(request.method, JSON.parse(request.args || '[]'));
      else if (request.op === 'desk.status') value = { name: 'Axiko', factory: false };
      else if (request.op === 'eval') value = request.code.includes('nav.json') ? JSON.stringify({ items: [] }) : [];
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', Connection: 'close' });
      res.end(JSON.stringify({ ok: true, result: JSON.stringify(value) }));
    });
    return;
  }
  const relative = route === '/' ? '/ui/desk/index.html' : route === '/__axiko/client.js' ? '/ui/desk/client.js' : route;
  const file = path.join(factory, relative);
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  const ext = path.extname(file);
  res.writeHead(200, { 'Content-Type': { '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.json': 'application/json', '.svg': 'image/svg+xml' }[ext] || 'application/octet-stream' });
  const data = fs.readFileSync(file);
  res.end(ext === '.html' ? inject(data.toString('utf8')) : data);
});

// ---- the browser ---------------------------------------------------------------------------------
async function chat(page) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    const frame = page.frames().find(item => /\/ui\/chat\/index\.html/.test(item.url()));
    if (frame) {
      try {
        await frame.waitForFunction(() => window.axiko && document.getElementById('input'), null, { timeout: 3000 });
        await frame.waitForFunction(() => document.querySelector('#log .blank') || document.querySelector('#log > *'), null, { timeout: 5000 });
        return frame;
      } catch (error) { /* still loading */ }
    }
    await wait(100);
  }
  throw new Error('The chat did not load in the hub.');
}
const view = frame => frame.evaluate(() => ({
  me: [...document.querySelectorAll('#log .me')].map(node => node.textContent),
  answer: [...document.querySelectorAll('#log .it')].map(node => node.textContent.trim()).join(' '),
  status: document.getElementById('status').hidden ? '' : document.getElementById('status-text').textContent
}));
async function until(frame, test, ms, what) {
  const deadline = Date.now() + ms;
  let last;
  while (Date.now() < deadline) {
    last = await view(frame);
    if (test(last)) return last;
    await wait(100);
  }
  throw new Error(what + ' within ' + ms + ' ms; the chat shows ' + JSON.stringify(last));
}
async function ask(frame, text) {
  await frame.fill('#input', text);
  await frame.click('#send');
}

const scenarios = {
  // A stream that stays open but says nothing is opened anew from its pings alone, before anyone asks.
  async silentStreamIsReplaced(page) {
    const frame = await chat(page);
    const opened = phone.opened;
    for (const res of phone.streams) phone.dead.add(res);
    const started = Date.now();
    while (phone.opened === opened && Date.now() - started < 6000) await wait(100);
    assert.equal(phone.opened, opened + 1, 'the browser opened a new stream');
    assert.ok(Date.now() - started < PING_MS * 4, 'within a few missed pings: ' + (Date.now() - started) + ' ms');
    await ask(frame, 'после тишины');
    await until(frame, v => v.me.includes('после тишины') && v.status, 1500, 'the request and the work are shown live');
    await until(frame, v => v.answer.includes('слово11'), 6000, 'the whole answer is shown');
  },
  // A request sent into a stream that died a moment ago: the chat does not stay frozen while the work goes on.
  async requestIntoDeadStream(page) {
    const frame = await chat(page);
    phone.words = 40;
    for (const res of phone.streams) phone.dead.add(res);
    await ask(frame, 'в мёртвый поток');
    await until(frame, v => v.me.includes('в мёртвый поток'), 5500, 'the request appears though its echo was lost');
    await until(frame, v => v.status || v.answer.includes('слово'), 2000, 'the work in progress is shown');
    await until(frame, v => v.answer.includes('слово39'), 9000, 'the whole answer is shown');
  },
  // A reconnection refused once with an error page: the browser gives such a stream up, the hub does not.
  async refusedStreamIsRetried(page) {
    const frame = await chat(page);
    const opened = phone.opened;
    phone.refuse = 1;
    for (const res of phone.streams) res.destroy();
    const started = Date.now();
    while (phone.opened === opened && Date.now() - started < 12000) await wait(100);
    assert.equal(phone.opened, opened + 1, 'the hub opened the stream again after it was refused');
    assert.equal(phone.refuse, 0);
    await ask(frame, 'после отказа');
    await until(frame, v => v.me.includes('после отказа') && v.status, 1500, 'the request and the work are shown live');
  },
  // Nothing goes wrong: the request and the work are shown as they happen, and nothing is reloaded.
  async liveStreamStaysLive(page) {
    const frame = await chat(page);
    const opened = phone.opened;
    await ask(frame, 'обычный вопрос');
    await until(frame, v => v.me.includes('обычный вопрос') && v.status, 1500, 'the request and the work are shown live');
    await until(frame, v => v.answer.includes('слово11'), 6000, 'the whole answer is shown');
    await wait(PING_MS * 3);
    assert.equal(phone.opened, opened, 'a stream that pings is never replaced');
  }
};

(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = 'http://127.0.0.1:' + server.address().port + '/';
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  const passed = [];
  try {
    for (const [name, run] of Object.entries(scenarios)) {
      resetPhone();
      const context = await browser.newContext({ viewport: { width: 1280, height: 860 } });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto(base);
      try {
        await run(page);
      } catch (error) {
        error.message = name + ': ' + error.message;
        throw error;
      } finally {
        await context.close();
      }
      passed.push(name);
    }
  } finally {
    await browser.close();
    server.close();
  }
  console.log(JSON.stringify({ passed }));
})().catch(error => { console.error(error); process.exit(1); });

// Real browser regression for keyboard input, local audio, and reconnecting a stalled phone stream.
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
const audioWave = (() => {
  // Real mono PCM audio long enough to observe playback and stop before completion.
  const rate = 8000;
  const data = Buffer.alloc(44 + 4 * rate * 2);
  data.write('RIFF', 0);
  data.writeUInt32LE(data.length - 8, 4);
  data.write('WAVEfmt ', 8);
  data.writeUInt32LE(16, 16);
  data.writeUInt16LE(1, 20);
  data.writeUInt16LE(1, 22);
  data.writeUInt32LE(rate, 24);
  data.writeUInt32LE(rate * 2, 28);
  data.writeUInt16LE(2, 32);
  data.writeUInt16LE(16, 34);
  data.write('data', 36);
  data.writeUInt32LE(data.length - 44, 40);
  return data;
})();
function resetPhone() {
  Object.assign(phone, { streams: new Set(), dead: new Set(), opened: 0, refuse: 0, current: 't1', threads: { t1: [] }, busy: {}, sent: [], stopped: 0, audio: [], phoneAudio: [] });
}
function write(line) {
  for (const res of phone.streams) if (!phone.dead.has(res)) res.write(line);
}
function emit(event, data) { write('data: ' + JSON.stringify({ event, data }) + '\n\n'); }
const agentEvent = (thread, data) => emit('agent', Object.assign({ agent: 'axiko', thread }, data));
resetPhone();
setInterval(() => write('event: ping\ndata: {}\n\n'), PING_MS).unref();

async function turn(thread, text, words, browserVoice) {
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
  agentEvent(thread, { type: 'message', browserVoice });
  agentEvent(thread, { type: 'done', stopped: false });
  phone.busy[thread] = false;
  agentEvent(thread, { type: 'idle' });
}

function native(method, args) {
  if (method === 'speech.say' || method === 'speech.stop' || (method === 'pref.set' && args[0] === 'tts.on')) phone.phoneAudio.push(method);
  switch (method) {
    case 'agent.list': return [{ id: 'axiko', title: 'Axiko', icon: 'spark', description: '' }];
    case 'speech.status': return { tts: false, ready: false };
    case 'speech.listen': return new Promise(resolve => { phone.dictation = { id: args[1]?.id, finish: resolve }; });
    case 'agent.thread': return phone.current;
    case 'agent.threads': return [];
    case 'agent.history': return JSON.parse(JSON.stringify(phone.threads[args[1] || phone.current] || []));
    case 'agent.busy': return !!phone.busy[args[1] || phone.current];
    case 'agent.send': {
      const thread = args[2] || phone.current;
      phone.sent.push({ thread, text: args[1], browserVoice: args[5] });
      setTimeout(() => turn(thread, args[1], phone.words || 12, args[5]), 5);
      return true;
    }
    case 'agent.stop': phone.stopped++; return true;
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
  if (route === '/__axiko/rpc' || route === '/__axiko/audio') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', async () => {
      const request = JSON.parse(body);
      if (route === '/__axiko/audio') {
        phone.audio.push({ method: req.method, text: request.text });
        res.writeHead(200, { 'Content-Type': 'audio/wav', 'Content-Length': audioWave.length });
        res.end(audioWave);
        return;
      }
      let value = null;
      if (request.op === 'native') value = await native(request.method, JSON.parse(request.args || '[]'));
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

async function watchAudio(page) {
  // Track the real browser-owned players, leaving source decoding/play/pause untouched.
  await page.addInitScript(() => {
    const createAudio = window.Audio;
    const players = window.chatTestPlayers = [];
    window.Audio = function (src) {
      const player = new createAudio(src);
      players.push(player);
      return player;
    };
  });
}

async function waitForAudio(frame) {
  await frame.waitForFunction(() => window.chatTestPlayers.some(player =>
    player.src.startsWith('blob:') && !player.paused && player.currentTime > 0), null, { timeout: 4000 });
}

const scenarios = {
  async unlimitedRetryShowsCountdownAndCanBeStopped(page) {
    const frame = await chat(page);
    const retryStatus = frame.locator('#retry-status');
    const stop = frame.locator('#retry-stop');
    const input = frame.locator('#input');
    const waiting = { phase: 'waiting', attempt: 12, limit: -1, delayMs: 4000, available: false };
    phone.busy.t1 = true;
    agentEvent('t1', { type: 'user', text: 'Проверка безлимитного повтора' });
    agentEvent('t1', { type: 'retry', retry: waiting });
    await stop.waitFor({ state: 'visible' });
    const initial = await retryStatus.textContent();
    assert.match(initial, /12/);
    assert.doesNotMatch(initial, /-1/);
    const seconds = Number(initial.match(/\d+/)?.[0]);
    assert.ok(seconds > 0);
    await frame.waitForFunction(previous => {
      const current = document.getElementById('retry-status').textContent.match(/\d+/);
      return current && Number(current[0]) < previous;
    }, seconds, { timeout: 3500 });

    await input.fill('Черновик в редакторе');
    agentEvent('t1', { type: 'queue', items: [{ id: 'q1', text: 'Сообщение из очереди', steer: false }] });
    await frame.locator('#queue .item').waitFor({ state: 'visible' });
    await stop.click();
    await frame.waitForFunction(() => document.getElementById('input').value === 'Сообщение из очереди\nЧерновик в редакторе');
    assert.equal(phone.stopped, 1);
    phone.busy.t1 = false;
    agentEvent('t1', { type: 'retry', retry: { phase: 'idle' } });
    agentEvent('t1', { type: 'idle', more: false });
    await stop.waitFor({ state: 'hidden' });
  },

  async browserAudioControls(page) {
    const frame = await chat(page);
    await ask(frame, 'Проверка озвучки');
    await until(frame, v => v.answer.includes('слово11') && !v.status, 6000, 'the finished reply is shown');
    const speaker = frame.locator('#log .said button').last();
    const answer = phone.threads.t1.at(-1).text;
    await speaker.click();
    await waitForAudio(frame);
    assert.deepEqual(phone.audio, [{ method: 'POST', text: answer }]);
    assert.equal(await speaker.getAttribute('data-state'), 'on');

    await speaker.click();
    assert.equal(await speaker.getAttribute('data-state'), '');
    assert.equal(await frame.evaluate(() => window.chatTestPlayers.every(player => player.paused)), true);
    assert.deepEqual(phone.phoneAudio, []);
  },

  async browserAutoAudioStaysInOriginatingBrowser(page) {
    const base = page.url();
    const frame = await chat(page);
    const otherContext = await page.context().browser().newContext();
    try {
      const other = await otherContext.newPage();
      await watchAudio(other);
      await other.goto(base);
      const otherFrame = await chat(other);
      await otherFrame.click('#voice');
      await frame.click('#voice');
      for (const owner of [frame, otherFrame]) {
        assert.equal(await owner.evaluate(() => localStorage.getItem('chat.webSpeak')), 'on');
      }

      await ask(frame, 'Автоматическая озвучка');
      await waitForAudio(frame);
      await until(otherFrame, v => v.answer.includes('слово11') && !v.status, 6000, 'the other browser receives the finished reply');
      assert.match(phone.sent[0].browserVoice, /^web-/);
      assert.equal(phone.audio.length, 1);
      assert.equal(await otherFrame.evaluate(() => window.chatTestPlayers.length), 0);
      assert.deepEqual(phone.phoneAudio, []);

      await frame.click('#voice');
      assert.equal(await frame.evaluate(() => window.chatTestPlayers.every(player => player.paused)), true);
      assert.equal(await frame.evaluate(() => localStorage.getItem('chat.webSpeak')), 'off');
      assert.equal(await otherFrame.evaluate(() => localStorage.getItem('chat.webSpeak')), 'on');
      assert.deepEqual(phone.phoneAudio, []);
    } finally {
      await otherContext.close();
    }
  },

  async keyboardComposer(page) {
    const frame = await chat(page);
    const composer = frame.locator('#input');
    await composer.press('Enter');
    assert.equal(await composer.inputValue(), '');
    assert.equal(phone.sent.length, 0);

    await composer.fill('Первая строка');
    await composer.press('Shift+Enter');
    await composer.press('End');
    await page.keyboard.insertText('Вторая строка');
    const multiline = 'Первая строка\nВторая строка';
    assert.equal(await composer.inputValue(), multiline);
    assert.equal(phone.sent.length, 0);

    for (const options of [{ isComposing: true }, { keyCode: 229 }]) {
      const kept = await composer.evaluate((input, options) => input.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, ...options })), options);
      assert.equal(kept, true, 'the IME still gets Enter');
      assert.equal(await composer.inputValue(), multiline);
      assert.equal(phone.sent.length, 0);
    }

    await composer.press('Enter');
    await until(frame, v => v.status, 1500, 'the keyboard request starts the turn');
    assert.deepEqual(phone.sent.map(item => item.text), [multiline]);
    assert.equal(await composer.inputValue(), '');

    await composer.press('Enter');
    await composer.fill('Уточнение по ходу работы');
    await composer.press('Control+Enter');
    await until(frame, () => phone.sent.length === 2, 1500, 'the pending work accepts the follow-up');
    assert.deepEqual(phone.sent.map(item => item.text), [multiline, 'Уточнение по ходу работы']);
    assert.equal(phone.stopped, 0);
  },
  async provisionalSpeech(page) {
    const frame = await chat(page);
    const composer = frame.locator('#input');
    const preview = frame.locator('#speech-preview');
    const snippet = preview.locator('#speech-preview-text');
    const waitForDictation = async previous => {
      const deadline = Date.now() + 2500;
      while (Date.now() < deadline) {
        const request = phone.dictation;
        if (request?.id && request.id !== previous) return request;
        await wait(25);
      }
      throw new Error('The microphone did not start a new dictation request');
    };
    await composer.fill('Мой черновик');
    await frame.click('#mic');
    const first = await waitForDictation();
    emit('speech.partial', { id: 'other-screen', mode: 'listen', text: 'Чужое распознавание' });
    emit('speech.partial', { id: first.id, mode: 'stream', text: 'Запись другого модуля' });
    assert.equal(await preview.isVisible(), false);
    emit('speech.partial', { id: first.id, mode: 'listen', text: 'ещё' });
    await snippet.getByText('ещё', { exact: true }).waitFor();
    emit('speech.partial', { id: first.id, mode: 'listen', text: 'ещё несколько слов' });
    await snippet.getByText('ещё несколько слов', { exact: true }).waitFor();
    assert.equal(await composer.inputValue(), 'Мой черновик');
    assert.equal(phone.sent.length, 0);
    first.finish('точная фраза');
    await frame.waitForFunction(() => document.getElementById('input').value === 'Мой черновик точная фраза');
    await preview.waitFor({ state: 'hidden' });
    assert.equal(phone.sent.length, 0);

    await composer.fill('');
    await frame.click('#mic');
    const second = await waitForDictation(first.id);
    emit('speech.partial', { id: first.id, mode: 'listen', text: 'Запоздавший черновик' });
    emit('speech.partial', { id: second.id, mode: 'listen', text: 'оконч…' });
    await snippet.getByText('оконч…', { exact: true }).waitFor();
    assert.equal(phone.sent.length, 0);
    second.finish('Окончательный текст');
    await until(frame, () => phone.sent.length === 1, 1500, 'only the final phrase is sent');
    assert.equal(phone.sent[0].text, 'Окончательный текст');
    await preview.waitFor({ state: 'hidden' });

    const live = { id: 'live-test', agent: 'axiko', thread: 't1' };
    emit('live', { ...live, state: 'hearing', active: true, level: 0.1 });
    emit('live.partial', { ...live, text: 'предварительная реплика' });
    await snippet.getByText('предварительная реплика', { exact: true }).waitFor();
    emit('live.partial', { ...live, thread: 'different', text: 'Другой разговор' });
    emit('live.partial', { ...live, id: 'old-id', text: 'Старая запись' });
    emit('live.partial', { ...live, text: 'предварительная реплика обновлена' });
    await snippet.getByText('предварительная реплика обновлена', { exact: true }).waitFor();
    assert.equal(phone.sent.length, 1);
    emit('live', { ...live, state: 'off', active: false });
    await preview.waitFor({ state: 'hidden' });
  },
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
      await watchAudio(page);
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

// A stand-in for a model provider, for testing the agent loop without a key or a bill:
//   node scripts/mock-llm.mjs        → http://localhost:8787/v1  (from the emulator: http://10.0.2.2:8787/v1)
// It plays a fixed scenario: asked anything, it builds a small "Заметки" module through the
// app's tools, checks it, looks at the screen and reports. Tool-call arguments are streamed
// in small pieces, the way real providers send them.
import http from 'node:http';

const PORT = 8787;

const MODULE_JSON = JSON.stringify({ title: 'Заметки', description: 'Короткие записи' }, null, 1);

const ICON_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 13h6M9 17h4"/>
</svg>
`;

const MAIN_JS = `var assi = require('lib/assi');
var notes = assi.store('notes');

exports.list = function () {
  return notes.get('items', []);
};

exports.add = function (text) {
  var items = notes.get('items', []);
  items.unshift({ id: Date.now(), text: String(text), time: Date.now() });
  notes.set('items', items);
  return items;
};

exports.remove = function (id) {
  var items = notes.get('items', []).filter(function (item) { return item.id !== id; });
  notes.set('items', items);
  return items;
};
`;

const INDEX_HTML = `<!doctype html>
<html lang="ru">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Заметки</title>
<link rel="stylesheet" href="/ui/kit/kit.css">
<body>
<main class="page">
  <header class="topbar">
    <button class="icon-btn" onclick="assi.back()" aria-label="Назад" data-icon="back"></button>
    <h1>Заметки</h1>
  </header>
  <div class="row">
    <input class="input grow" id="text" placeholder="Новая заметка">
    <button class="btn primary" id="add">Добавить</button>
  </div>
  <div class="list" id="list"></div>
</main>
<script src="/ui/kit/kit.js"></script>
<script>
  const MAIN = 'modules/notes/main.js';
  const list = document.getElementById('list');
  const text = document.getElementById('text');

  function render(items) {
    if (!items.length) {
      list.replaceChildren(ui.h('div', { class: 'empty' }, 'Заметок пока нет. Запишите первую.'));
      return;
    }
    list.replaceChildren(...items.map(item =>
      ui.h('div', {},
        ui.h('div', { class: 'grow' }, ui.h('div', {}, item.text), ui.h('div', { class: 'small muted' }, ui.ago(item.time))),
        ui.h('button', { class: 'icon-btn', 'aria-label': 'Удалить', onclick: async () => render(await assi.call(MAIN, 'remove', item.id)) }, ui.icon('trash')))));
  }

  document.getElementById('add').onclick = async () => {
    if (!text.value.trim()) return;
    render(await assi.call(MAIN, 'add', text.value.trim()));
    text.value = '';
  };
  assi.call(MAIN, 'list').then(render);
</script>
</body>
</html>
`;

/** What the "model" does at each step of the scenario. */
const STEPS = [
  {
    text: 'Сделаю модуль заметок: логика, экран и плитка на главном.',
    calls: [
      ['fs_write', { path: 'modules/notes/module.json', content: MODULE_JSON }],
      ['fs_write', { path: 'modules/notes/icon.svg', content: ICON_SVG }],
      ['fs_write', { path: 'modules/notes/main.js', content: MAIN_JS }],
      ['fs_write', { path: 'modules/notes/ui/index.html', content: INDEX_HTML }],
    ],
  },
  { calls: [['js_eval', { code: "var m = require('modules/notes/main.js'); m.add('Проверка'); m.list().length" }]] },
  { calls: [['ui_open', { path: 'modules/notes/ui/index.html' }]] },
  { calls: [['ui_screenshot', {}]] },
  { text: 'Готово: на главном экране появилась плитка **«Заметки»**. Там можно:\n\n- записать заметку;\n- удалить её крестиком.\n\nПроверочную запись я оставил — её можно удалить.' },
];

function stepOf(messages) {
  let lastUser = -1;
  messages.forEach((message, index) => {
    if (message.role === 'user' && typeof message.content === 'string') lastUser = index;
  });
  return messages.slice(lastUser + 1).filter(message => message.role === 'assistant').length;
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function stream(response, step) {
  response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
  const send = delta => response.write('data: ' + JSON.stringify({ choices: [{ index: 0, delta }] }) + '\n\n');
  response.write(': keep-alive\n\n');
  send({ role: 'assistant', content: '' });
  for (const word of (step.text || '').split(/(?<=\s)/)) {
    if (!word) continue;
    send({ content: word });
    await sleep(25);
  }
  let index = 0;
  for (const [name, args] of step.calls || []) {
    send({ tool_calls: [{ index, id: 'call_' + Date.now() + '_' + index, type: 'function', function: { name, arguments: '' } }] });
    const text = JSON.stringify(args);
    for (let at = 0; at < text.length; at += 37) {
      send({ tool_calls: [{ index, function: { arguments: text.slice(at, at + 37) } }] });
    }
    index++;
  }
  response.write('data: ' + JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: step.calls ? 'tool_calls' : 'stop' }] }) + '\n\n');
  // The closing chunk real providers send: no choices, only what the answer cost.
  response.write('data: ' + JSON.stringify({ choices: [], usage: { prompt_tokens: 6200, completion_tokens: 180, prompt_tokens_details: { cached_tokens: 5000 }, cost: 0.0042 } }) + '\n\n');
  response.write('data: [DONE]\n\n');
  response.end();
}

http.createServer((request, response) => {
  let body = '';
  // Text, not bytes: a letter of two bytes may be split between two chunks.
  request.setEncoding('utf8');
  request.on('data', chunk => { body += chunk; });
  request.on('end', async () => {
    console.log(new Date().toISOString().slice(11, 19), request.method, request.url, body.length + ' bytes');
    if (request.url.endsWith('/models')) {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ data: [
        { id: 'mock-1', architecture: { input_modalities: ['text', 'image'] }, supported_parameters: ['tools', 'reasoning'] },
        { id: 'mock-small', architecture: { input_modalities: ['text'] }, supported_parameters: ['tools'] },
        { id: 'mock-tts' },
      ] }));
      return;
    }
    // A stand-in OAuth provider: "signs the owner in" at once and sends the browser back with a code.
    if (request.url.startsWith('/oauth/authorize')) {
      const query = new URL(request.url, 'http://x').searchParams;
      console.log('  oauth authorize: client', query.get('client_id'), '| scope', query.get('scope'), '| challenge', Boolean(query.get('code_challenge')));
      response.writeHead(302, { Location: query.get('redirect_uri') + '?code=mock-code&state=' + encodeURIComponent(query.get('state')) });
      response.end();
      return;
    }
    if (request.url.startsWith('/oauth/token')) {
      const form = new URLSearchParams(body);
      console.log('  oauth token:', form.get('grant_type'), '| verifier', Boolean(form.get('code_verifier')));
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ access_token: 'mock-access-' + form.get('grant_type'), refresh_token: 'mock-refresh', expires_in: form.get('grant_type') === 'refresh_token' ? 3600 : 30 }));
      return;
    }
    if (request.url.endsWith('/embeddings')) {
      // Toy vectors: counts of letter pairs, so texts with common words come out close.
      const vector = text => {
        const out = new Array(64).fill(0);
        const clean = String(text).toLowerCase();
        for (let i = 0; i + 1 < clean.length; i++) out[(clean.charCodeAt(i) * 31 + clean.charCodeAt(i + 1)) % 64] += 1;
        return out;
      };
      const input = JSON.parse(body || '{}').input || [];
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ data: (Array.isArray(input) ? input : [input]).map((text, index) => ({ index, embedding: vector(text) })) }));
      return;
    }
    if (request.url.endsWith('/audio/speech')) {
      // Raw PCM, 24 kHz mono: one second of a soft falling tone, in two chunks like a stream.
      const rate = 24000;
      const pcm = Buffer.alloc(rate * 2);
      for (let i = 0; i < rate; i++) {
        const fade = Math.min(1, i / 600, (rate - i) / 6000);
        pcm.writeInt16LE(Math.round(Math.sin(2 * Math.PI * (520 - 160 * i / rate) * i / rate) * 9000 * fade), i * 2);
      }
      console.log('  speech:', body.slice(0, 160));
      response.writeHead(200, { 'Content-Type': 'audio/pcm;rate=24000;channels=1' });
      response.write(pcm.subarray(0, 20001));
      setTimeout(() => response.end(pcm.subarray(20001)), 150);
      return;
    }
    if (request.url.endsWith('/chat/completions')) {
      const payload = JSON.parse(body || '{}');
      const images = (payload.messages || []).filter(message => Array.isArray(message.content)).length;
      const tools = (payload.tools || []).map(tool => tool.function.name);
      console.log('  model:', payload.model, '| messages:', (payload.messages || []).length, '| images:', images, '| tools:', tools.length,
        '| screenshot tool:', tools.includes('ui_screenshot'), '| effort:', JSON.stringify(payload.reasoning || payload.reasoning_effort || null));
      if (!payload.stream) {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: 'Ответ подставной модели ' + payload.model } }] }));
        return;
      }
      // For the check of messages sent while the agent works: a step that takes a while, then an
      // answer that says whether the owner's words reached the model in the middle of the work.
      const said = (payload.messages || []).filter(message => message.role === 'user' && typeof message.content === 'string').map(message => message.content);
      if (said.some(text => text.includes('проверка очереди'))) {
        const steered = said.filter(text => text.includes('пока вы работали'));
        const worked = (payload.messages || []).some(message => message.role === 'tool');
        await stream(response, steered.length ? { text: 'Учёл: ' + steered[steered.length - 1].split('\n')[0] }
          : worked ? { text: 'Готово без вмешательства' } : { calls: [['js_eval', { code: 'sleep(2500); 1' }]] });
        return;
      }
      // For the check of the step limit: a model that never stops by itself. Asked without
      // tools — that is how the app asks at the limit — it says where it stands.
      if (said.some(text => text.includes('проверка предела шагов'))) {
        const done = (payload.messages || []).filter(message => message.role === 'tool').length;
        await stream(response, tools.length ? { calls: [['js_eval', { code: String(done + 1) }]] }
          : { text: 'Остановился на пределе: сделано шагов — ' + done + '. Осталось продолжить счёт.' });
        return;
      }
      const step = STEPS[Math.min(stepOf(payload.messages || []), STEPS.length - 1)];
      await stream(response, step);
      return;
    }
    response.writeHead(404);
    response.end('{}');
  });
}).listen(PORT, () => console.log('Mock model provider: http://localhost:' + PORT + '/v1'));

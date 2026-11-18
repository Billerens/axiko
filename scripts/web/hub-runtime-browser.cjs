// Browser regressions for progressive data, stable focus and the editor's first-use loading.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../../app/src/main/assets/factory');
const streams = new Set(), waiting = [], resources = [], reads = [];
let holdMetadata = true, moduleCalls = 0, editorMode = 'valid';
let modules = [
  { id: 'notes', title: 'Заметки', description: 'Идеи рядом', icon: 'note', entry: 'modules/notes/ui/index.html' },
  { id: 'games', title: 'Игротека', description: 'Короткая пауза', icon: 'play', entry: 'modules/games/ui/index.html' }
];
const nav = { items: [{ title: 'Главная', icon: 'home', open: 'ui/home/index.html' }] };
function reply(res, value) {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ result: JSON.stringify(value) }));
}
function metadata(res, value) {
  if (holdMetadata) waiting.push(() => reply(res, value));
  else reply(res, value);
}
function releaseMetadata() { holdMetadata = false; while (waiting.length) waiting.shift()(); }
function count(name) { return resources.filter(resource => resource === '/ui/desk/' + name).length; }
const inject = html => html.replace(/(<!doctype[^>]*>)/i, '$1<script src="/__axiko/client.js"></script>');
const server = http.createServer((req, res) => {
  const route = new URL(req.url, 'http://localhost').pathname;
  if (route === '/__axiko/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.flushHeaders();
    streams.add(res); req.on('close', () => streams.delete(res)); return;
  }
  if (route === '/__axiko/rpc') {
    let body = ''; req.on('data', chunk => body += chunk);
    req.on('end', () => {
      const request = JSON.parse(body);
      if (request.op === 'desk.status') return metadata(res, { name: 'Axiko', factory: false });
      if (request.op === 'desk.read') {
        reads.push(request);
        return reply(res, { path: request.path, text: 'contents of ' + request.path, revision: 'r1', exists: true });
      }
      if (request.op === 'eval') {
        if (request.code.includes('ui/nav.json')) return metadata(res, JSON.stringify(nav));
        return reply(res, []);
      }
      if (request.op === 'native' && request.method === 'modules.list') { moduleCalls++; return reply(res, modules); }
      if (request.op === 'native' && request.method === 'tasks.list') return metadata(res, []);
      // The owner of this hub has seen the tours: they would stand over what the test clicks.
      if (request.op === 'native' && request.method === 'pref.get' && String(request.args).includes('tours.done')) return reply(res, '*');
      reply(res, []);
    }); return;
  }
  if (route === '/ui/chat/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html' }); res.end(inject('<!doctype html><textarea></textarea>')); return;
  }
  let relative = route === '/' ? '/ui/desk/index.html' : route === '/__axiko/client.js' ? '/ui/desk/client.js' : route;
  resources.push(relative);
  if (relative === '/ui/desk/editor-model.js' && editorMode === 'missing-model') {
    res.writeHead(200, { 'Content-Type': 'text/javascript' }); res.end('/* incomplete model */'); return;
  }
  if (relative === '/ui/desk/editor.js' && editorMode !== 'valid') {
    if (editorMode === 'network') { res.writeHead(503); res.end(); return; }
    if (editorMode === 'missing-editor') {
      res.writeHead(200, { 'Content-Type': 'text/javascript' }); res.end('/* incomplete editor */'); return;
    }
    if (editorMode === 'mount-error') {
      res.writeHead(200, { 'Content-Type': 'text/javascript' });
      res.end('window.AxikoEditor={mount:function(){throw new Error("Editor mount failed")}};'); return;
    }
  }
  const file = path.join(root, relative);
  if (!fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  const ext = path.extname(file);
  res.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript' }[ext] || 'text/plain' });
  const text = fs.readFileSync(file, 'utf8'); res.end(ext === '.html' ? inject(text) : text);
});

(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  try {
    const origin = 'http://127.0.0.1:' + server.address().port;
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(origin, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('.module-tile', { timeout: 3000 });
    assert.equal(holdMetadata, true);
    assert.equal(waiting.length, 3, 'tasks, menu and identity remain unresolved while modules are already usable');
    for (const asset of ['editor.css', 'editor-model.js', 'editor.js']) assert.equal(count(asset), 0, asset + ' must not load on overview');
    await page.locator('[data-module="notes"]').focus();
    await page.evaluate(() => {
      window.qaCard = document.querySelector('[data-module="notes"]');
      window.qaNav = document.querySelector('#primary-nav button');
      window.dispatchEvent(new Event('desk.file.saved'));
    });
    await page.waitForFunction(() => document.querySelector('[data-module="notes"]') === window.qaCard);
    while (moduleCalls < 2) await page.waitForTimeout(25);
    await page.waitForTimeout(50);
    assert.equal(await page.evaluate(() => document.activeElement === qaCard && qaCard.isConnected), true);
    assert.equal(await page.evaluate(() => document.querySelector('#primary-nav button') === qaNav), true);
    assert.equal(waiting.length, 3, 'a slow source is coalesced, not duplicated');
    await page.locator('#module-filter').fill('Заметки');
    modules = modules.concat({ id: 'pet', title: 'Питомец', description: 'Сосед', icon: 'heart', entry: 'modules/pet/ui/index.html' });
    await page.evaluate(() => window.dispatchEvent(new Event('desk.file.saved')));
    await page.waitForFunction(() => document.querySelector('#module-count').textContent === '3');
    assert.equal(await page.locator('.module-tile').count(), 1);
    releaseMetadata();
    await page.waitForTimeout(150);
    console.log(JSON.stringify({ progressiveModules: true, slowMetadataDoesNotBlockRefresh: true,
      unchangedNodesKeepFocus: true, filteredTotalUpdated: true, initialEditorRequests: 0 }));
    await page.close();

    for (const mode of ['network', 'missing-editor', 'missing-model', 'mount-error']) {
      editorMode = mode;
      const currentReads = reads.length;
      const initialEditor = count('editor.js'), initialModel = count('editor-model.js'), initialCss = count('editor.css');
      const p = await browser.newPage({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' });
      p.on('pageerror', error => errors.push(error.message));
      await p.goto(origin, { waitUntil: 'domcontentloaded' });
      await p.waitForSelector('.module-tile');
      assert.equal(await p.locator('#sidebar').evaluate(node => node.inert), true, 'closed mobile navigation cannot steal focus');
      await p.evaluate(() => {
        axikoDesk.openFile('a.js');
        axikoDesk.openFile('b.js', { draft: 'd2' });
      });
      await p.waitForSelector('.editor-error');
      editorMode = 'valid';
      await p.locator('.editor-error button').click();
      await p.waitForFunction(() => document.querySelector('.ae-code')?.value === 'contents of b.js');
      assert.deepEqual(reads.slice(currentReads).map(item => ({ path: item.path, draft: item.draft })), [{ path: 'b.js', draft: 'd2' }]);
      assert.equal(count('editor.css') - initialCss, 1);
      assert.equal(count('editor-model.js') - initialModel, mode === 'missing-model' ? 2 : 1);
      assert.equal(count('editor.js') - initialEditor, mode === 'missing-model' ? 1 : 2);
      const afterMount = resources.length;
      await p.evaluate(() => axikoDesk.openFile('a.js'));
      await p.waitForFunction(() => document.querySelector('.ae-code')?.value === 'contents of a.js');
      assert.equal(resources.length, afterMount, 'opening a second file reuses the editor assets');
      assert.equal(await p.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      await p.close();
      console.log(JSON.stringify({ lazyEditorFailure: mode, retryWorks: true, latestFileAndDraftPreserved: true, assetsReused: true }));
    }
    assert.deepEqual(errors, []);
  } finally {
    releaseMetadata();
    await browser.close();
    for (const stream of streams) stream.destroy();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });

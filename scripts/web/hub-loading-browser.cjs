// Real browser regression: a usable document must not wait for every image's load event.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const factory = path.resolve(__dirname, '../../app/src/main/assets/factory');
const held = new Map(), streams = new Set(), visits = new Map();
const inject = html => html.replace(/(<!doctype[^>]*>)/i, '$1<script src="/__axiko/client.js"></script>');
function release(key) {
  for (const res of held.get(key) || []) res.end('');
  held.delete(key);
}
function hold(key, res, type) {
  res.writeHead(200, { 'Content-Type': type }); res.flushHeaders();
  if (!held.has(key)) held.set(key, new Set());
  held.get(key).add(res);
  res.on('close', () => held.get(key)?.delete(res));
}
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const page = url.pathname;
  if (page === '/__axiko/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.flushHeaders();
    streams.add(res); req.on('close', () => streams.delete(res)); return;
  }
  if (page === '/__axiko/rpc') {
    let body = ''; req.on('data', chunk => body += chunk);
    req.on('end', () => {
      const request = JSON.parse(body);
      let value = null;
      if (request.op === 'desk.status') value = { name: 'Axiko', factory: false };
      else if (request.op === 'eval') value = request.code.includes('ui/nav.json') ? '{"items":[]}' : [];
      // The owner of this hub has seen the tours: they would stand over what the test clicks.
      else if (request.op === 'native' && request.method === 'pref.get' && String(request.args).includes('tours.done')) value = '*';
      else if (request.op === 'native') value = [];
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ result: JSON.stringify(value) }));
    }); return;
  }
  if (page === '/held-image') return hold('image', res, 'image/png');
  if (page === '/held-script') return hold(url.searchParams.get('case'), res, 'text/javascript');
  if (page.startsWith('/modules/test/')) {
    visits.set(page, (visits.get(page) || 0) + 1);
    let html = '<!doctype html><meta charset=utf-8><h1 id="content">Usable screen</h1>';
    if (page.endsWith('image.html')) html += '<img src="/held-image" alt="Optional picture">';
    if (page.endsWith('blocked.html')) html += '<script src="/held-script?case=blocked"></script>';
    if (page.endsWith('retry.html') && visits.get(page) === 1) html += '<script src="/held-script?case=retry"></script>';
    res.writeHead(200, { 'Content-Type': 'text/html' }); res.end(inject(html)); return;
  }
  if (page === '/ui/chat/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html' }); res.end(inject('<!doctype html><textarea></textarea>')); return;
  }
  const relative = page === '/' ? 'ui/desk/index.html' : page === '/__axiko/client.js' ? 'ui/desk/client.js' : page.slice(1);
  const file = path.join(factory, relative);
  if (!fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  const ext = path.extname(file);
  res.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }[ext] || 'text/plain' });
  const content = fs.readFileSync(file, 'utf8'); res.end(ext === '.html' ? inject(content) : content);
});

(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  try {
    const page = await browser.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    // Shorten only the hub's 15-second screen watchdog; all navigation and rendering remain real.
    await page.addInitScript(() => {
      if (window.parent !== window) return;
      const original = window.setTimeout;
      window.setTimeout = (callback, delay, ...args) => original(callback, delay === 15000 ? 250 : delay, ...args);
    });
    await page.goto('http://127.0.0.1:' + server.address().port, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => !!window.axikoDesk && document.querySelector('#connection').dataset.state === 'online');
    await page.waitForTimeout(150);
    const open = route => page.evaluate(route => axikoDesk.open('modules/test/ui/' + route), route);
    const warning = () => page.locator('#hub-notice').isVisible();

    await open('image.html');
    await page.frameLocator('#page').locator('#content').waitFor();
    await page.waitForTimeout(400);
    assert.equal(await page.locator('#frame-loading').isVisible(), false, 'DOM readiness releases the loading overlay');
    assert.equal(await warning(), false, 'a slow optional image must not show a connection warning');
    assert.equal(await page.evaluate(() => document.querySelector('#page').contentDocument.readyState), 'interactive');
    release('image');

    await open('blocked.html');
    await page.frameLocator('#page').locator('#content').waitFor();
    assert.equal(await page.evaluate(() => axikoDesk.ready(document.querySelector('#chat').contentWindow, '/modules/test/ui/blocked.html')), false);
    assert.equal(await page.evaluate(() => axikoDesk.ready(document.querySelector('#page').contentWindow, '/modules/test/ui/previous.html')), false);
    await page.locator('#hub-notice').waitFor();
    release('blocked');
    await page.waitForFunction(() => document.querySelector('#hub-notice').hidden);

    await open('retry.html');
    await page.locator('#hub-notice').waitFor();
    await page.locator('#hub-retry').click();
    await page.waitForFunction(() => document.querySelector('#hub-notice').hidden && document.querySelector('#page').contentDocument.readyState === 'complete');
    assert.equal(visits.get('/modules/test/ui/retry.html'), 2, 'retry reloads the requested screen');
    release('retry');

    await open('blocked.html');
    await page.frameLocator('#page').locator('#content').waitFor();
    await page.locator('#brand').click();
    await page.waitForTimeout(400);
    assert.equal(await warning(), false, 'a hidden old screen cannot warn over the overview');
    release('blocked');
    await open('ready.html');
    await page.waitForFunction(() => document.querySelector('#page').contentDocument.readyState === 'complete');
    await page.locator('#brand').click();
    await open('ready.html#section');
    await page.waitForTimeout(400);
    assert.equal(await warning(), false, 'same-document hash navigation needs no new load event');
    assert.equal(await page.locator('#frame-loading').isVisible(), false);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ delayedImage: true, lateReadyClearsWarning: true, retryNavigates: true,
      staleFrameIgnored: true, overviewCancelsWarning: true, hashNavigation: true, errors }));
  } finally {
    await browser.close();
    for (const key of held.keys()) release(key);
    for (const stream of streams) stream.destroy();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });

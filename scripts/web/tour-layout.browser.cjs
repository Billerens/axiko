// Real kit/tour regression against a higher native chat hit area. No app or device is changed.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { execFileSync } = require('node:child_process');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../..');
const factory = path.join(root, 'app/src/main/assets/factory');
const out = process.env.TOUR_ARTIFACTS || path.join(root, 'build/artifacts/tour-layout');
fs.mkdirSync(out, { recursive: true });
const baseline = process.env.TOUR_USE_BASELINE === '1';
const source = relative => baseline && ['ui/kit/kit.js', 'ui/kit/kit.css'].includes(relative)
  ? execFileSync('git', ['show', 'HEAD:app/src/main/assets/factory/' + relative], { cwd: root })
  : fs.readFileSync(path.join(factory, relative));
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (url.pathname === '/') {
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.end(`<!doctype html><html lang="ru"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/ui/kit/kit.css"><body>
      <main style="height:1600px">Тест экрана</main><button id="native-chat" style="position:fixed;z-index:2147483647;right:8px;bottom:0;width:104px;height:78px;background:#d44">Чат</button>
      <script>window.nativePresses=0;document.getElementById('native-chat').onclick=()=>nativePresses++;window.assi={platform:${JSON.stringify(url.searchParams.get('platform') || 'android')},on(){},native(){return Promise.resolve(null)}};</script>
      <script src="/ui/kit/kit.js"></script></body></html>`);
    return;
  }
  const relative = url.pathname.slice(1);
  const file = path.join(factory, relative);
  if (!file.startsWith(factory + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { res.writeHead(404); res.end(); return; }
  res.setHeader('Content-Type', { '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml' }[path.extname(file)] || 'text/plain');
  res.end(source(relative));
});
const scenarios = [
  { name: 'fold-cover', w: 380, h: 862 },
  { name: 'narrow', w: 344, h: 500 },
  { name: 'short', w: 380, h: 320 },
  { name: 'keyboard-height', w: 380, h: 240 },
  { name: 'narrow-short-large-text', w: 320, h: 300, font: 24 },
  { name: 'unfolded', w: 760, h: 320 },
  { name: 'plain-button', w: 380, h: 500, plain: true },
  { name: 'desktop', w: 1280, h: 720, platform: 'web' },
  { name: 'web-narrow', w: 344, h: 500, platform: 'web' },
];
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  const results = [];
  try {
    for (const scenario of scenarios) {
      const page = await browser.newPage({ viewport: { width: scenario.w, height: scenario.h }, reducedMotion: 'reduce' });
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      await page.goto('http://127.0.0.1:' + server.address().port + '/?platform=' + (scenario.platform || 'android'));
      await page.evaluate(s => {
        if (s.font) document.body.style.fontSize = s.font + 'px';
        const chat = document.getElementById('native-chat');
        if (s.platform === 'web') chat.hidden = true;
        if (s.plain) Object.assign(chat.style, { right: '18px', bottom: '22px', width: '56px', height: '56px' });
      }, scenario);
      const start = async long => page.evaluate(long => {
        window.tourResult = 'running';
        ui.tour([
          { target: { top: 0, left: 0, width: innerWidth, height: innerHeight }, title: 'Весь экран', text: 'Кнопки руководства должны оставаться доступны, даже когда большой раздел занимает всё окно.' },
          { target: { top: 30, left: 20, width: innerWidth - 40, height: innerHeight - 60 }, title: 'Подробнее', text: long ? 'Длинное объяснение с важными подробностями. '.repeat(100) : 'Ещё один шаг руководства.' },
          { target: ui.tour.chatButton, title: 'Остались вопросы?', text: 'Спросите в чате. Завершить руководство можно отдельной кнопкой.' }
        ]).then(result => { window.tourResult = result; });
      }, long);
      async function controls() {
        const buttons = page.locator('.tour-row button:visible');
        const heights = [];
        for (let i = 0; i < await buttons.count(); i++) {
          const button = buttons.nth(i); await button.scrollIntoViewIfNeeded();
          const hit = await button.evaluate(node => {
            const r = node.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
            const at = document.elementFromPoint(x, y);
            return { label: node.textContent, visible: x >= 0 && y >= 0 && x < innerWidth && y < innerHeight,
              hit: at === node || node.contains(at), x, y, height: r.height };
          });
          assert.ok(hit.visible && hit.hit, scenario.name + ': inaccessible ' + JSON.stringify(hit));
          heights.push(hit.height);
        }
        assert.ok(heights.every(height => height >= 44), scenario.name + ': short tap target');
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false, scenario.name + ': horizontal overflow');
      }
      await start(true);
      await page.locator('.tour-card').waitFor();
      await controls();
      await page.getByRole('button', { name: 'Далее', exact: true }).click();
      await page.waitForFunction(() => document.querySelector('.tour-count').textContent === '2 / 3');
      await controls();
      assert.ok(await page.locator('.tour-copy').evaluate(node => node.scrollHeight > node.clientHeight), scenario.name + ': long copy should scroll');
      await page.getByRole('button', { name: 'Назад', exact: true }).click();
      await page.waitForFunction(() => document.querySelector('.tour-count').textContent === '1 / 3');
      await page.getByRole('button', { name: 'Далее', exact: true }).click();
      await page.getByRole('button', { name: 'Далее', exact: true }).click();
      await page.waitForFunction(() => document.querySelector('.tour-count').textContent === '3 / 3');
      await controls();
      await page.screenshot({ path: path.join(out, scenario.name + '.png') });
      await page.getByRole('button', { name: 'Понятно', exact: true }).click();
      await page.waitForFunction(() => window.tourResult === true);
      await start(false); await controls();
      await page.getByRole('button', { name: 'Пропустить', exact: true }).click();
      await page.waitForFunction(() => window.tourResult === false);
      assert.equal(await page.evaluate(() => nativePresses), 0, scenario.name + ': chat was invoked');
      assert.deepEqual(errors, [], scenario.name + ': JS errors');
      results.push({ ...scenario, passed: true, nextBackDoneSkipClickable: true, nativeChatInvoked: false });
      await page.close();
    }
    fs.writeFileSync(path.join(out, 'report.json'), JSON.stringify({ passed: true, baseline, scenarios: results }, null, 2));
    console.log(JSON.stringify({ passed: results.length, baseline }));
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; server.close(); });

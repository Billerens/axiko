// Integration check against an isolated emulator hub; no phone or ADB access.
const fs = require('node:fs');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.AXIKO_PLAYWRIGHT_MODULE || 'playwright');

(async () => {
  const session = JSON.parse(fs.readFileSync(process.env.AXIKO_HUB_SESSION, 'utf8').replace(/^\uFEFF/, ''));
  assert.equal(session.serial, 'emulator-5554', 'Use an isolated emulator session');
  const url = new URL(session.url);
  assert.equal(url.hostname, '127.0.0.1');
  const browser = await chromium.launch({ headless: true, executablePath: process.env.AXIKO_CHROMIUM_EXECUTABLE });
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 920 } });
    await context.addCookies([{ name: 'axiko', value: crypto.createHash('sha256').update('axiko:' + session.code).digest('hex').slice(0, 32), url: url.origin, httpOnly: true }]);
    const page = await context.newPage(), errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(url.origin, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.axikoDesk && document.querySelector('#connection').dataset.state === 'online');
    await page.evaluate(() => axikoDesk.open('modules/games/wave-survival/ui/index.html'));
    const frame = page.frameLocator('#page');
    await frame.locator('.ws-tabs .ws-tab').first().waitFor();
    assert.equal(await page.locator('#desk').evaluate(node => node.classList.contains('stage')), false, 'Opening a game must retain the hub');
    await page.locator('#stage-toggle').click();
    await page.waitForFunction(() => document.querySelector('#desk').classList.contains('stage'));
    await page.locator('#stage-exit').click();
    await page.waitForFunction(() => !document.querySelector('#desk').classList.contains('stage'));
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ emulator: session.serial, embeddedOnOpen: true, explicitFullscreen: true, exitFullscreen: true, errors }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });

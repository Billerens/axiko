const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const factory = path.resolve(__dirname, '../../app/src/main/assets/factory');
const origin = 'https://axiko.local';
const sections = [
  ['assistant', '#assistant-talk select, #assistant-look select', 5],
  ['voice', '#voice select', 1],
  ['agents', '#retry-limit select', 1],
  ['look', '#lang select', 1],
];

async function openSettings(browser, width) {
  const page = await browser.newPage({
    viewport: { width, height: 800 },
    hasTouch: width < 500,
    isMobile: width < 500,
  });
  page.setDefaultTimeout(7000);
  await page.route(origin + '/**', route => {
    const file = path.resolve(factory, '.' + new URL(route.request().url()).pathname);
    return file.startsWith(factory + path.sep) && fs.existsSync(file) && fs.statSync(file).isFile()
      ? route.fulfill({ path: file }) : route.fulfill({ status: 404 });
  });
  await page.addInitScript(platform => {
    window.__lang = 'ru';
    window.axikoProbe = true;
    const response = {
      'sys.info': { version: '0.3.2', device: 'Test phone', android: '16' },
      'lang.status': { language: 'ru', setting: 'auto', languages: [{ code: 'ru', name: 'Русский' }] },
      'speech.status': { engine: 'system', ready: true },
      'tts.local.status': { ready: true, voice: 0, voices: 10, speed: 1 },
      'wake.status': { name: 'Axiko', on: false, lightReady: true, spottable: true },
      'assistant.pref.get': { role: true, agent: 'notes', history: 'save' },
      'agent.list': [
        { id: 'axiko', title: 'Axiko' },
        { id: 'notes', title: 'Помощник для заметок и исследований', role: 'notes' },
      ],
      'models.providers': [
        { id: 'cloud', name: 'OpenRouter — личные облачные модели', baseUrl: 'https://openrouter.ai/api/v1', hasKey: true },
        { id: 'local', name: 'Локальный сервер для тестов', baseUrl: 'http://localhost/v1' },
      ],
      'models.roles': {
        main: { provider: 'cloud', model: 'google/gemini-flash' },
        tts: { provider: 'cloud', model: 'google/gemini-flash-tts', voice: 'Kore' },
      },
      'backup.status': { auto: { mode: 'off' } },
      'webcache.info': { files: 0 },
      'update.state': { status: 'current', installedVersion: '0.3.2' },
      'usage.summary': { days: [] },
      'local.status': [], 'modules.list': [],
      'secret.names': [], 'mcp.servers': [], 'theme.list': [], 'bubble.looks': { looks: [] }, 'launcher.icons': [],
    };
    window.axiko = {
      platform,
      on: () => () => {},
      back() {},
      eval: async () => true,
      async native(name, ...args) {
        if (name === 'pref.get') {
          return args[0] === 'chat.retryLimit' ? 'unlimited' : args[1] ?? null;
        }
        return response[name] ?? {};
      },
    };
  }, width < 500 ? 'android' : 'web');
  await page.goto(origin + '/ui/settings/index.html?tab=assistant');
  return page;
}

async function measureFields(page, tab, selector, count) {
  await page.locator('#tabs [data-id="' + tab + '"]').click();
  await page.waitForFunction(({ selector, count }) => {
    const fields = Array.from(document.querySelectorAll(selector));
    return fields.length === count && fields.every(field => field.querySelector('.select-trigger selectedcontent .select-label'));
  }, { selector, count });
  return page.locator(selector).evaluateAll(fields => fields.map(field => {
    const row = field.parentElement;
    const rowBox = row.getBoundingClientRect();
    const rowStyle = getComputedStyle(row);
    const label = row.querySelector(':scope > .grow').getBoundingClientRect();
    const box = field.getBoundingClientRect();
    return {
      text: field.selectedOptions[0].textContent,
      rowWidth: row.clientWidth - parseFloat(rowStyle.paddingLeft) - parseFloat(rowStyle.paddingRight),
      left: box.left - rowBox.left - parseFloat(rowStyle.paddingLeft),
      width: box.width,
      labelRight: label.right,
      labelBottom: label.bottom,
      fieldLeft: box.left,
      fieldTop: box.top,
      textWidth: field.querySelector('selectedcontent .select-label').clientWidth,
    };
  }));
}

async function checkPopup(page, width, field, minWidth = 0) {
  await field.scrollIntoViewIfNeeded();
  if (width < 500) await field.tap();
  else await field.click();
  await field.evaluate(field => field.matches(':open') || Promise.reject(new Error('Picker did not open')));
  const sizes = await field.evaluate(field => {
    const option = field.options[0].getBoundingClientRect();
    return { field: field.getBoundingClientRect().width, option: option.width, left: option.left, right: option.right, viewport: innerWidth };
  });
  assert.ok(sizes.option >= Math.max(sizes.field, minWidth) - 14, JSON.stringify(sizes));
  assert.ok(sizes.left >= 0 && sizes.right <= sizes.viewport, JSON.stringify(sizes));
  await page.keyboard.press('Escape');
}

async function checkCloudVoiceSheet(page, width) {
  await page.getByRole('button', { name: /Облачный голос/ }).click();
  const fields = page.locator('.sheet .field > select');
  await fields.nth(1).waitFor();
  const sizes = await fields.evaluateAll(inputs => inputs.map(field => ({
    width: field.getBoundingClientRect().width,
    available: field.parentElement.getBoundingClientRect().width,
    text: field.selectedOptions[0].textContent,
  })));
  assert.equal(sizes.length, 2);
  assert.match(sizes[0].text, /OpenRouter/);
  assert.match(sizes[1].text, /Kore/);
  assert.ok(sizes.every(value => Math.abs(value.width - value.available) <= 1), JSON.stringify(sizes));
  await checkPopup(page, width, fields.nth(1));
}

async function checkCompactPicker(page, width) {
  const minimum = await page.evaluate(() => 16 * parseFloat(getComputedStyle(document.documentElement).fontSize));
  for (const side of ['left', 'right']) {
    await page.evaluate(side => {
      const select = document.createElement('select');
      select.id = 'compact-picker-qa';
      select.className = 'input';
      Object.assign(select.style, {
        position: 'fixed', bottom: '8px', [side]: '8px', width: '112px', zIndex: 2000,
      });
      select.append(new Option('Повседневный режим для работы и заметок', 'normal'));
      select.append(new Option('Подробный режим для большой исследовательской задачи', 'extended'));
      document.body.append(select);
    }, side);
    const field = page.locator('#compact-picker-qa');
    await checkPopup(page, width, field, minimum);
    await field.evaluate(field => field.remove());
  }
}

async function main() {
  const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  try {
    for (const width of [320, 360, 411, 1280]) {
      const page = await openSettings(browser, width);
      const fields = [];
      for (const [tab, selector, count] of sections) {
        fields.push(...await measureFields(page, tab, selector, count));
      }

      for (const field of fields) {
        const details = JSON.stringify({ viewport: width, ...field });
        assert.ok(field.textWidth >= 100, 'Selected label has too little room: ' + details);
        if (width < 500) {
          assert.ok(field.fieldTop >= field.labelBottom, 'Control must sit below its label: ' + details);
          assert.ok(Math.abs(field.width - field.rowWidth) <= 2 && Math.abs(field.left) <= 2, 'Control must use the full row: ' + details);
        } else {
          assert.ok(field.fieldLeft >= field.labelRight, 'Desktop label and control should sit side by side: ' + details);
          assert.ok(field.width >= field.rowWidth * 0.42, 'Desktop control has too little room: ' + details);
        }
      }
      if (width >= 500) {
        const left = fields[0].left;
        assert.ok(fields.every(field => Math.abs(field.left - left) <= 2), 'Desktop controls should align in a common column');
      }
      await page.locator('#tabs [data-id="voice"]').click();
      const voice = page.locator('#voice select');
      if (width >= 500) {
        const fits = await voice.locator('selectedcontent .select-label').evaluate(label => label.scrollWidth <= label.clientWidth + 1);
        assert.ok(fits, 'The standard long voice setting should fit in the closed desktop field');
      }
      await checkPopup(page, width, voice);
      await checkCompactPicker(page, width);
      await checkCloudVoiceSheet(page, width);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
      console.log(JSON.stringify({ viewport: width, settingsFields: fields.length, minimumControlWidth: Math.round(Math.min(...fields.map(field => field.width))) }));
      await page.close();
    }
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const factory = path.resolve(__dirname, '../../app/src/main/assets/factory');
const english = JSON.parse(fs.readFileSync(path.join(factory, 'ui/i18n/en.json'), 'utf8'));

async function main() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined,
  });
  try {
    const page = await browser.newPage({ viewport: { width: 360, height: 405 }, reducedMotion: 'reduce' });
    page.setDefaultTimeout(5000);
    const errors = [];
    let light = false;
    page.on('pageerror', error => errors.push(error.message));
    await page.route('https://axiko.local/**', route => {
      const file = path.resolve(factory, '.' + new URL(route.request().url()).pathname);
      if (light && file === path.join(factory, 'ui/kit/kit.css')) {
        const tokens = ':root { color-scheme: light; --bg: #f0f3f7; --surface: #f9fafc; --surface-2: #edf1f5; ' +
          '--ink: #182331; --ink-2: #455266; --line: #becadc; --accent: #2367a7; --accent-ink: #ffffff; ' +
          '--accent-soft: #dbeafe; --radius: 24px; --radius-s: 12px; }';
        return route.fulfill({ body: fs.readFileSync(file, 'utf8') + '\n' + tokens + '\nbody { font-family: Georgia, serif; }', contentType: 'text/css' });
      }
      return file.startsWith(factory + path.sep) && fs.existsSync(file)
        ? route.fulfill({ path: file }) : route.fulfill({ status: 404 });
    });
    await page.addInitScript(dictionary => {
      window.__lang = 'en';
      window.__i18n = dictionary;
      const listeners = new Map();
      const state = {
        id: 'panel-session', thread: 'current-thread', agent: 'axiko', agentTitle: 'Axiko', busy: false, messages: [],
        conversation: false, retry: { phase: 'idle', available: false },
        mode: 'voice', draft: '', draftRevision: 0, draftAudioRevision: 0, saved: true,
        audio: { active: false, state: 'off' }, context: { available: true, attached: false, app: 'Calendar' },
        profile: { opacity: 84, animation: true, mascot: false }, canOpenChat: false, canResume: true,
      };
      const drafts = new Map([['older-thread', {
        title: 'Earlier assistant conversation', draft: 'Earlier draft', draftRevision: 1, draftAudioRevision: 0,
        messages: [{ role: 'assistant', text: 'Earlier answer' }], saved: true,
      }], ['creatine-thread', {
        title: 'Creatine dosing', agent: 'nora', agentTitle: 'Nora',
        draft: '', draftRevision: 0, draftAudioRevision: 0,
        messages: [{ role: 'assistant', text: 'Notes from Nora' }], saved: true,
      }]]);
      let sequence = 0;
      const switchTo = (thread, next) => {
        drafts.set(state.thread, { title: state.messages[0]?.text || 'Current conversation', messages: state.messages,
          agent: state.agent, agentTitle: state.agentTitle, draft: state.draft, draftRevision: state.draftRevision,
          draftAudioRevision: state.draftAudioRevision, saved: state.saved });
        Object.assign(state, { agent: 'axiko', agentTitle: 'Axiko' }, next, { thread, busy: false, reply: '', work: '',
          audio: { active: false, state: 'off' }, conversation: false, retry: { phase: 'idle', available: false } });
        push();
      };
      const calls = [];
      const launches = [];
      const push = (name = 'assistant', value) => (listeners.get(name) || []).forEach(listener => listener(value));
      const profile = {
        role: false, agent: 'axiko', activation: 'manual', history: 'save',
        layout: 'bottom', opacity: 91, animation: true, mascot: true, screen: true, voice: 'default', conversationIdleSeconds: 180,
      };
      const settingsData = {
        'sys.info': { safeMode: false },
        'models.providers': [], 'agent.list': [{ id: 'axiko', title: 'Axiko' }], 'modules.list': [],
        'speech.status': { engine: 'system' }, 'local.status': [], 'theme.list': [], 'launcher.icons': [],
        'backup.status': { auto: { mode: 'off' } }, 'secret.names': [], 'mcp.servers': [],
        'lang.status': { language: 'en', setting: 'en', languages: [{ name: 'English', code: 'en' }] },
        'usage.summary': { days: [] }, 'bubble.looks': null, 'ui.scale': null,
      };
      window.fixture = { calls, launches, state, push, profile, failSend: false, deferDraft: false };
      window.axiko = {
        platform: 'android',
        on(name, listener) { listeners.set(name, [...(listeners.get(name) || []), listener]); },
        async native(method, ...args) {
          calls.push([method, ...args]);
          if (method === 'assistant.status' || method === 'assistant.ready') return structuredClone(state);
          if (method === 'assistant.hitAreas') return true;
          if (method === 'assistant.setMode') {
            state.mode = args[0];
            state.conversation = false;
            state.audio = { active: false, state: 'off' };
            push();
            return true;
          }
          if (method === 'assistant.setDraft') {
            if (window.fixture.deferDraft) await new Promise(resolve => setTimeout(resolve, 35));
            if (args[1] !== state.thread || (args.length > 2 && args[2] !== state.draftAudioRevision)) return false;
            if (state.draft !== args[0]) state.draftRevision++;
            state.draft = args[0];
            return true;
          }
          if (method === 'assistant.openApp') {
            launches.push({ thread: state.thread, saved: state.saved, draft: state.draft });
            return true;
          }
          if (method === 'assistant.sessions') {
            if (args[0]) return args[0] === 'creatine' ? [{
              id: 'creatine-thread', agent: 'nora', agentTitle: 'Nora', title: 'Creatine dosing',
              current: true, snippet: 'Creatine dosage notes',
            }] : [];
            const rows = [...drafts.entries()].filter(([id, data]) => id !== state.thread &&
              (data.agent ?? 'axiko') === state.agent).map(([id, data]) => ({
              id, title: data.title || 'Assistant conversation', time: 1, current: false, saved: data.saved, busy: false,
            }));
            return [{ id: state.thread, title: state.title || 'Current conversation', time: 2, current: true, saved: state.saved, busy: state.busy }, ...rows];
          }
          if (method === 'assistant.selectSession') {
            if (args[0] === state.thread) return true;
            const chosen = drafts.get(args[0]);
            if (!chosen || (chosen.agent ?? 'axiko') !== (args[1] ?? state.agent)) return false;
            switchTo(args[0], chosen);
            return true;
          }
          if (method === 'assistant.newSession') {
            switchTo('new-thread-' + ++sequence, { draft: '', draftRevision: 0, draftAudioRevision: 0,
              messages: [], title: '', saved: profile.history !== 'temporary' });
            return true;
          }
          if (method === 'assistant.attachScreen') state.context.attached = args[0];
          if (method === 'assistant.startVoice') state.audio = { active: true, state: 'listening' };
          if (method === 'assistant.startConversation') {
            state.conversation = true;
            state.audio = { active: true, state: 'listening' };
          }
          if (method === 'assistant.stopVoice') {
            state.conversation = false;
            state.audio = { active: false, state: 'off' };
          }
          if (method === 'assistant.stopSpeaking') state.audio.state = state.audio.active ? 'listening' : 'off';
          if (method === 'assistant.retry') {
            if (!state.retry.available || state.busy) return false;
            state.retry = { ...state.retry, phase: 'retrying', available: false };
            state.busy = true;
          }
          if (method === 'assistant.send') {
            if (window.fixture.failSend) throw new Error('Send failed');
            if ((args[1] && args[1] !== state.thread) || (args[2] && args[2] !== state.agent)) return false;
            state.messages.push({ role: 'user', text: args[0] });
            state.context.attached = false;
            if (state.draft.trim() === args[0].trim()) {
              state.draft = '';
              state.draftRevision++;
            }
          }
          if (method === 'assistant.answer' || method === 'assistant.planAction') delete state.question;
          if (method === 'assistant.pref.get') return structuredClone(profile);
          if (method === 'assistant.pref.set') return structuredClone(Object.assign(profile, args[0]));
          if (method === 'pref.get') return args[0] === 'tours.done' ? 'settings' : args[1];
          if (Object.hasOwn(settingsData, method)) return settingsData[method];
          if (!method.startsWith('assistant.')) return {};
          return true;
        },
      };
    }, english);
    await page.goto('https://axiko.local/ui/assistant/index.html');
    const called = (name, ...args) => page.waitForFunction(([method, ...args]) => window.fixture.calls.some(call =>
      JSON.stringify(call) === JSON.stringify(['assistant.' + method, ...args])), [name, ...args]);
    await called('ready');
    const countCalls = name => page.evaluate(method => window.fixture.calls.filter(([call]) => call === method).length, 'assistant.' + name);
    const composer = page.locator('#assistant-input');
    const reply = page.locator('#assistant-reply');
    const panel = page.locator('#assistant-panel');
    const mic = page.locator('#assistant-mic');
    const openApp = page.locator('#assistant-app');
    const transcript = page.locator('#assistant-expand');
    assert.equal(await panel.evaluate(node => node.style.getPropertyValue('--assistant-opacity')), '84%');
    assert.equal(await page.locator('#assistant-name').textContent(), 'Axiko');
    assert.equal(await page.locator('#assistant-status').textContent(), 'Ready to talk');
    assert.equal(await page.locator('#assistant-open').isVisible(), false);
    assert.equal(await page.getByRole('button', { name: 'Open Axiko', exact: true }).isEnabled(), true);
    assert.equal(await transcript.isVisible(), false);
    assert.equal(await composer.isVisible(), false);
    const darkColor = await mic.evaluate(node => getComputedStyle(node).backgroundColor);
    assert.equal(await panel.evaluate(node => getComputedStyle(node).backgroundColor), 'rgba(0, 0, 0, 0)');
    assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), 'rgba(0, 0, 0, 0)');
    await page.waitForFunction(() => window.fixture.calls.some(call => call[0] === 'assistant.hitAreas'));
    const hitAreas = await page.evaluate(() => window.fixture.calls.filter(call => call[0] === 'assistant.hitAreas').at(-1)[1]);
    assert.ok(hitAreas.width > 0 && hitAreas.height > 0 && hitAreas.rects.length);
    const covers = (x, y) => hitAreas.rects.some(({ left, top, right, bottom }) => x > left && x < right && y > top && y < bottom);
    const bounds = await mic.boundingBox();
    assert.ok(covers(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2), 'the microphone should be touchable');
    const appBounds = await openApp.boundingBox();
    assert.ok(appBounds && covers(appBounds.x + appBounds.width / 2, appBounds.y + appBounds.height / 2),
      'the main app button should be visible and touchable in an empty conversation');
    assert.ok(!covers(hitAreas.width / 2, hitAreas.height / 8), 'the top of the viewport should remain touch-through');
    await openApp.click();
    await called('openApp');
    assert.deepEqual(await page.evaluate(() => window.fixture.launches), [{ thread: 'current-thread', saved: true, draft: '' }]);
    await page.locator('#assistant-close').click();
    await called('close');

    await page.locator('#assistant-screen').click();
    await called('attachScreen', true);
    await page.waitForFunction(() => document.querySelector('#assistant-screen').getAttribute('aria-pressed') === 'true');
    await page.locator('#assistant-screen').click();
    await called('attachScreen', false);
    await page.waitForFunction(() => document.querySelector('#assistant-screen').getAttribute('aria-pressed') === 'false');

    await mic.click();
    await called('startVoice');
    await page.waitForFunction(() => document.querySelector('#assistant-panel').dataset.phase === 'listening');
    await page.evaluate(() => window.fixture.push('assistant.audio', { active: true, state: 'hearing', level: 0.7 }));
    assert.equal(await panel.getAttribute('data-phase'), 'hearing');
    assert.equal(await panel.evaluate(node => Number(node.style.getPropertyValue('--assistant-level'))), 0.7);
    await mic.click();
    await called('stopVoice');
    await page.waitForFunction(() => document.querySelector('#assistant-panel').dataset.phase === 'idle');

    await mic.hover();
    await page.mouse.down();
    await called('startConversation');
    await page.mouse.up();
    await page.getByRole('button', { name: 'End conversation', exact: true }).waitFor();
    assert.equal(await countCalls('startConversation'), 1);
    assert.equal(await countCalls('startVoice'), 1, 'Releasing a long press should not send a regular start');
    assert.equal(await countCalls('stopVoice'), 1, 'Releasing a long press should leave the conversation active');

    await page.evaluate(() => {
      const { state, push } = window.fixture;
      state.audio = { active: true, state: 'speaking' };
      push();
    });
    await page.waitForFunction(() => document.querySelector('#assistant-panel').dataset.phase === 'speaking');
    await mic.click();
    await called('stopSpeaking');
    await page.getByRole('button', { name: 'End conversation', exact: true }).waitFor();
    assert.equal(await mic.getAttribute('aria-pressed'), 'true');
    assert.equal(await countCalls('stopVoice'), 1, 'Interrupting speech should keep the microphone open');
    await mic.click();
    await page.waitForFunction(() => document.querySelector('#assistant-panel').dataset.phase === 'idle');
    assert.equal(await countCalls('stopVoice'), 2);

    await page.evaluate(() => {
      const { state, push } = window.fixture;
      state.busy = true;
      state.work = 'Looking at the calendar';
      window.stream = setInterval(() => {
        state.reply = 'Streaming ' + ((Number(state.reply?.split(' ')[1]) || 0) + 1);
        push();
      }, 25);
    });
    // During sustained invalidations, the UI updates without waiting for a quiet gap.
    await page.waitForFunction(() => document.querySelector('#assistant-reply').textContent.startsWith('Streaming '), undefined, { timeout: 1500 });
    const firstReply = await reply.textContent();
    await page.waitForFunction(first => document.querySelector('#assistant-reply').textContent !== first, firstReply, { timeout: 1500 });
    await page.evaluate(() => clearInterval(window.stream));
    assert.equal(await page.locator('#assistant-work').textContent(), 'Looking at the calendar');
    await page.evaluate(() => {
      const { state, push } = window.fixture;
      state.reply = '';
      state.busy = false;
      state.work = '';
      state.messages.push({ role: 'assistant', text: 'Finished appointment' });
      push();
    });
    await page.locator('#assistant-bubbles').getByText('Finished appointment', { exact: true }).waitFor();
    assert.ok((await page.locator('#assistant-reply-bubble').boundingBox()).height < 70, 'short replies stay compact');
    await reply.waitFor({ state: 'hidden', timeout: 10000 });
    await transcript.click();
    await page.locator('#assistant-log').getByText('Finished appointment', { exact: true }).waitFor();
    await page.locator('#assistant-chat').click();
    await called('setMode', 'text');
    await composer.waitFor();
    await page.locator('#assistant-log').getByText('Finished appointment', { exact: true }).waitFor();
    await composer.fill('In progress, unsent');
    await page.waitForFunction(() => window.fixture.state.draft === 'In progress, unsent');
    await mic.click();
    await page.waitForFunction(() => window.fixture.calls.filter(([method]) => method === 'assistant.startVoice').length === 2);
    await page.evaluate(() => {
      const { state, push } = window.fixture;
      state.draft += ' with dictated text';
      state.draftRevision++;
      state.draftAudioRevision++;
      push();
    });
    await page.waitForFunction(() => document.querySelector('#assistant-input').value === 'In progress, unsent with dictated text');
    assert.equal(await page.evaluate(() => window.fixture.calls.filter(([method]) => method === 'assistant.send').length), 0);
    await mic.click();
    await page.waitForFunction(() => window.fixture.calls.filter(([method]) => method === 'assistant.stopVoice').length === 3);

    await page.locator('#assistant-sessions').click();
    await page.locator('#assistant-sessions-list').getByText('Earlier assistant conversation', { exact: true }).click();
    await called('selectSession', 'older-thread');
    await page.waitForFunction(() => document.querySelector('#assistant-input').value === 'Earlier draft');
    await page.locator('#assistant-log').getByText('Earlier answer', { exact: true }).waitFor();
    await page.locator('#assistant-sessions').click();
    await page.locator('.assistant-session[data-thread="current-thread"]').click();
    await called('selectSession', 'current-thread');
    await page.waitForFunction(() => document.querySelector('#assistant-input').value === 'In progress, unsent with dictated text');

    await page.evaluate(() => { window.fixture.failSend = true; });
    await composer.press('Enter');
    await page.getByText('Send failed', { exact: true }).waitFor();
    assert.equal(await composer.inputValue(), 'In progress, unsent with dictated text');
    await page.evaluate(() => { window.fixture.failSend = false; });
    await composer.press('Enter');
    await page.waitForFunction(() => document.querySelector('#assistant-input').value === '');
    await called('send', 'In progress, unsent with dictated text', 'current-thread', 'axiko');

    await page.evaluate(() => {
      const { state, push } = window.fixture;
      state.retry = { phase: 'failed', available: true, partial: 'Here is the beginning', message: 'Network timeout' };
      push();
    });
    const retry = page.getByRole('button', { name: 'Retry request', exact: true });
    const log = page.locator('#assistant-log');
    const partial = log.getByText('Here is the beginning', { exact: true });
    await partial.waitFor();
    assert.equal(await log.getByText('Partial response', { exact: true }).count(), 1);
    await retry.click();
    await called('retry');
    await retry.waitFor({ state: 'hidden' });
    await page.evaluate(() => {
      const { state, push } = window.fixture;
      state.messages.push({ role: 'assistant', text: 'Here is the completed answer' });
      state.retry = { phase: 'idle', available: false };
      state.busy = false;
      push();
    });
    await log.getByText('Here is the completed answer', { exact: true }).waitFor();
    assert.equal(await partial.count(), 0);
    assert.equal(await log.getByText('In progress, unsent with dictated text', { exact: true }).count(), 1);
    assert.equal(await countCalls('send'), 2, 'Retry should use its own operation, alongside the failed and successful sends');
    assert.equal(await countCalls('retry'), 1);

    await page.evaluate(() => {
      const { state, push } = window.fixture;
      state.messages.push({ role: 'assistant', text: '<img src=x onerror=alert(1)>' });
      state.reply = '';
      push();
    });
    await page.locator('#assistant-log').getByText('<img src=x onerror=alert(1)>', { exact: true }).waitFor();
    assert.equal(await page.locator('#assistant-log img').count(), 0);
    await page.evaluate(() => {
      const { state, push } = window.fixture;
      state.question = {
        type: 'ask', id: 'choice',
        form: { questions: [{ question: 'Which day?', options: ['Tuesday', 'Friday'] }] },
      };
      push();
    });
    await page.getByLabel('Friday').check();
    await page.locator('#assistant-question button').click();
    await called('answer', 'choice', { text: 'Which day?\n— Friday' });
    await page.locator('#assistant-question').waitFor({ state: 'hidden' });

    await page.evaluate(() => {
      const { state, push } = window.fixture;
      state.question = { type: 'plan', id: 'plan', plan: { title: 'Review proposal', steps: [{ text: 'Draft the schedule' }] } };
      push();
    });
    await page.getByText('Draft the schedule', { exact: true }).waitFor();
    await page.locator('#assistant-question textarea').fill('Use a shorter meeting');
    await page.locator('#assistant-question .assistant-action').click();
    await called('planAction', 'plan', 'revise', 'Use a shorter meeting');
    await page.locator('#assistant-question').waitFor({ state: 'hidden' });

    await page.locator('#assistant-sessions').click();
    await page.locator('#assistant-session-search').fill('creatine');
    await called('sessions', 'creatine');
    const crossAgent = page.locator('#assistant-sessions-list').getByRole('button', { name: /Creatine dosing/ });
    await crossAgent.waitFor();
    assert.equal(await crossAgent.getAttribute('aria-current'), null, 'Current in the main app is separate from current in the panel');
    await crossAgent.click();
    await called('selectSession', 'creatine-thread', 'nora');
    await log.getByText('Notes from Nora', { exact: true }).waitFor();
    assert.equal(await page.locator('#assistant-name').textContent(), 'Nora');

    await page.evaluate(() => { window.fixture.profile.history = 'temporary'; });
    await page.locator('#assistant-sessions').click();
    await page.locator('#assistant-new').click();
    await called('newSession');
    await page.locator('#assistant-log').getByText('Earlier answer', { exact: true }).waitFor({ state: 'hidden' });
    assert.equal(await composer.inputValue(), '');
    assert.equal(await transcript.isVisible(), false);

    // Leave immediately after editing so the normal autosave debounce has not elapsed.
    await page.evaluate(() => {
      window.fixture.deferDraft = true;
      const draft = document.querySelector('#assistant-input');
      draft.value = 'Draft to keep when opening Axiko';
      draft.dispatchEvent(new Event('input', { bubbles: true }));
      document.querySelector('#assistant-app').click();
    });
    await page.waitForFunction(() => window.fixture.launches.length === 2);
    assert.deepEqual(await page.evaluate(() => window.fixture.launches.at(-1)), {
      thread: 'new-thread-1', saved: false, draft: 'Draft to keep when opening Axiko',
    });

    for (const viewport of [{ width: 340, height: 174 }, { width: 448, height: 572 }, { width: 640, height: 510 }]) {
      await page.setViewportSize(viewport);
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth === innerWidth));
      const position = await mic.boundingBox();
      assert.ok(Math.abs(position.x + position.width / 2 - viewport.width / 2) < 2, 'mic centered in the viewport');
      const bounds = await composer.boundingBox();
      assert.ok(bounds && bounds.height >= 44 && bounds.y + bounds.height <= viewport.height);
      await composer.click({ trial: true });
    }

    light = true;
    await page.reload();
    await called('ready');
    assert.equal(await mic.evaluate(node => getComputedStyle(node).backgroundColor), 'rgb(35, 103, 167)');
    assert.notEqual(await mic.evaluate(node => getComputedStyle(node).backgroundColor), darkColor);
    assert.match(await mic.evaluate(node => getComputedStyle(node).fontFamily), /Georgia/iu);
    assert.equal(await panel.evaluate(node => getComputedStyle(node).backgroundColor), 'rgba(0, 0, 0, 0)');

    await page.setViewportSize({ width: 360, height: 720 });
    await page.goto('https://axiko.local/ui/settings/index.html?tab=assistant');
    const preview = page.getByRole('button', { name: 'Try the panel', exact: true });
    await preview.waitFor();
    assert.ok(await preview.isDisabled());
    await page.getByRole('button', { name: 'Choose an Android assistant' }).click();
    await called('settings');
    await page.evaluate(() => {
      window.fixture.profile.role = true;
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await preview.click();
    await called('preview');
    await page.locator('#assistant-talk select').filter({ has: page.getByRole('option', { name: 'Start listening immediately' }) })
      .selectOption('voice');
    await called('pref.set', { activation: 'voice' });
    await page.locator('#assistant-talk select').filter({ has: page.getByRole('option', { name: 'Temporary conversation' }) })
      .selectOption('temporary');
    await called('pref.set', { history: 'temporary' });
    const idle = page.getByRole('spinbutton', { name: 'End conversation after an idle pause (seconds)' });
    assert.equal(await idle.inputValue(), '180');
    await idle.fill('0');
    await idle.blur();
    await called('pref.set', { conversationIdleSeconds: 0 });
    await page.getByRole('slider', { name: 'Panel opacity', exact: true }).fill('73');
    await called('pref.set', { opacity: 73 });
    await page.getByLabel('Animate the waveform', { exact: true }).uncheck();
    await called('pref.set', { animation: false });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth === innerWidth));

    assert.deepEqual(errors, []);
    console.log('Assistant panel and settings browser regression passed');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });

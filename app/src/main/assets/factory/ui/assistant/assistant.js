(() => {
  'use strict';

  const { h, t, raw } = ui;
  const byId = name => document.getElementById('assistant-' + name);
  const native = (name, ...args) => axiko.native('assistant.' + name, ...args);
  const panel = byId('panel');
  const content = byId('content');
  const input = byId('input');
  const send = byId('send');
  const mic = byId('mic');
  const app = byId('app');
  const sessionList = byId('sessions-list');
  const sessionSearch = byId('session-search');
  const participantList = byId('participant-list');
  const actionRow = byId('actions');
  const attachments = byId('attachments');
  const file = byId('file');
  const camera = byId('camera');
  let state = null;
  let audio = {};
  let audioVersion = 0;
  let pane = '';
  let sending = false;
  let switching = false;
  let attachmentAction = false;
  let attachmentKey = '';
  let mascot;
  let actionError = '';
  let logKey = '';
  let questionKey = '';
  let refreshTimer = null;
  let refreshAt = Infinity;
  let refreshing = false;
  let refreshAgain = false;
  let sessionListRequest = 0;
  let sessionSearchTimer;
  let participantOptions = [];
  let participantKey = '';

  const bubbleTimers = {};
  let previewKeys = {};
  let draft = { thread: null, agent: null, text: '', revision: -1, audioRevision: 0 };
  let pendingDraft = null;
  let writingDraft = null;
  let draftTimer;
  let draftWrites = null;
  let hitFrame;
  let lastHitLayout = '';

  function visibleText(node, value) {
    if (node.textContent !== value) node.textContent = value;
    node.hidden = !value;
  }

  function label(node, value) {
    const translated = t(value);
    node.setAttribute('aria-label', translated);
    node.setAttribute('title', translated);
  }

  function errorText(message) {
    actionError = message || '';
    renderError();
  }

  function renderError() {
    const failure = state?.retry?.phase === 'failed' && !state.busy ? state.retry.message || t('Ответ прерван') : '';
    visibleText(byId('error'), actionError || audio.error || state?.error || failure);
    queueHitAreas();
  }

  function inputState() {
    const ready = Boolean(state?.id);
    const attachmentBusy = !ready || switching || sending || attachmentAction || Boolean(state?.attachmentsPicking);
    input.disabled = !ready || switching;
    send.disabled = attachmentBusy || !input.value.trim();
    input.placeholder = t(state?.attachments?.length ? 'Что с этим сделать?' :
      state?.context?.attached ? 'Что сделать с этим экраном?' : 'Написать или сказать…');
    for (const button of [file, camera, ...(attachments?.querySelectorAll('button') || [])]) {
      if (button) button.disabled = attachmentBusy;
    }
    for (const button of [mic, byId('chat'), byId('sessions'), byId('expand')]) {
      button.disabled = !ready || switching;
    }
    if (app) app.disabled = !ready || switching;
  }

  function renderAttachments() {
    for (const button of [file, camera]) {
      if (button) button.hidden = !state?.attachmentsAvailable;
    }
    if (!attachments) return;
    const items = Array.isArray(state?.attachments) ? state.attachments : [];
    const key = JSON.stringify([state?.id, state?.thread, state?.agent, items]);
    if (key === attachmentKey) return;
    attachmentKey = key;
    attachments.replaceChildren(...items.map(item => {
      const remove = h('button', {
        type: 'button', class: 'assistant-icon', onclick: () => changeAttachments('removeAttachment', item.id),
      }, ui.icon('close'));
      remove.setAttribute('aria-label', t('Убрать вложение {}', item.name));
      remove.title = t('Не прикладывать');
      const name = h('span', { class: 'assistant-attachment-name', 'data-raw': '' }, raw(item.name));
      name.title = item.name;
      return h('li', { class: 'assistant-attachment' }, ui.icon(item.kind === 'image' ? 'image' : 'clip'), name, remove);
    }));
    attachments.hidden = !items.length;
  }

  function renderAudio() {
    const phase = audio.state === 'speaking' || audio.state === 'hearing' ? audio.state
      : state?.question ? 'asking'
        : state?.busy || audio.state === 'thinking' ? 'thinking'
          : audio.active ? 'listening' : 'idle';
    panel.dataset.phase = phase;
    const level = Math.max(0, Math.min(1, Number(audio.level) || 0));
    panel.style.setProperty('--assistant-level', level.toFixed(3));

    const conversation = Boolean(state?.conversation || audio.conversation);
    const listening = state?.mode === 'text' ? 'Диктую' : 'Слушаю';
    const status = {
      idle: state ? 'Готов к разговору' : 'Открываю разговор…',
      listening, hearing: listening, thinking: 'Работаю', speaking: 'Говорю', asking: 'Нужен ваш ответ',
    }[phase];
    const retrying = ['waiting', 'retrying'].includes(state?.retry?.phase);
    const activity = retrying && phase !== 'speaking' ? t('Повторяю запрос') : t(status);
    visibleText(byId('status'), conversation ? t('Режим разговора') + ' · ' + activity : activity);
    byId('activity').hidden = phase === 'idle' && Boolean(state);
    visibleText(byId('work'), state?.work || '');

    const pose = { idle: 'idle', listening: 'listening', hearing: 'listening', thinking: 'working', speaking: 'idle', asking: 'asking' }[phase];
    if (mascot && mascot.dataset.pose !== pose) ui.mascot.pose(mascot, pose);
    const micAction = phase === 'speaking' ? 'Остановить озвучку' : conversation ? 'Завершить разговор' : state?.mode === 'text'
      ? (audio.active ? 'Остановить диктовку' : 'Продиктовать текст')
      : (audio.active ? 'Выключить микрофон' : 'Включить микрофон');
    label(mic, micAction);
    if (!conversation) mic.title += ' · ' + t('Удерживайте для режима разговора');
    mic.setAttribute('aria-pressed', String(Boolean(audio.active)));
    byId('silence').hidden = phase !== 'speaking';
    renderError();
  }

  function showBubble(role, text, live = false) {
    const node = byId(role === 'user' ? 'user-bubble' : 'reply-bubble');
    const paragraph = byId(role === 'user' ? 'utterance' : 'reply');
    clearTimeout(bubbleTimers[role]);
    node.classList.remove('leaving');
    node.hidden = !text;
    paragraph.textContent = text;
    if (text && !live) {
      bubbleTimers[role] = setTimeout(() => {
        node.classList.add('leaving');
        bubbleTimers[role] = setTimeout(() => { node.hidden = true; }, 230);
      }, 4200 + Math.min(3300, text.length * 18));
    }
  }

  function transcript() {
    const messages = Array.isArray(state?.messages) ? state.messages : [];
    const reply = typeof state?.reply === 'string' ? state.reply : '';
    const latest = messages.at(-1);
    const live = reply && (reply !== latest?.text || latest?.role !== 'assistant') ? reply : '';
    return { messages, live };
  }

  function renderBubbles(changedThread) {
    const { messages, live } = transcript();
    if (changedThread) {
      showBubble('user', '');
      showBubble('assistant', '');
    }
    const lastUser = messages.findLastIndex(message => message.role === 'user');
    const lastReply = messages.findLastIndex(message => message.role === 'assistant');
    const user = messages[lastUser]?.text || '';
    const partial = (state?.retry?.phase === 'failed' || state?.retry?.phase === 'waiting') ? state.retry.partial : '';
    const answer = live || partial || messages[lastReply]?.text || '';
    visibleText(byId('name'), (!live && !partial && messages[lastReply]?.author) || state.agentTitle || 'Axiko');
    const keys = {
      user: lastUser + '|' + user,
      assistant: (live ? 'live' : lastReply) + '|' + answer,
    };
    if ((!changedThread || state.busy) && keys.user !== previewKeys.user) showBubble('user', user);
    if ((!changedThread || live) && keys.assistant !== previewKeys.assistant) {
      showBubble('assistant', answer, Boolean(live));
    }
    previewKeys = keys;
  }

  function messageRow(message) {
    const own = message.role === 'user';
    return h('article', {
      class: 'assistant-message' + (own ? ' user' : ' reply'),
      'data-role': message.role,
    }, h('span', { class: 'assistant-author' }, raw(own ? t('Вы') : message.author || state.agentTitle || 'Axiko')),
    h('p', { 'data-raw': '' }, raw(message.text || '')));
  }

  function renderTranscript() {
    const log = byId('log');
    if (log.hidden) return;
    const { messages, live } = transcript();
    const partial = !live && ['failed', 'waiting'].includes(state?.retry?.phase) ? state.retry.partial : '';
    const key = JSON.stringify([state?.agent, state?.thread, messages, live, partial]);
    if (key === logKey) return;
    logKey = key;
    const follow = content.scrollHeight - content.scrollTop - content.clientHeight < 36;
    const rows = messages.map(messageRow);
    if (live) rows.push(messageRow({ role: 'assistant', text: live }));
    else if (partial) rows.push(messageRow({ role: 'assistant', author: t('Фрагмент ответа'), text: partial }));
    if (!rows.length) rows.push(h('p', { class: 'assistant-empty' }, 'В этом разговоре ещё нет реплик.'));
    log.replaceChildren(...rows);
    if (follow && !state?.question) content.scrollTop = content.scrollHeight;
  }

  function sheet() {
    return pane || (state?.question ? 'question' : state?.mode === 'text' ? 'text' : '');
  }

  function renderSheet() {
    const next = sheet();
    const drawer = byId('drawer');
    const newSheet = drawer.dataset.sheet !== next;
    drawer.dataset.sheet = next;
    panel.dataset.sheet = next;
    panel.dataset.mode = state?.mode || 'voice';
    drawer.hidden = !next;
    byId('drawer-close').hidden = next === 'question';
    byId('log').hidden = next !== 'text' && next !== 'history';
    byId('composer').hidden = next !== 'text';
    byId('question').hidden = next !== 'question';
    sessionList.hidden = next !== 'sessions';
    if (sessionSearch) sessionSearch.hidden = next !== 'sessions';
    if (participantList) participantList.hidden = next !== 'participants';
    if (byId('participants')) byId('participants').hidden = next !== 'text' && next !== 'history';
    byId('new').hidden = next !== 'sessions';
    byId('resume').hidden = next !== 'sessions' || !state?.canResume || !sessionList.dataset.failed;
    byId('open').hidden = !state?.canOpenChat || !next || next === 'question';
    byId('drawer-title').textContent = t(({ text: 'Текстовый чат', history: 'Переписка', sessions: 'Сессии', participants: 'Участники чата', question: 'Нужен ваш ответ' })[next] || 'Переписка');
    byId('sessions').setAttribute('aria-expanded', String(next === 'sessions'));
    byId('chat').setAttribute('aria-pressed', String(next === 'text'));
    label(byId('chat'), next === 'text' ? 'Вернуться к голосовому режиму' : 'Текстовый чат');
    const expand = byId('expand');
    if (actionRow.contains(expand)) {
      const { messages, live } = transcript();
      expand.hidden = !messages.length && !live;
    }
    expand.setAttribute('aria-expanded', String(next === 'history'));
    label(expand, next === 'history' ? 'Скрыть переписку' : 'Показать переписку');
    renderTranscript();
    if (next === 'participants') renderParticipants();
    if (newSheet) content.scrollTop = next === 'sessions' || next === 'participants' ? 0 : content.scrollHeight;
    queueHitAreas();
  }

  async function showSessions(query = '') {
    pane = 'sessions';
    const ticket = ++sessionListRequest;
    sessionList.replaceChildren(h('p', { class: 'assistant-empty' }, 'Загрузка…'));
    delete sessionList.dataset.failed;
    renderSheet();
    try {
      const sessions = await native('sessions', ...(query ? [query] : []));
      if (ticket !== sessionListRequest || pane !== 'sessions') return;
      if (!Array.isArray(sessions)) throw new Error(t('Не удалось выполнить действие'));
      sessionList.replaceChildren(...sessions.map(session => {
        const current = (!query && session.current) || (session.id === state?.thread && (!session.agent || session.agent === state.agent));
        const details = [current ? t('Текущая сессия') : '', session.agentTitle, session.busy ? t('Работаю') : '',
          Number(session.time) > 0 ? ui.ago(Number(session.time)) : ''].filter(Boolean).join(' · ');
        const button = h('button', {
          type: 'button', class: 'assistant-session', 'data-thread': session.id, 'data-agent': session.agent || null,
          'aria-current': current ? 'true' : null,
          onclick: () => {
            if (current) { pane = ''; renderSheet(); }
            else changeDialog('selectSession', session.id, ...(session.agent ? [session.agent] : []));
          },
        }, h('span', {},
          h('strong', {}, raw(session.title || t('Новый разговор'))),
          details ? h('small', {}, raw(details)) : null,
          session.snippet ? h('small', {}, raw(session.snippet)) : null), current ? ui.icon('check') : ui.icon('right'));
        return button;
      }));
      if (!sessions.length) sessionList.append(h('p', { class: 'assistant-empty' }, 'Чаты не найдены'));
    } catch (error) {
      if (ticket !== sessionListRequest || pane !== 'sessions') return;
      sessionList.dataset.failed = 'true';
      sessionList.replaceChildren(h('p', { class: 'assistant-empty', 'data-raw': '' }, raw(error.message)));
    }
    renderSheet();
  }

  function renderParticipants() {
    if (!participantList) return;
    const joined = new Set((state?.participants || []).map(agent => agent.id));
    const key = JSON.stringify([participantOptions, [...joined]]);
    if (participantKey === key) return;
    participantKey = key;
    participantList.replaceChildren(h('p', { class: 'assistant-empty' }, participantOptions.length
      ? 'Вопрос участнику можно адресовать через @имя.' : 'Других агентов пока нет.'),
    ...participantOptions.map(agent => {
      const included = joined.has(agent.id);
      return h('button', { type: 'button', class: 'assistant-session', 'aria-pressed': String(included),
        onclick: async event => {
          const button = event.currentTarget;
          button.disabled = true;
          if (await run('participant', agent.id, !included)) await refresh();
          button.disabled = false;
        },
      }, h('span', {}, h('strong', {}, raw(agent.title)), h('small', {}, included ? 'Убрать участника' : 'Позвать агента')),
      ui.icon(included ? 'check' : 'plus'));
    }));
  }

  function acceptDraft(next) {
    const snapshot = {
      thread: next.thread,
      agent: next.agent,
      text: typeof next.draft === 'string' ? next.draft : '',
      revision: Number(next.draftRevision) || 0,
      audioRevision: Number(next.draftAudioRevision) || 0,
    };
    if (draft.thread !== snapshot.thread || draft.agent !== snapshot.agent) {
      pendingDraft = null;
      input.value = snapshot.text;
    } else if (snapshot.revision < draft.revision) return;
    else if (pendingDraft && snapshot.audioRevision > pendingDraft.audioRevision) {
      // Carry the dictation appended by Android into the user's newer local edit.
      const prefix = [draft.text, writingDraft?.text, pendingDraft.text]
        .filter(value => typeof value === 'string' && snapshot.text.startsWith(value))
        .sort((a, b) => b.length - a.length)[0] || '';
      const spoken = snapshot.text.slice(prefix.length).trim();
      if (spoken) input.value = (input.value.trimEnd() + ' ' + spoken).trimStart().slice(0, input.maxLength);
      pendingDraft = { thread: snapshot.thread, agent: snapshot.agent, text: input.value, audioRevision: snapshot.audioRevision };
      queueDraft();
    } else if (!pendingDraft) input.value = snapshot.text;
    draft = snapshot;
    inputState();
  }

  function queueDraft() {
    clearTimeout(draftTimer);
    draftTimer = setTimeout(() => flushDraft(), 90);
  }

  function flushDraft() {
    clearTimeout(draftTimer);
    if (!pendingDraft) return draftWrites || Promise.resolve(true);
    if (!draftWrites) {
      draftWrites = (async () => {
        while (pendingDraft) {
          const next = pendingDraft;
          writingDraft = next;
          const accepted = await native('setDraft', next.text, next.thread, next.audioRevision, ...(next.agent ? [next.agent] : []));
          const snapshot = await native('status');
          if (snapshot?.thread === next.thread && snapshot.agent === next.agent) acceptDraft(snapshot);
          writingDraft = null;
          if (pendingDraft === next) {
            if (!accepted) throw new Error(t('Не удалось выполнить действие'));
            pendingDraft = null;
          }
        }
        return true;
      })().catch(error => { errorText(error.message); return false; })
        .finally(() => { writingDraft = null; draftWrites = null; });
    }
    return draftWrites;
  }

  function draftChanged() {
    if (!state?.thread) return;
    pendingDraft = { thread: state.thread, agent: state.agent, text: input.value, audioRevision: draft.audioRevision };
    inputState();
    queueDraft();
  }
  function renderQuestion(question) {
    const container = byId('question');
    if ((question?.id || '') === questionKey) return;
    questionKey = question?.id || '';
    container.replaceChildren();
    container.hidden = sheet() !== 'question';
    if (!questionKey) return;

    const decide = async (action, ...args) => {
      const controls = Array.from(container.querySelectorAll('button, input, textarea'));
      controls.forEach(control => { control.disabled = true; });
      await run(action, question.id, ...args);
      controls.forEach(control => { control.disabled = false; });
    };

    if (question.type === 'plan' && question.plan) {
      const plan = question.plan;
      const change = h('textarea', { class: 'input', rows: 2, maxlength: 2000, placeholder: 'Что изменить в плане', 'aria-label': 'Что изменить в плане' });
      container.append(
        h('strong', {}, raw(plan.title || t('План'))),
        h('ul', {}, (plan.steps || []).map(step => h('li', {}, raw(step.text || '')))),
        change,
        h('div', { class: 'assistant-actions' },
          h('button', { class: 'assistant-action', type: 'button', onclick: () => {
            if (!change.value.trim()) { change.focus(); return; }
            decide('planAction', 'revise', change.value.trim());
          } }, 'Изменить'),
          h('button', { class: 'btn primary small', type: 'button', onclick: () => decide('planAction', 'approve') }, 'Начать')));
    } else {
      const form = question.form || {};
      const fields = (form.questions || []).map((item, index) => {
        const options = Array.isArray(item.options) ? item.options : [];
        const choiceInputs = options.map(option => h('input', { type: item.multiple ? 'checkbox' : 'radio', name: 'q-' + index, value: String(option) }));
        const answer = h('input', {
          class: 'input', maxlength: 2000,
          placeholder: options.length ? 'Свой вариант' : 'Ваш ответ',
          'aria-label': options.length ? 'Свой вариант' : 'Ваш ответ',
        });
        return {
          title: String(item.question || ''), answer, choiceInputs,
          element: h('fieldset', {},
            h('legend', {}, raw(item.question || '')),
            choiceInputs.map((choice, i) => h('label', { class: 'assistant-option' }, choice, h('span', {}, raw(options[i])))), answer),
        };
      });
      const formElement = h('form', { onsubmit: event => {
        event.preventDefault();
        const rows = fields.map(field => {
          const values = field.choiceInputs.filter(choice => choice.checked).map(choice => choice.value);
          if (field.answer.value.trim()) values.push(field.answer.value.trim());
          return values.join(', ');
        });
        const incomplete = rows.findIndex(value => !value);
        if (incomplete >= 0) { fields[incomplete].answer.focus(); return; }
        const text = fields.map((field, index) => field.title + '\n— ' + rows[index]).join('\n');
        if (text.trim()) decide('answer', { text });
      } }, fields.map(field => field.element), h('button', { class: 'btn primary small', type: 'submit' }, 'Ответить'));
      container.append(h('strong', {}, raw(form.title || t('Нужен ваш ответ'))), formElement);
    }
    content.scrollTop = content.scrollHeight;
  }


  let statusRequest = 0;
  let statusSeen = 0;

  function render(next, seenAudioVersion) {
    if (!next?.id) throw new Error(t('Сессия ассистента завершена'));
    const changedThread = state?.id !== next.id || state?.thread !== next.thread || state?.agent !== next.agent;
    if (changedThread) {
      logKey = '';
      questionKey = '';
      if (pane === 'participants') pane = '';
    }
    state = next;
    acceptDraft(next);
    if (seenAudioVersion === audioVersion) audio = next.audio || {};
    const profile = state.profile || {};
    const opacity = Number(profile.opacity ?? 90);
    panel.style.setProperty('--assistant-opacity', (Number.isFinite(opacity) ? Math.max(0, Math.min(100, opacity)) : 90) + '%');
    panel.dataset.motion = String(profile.animation !== false);
    const emblem = byId('mascot');
    if (profile.mascot !== false && !mascot) {
      mascot = ui.mascot('idle', { size: 19 });
      emblem.append(mascot);
    }
    emblem.hidden = profile.mascot === false || !mascot;
    visibleText(byId('name'), state.agentTitle || 'Axiko');

    const context = state.context || {};
    const screen = byId('screen');
    screen.hidden = !context.available && !context.attached;
    screen.setAttribute('aria-pressed', String(Boolean(context.attached)));
    screen.title = context.app || '';
    visibleText(byId('screen-label'), t(context.attached ? 'Экран добавлен' : 'Добавить экран'));
    renderAttachments();
    byId('cancel').hidden = !state.busy;
    if (byId('retry')) byId('retry').hidden = !state.retry?.available || state.busy;
    renderAudio();
    renderBubbles(changedThread);
    renderSheet();
    renderQuestion(state.question);
    inputState();
  }

  async function readState() {
    const ticket = ++statusRequest;
    const seenAudioVersion = audioVersion;
    const next = await native('status');
    if (ticket >= statusSeen) {
      statusSeen = ticket;
      render(next, seenAudioVersion);
    }
  }

  async function refresh() {
    if (refreshing) { refreshAgain = true; return; }
    refreshing = true;
    try {
      await readState();
    } finally {
      refreshing = false;
      if (refreshAgain) { refreshAgain = false; schedule(); }
      else if (state?.busy) schedule(1600);
    }
  }

  function schedule(delay = 120) {
    if (document.hidden) return;
    const at = Date.now() + delay;
    if (refreshTimer !== null && refreshAt <= at) return;
    clearTimeout(refreshTimer);
    refreshAt = at;
    refreshTimer = setTimeout(() => {
      refreshTimer = null;
      refreshAt = Infinity;
      refresh().catch(error => errorText(error.message));
    }, delay);
  }

  async function run(method, ...args) {
    errorText('');
    try {
      const result = await native(method, ...args);
      if (result === false) {
        await readState();
        if (!state?.error) errorText(t('Не удалось выполнить действие'));
      }
      return result !== false;
    } catch (error) {
      errorText(error.message || t('Не удалось выполнить действие'));
      return false;
    } finally {
      schedule(0);
    }
  }

  async function submit() {
    if (sending || switching || attachmentAction || state?.attachmentsPicking || !state?.id || !input.value.trim()) return;
    sending = true;
    inputState();
    try {
      if (audio.active && !await run('stopVoice')) return;
      if (!await flushDraft()) return;
      const thread = state.thread;
      const agent = state.agent;
      const message = input.value.trim();
      if (message && await run('send', message, thread, ...(agent ? [agent] : []))) await readState();
    } catch (error) {
      errorText(error.message);
    } finally {
      sending = false;
      inputState();
    }
  }

  async function changeAttachments(method, value) {
    if (!state?.id || switching || sending || attachmentAction || state.attachmentsPicking) return;
    attachmentAction = true;
    inputState();
    try {
      if (method === 'pickAttachment' && !await flushDraft()) return;
      if (await run(method, value)) await readState();
    } catch (error) {
      errorText(error.message);
    } finally {
      attachmentAction = false;
      inputState();
    }
  }

  async function changeDialog(method, ...args) {
    if (switching || sending) return;
    switching = true;
    inputState();
    try {
      if (!await flushDraft() || !await run(method, ...args)) return;
      pane = '';
      await readState();
    } catch (error) {
      errorText(error.message);
    } finally {
      switching = false;
      inputState();
    }
  }

  function toggleText() {
    if (pane && state?.mode === 'text') {
      pane = '';
      renderSheet();
      return;
    }
    // The composer appears without forcing the keyboard, so the centered mic stays available.
    changeDialog('setMode', state?.mode === 'text' ? 'voice' : 'text');
  }

  async function toggleVoice() {
    if (audio.state === 'speaking') {
      await run('stopSpeaking');
    } else if (audio.active) {
      await run('stopVoice');
    } else if (await flushDraft()) {
      await run('startVoice');
    }
  }

  function micActions() {
    let press;
    let suppressClick = false;
    let longPressTimer;
    const cancel = () => { clearTimeout(longPressTimer); press = null; };
    const start = async () => {
      if (switching || sending || mic.disabled || !await flushDraft()) return;
      if (await run('startConversation')) await readState();
    };
    mic.onpointerdown = event => {
      if (!event.isPrimary || event.button !== 0 || mic.disabled) return;
      suppressClick = false;
      press = event;
      longPressTimer = setTimeout(() => {
        press = null;
        suppressClick = true;
        start().catch(error => errorText(error.message));
      }, 550);
    };
    mic.onpointermove = event => {
      if (press && Math.hypot(event.clientX - press.clientX, event.clientY - press.clientY) > 14) cancel();
    };
    mic.onpointerup = mic.onpointercancel = mic.onpointerleave = cancel;
    mic.oncontextmenu = event => event.preventDefault();
    mic.onkeydown = event => {
      if (event.key !== 'Enter' || !event.shiftKey || event.repeat) return;
      event.preventDefault();
      start().catch(error => errorText(error.message));
    };
    mic.onclick = event => {
      if (suppressClick && event.detail !== 0) { suppressClick = false; return; }
      toggleVoice().catch(error => errorText(error.message));
    };
  }

  async function openApp() {
    if (switching) return;
    switching = true;
    inputState();
    try {
      if (await flushDraft()) await run('openApp');
    } finally {
      switching = false;
      inputState();
    }
  }

  function queueHitAreas() {
    if (hitFrame !== undefined) return;
    hitFrame = requestAnimationFrame(() => {
      hitFrame = undefined;
      const width = window.innerWidth;
      const height = window.innerHeight;
      if (!width || !height) return;
      const targets = [...panel.querySelectorAll('[data-assistant-touch]')];
      if (getComputedStyle(actionRow).overflowX === 'auto' && actionRow.scrollWidth > actionRow.clientWidth) {
        targets.push(actionRow);
      }
      const rects = [];
      for (const node of targets) {
        if (targets.at(-1) === actionRow && node !== actionRow && actionRow.contains(node)) continue;
        const box = node.getBoundingClientRect();
        const left = Math.max(0, Math.floor(box.left));
        const top = Math.max(0, Math.floor(box.top));
        const right = Math.min(width, Math.ceil(box.right));
        const bottom = Math.min(height, Math.ceil(box.bottom));
        if (left >= right || top >= bottom) continue;
        const rounded = parseFloat(getComputedStyle(node).borderTopLeftRadius) || 0;
        const radius = Math.min(rounded, (right - left) / 2, (bottom - top) / 2);
        rects.push({ left, top, right, bottom, radius });
      }
      const layout = { width, height, rects };
      const key = JSON.stringify(layout);
      if (!rects.length || key === lastHitLayout) return;
      lastHitLayout = key;
      native('hitAreas', layout).catch(() => {});
    });
  }

  function actions() {
    byId('expand').onclick = () => {
      pane = pane === 'history' ? '' : 'history';
      renderSheet();
    };
    byId('sessions').onclick = () => {
      clearTimeout(sessionSearchTimer);
      if (pane === 'sessions') { pane = ''; renderSheet(); }
      else {
        if (sessionSearch) sessionSearch.value = '';
        showSessions();
      }
    };
    if (sessionSearch) sessionSearch.oninput = () => {
      clearTimeout(sessionSearchTimer);
      sessionListRequest++;
      sessionSearchTimer = setTimeout(() => {
        if (pane === 'sessions') showSessions(sessionSearch.value.trim());
      }, 175);
    };
    if (byId('participants')) byId('participants').onclick = async () => {
      const { agent, thread } = state;
      try {
        const options = await native('agents');
        if (state.agent !== agent || state.thread !== thread || !['text', 'history'].includes(sheet())) return;
        participantOptions = Array.isArray(options) ? options : [];
        pane = 'participants';
        renderSheet();
      } catch (error) { errorText(error.message); }
    };
    byId('chat').onclick = toggleText;
    byId('drawer-close').onclick = () => {
      if (pane) { pane = ''; renderSheet(); }
      else if (state?.mode === 'text') toggleText();
    };
    byId('new').onclick = () => changeDialog('newSession');
    if (app) app.onclick = openApp;
    byId('close').onclick = async () => { if (await flushDraft()) run('close'); };
    micActions();
    byId('silence').onclick = () => run('stopSpeaking');
    if (byId('retry')) byId('retry').onclick = async event => {
      const button = event.currentTarget;
      button.disabled = true;
      try { if (await run('retry')) await readState(); }
      catch (error) { errorText(error.message); }
      finally { button.disabled = false; }
    };
    byId('cancel').onclick = () => run('cancel');
    byId('screen').onclick = () => run('attachScreen', !state?.context?.attached);
    if (file) file.onclick = () => changeAttachments('pickAttachment', 'file');
    if (camera) camera.onclick = () => changeAttachments('pickAttachment', 'camera');
    byId('resume').onclick = () => changeDialog('resume');
    byId('open').onclick = async () => { if (await flushDraft()) run('openChat'); };
    byId('composer').onsubmit = event => { event.preventDefault(); submit(); };
    input.oninput = draftChanged;
    input.onkeydown = event => {
      if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
      event.preventDefault();
      submit();
    };
  }

  function start() {
    const field = byId('wave-field');
    [5, 9, 12, 16, 11, 8, 5].forEach((height, index) => field.append(h('span', {
      style: '--height:' + height + 'px;--delay:' + (-index * .11) + 's',
    })));
    ui.icons(panel);
    actions();
    axiko.on('assistant', () => schedule());
    axiko.on('assistant.audio', event => {
      if (!event || (event.id && event.id !== state?.id)) return;
      audioVersion++;
      audio = { ...audio, ...event, error: event.error || '' };
      renderAudio();
    });
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        clearTimeout(refreshTimer);
        refreshTimer = null;
        refreshAt = Infinity;
      } else schedule(0);
    });
    const resize = new ResizeObserver(queueHitAreas);
    for (const node of [panel, ...panel.children]) resize.observe(node);
    window.addEventListener('resize', queueHitAreas);
    queueHitAreas();
    readState().then(() => native('ready')).catch(error => errorText(error.message));
  }

  start();
})();

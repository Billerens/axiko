/* Universal browser hub. The factory copy is installed into the editable workspace. */
(function () {
  'use strict';
  const T = window.AxikoTransport;
  const $ = id => document.getElementById(id);
  const h = ui.h;
  const desk = $('desk');
  const frame = $('page');
  const chatFrame = $('chat');
  const narrow = window.matchMedia('(max-width: 759px)');
  const state = { mode: 'app', tab: 'code', path: '', modules: [], nav: [], tasks: [], recent: [], factory: false, status: null };
  let editor = null;
  let editorLoading = null;
  let requestedEditorFile = null;
  const editorAssets = new Map();
  const dataJobs = new Map();
  const dataErrors = new Map();
  let navSignature = null;
  let modulesSignature = null;
  let recentSignature = null;
  let viewKey = null;
  let viewMotionTicket = 0;
  let refreshTimer = null;
  let journalBusy = false;
  let journalLines = [];
  let journalTimer = null;
  let chatAgent = null;
  let chatReady = false;
  let frameLoadingTimer = null;
  let frameNavigation = null;
  let frameNavigationId = 0;
  let noticeKind = '';
  let routeApplied = false;
  const subscribers = new Set();

  function fanout(event) {
    [...subscribers].forEach(callback => {
      try { callback(event); } catch (error) { console.error('Axiko frame event:', error); }
    });
  }
  function visibilityChanged() {
    fanout({ event: 'desk.visibility' });
  }


  function readLocal(key, fallback) {
    try { const value = localStorage.getItem('desk.' + key); return value === null ? fallback : JSON.parse(value); }
    catch (error) { return fallback; }
  }
  function writeLocal(key, value) {
    try { localStorage.setItem('desk.' + key, JSON.stringify(value)); } catch (error) { /* storage can be unavailable */ }
  }
  function toast(text) { ui.toast(String(text)); }
  function notice(text, kind = 'general') {
    // A delayed resource must not replace an actionable data/editor error.
    if (kind === 'frame' && !$('hub-notice').hidden && noticeKind !== 'frame') return;
    noticeKind = kind;
    $('hub-notice').dataset.kind = kind;
    $('hub-notice-text').textContent = String(text);
    $('hub-notice').hidden = false;
  }
  function clearNotice(kind) {
    if (kind && noticeKind !== kind) return;
    noticeKind = '';
    delete $('hub-notice').dataset.kind;
    $('hub-notice').hidden = true;
  }
  function validPath(raw, allowDesk) {
    if (typeof raw !== 'string' || !raw.trim() || /[\u0000-\u001f\\]/.test(raw)) throw new Error('Некорректный адрес экрана');
    if (/^[a-z][a-z0-9+.-]*:/i.test(raw) || raw.startsWith('//')) throw new Error('Откройте локальный экран Axiko');
    const path = raw.replace(/^\/+/, '');
    const pathname = path.split(/[?#]/)[0];
    const parts = pathname.split('/').map(part => decodeURIComponent(part));
    if (parts.some(part => part === '..' || part === '.' || part.includes('/') || part.includes('\\'))) throw new Error('Некорректный путь');
    if (!/^(?:~[a-z0-9]+\/)?(?:ui|modules)\//.test(pathname)) throw new Error('Экран должен находиться в ui/ или modules/');
    if (!allowDesk && /^(?:~[a-z0-9]+\/)?ui\/desk(?:\/|$)/.test(pathname)) throw new Error('Интерфейс хаба уже открыт');
    return path;
  }
  function plainPath(path) { return String(path || '').replace(/^~[a-z0-9]+\//, '').split(/[?#]/)[0]; }
  function titleOf(path) {
    const plain = plainPath(path);
    const nav = state.nav.find(item => plainPath(item.open) === plain);
    if (nav) return nav.title;
    const module = state.modules.find(item => plainPath(item.entry) === plain || plain.startsWith('modules/' + item.id + '/'));
    if (module) return module.title;
    return plain.split('/').filter(Boolean).slice(-2).join(' / ') || 'Обзор';
  }
  function iconOf(path) {
    const plain = plainPath(path);
    return state.modules.find(item => plain.startsWith('modules/' + item.id + '/')) ||
      state.nav.find(item => plainPath(item.open) === plain) || { icon: 'file' };
  }
  function mark(item) {
    try { return ui.mark(item || { icon: 'box' }); } catch (error) { return ui.icon('box'); }
  }
  function setNavOpen(on) {
    desk.classList.toggle('nav-open', !!on);
    $('sidebar').inert = narrow.matches && !on;
    $('sidebar-shade').hidden = !on;
    $('mobile-menu').setAttribute('aria-expanded', String(!!on));
  }
  function setChat(on, agent) {
    if (agent) {
      chatAgent = agent;
      if (chatReady && chatFrame.contentWindow && typeof chatFrame.contentWindow.__axikoEmit === 'function') {
        chatFrame.contentWindow.__axikoEmit('chat.agent', agent);
        chatAgent = null;
      }
    }
    if (narrow.matches) {
      desk.classList.toggle('mobile-chat', !!on);
      desk.classList.remove('chat-hidden');
    } else {
      desk.classList.toggle('chat-hidden', !on);
      desk.classList.remove('mobile-chat');
      writeLocal('chatVisible', !!on);
    }
    $('chat-toggle').setAttribute('aria-pressed', String(!!on));
    setNavOpen(false);
    syncFrameLoading();
    visibilityChanged();
  }
  function stage(on) {
    if (on && !state.path) return false;
    desk.classList.toggle('stage', !!on);
    $('stage-exit').hidden = !on;
    visibilityChanged();
    return true;
  }
  function currentPath() {
    if (!state.path) return '';
    try {
      const location = frame.contentWindow.location;
      if (location.origin === window.location.origin && location.pathname !== '/ui/desk/index.html') {
        return location.pathname.replace(/^\/+/, '') + location.search + location.hash;
      }
    } catch (error) { /* frame is navigating */ }
    return state.path;
  }
  function saveRoute(replace) {
    const params = new URLSearchParams();
    if (state.path) params.set('screen', state.path);
    if (state.mode === 'studio') params.set('studio', state.tab);
    const hash = params.toString() ? '#' + params.toString() : '#home';
    if (location.hash !== hash) history[replace ? 'replaceState' : 'pushState'](null, '', location.pathname + location.search + hash);
    writeLocal('route', { path: state.path, mode: state.mode, tab: state.tab });
  }
  function remember(path) {
    state.recent = [{ path, at: Date.now() }].concat(state.recent.filter(item => item.path !== path)).slice(0, 8);
    writeLocal('recent', state.recent);
    renderRecent();
  }
  function documentPath(path) {
    try {
      const url = new URL('/' + String(path || '').replace(/^\/+/, ''), location.origin);
      return url.pathname + url.search;
    } catch (error) { return ''; }
  }
  function stopFrameWaiting() {
    clearTimeout(frameLoadingTimer);
    frameLoadingTimer = null;
    $('frame-loading').hidden = true;
    clearNotice('frame');
  }
  function syncFrameLoading() {
    const visible = !!state.path && (state.mode !== 'studio' || state.tab === 'preview') &&
      !(narrow.matches && desk.classList.contains('mobile-chat')) && !document.hidden;
    if (!visible || !frameNavigation || frameNavigation.ready) {
      stopFrameWaiting();
      if (!visible && frameNavigation) frameNavigation.warned = false;
      return;
    }
    if (frameLoadingTimer !== null || frameNavigation.warned) return;
    const pending = frameNavigation;
    $('frame-loading').hidden = false;
    frameLoadingTimer = setTimeout(() => {
      frameLoadingTimer = null;
      if (pending !== frameNavigation || pending.ready) return;
      pending.warned = true;
      $('frame-loading').hidden = true;
      notice('Экран открывается дольше обычного. Проверьте соединение или нажмите «Обновить».', 'frame');
    }, 15000);
  }
  function frameReady(sourceWindow, path) {
    if (sourceWindow !== frame.contentWindow) return false;
    const expected = documentPath(frame.dataset.route);
    if (!expected || documentPath(path) !== expected) return false;
    try {
      const actual = sourceWindow.location;
      const doc = sourceWindow.document;
      if (actual.origin !== location.origin || actual.pathname + actual.search !== expected || doc.readyState === 'loading') return false;
      // A same-URL reload still has the previous document until navigation commits.
      if (frameNavigation && !frameNavigation.ready && doc === frameNavigation.previousDocument) return false;
    } catch (error) { return false; }
    if (frameNavigation) {
      frameNavigation.ready = true;
      frameNavigation.previousDocument = null;
    }
    stopFrameWaiting();
    return true;
  }
  function setFrame(path, force = false) {
    if (!force && frame.dataset.route === path) return;
    let previousDocument = null;
    let sameDocument = false;
    try {
      previousDocument = frame.contentDocument;
      sameDocument = !force && (!frameNavigation || frameNavigation.ready) &&
        documentPath(frame.dataset.route) === documentPath(path) &&
        frame.contentWindow.location.pathname + frame.contentWindow.location.search === documentPath(path) &&
        previousDocument.readyState !== 'loading';
    } catch (error) { /* a previous navigation may not have committed */ }
    stopFrameWaiting();
    frame.dataset.route = path;
    frameNavigation = { id: ++frameNavigationId, ready: sameDocument, warned: false, previousDocument };
    frame.src = '/' + path;
    syncFrameLoading();
  }
  function reloadFrame() {
    const path = frame.dataset.route || state.path;
    if (path) setFrame(path, true);
  }
  function openScreen(path, options) {
    options = options || {};
    try { path = validPath(path); } catch (error) { notice(error.message); return false; }
    state.path = path;
    state.mode = options.studio ? 'studio' : 'app';
    if (options.studio) state.tab = 'preview';
    stage(false);
    setFrame(path);
    if (!options.restore) remember(path);
    if (!options.noHistory) saveRoute(!!options.replace);
    renderView();
    setNavOpen(false);
    if (narrow.matches) setChat(false);
    return true;
  }
  function overview(options) {
    state.mode = 'app';
    state.path = '';
    stage(false);
    if (!options || !options.noHistory) saveRoute(false);
    renderView();
    setNavOpen(false);
    if (narrow.matches) setChat(false);
  }
  function back() {
    if (location.hash !== '#home' && history.length > 1) history.back();
    else overview();
  }
  function studio(tab, options) {
    state.mode = 'studio';
    state.tab = tab || 'code';
    if (state.tab === 'preview' && !state.path) {
      state.path = 'ui/home/index.html';
      setFrame(state.path);
    }
    stage(false);
    if (!options || !options.noHistory) saveRoute(false);
    renderView();
    setNavOpen(false);
    if (narrow.matches) setChat(false);
  }
  function openEditorPreview(path) {
    try { path = validPath(path, true); } catch (error) { notice(error.message); return; }
    if (/^(?:~[a-z0-9]+\/)?ui\/desk\//.test(path)) {
      window.open('/' + path, '_blank', 'noopener');
    } else openScreen(path, { studio: true });
  }
  function editorAsset(path, style) {
    if (editorAssets.has(path)) return editorAssets.get(path);
    const promise = new Promise((resolve, reject) => {
      const existing = document.querySelector(style ? 'link[href="' + path + '"]' : 'script[src="' + path + '"]');
      if (existing) { resolve(); return; }
      const node = document.createElement(style ? 'link' : 'script');
      if (style) { node.rel = 'stylesheet'; node.href = path; }
      else node.src = path;
      let finished = false;
      const finish = error => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        node.onload = node.onerror = null;
        if (error) { node.remove(); editorAssets.delete(path); reject(error); }
        else resolve();
      };
      const timer = setTimeout(() => finish(new Error('Загрузка редактора заняла слишком много времени. Повторите попытку.')), 15000);
      node.onload = () => finish();
      node.onerror = () => finish(new Error('Не удалось загрузить редактор. Проверьте соединение.'));
      document.head.append(node);
    });
    editorAssets.set(path, promise);
    return promise;
  }
  function forgetEditorScript(path, name) {
    editorAssets.delete(path);
    document.querySelectorAll('script[src="' + path + '"]').forEach(node => node.remove());
    delete window[name];
  }
  function mountEditor() {
    if (editor) return Promise.resolve(editor);
    if (editorLoading) return editorLoading;
    $('studio-editor').replaceChildren(h('p', { class: 'empty-copy editor-loading', role: 'status' }, 'Открываю редактор…'));
    editorLoading = (async () => {
      if (!window.AxikoEditor) {
        await Promise.all([
          editorAsset('/ui/desk/editor.css', true),
          window.AxikoEditorModel ? Promise.resolve() : editorAsset('/ui/desk/editor-model.js', false)
        ]);
        if (typeof window.AxikoEditorModel?.create !== 'function') {
          forgetEditorScript('/ui/desk/editor-model.js', 'AxikoEditorModel');
          throw new Error('Модель редактора не загрузилась. Повторите попытку.');
        }
        await editorAsset('/ui/desk/editor.js', false);
      }
      if (typeof window.AxikoEditor?.mount !== 'function') {
        forgetEditorScript('/ui/desk/editor.js', 'AxikoEditor');
        throw new Error('Редактор не загрузился. Повторите попытку.');
      }
      try {
        editor = window.AxikoEditor.mount($('studio-editor'), { rpc: payload => T.rpc(payload), open: openEditorPreview, toast });
        if (!editor) throw new Error('Редактор не открылся. Повторите попытку.');
      } catch (error) {
        forgetEditorScript('/ui/desk/editor.js', 'AxikoEditor');
        throw error;
      }
      return editor;
    })().catch(error => {
      const retry = h('button', { class: 'plain-action', type: 'button' }, 'Попробовать ещё раз');
      retry.addEventListener('click', () => requestedEditorFile ? openEditorFile(requestedEditorFile.path, requestedEditorFile.options) : mountEditor());
      $('studio-editor').replaceChildren(h('div', { class: 'empty-copy editor-error', role: 'status' }, h('p', {}, error.message), retry));
      return null;
    }).finally(() => { editorLoading = null; });
    return editorLoading;
  }
  function openEditorFile(path, options) {
    const wanted = { path, options };
    requestedEditorFile = wanted;
    studio('code');
    return mountEditor().then(instance => {
      if (!instance || requestedEditorFile !== wanted) return;
      requestedEditorFile = null;
      return instance.openFile(path, options);
    });
  }
  function enterView(element, key) {
    if (key === viewKey) return;
    viewKey = key;
    const ticket = ++viewMotionTicket;
    ['overview', 'screen-view', 'studio-view', 'journal-view'].forEach(id => $(id).classList.remove('is-entering'));
    if (document.hidden || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    requestAnimationFrame(() => {
      if (ticket === viewMotionTicket && !element.hidden) element.classList.add('is-entering');
    });
  }
  function renderView() {
    const isStudio = state.mode === 'studio';
    const isPreview = isStudio && state.tab === 'preview';
    const showScreen = !!state.path && (!isStudio || isPreview);
    $('overview').hidden = isStudio || !!state.path;
    $('screen-view').hidden = !showScreen;
    $('studio-bar').hidden = !isStudio;
    $('studio-view').hidden = !isStudio || state.tab !== 'code';
    $('journal-view').hidden = !isStudio || state.tab !== 'journal';
    $('preview-bar').hidden = !isPreview;
    $('stage-toggle').hidden = !showScreen;
    $('location-eyebrow').textContent = isStudio ? 'Студия' : 'Рабочее место';
    $('location-title').textContent = isStudio ? ({ code: 'Файлы и код', preview: 'Просмотр экрана', journal: 'Журнал' }[state.tab]) : titleOf(state.path);
    $('preview-path').textContent = state.path || 'Выберите экран';
    $('draft-label').hidden = !/^~[a-z0-9]+\//.test(state.path);
    document.querySelectorAll('[data-studio]').forEach(button => button.setAttribute('aria-selected', String(button.dataset.studio === state.tab)));
    document.querySelectorAll('.nav-item[data-route]').forEach(button => {
      const active = !isStudio && (button.dataset.route === 'overview' ? !state.path : plainPath(button.dataset.route) === plainPath(state.path));
      button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
    });
    $('studio-open').classList.toggle('active', isStudio);
    const width = isPreview ? readLocal('previewWidth', 'auto') : 'auto';
    applyWidth(width, false);
    if (isStudio && state.tab === 'code') mountEditor();
    clearInterval(journalTimer);
    if (isStudio && state.tab === 'journal') {
      readJournal();
      journalTimer = setInterval(readJournal, 4000);
    }
    const activeView = isStudio ? (isPreview ? 'screen-view' : state.tab === 'code' ? 'studio-view' : 'journal-view') : showScreen ? 'screen-view' : 'overview';
    enterView($(activeView), activeView + (showScreen ? ':' + state.path : ''));
    syncFrameLoading();
    visibilityChanged();
  }

  // Installed before any iframe gets a src: every nested module shares one event connection.
  window.axikoDesk = {
    rpc: payload => T.rpc(payload),
    subscribe: callback => {
      subscribers.add(callback);
      return () => subscribers.delete(callback);
    },
    reconnect: () => T.reconnect(),
    where: currentPath,
    ready: frameReady,
    open: path => openScreen(path),
    back,
    chat: setChat,
    stage,
    openFile: openEditorFile
  };
  window.dispatchEvent(new Event('axiko-desk-ready'));

  function navButton(item, route) {
    const button = h('button', { class: 'nav-item', title: item.title, 'data-route': route },
      mark(item), h('span', { class: 'nav-label' }, item.title));
    button.addEventListener('click', () => route === 'overview' ? overview() : openScreen(route));
    return button;
  }
  function renderNav() {
    const signature = JSON.stringify([state.nav, state.modules]);
    if (signature === navSignature) return;
    navSignature = signature;
    const known = new Set();
    const rows = [navButton({ title: 'Обзор', icon: 'grid' }, 'overview')];
    const items = state.nav.slice();
    if (!items.some(item => plainPath(item.open) === 'modules/games/ui/index.html')) {
      items.splice(1, 0, { title: 'Игротека', icon: 'play', open: 'modules/games/ui/index.html' });
    }
    if (!items.some(item => plainPath(item.open) === 'ui/store/index.html')) {
      items.splice(2, 0, { title: 'Магазин', icon: 'box', open: 'ui/store/index.html' });
    }
    items.forEach(item => {
      let route;
      try { route = validPath(item.open); } catch (error) { return; }
      if (known.has(route)) return;
      known.add(route);
      const shown = Object.assign({}, item);
      if (plainPath(route) === 'ui/home/index.html') shown.title = 'Домашний экран';
      rows.push(navButton(shown, route));
    });
    $('primary-nav').replaceChildren(...rows);
    known.add('modules/store/ui/index.html');
    const modules = state.modules.filter(module => !known.has(module.entry));
    $('side-count').textContent = String(state.modules.length);
    $('module-nav').replaceChildren(...(modules.length ? modules.map(module => navButton(module, module.entry)) :
      [h('p', { class: 'sidebar-note' }, 'Модули появятся здесь после установки.')]));
    renderView();
  }
  function renderModules() {
    const query = $('module-filter').value.trim().toLocaleLowerCase();
    const modules = state.modules.filter(module => (module.title + ' ' + module.description).toLocaleLowerCase().includes(query));
    $('module-count').textContent = String(state.modules.length);
    const signature = JSON.stringify([query, modules]);
    if (signature === modulesSignature) return;
    modulesSignature = signature;
    $('module-grid').classList.toggle('modules-enter', !query);
    $('module-grid').replaceChildren(...(modules.length ? modules.map((module, index) => {
      let tone = 0;
      for (const letter of module.id) tone = ((tone * 31) + letter.charCodeAt(0)) >>> 0;
      const button = h('button', { class: 'module-tile', 'data-module': module.id, title: module.title },
        h('span', { class: 'module-symbol' }, mark(module)),
        h('span', { class: 'module-description' }, h('strong', {}, module.title), h('small', {}, module.description || 'Открыть модуль')));
      button.dataset.tone = ['violet', 'mint', 'warm', 'blue'][tone % 4];
      button.style.setProperty('--tile-index', String(Math.min(index, 7)));
      if (!query && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        button.classList.add('tile-enter');
        button.addEventListener('animationend', () => button.classList.remove('tile-enter'), { once: true });
      }
      button.addEventListener('click', () => openScreen(module.entry));
      return button;
    }) : [h('p', { class: 'empty-copy' }, query ? 'По этому запросу модулей не найдено.' : 'Модулей пока нет. Попросите агента создать нужную функцию.')]));
  }
  function renderRecent() {
    const items = state.recent.filter(item => {
      try { validPath(item.path); return true; } catch (error) { return false; }
    });
    const signature = JSON.stringify(items.slice(0, 6).map(item => [item.path, titleOf(item.path), iconOf(item.path)]));
    if (signature === recentSignature) return;
    recentSignature = signature;
    $('recent-section').hidden = !items.length;
    $('recent-list').replaceChildren(...items.slice(0, 6).map(item => {
      const button = h('button', { class: 'recent-item', title: titleOf(item.path) }, mark(iconOf(item.path)), h('span', {}, titleOf(item.path)));
      button.addEventListener('click', () => openScreen(item.path));
      return button;
    }));
  }
  function renderTasks() {
    const active = state.tasks.filter(task => task.state === 'run' && !task.parent).length;
    $('task-summary').textContent = active ? 'В работе: ' + active : 'Работа агента';
    renderHeroStatus();
  }
  function renderHeroStatus() {
    const status = $('hero-status');
    if (!status) return;
    const active = state.tasks.filter(task => task.state === 'run' && !task.parent).length;
    const connection = state.status?.state;
    status.textContent = connection === 'online' ? (active ? 'В работе: ' + active : 'На связи с вашим телефоном') :
      connection === 'auth-required' ? 'Нужно войти снова' : connection === 'offline' || connection === 'closed' ? 'Жду соединения с телефоном' : 'Подключаюсь к Axiko…';
    status.dataset.state = connection || 'connecting';
  }
  function setIdentity(status) {
    state.factory = !!status.factory;
    $('brand-name').textContent = status.name || 'Axiko';
    document.title = (status.name || 'Axiko') + ' — ваше пространство';
    $('factory-label').hidden = !state.factory;
    $('factory-switch').textContent = state.factory ? 'Вернуться к своей версии' : 'Заводской интерфейс';
  }
  function reportDataErrors() {
    const error = dataErrors.values().next().value;
    if (error) notice('Часть данных пока недоступна: ' + (error.message || error), 'data');
    else clearNotice('data');
  }
  const dataSources = {
    modules: {
      request: () => T.native('modules.list'),
      apply: value => {
        state.modules = (Array.isArray(value) ? value : []).filter(item => item && item.id && item.entry)
          .map(item => Object.assign({}, item, { title: String(item.title || item.id), description: String(item.description || '') }))
          .sort((a, b) => a.title.localeCompare(b.title, 'ru'));
        renderModules(); renderNav(); renderRecent();
      }
    },
    nav: {
      request: () => T.eval('require("lib/axiko").files.read("ui/nav.json")'),
      apply: value => {
        const nav = typeof value === 'string' ? JSON.parse(value) : value;
        state.nav = Array.isArray(nav?.items) ? nav.items : [];
        renderNav(); renderRecent();
      }
    },
    tasks: { request: () => T.native('tasks.list'), apply: value => { state.tasks = Array.isArray(value) ? value : []; renderTasks(); } },
    identity: { request: () => T.rpc({ op: 'desk.status' }), apply: value => setIdentity(value || {}) }
  };
  function refreshDataSource(name) {
    let job = dataJobs.get(name);
    if (job?.pending) { job.again = true; return job.pending; }
    if (!job) { job = { pending: null, again: false }; dataJobs.set(name, job); }
    const source = dataSources[name];
    job.pending = Promise.resolve().then(source.request).then(value => {
      source.apply(value);
      dataErrors.delete(name);
    }).catch(error => {
      dataErrors.set(name, error);
      if (name === 'modules' && !state.modules.length) {
        modulesSignature = null;
        $('module-grid').replaceChildren(h('p', { class: 'empty-copy' }, 'Модули не загрузились. Восстановите соединение и повторите.'));
      }
    }).finally(() => {
      job.pending = null;
      reportDataErrors();
      if (job.again) { job.again = false; return refreshDataSource(name); }
    });
    return job.pending;
  }
  function refreshData() {
    // Independent results paint immediately; a slow journal/task backend cannot hold the catalog.
    return Promise.allSettled(Object.keys(dataSources).map(refreshDataSource));
  }
  function scheduleRefresh() {
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(refreshData, 180);
  }
  function onStatus(status) {
    state.status = status;
    $('connection').dataset.state = status.state;
    const labels = { connecting: 'Подключаюсь…', online: 'Телефон подключён', offline: 'Нет связи с телефоном', 'auth-required': 'Войдите снова', closed: 'Соединение закрыто' };
    $('connection-label').textContent = labels[status.state] || 'Соединение';
    $('connection-detail').textContent = status.state === 'online' ? 'Изменения доступны сразу' :
      status.state === 'auth-required' ? 'Нажмите, чтобы войти' : 'Данные и правки остаются на месте';
    $('connection').setAttribute('aria-label', labels[status.state] || 'Соединение');
    renderHeroStatus();
  }
  function applyWidth(value, save) {
    const width = ['344', '690'].includes(String(value)) ? String(value) : 'auto';
    $('frame-stage').classList.toggle('sized', width !== 'auto');
    $('frame-stage').style.setProperty('--preview-width', width === 'auto' ? '100%' : width + 'px');
    document.querySelectorAll('[data-width]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.width === width)));
    if (save !== false) writeLocal('previewWidth', width);
  }
  function setChatWidth(width, save) {
    const max = Math.max(300, Math.min(640, window.innerWidth - 460));
    width = Math.max(300, Math.min(max, Number(width) || 380));
    desk.style.setProperty('--desk-chat', width + 'px');
    $('chat-divider').setAttribute('aria-valuenow', String(Math.round(width)));
    if (save !== false) writeLocal('chatWidth', width);
    return width;
  }
  function restoreRoute() {
    const hash = location.hash.slice(1);
    const params = new URLSearchParams(hash);
    const stored = !routeApplied && !hash ? readLocal('route', null) : null;
    routeApplied = true;
    const path = params.get('screen') || stored?.path || '';
    const tab = params.get('studio') || (stored?.mode === 'studio' ? stored.tab : '');
    if (path) openScreen(path, { restore: true, noHistory: true, studio: !!tab });
    else { state.path = ''; state.mode = 'app'; }
    if (['code', 'preview', 'journal'].includes(tab)) studio(tab, { noHistory: true });
    else { state.mode = 'app'; renderView(); }
    saveRoute(true);
  }

  async function readJournal() {
    if (journalBusy || document.hidden || state.mode !== 'studio' || state.tab !== 'journal') return;
    journalBusy = true;
    try {
      const lines = await T.native('log.engine', 500);
      journalLines = Array.isArray(lines) ? lines : [];
      renderJournal();
      $('journal-meta').textContent = 'Последние ' + journalLines.length + ' сообщений · обновлено ' + new Date().toLocaleTimeString('ru', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch (error) { $('journal-meta').textContent = 'Журнал недоступен: ' + error.message; }
    finally { journalBusy = false; }
  }
  function renderJournal() {
    const query = $('journal-search').value.toLocaleLowerCase();
    const level = $('journal-level').value;
    const lines = journalLines.filter(line => {
      const text = String(line).toLocaleLowerCase();
      return (!query || text.includes(query)) && (level === 'all' || (level === 'error' ? /error|ошибк/.test(text) : /warn|предупрежд/.test(text)));
    });
    const box = $('journal-lines');
    const pinned = box.scrollHeight - box.scrollTop - box.clientHeight < 50;
    box.textContent = lines.length ? lines.join('\n') : journalLines.length ? 'Нет сообщений по выбранному фильтру.' : 'Журнал пока пуст.';
    if (pinned) box.scrollTop = box.scrollHeight;
  }

  function commands() {
    return [
      { title: 'Обзор', icon: 'grid', kind: 'Раздел', run: overview },
      { title: 'Студия: файлы и код', icon: 'code', kind: 'Действие', run: () => studio('code') },
      { title: 'Студия: журнал', icon: 'list', kind: 'Действие', run: () => studio('journal') },
      { title: 'Поговорить с агентом', icon: 'message', kind: 'Действие', run: () => setChat(true) }
    ].concat(state.nav.map(item => ({ title: item.title, icon: item.icon || 'box', kind: 'Раздел', run: () => openScreen(item.open) })),
      state.modules.map(item => ({ title: item.title, icon: item.icon || 'box', kind: 'Модуль', run: () => openScreen(item.entry) })));
  }
  function renderCommands() {
    const query = $('command-input').value.trim().toLocaleLowerCase();
    const list = commands().filter(item => item.title.toLocaleLowerCase().includes(query)).slice(0, 18);
    $('command-results').replaceChildren(...(list.length ? list.map(item => {
      const button = h('button', { class: 'command-result', type: 'button' }, ui.icon(item.icon), h('span', {}, item.title), h('small', {}, item.kind));
      button.addEventListener('click', () => { $('command-dialog').close(); item.run(); });
      return button;
    }) : [h('p', { class: 'empty-copy' }, 'Ничего не найдено.')]));
  }
  function openCommands() {
    $('command-input').value = '';
    renderCommands();
    if (!$('command-dialog').open) $('command-dialog').showModal();
    $('command-input').focus();
  }

  $('brand').addEventListener('click', () => overview());
  $('collapse-nav').addEventListener('click', () => {
    if (narrow.matches) { setNavOpen(false); return; }
    desk.classList.toggle('compact');
    writeLocal('compact', desk.classList.contains('compact'));
    $('collapse-nav').setAttribute('aria-label', desk.classList.contains('compact') ? 'Развернуть навигацию' : 'Свернуть навигацию');
  });
  $('mobile-menu').addEventListener('click', () => setNavOpen(!desk.classList.contains('nav-open')));
  $('sidebar-shade').addEventListener('click', () => setNavOpen(false));
  $('chat-toggle').addEventListener('click', () => setChat(narrow.matches ? !desk.classList.contains('mobile-chat') : desk.classList.contains('chat-hidden')));
  $('chat-close').addEventListener('click', () => setChat(false));
  $('create-prompt').addEventListener('click', () => setChat(true));
  $('studio-open').addEventListener('click', () => studio('code'));
  $('quick-studio').addEventListener('click', () => studio('code'));
  $('leave-studio').addEventListener('click', () => { state.mode = 'app'; saveRoute(false); renderView(); });
  document.querySelectorAll('[data-route]').forEach(button => button.addEventListener('click', () => openScreen(button.dataset.route)));
  document.querySelectorAll('[data-studio]').forEach(button => button.addEventListener('click', () => studio(button.dataset.studio)));
  document.querySelectorAll('[data-width]').forEach(button => button.addEventListener('click', () => applyWidth(button.dataset.width)));
  $('module-filter').addEventListener('input', renderModules);
  $('clear-recent').addEventListener('click', () => { state.recent = []; writeLocal('recent', []); renderRecent(); });
  $('notice-close').addEventListener('click', () => clearNotice());
  $('hub-retry').addEventListener('click', () => noticeKind === 'frame' ? reloadFrame() : refreshData());
  $('reload').addEventListener('click', () => {
    if (state.mode === 'studio' && state.tab === 'code') mountEditor().then(instance => instance?.refresh());
    else if (state.mode === 'studio' && state.tab === 'journal') readJournal();
    else if (state.path) reloadFrame();
    else refreshData();
  });
  $('stage-toggle').addEventListener('click', () => stage(true));
  $('stage-exit').addEventListener('click', () => stage(false));
  $('follow-agent').checked = readLocal('follow', false);
  $('follow-agent').addEventListener('change', () => writeLocal('follow', $('follow-agent').checked));
  $('search-open').addEventListener('click', openCommands);
  $('command-input').addEventListener('input', renderCommands);
  $('command-dialog').addEventListener('click', event => { if (event.target === $('command-dialog')) $('command-dialog').close(); });
  $('command-dialog').addEventListener('keydown', event => {
    const buttons = [...$('command-results').querySelectorAll('button')];
    const at = buttons.indexOf(document.activeElement);
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const index = event.key === 'ArrowDown' ? Math.min(buttons.length - 1, at + 1) : Math.max(0, at - 1);
      buttons[index]?.focus();
    } else if (event.key === 'Enter' && document.activeElement === $('command-input')) {
      event.preventDefault();
      buttons[0]?.click();
    }
  });
  document.addEventListener('keydown', event => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); openCommands(); }
    if (event.key === 'Escape') { stage(false); setNavOpen(false); }
  });
  $('journal-search').addEventListener('input', renderJournal);
  $('journal-level').addEventListener('change', renderJournal);
  $('journal-refresh').addEventListener('click', readJournal);
  $('journal-download').addEventListener('click', () => {
    const url = URL.createObjectURL(new Blob([journalLines.join('\n')], { type: 'text/plain;charset=utf-8' }));
    const link = h('a', { href: url, download: 'axiko-journal.txt' });
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  $('connection').addEventListener('click', async () => {
    if (state.status?.state === 'auth-required') {
      if (!editor || !editor.canLeave || await editor.canLeave()) T.login();
    } else refreshData();
  });
  $('factory-switch').addEventListener('click', async () => {
    if (editor?.canLeave && !await editor.canLeave()) return;
    location.href = '/?factory=' + (state.factory ? '0' : '1') + location.hash;
  });
  $('chat-divider').addEventListener('pointerdown', event => {
    if (narrow.matches) return;
    const start = event.clientX;
    const initial = $('chat-panel').getBoundingClientRect().width;
    const handle = $('chat-divider');
    desk.classList.add('resizing');
    handle.setPointerCapture(event.pointerId);
    const move = next => setChatWidth(initial + start - next.clientX, false);
    const end = () => {
      desk.classList.remove('resizing');
      writeLocal('chatWidth', $('chat-panel').getBoundingClientRect().width);
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', end);
      handle.removeEventListener('pointercancel', end);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', end);
    handle.addEventListener('pointercancel', end);
  });
  $('chat-divider').addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
    event.preventDefault();
    setChatWidth($('chat-panel').getBoundingClientRect().width + (event.key === 'ArrowLeft' ? 20 : -20));
  });
  narrow.addEventListener('change', () => { setChat(narrow.matches ? false : readLocal('chatVisible', true)); setNavOpen(false); });
  window.addEventListener('resize', () => { if (!narrow.matches && !desk.classList.contains('chat-hidden')) setChatWidth(readLocal('chatWidth', 380), false); });
  window.addEventListener('hashchange', restoreRoute);
  window.addEventListener('popstate', restoreRoute);
  frame.addEventListener('load', () => {
    try {
      const actual = frame.contentWindow.location;
      const candidate = validPath(actual.pathname + actual.search + actual.hash);
      // A late load from the previous document cannot finish a newer navigation.
      if (frameNavigation && !frameNavigation.ready && documentPath(candidate) !== documentPath(frame.dataset.route)) return;
      if (state.path && candidate !== state.path) { state.path = candidate; frame.dataset.route = candidate; saveRoute(true); renderView(); }
      frameReady(frame.contentWindow, candidate);
    } catch (error) { /* about:blank during creation */ }
    visibilityChanged();
  });
  chatFrame.addEventListener('load', () => { chatReady = true; if (chatAgent) setChat(true, chatAgent); visibilityChanged(); });
  document.addEventListener('visibilitychange', () => { syncFrameLoading(); visibilityChanged(); if (!document.hidden) { refreshData(); readJournal(); } });
  window.addEventListener('desk.file.saved', () => scheduleRefresh());
  ['overview', 'screen-view', 'studio-view', 'journal-view'].forEach(id => {
    const element = $(id);
    element.addEventListener('animationend', event => {
      if (event.target === element) element.classList.remove('is-entering');
    });
  });

  // ---- the mascot: in the brand mark and in the middle of the overview, where it shows what an
  // agent is doing right now. With the mascot off both places keep their spark.
  const TOOL_POSE = { web_search: 'searching', web_read: 'searching', fs_read: 'searching', fs_list: 'searching', base_find: 'searching',
    agent_run: 'building', subtasks: 'building', draft_begin: 'building', ask_owner: 'asking', plan_propose: 'plan', plan_step: 'plan' };
  let heroMascot = null;
  let cheer = null;
  function drawMascot(config) {
    [['.brand-mark', 'head', false], ['.core-center', 'hello', true]].forEach(([place, pose, hero]) => {
      const holder = document.querySelector(place);
      if (!holder) return;
      holder.querySelectorAll('.mascot').forEach(old => old.remove());
      holder.classList.toggle('has-mascot', Boolean(config));
      const spark = holder.querySelector('svg.i');
      if (spark) spark.style.display = config ? 'none' : '';
      if (!config) { if (hero) heroMascot = null; return; }
      const figure = ui.mascot(pose);
      holder.append(figure);
      if (hero) heroMascot = figure;
    });
  }
  function followAgent(event) {
    if (!heroMascot) return;
    clearTimeout(cheer);
    const show = pose => ui.mascot.pose(heroMascot, pose);
    if (event.type === 'idle') {
      // The work is done: a moment of joy, then back to the greeting.
      show('celebrating');
      cheer = setTimeout(() => show('hello'), 2600);
    } else if (event.type === 'error') show('oops');
    else if (event.type === 'ask') show('asking');
    else if (event.type === 'tool' && event.status === 'run') show(TOOL_POSE[event.name] || 'working');
    else if (event.type === 'user' || event.type === 'thought') show('thinking');
  }

  T.onStatus(onStatus);
  T.subscribe(event => {
    if (!event || !event.event) return;
    fanout(event);
    if (['nav', 'modules', 'workspace.changed', 'desk.reconnected'].includes(event.event)) scheduleRefresh();
    if (event.event === 'tasks') refreshDataSource('tasks');
    if (event.event === 'agent' && event.data?.type === 'changes') scheduleRefresh();
    if (event.event === 'agent' && event.data) followAgent(event.data);
    if (event.event === 'mascot') ui.mascot.ready(true).then(drawMascot);
    if (event.event === 'ui.opened' && $('follow-agent').checked) {
      const path = typeof event.data === 'string' ? event.data : event.data?.path;
      if (path) openScreen(path);
    }
  });

  state.recent = readLocal('recent', []);
  state.recent = Array.isArray(state.recent) ? state.recent.filter(item => item && typeof item.path === 'string') : [];
  desk.classList.toggle('compact', readLocal('compact', false));
  setChatWidth(readLocal('chatWidth', 380), false);
  setChat(narrow.matches ? false : readLocal('chatVisible', true));
  state.nav = [
    { title: 'Домашний экран', icon: 'home', open: 'ui/home/index.html' },
    { title: 'Задачи', icon: 'list', open: 'ui/tasks/index.html' },
    { title: 'История', icon: 'history', open: 'ui/history/index.html' },
    { title: 'Настройки', icon: 'settings', open: 'ui/settings/index.html' }
  ];
  renderNav();
  restoreRoute();
  chatFrame.src = '/ui/chat/index.html';
  refreshData();
  ui.icons(document);
  ui.mascot.ready().then(drawMascot);
  // The first time the hub is opened it is shown around.
  ui.tour.offer('desk', [
    { title: 'Рабочее место на компьютере', text: 'Это то же приложение, что на телефоне, — на большом экране. Всё, что вы делаете здесь, происходит на телефоне, и наоборот.' },
    { target: '#create-prompt', title: 'С чего начать', text: 'Нажмите — и откроется чат. Скажите, что сделать или изменить, и приложение изменится.' },
    { target: '#search-open', title: 'Быстрый переход', text: 'Ctrl+K открывает поиск по модулям, разделам и действиям.' },
    { target: '#primary-nav', title: 'Разделы', text: 'Экраны самого приложения: домашний экран, задачи, история изменений, настройки.' },
    { target: '.sidebar-modules', title: 'Модули', text: 'Всё, что появилось в приложении по вашим просьбам или из магазина. Нажатие открывает модуль прямо здесь.' },
    { target: '#studio-open', title: 'Студия', text: 'Файлы приложения, просмотр экранов в размере телефона и журнал. Здесь можно править приложение руками — каждая правка сохраняется в истории.' },
    { target: '#chat-panel', title: 'Чат всегда рядом', text: 'Разговор с ассистентом идёт сбоку от экрана. «Следовать» открывает экраны, которые он проверяет, пока работает.' },
    { target: '#connection', title: 'Связь с телефоном', text: 'Хаб работает, пока приложение на телефоне открыто и оба устройства в одной сети.' },
  ], { askAt: '#chat-panel' });
})();

// The editable store screen. Validation, downloads and installation live in the shell.
(function () {
  'use strict';
  const h = ui.h, tr = (...args) => ui.t(...args), raw = value => ui.raw(String(value == null ? '' : value));
  const $ = id => document.getElementById(id);
  const PORTION = 24;
  const state = { tab: 'catalog', sources: [], items: [], total: 0, counts: { all: 0, updates: 0 }, tags: [], kinds: [], tag: '', sort: 'title', view: 'grid', listing: false, listSerial: 0, installed: [], plans: [], bans: [], query: '', kind: 'all', source: 'all', sourceUrl: '', loaded: false, operations: new Set(), pending: null, reviewTask: null, dialog: null, token: 0, acknowledged: new Set(), poll: null, visible: true, loadSerial: 0 };
  const api = (method, ...args) => assi.native('store.' + method, ...args);
  const listOf = (value, key) => Array.isArray(value) ? value : value && Array.isArray(value[key]) ? value[key] : [];
  const packageId = item => item.packageId || item.id;
  const installationKey = item => item.installationKey || item.key;
  function bytes(value) { const n = Number(value) || 0; return n < 1024 ? tr('{} Б', n) : n < 1048576 ? tr('{} КиБ', Math.round(n / 1024)) : tr('{} МиБ', (n / 1048576).toFixed(1)); }
  function errorText(error) { return error && error.message ? error.message : String(error || tr('Не удалось выполнить действие')); }
  function button(text, fn, options) { return h('button', Object.assign({ class: 'btn small', type: 'button', onclick: fn }, options || {}), text); }
  function note(text) { return h('p', { class: 'store-note' }, text); }
  // What a review found, one item per rule: the level, where it is in the code, what it means and what is proposed.
  // Titles are the shell's own words; explanations are a model's or the installer's and are shown as written.
  function findingItem(finding) {
    if (typeof finding === 'string') return h('li', { class: 'store-finding' }, h('p', {}, raw(finding)));
    const evidence = finding.evidence || {}, place = evidence.path ? evidence.path + (evidence.line ? ':' + evidence.line : '') : '';
    return h('li', { class: 'store-finding', 'data-finding': finding.rule || '', 'data-severity': finding.severity || '' },
      h('div', { class: 'store-finding-head' },
        finding.severity ? h('span', { class: 'store-chip ' + (finding.severity === 'violation' ? 'problem' : 'changed') }, tr(finding.severity === 'violation' ? 'Нарушение' : 'Подозрение')) : null,
        h('strong', {}, finding.title ? tr(finding.title) : raw(finding.rule || finding.code || ''))),
      h('p', {}, raw(finding.explanation || finding.message || '')),
      place ? h('code', { class: 'store-finding-place' }, raw(place)) : null,
      finding.resolution ? h('p', { class: 'store-note' }, tr('Что предлагается:'), ' ', raw(finding.resolution)) : null);
  }
  function findingList(findings) { return h('ul', { class: 'store-findings' }, findings.map(findingItem)); }
  function banNotice(ban, earlier) {
    const violations = Array.isArray(ban.violations) ? ban.violations : [];
    return h('div', { class: 'store-notice danger', 'data-ban': ban.artifactSha256 || '', 'data-ban-earlier': earlier ? 'true' : 'false' },
      h('strong', {}, earlier ? tr('Версия {} этого пакета была забанена', ban.version || '') : tr('Этот выпуск забанен за нарушение правил магазина')),
      violations.length ? findingList(violations) : ban.reason ? h('p', {}, raw(ban.reason)) : null,
      note(earlier ? tr('Новая версия — другой архив: она проверяется заново.') : tr('Подготовить, проверить повторно и установить его нельзя. Снять бан может только владелец в разделе «Источники».')));
  }
  function installReview(review) {
    const findings = Array.isArray(review.findings) ? review.findings : [], unknowns = Array.isArray(review.unknowns) ? review.unknowns : [];
    if (review.ownerDecision) return h('div', { class: 'store-notice', 'data-install-review': 'warnings' },
      h('strong', {}, tr('Установлен с предупреждениями')), note(tr('Проверка нашла подозрительные места, и вы решили установить пакет: {}', ui.ago(Number(review.ownerDecision.at)))),
      findings.length ? findingList(findings) : null, unknowns.length ? note(tr('Не проверено:'), ' ', raw(unknowns.join('; '))) : null);
    return h('p', { class: 'store-note', 'data-install-review': review.verdict || 'none' }, tr(review.verdict === 'pass' ? 'Перед установкой агент проверил пакет по правилам магазина: находок нет.' : 'Установлен без проверки агентом.'));
  }
  // The review's conversation opens in the chat panel over this screen, as any other chat does:
  // the phone slides it up, the web hub shows its own side panel. Closing it returns here.
  async function openReviewChat(task) {
    const agent = task.agent || 'assi';
    if (task.thread) await assi.native('agent.openThread', agent, task.thread);
    await assi.native('ui.chat', true, agent);
  }
  function icon(kind) {
    const paths = kind === 'game' ? '<rect x="3" y="6" width="18" height="12" rx="5"/><path d="M7 12h6m-3-3v6m7-4h.1m2 3h.1"/>' : kind === 'source' ? '<path d="m12 3 9 5v8l-9 5-9-5V8l9-5Zm-9 5 9 5 9-5m-9 5v8"/>' : '<rect x="4" y="4" width="7" height="7" rx="2"/><rect x="14" y="4" width="6" height="7" rx="2"/><rect x="4" y="14" width="7" height="6" rx="2"/><path d="M14 17h7m-3.5-3.5v7"/>';
    // All markup below is a local constant. Repository SVG/HTML is never inserted here.
    return h('span', { class: 'store-icon ' + kind, 'aria-hidden': 'true', html: '<svg viewBox="0 0 24 24">' + paths + '</svg>' });
  }
  function status(text, failed, retry) {
    const root = $('store-status'); root.hidden = !text; root.className = 'store-status' + (failed ? ' error' : '');
    root.replaceChildren(h('span', { class: 'grow' }, raw(text)));
    if (retry) root.append(button(tr('Повторить'), retry));
  }
  async function operation(key, control, fn) {
    if (state.operations.has(key)) return;
    state.operations.add(key); if (control) control.disabled = true;
    try { return await fn(); }
    catch (error) { if (state.dialog) dialogStatus(errorText(error), true); else status(errorText(error), true); }
    finally { state.operations.delete(key); if (control && control.isConnected) control.disabled = false; }
  }
  function listed() { return state.tab === 'catalog' || state.tab === 'updates'; }
  function pickTab(tab, focus) {
    state.tab = tab;
    document.querySelectorAll('.store-tabs [data-tab]').forEach(node => { const selected = node.dataset.tab === tab; node.setAttribute('aria-selected', String(selected)); node.tabIndex = selected ? 0 : -1; });
    $('store-content').setAttribute('aria-labelledby', 'store-tab-' + tab);
    document.querySelector('.store-intro').hidden = tab !== 'catalog';
    $('store-controls').hidden = tab === 'sources';
    if (listed()) relist(); else render();
    if (focus) $('store-tab-' + tab).focus({ preventScroll: true });
  }
  function updateCount(name, value) { document.querySelector('[data-count="' + name + '"]').textContent = value ? String(value) : ''; }
  // A module that stands on the device without the store's record: the catalog cannot tell its version,
  // so it is offered for taking over next to the real updates instead of being called absent.
  function unmanaged(item) { return item.status === 'unmanaged'; }
  function installedFor(item) { return state.installed.find(entry => entry.sourceId === item.sourceId && packageId(entry) === packageId(item)); }
  function sourceName(id) { const source = state.sources.find(item => item.id === id); return source ? source.title || source.repositoryId || id : tr('Источник удалён'); }
  function entryPath(item) { const value = item.entryPath || ''; return /^(?:modules|ui)\/[a-zA-Z0-9_./-]+\.html?$/i.test(value) && !value.split('/').includes('..') ? value : ''; }
  function openInstalled(item) { const path = entryPath(item); if (path) assi.open(path); else status(tr('У пакета нет отдельного экрана.'), false); }
  function empty(title, text, action, run) {
    return h('div', { class: 'store-empty' }, icon('source'), h('h3', {}, title), h('p', {}, text), action ? button(action, run, { class: 'btn primary' }) : null);
  }
  function matches(item) {
    return (state.kind === 'all' || item.kind === state.kind) && (state.source === 'all' || item.sourceId === state.source) &&
      (!state.query || [item.title, item.summary, item.publisher, packageId(item), ...(item.tags || [])].join(' ').toLocaleLowerCase().includes(state.query));
  }
  function filtered() { return !!state.query || state.kind !== 'all' || state.source !== 'all' || !!state.tag; }

  // The catalog is never asked for or drawn whole: the shell cuts it into portions over what it keeps
  // on disk, and the next portion is asked for when the end of the list comes near.
  function listOptions(offset) { return { query: state.query, kind: state.kind, sourceId: state.source, tag: state.tag, only: state.tab === 'updates' ? 'updates' : '', sort: state.sort, offset: offset, limit: PORTION }; }
  async function list(reset) {
    if (!listed()) return;
    if (!reset && (state.listing || state.items.length >= state.total)) return;
    // A newer request (another tab, another search) makes an older answer void.
    const serial = reset ? ++state.listSerial : state.listSerial;
    state.listing = true;
    try {
      const page = await api('catalog', listOptions(reset ? 0 : state.items.length));
      if (serial !== state.listSerial) return;
      const items = listOf(page, 'items');
      state.items = reset ? items : state.items.concat(items);
      state.total = Number(page && page.total) || state.items.length;
      if (page && page.counts) state.counts = page.counts;
      state.tags = listOf(page, 'tags'); state.kinds = listOf(page, 'kinds');
      state.listing = false;
      if (reset) render(); else extend(items);
    } finally { if (serial === state.listSerial) state.listing = false; }
  }
  function relist() { state.items = []; state.total = 0; state.listing = true; render(); return list(true).catch(error => { state.listing = false; render(); status(errorText(error), true, relist); }); }

  // What the catalog can be narrowed by comes from the catalog itself: the kinds and tags its packages
  // carry. Nothing here is a fixed list; a name for a kind is only a nicer word for what the data says.
  const KIND_NAMES = { module: 'Модули', game: 'Игры' };
  function fill(select, first, rows, chosen) {
    select.replaceChildren(h('option', { value: first[0] }, first[1]), ...rows.map(row => h('option', { value: row[0] }, raw(row[1]))));
    select.value = rows.some(row => row[0] === chosen) ? chosen : first[0];
    return select.value;
  }
  function facets() {
    state.source = fill($('store-source'), ['all', tr('Все источники')], state.sources.map(source => [source.id, source.title || source.repositoryId]), state.source);
    const kinds = state.tab === 'installed' ? [...new Set(state.installed.map(item => item.kind))].map(kind => ({ kind: kind })) : state.kinds;
    state.kind = fill($('store-kind'), ['all', tr('Все разделы')], kinds.map(row => [row.kind, (KIND_NAMES[row.kind] ? tr(KIND_NAMES[row.kind]) : row.kind) + (row.count ? ' · ' + row.count : '')]), state.kind);
    const tags = $('store-tag'); tags.hidden = !listed() || !state.tags.length && !state.tag;
    const known = state.tags.map(row => [row.tag, row.tag + ' · ' + row.count]);
    if (state.tag && !known.some(row => row[0] === state.tag)) known.unshift([state.tag, state.tag]);
    state.tag = fill(tags, ['', tr('Все теги')], known, state.tag);
    $('store-sort').hidden = !listed(); $('store-sort').value = state.sort;
    document.querySelectorAll('#store-view [data-view]').forEach(node => node.setAttribute('aria-pressed', String(node.dataset.view === state.view)));
  }

  function render() {
    updateCount('catalog', state.counts.all); updateCount('installed', state.installed.length); updateCount('updates', state.counts.updates); updateCount('sources', state.sources.length);
    facets(); renderPending();
    const content = $('store-content'); content.setAttribute('aria-busy', String(!state.loaded || listed() && state.listing && !state.items.length));
    if (!state.loaded) return;
    if (state.tab === 'sources') { renderSources(content); return; }
    if (listed() && state.listing && !state.items.length) { content.replaceChildren(h('div', { class: 'store-loading' }, tr('Загружаем каталог…'))); return; }
    const items = state.tab === 'installed' ? state.installed.filter(matches) : state.items;
    if (!items.length) {
      if (filtered()) content.replaceChildren(empty(tr('Ничего не найдено'), tr('Попробуйте другое название или измените фильтры.')));
      else if (state.tab === 'updates') content.replaceChildren(empty(tr('Всё актуально'), tr('Когда в подключённых источниках появятся новые версии, они будут здесь.'), tr('Проверить источники'), refreshSources));
      else if (state.tab === 'installed') content.replaceChildren(empty(tr('Пакетов из каталога пока нет'), tr('Ваши существующие модули остаются на месте. Здесь появятся пакеты, установленные через магазин.'), tr('Открыть каталог'), () => pickTab('catalog', true)));
      else content.replaceChildren(empty(tr(state.sources.length ? 'В источниках пока нет пакетов' : 'Подключите первый источник'), tr(state.sources.length ? 'Проверьте, включён ли источник, и обновите его каталог.' : 'Добавьте GitHub-репозиторий или ссылку на index.json. Каталог проверит формат перед подключением.'), tr('Добавить источник'), () => pickTab('sources', true)));
      return;
    }
    content.replaceChildren(h('div', { class: 'store-grid', id: 'store-list', 'data-view': state.view }, items.map(item => packageCard(item, state.tab === 'installed'))));
    more(content);
  }
  // The next portion is appended to what is already on the screen; nothing drawn is drawn again.
  function extend(items) {
    const grid = $('store-list'); if (!grid || !listed()) { render(); return; }
    items.forEach(item => grid.append(packageCard(item, false)));
    more($('store-content'));
  }
  let nearEnd = null;
  function more(content) {
    const old = $('store-more'); if (old) old.remove();
    if (nearEnd) { nearEnd.disconnect(); nearEnd = null; }
    if (!listed() || state.items.length >= state.total) return;
    const next = () => list(false).catch(error => status(errorText(error), true));
    const node = h('div', { class: 'store-more', id: 'store-more' }, h('span', { id: 'store-shown' }, tr('Показано {} из {}', state.items.length, state.total)), button(tr('Показать ещё'), next, { id: 'store-more-button' }));
    content.append(node);
    if (typeof IntersectionObserver === 'function') { nearEnd = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) next(); }, { rootMargin: '800px 0px' }); nearEnd.observe(node); }
  }

  // Pictures of listings come through the shell as data, by the reference of the checked catalog, and only
  // for what is near the screen. One picture is asked for once, however many cards show it.
  const pictures = new Map();
  const nearScreen = typeof IntersectionObserver === 'function' ? new IntersectionObserver(entries => entries.forEach(entry => {
    if (!entry.isIntersecting) return; nearScreen.unobserve(entry.target);
    const job = entry.target.storePicture; entry.target.storePicture = null; if (job) job();
  }), { rootMargin: '400px 0px' }) : null;
  function picture(node, key, fetch, place) {
    node.storePicture = () => {
      let promise = pictures.get(key);
      if (!promise) { promise = fetch(); pictures.set(key, promise); promise.catch(() => pictures.delete(key)); }
      promise.then(url => {
        if (!/^data:image\/(?:png|jpeg|webp|svg\+xml);base64,[A-Za-z0-9+/=]+$/.test(String(url)) || !node.isConnected) return;
        place(h('img', { src: url, alt: '', decoding: 'async' }));
      }).catch(() => {});
    };
    if (nearScreen) nearScreen.observe(node); else node.storePicture();
  }
  function packageIcon(item, installedView) {
    const node = icon(item.kind === 'game' ? 'game' : 'module');
    const reference = installedView ? item.catalogEntry && item.catalogEntry.icon : item.icon;
    if (reference && reference.sha256 && item.sourceId) picture(node, reference.sha256, () => api('media', item.sourceId, packageId(item), 'icon'), img => { node.classList.add('picture'); node.replaceChildren(img); });
    return node;
  }
  function packageCard(item, installedView) {
    const installed = installedView ? item : installedFor(item);
    const shown = installedView && item.catalogEntry ? Object.assign({}, item.catalogEntry, item) : item;
    const title = shown.title || packageId(item), card = h('article', { class: 'store-card', 'data-package': packageId(item), 'data-source': item.sourceId });
    card.append(h('div', { class: 'store-card-top' }, packageIcon(item, installedView), h('div', { class: 'grow' }, h('h3', {}, raw(title)), h('div', { class: 'store-meta' }, h('span', {}, KIND_NAMES[item.kind] ? tr(item.kind === 'game' ? 'Игра' : 'Модуль') : raw(item.kind || '')), h('span', {}, raw(item.version || ''))))));
    card.append(h('p', { class: 'store-description' }, raw(shown.summary || (installedView ? tr('Установлено через каталог Axiko') : ''))));
    const badges = h('div', { class: 'store-meta store-badges' });
    if (item.status === 'update' || item.updateAvailable) badges.append(h('span', { class: 'store-chip update' }, tr('Есть обновление')));
    else if (installed) badges.append(h('span', { class: 'store-chip success' }, tr('Установлено')));
    else if (unmanaged(item)) badges.append(h('span', { class: 'store-chip changed', 'data-unmanaged': '' }, tr('Стоит без магазина')));
    if (item.localModified || installed && installed.localModified) badges.append(h('span', { class: 'store-chip changed' }, tr('Есть свои правки')));
    if (item.ban || item.status === 'banned') badges.append(h('span', { class: 'store-chip problem', 'data-banned': '' }, tr('Забанен')));
    else if (Array.isArray(item.previousBans) && item.previousBans.length) badges.append(h('span', { class: 'store-chip changed', 'data-previous-ban': '' }, tr('Прежняя версия забанена')));
    if (installedView && item.review && item.review.ownerDecision) badges.append(h('span', { class: 'store-chip changed', 'data-installed-warnings': '' }, tr('Установлен с предупреждениями')));
    if (item.status === 'sourceConflict') badges.append(h('span', { class: 'store-chip problem' }, tr('Конфликт источников')));
    else if (item.status === 'changedRelease') badges.append(h('span', { class: 'store-chip problem' }, tr('Выпуск изменён')));
    else if (item.status !== 'banned' && (item.compatible === false || item.status === 'incompatible')) badges.append(h('span', { class: 'store-chip problem' }, tr('Нужна другая версия Axiko')));
    (Array.isArray(shown.tags) ? shown.tags : []).slice(0, 3).forEach(tag => badges.append(h('span', { class: 'store-tag' }, raw('#' + tag))));
    if (item.archive && item.archive.size) badges.append(h('span', { class: 'store-size' }, bytes(item.archive.size)));
    card.append(badges, h('div', { class: 'store-source-line' }, raw(item.sourceTitle || sourceName(item.sourceId))));
    const actions = h('div', { class: 'store-card-actions' }, button(tr('Подробнее'), event => operation('details-' + item.sourceId + ':' + packageId(item), event.currentTarget, () => showPackage(item, installed)), { 'data-details': packageId(item) }));
    if (installedView && entryPath(item)) actions.append(button(tr('Открыть'), () => openInstalled(item), { class: 'btn primary', 'data-open-package': packageId(item) }));
    else if (!installed || item.status === 'update' || item.updateAvailable) actions.append(button(tr(installed ? 'Обновить' : unmanaged(item) ? 'Принять модуль' : 'Установить'), event => prepare(item, event.currentTarget), { class: 'btn primary', disabled: item.compatible === false || item.status === 'incompatible' || item.status === 'banned', 'data-install': packageId(item) }));
    else if (entryPath(installed)) actions.append(button(tr('Открыть'), () => openInstalled(installed), { class: 'btn primary' }));
    card.append(actions); return card;
  }
  async function load(options) {
    const serial = ++state.loadSerial;
    const results = await Promise.allSettled([api('sources'), api('installed'), api('plans'), api('bans')]);
    if (serial !== state.loadSerial) return;
    if (results[0].status === 'fulfilled') state.sources = listOf(results[0].value, 'sources');
    if (results[1].status === 'fulfilled') state.installed = listOf(results[1].value, 'installed');
    if (results[2].status === 'fulfilled') {
      const plans = listOf(results[2].value, 'plans');
      state.plans = plans;
      const live = state.pending && plans.find(plan => plan.id === state.pending.id);
      if (live) state.pending = live;
      else if (!state.pending) state.pending = plans.find(plan => ['ready','conflicts','blocked'].includes(plan.status)) || null;
      if (state.pending && state.pending.reviewTask && state.pending.reviewTask.status === 'running' && state.pending.status !== 'applied') state.reviewTask = Object.assign({ planId: state.pending.id }, state.pending.reviewTask);
    }
    if (results[3].status === 'fulfilled') state.bans = listOf(results[3].value, 'bans');
    let failed = results.find(result => result.status === 'rejected');
    // The counts of the tabs come with a portion of the catalog, so one is asked for whatever tab is open.
    try {
      if (listed()) { state.loaded = true; await list(true); }
      else { const page = await api('catalog', { limit: 1 }); if (page && page.counts) state.counts = page.counts; }
    } catch (error) { failed = failed || { reason: error }; }
    state.loaded = true; state.listing = false; render();
    if (failed) status(errorText(failed.reason), true, () => load());
    else if (!(options && options.keepStatus)) status('');
    schedulePoll();
  }
  async function refreshSources(event) {
    const control = event && event.currentTarget;
    await operation('refresh-sources', control, async () => {
      const enabled = state.sources.filter(source => source.enabled !== false);
      if (!enabled.length) { await load(); return; }
      status(tr('Проверяем обновления в источниках…'));
      const errors = [];
      for (const source of enabled) { try { await api('source.refresh', source.id); } catch (error) { errors.push(errorText(error)); } }
      await load({ keepStatus: true }); status(errors.length ? errors.join('\n') : tr('Каталоги обновлены'), !!errors.length);
    });
  }
  function renderSources(content) {
    const input = h('input', { type: 'url', id: 'store-source-url', value: state.sourceUrl, placeholder: 'https://github.com/author/repository', autocomplete: 'url', 'aria-label': tr('Адрес репозитория или index.json'), required: true, oninput: event => { state.sourceUrl = event.target.value; } });
    const submit = button(tr('Добавить'), null, { class: 'btn primary', type: 'submit', id: 'store-source-add' });
    const form = h('form', { class: 'store-source-form', onsubmit: event => {
      event.preventDefault(); let url = input.value.trim(); if (/^github\.com\//i.test(url)) url = 'https://' + url;
      operation('add-source', submit, async () => {
        if (!/^https:\/\//i.test(url)) throw new Error(tr('Укажите HTTPS-ссылку на репозиторий или index.json.'));
        const source = await api('source.add', url); state.sourceUrl = ''; await load({ keepStatus: true }); status(tr('Источник «{}» добавлен', source.title || source.repositoryId || ''));
      });
    } }, h('h3', {}, tr('Подключить репозиторий')), h('p', {}, tr('В репозитории обязателен index.json по правилам Axiko. Подойдут GitHub и другие HTTPS-источники.')), h('div', { class: 'store-source-inputs' }, input, submit));
    content.replaceChildren(h('div', { class: 'store-sources' }, form, state.sources.map(source => sourceCard(source))));
    if (!state.sources.length) content.firstChild.append(note(tr('Источников пока нет. Установка публичных пакетов не требует GitHub-аккаунта.')));
    if (state.bans.length) content.firstChild.append(h('section', { class: 'store-bans', id: 'store-bans', 'aria-label': tr('Забаненные выпуски') },
      h('h3', {}, tr('Забаненные выпуски')), note(tr('Эти выпуски нарушили правила магазина. Их нельзя подготовить, проверить повторно и установить, пока вы сами не снимете бан.')),
      state.bans.map(ban => banCard(ban))));
  }
  function banCard(ban) {
    const sha = ban.artifactSha256;
    return h('article', { class: 'store-ban', 'data-ban-card': sha },
      h('div', { class: 'grow' }, h('h4', {}, raw((ban.title || ban.packageId || '') + ' · ' + (ban.version || ''))),
        h('div', { class: 'store-meta' }, h('span', {}, raw(ban.sourceTitle || '')), h('span', {}, tr(ban.origin === 'validator' ? 'Отклонён установщиком' : 'Отклонён проверкой агента')), ban.bannedAt ? h('span', {}, ui.ago(Number(ban.bannedAt))) : null),
        findingList(Array.isArray(ban.violations) ? ban.violations : [])),
      h('div', { class: 'store-source-actions' }, button(tr('Снять бан'), event => operation('unban-' + sha, event.currentTarget, async () => {
        status(tr('Подтвердите решение в окне Axiko на экране телефона.'));
        const result = await api('unban', sha); await load({ keepStatus: true });
        status(result && result.status === 'lifted' ? tr('Бан снят. Выпуск можно подготовить и проверить заново.') : tr('Бан не снят: решение не подтверждено.'));
      }), { 'data-unban': sha })));
  }
  function sourceCard(source) {
    const address = source.indexUrl || source.url || '';
    const details = h('div', { class: 'grow' }, h('h3', {}, raw(source.title || source.repositoryId || source.id)), h('p', { class: 'store-source-address' }, raw(address)),
      h('div', { class: 'store-meta' }, h('span', {}, tr(source.enabled === false ? 'Выключен' : 'Подключён')), h('span', {}, tr('Пакетов: {}', source.packageCount || 0)), h('span', {}, source.lastSuccess ? tr('Обновлено: {}', ui.ago(Number(source.lastSuccess))) : tr('Ещё не обновлялся'))));
    if (source.error) details.append(h('p', { class: 'store-source-error' }, raw(source.error)), note(tr('Последний полученный каталог сохранён.')));
    if (Array.isArray(source.problems) && source.problems.length) details.append(h('div', { class: 'store-notice', 'data-source-problems': source.id }, h('strong', {}, tr('Замечания к источнику')),
      h('ul', { class: 'store-plain-list' }, source.problems.map(problem => h('li', {}, raw((problem.packageId || '') + ' ' + (problem.version || '') + ': ' + (problem.message || ''))))),
      note(tr('Архив или манифест не совпали с каталогом источника. Пакет за это не банится: обновите источник или сообщите его автору.'))));
    const actions = h('div', { class: 'store-source-actions' },
      button(tr('Обновить'), event => operation('source-' + source.id, event.currentTarget, async () => { try { await api('source.refresh', source.id); } finally { await load({ keepStatus: true }); } }), { 'data-refresh-source': source.id }),
      button(tr(source.enabled === false ? 'Включить' : 'Выключить'), event => operation('source-' + source.id, event.currentTarget, async () => { await api('source.enable', source.id, source.enabled === false); await load(); }), { 'data-enable-source': source.id }),
      button(tr('Удалить источник'), event => operation('source-' + source.id, event.currentTarget, async () => { await api('source.remove', source.id); await load({ keepStatus: true }); status(tr('Источник удалён. Установленные пакеты и данные сохранены.')); }), { 'data-remove-source': source.id }));
    return h('article', { class: 'store-source' + (source.enabled === false ? ' disabled' : ''), 'data-source-card': source.id }, details, actions);
  }
  function closeDialog() {
    const dialog = state.dialog; if (!dialog || dialog.locked) return;
    document.removeEventListener('keydown', dialog.keydown, true); dialog.backdrop.remove(); state.dialog = null; state.token++;
    if (dialog.origin && dialog.origin.isConnected) dialog.origin.focus({ preventScroll: true }); else $('store-tab-' + state.tab).focus({ preventScroll: true });
  }
  function dialog(title) {
    closeDialog(); const token = ++state.token, origin = document.activeElement;
    const heading = h('h2', { id: 'store-dialog-title' }, raw(title));
    const close = button(tr('Закрыть'), closeDialog, { id: 'store-dialog-close' });
    const header = h('div', { class: 'store-dialog-header' }, heading, close);
    const body = h('div', { class: 'store-dialog-body' }), footer = h('div', { class: 'store-dialog-footer' });
    const root = h('section', { class: 'store-dialog', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'store-dialog-title' }, header, body, footer);
    const backdrop = h('div', { class: 'store-dialog-backdrop', onclick: event => { if (event.target === backdrop) closeDialog(); } }, root);
    const keydown = event => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); closeDialog(); return; }
      if (event.key !== 'Tab') return;
      const targets = [...root.querySelectorAll('button:not([disabled]),input:not([disabled]),select,a[href],summary,[tabindex="0"]')].filter(node => node.getClientRects().length && !node.closest('[hidden]') && ![...root.querySelectorAll('details:not([open])')].some(d => d.contains(node) && !d.querySelector('summary').contains(node)));
      if (!targets.length) return;
      const first = targets[0], last = targets[targets.length - 1];
      if (event.shiftKey && (document.activeElement === first || !root.contains(document.activeElement))) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && (document.activeElement === last || !root.contains(document.activeElement))) { event.preventDefault(); first.focus(); }
    };
    document.body.append(backdrop); document.addEventListener('keydown', keydown, true);
    state.dialog = { token, origin, backdrop, root, body, footer, header, heading, close, keydown, locked: false };
    close.focus({ preventScroll: true }); return state.dialog;
  }
  function dialogStatus(text, failed) {
    const current = state.dialog; if (!current) return status(text, failed);
    let node = current.body.querySelector('#store-dialog-status');
    if (!node) { node = h('div', { id: 'store-dialog-status', role: 'status', 'aria-live': 'polite' }); current.body.prepend(node); }
    node.className = 'store-notice' + (failed ? ' danger' : ''); node.replaceChildren(raw(text));
  }
  function facts(rows) { return h('dl', { class: 'store-details' }, rows.filter(row => row[1] !== undefined && row[1] !== '').map(([label,value]) => h('div', {}, h('dt', {}, tr(label)), h('dd', {}, raw(value))))); }
  function safeHttps(value) { try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password && !url.hash ? url.href : null; } catch (_) { return null; } }
  // Text from a repository is the author's, not ours: it goes through the kit's Markdown, which escapes
  // everything first, and its links open outside, without telling the site where the reader came from.
  function authorText(source) {
    const node = h('div', { class: 'store-text', html: ui.md(String(source || '')) });
    node.querySelectorAll('a').forEach(link => { if (safeHttps(link.getAttribute('href'))) { link.target = '_blank'; link.rel = 'noopener noreferrer'; link.referrerPolicy = 'no-referrer'; } else link.replaceWith(document.createTextNode(link.textContent)); });
    return node;
  }
  // What is read when a package is opened: fetched then, not with the list.
  async function about(current, box, item) {
    const token = current.token;
    try {
      const details = await api('details', item.sourceId, packageId(item));
      if (!state.dialog || state.dialog.token !== token) return;
      const shots = listOf(details, 'screenshots');
      if (shots.length) box.append(h('div', { class: 'store-shots', role: 'list', 'aria-label': tr('Снимки экрана') }, shots.map((shot, index) => {
        const frame = h('figure', { class: 'store-shot', role: 'listitem', 'data-shot': String(index), style: 'aspect-ratio:' + (Number(shot.width) || 9) + '/' + (Number(shot.height) || 16) }, shot.caption ? h('figcaption', {}, raw(shot.caption)) : null);
        picture(frame, shot.sha256, () => api('media', item.sourceId, packageId(item), 'screenshot', index), img => { img.alt = shot.caption || ''; frame.prepend(img); });
        return frame;
      })));
      if (details.description) box.append(authorText(details.description));
      const changes = listOf(details, 'changes');
      if (changes.length) box.append(h('details', { class: 'store-changes', open: true }, h('summary', {}, tr('Что нового')),
        changes.map(change => h('div', { class: 'store-change', 'data-change': change.version }, h('strong', {}, raw(change.version + (change.date ? ' · ' + change.date : ''))), authorText(change.notes)))));
      const links = Object.entries(details.links || {}).map(([kind, url]) => [({ homepage: 'Сайт', source: 'Исходники', support: 'Поддержка' })[kind], safeHttps(url)]).filter(row => row[0] && row[1]);
      if (links.length) box.append(h('div', { class: 'store-links' }, links.map(([label, url]) => h('a', { href: url, target: '_blank', rel: 'noopener noreferrer', referrerpolicy: 'no-referrer' }, tr(label)))));
    } catch (error) { if (state.dialog && state.dialog.token === token) box.append(note(tr('Описание не загрузилось: {}', errorText(error)))); }
    finally { box.setAttribute('aria-busy', 'false'); }
  }
  async function showPackage(item, installed) {
    let display = item;
    // An installed record is opened with what the catalog offers for it now, if its source still lists it.
    if (!item.status && item.sourceId) {
      const page = await api('catalog', { query: packageId(item), sourceId: item.sourceId, limit: 50 }).catch(() => null);
      display = listOf(page, 'items').find(entry => packageId(entry) === packageId(item)) || (item.catalogEntry ? Object.assign({}, item.catalogEntry, item) : item);
    }
    const current = dialog(display.title || packageId(item));
    const banned = display.status === 'banned' || !!display.ban && !installed;
    const compatible = !banned && display.compatible !== false && display.status !== 'incompatible';
    current.body.append(h('p', {}, raw(display.summary || '')), facts([
      ['Тип', KIND_NAMES[display.kind] ? tr(display.kind === 'game' ? 'Игра' : 'Модуль') : display.kind], ['Версия', display.version], ['Установлена', installed && installed.version], ['Выпуск от', display.updated], ['Автор', display.publisher], ['Лицензия', display.license], ['Источник', display.sourceTitle || sourceName(item.sourceId)], ['Загрузка', display.archive && bytes(display.archive.size)], ['После распаковки', display.archive && bytes(display.archive.unpackedSize)]
    ]));
    if (Array.isArray(display.tags) && display.tags.length) current.body.append(h('div', { class: 'store-meta', 'data-tags': '' }, display.tags.map(tag => h('span', { class: 'store-tag' }, raw('#' + tag)))));
    if (display.compatibility) current.body.append(note(tr('API Axiko: от {}', display.compatibility.minAssiApi) + (display.compatibility.gamesEngine ? ' · ' + tr('Движок игр: {}', display.compatibility.gamesEngine) : '')));
    if (display.ban) current.body.append(banNotice(display.ban));
    else if (!compatible) current.body.append(h('p', { class: 'store-notice danger' }, raw(display.compatibilityError || tr('Пакет несовместим с установленной версией Axiko.'))));
    (Array.isArray(display.previousBans) ? display.previousBans : []).forEach(ban => current.body.append(banNotice(ban, true)));
    if (installed && installed.review) current.body.append(installReview(installed.review));
    if (installed && installed.localModified) current.body.append(h('p', { class: 'store-notice' }, tr('В пакете есть ваши доработки. Перед обновлением покажем изменения и возможные конфликты.')));
    if (!installed && unmanaged(display)) current.body.append(h('p', { class: 'store-notice', 'data-unmanaged-note': '' }, tr('Этот модуль уже стоит на устройстве, но установлен не через магазин. Каталог не знает его версии и не обновляет его. Примите его под управление магазина: файлы, совпавшие с выпуском, останутся как есть, по остальным решите вы.')));
    if (display.status && display.details) { const box = h('div', { class: 'store-about', 'aria-busy': 'true' }); current.body.append(box); about(current, box, display); }
    current.body.append(note(tr('Перед установкой загрузим конкретную версию, проверим структуру и хеши. Код пакета на этом шаге не запускается.')));
    const controls = h('div', { class: 'store-dialog-actions' });
    if (compatible && display.status && (!installed || display.status === 'update' || display.updateAvailable)) controls.append(button(tr(installed ? 'Подготовить обновление' : unmanaged(display) ? 'Принять под управление магазина' : 'Подготовить установку'), event => prepare(display, event.currentTarget), { class: 'btn primary', id: 'store-prepare' }));
    if (installed && entryPath(installed)) controls.append(button(tr('Открыть пакет'), () => { closeDialog(); openInstalled(installed); }));
    if (installed && installed.rollbackAvailable) controls.append(button(tr('Вернуть предыдущую версию'), event => operation('prepare-rollback', event.currentTarget, async () => {
      const plan = await api('rollback', installationKey(installed)); state.pending = plan; showPlan(plan); renderPending();
    }), { id: 'store-rollback' }));
    current.footer.append(controls);
  }
  async function prepare(item, control) {
    await operation('prepare-' + item.sourceId + ':' + packageId(item), control, async () => {
      const current = state.dialog || dialog(tr('Подготовка пакета'));
      dialogStatus(tr('Загружаем и проверяем пакет. Это может занять немного времени.'));
      const token = current.token;
      // Taking over a module that already stands there is asked for in so many words; a plain install never touches it.
      const plan = await (unmanaged(item) ? api('prepare', item.sourceId, packageId(item), true) : api('prepare', item.sourceId, packageId(item))); state.pending = plan; renderPending();
      // An archive that was already reviewed brings its warnings along: a new plan is not a clean slate.
      const report = await api('review', plan.id).catch(() => null);
      if (state.dialog && state.dialog.token === token) showPlan(plan, report || undefined); else status(tr('Пакет подготовлен. Можно продолжить установку.'));
    });
  }
  function renderPending() {
    const node = $('store-pending'), plan = state.pending && state.pending.status !== 'applied' ? state.pending : state.plans.find(item => ['ready','conflicts','blocked'].includes(item.status));
    node.hidden = !plan || plan.status === 'applied'; if (node.hidden) { node.replaceChildren(); return; }
    node.replaceChildren(h('div', { class: 'store-pending' }, h('div', {}, h('strong', {}, tr(state.reviewTask && state.reviewTask.planId === plan.id ? 'Агент проверяет пакет' : 'Подготовленная установка')), h('p', {}, raw((plan.title || plan.manifest && plan.manifest.id || plan.packageId || '') + ' · ' + (plan.version || '')))),
      button(tr('Продолжить'), event => operation('resume-plan', event.currentTarget, async () => { const [fresh, report] = await Promise.all([api('plan', plan.id), api('review', plan.id)]); showPlan(fresh, report); }), { id: 'store-resume-plan' })));
  }
  function readableStatus(value) { return tr(({ pass: 'Проверка завершена: находок нет', needsAttention: 'Найдены подозрения: решение за вами', reject: 'Нарушение правил: выпуск забанен', running: 'Агент проверяет пакет', pending: 'Проверка ещё не завершена', applied: 'Установка завершена' })[value] || value || 'Проверка ещё не завершена'); }
  function reviewCard(report, task, brief) {
    const stopped = report.agentStatus === 'stopped' || task && task.status === 'stopped';
    const waiting = ['pending','running'].includes(report.status), earlier = waiting && report.inherited && report.inherited.status === 'needsAttention' ? report.inherited : null;
    const shown = earlier || report;
    const card = h('section', { class: 'store-review ' + (shown.status || ''), 'data-review-status': report.status || 'pending', 'data-review-inherited': earlier ? 'true' : 'false' },
      h('strong', {}, earlier ? tr('Прежняя проверка этого выпуска нашла подозрения') : stopped && waiting ? tr('Проверка остановлена') : readableStatus(report.status)));
    if (stopped && waiting && !earlier) card.append(note(tr('Можно открыть диалог или запустить проверку заново. Пакет пока не установлен.')));
    if (shown.summary) card.append(note(raw(shown.summary)));
    const findings = Array.isArray(shown.findings) ? shown.findings : [];
    if (findings.length && !brief) card.append(findingList(findings));
    if (Array.isArray(shown.unknowns) && shown.unknowns.length) card.append(h('div', { class: 'store-unknowns' }, h('strong', {}, tr('Не проверено')), h('ul', { class: 'store-plain-list' }, shown.unknowns.map(text => h('li', {}, raw(text))))));
    if (shown.ownerDecision) card.append(note(tr('Вы решили установить этот выпуск, несмотря на предупреждения: {}', ui.ago(Number(shown.ownerDecision.at)))));
    if (task && task.thread) card.append(button(tr('Открыть диалог с агентом'), () => operation('open-review', null, () => openReviewChat(task)), { id: 'store-open-review' }));
    return card;
  }
  function showPlan(plan, report) {
    const previous = state.dialog, origin = previous && previous.origin;
    const adoption = !!plan.adoption;
    const current = dialog(plan.status === 'applied' ? tr('Готово') : tr(plan.rollback || plan.operation === 'rollback' ? 'Возврат версии' : adoption ? 'Принятие под управление магазина' : 'Проверка перед установкой'));
    if (origin) current.origin = origin; current.planId = plan.id;
    state.pending = plan; renderPending();
    const manifest = plan.manifest || {}, conflicts = Array.isArray(plan.conflicts) ? plan.conflicts.filter(c => !c.resolved) : [];
    const blockers = Array.isArray(plan.blockers) ? plan.blockers : [];
    const blocked = plan.status === 'blocked' || blockers.length > 0;
    const changes = Array.isArray(plan.changes) ? plan.changes : [];
    const about = facts([['Пакет', plan.title || manifest.id || plan.packageId], ['Версия', plan.version || manifest.version], ['Автор', manifest.publisher], ['Лицензия', manifest.license]]);
    current.body.append(about);
    if (plan.status === 'applied') {
      current.body.append(h('p', { class: 'store-notice' }, tr(adoption ? 'Модуль принят под управление магазина: теперь каталог знает его версию и покажет обновления. Сохранения, настройки и оставленные вами файлы на месте.' : 'Пакет установлен. Сохранения, настройки и сохранённые локальные правки остались на месте.')));
      const completedReport = report || plan.review;
      if (completedReport && completedReport.completed && completedReport.historicalOnly) current.body.append(reviewCard(completedReport, plan.reviewTask || completedReport));
      const installed = state.installed.find(item => installationKey(item) === plan.installationKey);
      current.footer.append(entryPath(installed || plan) ? button(tr('Открыть пакет'), () => { closeDialog(); openInstalled(installed || plan); }, { class: 'btn primary' }) : button(tr('Готово'), closeDialog, { class: 'btn primary' })); return;
    }
    const task = plan.reviewTask || (state.reviewTask && state.reviewTask.planId === plan.id ? state.reviewTask : null);
    const currentReport = report || plan.review;
    const discard = (text, options) => button(text, event => operation('discard-' + plan.id, event.currentTarget, async () => {
      await api('discard', plan.id); state.pending = null; state.reviewTask = null; closeDialog(); await load({ keepStatus: true });
      status(tr('Пакет не установлен. Подготовленный план убран.'));
    }), options);
    if (plan.status === 'banned' || plan.ban) {
      // A banned release has one way out of this dialog: away. Lifting the ban is in «Источники».
      current.root.setAttribute('data-plan-banned', '');
      if (currentReport && currentReport.status === 'reject') current.body.append(reviewCard(currentReport, task, true));
      current.body.append(banNotice(plan.ban || {}));
      current.footer.append(h('div', { class: 'store-dialog-actions' }, discard(tr('Убрать подготовленный план'), { class: 'btn primary', id: 'store-discard' })));
      return;
    }
    (Array.isArray(plan.previousBans) ? plan.previousBans : []).forEach(ban => current.body.append(banNotice(ban, true)));
    current.body.append(note(adoption ? tr('Модуль уже стоит на устройстве. Файлов, совпавших с выпуском {}: {}. Личные данные и сохранения не заменяются.', plan.version || manifest.version || '', Number(plan.matchingFiles) || 0) : tr('Обновляется код пакета. Личные данные и сохранения не заменяются.')));
    if (blocked) current.body.append(h('div', { class: 'store-notice danger', 'data-plan-blocked': '' }, h('strong', {}, tr('Сначала нужно устранить препятствия')), h('ul', { class: 'store-plain-list' }, blockers.length ? blockers.map(reason => h('li', {}, raw(typeof reason === 'string' ? reason : reason.message || reason.reason || JSON.stringify(reason)))) : h('li', {}, tr('Подготовьте пакет заново после обновления источника.')))));
    if (Array.isArray(plan.dependencies) && plan.dependencies.length) current.body.append(h('details', { open: blocked }, h('summary', {}, tr('Зависимости: {}', plan.dependencies.length)), h('ul', { class: 'store-plain-list' }, plan.dependencies.map(item => h('li', {}, raw(item.id + ' ' + (item.version || '')), ' · ', tr(item.satisfied ? 'Установлено' : item.optional ? 'Необязательная связь' : 'Не установлено'))))));
    if (plan.rollback || plan.operation === 'rollback') current.body.append(h('p', { class: 'store-notice' }, tr('Возвращается предыдущий код. Данные сохраняются; старый код может не поддерживать более новый формат сохранения.')));
    if (conflicts.length) {
      // One file: the owner's copy stays, or the release's own file takes its place. Both go through the
      // shell, which checks the hash the choice was made about; a snapshot is taken before anything is replaced.
      const settle = (conflict, incoming) => incoming
        ? api('resolve', plan.id, conflict.path, conflict.localSha || conflict.localHash || 'missing', null, false, true)
        : api('resolve', plan.id, conflict.path, conflict.localSha || conflict.localHash || 'missing', null, true);
      const one = (conflict, incoming) => event => operation('resolve-' + plan.id, event.currentTarget, async () => { const next = await settle(conflict, incoming); state.pending = next; showPlan(next); });
      const every = incoming => event => operation('resolve-' + plan.id, event.currentTarget, async () => {
        let next = plan;
        try { for (const conflict of conflicts) next = await settle(conflict, incoming); }
        finally { state.pending = next; showPlan(next); }
      });
      current.body.append(h('p', { class: 'store-notice' }, tr(adoption
        ? 'Эти файлы у вас отличаются от выпуска магазина, и неизвестно, чья версия новее. Выберите для каждого, что оставить. Ничего не заменено; перед заменой будет сделан снимок.'
        : 'Есть конфликт с вашими доработками. Сохраните свою версию файла, возьмите версию магазина или поручите агенту разобраться. Ничего не заменено.')));
      if (conflicts.length > 1) current.body.append(h('div', { class: 'store-dialog-actions', 'data-resolve-all': '' },
        button(tr('Взять все из магазина'), every(true), { 'data-take-all': '' }), button(tr('Оставить все мои'), every(false), { 'data-keep-all': '' })));
      current.body.append(h('div', { class: 'store-plan-list' }, conflicts.map(conflict => h('div', { class: 'store-plan-file store-plan-conflict', 'data-conflict': conflict.path },
        h('div', { class: 'grow' }, h('code', {}, raw(conflict.path)), note(tr(adoption ? 'Отличается от выпуска магазина' : 'Изменён и у вас, и в новой версии'))),
        h('div', { class: 'store-plan-choice' },
          button(tr(adoption ? 'Оставить мою' : 'Сохранить мою версию'), one(conflict, false), { 'data-keep-local': conflict.path }),
          button(tr('Взять из магазина'), one(conflict, true), { 'data-take-incoming': conflict.path }))))));
    }
    const preserved = changes.filter(change => change.localModified || /preserv|keep|local/i.test(change.kind || ''));
    if (preserved.length && !adoption) current.body.append(note(tr('Локальных правок будет сохранено: {}', preserved.length)));
    const extras = adoption ? changes.filter(change => change.kind === 'keep-local') : [];
    if (extras.length) current.body.append(note(tr('Ваших файлов, которых нет в выпуске, останется: {}', extras.length)));
    const fileList = h('details', {}, h('summary', {}, tr('Изменения файлов: {}', changes.length)), h('ul', { class: 'store-plain-list' }, changes.map(change => h('li', {}, h('code', {}, raw(change.path)), ' · ', raw(change.kind || change.action || '')))));
    current.body.append(fileList);
    const requests = (manifest.capabilities || []).concat((manifest.networkDomains || []).map(domain => 'https://' + domain));
    if (requests.length) current.body.append(h('details', {}, h('summary', {}, tr('Заявленные возможности и сеть: {}', requests.length)), h('ul', { class: 'store-plain-list' }, requests.map(item => h('li', {}, raw(item))))));
    current.body.append(h('p', { class: 'store-notice' }, tr('Этот пакет выполняется с возможностями Axiko. Изоляция доступа модулей пока не включена. Проверка агентом помогает изучить код, но не создаёт такую изоляцию.')));
    const agentRunning = !!task && task.status !== 'stopped' && (!currentReport || currentReport.agentStatus !== 'stopped');
    // Suspicions leave the decision to the owner: the report itself, or the last one made about this same archive.
    const verdict = currentReport && currentReport.status === 'pending' && currentReport.inherited ? currentReport.inherited : currentReport;
    const warned = !!verdict && verdict.status === 'needsAttention';
    if (currentReport && (currentReport.status !== 'pending' || currentReport.inherited || task) || task) {
      const card = reviewCard(currentReport || { status: 'running' }, task);
      // What the owner has to decide about is read first, before the lists of files.
      if (warned) about.after(card); else current.body.append(card);
    }
    const ackKey = plan.id + ':' + plan.digest;
    const ack = h('input', { type: 'checkbox', id: 'store-trust-ack', checked: state.acknowledged.has(ackKey) });
    const direct = button(tr(plan.rollback || plan.operation === 'rollback' ? 'Вернуть версию' : adoption ? 'Принять модуль' : 'Установить'), event => operation('apply-' + plan.id, event.currentTarget, async () => {
      if (!state.acknowledged.has(ackKey) || conflicts.length || blocked || agentRunning) return;
      current.locked = true; current.close.disabled = true; dialogStatus(tr('Устанавливаем проверенный пакет…'));
      try {
        const result = await api('apply', plan.id, plan.digest, true);
        state.pending = Object.assign({}, plan, result, { id: plan.id, status: 'applied' }); state.reviewTask = null;
        current.locked = false; current.close.disabled = false; await load({ keepStatus: true }); showPlan(state.pending); status(tr('Установка завершена'));
      } finally { current.locked = false; current.close.disabled = false; }
    }), { class: 'btn primary', id: 'store-apply', disabled: blocked || agentRunning || !ack.checked || conflicts.length > 0 || currentReport && currentReport.status === 'reject' });
    const agent = button(tr(warned ? 'Проверить заново с агентом' : 'Проверить и установить с агентом'), event => operation('review-' + plan.id, event.currentTarget, async () => {
      if (!state.acknowledged.has(ackKey) || blocked) return;
      const started = await api('reviewStart', plan.id, true); state.reviewTask = Object.assign({ planId: plan.id }, started); showPlan(plan, { status: 'running' }); schedulePoll();
      // The conversation is shown at once, over this dialog; the card under it keeps the status.
      try { await openReviewChat(started); } catch (error) { dialogStatus(tr('Проверка идёт, но чат открыть не удалось: {}', errorText(error)), true); }
    }), { id: 'store-review-agent', disabled: blocked || !ack.checked || agentRunning });
    ack.addEventListener('change', () => {
      if (ack.checked) state.acknowledged.add(ackKey); else state.acknowledged.delete(ackKey);
      direct.disabled = blocked || agentRunning || !ack.checked || conflicts.length > 0 || currentReport && currentReport.status === 'reject'; agent.disabled = blocked || !ack.checked || agentRunning;
    });
    const trust = h('label', { class: 'store-trust' }, ack, h('span', {}, tr('Разрешаю запуск этого пакета с возможностями Axiko'), h('small', {}, tr('Для этой версии и показанного плана установки.'))));
    if (!warned) { current.footer.append(trust, h('div', { class: 'store-dialog-actions' }, direct, agent)); return; }
    // The agent does not install a release it has doubts about, and no page or model can say yes for the owner:
    // the shell asks in its own dialog, bound to this plan and digest.
    const accept = button(tr('Установить, несмотря на предупреждения'), event => operation('accept-' + plan.id, event.currentTarget, async () => {
      if (conflicts.length || blocked || agentRunning) return;
      current.locked = true; current.close.disabled = true; dialogStatus(tr('Подтвердите решение в окне Axiko на экране телефона.'));
      try {
        const result = await api('acceptWarnings', plan.id, plan.digest);
        if (!result || result.ok === false || result.status === 'cancelled') { dialogStatus(tr('Пакет не установлен: решение не подтверждено.')); return; }
        state.reviewTask = null; current.locked = false; current.close.disabled = false;
        await load({ keepStatus: true });
        const [fresh, done] = await Promise.all([api('plan', plan.id), api('review', plan.id)]);
        showPlan(fresh, done); status(tr('Пакет установлен с предупреждениями'));
      } finally { current.locked = false; current.close.disabled = false; }
    }), { class: 'btn danger', id: 'store-accept-warnings', disabled: blocked || agentRunning || conflicts.length > 0 });
    current.footer.append(h('p', { class: 'store-note', id: 'store-warned-note' }, tr('Агент не устанавливает пакет с подозрениями. Решение за вами.')),
      h('div', { class: 'store-dialog-actions' }, discard(tr('Не устанавливать'), { class: 'btn primary', id: 'store-decline' }), accept),
      h('details', { class: 'store-recheck' }, h('summary', {}, tr('Проверить ещё раз')), trust, h('div', { class: 'store-dialog-actions' }, agent)));
  }
  function visible() { return state.visible && !document.hidden; }
  function schedulePoll() {
    if (state.poll) clearTimeout(state.poll); state.poll = null;
    if (!visible() || !state.reviewTask) return;
    state.poll = setTimeout(pollReview, 5000);
  }
  async function pollReview() {
    state.poll = null; if (!visible() || !state.reviewTask) return;
    const task = state.reviewTask;
    try {
      const [plan, report] = await Promise.all([api('plan', task.planId), api('review', task.planId)]);
      if (!state.reviewTask || state.reviewTask.planId !== task.planId) return;
      state.pending = plan;
      if (plan.status === 'applied') { state.reviewTask = null; await load({ keepStatus: true }); status(tr('Агент проверил и установил пакет')); }
      else if (['reject','needsAttention'].includes(report.status) || report.agentStatus === 'stopped') state.reviewTask = null;
      if (plan.status === 'banned' && !state.bans.some(ban => ban.artifactSha256 === plan.artifactSha256)) await load({ keepStatus: true });
      if (state.dialog && state.dialog.planId === task.planId) {
        // Poll results only replace the dialog when report or plan materially changed.
        const signature = JSON.stringify([plan.digest,plan.status,report]);
        if (state.dialog.reviewSignature !== signature) { showPlan(Object.assign({}, plan, { reviewTask: plan.reviewTask || Object.assign({}, task, { status: report.agentStatus || task.status }) }), report); if (state.dialog) state.dialog.reviewSignature = signature; }
      }
      renderPending();
    } catch (error) { status(tr('Не удалось получить состояние проверки: {}', errorText(error)), true); }
    schedulePoll();
  }
  document.querySelectorAll('.store-tabs [data-tab]').forEach(node => node.addEventListener('click', () => pickTab(node.dataset.tab)));
  document.querySelector('.store-tabs').addEventListener('keydown', event => {
    if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
    const buttons = [...document.querySelectorAll('.store-tabs [data-tab]')], index = buttons.indexOf(event.target); if (index < 0) return;
    event.preventDefault(); const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : buttons.length - 1)) % buttons.length;
    pickTab(buttons[next].dataset.tab, true); buttons[next].scrollIntoView({ block: 'nearest', inline: 'nearest' });
  });
  const again = () => { if (listed()) relist(); else render(); };
  const remember = (key, value) => { Promise.resolve(assi.native('pref.set', key, value)).catch(() => {}); };
  let typing = null;
  $('store-search').addEventListener('input', event => { state.query = event.target.value.trim().toLocaleLowerCase(); if (typing) clearTimeout(typing); typing = setTimeout(again, listed() ? 250 : 0); });
  $('store-kind').addEventListener('change', event => { state.kind = event.target.value; again(); });
  $('store-source').addEventListener('change', event => { state.source = event.target.value; again(); });
  $('store-tag').addEventListener('change', event => { state.tag = event.target.value; again(); });
  $('store-sort').addEventListener('change', event => { state.sort = event.target.value; remember('store.sort', state.sort); again(); });
  document.querySelectorAll('#store-view [data-view]').forEach(node => node.addEventListener('click', () => {
    state.view = node.dataset.view; remember('store.view', state.view);
    const grid = $('store-list'); if (grid) grid.dataset.view = state.view; facets();
  }));
  $('store-reload').addEventListener('click', refreshSources); $('store-back').addEventListener('click', () => assi.back());
  document.addEventListener('visibilitychange', schedulePoll);
  window.addEventListener('pagehide', () => { if (state.poll) clearTimeout(state.poll); state.poll = null; });
  if (assi.on) {
    assi.on('app.visible', event => { state.visible = !event || event.on !== false; schedulePoll(); });
    assi.on('store.changed', () => { if (visible()) load({ keepStatus: true }).catch(error => status(errorText(error), true)); });
  }
  // How the list looks is the owner's habit, kept between visits.
  Promise.all([assi.native('pref.get', 'store.view', 'grid'), assi.native('pref.get', 'store.sort', 'title')]).then(([view, sort]) => {
    if (['grid', 'list', 'compact'].includes(view)) state.view = view;
    if (['title', 'updated'].includes(sort)) state.sort = sort;
  }).catch(() => {}).then(() => load()).catch(error => { state.loaded = true; state.listing = false; render(); status(errorText(error), true, () => load()); }).then(() => ui.tour && ui.tour.offer('store', [
    { target: '#store-tab-catalog', title: 'Каталог', text: 'Готовые модули и игры из подключённых источников. Установка идёт через план: сначала видно, какие файлы изменятся, и только потом что-то меняется.' },
    { target: '#store-tab-sources', title: 'Источники', text: 'Откуда берётся каталог. Источник — это репозиторий с модулями; добавляйте только те, авторам которых доверяете: пакеты работают с возможностями всего приложения.' },
    { target: '#store-tab-updates', title: 'Обновления и откат', text: 'Новые версии установленного. Ваши данные и ваши правки сохраняются, а прежнюю версию можно вернуть.' },
  ]));
})();

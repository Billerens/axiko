// The editable store screen. Validation, downloads and installation live in the shell.
(function () {
  'use strict';
  const h = ui.h, tr = (...args) => ui.t(...args), raw = value => ui.raw(String(value == null ? '' : value));
  const $ = id => document.getElementById(id);
  const state = { tab: 'catalog', sources: [], catalog: [], installed: [], plans: [], query: '', kind: 'all', source: 'all', sourceUrl: '', loaded: false, operations: new Set(), pending: null, reviewTask: null, dialog: null, token: 0, acknowledged: new Set(), poll: null, visible: true, loadSerial: 0 };
  const api = (method, ...args) => assi.native('store.' + method, ...args);
  const listOf = (value, key) => Array.isArray(value) ? value : value && Array.isArray(value[key]) ? value[key] : [];
  const packageId = item => item.packageId || item.id;
  const installationKey = item => item.installationKey || item.key;
  function bytes(value) { const n = Number(value) || 0; return n < 1024 ? tr('{} Б', n) : n < 1048576 ? tr('{} КиБ', Math.round(n / 1024)) : tr('{} МиБ', (n / 1048576).toFixed(1)); }
  function errorText(error) { return error && error.message ? error.message : String(error || tr('Не удалось выполнить действие')); }
  function button(text, fn, options) { return h('button', Object.assign({ class: 'btn small', type: 'button', onclick: fn }, options || {}), text); }
  function note(text) { return h('p', { class: 'store-note' }, text); }
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
  function pickTab(tab, focus) {
    state.tab = tab;
    document.querySelectorAll('.store-tabs [data-tab]').forEach(node => { const selected = node.dataset.tab === tab; node.setAttribute('aria-selected', String(selected)); node.tabIndex = selected ? 0 : -1; });
    $('store-content').setAttribute('aria-labelledby', 'store-tab-' + tab);
    document.querySelector('.store-intro').hidden = tab !== 'catalog';
    $('store-controls').hidden = tab === 'sources'; render();
    if (focus) $('store-tab-' + tab).focus({ preventScroll: true });
  }
  function updateCount(name, value) { document.querySelector('[data-count="' + name + '"]').textContent = value ? String(value) : ''; }
  function availableCatalog() { return state.catalog.filter(item => item.sourceEnabled !== false); }
  function updates() { return availableCatalog().filter(item => item.status === 'update' || item.updateAvailable); }
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
  function render() {
    updateCount('catalog', availableCatalog().length); updateCount('installed', state.installed.length); updateCount('updates', updates().length); updateCount('sources', state.sources.length);
    const select = $('store-source'), before = select.value;
    select.replaceChildren(h('option', { value: 'all' }, tr('Все источники')), ...state.sources.map(source => h('option', { value: source.id }, raw(source.title || source.repositoryId))));
    select.value = state.sources.some(source => source.id === before) ? before : 'all'; state.source = select.value;
    renderPending();
    const content = $('store-content'); content.setAttribute('aria-busy', String(!state.loaded));
    if (!state.loaded) return;
    if (state.tab === 'sources') { renderSources(content); return; }
    const items = (state.tab === 'installed' ? state.installed : state.tab === 'updates' ? updates() : availableCatalog()).filter(matches);
    if (!items.length) {
      if (state.query || state.kind !== 'all' || state.source !== 'all') content.replaceChildren(empty(tr('Ничего не найдено'), tr('Попробуйте другое название или измените фильтры.')));
      else if (state.tab === 'updates') content.replaceChildren(empty(tr('Всё актуально'), tr('Когда в подключённых источниках появятся новые версии, они будут здесь.'), tr('Проверить источники'), refreshSources));
      else if (state.tab === 'installed') content.replaceChildren(empty(tr('Пакетов из каталога пока нет'), tr('Ваши существующие модули остаются на месте. Здесь появятся пакеты, установленные через магазин.'), tr('Открыть каталог'), () => pickTab('catalog', true)));
      else content.replaceChildren(empty(tr(state.sources.length ? 'В источниках пока нет пакетов' : 'Подключите первый источник'), tr(state.sources.length ? 'Проверьте, включён ли источник, и обновите его каталог.' : 'Добавьте GitHub-репозиторий или ссылку на index.json. Каталог проверит формат перед подключением.'), tr('Добавить источник'), () => pickTab('sources', true)));
      return;
    }
    content.replaceChildren(h('div', { class: 'store-grid' }, items.map(item => packageCard(item, state.tab === 'installed'))));
  }
  function packageCard(item, installedView) {
    const installed = installedView ? item : installedFor(item);
    const title = item.title || packageId(item), card = h('article', { class: 'store-card', 'data-package': packageId(item), 'data-source': item.sourceId });
    card.append(h('div', { class: 'store-card-top' }, icon(item.kind || 'module'), h('div', { class: 'grow' }, h('h3', {}, raw(title)), h('div', { class: 'store-meta' }, h('span', {}, tr(item.kind === 'game' ? 'Игра' : 'Модуль')), h('span', {}, raw(item.version || ''))))));
    card.append(h('p', { class: 'store-description' }, raw(item.summary || (installedView ? tr('Установлено через каталог Assi') : ''))));
    const badges = h('div', { class: 'store-meta' });
    if (item.status === 'update' || item.updateAvailable) badges.append(h('span', { class: 'store-chip update' }, tr('Есть обновление')));
    else if (installed) badges.append(h('span', { class: 'store-chip success' }, tr('Установлено')));
    if (item.localModified || installed && installed.localModified) badges.append(h('span', { class: 'store-chip changed' }, tr('Есть свои правки')));
    if (item.status === 'sourceConflict') badges.append(h('span', { class: 'store-chip problem' }, tr('Конфликт источников')));
    else if (item.status === 'changedRelease') badges.append(h('span', { class: 'store-chip problem' }, tr('Выпуск изменён')));
    else if (item.compatible === false || item.status === 'incompatible') badges.append(h('span', { class: 'store-chip problem' }, tr('Нужна другая версия Assi')));
    if (item.archive && item.archive.size) badges.append(h('span', {}, bytes(item.archive.size)));
    card.append(badges, h('div', { class: 'store-source-line' }, raw(item.sourceTitle || sourceName(item.sourceId))));
    const actions = h('div', { class: 'store-card-actions' }, button(tr('Подробнее'), () => showPackage(item, installed), { 'data-details': packageId(item) }));
    if (installedView && entryPath(item)) actions.append(button(tr('Открыть'), () => openInstalled(item), { class: 'btn primary', 'data-open-package': packageId(item) }));
    else if (!installed || item.status === 'update' || item.updateAvailable) actions.append(button(tr(installed ? 'Обновить' : 'Установить'), event => prepare(item, event.currentTarget), { class: 'btn primary', disabled: item.compatible === false || item.status === 'incompatible', 'data-install': packageId(item) }));
    else if (entryPath(installed)) actions.append(button(tr('Открыть'), () => openInstalled(installed), { class: 'btn primary' }));
    card.append(actions); return card;
  }
  async function load(options) {
    const serial = ++state.loadSerial;
    const results = await Promise.allSettled([api('sources'), api('catalog'), api('installed'), api('plans')]);
    if (serial !== state.loadSerial) return;
    const fields = [['sources','sources'],['catalog','packages'],['installed','installed']];
    results.slice(0, 3).forEach((result, index) => { if (result.status === 'fulfilled') state[fields[index][0]] = listOf(result.value, fields[index][1]); });
    if (results[3].status === 'fulfilled') {
      const plans = listOf(results[3].value, 'plans');
      state.plans = plans;
      const live = state.pending && plans.find(plan => plan.id === state.pending.id);
      if (live) state.pending = live;
      else if (!state.pending) state.pending = plans.find(plan => ['ready','conflicts','blocked'].includes(plan.status)) || null;
      if (state.pending && state.pending.reviewTask && state.pending.reviewTask.status === 'running' && state.pending.status !== 'applied') state.reviewTask = Object.assign({ planId: state.pending.id }, state.pending.reviewTask);
    }
    state.loaded = true; render();
    const failed = results.find(result => result.status === 'rejected');
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
    } }, h('h3', {}, tr('Подключить репозиторий')), h('p', {}, tr('В репозитории обязателен index.json по правилам Assi. Подойдут GitHub и другие HTTPS-источники.')), h('div', { class: 'store-source-inputs' }, input, submit));
    content.replaceChildren(h('div', { class: 'store-sources' }, form, state.sources.map(source => sourceCard(source))));
    if (!state.sources.length) content.firstChild.append(note(tr('Источников пока нет. Установка публичных пакетов не требует GitHub-аккаунта.')));
  }
  function sourceCard(source) {
    const address = source.indexUrl || source.url || '';
    const details = h('div', { class: 'grow' }, h('h3', {}, raw(source.title || source.repositoryId || source.id)), h('p', { class: 'store-source-address' }, raw(address)),
      h('div', { class: 'store-meta' }, h('span', {}, tr(source.enabled === false ? 'Выключен' : 'Подключён')), h('span', {}, tr('Пакетов: {}', source.packageCount || 0)), h('span', {}, source.lastSuccess ? tr('Обновлено: {}', ui.ago(Number(source.lastSuccess))) : tr('Ещё не обновлялся'))));
    if (source.error) details.append(h('p', { class: 'store-source-error' }, raw(source.error)), note(tr('Последний полученный каталог сохранён.')));
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
  function showPackage(item, installed) {
    const catalogEntry = state.catalog.find(entry => entry.sourceId === item.sourceId && packageId(entry) === packageId(item));
    const display = catalogEntry || item;
    const current = dialog(display.title || packageId(item));
    const compatible = display.compatible !== false && display.status !== 'incompatible';
    current.body.append(h('p', {}, raw(display.summary || '')), facts([
      ['Тип', tr(display.kind === 'game' ? 'Игра' : 'Модуль')], ['Версия', display.version], ['Установлена', installed && installed.version], ['Автор', display.publisher], ['Лицензия', display.license], ['Источник', display.sourceTitle || sourceName(item.sourceId)], ['Загрузка', display.archive && bytes(display.archive.size)], ['После распаковки', display.archive && bytes(display.archive.unpackedSize)]
    ]));
    if (display.compatibility) current.body.append(note(tr('API Assi: от {}', display.compatibility.minAssiApi) + (display.compatibility.gamesEngine ? ' · ' + tr('Движок игр: {}', display.compatibility.gamesEngine) : '')));
    if (!compatible) current.body.append(h('p', { class: 'store-notice danger' }, raw(display.compatibilityError || tr('Пакет несовместим с установленной версией Assi.'))));
    if (installed && installed.localModified) current.body.append(h('p', { class: 'store-notice' }, tr('В пакете есть ваши доработки. Перед обновлением покажем изменения и возможные конфликты.')));
    if (display.screenshots && display.screenshots.length) {
      const links = display.screenshots.map(safeHttps).filter(Boolean).map((url,index) => h('a', { href: url, target: '_blank', rel: 'noopener noreferrer', referrerpolicy: 'no-referrer' }, tr('Снимок {}', index + 1)));
      current.body.append(h('div', { class: 'store-screenshot-links' }, links));
    }
    current.body.append(note(tr('Перед установкой загрузим конкретную версию, проверим структуру и хеши. Код пакета на этом шаге не запускается.')));
    const controls = h('div', { class: 'store-dialog-actions' });
    if (compatible && (!installed || display.status === 'update' || display.updateAvailable)) controls.append(button(tr(installed ? 'Подготовить обновление' : 'Подготовить установку'), event => prepare(display, event.currentTarget), { class: 'btn primary', id: 'store-prepare' }));
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
      const plan = await api('prepare', item.sourceId, packageId(item)); state.pending = plan; renderPending();
      if (state.dialog && state.dialog.token === token) showPlan(plan); else status(tr('Пакет подготовлен. Можно продолжить установку.'));
    });
  }
  function renderPending() {
    const node = $('store-pending'), plan = state.pending && state.pending.status !== 'applied' ? state.pending : state.plans.find(item => ['ready','conflicts','blocked'].includes(item.status));
    node.hidden = !plan || plan.status === 'applied'; if (node.hidden) { node.replaceChildren(); return; }
    node.replaceChildren(h('div', { class: 'store-pending' }, h('div', {}, h('strong', {}, tr(state.reviewTask && state.reviewTask.planId === plan.id ? 'Агент проверяет пакет' : 'Подготовленная установка')), h('p', {}, raw((plan.title || plan.manifest && plan.manifest.id || plan.packageId || '') + ' · ' + (plan.version || '')))),
      button(tr('Продолжить'), event => operation('resume-plan', event.currentTarget, async () => { const [fresh, report] = await Promise.all([api('plan', plan.id), api('review', plan.id)]); showPlan(fresh, report); }), { id: 'store-resume-plan' })));
  }
  function readableStatus(value) { return tr(({ pass: 'Проверка завершена', needsAttention: 'Нужно ваше внимание', reject: 'Пакет отклонён', running: 'Агент проверяет пакет', pending: 'Проверка ещё не завершена', applied: 'Установка завершена' })[value] || value || 'Проверка ещё не завершена'); }
  function reviewCard(report, task) {
    const stopped = report.agentStatus === 'stopped' || task && task.status === 'stopped';
    const card = h('section', { class: 'store-review ' + (report.status || ''), 'data-review-status': report.status || 'pending' }, h('strong', {}, stopped && ['pending','running'].includes(report.status) ? tr('Проверка остановлена') : readableStatus(report.status)));
    if (stopped && ['pending','running'].includes(report.status)) card.append(note(tr('Можно открыть диалог или запустить проверку заново. Пакет пока не установлен.')));
    if (report.summary) card.append(note(raw(report.summary)));
    const findings = Array.isArray(report.findings) ? report.findings : [];
    if (findings.length) card.append(h('ul', { class: 'store-plain-list' }, findings.map(f => h('li', {}, raw(f.explanation || f.message || f.code || String(f))))));
    if (Array.isArray(report.unknowns) && report.unknowns.length) card.append(note(tr('Не проверено: {}', report.unknowns.join('; '))));
    if (task && task.thread) card.append(button(tr('Открыть диалог с агентом'), () => operation('open-review', null, async () => {
      await assi.native('agent.openThread', task.agent || 'assi', task.thread); assi.open('ui/chat/index.html?agent=' + encodeURIComponent(task.agent || 'assi'));
    }), { id: 'store-open-review' }));
    return card;
  }
  function showPlan(plan, report) {
    const previous = state.dialog, origin = previous && previous.origin;
    const current = dialog(plan.status === 'applied' ? tr('Готово') : tr(plan.rollback || plan.operation === 'rollback' ? 'Возврат версии' : 'Проверка перед установкой'));
    if (origin) current.origin = origin; current.planId = plan.id;
    state.pending = plan; renderPending();
    const manifest = plan.manifest || {}, conflicts = Array.isArray(plan.conflicts) ? plan.conflicts.filter(c => !c.resolved) : [];
    const blockers = Array.isArray(plan.blockers) ? plan.blockers : [];
    const blocked = plan.status === 'blocked' || blockers.length > 0;
    const changes = Array.isArray(plan.changes) ? plan.changes : [];
    current.body.append(facts([['Пакет', plan.title || manifest.id || plan.packageId], ['Версия', plan.version || manifest.version], ['Автор', manifest.publisher], ['Лицензия', manifest.license]]));
    if (plan.status === 'applied') {
      current.body.append(h('p', { class: 'store-notice' }, tr('Пакет установлен. Сохранения, настройки и сохранённые локальные правки остались на месте.')));
      const completedReport = report || plan.review;
      if (completedReport && completedReport.completed && completedReport.historicalOnly) current.body.append(reviewCard(completedReport, plan.reviewTask || completedReport));
      const installed = state.installed.find(item => installationKey(item) === plan.installationKey);
      current.footer.append(entryPath(installed || plan) ? button(tr('Открыть пакет'), () => { closeDialog(); openInstalled(installed || plan); }, { class: 'btn primary' }) : button(tr('Готово'), closeDialog, { class: 'btn primary' })); return;
    }
    current.body.append(note(tr('Обновляется код пакета. Личные данные и сохранения не заменяются.')));
    if (blocked) current.body.append(h('div', { class: 'store-notice danger', 'data-plan-blocked': '' }, h('strong', {}, tr('Сначала нужно устранить препятствия')), h('ul', { class: 'store-plain-list' }, blockers.length ? blockers.map(reason => h('li', {}, raw(typeof reason === 'string' ? reason : reason.message || reason.reason || JSON.stringify(reason)))) : h('li', {}, tr('Подготовьте пакет заново после обновления источника.')))));
    if (Array.isArray(plan.dependencies) && plan.dependencies.length) current.body.append(h('details', { open: blocked }, h('summary', {}, tr('Зависимости: {}', plan.dependencies.length)), h('ul', { class: 'store-plain-list' }, plan.dependencies.map(item => h('li', {}, raw(item.id + ' ' + (item.version || '')), ' · ', tr(item.satisfied ? 'Установлено' : item.optional ? 'Необязательная связь' : 'Не установлено'))))));
    if (plan.rollback || plan.operation === 'rollback') current.body.append(h('p', { class: 'store-notice' }, tr('Возвращается предыдущий код. Данные сохраняются; старый код может не поддерживать более новый формат сохранения.')));
    if (conflicts.length) {
      current.body.append(h('p', { class: 'store-notice' }, tr('Есть конфликт с вашими доработками. Сохраните свою версию файла или поручите агенту разобраться. Ничего не заменено.')));
      current.body.append(h('div', { class: 'store-plan-list' }, conflicts.map(conflict => h('div', { class: 'store-plan-file store-plan-conflict', 'data-conflict': conflict.path },
        h('div', { class: 'grow' }, h('code', {}, raw(conflict.path)), note(tr('Изменён и у вас, и в новой версии'))),
        button(tr('Сохранить мою версию'), event => operation('resolve-' + plan.id, event.currentTarget, async () => {
          const next = await api('resolve', plan.id, conflict.path, conflict.localSha || conflict.localHash || 'missing', null, true); state.pending = next; showPlan(next);
        }), { 'data-keep-local': conflict.path })))));
    }
    const preserved = changes.filter(change => change.localModified || /preserv|keep|local/i.test(change.kind || ''));
    if (preserved.length) current.body.append(note(tr('Локальных правок будет сохранено: {}', preserved.length)));
    const fileList = h('details', {}, h('summary', {}, tr('Изменения файлов: {}', changes.length)), h('ul', { class: 'store-plain-list' }, changes.map(change => h('li', {}, h('code', {}, raw(change.path)), ' · ', raw(change.kind || change.action || '')))));
    current.body.append(fileList);
    const requests = (manifest.capabilities || []).concat((manifest.networkDomains || []).map(domain => 'https://' + domain));
    if (requests.length) current.body.append(h('details', {}, h('summary', {}, tr('Заявленные возможности и сеть: {}', requests.length)), h('ul', { class: 'store-plain-list' }, requests.map(item => h('li', {}, raw(item))))));
    current.body.append(h('p', { class: 'store-notice' }, tr('Этот пакет выполняется с возможностями Assi. Изоляция доступа модулей пока не включена. Проверка агентом помогает изучить код, но не создаёт такую изоляцию.')));
    const task = plan.reviewTask || (state.reviewTask && state.reviewTask.planId === plan.id ? state.reviewTask : null);
    const currentReport = report || plan.review;
    const agentRunning = !!task && task.status !== 'stopped' && (!currentReport || currentReport.agentStatus !== 'stopped');
    if (currentReport || task) current.body.append(reviewCard(currentReport || { status: 'running' }, task));
    const ackKey = plan.id + ':' + plan.digest;
    const ack = h('input', { type: 'checkbox', id: 'store-trust-ack', checked: state.acknowledged.has(ackKey) });
    const direct = button(tr(plan.rollback || plan.operation === 'rollback' ? 'Вернуть версию' : 'Установить'), event => operation('apply-' + plan.id, event.currentTarget, async () => {
      if (!state.acknowledged.has(ackKey) || conflicts.length || blocked || agentRunning) return;
      current.locked = true; current.close.disabled = true; dialogStatus(tr('Устанавливаем проверенный пакет…'));
      try {
        const result = await api('apply', plan.id, plan.digest, true);
        state.pending = Object.assign({}, plan, result, { id: plan.id, status: 'applied' }); state.reviewTask = null;
        current.locked = false; current.close.disabled = false; await load({ keepStatus: true }); showPlan(state.pending); status(tr('Установка завершена'));
      } finally { current.locked = false; current.close.disabled = false; }
    }), { class: 'btn primary', id: 'store-apply', disabled: blocked || agentRunning || !ack.checked || conflicts.length > 0 || currentReport && currentReport.status === 'reject' });
    const agent = button(tr('Проверить и установить с агентом'), event => operation('review-' + plan.id, event.currentTarget, async () => {
      if (!state.acknowledged.has(ackKey) || blocked) return;
      const task = await api('reviewStart', plan.id, true); state.reviewTask = Object.assign({ planId: plan.id }, task); showPlan(plan, { status: 'running' }); schedulePoll();
    }), { id: 'store-review-agent', disabled: blocked || !ack.checked || agentRunning });
    ack.addEventListener('change', () => {
      if (ack.checked) state.acknowledged.add(ackKey); else state.acknowledged.delete(ackKey);
      direct.disabled = blocked || agentRunning || !ack.checked || conflicts.length > 0 || currentReport && currentReport.status === 'reject'; agent.disabled = blocked || !ack.checked || agentRunning;
    });
    current.footer.append(h('label', { class: 'store-trust' }, ack, h('span', {}, tr('Разрешаю запуск этого пакета с возможностями Assi'), h('small', {}, tr('Для этой версии и показанного плана установки.')))), h('div', { class: 'store-dialog-actions' }, direct, agent));
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
  $('store-search').addEventListener('input', event => { state.query = event.target.value.trim().toLocaleLowerCase(); render(); });
  $('store-kind').addEventListener('change', event => { state.kind = event.target.value; render(); });
  $('store-source').addEventListener('change', event => { state.source = event.target.value; render(); });
  $('store-reload').addEventListener('click', refreshSources); $('store-back').addEventListener('click', () => assi.back());
  document.addEventListener('visibilitychange', schedulePoll);
  window.addEventListener('pagehide', () => { if (state.poll) clearTimeout(state.poll); state.poll = null; });
  if (assi.on) {
    assi.on('app.visible', event => { state.visible = !event || event.on !== false; schedulePoll(); });
    assi.on('store.changed', () => { if (visible()) load({ keepStatus: true }).catch(error => status(errorText(error), true)); });
  }
  load().catch(error => { state.loaded = true; render(); status(errorText(error), true, () => load()); });
})();

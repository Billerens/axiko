/* A small workspace editor with independent documents and revision-aware saves. */
(function () {
  'use strict';
  var PREFIX = 'assi.desk.buffer:';
  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (key) {
      if (key === 'class') node.className = attrs[key];
      else if (key.indexOf('on') === 0) node.addEventListener(key.slice(2), attrs[key]);
      else if (key in node && key !== 'role') node[key] = attrs[key];
      else node.setAttribute(key, attrs[key]);
    });
    (Array.isArray(children) ? children : children == null ? [] : [children]).forEach(function (child) {
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    });
    return node;
  }
  function button(text, action, className) {
    return el('button', { type: 'button', class: className || 'ae-button', onclick: action }, text);
  }
  function readRecovery(key) {
    try { return JSON.parse(localStorage.getItem(PREFIX + key) || 'null'); } catch (_) { return null; }
  }

  function mount(container, options) {
    var active = null, alive = true, treeTicket = 0, recoveryTimer = 0;
    var rows = [], folders = new Map(), findAt = 0;
    var rpc = options.rpc;
    var toast = options.toast || function () {};
    var root = el('section', { class: 'assi-editor', 'aria-label': 'Редактор файлов' });
    var tree = el('nav', { class: 'ae-tree', 'aria-label': 'Файлы приложения' });
    var filter = el('input', { class: 'ae-filter', type: 'search', placeholder: 'Найти в открытых папках', 'aria-label': 'Фильтр файлов' });
    var tabs = el('div', { class: 'ae-tabs', role: 'tablist', 'aria-label': 'Открытые файлы' });
    var pathLabel = el('span', { class: 'ae-path' }, 'Выберите файл');
    var stateLabel = el('span', { class: 'ae-state', role: 'status' }, '');
    var trouble = el('div', { class: 'ae-trouble', hidden: true });
    var code = el('textarea', { class: 'ae-code', spellcheck: false, wrap: 'off', disabled: true, 'aria-label': 'Код файла', 'aria-description': 'Tab — отступ, Shift+Tab — перейти к панели редактора', autocapitalize: 'off', autocomplete: 'off' });
    var lines = el('pre', { class: 'ae-lines', 'aria-hidden': 'true' }, '1');
    var footer = el('div', { class: 'ae-footer' }, 'Файлы из рабочей папки Аксико');
    var empty = el('div', { class: 'ae-empty' }, [
      el('strong', {}, 'Здесь можно менять Аксико'),
      el('p', {}, 'Откройте файл слева. Правки остаются в своих вкладках, пока вы их не сохраните.'),
      button('Открыть файлы web-хаба', function () { expand('ui/').then(function () { return expand('ui/desk/'); }); })
    ]);
    var findInput = el('input', { class: 'ae-filter', type: 'search', placeholder: 'Текст в файле', 'aria-label': 'Поиск в файле' });
    var findBar = el('div', { class: 'ae-find', hidden: true }, [
      findInput, button('Назад', function () { find(-1); }), button('Дальше', function () { find(1); }),
      button('Закрыть', function () { findBar.hidden = true; code.focus(); })
    ]);
    var saveButton = button('Сохранить', saveActive, 'ae-button ae-primary');
    var previewButton = button('Открыть экран', function () {
      var doc = selected(); if (doc) options.open((doc.draft ? '~' + doc.draft + '/' : '') + doc.path);
    });
    var reloadButton = button('Перечитать', reloadActive);
    var treeSide = el('aside', { class: 'ae-side' }, [
      el('div', { class: 'ae-tree-head' }, [el('strong', {}, 'Файлы'), button('+', newFile, 'ae-icon'), button('↻', refresh, 'ae-icon')]),
      filter, tree
    ]);
    treeSide.querySelectorAll('.ae-icon')[0].setAttribute('aria-label', 'Новый файл');
    treeSide.querySelectorAll('.ae-icon')[1].setAttribute('aria-label', 'Обновить дерево файлов');
    var main = el('div', { class: 'ae-main' }, [
      tabs,
      el('div', { class: 'ae-toolbar' }, [
        button('Папки', function () { root.classList.toggle('ae-tree-open'); }),
        saveButton, previewButton, reloadButton,
        button('Найти', function () { findBar.hidden = false; findInput.focus(); })
      ]),
      el('div', { class: 'ae-caption' }, [pathLabel, stateLabel]),
      trouble, findBar,
      el('div', { class: 'ae-buffer' }, [lines, code, empty]), footer
    ]);
    root.append(treeSide, main); container.replaceChildren(root);

    var store = AssiEditorModel.create(rpc, {
      changed: function (doc) {
        if (!alive) return;
        renderTabs();
        if (active === doc.key) renderDocument();
        clearTimeout(recoveryTimer);
        recoveryTimer = setTimeout(persistAll, 200);
      },
      saved: function (doc) {
        window.dispatchEvent(new CustomEvent('desk.file.saved', { detail: { path: doc.path, draft: doc.draft } }));
      }
    });
    function selected() { return store.docs.get(active); }
    function persistAll() {
      store.docs.forEach(function (doc) {
        if (doc.loading || (doc.error && doc.revision === null && !doc.isNew)) return;
        try {
          if (!store.dirty(doc)) localStorage.removeItem(PREFIX + doc.key);
          else localStorage.setItem(PREFIX + doc.key, JSON.stringify({ text: doc.text, revision: doc.revision, at: Date.now() }));
        } catch (_) { if (doc.key === active) footer.textContent = 'Браузер не смог сохранить резервную копию текста'; }
      });
    }
    function renderTabs() {
      tabs.replaceChildren();
      store.docs.forEach(function (doc) {
        var open = button(doc.path.split('/').pop() + (store.dirty(doc) ? ' •' : ''), function () {
          active = doc.key; renderTabs(); renderDocument();
        }, 'ae-tab-label');
        open.setAttribute('role', 'tab');
        open.setAttribute('aria-selected', String(active === doc.key));
        open.title = doc.key;
        var close = button('×', function () { closeDoc(doc); }, 'ae-tab-close');
        close.setAttribute('aria-label', 'Закрыть ' + doc.path);
        tabs.append(el('div', { class: 'ae-tab' + (active === doc.key ? ' on' : '') }, [open, close]));
      });
    }
    function renderDocument() {
      var doc = selected();
      if (!doc) {
        code.value = ''; code.disabled = true; empty.hidden = false;
        pathLabel.textContent = 'Выберите файл'; stateLabel.textContent = '';
        saveButton.disabled = previewButton.disabled = reloadButton.disabled = true;
        trouble.hidden = true; updateLines(); return;
      }
      empty.hidden = true;
      pathLabel.textContent = (doc.draft ? 'Черновик ' + doc.draft + ' / ' : '') + doc.path;
      if (code.value !== doc.text) {
        var top = code.scrollTop, left = code.scrollLeft;
        code.value = doc.text; code.scrollTop = top; code.scrollLeft = left;
      }
      code.disabled = doc.loading || !!(doc.error && doc.revision === null && !doc.isNew);
      saveButton.disabled = doc.loading || doc.saving || (!store.dirty(doc)) || code.disabled;
      saveButton.textContent = doc.saving ? 'Сохраняется…' : 'Сохранить';
      previewButton.disabled = !/\.html?$/i.test(doc.path) || doc.isNew;
      reloadButton.disabled = doc.loading || doc.saving;
      stateLabel.textContent = doc.loading ? 'Загрузка…' : doc.saving ? 'Сохраняется…' :
        doc.error ? 'Не сохранено' : doc.recovered ? 'Восстановлены правки' : doc.isNew ? 'Новый файл' :
        store.dirty(doc) ? 'Есть изменения' : 'Сохранено';
      stateLabel.dataset.state = doc.error ? 'error' : store.dirty(doc) ? 'dirty' : 'saved';
      trouble.replaceChildren();
      trouble.hidden = !doc.error;
      if (doc.error) {
        trouble.append(el('span', {}, doc.error.message || String(doc.error)));
        if (doc.error.code === 'conflict') trouble.append(button('Сравнить версии', function () { compare(doc); }));
        else trouble.append(button('Повторить загрузку', reloadActive));
      }
      updateLines();
    }
    function updateLines() {
      var count = code.value.split('\n').length;
      lines.textContent = Array.from({ length: count }, function (_, i) { return i + 1; }).join('\n');
      lines.scrollTop = code.scrollTop;
      var position = code.value.slice(0, code.selectionStart);
      footer.textContent = (selected() ? 'Строка ' + position.split('\n').length + ', столбец ' + (position.length - position.lastIndexOf('\n')) +
        ' · ' + code.value.length.toLocaleString('ru') + ' символов · Ctrl+S — сохранить' : 'Файлы из рабочей папки Аксико');
    }
    async function openFile(path, settings) {
      var a;
      try { a = store.address(path, settings && settings.draft); } catch (error) { toast(error.message); return; }
      active = a.key;
      root.classList.remove('ae-tree-open');
      var wasOpen = store.docs.has(a.key);
      var promise = store.open(path, settings);
      renderTabs(); renderDocument();
      var doc = await promise;
      if (!alive || store.docs.get(doc.key) !== doc) return;
      if (!wasOpen && !doc.error) {
        var local = readRecovery(doc.key);
        if (local && typeof local.text === 'string' && local.text !== doc.saved) {
          store.update(doc, local.text);
          doc.recovered = true;
          if (local.revision !== doc.revision) {
            doc.error = Object.assign(new Error('На телефоне уже другая версия. Сравните её с восстановленным текстом.'), { code: 'conflict' });
          }
        }
      }
      renderTabs(); if (active === doc.key) renderDocument();
      return doc;
    }
    async function saveActive() {
      var doc = selected(); if (!doc) return;
      if (doc.error && doc.error.code === 'conflict') return compare(doc);
      try { await store.save(doc); doc.recovered = false; persistAll(); renderTabs(); renderDocument(); }
      catch (error) { toast(error.message); }
    }
    async function reloadActive() {
      var doc = selected(); if (!doc) return;
      if (store.dirty(doc) && !window.confirm('Загрузить версию с телефона и отбросить несохранённые правки в ' + doc.path + '?')) return;
      try { doc.recovered = false; await store.reload(doc); persistAll(); }
      catch (error) { toast(error.message); }
    }
    function closeDoc(doc) {
      if (doc.saving) { toast('Дождитесь завершения сохранения'); return; }
      if (store.dirty(doc) && !window.confirm('Закрыть ' + doc.path + ' без сохранения?')) return;
      // A pending/failed read has not offered the stored recovery text to the user yet.
      // Closing that tab must not silently discard an earlier editing session.
      var unreadRecovery = doc.loading || !!(doc.error && doc.revision === null && !doc.isNew);
      store.close(doc);
      if (!unreadRecovery) {
        try { localStorage.removeItem(PREFIX + doc.key); } catch (_) {}
      }
      if (active === doc.key) active = store.docs.keys().next().value || null;
      renderTabs(); renderDocument();
    }
    async function compare(doc) {
      var remote;
      try { remote = await store.remote(doc); } catch (error) { toast(error.message); return; }
      var dialog = el('dialog', { class: 'ae-compare' });
      var body = el('div', { class: 'ae-compare-grid' }, [
        el('label', {}, ['На телефоне', el('textarea', { readOnly: true, value: remote.text, 'aria-label': 'Версия на телефоне' })]),
        el('label', {}, ['Ваши правки', el('textarea', { readOnly: true, value: doc.text, 'aria-label': 'Ваши правки' })])
      ]);
      var finish = function () { dialog.close(); dialog.remove(); };
      dialog.append(el('h2', {}, 'Файл изменился: ' + doc.path), el('p', {}, 'Выберите версию или закройте сравнение, чтобы объединить правки вручную.'), body,
        el('div', { class: 'ae-compare-actions' }, [
          button('Оставить версию телефона', function () { store.adopt(doc, remote, false); doc.recovered = false; persistAll(); finish(); }),
          button('Сохранить мои правки', async function () {
            try { store.adopt(doc, remote, true); await store.save(doc); doc.recovered = false; persistAll(); finish(); }
            catch (error) { toast(error.message); finish(); }
          }),
          button('Вернуться к редактированию', finish)
        ]));
      dialog.addEventListener('cancel', function () { dialog.remove(); });
      document.body.append(dialog); dialog.showModal();
    }
    async function newFile() {
      var path = window.prompt('Путь нового файла относительно рабочей папки', 'ui/new-page.html');
      if (path) await openFile(path.trim(), { create: true });
    }

    async function list(path, into, ticket) {
      into.replaceChildren(el('span', { class: 'ae-tree-note' }, 'Загрузка…'));
      try {
        var names = await rpc({ op: 'eval', draft: null, code: 'require("lib/assi").files.list(' + JSON.stringify(path) + ')' });
        if (!alive || ticket !== treeTicket) return;
        names = names.slice().sort(function (a, b) { return Number(b.endsWith('/')) - Number(a.endsWith('/')) || a.localeCompare(b, 'ru'); });
        into.replaceChildren();
        names.forEach(function (name) {
          var isFolder = name.endsWith('/'), full = path + name;
          var row = button(name, function () {
            if (isFolder) toggle(full); else openFile(full);
          }, 'ae-file' + (isFolder ? ' ae-folder' : ''));
          row.dataset.path = full;
          row.title = full;
          if (isFolder) row.setAttribute('aria-expanded', 'false');
          var box = el('div', { class: 'ae-branch', hidden: true });
          rows.push({ row: row, path: full });
          into.append(row);
          if (isFolder) { into.append(box); folders.set(full, { row: row, box: box, loaded: false }); }
        });
        if (!names.length) into.append(el('span', { class: 'ae-tree-note' }, 'Папка пуста'));
        filterTree();
      } catch (error) {
        if (!alive || ticket !== treeTicket) return;
        into.replaceChildren(el('p', { class: 'ae-tree-note' }, error.message), button('Повторить', function () { list(path, into, ticket); }));
      }
    }
    async function toggle(path) {
      var folder = folders.get(path); if (!folder) return;
      folder.box.hidden = !folder.box.hidden;
      folder.row.setAttribute('aria-expanded', String(!folder.box.hidden));
      if (!folder.box.hidden && !folder.loaded) {
        folder.loaded = true;
        await list(path, folder.box, treeTicket);
      }
    }
    async function expand(path) {
      var folder = folders.get(path); if (folder && folder.box.hidden) await toggle(path);
    }
    function filterTree() {
      var query = filter.value.trim().toLowerCase();
      rows.forEach(function (item) { item.row.hidden = !!query && !item.path.toLowerCase().includes(query) && !item.row.classList.contains('ae-folder'); });
    }
    async function refresh() {
      treeTicket++; rows = []; folders.clear();
      await list('', tree, treeTicket);
    }
    function find(direction) {
      var query = findInput.value;
      if (!query || code.disabled) return;
      var hay = code.value.toLocaleLowerCase('ru'), needle = query.toLocaleLowerCase('ru');
      var at;
      if (direction < 0) { at = hay.lastIndexOf(needle, Math.max(0, code.selectionStart - 1)); if (at < 0) at = hay.lastIndexOf(needle); }
      else { at = hay.indexOf(needle, code.selectionEnd); if (at < 0) at = hay.indexOf(needle); }
      if (at < 0) { stateLabel.textContent = 'Текст не найден'; return; }
      code.focus(); code.setSelectionRange(at, at + query.length);
      code.scrollTop = Math.max(0, (code.value.slice(0, at).split('\n').length - 3) * 20);
      updateLines();
    }
    code.addEventListener('input', function () {
      var doc = selected(); if (!doc) return;
      doc.recovered = false; store.update(doc, code.value);
    });
    code.addEventListener('scroll', function () { lines.scrollTop = code.scrollTop; });
    code.addEventListener('click', updateLines);
    code.addEventListener('keyup', updateLines);
    code.addEventListener('keydown', function (event) {
      if (event.key === 'Tab' && !event.shiftKey && !event.ctrlKey && !event.metaKey) {
        event.preventDefault();
        code.setRangeText('  ', code.selectionStart, code.selectionEnd, 'end');
        code.dispatchEvent(new Event('input'));
      }
    });
    filter.addEventListener('input', filterTree);
    findInput.addEventListener('keydown', function (event) { if (event.key === 'Enter') { event.preventDefault(); find(event.shiftKey ? -1 : 1); } });
    function keydown(event) {
      if (!root.isConnected || !root.getClientRects().length) return;
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); saveActive(); }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'f' && root.contains(document.activeElement)) {
        event.preventDefault(); findBar.hidden = false; findInput.focus();
      }
    }
    function beforeUnload(event) { persistAll(); if (hasUnsaved()) { event.preventDefault(); event.returnValue = ''; } }
    function hasUnsaved() { return Array.from(store.docs.values()).some(store.dirty); }
    document.addEventListener('keydown', keydown);
    window.addEventListener('beforeunload', beforeUnload);
    renderDocument(); refresh();
    return {
      openFile: openFile, refresh: refresh, hasUnsaved: hasUnsaved,
      canLeave: function () { persistAll(); return !hasUnsaved() || window.confirm('Есть несохранённые правки. Они останутся в этом браузере. Продолжить?'); },
      dispose: function () {
        persistAll(); alive = false; clearTimeout(recoveryTimer); treeTicket++;
        document.removeEventListener('keydown', keydown); window.removeEventListener('beforeunload', beforeUnload);
        container.replaceChildren();
      }
    };
  }
  window.AssiEditor = { mount: mount };
})();

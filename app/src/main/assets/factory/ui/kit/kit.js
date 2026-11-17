// Shared helpers of every screen: window.ui. Plain script, no build step.
(function () {
  // ---- the language of the interface ------------------------------------------------------------
  // Screens are written in Russian. For another language the app adds a dictionary to this file
  // as it serves it (window.__i18n: Russian text -> that language, see ui/i18n/*.json), and
  // every text that appears on the page is shown in that language — however it got there: the
  // static markup, ui.h, textContent. A key with {} matches a text with something put in:
  // "Удалить тему «{}»?" matches "Удалить тему «Закат»?".
  // What is not the interface's own words — a message, an answer, a file — is kept as written:
  // put it in with ui.raw(text), or mark its container with the attribute data-raw.
  var CYRILLIC = /[А-Яа-яЁё]/;
  var READ = ['placeholder', 'aria-label', 'title', 'alt'];
  var patterns = null;
  var missed = window.__i18nMissed = {};

  function compile(dict) {
    return Object.keys(dict).filter(function (key) { return key.indexOf('{}') >= 0 && dict[key]; })
      .sort(function (a, b) { return b.length - a.length; })
      .map(function (key) {
        var source = key.split('{}').map(function (part) { return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }).join('([\\s\\S]+?)');
        return [new RegExp('^' + source + '$'), dict[key]];
      });
  }

  function fill(into, values) {
    var next = 0;
    return into.replace(/\{(\d*)\}/g, function (slot, number) {
      var value = values[number ? Number(number) - 1 : next++];
      return value === undefined ? '' : value;
    });
  }

  /** The text as the interface's language has it; the text itself when there is no translation. */
  function tr(text, inner) {
    var dict = window.__i18n;
    if (!dict || typeof text !== 'string' || !CYRILLIC.test(text)) return text;
    var core = text.trim();
    var hit = dict[core];
    if (typeof hit === 'string' && hit) return text.replace(core, function () { return hit; });
    if (core.length > 400) return text;
    patterns = patterns || compile(dict);
    for (var i = 0; i < patterns.length; i++) {
      var match = patterns[i][0].exec(core);
      if (!match) continue;
      // What was put in is translated too when it is itself a text of the interface.
      var values = match.slice(1).map(function (value) { return inner ? value : tr(value, true); });
      var made = fill(patterns[i][1], values);
      return text.replace(core, function () { return made; });
    }
    if (!inner) missed[core] = 1;
    return text;
  }

  /**
   * A text of your own screen in the interface's language: ui.t('Осталось {} стаканов', left).
   * Texts given to ui.h are translated by themselves; this is for texts put together by hand.
   */
  function t(text) {
    var values = Array.prototype.slice.call(arguments, 1);
    if (!values.length) return tr(text);
    var dict = window.__i18n;
    var known = dict && dict[text];
    return fill(typeof known === 'string' && known ? known : text, values);
  }

  /** A text that must be shown as it is — what the owner or a model wrote, not the interface's own words. */
  function raw(text) {
    var node = document.createTextNode(String(text));
    node.__raw = true;
    return node;
  }

  var KEPT = { SCRIPT: 1, STYLE: 1, TEXTAREA: 1 };

  function kept(element) {
    return !element || KEPT[element.nodeName] || (element.closest && element.closest('[data-raw]'));
  }

  function word(node) {
    var text = node.nodeValue;
    if (node.__raw || !CYRILLIC.test(text) || kept(node.parentNode)) return;
    var made = tr(text);
    // Markup wraps long texts over lines; the dictionary knows them in one line.
    if (made === text && /\s\s|\n/.test(text)) made = tr(text.replace(/\s+/g, ' '));
    if (made !== text) node.nodeValue = made;
  }

  function attribute(element, name) {
    var value = element.getAttribute(name);
    if (!value || !CYRILLIC.test(value) || (element.closest && element.closest('[data-raw]'))) return;
    var made = tr(value);
    if (made !== value) element.setAttribute(name, made);
  }

  /** Everything readable inside the element. */
  function translate(root) {
    if (!window.__i18n || root.nodeType !== 1) return;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(word);
    READ.forEach(function (name) {
      if (root.hasAttribute(name)) attribute(root, name);
      root.querySelectorAll('[' + name + ']').forEach(function (node) { attribute(node, name); });
    });
  }

  // Whatever appears on the page later is translated as it appears, before it is painted.
  new MutationObserver(function (records) {
    if (!window.__i18n) return;
    records.forEach(function (record) {
      if (record.type === 'characterData') word(record.target);
      else if (record.type === 'attributes') attribute(record.target, record.attributeName);
      else record.addedNodes.forEach(function (node) { if (node.nodeType === 3) word(node); else translate(node); });
    });
  }).observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: READ });

  /** h('button', { class: 'btn', onclick: fn }, 'Текст', childNode) */
  function h(tag, attrs) {
    var node = document.createElement(tag);
    for (var key in attrs || {}) {
      var value = attrs[key];
      if (value === null || value === undefined || value === false) continue;
      if (READ.indexOf(key) >= 0) value = tr(value);
      if (key.slice(0, 2) === 'on' && typeof value === 'function') node.addEventListener(key.slice(2), value);
      else if (key === 'class') node.className = value;
      else if (key === 'html') node.innerHTML = value;
      else if (key in node && key !== 'list') node[key] = value;
      else node.setAttribute(key, value === true ? '' : value);
    }
    for (var i = 2; i < arguments.length; i++) append(node, arguments[i]);
    return node;
  }

  function append(node, child) {
    if (child === null || child === undefined || child === false) return;
    if (Array.isArray(child)) child.forEach(function (item) { append(node, item); });
    else node.appendChild(child instanceof Node ? child : document.createTextNode(tr(String(child))));
  }

  function esc(text) {
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function inline(text) {
    return esc(text)
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
      .replace(/(^|[\s(])\*([^*\s][^*]*)\*/g, '$1<i>$2</i>')
      .replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g, '<a href="$2">$1</a>');
  }

  /** Markdown as the model writes it → HTML: paragraphs, lists, headings, code, tables. */
  function md(source) {
    var lines = String(source || '').replace(/\r/g, '').split('\n');
    var out = [];
    var i = 0;
    while (i < lines.length) {
      var line = lines[i];
      if (/^```/.test(line)) {
        var code = [];
        i++;
        while (i < lines.length && !/^```/.test(lines[i])) code.push(lines[i++]);
        i++;
        out.push('<pre><code>' + esc(code.join('\n')) + '</code></pre>');
      } else if (/^\s*$/.test(line)) {
        i++;
      } else if (/^#{1,6}\s/.test(line)) {
        var level = line.match(/^#+/)[0].length;
        out.push((level <= 2 ? '<h3>' : '<h4>') + inline(line.replace(/^#+\s*/, '')) + (level <= 2 ? '</h3>' : '</h4>'));
        i++;
      } else if (/^\s*([-*•]|\d+[.)])\s+/.test(line)) {
        var ordered = /^\s*\d+[.)]\s+/.test(line);
        var items = [];
        while (i < lines.length && /^\s*([-*•]|\d+[.)])\s+/.test(lines[i])) {
          var item = lines[i++].replace(/^\s*([-*•]|\d+[.)])\s+/, '');
          while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*•]|\d+[.)])\s+/.test(lines[i])) item += ' ' + lines[i++].trim();
          items.push('<li>' + inline(item) + '</li>');
        }
        out.push((ordered ? '<ol>' : '<ul>') + items.join('') + (ordered ? '</ol>' : '</ul>'));
      } else if (/^\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
        var cells = function (row) { return row.trim().replace(/^\||\|$/g, '').split('|'); };
        var head = cells(line).map(function (cell) { return '<th>' + inline(cell.trim()) + '</th>'; }).join('');
        var body = [];
        i += 2;
        while (i < lines.length && /^\|.*\|\s*$/.test(lines[i])) {
          body.push('<tr>' + cells(lines[i++]).map(function (cell) { return '<td>' + inline(cell.trim()) + '</td>'; }).join('') + '</tr>');
        }
        out.push('<table><tr>' + head + '</tr>' + body.join('') + '</table>');
      } else {
        var para = [];
        while (i < lines.length && !/^\s*$/.test(lines[i]) && !/^```/.test(lines[i]) && !/^#{1,6}\s/.test(lines[i]) &&
          !/^\s*([-*•]|\d+[.)])\s+/.test(lines[i])) para.push(lines[i++]);
        out.push('<p>' + para.map(inline).join('<br>') + '</p>');
      }
    }
    return out.join('');
  }

  var toastTimer = null;
  function toast(text) {
    var old = document.querySelector('.toast');
    if (old) old.remove();
    var node = h('div', { class: 'toast' }, text);
    document.body.appendChild(node);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { node.remove(); }, 2600);
  }

  /** A bottom sheet with any content; returns a function that closes it. */
  function sheet(content, onClose) {
    var back = h('div', { class: 'sheet-back' });
    var body = h('div', { class: 'sheet' }, content);
    function close() {
      back.remove();
      if (onClose) onClose();
    }
    back.addEventListener('click', function (event) { if (event.target === back) close(); });
    back.appendChild(body);
    document.body.appendChild(back);
    return close;
  }

  /** Asks before something that cannot be taken back; resolves to true or false. */
  function confirm(text, yes, danger) {
    return new Promise(function (resolve) {
      var answered = false;
      var close = sheet([
        h('p', {}, text),
        h('div', { class: 'actions' },
          h('button', { class: 'btn', onclick: function () { close(); } }, 'Отмена'),
          h('button', { class: 'btn ' + (danger ? 'danger' : 'primary'), onclick: function () { answered = true; close(); } }, yes || 'Да'))
      ], function () { resolve(answered); });
    });
  }

  /** "5 минут назад", "вчера, 14:02", "3 марта". */
  function ago(time) {
    var date = new Date(time);
    var minutes = Math.round((Date.now() - date.getTime()) / 60000);
    var lang = window.__lang || 'ru';
    var clock = date.toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' });
    if (minutes < 1) return tr('только что');
    if (minutes < 60) return t('{} мин назад', minutes);
    if (new Date().toDateString() === date.toDateString()) return t('сегодня, {}', clock);
    if (minutes < 60 * 48 && new Date(Date.now() - 864e5).toDateString() === date.toDateString()) return t('вчера, {}', clock);
    return date.toLocaleDateString(lang, { day: 'numeric', month: 'long' }) + ', ' + clock;
  }

  // Line icons, 24x24. The interface uses no emoji: every glyph comes from this set.
  // In markup: <button class="icon-btn" data-icon="back"></button>; in code: ui.icon('trash').
  // Modules and agents name their icon in module.json / agent.json. Add a shape here when
  // a screen needs a new one.
  var ICONS = {
    spark: '<path d="M12 3C12.6 8.4 15.6 11.4 21 12C15.6 12.6 12.6 15.6 12 21C11.4 15.6 8.4 12.6 3 12C8.4 11.4 11.4 8.4 12 3Z"/>',
    back: '<path d="M19 12H5M12 19l-7-7 7-7"/>',
    close: '<path d="M18 6L6 18M6 6l12 12"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/>',
    trash: '<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14M10 11v5M14 11v5"/>',
    edit: '<path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
    settings: '<path d="M4 6h8M16 6h4M4 12h2M10 12h10M4 18h10M18 18h2"/><circle cx="14" cy="6" r="2"/><circle cx="8" cy="12" r="2"/><circle cx="16" cy="18" r="2"/>',
    history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 2"/>',
    refresh: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5"/>',
    down: '<path d="M6 9l6 6 6-6"/>',
    right: '<path d="M9 6l6 6-6 6"/>',
    more: '<circle cx="5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="19" cy="12" r="1.4"/>',
    mic: '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
    send: '<path d="M12 19V5M6 11l6-6 6 6"/>',
    stop: '<rect x="7" y="7" width="10" height="10" rx="2" fill="currentColor"/>',
    clip: '<path d="M21.4 11.1l-9.2 9.2a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 0 1 5.7 5.7l-9.2 9.2a2 2 0 0 1-2.8-2.8l8.5-8.5"/>',
    undo: '<path d="M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3"/>',
    speaker: '<path d="M11 5L6 9H3v6h3l5 4zM15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13"/>',
    mute: '<path d="M11 5L6 9H3v6h3l5 4zM22 9l-6 6M16 9l6 6"/>',
    star: '<path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z"/>',
    heart: '<path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.5A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"/>',
    calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 7l9 6 9-6"/>',
    message: '<path d="M4 5h16v11H9l-5 4z"/>',
    bell: '<path d="M6 9a6 6 0 0 1 12 0c0 6 2 7 2 8H4c0-1 2-2 2-8M10 20a2 2 0 0 0 4 0"/>',
    play: '<path d="M7 4l13 8-13 8z"/>',
    pause: '<path d="M8 5v14M16 5v14"/>',
    share: '<path d="M12 15V3M8 7l4-4 4 4M5 12v7h14v-7"/>',
    download: '<path d="M12 4v11M7 11l5 5 5-5M5 20h14"/>',
    copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a1 1 0 0 1 1-1h10"/>',
    link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5v.5"/>',
    box: '<rect x="4" y="4" width="16" height="16" rx="3"/>',
    grid: '<path d="M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"/>',
    list: '<path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"/>',
    note: '<path d="M6 3h9l4 4v14H6zM14 3v5h5M9 13h6M9 17h6"/>',
    file: '<path d="M6 3h9l4 4v14H6zM14 3v5h5"/>',
    folder: '<path d="M3 6h6l2 2h10v11H3z"/>',
    book: '<path d="M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3zM5 17a3 3 0 0 1 3-3h11"/>',
    chart: '<path d="M2 20h20M5 20V10M11 20V4M17 20v-8"/>',
    wallet: '<rect x="3" y="6" width="18" height="13" rx="2"/><path d="M3 10h18M16 14.5h2"/>',
    cart: '<path d="M3 4h3l2.5 11h10L21 7H7"/><circle cx="10" cy="19" r="1.5"/><circle cx="17" cy="19" r="1.5"/>',
    tag: '<path d="M3 12V4h8l10 10-8 8z"/><circle cx="7.5" cy="8.5" r="1"/>',
    flag: '<path d="M5 21V4h12l-2 4 2 4H5"/>',
    bolt: '<path d="M13 3L5 13h6l-1 8 8-10h-6z"/>',
    home: '<path d="M4 11l8-7 8 7v9h-5v-6H9v6H4z"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
    bot: '<rect x="4" y="8" width="16" height="11" rx="3"/><path d="M12 8V5M9 13v2M15 13v2"/><circle cx="12" cy="4" r="1"/>',
    eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    image: '<rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="8.5" cy="10" r="1.5"/><path d="M21 16l-5-5-8 8"/>',
    camera: '<path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/>',
    pin: '<path d="M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11z"/><circle cx="12" cy="10" r="2.5"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
    music: '<path d="M9 18V5l11-2v13"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/>',
    phone: '<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    moon: '<path d="M20 14A8 8 0 1 1 10 4a6.5 6.5 0 0 0 10 10z"/>',
    code: '<path d="M8 7l-5 5 5 5M16 7l5 5-5 5"/>',
    dumbbell: '<path d="M4 9v6M7 6v12M17 6v12M20 9v6M7 12h10"/>',
    drop: '<path d="M12 3s6 6.4 6 11a6 6 0 0 1-12 0c0-4.6 6-11 6-11z"/>',
    pill: '<path d="M4.9 12.1l7.2-7.2a4.5 4.5 0 0 1 6.4 6.4l-7.2 7.2a4.5 4.5 0 0 1-6.4-6.4zM8.5 8.5l7 7"/>',
    steps: '<path d="M8 3c1.7 0 2.6 2 2.6 4.4S9.6 12 8 12s-2.6-2.2-2.6-4.6S6.3 3 8 3zM5.8 14.5h4.4l-.4 2.6a2 2 0 0 1-3.6 0zM16 7c1.7 0 2.6 2 2.6 4.4S17.6 16 16 16s-2.6-2.2-2.6-4.6S14.3 7 16 7zM13.8 18.5h4.4l-.4 1.6a2 2 0 0 1-3.6 0z"/>',
    smile: '<circle cx="12" cy="12" r="9"/><path d="M8.5 14.5a4.5 4.5 0 0 0 7 0M9 9.5h.01M15 9.5h.01"/>'
  };

  function icon(name) {
    var holder = document.createElement('span');
    holder.innerHTML = '<svg class="i" viewBox="0 0 24 24" aria-hidden="true">' + (ICONS[name] || ICONS.box) + '</svg>';
    return holder.firstChild;
  }

  /** Fills every element marked data-icon="name" inside root with its icon. */
  function icons(root) {
    (root || document).querySelectorAll('[data-icon]').forEach(function (node) {
      if (node.querySelector('svg.i')) return;
      node.prepend(icon(node.getAttribute('data-icon')));
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    translate(document.body);
    document.title = tr(document.title);
    icons(document);
  });

  /**
   * The icon of a module or an agent as the shell describes it (modules.list, agent.list):
   * the owner's picture, the SVG the agent drew, or a kit icon by name.
   */
  function mark(item) {
    if (item.iconImage) return h('img', { class: 'mark-img', src: item.iconImage, alt: '' });
    if (item.iconSvg) {
      var holder = document.createElement('span');
      holder.innerHTML = item.iconSvg;
      var drawn = holder.querySelector('svg');
      if (drawn) {
        drawn.classList.add('i');
        drawn.removeAttribute('width');
        drawn.removeAttribute('height');
        return drawn;
      }
    }
    return icon(item.icon);
  }

  /** Reads a picked image file and returns it as a square PNG data URL of the given size. */
  function squareImage(file, size) {
    return new Promise(function (resolve, reject) {
      var image = new Image();
      image.onload = function () {
        var canvas = document.createElement('canvas');
        canvas.width = canvas.height = size;
        var side = Math.min(image.width, image.height);
        canvas.getContext('2d').drawImage(image, (image.width - side) / 2, (image.height - side) / 2, side, side, 0, 0, size, size);
        URL.revokeObjectURL(image.src);
        resolve(canvas.toDataURL('image/png'));
      };
      image.onerror = function () { reject(new Error('Не удалось открыть картинку')); };
      image.src = URL.createObjectURL(file);
    });
  }

  /**
   * Tabs. In the page: <nav class="tabs" id="tabs"></nav> and blocks <div class="tab" data-tab="id">.
   * ui.tabs(document.getElementById('tabs'), [['id', 'Название'], …]) draws the chips, shows one
   * block at a time and remembers the choice for this screen. Returns show(id).
   */
  function tabs(bar, list, onShow) {
    var key = 'tab:' + location.pathname;
    function show(id) {
      if (!list.some(function (item) { return item[0] === id; })) id = list[0][0];
      bar.querySelectorAll('.chip').forEach(function (chip) { chip.classList.toggle('on', chip.dataset.id === id); });
      document.querySelectorAll('.tab[data-tab]').forEach(function (block) { block.hidden = block.dataset.tab !== id; });
      try { localStorage.setItem(key, id); } catch (error) { /* private mode */ }
      var chosen = bar.querySelector('.chip.on');
      if (chosen && chosen.scrollIntoView) chosen.scrollIntoView({ block: 'nearest', inline: 'center' });
      if (onShow) onShow(id);
    }
    bar.replaceChildren.apply(bar, list.map(function (item) {
      return h('button', { class: 'chip', 'data-id': item[0], onclick: function () { show(item[0]); window.scrollTo(0, 0); } }, item[1]);
    }));
    var asked = new URLSearchParams(location.search).get('tab');
    var saved = null;
    try { saved = localStorage.getItem(key); } catch (error) { /* private mode */ }
    show(asked || saved || list[0][0]);
    return show;
  }

  // The owner or the agent changed the theme: the kit's stylesheet is taken anew.
  // The language changed: the screen is drawn again in it.
  if (window.assi) {
    window.assi.on('lang', function () { location.reload(); });
    // A change that came while this screen was still loading went unheard: ask once.
    window.assi.native('lang.status').then(function (now) {
      var mine = window.__lang || 'ru';
      var tried = null;
      try { tried = sessionStorage.getItem('assi.lang.tried'); } catch (error) { /* private mode */ }
      if (!now || !now.language || now.language === mine || tried === now.language) return;
      try { sessionStorage.setItem('assi.lang.tried', now.language); } catch (error) { /* private mode */ }
      location.reload();
    }).catch(function () {});
    // The mascot was recoloured, replaced or turned off: what is drawn of it on this screen follows.
    window.assi.on('mascot', function () {
      mascotReady(true).then(function () { document.querySelectorAll('.mascot').forEach(mascotDraw); });
    });
    window.assi.on('theme', function () {
      document.querySelectorAll('link[rel="stylesheet"]').forEach(function (link) {
        if (link.href.indexOf('/ui/kit/kit.css') >= 0) link.href = '/ui/kit/kit.css?' + Date.now();
      });
    });
  }

  // ---- the instructions of a module: its optional guide.md (ui/kit/guide.js draws it) ----------
  // A screen at /modules/<id>/ui/… (or /modules/games/<id>/ui/…) has its guide at
  // /modules/<id>/guide.md. When the file is there, a book button appears in the screen's
  // .topbar by itself (<body data-guide="off"> turns that off); ui.guide() opens it from code.

  /** The folder of this screen's module ('/modules/inbox'), or null outside modules. */
  function moduleDir() {
    var p = location.pathname;
    var at = p.indexOf('/ui/');
    var dir = at > 0 ? p.slice(0, at) : '';
    return /^\/modules\/[a-z0-9-]+(\/[a-z0-9-]+)?$/.test(dir) ? dir : null;
  }

  var guideKnown = {};

  /** Whether a guide exists at path (asked from the engine, so a missing file makes no 404). */
  function guideExists(path) {
    path = path || (moduleDir() ? moduleDir() + '/guide.md' : null);
    if (!path || !window.assi || typeof window.assi.eval !== 'function') return Promise.resolve(false);
    if (guideKnown[path] !== undefined) return Promise.resolve(guideKnown[path]);
    return window.assi.eval('require("lib/assi").files.exists(' + JSON.stringify(path.replace(/^\//, '')) + ')')
      .then(function (yes) { guideKnown[path] = yes === true || yes === 'true'; return guideKnown[path]; })
      .catch(function () { return false; });
  }

  var guideScript = null;

  /** Opens the guide (the module's own, or path) in a modal over the screen. */
  function guide(path, title) {
    path = path || (moduleDir() ? moduleDir() + '/guide.md' : null);
    if (!path) return Promise.resolve(null);
    if (!guideScript) {
      guideScript = new Promise(function (resolve, reject) {
        if (window.__guide) return resolve();
        var s = document.createElement('script');
        s.src = '/ui/kit/guide.js';
        s.onload = function () { resolve(); };
        s.onerror = function () { guideScript = null; reject(new Error('guide.js')); };
        document.head.appendChild(s);
      });
    }
    return guideScript.then(function () { return window.__guide.open(path, title); });
  }

  function guideButton() {
    if (document.body && document.body.getAttribute('data-guide') === 'off') return;
    var bar = document.querySelector('.topbar');
    if (!bar || bar.querySelector('[data-guide-btn]')) return;
    guideExists().then(function (yes) {
      if (!yes || bar.querySelector('[data-guide-btn]')) return;
      var b = h('button', { class: 'icon-btn', 'data-guide-btn': '', 'aria-label': t('Инструкция'), onclick: function () { guide(); } }, icon('book'));
      var title = bar.querySelector('h1');
      if (title && title.nextSibling) bar.insertBefore(b, title.nextSibling);
      else bar.appendChild(b);
    });
  }

  if (moduleDir()) {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', guideButton);
    else setTimeout(guideButton, 0);
  }

  // ---- the mascot ---------------------------------------------------------------------------------
  // The app's character lives in the workspace like everything else: ui/mascot/mascot.json names
  // its poses (SVG files or pictures), its own colours and its phrases. The owner or the agent can
  // recolour it, put any other character in its place or turn it off ("enabled": false). So no
  // screen may depend on it: with the mascot off, ui.mascot() gives a hidden empty element and
  // ui.mascot.say() gives the plain text.
  var MASCOT = '/ui/mascot/';
  var LIGHTS = ['l1', 'r1', 'l2', 'r2', 'l3', 'r3'];
  var mascotConfig = null;
  var mascotArt = {};

  /** mascot.json, or null when there is no mascot (turned off, removed, broken). */
  function mascotReady(again) {
    if (!mascotConfig || again) {
      if (again) mascotArt = {};
      mascotConfig = fetch(MASCOT + 'mascot.json?' + Date.now())
        .then(function (response) { return response.ok ? response.json() : null; })
        .then(function (config) { return config && config.enabled !== false && config.poses && Object.keys(config.poses).length ? config : null; })
        .catch(function () { return null; });
    }
    return mascotConfig;
  }

  function mascotFile(file) {
    if (!mascotArt[file]) {
      mascotArt[file] = fetch(MASCOT + file + '?' + Date.now())
        .then(function (response) { return response.ok ? response.text() : null; })
        .catch(function () { return null; });
    }
    return mascotArt[file];
  }

  function mascotLights(node) {
    var count = Math.max(0, Math.min(LIGHTS.length, Number(node.dataset.lights) || 0));
    var fronds = node.querySelectorAll('[data-gill]');
    fronds.forEach(function (frond) { frond.classList.toggle('lit', LIGHTS.indexOf(frond.getAttribute('data-gill')) < count && LIGHTS.indexOf(frond.getAttribute('data-gill')) >= 0); });
    // A character without gills shows the same count as dots under it.
    var dots = node.querySelector('.mascot-dots');
    if (dots) dots.remove();
    if (count && !fronds.length && node.firstChild) {
      dots = h('span', { class: 'mascot-dots' });
      for (var i = 0; i < count; i++) dots.appendChild(h('i', {}));
      node.appendChild(dots);
    }
  }

  function mascotDraw(node) {
    var asked = node.dataset.pose;
    return mascotReady().then(function (config) {
      if (!config) { node.hidden = true; node.replaceChildren(); return null; }
      var file = config.poses[asked] || config.poses.idle || config.poses[Object.keys(config.poses)[0]];
      // The mascot's own colours go over whatever the theme gives it.
      Object.keys(config.colors || {}).forEach(function (key) {
        if (/^[a-z][a-z0-9-]*$/.test(key) && config.colors[key]) node.style.setProperty('--mascot-' + key, String(config.colors[key]));
      });
      var drawn = /\.svg$/i.test(file) ? mascotFile(file) : Promise.resolve(null);
      return drawn.then(function (svg) {
        if (node.dataset.pose !== asked) return config;
        if (svg && svg.indexOf('<svg') >= 0) {
          var holder = document.createElement('span');
          holder.innerHTML = svg;
          holder.querySelectorAll('script').forEach(function (script) { script.remove(); });
          node.replaceChildren(holder.querySelector('svg'));
        } else {
          node.replaceChildren(h('img', { src: MASCOT + file, alt: '' }));
        }
        node.hidden = false;
        // "alive": false in mascot.json leaves a still picture: no blinking, breathing or hopping.
        node.classList.toggle('alive', config.alive !== false);
        if (config.alive !== false) mascotLife();
        node.classList.remove('pop');
        void node.offsetWidth;
        node.classList.add('pop');
        mascotLights(node);
        return config;
      });
    });
  }

  var BLINK_EVERY_MS = 10000;  // a blink takes a third of a second
  var BREATH_EVERY = 15;       // one breath (or one bob over the edge) in place of every fifteenth blink
  var lifeTimer = null;
  var lifeTick = 0;

  /**
   * The life of the lively mascots on the page: a blink now and then, a breath more rarely, and
   * stillness between them. One timer for the page; a page nobody sees does nothing.
   */
  function mascotLife() {
    if (lifeTimer) return;
    lifeTimer = setInterval(function () {
      if (document.visibilityState === 'hidden') return;
      var nodes = document.querySelectorAll('.mascot.alive');
      if (!nodes.length) { clearInterval(lifeTimer); lifeTimer = null; return; }
      var move = ++lifeTick % BREATH_EVERY === 0 ? 'breath' : 'blink';
      nodes.forEach(function (node) { node.classList.add(move); });
      setTimeout(function () {
        nodes.forEach(function (node) { node.classList.remove(move); });
      }, move === 'breath' ? 4000 : 500);
    }, BLINK_EVERY_MS);
  }

  /** A tap on a lively mascot: a happy pose and a wiggle for a moment, then back to what it was. */
  function mascotReact(node) {
    if (node.dataset.reacting || !node.classList.contains('alive')) return;
    var back = node.dataset.pose;
    var moods = ['love', 'celebrating', 'hello'].filter(function (pose) { return pose !== back; });
    node.dataset.reacting = '1';
    mascot.pose(node, moods[Math.floor(Math.random() * moods.length)]).then(function () { node.classList.add('wiggle'); });
    setTimeout(function () {
      delete node.dataset.reacting;
      node.classList.remove('wiggle');
      mascot.pose(node, back);
    }, 1800);
  }

  /**
   * The mascot in a pose: ui.mascot('hello', { size: 120 }). Poses of the factory character:
   * idle, hello, point, listening, thinking, searching, working, building, plan, asking, oops,
   * celebrating, love, sleeping, safe, name, head, peek (a pose the character lacks falls back
   * to idle). `lights` (0–6) lights its gills one by one — how many agents are at work.
   * `lively: true` lets it answer a tap. The element is hidden until drawn and stays hidden with
   * the mascot off.
   */
  function mascot(pose, options) {
    options = options || {};
    var node = h('span', { class: 'mascot', hidden: true, 'data-pose': pose || 'idle', 'data-lights': options.lights || 0 });
    if (options.size) node.style.width = options.size + 'px';
    if (options.lively) node.addEventListener('click', function () { mascotReact(node); });
    mascotDraw(node);
    return node;
  }

  /**
   * The mascot climbing up from behind an edge and holding on to it: two layers, the body that
   * rises behind the edge (pose "peek-body") and the paws that stay on it ("peek-paw", drawn
   * twice). The edge is the bottom of the element — put it on top of a card, a bar, the screen's
   * rim. States (ui.mascot.climb(node, state)): 'hidden', 'peek' — just its eyes over the edge, 'up' —
   * out to the shoulders (its paws are short). `node.ready` resolves to false, and the element stays hidden, when the mascot is
   * off or has no such poses: then the place is simply empty.
   */
  mascot.climber = function (options) {
    options = options || {};
    var body = mascot('peek-body');
    var node = h('span', { class: 'climber', hidden: true, 'data-state': options.state || 'peek' },
      h('span', { class: 'climber-body' }, body),
      h('span', { class: 'climber-paw left' }, mascot('peek-paw')),
      h('span', { class: 'climber-paw right' }, mascot('peek-paw')));
    if (options.width) node.style.setProperty('--climber', options.width + 'px');
    node.body = body;
    node.ready = mascotReady().then(function (config) {
      var has = Boolean(config && config.poses['peek-body'] && config.poses['peek-paw']);
      node.hidden = !has;
      node.classList.toggle('alive', has && config.alive !== false);
      return has;
    });
    return node;
  };
  mascot.climb = function (node, state) { node.dataset.state = state; };

  mascot.ready = mascotReady;
  /** Changes the pose of a mascot element; resolves when it is drawn. */
  mascot.pose = function (node, pose) {
    if (node.dataset.pose === pose && node.firstChild) return Promise.resolve();
    node.dataset.pose = pose;
    return mascotDraw(node);
  };
  mascot.lights = function (node, count) {
    node.dataset.lights = count || 0;
    mascotLights(node);
  };
  /** The mascot's phrase for the occasion — or `plain`, the screen's own words, when it has none. */
  mascot.say = function (key, plain) {
    return mascotReady().then(function (config) {
      var phrase = config && config.phrases && config.phrases[key];
      return typeof phrase === 'string' && phrase.trim() ? phrase : plain;
    });
  };

  // ---- a tour of a screen ---------------------------------------------------------------------------
  // The screen goes dim except for the thing being explained, and a card beside it says what it
  // is — step by step. With a mascot, it holds on to the card's edge.

  // Where the chat button is on every screen: the shell draws it over the page's bottom right corner.
  var CHAT_BUTTON = { right: 2, bottom: 0, width: 120, height: 100 };
  // The shell's native hit area is above this WebView, so CSS z-index cannot cover it.
  // Reserve the full 132dp face, including the plain button's margin, on phone pages.
  var CHAT_CLEARANCE = 132;
  var TOURS_DONE = 'tours.done';

  /** What a step points at, as a box in the window — or null for a step about nothing in particular. */
  function tourBox(step) {
    var target = typeof step.target === 'function' ? step.target() : step.target;
    if (typeof target === 'string') target = document.querySelector(target);
    if (!target) return null;
    if (target.getBoundingClientRect) {
      if (target.scrollIntoView) target.scrollIntoView({ block: 'center' });
      var box = target.getBoundingClientRect();
      return box.width || box.height ? { x: box.left, y: box.top, w: box.width, h: box.height } : null;
    }
    // A place given by its distance from the window's sides.
    var w = target.width === undefined ? window.innerWidth - (target.left || 0) - (target.right || 0) : target.width;
    var hgt = target.height === undefined ? window.innerHeight - (target.top || 0) - (target.bottom || 0) : target.height;
    return {
      x: target.left !== undefined ? target.left : window.innerWidth - target.right - w,
      y: target.top !== undefined ? target.top : window.innerHeight - target.bottom - hgt, w: w, h: hgt
    };
  }

  /**
   * Shows the steps one by one: ui.tour([{ target: '#modules', title: 'Модули', text: '…' }, …]).
   * `target` is a selector, an element, a function that gives one, a place by its distance from
   * the window's sides ({ right: 0, bottom: 0, width: 120, height: 100 }), or nothing — then the
   * card stands in the middle. Resolves to true when the owner reached the end, false when they
   * skipped it.
   */
  function tour(steps, options) {
    options = options || {};
    // A step about something that is not on the screen now (a hidden section, an empty list) is
    // left out; so is a step about the phone's own parts (phone: true — the chat button, the
    // menu bar) when the screen is shown in the hub on a computer.
    var web = Boolean(window.assi && window.assi.platform === 'web');
    steps = steps.filter(function (step) {
      if (step.phone && web) return false;
      if (typeof step.target !== 'string') return true;
      var found = document.querySelector(step.target);
      return Boolean(found && (found.offsetWidth || found.offsetHeight));
    });
    return new Promise(function (resolve) {
      if (!steps.length) return resolve(true);
      var index = 0;
      var hole = h('div', { class: 'tour-hole' });
      var title = h('h3', {});
      var text = h('p', {});
      var count = h('span', { class: 'tour-count' });
      var back = h('button', { class: 'btn small', onclick: function () { go(index - 1); } }, 'Назад');
      var next = h('button', { class: 'btn primary small', onclick: function () { go(index + 1); } }, 'Далее');
      var skip = h('button', { class: 'tour-skip', onclick: function () { end(false); } }, 'Пропустить');
      var climber = mascot.climber({ width: 82, state: 'hidden' });
      var copy = h('div', { class: 'tour-copy', tabindex: '0' }, title, text);
      var card = h('div', { class: 'tour-card' }, climber, copy,
        h('div', { class: 'tour-row' }, h('div', { class: 'tour-meta' }, skip, count),
          h('div', { class: 'tour-actions' }, back, next)));
      var layer = h('div', { class: 'tour', role: 'dialog', 'aria-modal': 'true' }, hole, card);
      var hop = null;
      var resize = null;

      function place() {
        var box = tourBox(steps[index]);
        var pad = 6;
        var view = { w: window.innerWidth, h: window.innerHeight };
        if (box) {
          hole.style.cssText = 'left:' + (box.x - pad) + 'px;top:' + (box.y - pad) + 'px;width:' + (box.w + 2 * pad) + 'px;height:' + (box.h + 2 * pad) + 'px';
        } else {
          hole.style.cssText = 'left:' + view.w / 2 + 'px;top:' + view.h / 2 + 'px;width:0;height:0';
        }
        // Keep every control outside the native chat button, not just the highlighted target.
        // A centred desktop/tablet card may already stand to its left and needs no lower inset.
        var width = Math.min(420, view.w - 28);
        var chatOverlaps = !web && (view.w + width) / 2 > view.w - CHAT_CLEARANCE;
        var bottom = view.h - 12 - (chatOverlaps ? CHAT_CLEARANCE : 0);
        var room = climber.hidden ? 12 : 58;
        var compact = bottom - room < 160;
        var beside = compact && chatOverlaps && view.w - CHAT_CLEARANCE - 26 >= 160;
        card.classList.toggle('tour-compact', compact);
        card.style.right = (beside ? CHAT_CLEARANCE + 12 : 14) + 'px';
        card.style.marginLeft = beside ? '0' : '';
        card.style.marginRight = beside ? '0' : '';
        if (beside) bottom = view.h - 12;
        if (compact) room = 12;
        card.style.maxHeight = Math.max(44, bottom - room) + 'px';
        var height = card.offsetHeight;
        var top = room + (bottom - room - height) / 2;
        if (box) {
          var under = box.y + box.h + pad + 14 + (climber.hidden || compact ? 0 : 46);
          var over = box.y - pad - 14 - height;
          top = under + height <= bottom ? under : over >= room ? over : bottom - height;
        }
        card.style.top = Math.max(room, Math.min(top, bottom - height)) + 'px';
      }

      function go(to) {
        if (to < 0) return;
        if (to >= steps.length) return end(true);
        index = to;
        var step = steps[index];
        title.textContent = step.title || '';
        title.hidden = !step.title;
        text.textContent = step.text;
        copy.scrollTop = 0;
        count.textContent = (index + 1) + ' / ' + steps.length;
        back.hidden = index === 0;
        next.textContent = index === steps.length - 1 ? (options.done || 'Понятно') : 'Далее';
        place();
        // What scrolled into view settles in the next frame.
        requestAnimationFrame(place);
        // The mascot leans on the card all along; with every step it ducks and comes up again.
        mascot.climb(climber, 'peek');
        clearTimeout(hop);
        hop = setTimeout(function () { mascot.climb(climber, 'up'); }, 180);
      }

      function end(reached) {
        clearTimeout(hop);
        window.removeEventListener('resize', place);
        if (resize) resize.disconnect();
        layer.remove();
        resolve(reached);
      }

      document.body.appendChild(layer);
      window.addEventListener('resize', place);
      if (window.ResizeObserver) {
        resize = new ResizeObserver(place);
        resize.observe(card);
      }
      climber.ready.then(place);
      go(0);
    });
  }

  function toursDone() {
    return window.assi.native('pref.get', TOURS_DONE, '').then(function (done) { return String(done || '').split(',').filter(Boolean); });
  }

  /**
   * The tour of a screen for an owner who has not seen it: ui.tour.offer('home', steps). It is
   * shown once; its last step always says that anything unclear can be asked in the chat
   * (`askAt` — what that step points at; the chat button by default). An owner who skips a tour
   * is not offered the others; "Обучение" in the settings brings them all back.
   */
  tour.offer = function (key, steps, options) {
    options = options || {};
    // Not for a page framed in another — a card of the home screen. The hub on a computer frames
    // screens too, but there they are the screen itself: its own two frames do get their tours.
    var web = Boolean(window.assi && window.assi.platform === 'web');
    var framed = window.top !== window;
    var inHub = false;
    try { inHub = framed && web && window.parent === window.top && Boolean(window.top.__assiTransportRoot); } catch (error) { /* another origin */ }
    if (!window.assi || (framed && !inHub)) return Promise.resolve(false);
    // Nor on the agent's own screen: a tour is for the owner, and it would cover what the agent checks.
    if (window.assiProbe) return Promise.resolve(false);
    // One tour at a time: a screen inside the hub waits until the hub has finished its own.
    var turn = !inHub ? Promise.resolve() : new Promise(function (resolve) {
      (function wait() {
        var busy = false;
        try { busy = Boolean(window.top.document.querySelector('.tour')); } catch (error) { /* another origin */ }
        if (busy) setTimeout(wait, 1000); else resolve();
      })();
    });
    // The hub asks first: its frames give it a moment to start.
    return (inHub ? new Promise(function (resolve) { setTimeout(resolve, 1500); }) : Promise.resolve()).then(function () { return turn; }).then(toursDone).then(function (done) {
      if (done.indexOf(key) >= 0 || done.indexOf('*') >= 0) return false;
      var ask = {
        // In the hub the chat is a panel beside the screen, not a button over it.
        target: options.askAt === undefined ? (web ? null : CHAT_BUTTON) : options.askAt,
        title: 'Остались вопросы?',
        text: 'Если что-то непонятно — спросите в чате: про любой экран и любую кнопку. Там же можно попросить переделать то, что неудобно.'
      };
      return tour(steps.concat([ask]), options).then(function (reached) {
        return toursDone().then(function (now) {
          var list = reached ? now.concat(key) : ['*'];
          if (!reached) toast('Обучение можно пройти заново в настройках');
          return window.assi.native('pref.set', TOURS_DONE, list.join(','));
        }).then(function () { return reached; });
      });
    }).catch(function () { return false; });
  };
  /** Every screen offers its tour again. */
  tour.reset = function () { return window.assi.native('pref.set', TOURS_DONE, ''); };
  tour.chatButton = CHAT_BUTTON;

  window.ui = {
    h: h, esc: esc, md: md, toast: toast, sheet: sheet, confirm: confirm, ago: ago, tabs: tabs, t: t, raw: raw,
    icon: icon, icons: icons, iconNames: Object.keys(ICONS), mark: mark, squareImage: squareImage,
    guide: guide, guideExists: guideExists, mascot: mascot, tour: tour
  };
})();

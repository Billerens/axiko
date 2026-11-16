// The instructions of a module: its optional guide.md, written by the agent in sections, shown
// over the screen in a modal — contents of the sections, headings, lists (nested, with ticks),
// tables (with alignment), notes (> **Совет:** …), code, pictures, rules, and diagrams from
// ```mermaid blocks (flowchart, sequence, state, pie, timeline, mindmap…). kit.js loads this
// file on the first ui.guide() call; the module itself does nothing.
(function () {
  'use strict';

  const MERMAID = 'https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js';
  const NOTES = { 'совет': 'tip', 'подсказка': 'tip', 'важно': 'warn', 'внимание': 'warn', 'осторожно': 'warn', 'пример': 'note', 'заметка': 'note' };
  const esc = (text) => String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

  /** A link or a picture address: https, a page anchor, or a file next to guide.md. */
  function address(src, base) {
    src = String(src || '').trim();
    if (/^https:\/\//.test(src) || /^#[^\s/#]+$/.test(src)) return src;
    if (/^[a-z]+:/i.test(src) || src.indexOf('..') >= 0) return null;
    return src.charAt(0) === '/' ? src : base + src;
  }

  function inline(text, base) {
    const codes = [];
    let s = String(text).replace(/`([^`]+)`/g, (m, c) => { codes.push(c); return '\u0000' + (codes.length - 1) + '\u0000'; });
    s = esc(s)
      .replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (m, alt, src) => {
        const a = address(src.replace(/&amp;/g, '&'), base);
        return a ? '<img class="gd-img" src="' + esc(a) + '" alt="' + alt + '" loading="lazy">' : '';
      })
      .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (m, label, href) => {
        const a = address(href.replace(/&amp;/g, '&'), base);
        return a ? '<a href="' + esc(a) + '"' + (a.charAt(0) === '#' ? ' data-anchor' : '') + '>' + label + '</a>' : label;
      })
      .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
      .replace(/__([^_]+)__/g, '<b>$1</b>')
      .replace(/(^|[\s(«])\*([^*\s][^*]*)\*/g, '$1<i>$2</i>')
      .replace(/(^|[\s(«])_([^_\s][^_]*)_/g, '$1<i>$2</i>')
      .replace(/~~([^~]+)~~/g, '<s>$1</s>')
      .replace(/==([^=]+)==/g, '<mark>$1</mark>');
    return s.replace(/\u0000(\d+)\u0000/g, (m, i) => '<code>' + esc(codes[Number(i)]) + '</code>');
  }

  const LIST = /^(\s*)([-*+•]|\d+[.)])\s+(.*)$/;

  /** A list from line i at indent: nested lists by deeper indent, [ ] / [x] ticks. → [html, next i]. */
  function list(lines, i, base) {
    const first = LIST.exec(lines[i]);
    const indent = first[1].length;
    const ordered = /\d/.test(first[2]);
    const items = [];
    while (i < lines.length) {
      const m = LIST.exec(lines[i]);
      if (!m || m[1].length < indent) break;
      // A list of the other kind (numbers after dashes) at the same depth starts its own list.
      if (m[1].length === indent && /\d/.test(m[2]) !== ordered) break;
      if (m[1].length > indent) {
        const [inner, next] = list(lines, i, base);
        if (items.length) items[items.length - 1].inner += inner;
        i = next;
        continue;
      }
      let text = m[3];
      i++;
      while (i < lines.length && /^\s+\S/.test(lines[i]) && !LIST.test(lines[i])) text += ' ' + lines[i++].trim();
      const tick = /^\[([ xXхХ])\]\s+/.exec(text);
      if (tick) text = text.slice(tick[0].length);
      items.push({ text, tick: tick ? tick[1] !== ' ' : null, inner: '' });
    }
    const html = items.map((it) => '<li' + (it.tick === null ? '' : ' class="gd-task' + (it.tick ? ' done' : '') + '"') + '>' +
      (it.tick === null ? '' : '<span class="gd-tick"></span>') + inline(it.text, base) + it.inner + '</li>').join('');
    return [(ordered ? '<ol>' : '<ul>') + html + (ordered ? '</ol>' : '</ul>'), i];
  }

  function table(lines, i, base) {
    const cells = (row) => row.trim().replace(/^\||\|$/g, '').split(/(?<!\\)\|/).map((c) => c.replace(/\\\|/g, '|').trim());
    const head = cells(lines[i]);
    const align = cells(lines[i + 1]).map((c) => (/^:-+:$/.test(c) ? 'center' : /-+:$/.test(c) ? 'right' : ''));
    const style = (k) => (align[k] ? ' style="text-align:' + align[k] + '"' : '');
    const rows = [];
    i += 2;
    while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) rows.push(cells(lines[i++]));
    return ['<div class="gd-table"><table><thead><tr>' + head.map((c, k) => '<th' + style(k) + '>' + inline(c, base) + '</th>').join('') +
      '</tr></thead><tbody>' + rows.map((r) => '<tr>' + head.map((x, k) => '<td' + style(k) + '>' + inline(r[k] || '', base) + '</td>').join('') + '</tr>').join('') +
      '</tbody></table></div>', i];
  }

  /** The markdown of a guide → {title, sections: [{id, title}], html}. base — the folder of guide.md. */
  function render(source, base) {
    const lines = String(source || '').replace(/\r/g, '').split('\n');
    const out = [];
    const sections = [];
    let title = '';
    let i = 0;
    const slug = (text) => 'gd-' + (sections.length + 1);
    while (i < lines.length) {
      const line = lines[i];
      let m;
      if ((m = /^\s*```\s*([\w-]*)/.exec(line))) {
        const code = [];
        i++;
        while (i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i++]);
        i++;
        if (m[1] === 'mermaid') out.push('<div class="gd-diagram" data-src="' + esc(code.join('\n')) + '"><pre><code>' + esc(code.join('\n')) + '</code></pre></div>');
        else out.push('<pre><code>' + esc(code.join('\n')) + '</code></pre>');
      } else if (/^\s*$/.test(line)) {
        i++;
      } else if ((m = /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line))) {
        const level = m[1].length;
        i++;
        if (level === 1 && !title) { title = m[2]; continue; }
        if (level <= 2) {
          const id = slug(m[2]);
          sections.push({ id, title: m[2] });
          out.push('<h2 id="' + id + '">' + inline(m[2], base) + '</h2>');
        } else out.push((level === 3 ? '<h3>' : '<h4>') + inline(m[2], base) + (level === 3 ? '</h3>' : '</h4>'));
      } else if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
        out.push('<hr>');
        i++;
      } else if (/^\s*>/.test(line)) {
        const inner = [];
        while (i < lines.length && /^\s*>/.test(lines[i])) inner.push(lines[i++].replace(/^\s*>\s?/, ''));
        const head = /^\s*\*\*([^*:]+):?\*\*:?\s*/.exec(inner[0] || '');
        const kind = head && NOTES[head[1].trim().toLowerCase()];
        out.push('<blockquote class="gd-note' + (kind ? ' ' + kind : '') + '">' + render(inner.join('\n'), base).html + '</blockquote>');
      } else if (LIST.test(line)) {
        const [html, next] = list(lines, i, base);
        out.push(html);
        i = next;
      } else if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
        const [html, next] = table(lines, i, base);
        out.push(html);
        i = next;
      } else {
        const para = [];
        while (i < lines.length && !/^\s*$/.test(lines[i]) && !/^\s*```/.test(lines[i]) && !/^#{1,6}\s/.test(lines[i]) &&
          !LIST.test(lines[i]) && !/^\s*>/.test(lines[i]) && !/^\s*\|.*\|\s*$/.test(lines[i])) para.push(lines[i++]);
        if (!para.length) para.push(lines[i++]);
        out.push('<p>' + para.map((p) => inline(p, base)).join('<br>') + '</p>');
      }
    }
    return { title, sections, html: out.join('') };
  }

  // ---- diagrams --------------------------------------------------------------------------------------

  let mermaidReady = null;

  function loadMermaid() {
    if (mermaidReady) return mermaidReady;
    mermaidReady = new Promise((resolve, reject) => {
      if (window.mermaid) return resolve(window.mermaid);
      const s = document.createElement('script');
      s.src = MERMAID;
      s.onload = () => resolve(window.mermaid);
      s.onerror = () => { mermaidReady = null; reject(new Error('нет сети для схем')); };
      document.head.appendChild(s);
    }).then((m) => {
      const css = getComputedStyle(document.documentElement);
      const v = (name, fallback) => (css.getPropertyValue(name) || fallback).trim();
      m.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        theme: 'base',
        fontFamily: 'system-ui, Roboto, sans-serif',
        themeVariables: {
          darkMode: true,
          background: v('--surface', '#1A1A1A'),
          primaryColor: v('--surface-2', '#252525'),
          primaryTextColor: v('--ink', '#EDEDED'),
          primaryBorderColor: v('--accent', '#8B7CFF'),
          secondaryColor: v('--accent-soft', '#24213A'),
          tertiaryColor: v('--surface', '#1A1A1A'),
          lineColor: v('--ink-2', '#A6A6A6'),
          textColor: v('--ink', '#EDEDED'),
          noteBkgColor: v('--accent-soft', '#24213A'),
          noteTextColor: v('--ink', '#EDEDED'),
          edgeLabelBackground: v('--surface-2', '#252525'),
          clusterBkg: v('--surface', '#1A1A1A'),
          clusterBorder: v('--line', '#2E2E2E')
        }
      });
      return m;
    });
    return mermaidReady;
  }

  async function diagrams(root) {
    const list = Array.from(root.querySelectorAll('.gd-diagram'));
    if (!list.length) return;
    let m;
    try {
      m = await loadMermaid();
    } catch (error) {
      list.forEach((d) => d.classList.add('offline'));
      return;
    }
    for (let k = 0; k < list.length; k++) {
      const d = list[k];
      try {
        const r = await m.render('gd-mmd-' + Date.now().toString(36) + k, d.dataset.src);
        d.innerHTML = r.svg;
        // Natural size, not squeezed into a phone's width: a wide diagram scrolls sideways.
        const svg = d.querySelector('svg');
        const vb = svg && svg.viewBox && svg.viewBox.baseVal;
        if (vb && vb.width) {
          svg.removeAttribute('width');
          svg.style.maxWidth = 'none';
          // A little wider than the screen: fitted; much wider: natural size, scrolled sideways.
          const room = d.clientWidth - 20;
          svg.style.width = Math.round(vb.width > room && vb.width <= room * 1.5 ? room : vb.width) + 'px';
          svg.style.height = 'auto';
        }
        d.classList.add('drawn');
      } catch (error) {
        d.classList.add('broken');
      }
    }
  }

  // ---- the modal -------------------------------------------------------------------------------------

  /** Opens the guide at path (a URL of the workspace: /modules/<id>/guide.md). */
  async function open(path, fallbackTitle) {
    const ui = window.ui;
    let text = null;
    try {
      const r = await fetch(path, { cache: 'no-store' });
      text = r.ok ? await r.text() : null;
    } catch (error) {
      text = null;
    }
    if (!text) {
      ui.toast(ui.t('Инструкции пока нет'));
      return null;
    }
    const base = path.replace(/[^/]*$/, '');
    const doc = render(text, base);
    const body = ui.h('div', { class: 'guide-md md' });
    body.innerHTML = doc.html;
    const back = ui.h('div', { class: 'guide-back', role: 'dialog', 'aria-modal': 'true' });
    const closeBtn = ui.h('button', { class: 'icon-btn', 'aria-label': ui.t('Закрыть') }, ui.icon('close'));
    const toc = doc.sections.length > 1
      ? ui.h('nav', { class: 'guide-toc' }, doc.sections.map((s) => ui.h('button', { class: 'chip', 'data-to': s.id }, s.title)))
      : null;
    const panel = ui.h('div', { class: 'guide' },
      ui.h('header', { class: 'guide-head' }, ui.h('h2', { class: 'grow' }, doc.title || fallbackTitle || ui.t('Инструкция')), closeBtn),
      toc, body);
    back.appendChild(panel);
    function close() {
      back.remove();
      document.removeEventListener('keydown', onKey);
    }
    function onKey(event) { if (event.key === 'Escape') close(); }
    function jump(id) {
      const target = body.querySelector('#' + id);
      if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
    closeBtn.addEventListener('click', close);
    back.addEventListener('click', (event) => { if (event.target === back) close(); });
    document.addEventListener('keydown', onKey);
    if (toc) toc.addEventListener('click', (event) => { const b = event.target.closest('[data-to]'); if (b) jump(b.dataset.to); });
    body.addEventListener('click', (event) => {
      const a = event.target.closest('a');
      if (!a) return;
      if (a.hasAttribute('data-anchor')) {
        event.preventDefault();
        const name = a.getAttribute('href').slice(1);
        const found = doc.sections.find((s) => s.title.toLowerCase().replace(/\s+/g, '-') === name.toLowerCase()) || doc.sections.find((s) => s.id === name);
        if (found) jump(found.id);
      }
    });
    document.body.appendChild(back);
    diagrams(body);
    return close;
  }

  window.__guide = { open, render };
})();

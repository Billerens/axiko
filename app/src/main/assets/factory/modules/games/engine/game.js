// The screen part of the game engine: window.Game. A game's screen loads the kit, the shared
// files (big, fmt, rng, core), icons.js, sfx.js, this file, its sim.js and its own game.js, then:
//
//   const g = await Game.start({ id: 'subway', sim: window.GameSim, render, animate });
//
// The engine keeps the state, the clock (wall time, fixed steps), saves (SQLite through
// modules/games/main.js plus a localStorage mirror), offline progress with the sheet «Пока вас
// не было», the inbox of the life links, prestige, achievements, sound and vibration, the frame
// of the screen (.g-bar, .g-top, .g-field, .g-actions). What a game calls is listed in AGENT.md.
(function () {
  'use strict';

  var MAIN = 'modules/games/main.js';
  var h = function () { return window.ui.h.apply(null, arguments); };

  // In the frame of ui/preview.html the link to the app may be only the parent's (same origin).
  if (!window.axiko && window.parent && window.parent !== window) {
    try {
      window.axiko = window.parent.axiko;
    } catch (ignored) { /* another origin */ }
  }
  var noop = function () {};

  var PATTERNS = {
    tap: [8], buy: [15], deny: [10, 40, 10], achievement: [20, 60, 40], prestige: [60, 40, 120],
    chest: [30, 50, 30], level: [20, 40, 20], alarm: [200, 100, 200]
  };

  var REASONS = {
    auto: 'автосохранение', action: 'действие', hide: 'выход с экрана', exit: 'выход из игры',
    prestige: 'престиж', 'before-prestige': 'копия перед престижем', 'before-migrate': 'копия перед обновлением игры',
    'before-offline': 'копия перед подсчётом офлайна', 'before-restore': 'копия перед возвратом',
    'before-reset': 'копия перед новой игрой', 'before-import': 'копия перед импортом', restore: 'возврат сохранения',
    reset: 'новая игра', import: 'импорт', migrate: 'обновление игры', offline: 'офлайн-доход', achievement: 'ачивка',
    chest: 'сундук', stock: 'запас контента', spend: 'трата энергии', inbox: 'начисление связки', mirror: 'из запасной копии',
    start: 'начало игры', check: 'проверка'
  };

  function call(fn) {
    var args = [MAIN, fn].concat(Array.prototype.slice.call(arguments, 1));
    return window.axiko.call.apply(window.axiko, args);
  }

  function frame() {
    return new Promise(function (resolve) { setTimeout(resolve, 0); });
  }

  function reasonText(reason) {
    var r = String(reason || '');
    var broken = / \(повреждено\)$/.test(r);
    var base = r.replace(/ \(повреждено\)$/, '');
    return (REASONS[base] || base) + (broken ? ' (повреждено)' : '');
  }

  function line(k, v) {
    return h('div', { class: 'g-line' }, h('span', { class: 'k' }, k), h('span', { class: 'v' }, v === undefined || v === null ? '' : v));
  }

  function note(text) { return h('p', { class: 'g-note' }, text); }

  function sheet(content, onClose) {
    return window.ui.sheet(h('div', { class: 'g-sheet' }, content), onClose);
  }

  function hhmm(ms) {
    var d = new Date(ms);
    return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
  }

  function dayText(ms) {
    var d = new Date(ms);
    return d.getDate() + '.' + ('0' + (d.getMonth() + 1)).slice(-2) + ', ' + hhmm(ms);
  }

  /**
   * A short flash at the bottom (an achievement, a chest, a note), 3 seconds. The icon is a
   * GameIcons name, the game's own picture as SVG markup, or a ready element.
   */
  function flashIcon(icon) {
    if (icon && icon.nodeType === 1) return icon;
    if (typeof icon === 'string' && icon.charAt(0) === '<' && window.GameArt) return window.GameArt.el(icon, { size: 32, cls: 'g-flash-pic' });
    return window.GameIcons ? window.GameIcons.el(icon || 'star') : window.ui.icon(icon || 'star');
  }

  function flash(icon, title, text, ms) {
    var old = document.querySelector('.g-flash');
    if (old) old.remove();
    var node = h('div', { class: 'g-flash', role: 'status' },
      flashIcon(icon),
      h('div', { class: 'grow' }, h('div', { class: 't' }, title), text ? h('div', { class: 'small muted' }, text) : null));
    document.body.appendChild(node);
    setTimeout(function () { node.remove(); }, ms || 3000);
    return node;
  }

  /**
   * A button that fires only when held for `ms` (in a carriage it shakes): a fill grows inside
   * it; letting go earlier cancels. hold(button, 1200, fn) returns the button.
   */
  function hold(button, ms, fn) {
    var fill = h('i', { class: 'g-hold-fill' });
    button.appendChild(fill);
    var startedAt = 0;
    var raf = 0;
    function stop() {
      cancelAnimationFrame(raf);
      raf = 0;
      fill.style.width = '0';
    }
    function step() {
      var p = Math.min(1, (Date.now() - startedAt) / ms);
      fill.style.width = (p * 100) + '%';
      if (p >= 1) {
        stop();
        fn();
        return;
      }
      raf = requestAnimationFrame(step);
    }
    button.addEventListener('pointerdown', function (event) {
      if (button.disabled) return;
      event.preventDefault();
      startedAt = Date.now();
      raf = requestAnimationFrame(step);
    });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(function (name) { button.addEventListener(name, stop); });
    button.addEventListener('contextmenu', function (event) { event.preventDefault(); });
    // Checks press it without holding: button.__hold().
    button.__hold = fn;
    return button;
  }

  function energyWord(n) { return window.Fmt.plural(n, 'энергия', 'энергии', 'энергии'); }

  // ---- the sheets of the life links (the catalog uses them too) --------------------------------

  async function linkSheet(name, gameId, o) {
    o = o || {};
    if (name === 'bet') return betSheet(gameId, o);
    var info = await call('linkSheet', name, gameId || null);
    var close = null;
    var rows = [h('h3', {}, info.title)];
    if (name === 'passenger') {
      rows.push(h('div', { class: 'g-big', style: 'font-size: 22px' }, info.text));
      rows.push(h('div', { class: 'g-lines' }, info.signs.map(function (s) { return h('div', { class: 'g-line' }, h('span', { class: 'k' }, s)); })));
      rows.push(note('В поездке доход в играх ' + window.Fmt.mult(info.mult) + ', и офлайн тоже. Сундук — за поездку от ' + info.chestMin +
        ' мин, не больше ' + info.chestsPerDay + ' в день (сегодня ' + info.chestsToday + ').'));
      if (info.trips.length) {
        rows.push(h('h2', {}, 'Последние поездки'));
        rows.push(h('div', { class: 'g-lines' }, info.trips.map(function (t) {
          return line(dayText(t.start), t.min + ' мин' + (t.chest ? ', сундук' : ''));
        })));
      }
      rows.push(h('div', { class: 'g-btns' },
        h('button', { class: 'g-btn small' + (info.state === 'trip' ? '' : ' primary'), id: 'g-trip-on', onclick: function () { manual(true); } }, 'Я еду'),
        h('button', { class: 'g-btn small', id: 'g-trip-off', onclick: function () { manual(false); } }, 'Я не еду')));
    } else if (name === 'steps') {
      rows.push(h('div', { class: 'g-lines' },
        line('Шаги сегодня', window.Fmt.num(info.steps)),
        line('Энергия', info.energy + ' из ' + info.cap),
        line('Получено сегодня', info.granted + ' из ' + info.dailyMax)));
      rows.push(note(info.toNextText + '. ' + info.perSteps + ' шагов — 1 энергия; энергия старше ' + info.expireDays + ' дн. сгорает, старая первой.'));
      if (info.chargeText) rows.push(h('div', { class: 'g-mark' }, info.chargeText));
      rows.push(note(info.chargeAvailable ? info.chargeRule + '.' : 'Заряд прогулки недоступен: шаги известны только суммой за день.'));
      if (info.source === 'none') {
        rows.push(note('Шагов пока нет: нет записей «Здоровья» и нет доступа к датчику шагов.'));
        if (!info.permission) {
          rows.push(h('button', {
            class: 'g-btn small primary', onclick: async function () {
              var ok = await call('stepsPermission');
              window.ui.toast(ok ? 'Датчик шагов подключён' : 'Доступа нет');
              if (close) close();
            }
          }, 'Разрешить датчик шагов'));
        }
      }
      if (info.spends.length) {
        rows.push(h('h2', {}, 'На что тратят игры'));
        info.spends.forEach(function (s) {
          rows.push(h('div', { class: 'g-lines' }, h('div', { class: 'g-line' }, h('span', { class: 'v' }, s.title)),
            s.items.map(function (i) { return line(i.title, i.cost + ' ' + energyWord(i.cost)); })));
        });
      }
      if (info.log.length) {
        rows.push(h('h2', {}, 'Траты'));
        rows.push(h('div', { class: 'g-lines' }, info.log.map(function (l) { return line(dayText(l.at) + ' · ' + (l.game || 'ставка'), '−' + l.cost); })));
      }
    }
    async function manual(on) {
      try {
        await call('passenger', on);
        window.ui.toast(on ? 'Поездка началась' : 'Поездка закончилась');
      } catch (error) {
        window.ui.toast(error.message);
      }
      if (close) close();
      if (o.onChange) o.onChange();
    }
    close = sheet(rows, o.onClose);
    return close;
  }

  async function betSheet(gameId, o) {
    o = o || {};
    var info = await call('bet', 'offers');
    var chosen = null;
    var stake = null;
    var percent = info.stakes[1] || info.stakes[0] || 0.25;
    var body = h('div', { class: 'g-sheet' });
    var close = window.ui.sheet(body, o.onClose);
    var Fmt = window.Fmt;
    var Big = window.Big;

    function stakeOptions() {
      var out = [];
      info.games.forEach(function (g) {
        if (gameId && g.id !== gameId && info.games.some(function (x) { return x.id === gameId; })) return;
        g.currencies.forEach(function (c) {
          out.push({ key: g.id + ':' + c.id, game: g.id, currency: c.id, title: g.title + ': ' + c.title.toLowerCase(), balance: c.balance, text: c.text });
        });
      });
      if (info.energyOn && info.energy > 0) out.push({ key: '__energy', game: '__energy', currency: null, title: 'Энергия из шагов', balance: String(info.energy), text: String(info.energy) });
      return out;
    }

    function draw() {
      var rows = [h('h3', {}, 'Ставка на день')];
      if (info.today) {
        rows.push(h('div', { class: 'card flat' },
          h('div', { style: 'font-weight: 600' }, info.today.title),
          h('div', { class: 'small muted' }, info.today.kText || Fmt.mult(info.today.k)),
          h('div', { class: 'small muted' }, (info.today.game === '__energy' ? 'энергия ' + info.today.energy : (info.today.gameTitle || info.today.game) + ', ' + Fmt.pct(info.today.percent)) +
            ' · расчёт ' + (info.today.settleAt === 'sleep' ? 'утром' : 'вечером'))));
      } else if (!info.open) {
        rows.push(note('Ставки принимаются до ' + info.deadline + '. Завтра — новая.'));
      } else {
        rows.push(h('div', { class: 'g-pick', id: 'g-bet-offers' }, info.offers.map(function (offer) {
          return h('button', {
            class: chosen === offer ? 'on' : '', onclick: function () {
              chosen = offer;
              draw();
            }
          }, h('div', { class: 'grow' }, h('div', { style: 'font-weight: 600' }, offer.title), h('div', { class: 'small muted' }, offer.hint)),
          h('span', { class: 'g-num', style: 'font-weight: 700' }, offer.kText));
        })));
        if (!info.offers.length) rows.push(note('Пока нечего предложить: нет данных о шагах, сне или экране за последнюю неделю.'));
        var input = h('input', { class: 'input', id: 'g-bet-custom', placeholder: 'Своё условие: «не открою ютуб до обеда»' });
        rows.push(h('div', { class: 'row' }, h('div', { class: 'grow' }, input), h('button', {
          class: 'btn', onclick: async function () {
            var text = input.value.trim();
            if (!text) return;
            var b = this;
            b.disabled = true;
            try {
              var offer = await call('bet', 'custom', text);
              if (!offer.template) window.ui.toast(offer.why || 'Такое пока не проверить по данным');
              else {
                info.offers.push(offer);
                chosen = offer;
              }
            } catch (error) {
              window.ui.toast(error.message);
            }
            draw();
          }
        }, 'Подобрать')));
        var options = stakeOptions();
        if (!stake && options.length) stake = options[0];
        if (options.length) {
          rows.push(h('h2', {}, 'Чем'));
          rows.push(h('div', { class: 'g-pick' }, options.map(function (opt) {
            return h('button', { class: stake && stake.key === opt.key ? 'on' : '', onclick: function () { stake = opt; draw(); } },
              h('div', { class: 'grow truncate' }, opt.title), h('span', { class: 'g-num muted' }, opt.text));
          })));
          rows.push(h('h2', {}, 'Сколько'));
          rows.push(h('div', { class: 'chips' }, info.stakes.map(function (p) {
            return h('button', { class: 'chip' + (p === percent ? ' on' : ''), style: 'min-height: 44px', onclick: function () { percent = p; draw(); } }, Fmt.pct(p));
          })));
          var amount = stake ? (stake.game === '__energy' ? Math.max(1, Math.floor(Number(stake.balance) * percent)) : Big.mul(stake.balance, percent)) : 0;
          rows.push(line('Ставка', '= ' + Fmt.num(amount)));
          if (chosen) rows.push(line('Выигрыш', '= ' + Fmt.num(Big.mul(amount, chosen.k)) + ' (' + chosen.kText + ')'));
          rows.push(h('button', {
            class: 'g-btn primary', id: 'g-bet-place', disabled: !chosen || !stake, onclick: async function () {
              try {
                await call('bet', 'place', {
                  template: chosen.template, params: chosen.params, title: chosen.title, p: chosen.p, k: chosen.k,
                  game: stake.game, currency: stake.currency, percent: percent
                });
                window.ui.toast('Ставка сделана');
                info = await call('bet', 'offers');
                if (o.onChange) o.onChange();
              } catch (error) {
                window.ui.toast(error.message);
              }
              draw();
            }
          }, chosen ? 'Поставить' : 'Выберите условие'));
        } else {
          rows.push(note('Ставить нечем: ни одна игра не принимает ставки, энергии нет.'));
        }
      }
      if (info.history.length) {
        rows.push(h('h2', {}, 'Последние ставки'));
        var words = { won: 'выиграна', lost: 'проиграна', refund: 'возвращена' };
        rows.push(h('div', { class: 'g-lines' }, info.history.map(function (x) { return line(x.title, words[x.result] + ', ' + Fmt.mult(x.k)); })));
        if (info.streak >= 2) rows.push(note('Выиграно подряд: ' + info.streak));
      }
      body.replaceChildren.apply(body, rows);
    }
    draw();
    return close;
  }

  /** The sheet «Сохранения»: versions with a way back (the current one is first kept as a copy). */
  async function savesSheet(id, o) {
    o = o || {};
    var rows = await call('saves', id);
    var list = h('div', { class: 'list' });
    if (!rows.length) list.appendChild(h('div', { class: 'empty' }, 'Сохранений пока нет.'));
    rows.forEach(function (r, i) {
      list.appendChild(h('div', {},
        h('div', { class: 'grow' },
          h('div', {}, window.ui.ago(r.at) + ' · ' + reasonText(r.reason)),
          h('div', { class: 'small muted truncate' }, r.card || ('версия ' + r.ver))),
        i === 0 ? h('span', { class: 'small muted' }, 'сейчас') : h('button', {
          class: 'btn small', onclick: async function () {
            var sure = await window.ui.confirm('Вернуть сохранение «' + window.ui.ago(r.at) + '»? Текущее состояние сохранится копией.', 'Вернуть');
            if (!sure) return;
            try {
              if (o.beforeRestore) await o.beforeRestore();
              await call('restore', id, r.id);
              close();
              if (o.onRestore) o.onRestore();
              else window.ui.toast('Сохранение возвращено');
            } catch (error) {
              window.ui.toast(error.message);
            }
          }
        }, 'Вернуть')));
    });
    var close = sheet([h('h3', {}, 'Сохранения'),
      note('Хранятся последние версии, по одной за день и копии перед престижем, обновлением и долгим офлайном.'), list]);
    return close;
  }

  // ---- a game ----------------------------------------------------------------------------------

  function buildFrame(root) {
    var app = h('div', { class: 'g-app' });
    var bar = h('header', { class: 'g-bar' });
    var exit = h('button', { class: 'icon-btn g-exit', 'aria-label': 'Выйти из игры', id: 'g-exit' }, window.GameIcons.el('exit'));
    var plates = h('div', { class: 'g-plates' });
    var menu = h('button', { class: 'icon-btn', 'aria-label': 'Меню игры', id: 'g-menu' }, window.ui.icon('more'));
    bar.append(exit, plates, menu);
    var top = h('section', { class: 'g-top' });
    var field = h('main', { class: 'g-field' });
    var actions = h('footer', { class: 'g-actions' });
    app.append(bar, top, field, actions);
    root.appendChild(app);
    return { app: app, bar: bar, exit: exit, plates: plates, menu: menu, top: top, field: field, actions: actions };
  }

  function readMirror(id) {
    try {
      var text = localStorage.getItem('games:' + id);
      if (!text) return null;
      var m = JSON.parse(text);
      return m && typeof m.seq === 'number' && m.state ? m : null;
    } catch (error) {
      return null;
    }
  }

  function writeMirror(id, seq, json) {
    try {
      localStorage.setItem('games:' + id, '{"seq":' + seq + ',"at":' + Date.now() + ',"state":' + json + '}');
    } catch (error) { /* full or private: the database is the main line */ }
  }

  /**
   * Starts a game: {id, sim, render(state, g), animate?(state, g, dt), root?}. Resolves to the
   * game object g once the state is loaded, the absence counted and the loop running.
   */
  async function start(opts) {
    // The id may be left out: it is the folder of the screen, /modules/games/<id>/ui/….
    var fromPath = /^\/modules\/games\/(daily\/\d{4}-\d{2}-\d{2}(?:-[2-9])?|[a-z0-9][a-z0-9-]*)\//.exec(location.pathname);
    var id = opts.id || (fromPath ? fromPath[1] : '');
    var sim = opts.sim;
    if (!sim) throw new Error('Game.start: нет sim (логики игры)');
    var Big = window.Big;
    var Fmt = window.Fmt;
    var Rng = window.Rng;
    var Core = window.Core;
    var q = new URLSearchParams(location.search);
    var preview = q.get('preview') === '1';
    document.body.classList.add('g-body');
    var el = buildFrame(opts.root || document.body);
    el.field.appendChild(h('div', { class: 'g-wait' }, 'Загрузка…'));
    if (!window.axiko) {
      el.field.replaceChildren(h('div', { class: 'g-error' }, 'Игра открывается только внутри Axiko.'));
      throw new Error('нет window.axiko');
    }
    // Browser games stay in their hub pane; its fullscreen button is the owner's choice.
    if (!preview && window.axiko.platform !== 'web') window.axiko.stage(true).catch(noop);

    var boot = await call('boot', id);
    var game = boot.game;
    var config = boot.config;
    var features = boot.features || [];
    var audio = boot.audio || { headphones: false, silent: false };
    var links = boot.links || { mult: 1 };
    var counts = boot.stock || {};
    var charging = !!boot.charging;
    var listeners = {};
    var readonly = preview;
    // The game's guide.md (written by the agent, optional): a menu item when it is there.
    var guideHere = false;
    if (window.ui && window.ui.guideExists) window.ui.guideExists('/modules/games/' + id + '/guide.md').then(function (yes) { guideHere = yes; }, noop);
    var stale = false;
    var failed = false;
    var dirty = true;
    var changed = false;
    var hidden = false;
    var layout = '';
    var buttons = [];
    var timers = [];

    function setupFmt() {
      Fmt.setup({ mode: config['fmt.mode'], digits: config['fmt.digits'], sciFrom: config['fmt.sciFrom'], lang: window.__lang === 'en' ? 'en' : 'ru' });
    }
    setupFmt();

    function emitG(name) {
      var args = Array.prototype.slice.call(arguments, 1);
      (listeners[name] || []).slice().forEach(function (fn) {
        try {
          fn.apply(null, args);
        } catch (error) {
          console.error(error);
        }
      });
    }

    function log(text) { if (!readonly) call('log', id, text).catch(noop); }

    // ---- loading the state ----
    var mirror = readMirror(id);
    var candidates = boot.rows.map(function (r) { return { json: r.state, seq: r.seq, at: r.at, source: 'db', id: r.id }; });
    if (mirror && mirror.seq > (boot.lastSeq || 0)) candidates.unshift({ state: mirror.state, seq: mirror.seq, at: mirror.at, source: 'mirror' });
    var picked = Core.pick(candidates, game, sim);
    var broken = picked.broken.slice();
    var lowest = boot.rows.length ? boot.rows[boot.rows.length - 1].seq : 0;
    for (var tries = 0; !picked.state && !picked.newer && lowest > 0 && tries < 6; tries++) {
      var more = await call('older', id, lowest, 10);
      if (!more.length) break;
      lowest = more[more.length - 1].seq;
      picked = Core.pick(more.map(function (r) { return { json: r.state, seq: r.seq, at: r.at, source: 'db', id: r.id }; }), game, sim);
      broken = broken.concat(picked.broken);
    }
    if (picked.newer) {
      readonly = true;
      el.field.replaceChildren(h('div', { class: 'g-error' }, 'Сохранение новее игры: игру откатили, а данные остались новыми. Поверх них игра не пишет.'));
      var close = sheet([h('h3', {}, 'Сохранение новее игры'), note('Вернуть копию, сделанную до обновления?'),
        h('div', { class: 'g-btns' },
          h('button', { class: 'g-btn small', onclick: function () { close(); window.axiko.back(); } }, 'Не сейчас'),
          h('button', {
            class: 'g-btn small primary', onclick: async function () {
              var rows = await call('saves', id);
              var fit = rows.filter(function (r) { return r.ver && r.ver <= (game.saveVersion || 1); })[0];
              if (!fit) {
                window.ui.toast('Копий старой версии нет');
                return;
              }
              await call('restore', id, fit.id);
              location.reload();
            }
          }, 'Вернуть копию'))]);
      return { game: game, readonly: true, newer: true };
    }
    var state = picked.state;
    var rng;
    var fresh = !state;
    broken.forEach(function (b) {
      if (b.candidate.source === 'db') call('markBroken', id, b.candidate.seq, b.error).catch(noop);
    });
    if (fresh) {
      rng = Rng.make(Rng.seed());
      state = Core.newState(game, sim, { now: Date.now(), config: config, rng: rng, links: links, stock: counts, mult: 1 });
    } else {
      rng = Rng.make(state.meta.seed);
    }
    var sent = Math.max(boot.lastSeq || 0, mirror ? mirror.seq : 0, state.seq || 0);
    var next = sent;

    function capBonus() {
      var extra = Number(state.meta.capExtraHours) || 0;
      if (typeof sim.capBonusHours === 'function') {
        try {
          extra += Number(sim.capBonusHours(state)) || 0;
        } catch (ignored) { /* no bonus */ }
      }
      return extra;
    }

    function makeCtx(extra) {
      var c = { now: Date.now(), config: config, rng: rng, mult: links.mult || 1, links: links, stock: counts, offline: false, capBonusHours: capBonus() };
      for (var k in extra || {}) c[k] = extra[k];
      return c;
    }

    // ---- saving ----
    var chain = Promise.resolve();
    var queued = null;
    var debounce = 0;
    var pendingSince = 0;
    var lastSaveAt = Date.now();

    function stamp(seq) {
      state.seq = seq;
      state.savedAt = Date.now();
      state.meta.seed = rng.state();
      delete state.card;
      state.card = Core.card(sim, state, config);
    }

    function touchMirror() {
      if (readonly || failed) return;
      stamp(next + 1);
      writeMirror(id, next + 1, JSON.stringify(state));
    }

    /** Saves now: the state as it is at this call; requests go one after another, a newer one replaces a waiting one. */
    function save(reason, keep) {
      if (readonly || stale || failed) return Promise.resolve({ ok: false, skipped: true });
      clearTimeout(debounce);
      pendingSince = 0;
      var seq = ++next;
      stamp(seq);
      var json = JSON.stringify(state);
      writeMirror(id, seq, json);
      changed = false;
      lastSaveAt = Date.now();
      if (queued && !queued.keep) queued.skip = true;
      var req = { seq: seq, json: json, reason: reason || 'auto', keep: !!keep, skip: false };
      queued = req;
      var p = chain.then(function () {
        if (queued === req) queued = null;
        if (req.skip || stale) return { ok: true, skipped: true };
        return call('save', id, req.seq, req.json, req.reason, req.keep ? 1 : 0).then(function (r) {
          if (r && r.ok) {
            sent = Math.max(sent, req.seq);
            return r;
          }
          if (r && r.stale) onStale();
          return r;
        }, function (error) {
          changed = true;
          console.warn('games: сохранение не прошло: ' + error.message);
          return { ok: false, error: error.message };
        });
      });
      chain = p.catch(noop);
      return p;
    }

    function schedule() {
      if (readonly) return;
      var t = Date.now();
      if (!pendingSince) pendingSince = t;
      clearTimeout(debounce);
      var wait = Math.max(0, Math.min(config['save.debounceMs'], pendingSince + config['save.maxDelayMs'] - t));
      debounce = setTimeout(function () { save('action'); }, wait);
    }

    function onStale() {
      if (stale) return;
      stale = true;
      var close = sheet([h('h3', {}, 'Игра открыта в другом месте'), note('Здесь прогресс старше. Перезагрузить?'),
        h('div', { class: 'g-btns' },
          h('button', { class: 'g-btn small', onclick: function () { close(); } }, 'Позже'),
          h('button', { class: 'g-btn small primary', onclick: function () { location.reload(); } }, 'Перезагрузить'))]);
    }

    function fail(error) {
      if (failed) return;
      failed = true;
      stopLoop();
      el.field.replaceChildren(h('div', { class: 'g-error' }, 'Игра остановилась из-за ошибки в её логике: ' + (error && error.message ? error.message : error) +
        '. Сохранено то, что было до ошибки.'));
      setTimeout(function () { throw error; }, 0);
    }

    // ---- sound, vibration, the screen ----
    function sfx(name) {
      if (!config['sound.on'] || audio.silent) return false;
      if (config['sound.headphonesOnly'] && !audio.headphones) return false;
      return window.Sfx ? window.Sfx.play(name, config['sound.volume']) : false;
    }

    function buzz(name) {
      if (!config['vibro.on']) return false;
      var scale = config['vibro.scale'];
      if (!(scale > 0)) return false;
      var base = PATTERNS[name] || [].concat(name).map(Number);
      // The even places are buzzes (scaled), the odd ones pauses.
      var pattern = base.map(function (ms, i) { return Math.max(1, Math.round(i % 2 === 0 ? ms * scale : ms)); });
      if (config['vibro.via'] === 'web') {
        try {
          return navigator.vibrate ? navigator.vibrate(pattern) : false;
        } catch (error) {
          return false;
        }
      }
      call('vibrate', pattern).catch(noop);
      return true;
    }

    function layoutOf() {
      var w = window.innerWidth;
      return w < config['layout.narrow'] ? 'narrow' : w >= config['layout.wide'] ? 'wide' : 'mid';
    }

    function screenMode(on) {
      if (preview) return;
      call('screenMode', { portrait: on && config['screen.portrait'] && layout === 'narrow', keepOn: on && config['screen.keepOn'] }).catch(noop);
    }

    function relayout() {
      var now = layoutOf();
      if (now === layout) return;
      layout = now;
      el.app.setAttribute('data-layout', layout);
      document.documentElement.setAttribute('data-layout', layout);
      g.layout = layout;
      if (!hidden) screenMode(true);
      emitG('resize', layout);
      dirty = true;
    }

    // ---- the plates of the links ----
    function plates() {
      var list = [];
      function plate(name, icon, text, on) {
        // The bar is narrow: an idle plate keeps its icon and its number, the full text goes to
        // the label and to the sheet the plate opens. A working plate shows its whole text.
        var num = on ? null : /\d[\d\s.,]*\S*/.exec(String(text || ''));
        var shown = on ? text : (num ? num[0].trim() : '');
        list.push(h('button', { class: 'g-plate' + (on ? ' on' : '') + (shown ? '' : ' bare'), 'data-link': name, 'aria-label': text, title: text, onclick: function () { linkSheet(name, id, { onChange: refreshLinks }); } },
          window.GameIcons.el(icon), shown));
      }
      if (links.passenger && links.passenger.on) plate('passenger', 'train', links.passenger.text, links.passenger.state === 'trip');
      if (links.steps && links.steps.on) {
        plate('steps', 'energy', links.steps.text, false);
        if (links.steps.charge) plate('steps', 'steps', links.steps.chargeText, true);
      }
      if (links.bet && links.bet.on) plate('bet', 'dice', links.bet.text, !!links.bet.today);
      el.plates.replaceChildren.apply(el.plates, list);
    }

    async function refreshLinks() {
      try {
        links = await call('links', id);
      } catch (error) {
        return links;
      }
      plates();
      emitG('links', links);
      return links;
    }

    // ---- the loop ----
    var running = false;
    var interval = 0;
    var raf = 0;
    var acc = 0;
    var lastDraw = 0;
    var lastButtons = 0;

    function advance() {
      var t = Date.now();
      var gap = (t - state.lastTick) / 1000;
      if (gap < 0) {
        state.lastTick = t;
        return 0;
      }
      if (gap > config['tick.gapSec']) {
        // An absence the loop did not see (the screen slept): counted as offline.
        stopLoop();
        resume().then(startLoop, fail);
        return 0;
      }
      var stepSec = 1 / config['tick.hz'];
      acc += Math.min(gap, config['tick.maxStepSec']);
      state.lastTick = t;
      var n = 0;
      if (acc >= stepSec) {
        var ctx = makeCtx({ now: t });
        while (acc >= stepSec && n < 1000) {
          sim.tick(state, stepSec, ctx);
          acc -= stepSec;
          n++;
        }
      }
      if (n) {
        dirty = true;
        changed = true;
        emitG('tick', n * stepSec);
      }
      return n;
    }

    function draw(force) {
      if (!dirty && !force) return;
      dirty = false;
      if (opts.render) opts.render(state, g);
      var t = Date.now();
      if (force || t - lastButtons > 250) {
        lastButtons = t;
        buttons.forEach(function (b) { b.update(); });
      }
    }

    function periodic() {
      if (!readonly && Date.now() - lastSaveAt >= config['save.everySec'] * 1000 && changed) save('auto');
    }

    function tickLoop() {
      if (!running) return;
      try {
        advance();
        if (!running) return;
        draw(false);
        periodic();
      } catch (error) {
        fail(error);
      }
    }

    function frameLoop(ts) {
      if (!running) return;
      raf = requestAnimationFrame(frameLoop);
      var fps = charging ? config['render.fpsCharging'] : config['render.fps'];
      if (ts - lastDraw < 1000 / fps - 2) return;
      var dt = lastDraw ? (ts - lastDraw) / 1000 : 0;
      lastDraw = ts;
      try {
        advance();
        if (!running) return;
        opts.animate(state, g, dt);
        draw(false);
        periodic();
      } catch (error) {
        fail(error);
      }
    }

    function startLoop() {
      if (running || failed || hidden) return;
      running = true;
      acc = 0;
      if (opts.animate) {
        lastDraw = 0;
        raf = requestAnimationFrame(frameLoop);
      } else {
        interval = setInterval(tickLoop, 1000 / config['tick.hz']);
      }
    }

    function stopLoop() {
      running = false;
      clearInterval(interval);
      cancelAnimationFrame(raf);
    }

    // ---- absence and the inbox ----
    var resuming = null;

    async function catchUp() {
      var t = Date.now();
      var p = Core.plan(state.lastTick, t, config, capBonus());
      if (p.clockBack) {
        log('Часы переведены назад на ' + Fmt.time((state.lastTick - t) / 1000) + ': время игры поставлено на текущее');
        state.lastTick = t;
        return null;
      }
      if (!(p.creditedMs > 0)) {
        state.lastTick = t;
        return null;
      }
      var segs = [];
      try {
        segs = await call('segments', id, p.from, p.to);
      } catch (error) {
        segs = [];
      }
      var pieces = Core.cut(p.from, p.to, segs);
      if (p.backup) save('before-offline', true);
      var r = Core.runner(sim, state, game, p, pieces, function (seg) { return makeCtx({ now: seg.to }); }, config);
      var progress = null;
      while (!r.step(config['offline.budgetMs'])) {
        if (!progress) {
          var bar = h('i', {});
          var closeBar = sheet([h('h3', {}, 'Считаю, что произошло за ' + Fmt.time(p.creditedMs / 1000) + '…'), h('div', { class: 'g-progress' }, bar)]);
          progress = { bar: bar, close: closeBar };
        }
        progress.bar.style.width = Math.round(r.progress() * 100) + '%';
        await frame();
      }
      if (progress) progress.close();
      var report = r.report();
      report.plan = p;
      state.lastTick = t;
      state.meta.stats.offlineMs = (Number(state.meta.stats.offlineMs) || 0) + p.creditedMs;
      if (!p.silent && state.meta.capExtraHours) state.meta.capExtraHours = 0;
      dirty = true;
      changed = true;
      emitG('offline', report);
      return report;
    }

    async function pullInbox(quiet) {
      var list;
      try {
        list = await call('inbox', id);
      } catch (error) {
        return null;
      }
      return applyEntries(list, quiet);
    }

    function applyEntries(list, quiet) {
      if (!list || !list.length) return null;
      var r = Core.applyInbox(state, game, sim, list, makeCtx());
      if (!r.changed) return null;
      dirty = true;
      changed = true;
      r.chests.forEach(function (c) { emitG('chest', c.loot, c); });
      emitG('inbox', r);
      if (!readonly) call('inboxClean', id, state.meta.applied).catch(noop);
      if (!quiet) {
        announce(r);
        save('inbox');
      }
      return r;
    }

    function amountText(a) {
      if (a === undefined || a === null) return '';
      if (typeof a === 'string' && !Big.valid(a)) return a;
      var b = Big.of(a, 0);
      return (Big.sign(b) < 0 ? '' : '+') + Fmt.num(b);
    }

    function lootLines(loot) {
      if (!loot) return [];
      var out = [];
      if (loot.items) loot.items.forEach(function (i) { out.push(line(i.title, amountText(i.amount))); });
      return out;
    }

    function moveLines(moves) {
      return (moves || []).map(function (m) { return line(m.reason || m.title, amountText(m.amount) + ' ' + String(m.title || '').toLowerCase()); });
    }

    /** Chests and moves of the links when no offline sheet shows them. */
    function announce(r) {
      if (!r) return;
      r.chests.forEach(function (c) {
        sfx('chest');
        buzz('chest');
        var close = sheet([h('h3', {}, c.reason || 'Сундук'), c.loot && c.loot.title ? note(c.loot.title) : null, h('div', { class: 'g-lines' }, lootLines(c.loot)),
          h('button', { class: 'g-btn primary', onclick: function () { close(); } }, 'Забрать')]);
      });
      r.moves.forEach(function (m) { flash(m.op === 'take' ? 'dice' : 'coin', m.reason || m.title, amountText(m.amount) + ' ' + String(m.title || '').toLowerCase(), 4000); });
    }

    function offlineSheet(report, inbox) {
      var p = report.plan;
      var t = function (ms) { return Fmt.time(ms / 1000); };
      var rows = [h('h3', {}, 'Пока вас не было — ' + t(p.away))];
      rows.push(note(p.capped ? 'Засчитано ' + t(p.creditedMs) + ' из ' + t(p.away) + ' — потолок можно поднять в настройках игры'
        : 'Засчитано ' + t(p.creditedMs) + ' из ' + t(p.capMs)));
      if (p.efficiency < 1) rows.push(note('Доля офлайн-дохода ' + Fmt.pct(p.efficiency)));
      var lines = [];
      game.currencies.forEach(function (c) {
        if (report.gains[c.id]) lines.push(line(c.title, amountText(report.gains[c.id])));
      });
      for (var k in report.extra) lines.push(line(k, amountText(report.extra[k])));
      report.multiplied.forEach(function (m) {
        var trip = m.kinds.indexOf('passenger') >= 0;
        var walk = m.kinds.indexOf('steps') >= 0;
        var label = trip && walk ? 'В поездке с зарядом прогулки' : trip ? 'Из них в поездке' : walk ? 'С зарядом прогулки' : 'С множителем';
        lines.push(line(label, t(m.ms) + ', доход ' + Fmt.mult(m.mult)));
      });
      report.events.forEach(function (e) { lines.push(h('div', { class: 'g-line' }, h('span', {}, e))); });
      if (inbox) {
        inbox.chests.forEach(function (c) {
          var summary = c.loot && c.loot.items ? c.loot.items.map(function (i) { return amountText(i.amount).replace(/^\+/, '') + ' ' + String(i.title).toLowerCase(); }).join(', ') : '';
          lines.push(line(c.reason || 'Сундук', summary || (c.loot && c.loot.title) || ''));
        });
        lines = lines.concat(moveLines(inbox.moves));
      }
      if (!lines.length) lines.push(note('Ничего не накопилось.'));
      rows.push(h('div', { class: 'g-lines' }, lines));
      var decl = game.links && game.links.steps && Core.find(game.links.steps.spend, 'double-offline');
      var positive = game.currencies.filter(function (c) { return report.gains[c.id] && Big.gt(report.gains[c.id], 0); });
      var close = null;
      var buttonsRow = [h('button', { class: 'g-btn primary', id: 'g-offline-take', onclick: function () { close(); } }, 'Забрать')];
      if (decl && positive.length && links.steps && links.steps.on && !readonly) {
        var cost = config['steps.cost.double-offline'] !== undefined ? Number(config['steps.cost.double-offline']) : Number(decl.cost || 1);
        var have = links.steps.energy || 0;
        var b = h('button', {
          class: 'g-btn', id: 'g-offline-double', disabled: have < cost, onclick: async function () {
            b.disabled = true;
            var r = await g.links.spend('double-offline');
            if (!r.ok) {
              window.ui.toast('Нужна ' + r.need + ' ' + energyWord(r.need) + ' — это ~' + Fmt.num(r.need * config['steps.perSteps']) + ' шагов');
              b.disabled = false;
              return;
            }
            positive.forEach(function (c) { Core.set(state, c.path, Big.add(Core.get(state, c.path), report.gains[c.id])); });
            dirty = true;
            changed = true;
            save('spend');
            close();
            flash('energy', 'Собрано вдвое', null);
          }
        }, h('span', { class: 'g-stack' }, h('span', {}, 'Забрать ×2 — ' + cost + ' ' + energyWord(cost)),
          have < cost ? h('span', { class: 'g-sub' }, 'Нужна ' + cost + ' ' + energyWord(cost) + ' — это ~' + Fmt.num(cost * config['steps.perSteps']) + ' шагов') : null));
        buttonsRow.push(b);
      }
      rows.push(h('div', { class: 'g-btns' }, buttonsRow));
      if (inbox && inbox.chests.length) {
        sfx('chest');
        buzz('chest');
      }
      close = sheet(rows);
      return close;
    }

    /** Counts an absence, applies the inbox, shows what came; then the state is saved. */
    function resume() {
      if (resuming) return resuming;
      resuming = (async function () {
        var report = await catchUp();
        var inbox = await pullInbox(true);
        if (report && !report.plan.silent) offlineSheet(report, inbox);
        else if (inbox) announce(inbox);
        if (report || inbox) save('offline');
        return report;
      })().finally(function () { resuming = null; });
      return resuming;
    }

    // ---- prestige ----
    function prestigeInfo(layerId) { return Core.prestigeInfo(sim, game, state, layerId, config); }

    function currencyTitle(cid) {
      var c = Core.find(game.currencies, cid);
      return c ? c.title : cid;
    }

    function bonusText(v) {
      if (v === null || v === undefined) return null;
      if (typeof v === 'number' || Big.isBig(v)) return Fmt.mult(v);
      return String(v);
    }

    function prestigeSheet(layerId, info) {
      return new Promise(function (resolve) {
        var layer = info.layer;
        var index = 0;
        game.prestige.forEach(function (l, i) { if (l.id === layerId) index = i; });
        var lose = layer.lose || (['текущий забег'].concat(game.prestige.slice(0, index).map(function (l) { return currencyTitle(l.currency).toLowerCase(); })).join(', '));
        var keep = layer.keep || ([currencyTitle(layer.currency).toLowerCase()].concat(game.prestige.slice(index + 1).map(function (l) { return currencyTitle(l.currency).toLowerCase(); }))
          .concat(['ачивки и статистика']).join(', '));
        var answered = false;
        var rows = [h('h3', {}, layer.title || 'Престиж'),
          h('div', { class: 'g-lines' },
            line('Получите', '+' + Fmt.num(info.gain) + ' · ' + currencyTitle(layer.currency)),
            line('Было → станет', Fmt.num(info.have) + ' → ' + Fmt.num(info.after)),
            bonusText(info.bonusBefore) ? line('Бонус', bonusText(info.bonusBefore) + ' → ' + bonusText(info.bonusAfter)) : null,
            line('Сбросится', lose),
            line('Останется', keep))];
        if (info.hint) rows.push(h('span', { class: 'g-mark' }, 'выгодно'));
        var go = h('button', { class: 'g-btn primary', id: 'g-prestige-hold' }, 'Удерживайте, чтобы начать заново');
        hold(go, config['prestige.holdMs'], function () {
          answered = true;
          close();
        });
        rows.push(go);
        rows.push(h('button', { class: 'g-btn small', onclick: function () { close(); } }, 'Отмена'));
        var close = sheet(rows, function () { resolve(answered); });
      });
    }

    async function prestige(layerId, o) {
      o = o || {};
      var info = prestigeInfo(layerId);
      if (!info.can) {
        sfx('deny');
        buzz('deny');
        return { ok: false, gain: info.gain, info: info };
      }
      if (o.confirm !== false) {
        var yes = await prestigeSheet(layerId, info);
        if (!yes) return { ok: false, cancelled: true, info: info };
      }
      save('before-prestige', true);
      var r = Core.prestige(sim, game, state, layerId, makeCtx());
      if (!r.ok) return r;
      dirty = true;
      changed = true;
      sfx('prestige');
      buzz('prestige');
      draw(true);
      emitG('prestige', { layer: layerId, gain: r.gain });
      await save('prestige');
      return r;
    }

    /** A button of prestige that shows the gain and the mark «выгодно»; updated by the engine. */
    function prestigeButton(layerId, o) {
      o = o || {};
      var layer = Core.find(game.prestige, layerId);
      var label = h('span', {}, o.label || (layer && layer.title) || 'Престиж');
      var sub = h('span', { class: 'g-sub' });
      var b = h('button', { class: 'g-btn' + (o.primary ? ' primary' : ''), 'data-prestige': layerId, onclick: function () { prestige(layerId); } },
        h('span', {}, label, sub));
      b.update = function () {
        var info = prestigeInfo(layerId);
        b.disabled = !info.can;
        sub.textContent = info.can ? '+' + Fmt.num(info.gain) + ' ' + currencyTitle(layer.currency).toLowerCase() + (info.hint ? ' · выгодно' : '')
          : 'нужно больше прогресса';
      };
      b.update();
      buttons.push(b);
      return b;
    }

    // ---- achievements ----
    function achieve(aid) {
      var a = Core.achieve(state, game, aid, Date.now());
      if (!a) return false;
      dirty = true;
      changed = true;
      flash('trophy', a.title, a.text || 'Ачивка');
      sfx('achievement');
      buzz('achievement');
      emitG('achievement', a);
      save('achievement');
      return true;
    }

    // ---- the menu ----
    function menuSheet() {
      var close = null;
      var items = [];
      function item(icon, text, fn, attrs) {
        var b = h('button', attrs || {}, window.GameIcons.el(icon), h('span', { class: 'grow' }, text));
        b.addEventListener('click', function () {
          close();
          fn();
        });
        items.push(b);
      }
      game.prestige.forEach(function (layer) { item('up', layer.title || 'Престиж', function () { prestige(layer.id); }); });
      if (game.achievements.length) item('trophy', 'Ачивки', achievementsSheet);
      // The game's own instructions (guide.md next to game.json), when the agent wrote them.
      if (guideHere) item('book', 'Инструкция', function () { window.ui.guide('/modules/games/' + id + '/guide.md', game.title); });
      if (opts.help || game.help) {
        item('info', 'Как играть', function () {
          var c = sheet([h('h3', {}, 'Как играть'), h('div', { class: 'md' }), h('button', { class: 'g-btn small', onclick: function () { c(); } }, 'Понятно')]);
          var md = document.querySelector('.g-sheet .md');
          if (md) md.innerHTML = window.ui.md(opts.help || game.help);
        });
      }
      var capDecl = game.links && game.links.steps && Core.find(game.links.steps.spend, 'cap');
      if (capDecl && links.steps && links.steps.on && !readonly) {
        var capCost = config['steps.cost.cap'] !== undefined ? Number(config['steps.cost.cap']) : Number(capDecl.cost || 1);
        item('timer', 'Потолок офлайна +' + config['steps.capHours'] + ' ч — ' + capCost + ' ' + energyWord(capCost), async function () {
          var r = await g.links.spend('cap');
          if (!r.ok) {
            window.ui.toast('Нужна ' + r.need + ' ' + energyWord(r.need));
            return;
          }
          act(function (s) { s.meta.capExtraHours = (Number(s.meta.capExtraHours) || 0) + config['steps.capHours']; }, { now: true, reason: 'spend' });
          flash('timer', 'Потолок следующего офлайна +' + config['steps.capHours'] + ' ч', null);
        });
      }
      item('history', 'Сохранения', function () {
        savesSheet(id, { beforeRestore: function () { stale = true; return chain; }, onRestore: function () { location.reload(); } });
      });
      item(config['sound.on'] ? 'speaker' : 'mute', config['sound.on'] ? 'Звук включён' : 'Звук выключен', async function () {
        try {
          await call('setSetting', id, 'sound.on', !config['sound.on']);
          await refreshConfig();
          window.ui.toast(config['sound.on'] ? 'Звук включён' : 'Звук выключен');
        } catch (error) {
          window.ui.toast(error.message);
        }
      });
      item('settings', 'Настройки игры', function () {
        save('action');
        window.axiko.open('modules/games/ui/settings.html?game=' + encodeURIComponent(id));
      });
      close = sheet([h('h3', {}, game.title || id), h('div', { class: 'list' }, items)]);
    }

    function achievementsSheet() {
      var got = state.meta.achievements;
      var n = 0;
      var rows = game.achievements.map(function (a) {
        var at = got[a.id];
        if (at) n++;
        var title = a.hidden && !at ? 'Скрытая ачивка' : a.title;
        return h('div', {},
          window.GameIcons.el(at ? 'trophy' : 'lock'),
          h('div', { class: 'grow' }, h('div', { style: at ? 'font-weight: 600' : 'color: var(--ink-2)' }, title),
            h('div', { class: 'small muted' }, at ? dayText(at) : a.hidden ? 'откроется сама' : (a.text || ''))));
      });
      var close = sheet([h('h3', {}, 'Ачивки — ' + n + ' из ' + game.achievements.length), h('div', { class: 'list' }, rows),
        h('button', { class: 'g-btn small', onclick: function () { close(); } }, 'Закрыть')]);
    }

    async function refreshConfig() {
      try {
        var s = await call('settings', id);
        config = s.values;
        setupFmt();
        g.config = config;
        dirty = true;
      } catch (ignored) { /* keep the old values */ }
    }

    // ---- actions ----
    function act(fn, o) {
      o = o || {};
      if (failed) return undefined;
      var result;
      try {
        result = fn(state, g);
      } catch (error) {
        fail(error);
        throw error;
      }
      dirty = true;
      changed = true;
      if (o.now) save(o.reason || 'action', o.keep);
      else {
        touchMirror();
        schedule();
      }
      draw(true);
      return result;
    }

    // ---- sessions and visibility ----
    var sessionFrom = 0;

    function sessionStart() {
      sessionFrom = Date.now();
      state.meta.stats.sessions = (Number(state.meta.stats.sessions) || 0) + 1;
      changed = true;
    }

    function sessionEnd() {
      if (!sessionFrom) return;
      var t = Date.now();
      var ms = t - sessionFrom;
      sessionFrom = 0;
      state.meta.stats.playMs = (Number(state.meta.stats.playMs) || 0) + ms;
      if (!readonly) call('session', id, ms, t - ms, t).catch(noop);
    }

    function hide() {
      if (hidden) return;
      hidden = true;
      try {
        if (running) advance();
      } catch (error) {
        fail(error);
      }
      stopLoop();
      sessionEnd();
      save('hide');
      screenMode(false);
      emitG('hidden');
    }

    async function show() {
      if (!hidden) return;
      hidden = false;
      await Promise.all([refreshLinks(), refreshConfig()]);
      try {
        await resume();
      } catch (error) {
        fail(error);
        return;
      }
      screenMode(true);
      sessionStart();
      draw(true);
      startLoop();
      emitG('visible');
    }

    // ---- the game object ----
    var fmt = function (x, o) { return Fmt.num(x, o); };
    ['price', 'live', 'rate', 'mult', 'pct', 'time', 'ago', 'count', 'plural', 'say'].forEach(function (k) { fmt[k] = Fmt[k]; });

    var preparing = {};
    var g = {
      id: id, game: game, sim: sim, el: { app: el.app, bar: el.bar, top: el.top, field: el.field, actions: el.actions },
      layout: '', readonly: readonly, fresh: fresh, features: features,
      get state() { return state; },
      get config() { return config; },
      set config(v) { config = v; },
      get rng() { return rng; },
      act: act,
      fmt: fmt,
      achieve: achieve,
      prestige: prestige,
      prestigeInfo: prestigeInfo,
      sfx: sfx,
      buzz: buzz,
      saveNow: function (reason, keep) { return save(reason || 'action', keep); },
      redraw: function () { draw(true); },
      on: function (name, fn) {
        (listeners[name] = listeners[name] || []).push(fn);
        return function () { listeners[name] = listeners[name].filter(function (x) { return x !== fn; }); };
      },
      log: log,
      flash: flash,
      sheet: sheet,
      stock: {
        take: async function (kind, prefer) {
          var r = null;
          try {
            r = await call('stockTake', id, kind, prefer || null);
          } catch (error) {
            r = null;
          }
          if (r && r.item) {
            counts[kind] = r.left;
            state.meta.stockUsed.push(r.id);
            if (state.meta.stockUsed.length > 500) state.meta.stockUsed = state.meta.stockUsed.slice(-500);
            changed = true;
            save('stock');
            return r.item;
          }
          preparing[kind] = !!(r && r.preparing);
          if (typeof sim.procedural === 'function') return sim.procedural(kind, rng);
          return null;
        },
        count: function (kind) { return counts[kind] || 0; },
        preparing: function (kind) { return !!preparing[kind]; }
      },
      // A series the model's artist draws (engine/series.js): the pictures of the numbers shown.
      // sync → {on, animate, items: {n: {title, text, theme, svg}}, ready}; img → an <img> of one,
      // still when the setting, the system's reduced motion or o.still says so.
      art: {
        sync: async function (kind, best, seen) {
          if (readonly) return { on: false, animate: false, items: {}, ready: 0 };
          try {
            return await call('seriesSync', id, kind, best, seen || []);
          } catch (error) {
            return { on: false, animate: false, items: {}, ready: 0 };
          }
        },
        img: function (svg, o) {
          o = o || {};
          var calm = o.still || o.animate === false || (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
          var A = window.GameArt;
          if (!A) return null;
          return A.el(calm ? A.still(svg) : svg, { cls: o.cls, alt: o.alt || '', size: o.size });
        }
      },
      links: {
        mult: function () { return links.mult || 1; },
        energy: function () { return links.steps && links.steps.on ? links.steps.energy || 0 : 0; },
        status: function () { return links; },
        spend: async function (spendId) {
          var r;
          try {
            r = await call('spend', id, spendId);
          } catch (error) {
            return { ok: false, error: error.message, need: 0 };
          }
          await refreshLinks();
          if (r.ok) save('spend');
          return r;
        }
      },
      ui: {
        hold: function (button, ms, fn) { return hold(button, ms || config['prestige.holdMs'], fn); },
        prestigeButton: prestigeButton,
        menu: menuSheet,
        achievements: achievementsSheet,
        line: line
      },
      debug: {
        /** Moves the clock of the game back by `hours` and counts the absence (checks). */
        offline: function (hours) {
          state.lastTick -= hours * 3600000;
          stopLoop();
          return resume().then(function (report) {
            startLoop();
            return report ? { gains: Object.keys(report.gains).length, away: report.plan.away, silent: report.plan.silent } : null;
          });
        },
        inbox: function (entries) { return applyEntries(entries, false); },
        stale: function () { return stale; },
        saved: function () { return chain.then(function () { return sent; }); }
      }
    };
    window.__game = g;

    // ---- wiring ----
    el.exit.addEventListener('click', function () {
      hide();
      chain.then(function () { window.axiko.back(); });
    });
    el.menu.addEventListener('click', menuSheet);
    window.addEventListener('resize', function () {
      relayout();
      draw(true);
    });
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'hidden') hide();
      else show();
    });
    window.addEventListener('pagehide', hide);
    if (features.indexOf('app.visible') >= 0) {
      window.axiko.on('app.visible', function (e) {
        if (e && e.on === false) hide();
        else if (document.visibilityState !== 'hidden') show();
      });
    }
    window.axiko.on('games.audio', function (a) { if (a) audio = a; });
    window.axiko.on('games.links', function () { refreshLinks(); });
    window.axiko.on('games.inbox', function (e) { if (!e || e.game === id) pullInbox(false); });
    window.axiko.on('games.stock', function (e) { if (e && e.game === id) call('stockStatus', id).then(function (c) { counts = c; }, noop); });
    window.axiko.on('games.config', function (e) { if (!e || !e.game || e.game === id) refreshConfig(); });
    window.axiko.on('power', function (e) { if (e) charging = !!e.charging; });
    timers.push(setInterval(function () { if (!hidden) pullInbox(false); }, config['inbox.everySec'] * 1000));
    timers.push(setInterval(function () { if (!hidden) refreshLinks(); }, 60000));

    relayout();
    plates();
    el.field.replaceChildren();
    if (picked.state && picked.candidate && picked.candidate.source === 'mirror') {
      log('Взято из запасной копии экрана: seq ' + picked.candidate.seq);
    }
    if (broken.length) {
      var bad = broken[0].candidate;
      var loaded = picked.candidate;
      if (loaded) flash('info', 'Сохранение от ' + hhmm(bad.at) + ' повреждено', 'Загружено от ' + hhmm(loaded.at), 6000);
      else flash('info', 'Сохранения не читаются', 'Начата новая игра, старые сохранения не удалены', 6000);
    }
    if (picked.migratedFrom) {
      // A copy of the old version before it is written over.
      var oldJson = picked.candidate.json || JSON.stringify(picked.candidate.state);
      if (!readonly) {
        next++;
        var copySeq = next;
        chain = chain.then(function () { return call('save', id, copySeq, oldJson, 'before-migrate', 1); }).catch(noop);
      }
      log('Сохранение переведено с версии ' + picked.migratedFrom + ' на ' + game.saveVersion);
    }
    if (opts.init) opts.init(g);
    sessionStart();
    if (fresh) save('start');
    else if (picked.migratedFrom) save('migrate');
    else if (picked.candidate && picked.candidate.source === 'mirror') save('mirror');
    await resume();
    screenMode(true);
    draw(true);
    startLoop();
    return g;
  }

  /** Read-only scenery: no clock, RNG, purchases or saves are owned by this renderer.
   * mount once in init; update a small derived model in render; pulse only confirmed events.
   * Logical coordinates stay fixed while backing pixels follow the available size.
   */
  function scene(options) {
    options = options || {};
    if (!options.parent || typeof options.draw !== 'function') throw new Error('Game.scene: parent and draw are required');
    var width = Math.max(1, Number(options.width) || 1000);
    var height = Math.max(1, Number(options.height) || 620);
    var node = document.createElement('div');
    node.className = 'g-scene' + (options.className ? ' ' + options.className : '');
    var canvas = document.createElement('canvas');
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-label', options.label || 'Игровой мир');
    canvas.style.aspectRatio = width + ' / ' + height;
    canvas.width = width;
    canvas.height = height;
    node.appendChild(canvas);
    options.parent.appendChild(node);
    var context = canvas.getContext('2d');
    var model = null, effects = [], time = 0, dirtyScene = true;
    var disposed = false, failedScene = false, onScreen = true, gameHidden = false, pageHidden = false;
    var request = 0, previous = 0, lastPaint = -Infinity, resizeObserver = null, intersection = null;
    var unbind = [];
    var motion = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
    var calm = !!(motion && motion.matches);
    var frameCount = 0;
    function visible() {
      return !disposed && !failedScene && !document.hidden && !gameHidden && !pageHidden && onScreen && node.isConnected;
    }
    function fps() {
      var cfg = options.game && options.game.config;
      return Math.max(1, Math.min(30, Number(options.fps || (cfg && cfg['render.fps'])) || 24));
    }
    function stopScene() {
      if (request) cancelAnimationFrame(request);
      request = 0;
      previous = 0;
    }
    function fitScene() {
      var bounds = canvas.getBoundingClientRect();
      if (!(bounds.width > 0 && bounds.height > 0)) return false;
      // Bound both DPR and total pixels: a large unfolded screen must not multiply work indefinitely.
      var ratio = Math.min(window.devicePixelRatio || 1, 2, Math.sqrt(1400000 / (bounds.width * bounds.height)));
      var w = Math.max(1, Math.round(bounds.width * ratio));
      var hh = Math.max(1, Math.round(bounds.height * ratio));
      if (canvas.width !== w || canvas.height !== hh) { canvas.width = w; canvas.height = hh; dirtyScene = true; }
      return true;
    }
    function paintScene() {
      if (!context || model === null || !fitScene()) return;
      var list = effects.map(function (e) {
        var age = calm ? e.duration * 0.5 : Math.max(0, time - e.at);
        return { kind: e.kind, age: age, duration: e.duration, payload: e.payload, strength: Math.max(0, 1 - age / e.duration) };
      });
      context.save();
      try {
        context.setTransform(canvas.width / width, 0, 0, canvas.height / height, 0, 0);
        context.clearRect(0, 0, width, height);
        options.draw(context, model, time, list);
        dirtyScene = false;
        frameCount++;
      } catch (error) {
        failedScene = true;
        node.dataset.sceneError = String(error && error.message || error);
        console.error('Game.scene: ' + (options.label || 'scene'), error);
      } finally {
        context.restore();
      }
    }
    function scheduleScene() {
      if (!request && model !== null && visible()) request = requestAnimationFrame(tickScene);
    }
    function tickScene(stamp) {
      request = 0;
      if (!visible() || model === null) { previous = 0; return; }
      if (!previous) previous = stamp;
      if (!calm) time += Math.max(0, Math.min(.1, (stamp - previous) / 1000));
      previous = stamp;
      effects = effects.filter(function (e) { return calm || time - e.at < e.duration; });
      if (stamp - lastPaint >= 1000 / fps() - 1 && (!calm || dirtyScene)) {
        paintScene();
        lastPaint = stamp;
        if (calm) effects = [];
      }
      if (!calm || dirtyScene) scheduleScene();
    }
    function visibilityScene() {
      if (!visible()) { effects = []; stopScene(); }
      else { dirtyScene = true; scheduleScene(); }
    }
    function resizeScene() { dirtyScene = true; scheduleScene(); }
    function motionScene() { calm = !!motion.matches; effects = []; dirtyScene = true; previous = 0; scheduleScene(); }
    function hideScene() { pageHidden = true; visibilityScene(); }
    function showScene() { pageHidden = false; visibilityScene(); }
    document.addEventListener('visibilitychange', visibilityScene);
    window.addEventListener('pagehide', hideScene);
    window.addEventListener('pageshow', showScene);
    window.addEventListener('resize', resizeScene);
    if (motion) {
      if (motion.addEventListener) motion.addEventListener('change', motionScene);
      else if (motion.addListener) motion.addListener(motionScene);
    }
    if (window.ResizeObserver) { resizeObserver = new ResizeObserver(resizeScene); resizeObserver.observe(canvas); }
    if (window.IntersectionObserver) {
      intersection = new IntersectionObserver(function (entries) { onScreen = !!entries[0].isIntersecting; visibilityScene(); });
      intersection.observe(node);
    }
    if (options.game && options.game.on) {
      unbind.push(options.game.on('hidden', function () { gameHidden = true; visibilityScene(); }));
      unbind.push(options.game.on('visible', function () { gameHidden = false; visibilityScene(); }));
    }
    return {
      node: node, canvas: canvas,
      update: function (next) { if (!disposed) { model = next; dirtyScene = true; scheduleScene(); } },
      pulse: function (kind, payload, duration) {
        if (!visible()) return;
        duration = Math.max(.1, Math.min(4, Number(duration) || 1.4));
        effects = effects.filter(function (e) { return e.kind !== kind; }).slice(-3);
        effects.push({ kind: kind, payload: payload, duration: duration, at: time });
        dirtyScene = true;
        scheduleScene();
      },
      dispose: function () {
        if (disposed) return;
        disposed = true;
        stopScene();
        effects = [];
        model = null;
        if (resizeObserver) resizeObserver.disconnect();
        if (intersection) intersection.disconnect();
        unbind.forEach(function (fn) { if (typeof fn === 'function') fn(); });
        document.removeEventListener('visibilitychange', visibilityScene);
        window.removeEventListener('pagehide', hideScene);
        window.removeEventListener('pageshow', showScene);
        window.removeEventListener('resize', resizeScene);
        if (motion) {
          if (motion.removeEventListener) motion.removeEventListener('change', motionScene);
          else if (motion.removeListener) motion.removeListener(motionScene);
        }
        node.remove();
      },
      // Diagnostic counters contain no state or persistence; useful on WebView as well as Chromium.
      inspect: function () { return { frames: frameCount, effects: effects.length, running: !!request, visible: visible(), reducedMotion: calm, failed: failedScene }; }
    };
  }

  window.Game = {
    scene: scene,
    start: start,
    ui: { hold: hold, sheet: sheet, line: line, note: note, flash: flash, savesSheet: savesSheet, linkSheet: linkSheet, betSheet: betSheet, reasonText: reasonText }
  };
})();

// The shared heart of the game engine: settings, the state of a game, loading and checking a
// save, offline progress, prestige, the inbox of the life links, achievements, the content
// stock. Pure logic over plain data — the screen (engine/game.js) and the engine
// (modules/games/main.js) both call it, and node tests it. Shared subset, ES5 only.
(function (root, make) {
  var node = typeof require === 'function' && typeof exports !== 'undefined';
  var api = node ? make(require('./big.js'), require('./rng.js'), function () { return require('./art.js'); }, function () { return require('./series.js'); })
    : make(root.Big, root.Rng, function () { return root.GameArt; }, function () { return root.Series || null; });
  if (typeof exports !== 'undefined') { for (var k in api) exports[k] = api[k]; } else { root.Core = api; }
})(this, function (Big, Rng, artOf, seriesOf) {
  'use strict';

  var VERSION = 1;
  var FORMAT = 1;
  var RESERVED = ['engine', 'ui', 'links', 'daily', 'archive', 'template'];
  var HOUR = 3600000;
  var DAY = 86400000;

  // ---- small helpers -----------------------------------------------------------------------------

  function has(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }

  function clone(x) { return x === undefined ? undefined : JSON.parse(JSON.stringify(x)); }

  function num(v, fallback) {
    var n = Number(v);
    return v === undefined || v === null || v === '' || n !== n ? fallback : n;
  }

  function find(list, id) {
    list = list || [];
    for (var i = 0; i < list.length; i++) if (list[i] && list[i].id === id) return list[i];
    return null;
  }

  /** The value at a dotted path: get(state, 'run.money'). */
  function get(o, path) {
    var parts = String(path).split('.');
    for (var i = 0; i < parts.length; i++) {
      if (o === null || o === undefined) return undefined;
      o = o[parts[i]];
    }
    return o;
  }

  /** Sets the value at a dotted path, making the objects on the way. */
  function set(o, path, value) {
    var parts = String(path).split('.');
    for (var i = 0; i < parts.length - 1; i++) {
      if (o[parts[i]] === null || typeof o[parts[i]] !== 'object') o[parts[i]] = {};
      o = o[parts[i]];
    }
    o[parts[parts.length - 1]] = value;
  }

  function pad(n) { return n < 10 ? '0' + n : String(n); }

  /** The local date of a moment: "2026-11-14". */
  function dayKey(ms) {
    var d = new Date(ms);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }

  /** "HH:MM" -> minutes since midnight, or null. */
  function minutes(text) {
    var m = /^(\d{1,2}):(\d{2})$/.exec(String(text || '').trim());
    return m ? Number(m[1]) * 60 + Number(m[2]) : null;
  }

  // ---- ids ---------------------------------------------------------------------------------------

  /** Local/store game id, including publisher.name (96 chars); legacy daily IDs stay unchanged. */
  function validId(id) {
    id = String(id || '');
    if (/^daily\/\d{4}-\d{2}-\d{2}(-[2-9])?$/.test(id)) return true;
    return /^[a-z0-9][a-z0-9.-]{0,95}$/.test(id) && id.indexOf('..') < 0 && !/[.-]$/.test(id) && RESERVED.indexOf(id) < 0;
  }

  // ---- settings ----------------------------------------------------------------------------------
  // Every number of the engine is a setting. `scope: 'hub'` ones are the hub's own (shown only
  // in the hub's settings); the rest can also be set for one game.

  var SETTINGS = {
    'tick.hz': { value: 10, title: 'Тактов в секунду', min: 1, max: 60, group: 'Движок' },
    'tick.maxStepSec': { value: 1, title: 'Логики за кадр, секунд максимум', min: 0.1, max: 5, step: 0.1, group: 'Движок' },
    'tick.gapSec': { value: 5, title: 'Перерыв, после которого считается отсутствие, с', min: 1, max: 120, group: 'Движок' },
    'render.fps': { value: 30, title: 'Кадров в секунду на батарее', min: 5, max: 120, group: 'Движок' },
    'render.fpsCharging': { value: 60, title: 'Кадров в секунду на зарядке', min: 5, max: 120, group: 'Движок' },
    'offline.capHours': { value: 12, title: 'Офлайн-доход, часов максимум', min: 1, max: 72, group: 'Офлайн' },
    'offline.efficiency': { value: 1, title: 'Доля офлайн-дохода', min: 0.1, max: 1, step: 0.05, group: 'Офлайн' },
    'offline.minSec': { value: 60, title: 'Сводка после отсутствия от, секунд', min: 0, max: 3600, group: 'Офлайн' },
    'offline.stepSec': { value: 1, title: 'Шаг подсчёта офлайна, секунд', min: 0.05, max: 600, step: 0.05, group: 'Офлайн' },
    'offline.maxSteps': { value: 2000, title: 'Шагов подсчёта офлайна максимум', min: 10, max: 100000, group: 'Офлайн' },
    'offline.budgetMs': { value: 250, title: 'Подсчёт за один раз, мс', min: 16, max: 2000, group: 'Офлайн' },
    'save.debounceMs': { value: 400, title: 'Сохранять после действия через, мс', min: 50, max: 5000, group: 'Сохранения' },
    'save.maxDelayMs': { value: 2000, title: 'Сохранять при нажатиях подряд не реже, мс', min: 200, max: 10000, group: 'Сохранения' },
    'save.everySec': { value: 15, title: 'Сохранять во время игры каждые, секунд', min: 3, max: 300, group: 'Сохранения' },
    'save.keep': { value: 30, title: 'Последних сохранений хранить', min: 5, max: 500, group: 'Сохранения' },
    'save.dailyDays': { value: 14, title: 'По одному сохранению за день, дней', min: 0, max: 365, group: 'Сохранения' },
    'save.warnKb': { value: 512, title: 'Предупреждать о сохранении больше, КБ', min: 16, max: 16384, group: 'Сохранения' },
    'prestige.holdMs': { value: 1200, title: 'Удержание кнопки престижа, мс', min: 300, max: 5000, group: 'Престиж' },
    'prestige.hintRatio': { value: 0.5, title: 'Метка «выгодно» с прироста от накопленного', min: 0, max: 10, step: 0.05, group: 'Престиж' },
    'layout.narrow': { value: 420, title: 'Узкий экран до, точек', min: 280, max: 700, group: 'Экран' },
    'layout.wide': { value: 600, title: 'Широкий экран от, точек', min: 400, max: 1200, group: 'Экран' },
    'screen.portrait': { value: true, title: 'Портрет на внешнем экране', group: 'Экран' },
    'screen.keepOn': { value: false, title: 'Не гасить экран во время игры', group: 'Экран' },
    'fmt.mode': {
      value: 'words', title: 'Вид больших чисел', group: 'Числа',
      options: [['words', 'Словами до трлн'], ['words-long', 'Словами до децлн'], ['sci', 'Порядком: 4,5e42'], ['eng', 'Порядком, кратным трём']]
    },
    'fmt.digits': { value: 3, title: 'Значащих цифр', min: 2, max: 6, group: 'Числа' },
    'fmt.sciFrom': { value: 15, title: 'Порядком с 10 в степени', min: 6, max: 33, step: 3, group: 'Числа' },
    'sound.on': { value: true, title: 'Звук', group: 'Звук и вибрация' },
    'sound.volume': { value: 0.6, title: 'Громкость', min: 0, max: 1, step: 0.05, group: 'Звук и вибрация' },
    'sound.headphonesOnly': { value: true, title: 'Звук только в наушниках', group: 'Звук и вибрация' },
    'vibro.on': { value: true, title: 'Вибрация', group: 'Звук и вибрация' },
    'vibro.scale': { value: 1, title: 'Сила вибрации', min: 0, max: 3, step: 0.1, group: 'Звук и вибрация' },
    'vibro.via': {
      value: 'auto', title: 'Вибрация через', group: 'Звук и вибрация',
      options: [['auto', 'Как получится'], ['engine', 'Приложение'], ['web', 'Страницу']]
    },
    'catalog.badgeAfterMin': { value: 60, title: 'Метка «накопилось» после, минут', min: 1, max: 1440, group: 'Каталог', scope: 'hub' },
    'achievements.share': {
      value: 'marked', title: 'Ачивки в «Ачивки за жизнь»', group: 'Ачивки', scope: 'hub',
      options: [['marked', 'Только отмеченные'], ['all', 'Все'], ['none', 'Никакие']]
    },
    'base.sessions': { value: true, title: 'Записывать игровые сессии в общую базу', group: 'Ачивки', scope: 'hub' },
    'notify.quiet': { value: '23:00-08:00', title: 'Тихие часы', group: 'Уведомления', scope: 'hub' },
    'stock.everyMin': { value: 120, title: 'Пополнять запас контента каждые, минут', min: 15, max: 1440, group: 'Запас контента', scope: 'hub' },
    'stock.beforeTripMin': { value: 60, title: 'Пополнять до обычной поездки за, минут', min: 0, max: 240, group: 'Запас контента', scope: 'hub' },
    'stock.maxCallsPerDay': { value: 30, title: 'Запросов к модели в день, максимум', min: 0, max: 500, group: 'Запас контента', scope: 'hub' },
    'stock.local': { value: false, title: 'Готовить контент моделью на телефоне, когда запас кончился', group: 'Запас контента', scope: 'hub' },
    'stock.expireDays': { value: 30, title: 'Неиспользованный запас хранить, дней', min: 1, max: 365, group: 'Запас контента', scope: 'hub' },
    'inbox.everySec': { value: 30, title: 'Проверять начисления связок каждые, секунд', min: 5, max: 600, group: 'Связки', scope: 'hub' },

    'passenger.on': { value: true, title: 'Связка «Пассажир»', group: 'Пассажир', scope: 'hub' },
    'passenger.mult': { value: 2, title: 'Доход в поездке, множитель', min: 1, max: 10, step: 0.1, group: 'Пассажир', scope: 'hub' },
    'passenger.chestMin': { value: 20, title: 'Сундук за поездку от, минут', min: 1, max: 240, group: 'Пассажир', scope: 'hub' },
    'passenger.chestsPerDay': { value: 2, title: 'Сундуков в день, максимум', min: 0, max: 20, group: 'Пассажир', scope: 'hub' },
    'passenger.chestFallbackMin': { value: 30, title: 'Сундук без своего содержимого: доход за, минут', min: 1, max: 600, group: 'Пассажир', scope: 'hub' },
    'passenger.minOfflineSec': { value: 60, title: 'Нет сети дольше, секунд — «похоже, едете»', min: 10, max: 900, group: 'Пассажир', scope: 'hub' },
    'passenger.confirmMin': { value: 3, title: 'Подтверждение поездки после, минут без сети', min: 1, max: 30, group: 'Пассажир', scope: 'hub' },
    'passenger.maybeMaxMin': { value: 10, title: 'Ждать подтверждения не дольше, минут', min: 3, max: 60, group: 'Пассажир', scope: 'hub' },
    'passenger.endOnlineMin': { value: 2, title: 'Поездка кончилась после сети подряд, минут', min: 1, max: 30, group: 'Пассажир', scope: 'hub' },
    'passenger.stillEndMin': { value: 15, title: 'Поездка кончилась после покоя подряд, минут', min: 3, max: 120, group: 'Пассажир', scope: 'hub' },
    'passenger.manualMaxMin': { value: 120, title: 'Кнопка «Я еду» действует до, минут', min: 10, max: 600, group: 'Пассажир', scope: 'hub' },
    'passenger.sensors': { value: true, title: 'Слушать датчики движения', group: 'Пассажир', scope: 'hub' },
    'passenger.sampleSec': { value: 10, title: 'Окно акселерометра, секунд', min: 2, max: 60, group: 'Пассажир', scope: 'hub' },
    'passenger.sampleEveryMin': { value: 1, title: 'Окно акселерометра раз в, минут', min: 1, max: 15, group: 'Пассажир', scope: 'hub' },
    'passenger.walkSteps': { value: 30, title: 'Шагов за 3 минуты — идёт пешком', min: 5, max: 500, group: 'Пассажир', scope: 'hub' },
    'passenger.descentHpa': { value: 2, title: 'Спуск под землю: рост давления, гПа', min: 0.5, max: 10, step: 0.1, group: 'Пассажир', scope: 'hub' },
    'passenger.descentMin': { value: 10, title: 'Спуск под землю за, минут', min: 2, max: 60, group: 'Пассажир', scope: 'hub' },
    'passenger.ssids': { value: '', title: 'Wi-Fi метро (имена сетей через запятую)', group: 'Пассажир', scope: 'hub' },
    'passenger.stillRms': { value: 0.03, title: 'Покой: ускорение меньше, м/с²', min: 0.001, max: 1, step: 0.005, group: 'Пассажир', scope: 'hub' },
    'passenger.walkRms': { value: 1.0, title: 'Шаги: ускорение больше, м/с²', min: 0.1, max: 5, step: 0.05, group: 'Пассажир', scope: 'hub' },
    'passenger.trainMinRms': { value: 0.05, title: 'Вагон: ускорение от, м/с²', min: 0.001, max: 2, step: 0.005, group: 'Пассажир', scope: 'hub' },
    'passenger.trainMaxRms': { value: 0.8, title: 'Вагон: ускорение до, м/с²', min: 0.05, max: 5, step: 0.05, group: 'Пассажир', scope: 'hub' },

    'steps.on': { value: true, title: 'Связка «Шаги — топливо»', group: 'Шаги — топливо', scope: 'hub' },
    'steps.perSteps': { value: 1000, title: 'Шагов за единицу энергии', min: 100, max: 20000, group: 'Шаги — топливо', scope: 'hub' },
    'steps.dailyMax': { value: 15, title: 'Энергии в день, максимум', min: 1, max: 100, group: 'Шаги — топливо', scope: 'hub' },
    'steps.cap': { value: 30, title: 'Запас энергии, максимум', min: 1, max: 500, group: 'Шаги — топливо', scope: 'hub' },
    'steps.expireDays': { value: 2, title: 'Энергия сгорает через, дней', min: 1, max: 60, group: 'Шаги — топливо', scope: 'hub' },
    'steps.chargeWindowMin': { value: 30, title: 'Заряд прогулки: шаги за последние, минут', min: 5, max: 180, group: 'Шаги — топливо', scope: 'hub' },
    'steps.chargeSteps': { value: 800, title: 'Заряд прогулки: шагов не меньше', min: 50, max: 20000, group: 'Шаги — топливо', scope: 'hub' },
    'steps.chargeMult': { value: 1.5, title: 'Заряд прогулки, множитель', min: 1, max: 10, step: 0.1, group: 'Шаги — топливо', scope: 'hub' },
    'steps.chargeMin': { value: 30, title: 'Заряд прогулки действует, минут', min: 5, max: 240, group: 'Шаги — топливо', scope: 'hub' },
    'steps.markEveryMin': { value: 30, title: 'Снимать счётчик шагов каждые, минут', min: 5, max: 240, group: 'Шаги — топливо', scope: 'hub' },
    'steps.capHours': { value: 2, title: 'Трата «потолок»: плюс часов офлайна', min: 1, max: 24, group: 'Шаги — топливо', scope: 'hub' },

    'bet.on': { value: true, title: 'Связка «Ставка на день»', group: 'Ставка на день', scope: 'hub' },
    'bet.morningAt': { value: '08:30', title: 'Утреннее уведомление', group: 'Ставка на день', scope: 'hub' },
    'bet.notifyMorning': { value: true, title: 'Напоминать утром о ставке', group: 'Ставка на день', scope: 'hub' },
    'bet.eveningAt': { value: '23:30', title: 'Расчёт вечером', group: 'Ставка на день', scope: 'hub' },
    'bet.sleepCheckAt': { value: '10:00', title: 'Расчёт условий про сон', group: 'Ставка на день', scope: 'hub' },
    'bet.deadline': { value: '12:00', title: 'Ставки принимаются до', group: 'Ставка на день', scope: 'hub' },
    'bet.historyDays': { value: 30, title: 'История для коэффициента, дней', min: 7, max: 120, group: 'Ставка на день', scope: 'hub' },
    'bet.edge': { value: 1, title: 'Щедрость коэффициента', min: 0.5, max: 2, step: 0.05, group: 'Ставка на день', scope: 'hub' },
    'bet.kMin': { value: 1.1, title: 'Коэффициент от', min: 1, max: 5, step: 0.1, group: 'Ставка на день', scope: 'hub' },
    'bet.kMax': { value: 5, title: 'Коэффициент до', min: 1.5, max: 20, step: 0.5, group: 'Ставка на день', scope: 'hub' },
    'bet.kNoHistory': { value: 2, title: 'Коэффициент без истории', min: 1, max: 10, step: 0.1, group: 'Ставка на день', scope: 'hub' },
    'bet.streakBonus': { value: 0.1, title: 'Прибавка за три выигрыша подряд', min: 0, max: 1, step: 0.05, group: 'Ставка на день', scope: 'hub' },
    'bet.stakes': { value: '0.1,0.25,0.5', title: 'Доли ставки', group: 'Ставка на день', scope: 'hub' }
  };

  var LINK_TITLES = { passenger: 'Связка «Пассажир»', steps: 'Связка «Шаги — топливо»', bet: 'Связка «Ставка на день»' };

  /** The settings of a game: the engine's, the game's config.json over them, and per-game switches. */
  function schema(gameConfig, game) {
    var out = {};
    var k;
    for (k in SETTINGS) {
      out[k] = clone(SETTINGS[k]);
      out[k].origin = 'engine';
    }
    for (k in gameConfig || {}) {
      if (!has(gameConfig, k) || !gameConfig[k] || typeof gameConfig[k] !== 'object') continue;
      var item = out[k] || {};
      for (var f in gameConfig[k]) item[f] = gameConfig[k][f];
      item.origin = 'game';
      out[k] = item;
    }
    if (game) {
      // «Не гасить экран»: off for idle, on for action — unless the game's config.json says.
      if (game.genre === 'action' && !(gameConfig && has(gameConfig, 'screen.keepOn'))) {
        out['screen.keepOn'].value = true;
        out['screen.keepOn'].origin = 'game';
      }
      for (k in game.links || {}) {
        if (LINK_TITLES[k] && !out['links.' + k]) out['links.' + k] = { value: true, title: LINK_TITLES[k], group: 'Связки', origin: 'game' };
      }
      (game.notify || []).forEach(function (n) {
        if (!out['notify.' + n.id]) out['notify.' + n.id] = { value: false, title: n.title || n.id, group: 'Уведомления', origin: 'game' };
      });
      // A series the model's artist draws (engine/series.js): its own switches, unless config.json has them.
      var series = (game.stock || []).some(function (d) { return d && d.artist; }) ? seriesOf() : null;
      (series ? game.stock : []).forEach(function (d) {
        if (!d || !d.artist) return;
        var own = series.settings(d);
        for (var key in own) {
          if (out[key]) continue;
          own[key].origin = 'game';
          out[key] = own[key];
        }
      });
    }
    return out;
  }

  /** One value as its setting wants it: the right type, within min..max, one of the options. */
  function coerce(item, v) {
    var d = item.value;
    if (v === undefined || v === null) return d;
    if (typeof d === 'boolean') return v === true || v === 'true' || v === 1 || v === '1';
    if (item.options) {
      for (var i = 0; i < item.options.length; i++) {
        var option = Array.isArray(item.options[i]) ? item.options[i][0] : item.options[i];
        if (String(option) === String(v)) return option;
      }
      return d;
    }
    if (item.big) return Big.valid(v) ? (typeof v === 'string' ? v : Big.str(v)) : d;
    if (typeof d === 'number') {
      var n = Number(v);
      if (n !== n || n === Infinity || n === -Infinity) return d;
      if (item.min !== undefined && n < item.min) n = item.min;
      if (item.max !== undefined && n > item.max) n = item.max;
      return n;
    }
    if (typeof d === 'string') return String(v);
    if (Array.isArray(d)) return Array.isArray(v) ? v : d;
    if (d && typeof d === 'object') return v && typeof v === 'object' ? v : d;
    return v;
  }

  /**
   * The values a game runs with. Order: the engine's default, the hub's settings over it (only
   * where the game declares nothing), the game's config.json, the owner's value for the game.
   */
  function config(sch, hub, owner) {
    var out = {};
    for (var k in sch) {
      var item = sch[k];
      var v = item.value;
      if (item.origin !== 'game' && hub && has(hub, k)) v = hub[k];
      if (owner && has(owner, k) && item.scope !== 'hub') v = owner[k];
      out[k] = coerce(item, v);
    }
    return out;
  }

  // ---- the state of a game -------------------------------------------------------------------------

  function emptyLayer() { return { currency: '0e0', count: 0, best: '0e0', state: {} }; }

  /** Fills what a state must have (older saves, new prestige layers); returns the state. */
  function complete(state, game) {
    state.run = state.run && typeof state.run === 'object' ? state.run : {};
    state.layers = state.layers && typeof state.layers === 'object' ? state.layers : {};
    state.meta = state.meta && typeof state.meta === 'object' ? state.meta : {};
    var meta = state.meta;
    if (typeof meta.seed !== 'number') meta.seed = Rng.hash(String(state.created || 0) + (game ? game.id : ''));
    meta.achievements = meta.achievements || {};
    meta.applied = meta.applied || [];
    meta.stockUsed = meta.stockUsed || [];
    meta.taken = meta.taken || {};
    meta.stats = meta.stats || {};
    ['playMs', 'sessions', 'offlineMs', 'trips'].forEach(function (k) { meta.stats[k] = num(meta.stats[k], 0); });
    ((game && game.prestige) || []).forEach(function (layer) {
      var l = state.layers[layer.id] = state.layers[layer.id] || emptyLayer();
      if (l.currency === undefined || l.currency === null) l.currency = '0e0';
      l.count = num(l.count, 0);
      if (l.best === undefined || l.best === null) l.best = '0e0';
      l.state = l.state && typeof l.state === 'object' ? l.state : {};
    });
    return state;
  }

  /** A new game: sim.init(ctx) gives {run, layers?, meta?}; the engine adds the rest. */
  function newState(game, sim, ctx) {
    var now = ctx.now;
    if (!ctx.rng) ctx.rng = Rng.make(ctx.seed !== undefined ? ctx.seed : Rng.seed());
    var made = sim.init(ctx) || {};
    var state = {
      format: FORMAT, game: game.id, ver: game.saveVersion || 1, seq: 0,
      created: now, savedAt: now, lastTick: now,
      run: made.run || {}, layers: made.layers || {}, meta: made.meta || {}, card: ''
    };
    complete(state, game);
    state.meta.seed = ctx.rng.state();
    return state;
  }

  var BIG_TEXT = /^-?\d+(\.\d+)?e[+-]?\d+$/;
  var WRONG = { 'NaN': 1, 'Infinity': 1, '-Infinity': 1, 'undefined': 1, '[object Object]': 1 };

  function scan(value, path, depth) {
    if (depth > 64) return path + ': слишком глубоко';
    if (typeof value === 'number') return value === value && value !== Infinity && value !== -Infinity ? null : path + ': ' + value;
    if (typeof value === 'string') {
      if (WRONG[value]) return path + ': «' + value + '»';
      if (BIG_TEXT.test(value) && !Big.valid(value)) return path + ': «' + value + '»';
      return null;
    }
    if (!value || typeof value !== 'object') return null;
    if (Big.isBig(value)) return value.m === value.m && value.e === value.e ? null : path + ': испорченное большое число';
    for (var k in value) {
      if (!has(value, k)) continue;
      var found = scan(value[k], path ? path + '.' + k : k, depth + 1);
      if (found) return found;
    }
    return null;
  }

  /** Checks a state as the engine sees it; null when it is fine, otherwise what is wrong. */
  function validate(state, game) {
    if (!state || typeof state !== 'object') return 'сохранение не объект';
    if (state.format !== FORMAT) return 'неизвестный формат ' + state.format;
    if (game && state.game !== game.id) return 'сохранение другой игры: ' + state.game;
    if (typeof state.ver !== 'number' || state.ver % 1 !== 0 || state.ver < 1) return 'нет версии сохранения';
    if (typeof state.seq !== 'number' || !(state.seq >= 0)) return 'нет счётчика seq';
    if (typeof state.lastTick !== 'number' || !isFinite(state.lastTick)) return 'нет времени lastTick';
    var parts = ['run', 'layers', 'meta'];
    for (var i = 0; i < parts.length; i++) {
      if (!state[parts[i]] || typeof state[parts[i]] !== 'object') return 'нет части ' + parts[i];
    }
    var bad = scan(state, '', 0);
    if (bad) return 'испорчено значение ' + bad;
    var currencies = (game && game.currencies) || [];
    for (var c = 0; c < currencies.length; c++) {
      var v = get(state, currencies[c].path);
      if (v === undefined || v === null) {
        // A layer's currency may wait for its layer to exist (complete() makes it).
        if (/^layers\./.test(currencies[c].path)) continue;
        return 'нет валюты ' + currencies[c].id + ' (' + currencies[c].path + ')';
      }
      if (!Big.valid(v)) return 'валюта ' + currencies[c].id + ' не число';
    }
    return null;
  }

  /**
   * Picks the state to load from candidates, best first: [{json | state, seq, at, source, id}].
   * Returns {state, candidate, index, broken: [{candidate, error}], migratedFrom} or {newer} when
   * a save is newer than the game (the code was rolled back), or {state: null} when none reads.
   */
  function pick(candidates, game, sim) {
    var broken = [];
    var version = game.saveVersion || 1;
    for (var i = 0; i < candidates.length; i++) {
      var c = candidates[i];
      var state;
      try {
        state = typeof c.json === 'string' ? JSON.parse(c.json) : clone(c.state);
      } catch (error) {
        broken.push({ candidate: c, error: 'не читается: ' + error.message });
        continue;
      }
      if (state && typeof state.ver === 'number' && state.ver > version) return { state: null, newer: c, broken: broken };
      // An older version is checked as a state only: its currencies may live elsewhere until migrate.
      var error = validate(state, state && state.ver < version ? { id: game.id } : game);
      if (error) {
        broken.push({ candidate: c, error: error });
        continue;
      }
      var from = null;
      if (state.ver < version) {
        if (typeof sim.migrate !== 'function') {
          broken.push({ candidate: c, error: 'игра не умеет переводить сохранение версии ' + state.ver });
          continue;
        }
        from = state.ver;
        try {
          state = sim.migrate(state, from) || state;
        } catch (failure) {
          broken.push({ candidate: c, error: 'перевод с версии ' + from + ': ' + (failure && failure.message ? failure.message : failure) });
          continue;
        }
        state.ver = version;
        error = validate(state, game);
        if (error) {
          broken.push({ candidate: c, error: 'после перевода: ' + error });
          continue;
        }
      }
      complete(state, game);
      if (typeof sim.validate === 'function') {
        try {
          error = sim.validate(state);
        } catch (failure) {
          error = String(failure && failure.message ? failure.message : failure);
        }
        if (error) {
          broken.push({ candidate: c, error: String(error) });
          continue;
        }
      }
      return { state: state, candidate: c, index: i, broken: broken, migratedFrom: from };
    }
    return { state: null, broken: broken };
  }

  /** The line of the catalog card from sim.summary(state, config); '' when the game has none or it fails. */
  function card(sim, state, cfg) {
    try {
      var s = typeof sim.summary === 'function' ? sim.summary(state, cfg || {}) : null;
      return s && s.line ? String(s.line) : '';
    } catch (error) {
      return '';
    }
  }

  // ---- offline progress --------------------------------------------------------------------------

  /** What an absence gives: the window credited (the last capHours), whether to show the sheet. */
  function plan(lastTick, now, cfg, capBonusHours) {
    cfg = cfg || {};
    var away = now - lastTick;
    var back = away < 0;
    if (back) away = 0;
    var capMs = Math.max(0, (num(cfg['offline.capHours'], 12) + num(capBonusHours, 0)) * HOUR);
    var credited = Math.min(away, capMs);
    return {
      away: away, clockBack: back, capMs: capMs, creditedMs: credited, capped: away > capMs,
      from: now - credited, to: now, efficiency: num(cfg['offline.efficiency'], 1),
      silent: away < num(cfg['offline.minSec'], 60) * 1000,
      backup: credited > HOUR
    };
  }

  /**
   * Cuts [from, to] into pieces of constant multiplier from the links' segments
   * ([{from, to, mult, kind}]): [{from, to, mult, kinds}], in order of time.
   */
  function cut(from, to, segments) {
    if (!(to > from)) return [];
    segments = (segments || []).filter(function (s) { return s && s.to > from && s.from < to && s.mult > 0; });
    var points = [from, to];
    segments.forEach(function (s) {
      points.push(Math.max(s.from, from));
      points.push(Math.min(s.to, to));
    });
    points.sort(function (a, b) { return a - b; });
    var out = [];
    for (var i = 0; i + 1 < points.length; i++) {
      var a = points[i];
      var b = points[i + 1];
      if (b <= a) continue;
      var mult = 1;
      var kinds = [];
      segments.forEach(function (s) {
        if (s.from <= a && s.to >= b) {
          mult *= s.mult;
          if (s.kind && kinds.indexOf(s.kind) < 0) kinds.push(s.kind);
        }
      });
      kinds.sort();
      var last = out[out.length - 1];
      if (last && last.to === a && last.mult === mult && last.kinds.join() === kinds.join()) last.to = b;
      else out.push({ from: a, to: b, mult: mult, kinds: kinds });
    }
    return out;
  }

  /** The currencies of a game as Big values: {id: Big}. */
  function currencies(state, game) {
    var out = {};
    ((game && game.currencies) || []).forEach(function (c) {
      var v = get(state, c.path);
      if (v !== undefined && v !== null && Big.valid(v)) out[c.id] = Big.of(v);
    });
    return out;
  }

  /** after − before for each currency that changed. */
  function diff(before, after) {
    var out = {};
    for (var k in after) {
      var d = Big.sub(after[k], before[k] === undefined ? 0 : before[k]);
      if (!Big.isZero(d)) out[k] = d;
    }
    return out;
  }

  function derive(ctx, seg) {
    var c = {};
    for (var k in ctx) c[k] = ctx[k];
    c.mult = seg.mult;
    c.now = seg.to;
    return c;
  }

  /**
   * Offline progress in parts: runner.step(budgetMs) runs until done (true) or out of time
   * (false, call again). Formula when the game has sim.offline, else fixed steps of
   * offline.stepSec, at most offline.maxSteps in all. makeCtx(segment) builds each piece's ctx.
   */
  function runner(sim, state, game, p, segments, makeCtx, cfg) {
    cfg = cfg || {};
    var segs = segments && segments.length ? segments : (p.to > p.from ? [{ from: p.from, to: p.to, mult: 1, kinds: [] }] : []);
    var before = currencies(state, game);
    var extra = {};
    var events = [];
    var formula = typeof sim.offline === 'function';
    var stepSec = num(cfg['offline.stepSec'], 1);
    var maxSteps = num(cfg['offline.maxSteps'], 2000);
    var jobs = segs.map(function (s) {
      var sec = (s.to - s.from) / 1000;
      return { seg: s, sec: sec, steps: formula ? 1 : Math.max(1, Math.ceil(sec / stepSec)), done: 0 };
    });
    var total = 0;
    jobs.forEach(function (j) { total += j.steps; });
    if (!formula && total > maxSteps) {
      var scale = maxSteps / total;
      total = 0;
      jobs.forEach(function (j) {
        j.steps = Math.max(1, Math.floor(j.steps * scale));
        total += j.steps;
      });
    }
    var done = 0;
    var at = 0;

    function report(text) { if (text) events.push(String(text)); }

    function step(budgetMs, clock) {
      clock = clock || function () { return Date.now(); };
      budgetMs = budgetMs === undefined ? Infinity : budgetMs;
      var start = clock();
      while (at < jobs.length) {
        var j = jobs[at];
        var ctx = makeCtx ? makeCtx(j.seg) : { mult: j.seg.mult };
        ctx = derive(ctx, j.seg);
        ctx.offline = true;
        ctx.report = report;
        if (formula) {
          var r = sim.offline(state, j.sec * p.efficiency, ctx) || {};
          for (var k in r.gains || {}) extra[k] = extra[k] === undefined ? Big.of(r.gains[k]) : Big.add(extra[k], r.gains[k]);
          (r.events || []).forEach(report);
          j.done = 1;
          done++;
          at++;
        } else {
          var dt = j.sec * p.efficiency / j.steps;
          var real = (j.seg.to - j.seg.from) / j.steps;
          while (j.done < j.steps) {
            ctx.now = j.seg.from + real * (j.done + 1);
            sim.tick(state, dt, ctx);
            j.done++;
            done++;
            if (j.done % 16 === 0 && clock() - start >= budgetMs && j.done < j.steps) return false;
          }
          at++;
        }
        if (at < jobs.length && clock() - start >= budgetMs) return false;
      }
      return true;
    }

    return {
      step: step,
      progress: function () { return total ? done / total : 1; },
      report: function () {
        var gains = diff(before, currencies(state, game));
        var others = {};
        for (var k in extra) if (!has(gains, k) && !find(game.currencies, k)) others[k] = extra[k];
        var by = {};
        segs.forEach(function (s) {
          if (s.mult === 1 && !s.kinds.length) return;
          var key = s.kinds.join('+') + ':' + s.mult;
          by[key] = by[key] || { kinds: s.kinds, mult: s.mult, ms: 0 };
          by[key].ms += s.to - s.from;
        });
        var multiplied = [];
        for (var b in by) multiplied.push(by[b]);
        return { gains: gains, extra: others, events: events, segments: segs, multiplied: multiplied, formula: formula, steps: done };
      }
    };
  }

  /** Runs offline progress to the end (the engine, checks): the runner's report. */
  function runOffline(sim, state, game, p, segments, makeCtx, cfg) {
    var r = runner(sim, state, game, p, segments, makeCtx, cfg);
    r.step(Infinity);
    return r.report();
  }

  // ---- prestige ----------------------------------------------------------------------------------

  /** What a reset of the layer would give now: {gain, have, after, can, hint, minGain, bonusBefore, bonusAfter}. */
  function prestigeInfo(sim, game, state, layerId, cfg) {
    var layer = find(game.prestige, layerId);
    if (!layer) throw new Error('Нет слоя престижа ' + layerId);
    complete(state, game);
    var gain;
    try {
      gain = Big.of(sim.prestigeGain(state, layerId, cfg || {}), 0);
    } catch (error) {
      gain = Big.ZERO;
    }
    var have = Big.of(state.layers[layerId].currency, 0);
    var minGain = Big.of(layer.minGain === undefined ? 1 : layer.minGain);
    var can = Big.gt(gain, 0) && Big.gte(gain, minGain);
    var ratio = num(layer.hintRatio, num(cfg && cfg['prestige.hintRatio'], 0.5));
    var after = Big.add(have, gain);
    var info = {
      layer: layer, gain: gain, have: have, after: after, can: can, minGain: minGain,
      hint: can && Big.gte(gain, Big.mul(have, ratio)), bonusBefore: null, bonusAfter: null
    };
    if (typeof sim.bonus === 'function') {
      try {
        info.bonusBefore = sim.bonus(state, layerId, have, cfg || {});
        info.bonusAfter = sim.bonus(state, layerId, after, cfg || {});
      } catch (ignored) {
        info.bonusBefore = info.bonusAfter = null;
      }
    }
    return info;
  }

  /**
   * Resets for the layer: run anew (sim.init(ctx).run), lower layers' currency and state cleared
   * (their count stays), the layer's currency grows by the gain, meta untouched; then
   * sim.onPrestige(state, layerId, before, ctx) carries over what the game wants. {ok, gain, info}.
   */
  function prestige(sim, game, state, layerId, ctx) {
    var info = prestigeInfo(sim, game, state, layerId, ctx && ctx.config);
    if (!info.can) return { ok: false, gain: info.gain, info: info };
    var before = clone(state);
    var index = 0;
    for (var i = 0; i < game.prestige.length; i++) if (game.prestige[i].id === layerId) index = i;
    var fresh = sim.init(ctx) || {};
    var resets = info.layer.resets;
    if (!resets || resets.indexOf('run') >= 0) state.run = fresh.run || {};
    for (var j = 0; j < index; j++) {
      var lower = state.layers[game.prestige[j].id];
      var made = fresh.layers && fresh.layers[game.prestige[j].id];
      lower.currency = '0e0';
      lower.state = made && made.state ? clone(made.state) : {};
    }
    var layer = state.layers[layerId];
    layer.currency = Big.add(layer.currency, info.gain);
    layer.count = num(layer.count, 0) + 1;
    layer.best = Big.max(layer.best || 0, info.gain);
    layer.lastAt = ctx && ctx.now ? ctx.now : Date.now();
    if (typeof sim.onPrestige === 'function') sim.onPrestige(state, layerId, before, ctx);
    return { ok: true, gain: info.gain, info: info };
  }

  // ---- the inbox of the life links -----------------------------------------------------------------

  /**
   * Applies inbox entries not applied yet ({id, at, from, op: add|take|chest|stat, currency,
   * amount | percent | ofTake + mult, stat, reason}). Money moves by the currency's path in
   * game.json, so the game's code is not involved; a chest goes through sim.chest(state, rng);
   * stat adds to meta.stats[stat]. Applied ids are kept in meta.applied (last 500), so an entry
   * is never applied twice.
   */
  function applyInbox(state, game, sim, entries, ctx) {
    complete(state, game);
    var meta = state.meta;
    var seen = {};
    meta.applied.forEach(function (id) { seen[id] = true; });
    var out = { applied: [], moves: [], chests: [], changed: false };
    var list = (entries || []).slice().sort(function (a, b) { return (a.at || 0) - (b.at || 0) || (a.id < b.id ? -1 : 1); });
    list.forEach(function (e) {
      if (!e || !e.id || seen[e.id]) return;
      if (e.op === 'add' || e.op === 'take') {
        var cur = find(game.currencies, e.currency);
        if (cur) {
          var have = Big.of(get(state, cur.path), 0);
          var amount;
          if (e.op === 'take') {
            var want = e.amount !== undefined ? Big.of(e.amount) : Big.mul(have, num(e.percent, 0));
            if (cur.whole) want = Big.floor(want);
            amount = Big.max(0, Big.min(want, have));
            set(state, cur.path, Big.sub(have, amount));
            meta.taken[e.id] = Big.str(amount);
            amount = Big.neg(amount);
          } else {
            var mult = e.mult === undefined ? 1 : num(e.mult, 1);
            amount = e.ofTake !== undefined ? Big.mul(meta.taken[e.ofTake] || 0, mult) : Big.mul(e.amount || 0, mult);
            if (cur.whole) amount = Big.floor(amount);
            set(state, cur.path, Big.add(have, amount));
          }
          out.moves.push({ id: e.id, from: e.from, op: e.op, currency: cur.id, title: cur.title, amount: amount, reason: e.reason || '' });
        }
      } else if (e.op === 'chest') {
        var loot = null;
        if (typeof sim.chest === 'function') {
          loot = sim.chest(state, ctx.rng, ctx.config || {}) || null;
        } else {
          var sec = num(ctx.config && ctx.config['passenger.chestFallbackMin'], 30) * 60;
          var now = ctx.now || Date.now();
          var p = { from: now - sec * 1000, to: now, efficiency: 1 };
          var r = runOffline(sim, state, game, p, null, function () { return ctx; }, ctx.config);
          var items = [];
          for (var k in r.gains) {
            var c = find(game.currencies, k);
            items.push({ title: c ? c.title : k, amount: r.gains[k] });
          }
          loot = { title: 'Сундук: доход за ' + Math.round(sec / 60) + ' мин', items: items };
        }
        out.chests.push({ id: e.id, reason: e.reason || '', loot: loot });
      } else if (e.op === 'stat' && /^[a-zA-Z]\w{0,30}$/.test(String(e.stat || ''))) {
        // A counter of meta.stats (trips of «Пассажир»): never reset, only added to.
        meta.stats[e.stat] = num(meta.stats[e.stat], 0) + num(e.amount, 1);
      }
      meta.applied.push(e.id);
      seen[e.id] = true;
      out.applied.push(e);
      out.changed = true;
    });
    if (meta.applied.length > 500) meta.applied = meta.applied.slice(-500);
    return out;
  }

  /** The inbox without old entries already applied (older than `days`, 30 by default). */
  function inboxClean(entries, appliedIds, now, days) {
    var applied = {};
    (appliedIds || []).forEach(function (id) { applied[id] = true; });
    var edge = now - num(days, 30) * DAY;
    return (entries || []).filter(function (e) { return !(applied[e.id] && (e.at || 0) < edge); });
  }

  // ---- achievements --------------------------------------------------------------------------------

  /** Records the achievement; returns its declaration when it is new, null otherwise. */
  function achieve(state, game, id, now) {
    var a = find(game.achievements, id);
    if (!a) return null;
    complete(state, game);
    if (state.meta.achievements[id]) return null;
    state.meta.achievements[id] = now || Date.now();
    return a;
  }

  /** The ids in meta.achievements that are not in `reported` yet. */
  function unreported(state, reported) {
    var done = {};
    (reported || []).forEach(function (id) { done[id] = true; });
    var out = [];
    for (var id in (state.meta && state.meta.achievements) || {}) if (!done[id]) out.push(id);
    return out;
  }

  /** Items for «Ачивки за жизнь» per the share setting: 'marked' (life: true), 'all', 'none'. */
  function lifeItems(game, state, ids, share) {
    if (share === 'none') return [];
    var out = [];
    (ids || []).forEach(function (id) {
      var a = find(game.achievements, id);
      if (!a || (share !== 'all' && !a.life)) return;
      out.push({
        key: 'games/' + game.id + '/' + a.id, source: 'games', category: 'Игротека', title: a.title,
        text: a.lifeText || (a.text ? a.text + ' — «' + game.title + '»' : '«' + game.title + '»'),
        at: (state.meta.achievements || {})[a.id] || Date.now(), hidden: !!a.hidden,
        icon: 'modules/games/' + game.id + '/icon.svg'
      });
    });
    return out;
  }

  // ---- content stock ---------------------------------------------------------------------------------
  // data/games/<id>/stock.json: [{id, kind, createdAt, usedAt, item}]

  function stockCount(list, kind) {
    var n = 0;
    (list || []).forEach(function (s) { if (s.kind === kind && !s.usedAt) n++; });
    return n;
  }

  /** Takes the oldest unused item of the kind, marking it used; null when there is none. */
  /**
   * Takes the oldest unused item of a kind. `prefer` — {field: value}: the item matching most of
   * these fields goes first (an animal's name for its breed), then the oldest; none matching — any.
   * `strict` — only an item matching all of them (the picture of exactly this level), else null.
   */
  function stockTake(list, kind, now, prefer, strict) {
    var best = null;
    var bestScore = -1;
    function score(s) {
      var n = 0;
      if (!prefer || !s.item) return 0;
      for (var f in prefer) if (has(prefer, f) && prefer[f] !== undefined && prefer[f] !== null && String(s.item[f]) === String(prefer[f])) n++;
      return n;
    }
    var need = 0;
    if (strict && prefer) for (var p in prefer) if (has(prefer, p) && prefer[p] !== undefined && prefer[p] !== null) need++;
    (list || []).forEach(function (s) {
      if (s.kind !== kind || s.usedAt) return;
      var n = score(s);
      if (strict && n < need) return;
      if (n > bestScore || (n === bestScore && s.createdAt < best.createdAt)) {
        best = s;
        bestScore = n;
      }
    });
    if (best) best.usedAt = now || Date.now();
    return best;
  }

  /** Kinds below their minimum: [{kind, decl, unused, want}] (want — up to target, a batch at a time). */
  function stockNeeds(list, decls) {
    var out = [];
    (decls || []).forEach(function (d) {
      var unused = stockCount(list, d.kind);
      if (unused >= num(d.min, 10)) return;
      var want = Math.min(num(d.batch, 10), num(d.target, 30) - unused);
      if (want > 0) out.push({ kind: d.kind, decl: d, unused: unused, want: want });
    });
    return out;
  }

  /** Drops unused items older than `days` and keeps the last 500 used ones per kind (for repeats). */
  function stockExpire(list, now, days) {
    var edge = now - num(days, 30) * DAY;
    var used = {};
    var kept = (list || []).filter(function (s) { return s.usedAt || (s.createdAt || 0) >= edge; });
    for (var i = kept.length - 1; i >= 0; i--) {
      if (!kept[i].usedAt) continue;
      used[kept[i].kind] = (used[kept[i].kind] || 0) + 1;
      if (used[kept[i].kind] > 500) kept.splice(i, 1);
    }
    return kept;
  }

  /** A name made comparable: lower case, ё as е, letters and digits only. */
  function norm(text) {
    return String(text || '').toLowerCase().replace(/ё/g, 'е').replace(/[^a-zа-я0-9]+/g, ' ').trim();
  }

  /**
   * The schema of a stock item from its prompt file: a fenced block ```schema {...}``` with
   * {"key": "title", "fields": {"title": {"type": "string", "max": 30}, ...}}.
   */
  function stockSchema(md) {
    var m = /```schema\s*([\s\S]*?)```/.exec(String(md || ''));
    if (!m) return { key: 'title', fields: {} };
    try {
      var s = JSON.parse(m[1]);
      s.fields = s.fields || {};
      s.key = s.key || 'title';
      return s;
    } catch (error) {
      return { key: 'title', fields: {}, error: 'схема не читается: ' + error.message };
    }
  }

  var EMOJI = /[\uD83C-\uDBFF][\uDC00-\uDFFF]|[\u2600-\u27BF]/;

  /** Checks one item from the model: fields and lengths, no emoji, no repeat, sim.validateStock. */
  function stockCheck(item, sch, seen, sim, kind) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return 'элемент не объект';
    var fields = (sch && sch.fields) || {};
    for (var f in fields) {
      var spec = fields[f];
      var v = item[f];
      if (v === undefined || v === null || v === '') {
        if (spec.required === false) continue;
        return 'нет поля ' + f;
      }
      if (spec.type === 'svg' || spec.type === 'look') {
        var art = artOf();
        if (!art) return 'поле ' + f + ': нет engine/art.js';
        if (spec.type === 'look') {
          var bad = art.lookCheck(v);
          if (bad && spec.required === false) delete item[f];
          else if (bad) return 'поле ' + f + ': ' + bad;
          continue;
        }
        var pic = art.clean(v, { size: spec.size || 64, palette: spec.palette, max: spec.max, shapes: spec.shapes, animate: !!spec.animate, effects: !!spec.effects, animations: spec.animations });
        if (pic.error) {
          if (spec.required === false) {
            // A picture is a bonus: a bad one is dropped, the rest of the item stays.
            delete item[f];
            continue;
          }
          return 'поле ' + f + ': ' + pic.error;
        }
        // What is kept is the markup rebuilt from the shapes read, not the model's own text.
        item[f] = pic.svg;
        continue;
      }
      if (spec.type === 'number') {
        if (typeof v !== 'number' || !isFinite(v)) return 'поле ' + f + ' не число';
        if (spec.min !== undefined && v < spec.min) return 'поле ' + f + ' меньше ' + spec.min;
        if (spec.max !== undefined && v > spec.max) return 'поле ' + f + ' больше ' + spec.max;
      } else if (spec.type === 'array') {
        if (!Array.isArray(v)) return 'поле ' + f + ' не список';
        if (spec.max !== undefined && v.length > spec.max) return 'в поле ' + f + ' больше ' + spec.max;
      } else {
        if (typeof v !== 'string') return 'поле ' + f + ' не строка';
        if (spec.max !== undefined && v.length > spec.max) return 'поле ' + f + ' длиннее ' + spec.max;
        if (spec.min !== undefined && v.length < spec.min) return 'поле ' + f + ' короче ' + spec.min;
        if (spec.oneOf && spec.oneOf.indexOf(v) < 0) return 'поле ' + f + ' не из списка';
      }
    }
    if (EMOJI.test(JSON.stringify(item))) return 'эмодзи';
    var key = norm(item[(sch && sch.key) || 'title']);
    if (key && seen && seen[key]) return 'повтор: ' + key;
    if (sim && typeof sim.validateStock === 'function') {
      var r = sim.validateStock(kind, item);
      if (r) return String(r);
    }
    return null;
  }

  // ---- keeping saves ---------------------------------------------------------------------------------

  /**
   * Which saves to delete: [{id, seq, at, keep}] -> ids. Kept: the last `keep` by seq, the latest
   * of each of the last `dailyDays` days, and every row marked keep (copies before prestige,
   * migration, long offline; broken ones).
   */
  function retention(rows, now, keep, dailyDays) {
    var sorted = (rows || []).slice().sort(function (a, b) { return b.seq - a.seq; });
    var days = {};
    var out = [];
    var counted = 0;
    sorted.forEach(function (row) {
      if (row.keep) return;
      var day = dayKey(row.at);
      var recent = now - row.at <= dailyDays * DAY;
      if (counted++ < keep) {
        if (recent) days[day] = true;
        return;
      }
      if (recent && !days[day]) {
        days[day] = true;
        return;
      }
      out.push(row.id);
    });
    return out;
  }

  // ---- notifications ---------------------------------------------------------------------------------

  /** Is the moment within the quiet hours "23:00-08:00"? */
  function inQuiet(spec, ms) {
    var parts = String(spec || '').split('-');
    var from = minutes(parts[0]);
    var to = minutes(parts[1]);
    if (from === null || to === null || from === to) return false;
    var d = new Date(ms);
    var m = d.getHours() * 60 + d.getMinutes();
    return from < to ? m >= from && m < to : m >= from || m < to;
  }

  /** The moment moved past the quiet hours, when it falls in them. */
  function afterQuiet(spec, ms) {
    if (!inQuiet(spec, ms)) return ms;
    var to = minutes(String(spec).split('-')[1]);
    var d = new Date(ms);
    d.setHours(Math.floor(to / 60), to % 60, 0, 0);
    if (d.getTime() <= ms) d.setDate(d.getDate() + 1);
    return d.getTime();
  }

  /** The nearest event the owner enabled, within a day: {id, at, text, title} or null. */
  function nextNotify(events, game, cfg, now) {
    var best = null;
    (events || []).forEach(function (ev) {
      if (!ev || !cfg['notify.' + ev.id]) return;
      if (!(ev.at >= now) || ev.at > now + DAY) return;
      var at = afterQuiet(cfg['notify.quiet'], ev.at);
      var decl = find(game.notify, ev.id);
      if (!best || at < best.at) best = { id: ev.id, at: at, text: String(ev.text || ''), title: (decl && decl.title) || game.title };
    });
    return best;
  }

  // ---- checks on the code of a game -----------------------------------------------------------------

  /** Code with comments, strings and regular expressions blanked out (line breaks kept). */
  function strip(code) {
    var out = '';
    var i = 0;
    var n = code.length;
    var last = '';
    var ticks = [];
    while (i < n) {
      var ch = code.charAt(i);
      var next = code.charAt(i + 1);
      if (ch === '/' && next === '/') {
        while (i < n && code.charAt(i) !== '\n') i++;
        continue;
      }
      if (ch === '/' && next === '*') {
        var end = code.indexOf('*/', i + 2);
        end = end < 0 ? n : end + 2;
        out += code.slice(i, end).replace(/[^\n]/g, ' ');
        i = end;
        continue;
      }
      if (ch === '"' || ch === '\'' || ch === '`') {
        if (ch === '`') ticks.push(out.split('\n').length);
        var j = i + 1;
        while (j < n && code.charAt(j) !== ch) {
          if (code.charAt(j) === '\\') j++;
          if (ch !== '`' && code.charAt(j) === '\n') break;
          j++;
        }
        out += ch + code.slice(i + 1, j).replace(/[^\n]/g, ' ') + ch;
        i = j + 1;
        last = 'x';
        continue;
      }
      if (ch === '/' && /[(,=:[!&|?{};+\-*%<>~^]$|^$|return$|typeof$/.test(last)) {
        var k = i + 1;
        var inClass = false;
        while (k < n && code.charAt(k) !== '\n') {
          var c = code.charAt(k);
          if (c === '\\') { k += 2; continue; }
          if (c === '[') inClass = true;
          else if (c === ']') inClass = false;
          else if (c === '/' && !inClass) break;
          k++;
        }
        if (code.charAt(k) === '/') {
          out += '/' + code.slice(i + 1, k).replace(/[^\n]/g, ' ') + '/';
          i = k + 1;
          last = 'x';
          continue;
        }
      }
      out += ch;
      if (!/\s/.test(ch)) last = /[A-Za-z_$0-9]/.test(ch) ? (/[A-Za-z_$0-9]$/.test(last) ? last + ch : ch) : ch;
      i++;
    }
    return { code: out, ticks: ticks };
  }

  /**
   * What breaks the shared subset of JavaScript (files that run on a screen and in Rhino):
   * [{line, rule}]. strict: also the ES5 rules of the engine's own shared files.
   */
  function lint(code, strict) {
    var s = strip(String(code));
    var text = s.code;
    var problems = [];
    var lines = text.split('\n');
    var rules = [
      [/\bclass\s+[A-Za-z_$]/, 'class'],
      [/\basync\s+function\b|\basync\s*\(|\basync\s+[A-Za-z_$][\w$]*\s*=>/, 'async'],
      [/\bawait\b/, 'await'],
      [/\bfor\s*\(\s*const\b/, 'for (const …)'],
      // Rhino binds a loop's let once for all turns: closures inside see the last value.
      [/\bfor\s*\(\s*let\b/, 'for (let …)'],
      [/^\s*(import|export)\b/, 'import/export']
    ];
    if (strict) {
      rules.push([/=>/, 'стрелочная функция']);
      rules.push([/\b(let|const)\s/, 'let/const']);
    }
    lines.forEach(function (line, i) {
      rules.forEach(function (rule) { if (rule[0].test(line)) problems.push({ line: i + 1, rule: rule[1] }); });
    });
    if (strict) s.ticks.forEach(function (line) { problems.push({ line: line, rule: 'шаблонная строка' }); });
    // A spread in a call: f(...args). Rest parameters and array spreads are fine.
    var stack = [];
    for (var i = 0; i < text.length; i++) {
      var ch = text.charAt(i);
      if (ch === '(' || ch === '[' || ch === '{') stack.push(i);
      else if (ch === ')' || ch === ']' || ch === '}') stack.pop();
      else if (ch === '.' && text.substr(i, 3) === '...') {
        var open = stack[stack.length - 1];
        if (open !== undefined && text.charAt(open) === '(') {
          var before = text.slice(Math.max(0, open - 60), open);
          var close = matching(text, open);
          var after = close >= 0 ? text.slice(close + 1, close + 8) : '';
          var callee = /[\w$)\]]\s*$/.test(before) && !/\bfunction\s*[\w$]*\s*$/.test(before) && !/^\s*=>/.test(after) &&
            !/\b(if|while|for|switch|catch|return|typeof)\s*$/.test(before);
          if (callee) problems.push({ line: text.slice(0, i).split('\n').length, rule: 'f(...args)' });
        }
        i += 2;
      }
    }
    problems.sort(function (a, b) { return a.line - b.line; });
    return problems;
  }

  function matching(text, open) {
    var depth = 0;
    for (var i = open; i < text.length; i++) {
      var ch = text.charAt(i);
      if (ch === '(') depth++;
      else if (ch === ')') {
        depth--;
        if (depth === 0) return i;
      }
    }
    return -1;
  }

  /**
   * Addresses on the network in a game's files ([{path, text}]): every http(s):// except
   * https://assi.local/ and the XML namespaces of SVG (they are names, nothing is fetched).
   */
  function offlineScan(files) {
    var hits = [];
    (files || []).forEach(function (f) {
      var text = String(f.text || '');
      var re = /(?:https?:)?\/\/[A-Za-z0-9.-]+\.[A-Za-z]{2,}[^\s'"`)<>]*/g;
      var m;
      while ((m = re.exec(text))) {
        var url = m[0];
        if (/^https:\/\/assi\.local(\/|$)/.test(url)) continue;
        if (/^http:\/\/www\.w3\.org\//.test(url)) continue;
        if (url.indexOf('//') === 0) {
          // Protocol-relative only where something is loaded: src=, href=, url(, import.
          var lead = text.slice(Math.max(0, m.index - 12), m.index);
          if (!/(src|href)\s*=\s*['"]?$|url\(\s*['"]?$|from\s*['"]$|import\(\s*['"]$/.test(lead)) continue;
        }
        hits.push({ path: f.path, url: url.slice(0, 120), line: text.slice(0, m.index).split('\n').length });
      }
    });
    return hits;
  }

  return {
    VERSION: VERSION, FORMAT: FORMAT, RESERVED: RESERVED, SETTINGS: SETTINGS,
    validId: validId, schema: schema, coerce: coerce, config: config,
    get: get, set: set, clone: clone, find: find, dayKey: dayKey, minutes: minutes,
    complete: complete, newState: newState, validate: validate, pick: pick, card: card,
    plan: plan, cut: cut, currencies: currencies, diff: diff, runner: runner, runOffline: runOffline,
    prestigeInfo: prestigeInfo, prestige: prestige,
    applyInbox: applyInbox, inboxClean: inboxClean,
    achieve: achieve, unreported: unreported, lifeItems: lifeItems,
    stock: { count: stockCount, take: stockTake, needs: stockNeeds, expire: stockExpire, norm: norm, schema: stockSchema, check: stockCheck },
    retention: retention, inQuiet: inQuiet, afterQuiet: afterQuiet, nextNotify: nextNotify,
    strip: strip, lint: lint, offlineScan: offlineScan
  };
});

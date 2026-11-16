// Pictures for games: window.GameArt on a screen, require('../engine/art.js') in the engine and
// node. Three ways to get a picture, all SVG, so it is sharp at any size:
//
//   1. A sprite from a seed: pixel creatures, ships, items. A mask says which cells may be body,
//      the seed fills it, one half is mirrored, an outline goes round, tones come from one hue.
//      The same seed always gives the same sprite; seeds never run out.
//        var sp = GameArt.sprite({ seed: 'crawler', mask: 'invader', hue: 95, frames: 2 });
//        GameArt.svg(sp, { animate: true })        markup; two frames swap like old arcade foes
//        GameArt.draw(ctx, sp, x, y, cell, frame)  straight onto a canvas, cell = pixels a cell
//   2. A look the model chose for a thing it invented, drawn by the generator:
//        GameArt.lookCheck(look)                   null when fine, otherwise why not
//        GameArt.fromLook(look, 'Ледяной клещ')    a sprite: the name is the seed
//   3. A picture the model drew itself, as SVG text:
//        GameArt.clean(markup, { size: 64, palette: ['#e0b04a', …] })  {svg} or {error}
//      Only plain shapes pass (path, circle, rect, …, gradients); scripts, links, styles, images,
//      text and foreign colours do not. The result is rebuilt from what was read, not passed on.
//
//      With o.animate SMIL animation passes (no faster than 0,3 s a cycle, up to o.animations),
//      with o.effects a glow (filter: feGaussianBlur, feMerge); without them both are dropped.
//   GameArt.still(markup)               the picture without its animation (reduced motion)
//   GameArt.url(markup)                 a data: URL for <img src> or a canvas Image
//   GameArt.el(markup | sprite, o)      an <img> (screen only): o = {size, cls, alt, animate}
//   GameArt.masks / GameArt.MASKS       the masks: names and what each looks like (for prompts)
//   GameArt.lookRules() / svgRules(o)   the text for a prompt: how to describe a look or draw
//
// A stock field {"type": "look"} or {"type": "svg", "size": 64, "palette": [...]} is checked by
// the engine with these (Core.stock.check), and its rules go into the prompt by themselves.
// Shared subset, ES5 only.
(function (root, make) {
  var node = typeof require === 'function' && typeof exports !== 'undefined';
  var api = node ? make(require('./rng.js')) : make(root.Rng);
  if (typeof exports !== 'undefined') { for (var k in api) exports[k] = api[k]; } else { root.GameArt = api; }
})(this, function (Rng) {
  'use strict';

  // ---- masks ---------------------------------------------------------------------------------------
  // The left half and the middle column (the last one); the right half is its mirror.
  //   .  empty            -  body or empty        +  body or outline
  //   #  always body      o  always outline       e  an eye or a detail (accent colour) or body
  // Rows from `move` down change between frames: legs, tails, flames.
  var MASKS = {
    invader: { title: 'пришелец: широкий, с усиками и лапками', move: 6, rows: [
      '.....-', '...-.+', '..-++#', '.-+#e#', '-+####', '-+#+##', '-.+-+-', '..-..-', '.-....'] },
    ship: { title: 'корабль: острый нос вверх, крылья по бокам', move: 9, rows: [
      '.....#', '.....#', '....-#', '....+#', '...-+e', '...+##', '..-+##', '.-++##', '-+++##', '-+.+#+', '-..-+-', '.....-'] },
    beast: { title: 'зверь: голова с ушами или рогами, тело, лапы', move: 8, rows: [
      '..-....', '.-+-...', '.-++--+', '..-+++#', '..+#e##', '.-+####', '-++###+', '-+.++##', '..-++-+', '..-+.-+', '.-+..-.'] },
    slime: { title: 'слизень: капля без лап', move: 7, rows: [
      '......', '....-+', '...-+#', '..-+##', '.-+e##', '-+####', '-+####', '-+++++', '.-----'] },
    bug: { title: 'жук или паук: круглое тело и тонкие лапы по бокам', move: 6, rows: [
      '-.....', '.-..-+', '..-+##', '-.+#e#', '.-+###', '-.+###', '..-+#+', '.-..-.', '-.....'] },
    ghost: { title: 'призрак или череп: большая голова, глаза, рваный низ', move: 7, rows: [
      '...-++', '..-+##', '.-+###', '.+##e#', '.+####', '.-+###', '..+#+#', '..-+-+', '.-.-.-'] },
    robot: { title: 'механизм: квадратная голова, корпус, опоры', move: 8, rows: [
      '....-', '.-++#', '.+###', '.+e##', '.+###', '..+-#', '-+###', '+-+##', '-.+##', '..+.#', '.-+.+'] },
    plant: { title: 'растение или гриб: шляпка или крона и стебель', move: 8, rows: [
      '..-+++', '.-+###', '-+####', '+##e##', '-+++++', '....+#', '....+#', '...-+#', '...+##', '..-+++'] },
    blade: { title: 'клинок: длинное лезвие, гарда, рукоять', move: 99, rows: [
      '...#', '...#', '..+#', '..+#', '..+#', '..+#', '..+#', '..+#', '+++#', '.-+#', '...#', '..+#', '...-'] },
    potion: { title: 'склянка: узкое горлышко и круглое дно', move: 99, rows: [
      '...o#', '...+#', '...+#', '..-+#', '.-+##', '-+###', '+###e', '+####', '+####', '-+###', '.-+++'] },
    gem: { title: 'кристалл или амулет: ромб с гранями', move: 99, rows: [
      '...-#', '..-+#', '.-+##', '-+##e', '+####', '-+###', '.-+##', '..-+#', '...-#'] }
  };
  var NAMES = Object.keys(MASKS);

  // Cell values in a sprite: 0 empty, 1 outline, 2 shadow, 3 body, 4 light, 5 accent.
  var EMPTY = 0;
  var OUTLINE = 1;
  var SHADOW = 2;
  var BODY = 3;
  var LIGHT = 4;
  var ACCENT = 5;

  function clamp(x, a, b) { return x < a ? a : x > b ? b : x; }

  function hex2(n) {
    var s = Math.round(clamp(n, 0, 255)).toString(16);
    return s.length < 2 ? '0' + s : s;
  }

  /** hsl (h 0–360, s and l 0–100) as #rrggbb. */
  function hsl(h, s, l) {
    h = ((h % 360) + 360) % 360 / 360;
    s = clamp(s, 0, 100) / 100;
    l = clamp(l, 0, 100) / 100;
    function part(t) {
      if (t < 0) t += 1;
      if (t > 1) t -= 1;
      var q = l < 0.5 ? l * (1 + s) : l + s - l * s;
      var p = 2 * l - q;
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
      return p;
    }
    return '#' + hex2(part(h + 1 / 3) * 255) + hex2(part(h) * 255) + hex2(part(h - 1 / 3) * 255);
  }

  function seedOf(seed) {
    if (typeof seed === 'number' && isFinite(seed)) return seed >>> 0;
    return Rng.hash(String(seed === undefined ? 'sprite' : seed));
  }

  /** The tones of a sprite: [null, outline, shadow, body, light, accent]. */
  function tones(o, rng) {
    if (o.colors && o.colors.length >= 5) return [null].concat(o.colors.slice(0, 5));
    var h = o.hue === undefined ? rng.int(360) : Number(o.hue);
    var s = o.sat === undefined ? 45 + rng.int(25) : Number(o.sat);
    var l = o.light === undefined ? 52 : Number(o.light);
    var a = o.accent === undefined ? (h + 150 + rng.int(60)) % 360 : Number(o.accent);
    return [null, hsl(h, s * 0.7, Math.max(14, l - 30)), hsl(h, s, l - 14), hsl(h, s, l), hsl(h + 8, s * 0.9, Math.min(86, l + 16)), hsl(a, 85, 68)];
  }

  /** The half of a mask filled from rng: rows of cell values (no outline yet). */
  function fillHalf(mask, rng, o, from, prev) {
    var rows = mask.rows;
    var density = o.density === undefined ? 0.55 : clamp(Number(o.density), 0.1, 0.95);
    var eyes = o.eyes === undefined ? 0.75 : clamp(Number(o.eyes), 0, 1);
    var out = [];
    for (var y = 0; y < rows.length; y++) {
      var line = [];
      for (var x = 0; x < rows[y].length; x++) {
        if (prev && y < from) {
          line.push(prev[y][x]);
          continue;
        }
        var c = rows[y].charAt(x);
        var v = EMPTY;
        if (c === '#') v = BODY;
        else if (c === 'o') v = OUTLINE;
        else if (c === '-') v = rng.next() < density ? BODY : EMPTY;
        else if (c === '+') v = rng.next() < 0.5 ? BODY : OUTLINE;
        else if (c === 'e') v = rng.next() < eyes ? ACCENT : BODY;
        if (v === BODY) {
          // Light from above: lighter high, darker low, a little noise.
          var t = rows.length > 1 ? y / (rows.length - 1) : 0;
          var k = (1 - t) * 0.7 + rng.next() * 0.5;
          v = k > 0.85 ? LIGHT : k < 0.4 ? SHADOW : BODY;
        }
        line.push(v);
      }
      out.push(line);
    }
    return out;
  }

  /** The half mirrored into a full grid with a free border of one cell, then outlined. */
  function grow(half) {
    var hh = half.length;
    var hw = half[0].length;
    var w = hw * 2 - 1 + 2;
    var h = hh + 2;
    var cells = [];
    var i;
    for (i = 0; i < w * h; i++) cells.push(EMPTY);
    for (var y = 0; y < hh; y++) {
      for (var x = 0; x < hw; x++) {
        var v = half[y][x];
        cells[(y + 1) * w + (x + 1)] = v;
        cells[(y + 1) * w + (w - 2 - x)] = v;
      }
    }
    var filled = 0;
    var out = cells.slice();
    for (var yy = 0; yy < h; yy++) {
      for (var xx = 0; xx < w; xx++) {
        var at = yy * w + xx;
        if (cells[at] >= SHADOW) {
          filled++;
          continue;
        }
        if (cells[at] !== EMPTY) continue;
        var near = (xx > 0 && cells[at - 1] >= SHADOW) || (xx < w - 1 && cells[at + 1] >= SHADOW) ||
          (yy > 0 && cells[at - w] >= SHADOW) || (yy < h - 1 && cells[at + w] >= SHADOW);
        if (near) out[at] = OUTLINE;
      }
    }
    return { w: w, h: h, cells: out, filled: filled };
  }

  /**
   * A sprite: {w, h, frames: [cells…], colors, mask, seed}. o = {seed, mask (a name or
   * {rows, move}), hue, sat, light, accent, colors (five: outline, shadow, body, light, accent),
   * density, eyes, frames (1–4)}.
   */
  function sprite(o) {
    o = o || {};
    var mask = typeof o.mask === 'object' && o.mask && o.mask.rows ? o.mask : MASKS[o.mask] || MASKS.invader;
    var seed = seedOf(o.seed);
    var best = null;
    // A thin sprite reads as noise: a few more tries with the next seeds, the fullest kept.
    for (var tryNo = 0; tryNo < 6; tryNo++) {
      var rng = Rng.make((seed + tryNo * 0x9E3779B1) >>> 0);
      var half = fillHalf(mask, rng, o, 0, null);
      var g = grow(half);
      var share = g.filled / (g.w * g.h);
      if (!best || share > best.share) best = { half: half, g: g, rng: rng, share: share };
      if (share >= 0.28) break;
    }
    var colors = tones(o, Rng.make(seed ^ 0x5bd1e995));
    var frames = [best.g.cells];
    var count = clamp(Math.floor(Number(o.frames) || 1), 1, 4);
    for (var f = 1; f < count; f++) {
      var moved = fillHalf(mask, best.rng.fork('frame' + f), o, mask.move === undefined ? 99 : mask.move, best.half);
      frames.push(grow(moved).cells);
    }
    return { w: best.g.w, h: best.g.h, frames: frames, colors: colors, mask: o.mask || 'invader', seed: seed };
  }

  /** The path of one colour of one frame: a rectangle a run of cells in a row. */
  function runs(sp, cells, value) {
    var d = '';
    for (var y = 0; y < sp.h; y++) {
      var x = 0;
      while (x < sp.w) {
        if (cells[y * sp.w + x] !== value) {
          x++;
          continue;
        }
        var start = x;
        while (x < sp.w && cells[y * sp.w + x] === value) x++;
        d += 'M' + start + ' ' + y + 'h' + (x - start) + 'v1h' + (start - x) + 'z';
      }
    }
    return d;
  }

  function frameMarkup(sp, cells) {
    var out = '';
    for (var v = OUTLINE; v <= ACCENT; v++) {
      var d = runs(sp, cells, v);
      if (d) out += '<path fill="' + sp.colors[v] + '" d="' + d + '"/>';
    }
    return out;
  }

  /** A sprite as SVG markup. o = {frame, animate (frames swap), period (seconds), title}. */
  function svg(sp, o) {
    o = o || {};
    var head = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + sp.w + ' ' + sp.h + '" shape-rendering="crispEdges">';
    var n = sp.frames.length;
    if (!o.animate || n < 2) {
      var f = clamp(Math.floor(Number(o.frame) || 0), 0, n - 1);
      return head + frameMarkup(sp, sp.frames[f]) + '</svg>';
    }
    var period = Number(o.period) > 0 ? Number(o.period) : 0.4 * n;
    var style = '<style>@keyframes s{0%{opacity:1}' + (100 / n).toFixed(3) + '%{opacity:0}100%{opacity:0}}' +
      'g{opacity:0;animation:s ' + period + 's step-end infinite}';
    var groups = '';
    for (var i = 0; i < n; i++) {
      style += '.f' + i + '{animation-delay:' + (-(n - i) * period / n).toFixed(3) + 's}';
      groups += '<g class="f' + i + '">' + frameMarkup(sp, sp.frames[i]) + '</g>';
    }
    return head + style + '</style>' + groups + '</svg>';
  }

  /** A sprite onto a 2D canvas at (x, y), `cell` pixels a cell. */
  function draw(ctx, sp, x, y, cell, frame) {
    var cells = sp.frames[clamp(Math.floor(Number(frame) || 0), 0, sp.frames.length - 1)];
    var c = Number(cell) || 1;
    for (var yy = 0; yy < sp.h; yy++) {
      var xx = 0;
      while (xx < sp.w) {
        var v = cells[yy * sp.w + xx];
        if (!v) {
          xx++;
          continue;
        }
        var start = xx;
        while (xx < sp.w && cells[yy * sp.w + xx] === v) xx++;
        ctx.fillStyle = sp.colors[v];
        ctx.fillRect(x + start * c, y + yy * c, (xx - start) * c, c);
      }
    }
  }

  // ---- a look the model chose --------------------------------------------------------------------

  var HUES = '0 красный, 30 оранжевый, 50 янтарный, 60 жёлтый, 90 салатовый, 120 зелёный, 170 бирюзовый, ' +
    '200 голубой, 225 синий, 265 фиолетовый, 290 сиреневый, 320 розовый, 345 малиновый';

  function isHue(v) { return typeof v === 'number' && isFinite(v) && v >= 0 && v <= 360; }

  /** null when a look is fine: {mask, hue, accent?, light?}; otherwise why not. */
  function lookCheck(look) {
    if (!look || typeof look !== 'object' || Array.isArray(look)) return 'облик не объект';
    if (!MASKS[look.mask]) return 'облик: форма не из списка: «' + look.mask + '»';
    if (!isHue(look.hue)) return 'облик: hue не число 0–360';
    if (look.accent !== undefined && !isHue(look.accent)) return 'облик: accent не число 0–360';
    if (look.light !== undefined && ['dark', 'mid', 'pale'].indexOf(look.light) < 0) return 'облик: light не dark, mid или pale';
    if (look.sat !== undefined && !(typeof look.sat === 'number' && look.sat >= 0 && look.sat <= 100)) return 'облик: sat не число 0–100';
    return null;
  }

  /** A sprite from a look; `seed` (a name, an id) makes two foes of one look differ. */
  function fromLook(look, seed, o) {
    o = o || {};
    var light = { dark: 40, mid: 52, pale: 64 }[look && look.light] || 52;
    return sprite({ seed: seed === undefined ? JSON.stringify(look) : seed, mask: look && MASKS[look.mask] ? look.mask : 'invader',
      hue: look && isHue(look.hue) ? look.hue : undefined, accent: look && isHue(look.accent) ? look.accent : undefined,
      sat: look && typeof look.sat === 'number' && look.sat >= 0 && look.sat <= 100 ? look.sat : undefined,
      light: light, frames: o.frames || 2, density: o.density, eyes: o.eyes });
  }

  function lookRules() {
    var forms = NAMES.map(function (n) { return n + ' — ' + MASKS[n].title; }).join('; ');
    return 'Облик (поле-объект) рисует генератор пиксельных спрайтов по твоему описанию: ' +
      '{"mask": форма, "hue": цвет тела 0–360, "accent": цвет глаз и деталей 0–360 (не обязательно), ' +
      '"light": "dark" | "mid" | "pale" (не обязательно)}. Формы: ' + forms + '. Цвета: ' + HUES + '. ' +
      'Подбирай форму и цвет по смыслу: ледяное — голубое, огненное — красное и оранжевое, ядовитое — салатовое.';
  }

  // ---- pictures the model drew ------------------------------------------------------------------

  var SHAPES = { path: 1, circle: 1, ellipse: 1, rect: 1, line: 1, polyline: 1, polygon: 1 };
  var CONTAINERS = { svg: 1, g: 1, defs: 1, linearGradient: 1, radialGradient: 1 };
  var LEAVES = { stop: 1 };
  var SKIP = { title: 1, desc: 1, metadata: 1 };
  // With o.animate: SMIL animation (it plays in an <img> too, with no script). With o.effects: a
  // glow — a blur merged under the shape. Without them these elements are dropped, not refused.
  var ANIMATIONS = { animate: 1, animateTransform: 1 };
  var FILTERS = { filter: 1, feGaussianBlur: 1, feMerge: 1, feMergeNode: 1 };
  var ANIMATED = ['opacity', 'fill-opacity', 'stroke-opacity', 'fill', 'stroke', 'stop-color', 'stop-opacity', 'offset',
    'r', 'rx', 'ry', 'cx', 'cy', 'x', 'y', 'width', 'height', 'stroke-width', 'stroke-dashoffset', 'transform', 'gradientTransform', 'x1', 'y1', 'x2', 'y2', 'stdDeviation'];
  // The attributes of an animation and of a filter; checked by the element, not by the table above.
  var ANIM_ATTRS = {
    attributeName: ANIMATED, values: 'anim', from: 'anim', to: 'anim', by: 'anim', dur: 'time', begin: 'time',
    repeatCount: 'repeat', calcMode: ['discrete', 'linear', 'paced', 'spline'], keyTimes: 'list', keySplines: 'list',
    type: ['translate', 'scale', 'rotate', 'skewX', 'skewY'], additive: ['replace', 'sum'], accumulate: ['none', 'sum'],
    fill: ['freeze', 'remove']
  };
  var FILTER_ATTRS = {
    id: 'id', x: 'number', y: 'number', width: 'number', height: 'number', filterUnits: ['userSpaceOnUse', 'objectBoundingBox'],
    stdDeviation: 'points', 'in': 'input', result: 'id'
  };
  var NUMERIC = ['cx', 'cy', 'r', 'rx', 'ry', 'x', 'y', 'x1', 'y1', 'x2', 'y2', 'width', 'height', 'stroke-width',
    'opacity', 'fill-opacity', 'stroke-opacity', 'stop-opacity', 'offset', 'fx', 'fy', 'stroke-miterlimit'];
  var ATTRS = {
    d: 'path', points: 'points', transform: 'transform', fill: 'paint', stroke: 'paint', 'stop-color': 'color',
    'stroke-linecap': ['butt', 'round', 'square'], 'stroke-linejoin': ['miter', 'round', 'bevel'],
    'fill-rule': ['nonzero', 'evenodd'], 'clip-rule': ['nonzero', 'evenodd'],
    gradientUnits: ['userSpaceOnUse', 'objectBoundingBox'], gradientTransform: 'transform', id: 'id',
    'stroke-dasharray': 'points', viewBox: 'points'
  };
  NUMERIC.forEach(function (a) { ATTRS[a] = 'number'; });

  var NAMED = { white: '#ffffff', black: '#000000' };

  function colorOk(v, palette) {
    var c = String(v).trim().toLowerCase();
    if (c === 'none') return true;
    if (/^url\(#[a-z][\w-]{0,30}\)$/.test(c)) return true;
    if (NAMED[c]) c = NAMED[c];
    if (/^#[0-9a-f]{3}$/.test(c)) c = '#' + c.charAt(1) + c.charAt(1) + c.charAt(2) + c.charAt(2) + c.charAt(3) + c.charAt(3);
    if (!/^#[0-9a-f]{6}$/.test(c)) return false;
    if (!palette || !palette.length) return true;
    for (var i = 0; i < palette.length; i++) if (String(palette[i]).toLowerCase() === c) return true;
    return false;
  }

  function valueOk(kind, v, palette) {
    if (/[<>"\\]|javascript|expression|data:|&#/i.test(v)) return false;
    if (Array.isArray(kind)) return kind.indexOf(v.trim()) >= 0;
    if (kind === 'number') return /^\s*-?[\d.]+(e-?\d+)?%?\s*$/i.test(v);
    if (kind === 'path') return /^[MmLlHhVvCcSsQqTtAaZz\d.,\s+eE-]*$/.test(v);
    if (kind === 'points') return /^[\d.,\s+eE-]*$/.test(v);
    if (kind === 'transform') {
      if (!/^[a-zA-Z\d.,\s()+eE-]*$/.test(v)) return false;
      var words = v.match(/[a-zA-Z]+(?=\s*\()/g) || [];
      for (var i = 0; i < words.length; i++) if (['translate', 'scale', 'rotate', 'matrix', 'skewX', 'skewY'].indexOf(words[i]) < 0) return false;
      return true;
    }
    if (kind === 'paint') return colorOk(v, palette);
    if (kind === 'color') return colorOk(v, palette) && !/url/i.test(v);
    if (kind === 'id') return /^[a-zA-Z][\w-]{0,30}$/.test(v);
    if (kind === 'input') return /^(SourceGraphic|SourceAlpha|[a-zA-Z][\w-]{0,30})$/.test(v.trim());
    if (kind === 'filterRef') return /^\s*url\(#[a-zA-Z][\w-]{0,30}\)\s*$/.test(v);
    if (kind === 'list') return /^[\d.,;\s+eE-]*$/.test(v);
    if (kind === 'repeat') return /^\s*(indefinite|\d+(\.\d+)?)\s*$/.test(v);
    if (kind === 'time') return /^\s*\d+(\.\d+)?(ms|s)?\s*$/.test(v);
    if (kind === 'anim') {
      // A list of values by «;»: colours of the palette, or numbers (a transform's numbers too).
      var parts = v.split(';');
      for (var p = 0; p < parts.length; p++) {
        var one = parts[p].trim();
        if (!one) continue;
        if (/^#|^[a-z]+$/i.test(one)) {
          if (!colorOk(one, palette)) return false;
        } else if (!/^[\d.,\s+eE%-]*$/.test(one)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /** Seconds of a SMIL time: «2s», «800ms», «1.5». */
  function seconds(v) {
    var m = /^\s*(\d+(?:\.\d+)?)(ms|s)?\s*$/.exec(String(v));
    if (!m) return NaN;
    return Number(m[1]) / (m[2] === 'ms' ? 1000 : 1);
  }

  /**
   * A picture of the model made safe: {svg} — rebuilt from the shapes read — or {error}.
   * o = {size (the viewBox must be 0 0 size size), palette (colours allowed; any when none),
   * max (characters, 8000), shapes (most shapes, 120)}.
   */
  function clean(markup, o) {
    o = o || {};
    var text = String(markup === undefined || markup === null ? '' : markup).trim();
    var max = Number(o.max) || 8000;
    if (!text) return { error: 'пустая картинка' };
    if (text.length > max) return { error: 'картинка длиннее ' + max + ' знаков' };
    text = text.replace(/^<\?xml[^>]*\?>/i, '').replace(/<!--[\s\S]*?-->/g, '').trim();
    if (/<!|<\?/.test(text)) return { error: 'в картинке DOCTYPE, CDATA или инструкция' };
    var re = /<\s*(\/)?\s*([a-zA-Z][\w:-]*)((?:\s+[^\s=>\/]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(\/)?\s*>|([^<]+)|(<)/g;
    var stack = [];
    var out = '';
    var shapes = 0;
    var animations = 0;
    var skipping = 0;
    var skipName = '';
    var rootSeen = false;
    var m;
    while ((m = re.exec(text))) {
      if (m[6]) return { error: 'картинка не читается: лишний «<»' };
      if (m[5] !== undefined) {
        if (!skipping && /\S/.test(m[5])) return { error: 'в картинке текст: «' + m[5].trim().slice(0, 20) + '»' };
        continue;
      }
      var closing = !!m[1];
      var name = m[2];
      var self = !!m[4];
      if (skipping) {
        if (name === skipName && !self) skipping += closing ? -1 : 1;
        continue;
      }
      // Dropped quietly: notes, and animation or filters the game did not allow.
      if (SKIP[name] || (ANIMATIONS[name] && !o.animate) || (FILTERS[name] && !o.effects)) {
        if (!closing && !self) {
          skipping = 1;
          skipName = name;
        }
        continue;
      }
      var anim = !!ANIMATIONS[name];
      var fx = !!FILTERS[name];
      if (!SHAPES[name] && !CONTAINERS[name] && !LEAVES[name] && !anim && !fx) return { error: 'в картинке запрещённый элемент <' + name + '>' };
      if (closing) {
        if (stack.pop() !== name) return { error: 'теги картинки не сходятся: </' + name + '>' };
        out += '</' + name + '>';
        continue;
      }
      if (!stack.length) {
        if (name !== 'svg' || rootSeen) return { error: 'картинка должна быть одним <svg>' };
        rootSeen = true;
      } else if (name === 'svg') {
        return { error: '<svg> внутри <svg>' };
      }
      var attrs = '';
      var viewBox = null;
      var are = /([^\s=]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
      var a;
      while ((a = are.exec(m[3] || ''))) {
        var an = a[1];
        var av = a[2] !== undefined ? a[2] : a[3];
        if (/^on/i.test(an) || /href$/i.test(an) || an === 'style') return { error: 'в картинке запрещённый атрибут ' + an };
        if (an === 'xmlns' || /^xmlns:/.test(an) || an === 'version' || an === 'width' && name === 'svg' || an === 'height' && name === 'svg') continue;
        var table = anim ? ANIM_ATTRS : fx ? FILTER_ATTRS : ATTRS;
        var kind = table.hasOwnProperty(an) ? table[an] : null;
        if (!kind && an === 'filter' && !anim && !fx) {
          if (!o.effects) continue;
          kind = 'filterRef';
        }
        if (!kind) continue;
        if (!valueOk(kind, av, o.palette)) {
          if (kind === 'paint' || kind === 'color') return { error: 'цвет не из палитры: ' + an + '="' + av.slice(0, 20) + '"' };
          return { error: 'плохое значение ' + an + '="' + av.slice(0, 30) + '"' };
        }
        // No flicker: an animation runs no faster than `minDur` (0,3 s) a cycle.
        if (anim && an === 'dur' && !(seconds(av) >= (Number(o.minDur) || 0.3))) {
          return { error: 'анимация быстрее ' + (Number(o.minDur) || 0.3) + ' с: dur="' + av.trim() + '"' };
        }
        if (an === 'viewBox') {
          viewBox = av.trim().split(/[\s,]+/).map(Number);
          continue;
        }
        attrs += ' ' + an + '="' + av.trim() + '"';
      }
      if (name === 'svg') {
        if (o.size && (!viewBox || viewBox.length !== 4 || viewBox[0] !== 0 || viewBox[1] !== 0 || viewBox[2] !== o.size || viewBox[3] !== o.size)) {
          return { error: 'viewBox должен быть "0 0 ' + o.size + ' ' + o.size + '"' };
        }
        var vb = viewBox && viewBox.length === 4 ? viewBox.join(' ') : '0 0 ' + (o.size || 64) + ' ' + (o.size || 64);
        out += '<svg xmlns="http://www.w3.org/2000/svg" viewBox="' + vb + '"' + attrs + (self ? '/>' : '>');
        if (!self) stack.push(name);
        continue;
      }
      if (SHAPES[name]) shapes++;
      if (shapes > (Number(o.shapes) || 120)) return { error: 'в картинке больше ' + (Number(o.shapes) || 120) + ' фигур' };
      if (anim && ++animations > (Number(o.animations) || 16)) return { error: 'в картинке больше ' + (Number(o.animations) || 16) + ' анимаций' };
      var parent = stack[stack.length - 1];
      if (LEAVES[name] && parent !== 'linearGradient' && parent !== 'radialGradient') {
        return { error: '<stop> вне градиента' };
      }
      if ((name === 'feGaussianBlur' || name === 'feMerge') && parent !== 'filter') return { error: '<' + name + '> вне <filter>' };
      if (name === 'feMergeNode' && parent !== 'feMerge') return { error: '<feMergeNode> вне <feMerge>' };
      if (name === 'filter' && parent !== 'defs') return { error: '<filter> вне <defs>' };
      out += '<' + name + attrs + (self ? '/>' : '>');
      if (!self) stack.push(name);
    }
    if (!rootSeen) return { error: 'нет <svg>' };
    if (stack.length) return { error: 'не закрыт <' + stack[stack.length - 1] + '>' };
    if (!shapes) return { error: 'в картинке нет ни одной фигуры' };
    return { svg: out, shapes: shapes, animations: animations };
  }

  /** The same picture standing still: animation taken out (reduced motion, a setting, a list). */
  function still(markup) {
    return String(markup || '')
      .replace(/<animate(Transform)?\b[^>]*\/>/g, '')
      .replace(/<animate(Transform)?\b[^>]*>[\s\S]*?<\/animate(Transform)?>/g, '');
  }

  /**
   * How to draw, for a prompt. o = {size, palette, shapes, max, style (the game's own words on
   * style), animate, effects}.
   */
  function svgRules(o) {
    o = o || {};
    var size = o.size || 64;
    return 'Значок (поле-строка) — SVG, который ты рисуешь сам: один <svg viewBox="0 0 ' + size + ' ' + size + '"> ' +
      'только с path, circle, ellipse, rect, line, polyline, polygon, g и градиентами (linearGradient, radialGradient, stop внутри defs). ' +
      'Без текста, script, style, image, use, ссылок и атрибутов on*. ' +
      (o.palette && o.palette.length ? 'Цвета fill и stroke — только из палитры: ' + o.palette.join(', ') + ' (или none). ' : '') +
      (o.animate ? 'Можно оживить: <animate attributeName="opacity|fill|stroke|r|cx|cy|stroke-dashoffset|offset|stop-color…" ' +
        'values="…" dur="…" repeatCount="indefinite"/> и <animateTransform attributeName="transform" type="rotate|translate|scale" ' +
        'values="…" dur="…" repeatCount="indefinite"/> внутри фигуры или градиента: мерцание, переливы, искры, пламя, волны. ' +
        'Плавно: dur не меньше 0.3s (лучше 1–4s), не больше ' + (o.animations || 16) + ' анимаций, без резких вспышек. ' : '') +
      (o.effects ? 'Можно свечение: <filter id="…"><feGaussianBlur stdDeviation="…" result="b"/><feMerge><feMergeNode in="b"/>' +
        '<feMergeNode in="SourceGraphic"/></feMerge></filter> в defs и filter="url(#…)" у фигуры. ' : '') +
      'Стиль: ' + (o.style ? String(o.style).replace(/\.?\s*$/, '') : 'крупная понятная пиктограмма предмета по центру, отступ около ' + Math.round(size / 10) +
        ', 2–4 цвета, светлый контур или блик, без мелких деталей') + '. ' +
      'До ' + (o.shapes || 30) + ' фигур, до ' + (o.max || 2500) + ' знаков. Кавычки внутри SVG — одинарные, чтобы не ломать JSON.';
  }

  // ---- showing -------------------------------------------------------------------------------------

  function url(markup) { return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(String(markup)); }

  /** An <img> of a sprite or of clean markup (screen only). o = {size (px), cls, alt, animate, frame}. */
  function el(x, o) {
    o = o || {};
    var markup = x && typeof x === 'object' && x.frames ? svg(x, o) : String(x || '');
    var img = document.createElement('img');
    img.className = 'g-art' + (x && x.frames ? ' g-pixel' : '') + (o.cls ? ' ' + o.cls : '');
    img.alt = o.alt || '';
    img.draggable = false;
    img.decoding = 'async';
    if (o.size) {
      img.style.width = o.size + 'px';
      img.style.height = o.size + 'px';
    }
    img.src = url(markup);
    return img;
  }

  return {
    MASKS: MASKS, masks: NAMES, sprite: sprite, svg: svg, draw: draw, hsl: hsl,
    lookCheck: lookCheck, fromLook: fromLook, lookRules: lookRules,
    clean: clean, still: still, svgRules: svgRules, url: url, el: el
  };
});

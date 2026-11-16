/* Concept paintings for Assi's editable game gallery. Canvas coordinates: 1000 × 620. */
(function () {
  'use strict';
  const TAU = Math.PI * 2;
  const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
  const hash = n => { const k = Math.sin(n * 127.13 + 74.17) * 43758.5453; return k - Math.floor(k); };
  const ease = x => 1 - Math.pow(1 - clamp(x), 3);
  const ink = '#26383a';
  function poly(c, p, fill, stroke, width = 1) {
    c.beginPath(); p.forEach((v, i) => i ? c.lineTo(v[0], v[1]) : c.moveTo(v[0], v[1])); c.closePath();
    if (fill) { c.fillStyle = fill; c.fill(); } if (stroke) { c.strokeStyle = stroke; c.lineWidth = width; c.stroke(); }
  }
  function rect(c, x, y, w, h, fill, r = 0, stroke) {
    c.beginPath(); c.roundRect(x, y, w, h, r); if (fill) { c.fillStyle = fill; c.fill(); }
    if (stroke) { c.strokeStyle = stroke; c.lineWidth = 1; c.stroke(); }
  }
  function ellipse(c, x, y, rx, ry, fill, angle = 0, stroke) {
    c.beginPath(); c.ellipse(x, y, Math.max(.01, rx), Math.max(.01, ry), angle, 0, TAU);
    if (fill) { c.fillStyle = fill; c.fill(); } if (stroke) { c.strokeStyle = stroke; c.lineWidth = 1; c.stroke(); }
  }
  function line(c, points, color, width = 1) {
    c.beginPath(); points.forEach((p, i) => i ? c.lineTo(p[0], p[1]) : c.moveTo(p[0], p[1]));
    c.strokeStyle = color; c.lineWidth = width; c.lineCap = 'round'; c.lineJoin = 'round'; c.stroke();
  }
  function curve(c, start, cp1, cp2, end, color, width = 1) {
    c.beginPath(); c.moveTo(...start); c.bezierCurveTo(...cp1, ...cp2, ...end);
    c.strokeStyle = color; c.lineWidth = width; c.lineCap = 'round'; c.stroke();
  }
  function gradient(c, x, y, x2, y2, stops) {
    const g = c.createLinearGradient(x, y, x2, y2); stops.forEach((color, i) => g.addColorStop(i / (stops.length - 1), color)); return g;
  }
  function glow(c, x, y, radius, color) {
    const g = c.createRadialGradient(x, y, 0, x, y, radius); g.addColorStop(0, color); g.addColorStop(1, 'transparent');
    rect(c, x - radius, y - radius, radius * 2, radius * 2, g);
  }
  function spark(c, x, y, size, color) {
    poly(c, [[x, y - size], [x + size * .22, y - size * .22], [x + size, y], [x + size * .22, y + size * .22], [x, y + size], [x - size * .22, y + size * .22], [x - size, y], [x - size * .22, y - size * .22]], color);
  }
  function sky(c, top, bottom) { rect(c, 0, 0, 1000, 620, gradient(c, 0, 0, 0, 620, [top, bottom])); }
  function cloud(c, x, y, s, color) {
    c.save(); c.translate(x, y); c.scale(s, s);
    ellipse(c, 0, 0, 75, 17, color); ellipse(c, -26, -9, 33, 20, color); ellipse(c, 17, -20, 39, 29, color); ellipse(c, 55, -4, 30, 17, color); c.restore();
  }
  function stars(c, t, color = '#f5dfad', n = 80, height = 430) {
    for (let i = 0; i < n; i++) {
      c.globalAlpha = .25 + .45 * (.5 + .5 * Math.sin(t * .6 + i));
      const x = hash(i + 2) * 1000, y = hash(i + 84) * height;
      if (i % 11 === 0) spark(c, x, y, 3, color); else ellipse(c, x, y, .7 + hash(i) * 1.2, .7 + hash(i) * 1.2, color);
    } c.globalAlpha = 1;
  }
  function motes(c, t, color, x, y, w, h, n = 22) {
    for (let i = 0; i < n; i++) {
      const px = x + hash(i * 7 + 3) * w + Math.sin(t * .35 + i) * 7;
      const py = y + ((hash(i * 11 + 5) * h - t * (3 + hash(i) * 4)) % h + h) % h;
      c.globalAlpha = .22 + .38 * Math.sin((py - y) / h * Math.PI);
      ellipse(c, px, py, 1.2 + hash(i + 20), 1.2 + hash(i + 20), color);
    } c.globalAlpha = 1;
  }
  function burst(c, state, x, y, color, n = 30, reach = 150) {
    const q = state.elapsed / 2.4; if (q < 0 || q > 1 || !state.count) return;
    for (let i = 0; i < n; i++) {
      const a = i * 2.3999, d = reach * (.35 + hash(i) * .65) * ease(q);
      c.globalAlpha = (1 - q) * .9;
      spark(c, x + Math.cos(a) * d, y + Math.sin(a) * d * .66 - q * 36, (2 + hash(i + 40) * 4) * (1 - q), color);
    } c.globalAlpha = 1;
  }
  function tree(c, x, y, s, color = '#397b61', t = 0) {
    c.save(); c.translate(x, y); c.scale(s, s); ellipse(c, 8, 4, 26, 8, '#1b3c382a');
    line(c, [[0, 0], [-2, -49]], '#715548', 8);
    const sway = Math.sin(t * .65 + x) * 1.8;
    ellipse(c, -12 + sway, -48, 25, 28, color); ellipse(c, 12 + sway, -55, 23, 33, color);
    ellipse(c, -5 + sway, -70, 22, 25, color); ellipse(c, -13 + sway, -65, 12, 14, '#b3d68e55');
    c.restore();
  }
  function pine(c, x, y, s, color) {
    rect(c, x - 3 * s, y - 30 * s, 6 * s, 32 * s, '#705447');
    for (let i = 0; i < 3; i++) poly(c, [[x, y - (75 - i * 16) * s], [x + (20 + i * 7) * s, y - (22 - i * 8) * s], [x - (20 + i * 7) * s, y - (22 - i * 8) * s]], color);
  }
  function person(c, x, y, s, color, pose = 0, hair = '#42383a') {
    c.save(); c.translate(x, y); c.scale(s, s);
    ellipse(c, 0, 0, 11, 3, '#172e3325');
    line(c, [[-4, -17], [-5 - Math.sin(pose) * 3, -2]], '#323b44', 5);
    line(c, [[4, -17], [5 + Math.sin(pose) * 3, -2]], '#323b44', 5);
    poly(c, [[-9, -37], [8, -37], [11, -15], [-11, -15]], color);
    line(c, [[-7, -34], [-13, -22 + Math.sin(pose) * 3]], color, 5);
    line(c, [[8, -33], [14, -23 - Math.sin(pose) * 3]], color, 5);
    ellipse(c, 0, -43, 7, 9, '#edc39d'); ellipse(c, -1, -48, 7.4, 4.5, hair);
    ellipse(c, 3, -43, 1, 1, '#2d3437'); c.restore();
  }
  function flag(c, x, y, s, color, t) {
    line(c, [[x, y + 36 * s], [x, y - 30 * s]], '#d5bb88', 2.3 * s);
    const q = Math.sin(t * 1.5 + x) * 4 * s;
    c.beginPath(); c.moveTo(x, y - 30 * s); c.bezierCurveTo(x + 15 * s, y - 38 * s + q, x + 24 * s, y - 24 * s - q, x + 39 * s, y - 30 * s + q);
    c.lineTo(x + 35 * s, y - 9 * s + q); c.bezierCurveTo(x + 20 * s, y - 4 * s, x + 14 * s, y - 16 * s + q, x, y - 10 * s); c.closePath(); c.fillStyle = color; c.fill();
  }
  function arch(c, x, y, w, h, fill, stroke, lineWidth = 1) {
    c.beginPath(); c.moveTo(x, y + h); c.lineTo(x, y + w / 2); c.arc(x + w / 2, y + w / 2, w / 2, Math.PI, TAU); c.lineTo(x + w, y + h); c.closePath();
    if (fill) { c.fillStyle = fill; c.fill(); } if (stroke) { c.lineWidth = lineWidth; c.strokeStyle = stroke; c.stroke(); }
  }
  function crown(c, x, y, s) {
    poly(c, [[x - 13 * s, y], [x - 16 * s, y - 15 * s], [x - 6 * s, y - 8 * s], [x, y - 21 * s], [x + 6 * s, y - 8 * s], [x + 16 * s, y - 15 * s], [x + 13 * s, y]], '#e7bf5f', '#a97c35');
    rect(c, x - 13 * s, y - 3 * s, 26 * s, 7 * s, '#f3d78a', 2); ellipse(c, x, y, 2 * s, 3 * s, '#b75554');
  }
  function house(c, x, y, s, roof = '#bd6954', lit = true) {
    c.save(); c.translate(x, y); c.scale(s, s);
    ellipse(c, 10, 5, 49, 14, '#203d3320');
    poly(c, [[-34, -35], [5, -20], [5, 15], [-34, 0]], '#e8d3a8', '#8a806152');
    poly(c, [[5, -20], [40, -39], [40, -3], [5, 15]], '#bda77c');
    poly(c, [[-42, -37], [-6, -69], [14, -57], [5, -17]], roof, '#4f4b4430');
    poly(c, [[-6, -69], [32, -57], [49, -39], [5, -17]], '#934c43');
    line(c, [[-6, -69], [32, -57], [49, -39]], '#edaf85', 2);
    poly(c, [[-17, -21], [-7, -17], [-7, -5], [-17, -9]], lit ? '#ffe7a6' : '#657c77');
    poly(c, [[17, -13], [28, -19], [28, -7], [17, -1]], '#e2c887');
    line(c, [[-26, -30], [-26, 3]], '#997b62', 3); line(c, [[-34, -12], [4, 3]], '#997b62', 2);
    rect(c, 20, -77, 10, 23, '#d9bc91'); poly(c, [[18, -77], [27, -83], [32, -79], [24, -73]], '#715c4d');
    c.restore();
  }
  function castleTower(c, x, y, s, t) {
    c.save(); c.translate(x, y); c.scale(s, s);
    rect(c, -28, -115, 56, 120, gradient(c, -28, 0, 28, 0, ['#dccdb3', '#aaa590']), 5);
    ellipse(c, 0, -115, 28, 9, '#efe1c6');
    poly(c, [[-35, -115], [0, -173], [35, -115]], '#377c7a', '#255b5c');
    line(c, [[0, -173], [13, -116]], '#76b0a1', 2);
    for (let i = 0; i < 3; i++) { arch(c, -6, -94 + i * 30, 12, 20, i === 0 ? '#f5d78b' : '#526d6a'); line(c, [[-26, -68 + i * 31], [26, -68 + i * 31]], '#8a938450'); }
    flag(c, 0, -184, .8, '#bd5c54', t); c.restore();
  }

  function dynastyRealm(c, t, state) {
    sky(c, '#9fc8c2', '#e9e5bf');
    glow(c, 777, 112, 145, '#fff4bd95'); ellipse(c, 777, 112, 42, 42, '#fff4cd');
    cloud(c, 210 + Math.sin(t * .07) * 18, 102, 1.1, '#f9f3da95'); cloud(c, 831 - t % 100 * .1, 184, .8, '#fff4d078');
    poly(c, [[0, 292], [143, 167], [258, 278], [403, 143], [578, 290], [745, 201], [1000, 292], [1000, 620], [0, 620]], '#8aa99b');
    poly(c, [[0, 331], [110, 268], [279, 348], [486, 256], [679, 342], [899, 260], [1000, 318], [1000, 620], [0, 620]], '#658e78');
    c.beginPath(); c.moveTo(1000, 338); c.bezierCurveTo(707, 340, 900, 477, 596, 620); c.lineTo(1000, 620); c.closePath(); c.fillStyle = '#81babc'; c.fill();
    for (let i = 0; i < 17; i++) line(c, [[789 + hash(i) * 180, 365 + i * 14], [821 + hash(i) * 160, 365 + i * 14]], '#d2e5d16a', 1.4);
    ellipse(c, 479, 479, 323, 82, '#244c4b35');
    poly(c, [[172, 359], [398, 245], [734, 299], [802, 431], [570, 553], [289, 485]], '#7b8d68');
    poly(c, [[172, 359], [289, 485], [570, 553], [535, 595], [303, 543], [208, 464]], '#596f62');
    poly(c, [[570, 553], [802, 431], [734, 493], [665, 552], [535, 595]], '#3d5e59');
    for (let i = 0; i < 17; i++) { const x = 227 + i * 29; line(c, [[x, 461 + Math.sin(i * .6) * 18], [x - 8, 508 + Math.sin(i * .6) * 21]], '#334e4c55', 3); }
    poly(c, [[182, 355], [398, 241], [727, 294], [788, 424], [565, 535], [294, 474]], '#8caa77');
    curve(c, [290, 440], [407, 378], [552, 459], [712, 339], '#d9c597', 24);
    curve(c, [462, 429], [453, 390], [503, 360], [492, 292], '#c7b285', 19);
    for (let i = 0; i < 12; i++) tree(c, 185 + hash(i + 2) * 586, 323 + hash(i + 50) * 159, .48 + hash(i + 90) * .28, '#47795c', t);
    house(c, 330, 358, .83, '#b77752'); house(c, 382, 411, .9); house(c, 595, 416, 1.02, '#c9855f'); house(c, 659, 368, .85, '#688e86');
    house(c, 270, 419, .7, '#b88a5c');
    poly(c, [[409, 312], [461, 290], [573, 318], [535, 360], [409, 339]], '#c4bfa5');
    rect(c, 422, 242, 123, 103, '#d9ceb4'); rect(c, 440, 202, 85, 48, '#d5c5a5');
    poly(c, [[430, 204], [484, 151], [537, 204]], '#366e6e');
    for (let i = 0; i < 3; i++) arch(c, 440 + i * 35, 259, 14, 30, '#536f6b');
    arch(c, 472, 298, 27, 48, '#3a5551');
    castleTower(c, 413, 340, .78, t); castleTower(c, 550, 344, .92, t);
    flag(c, 484, 154, .8, '#d4a95c', t);
    const count = Math.min(state.count, 6);
    const additions = [[694, 431], [559, 488], [426, 468], [298, 462], [680, 315], [333, 301]];
    for (let i = 0; i < count; i++) {
      const [x, y] = additions[i], p = i === count - 1 && state.elapsed < 1.5 ? ease(state.elapsed / 1.5) : 1;
      c.save(); c.translate(x, y); c.scale(.74, .74 * Math.max(.02, p)); house(c, 0, 0, 1, ['#b96b52', '#547b7b', '#ad8853'][i % 3]); c.restore();
    }
    person(c, 433 + Math.sin(t * .14) * 35, 421, .47, '#b95746', t * 3); person(c, 571, 453, .45, '#325f69', t * 2);
    person(c, 489, 363, .45, '#e5c67b', t * 2); person(c, 336, 447, .45, '#e6b880', t);
    curve(c, [698, 492], [750, 448], [823, 478], [870, 489], '#d8c6a2', 18);
    for (let i = 0; i < 6; i++) line(c, [[709 + i * 29, 480 + Math.sin(i * .7) * 6], [707 + i * 29, 506 + Math.sin(i * .7) * 6]], '#846e54', 3);
    tree(c, 106, 515, 1.5, '#3e7059', t); tree(c, 889, 552, 1.4, '#4c7962', t);
    pine(c, 934, 525, 1.1, '#3b6357'); pine(c, 63, 575, 1.3, '#335a4f');
    for (let i = 0; i < 8; i++) { const x = 100 + i * 16; line(c, [[x, 571], [x + 30, 552]], '#d9c08188', 3); }
    if (count) { const a = additions[count - 1]; burst(c, state, a[0], a[1] - 25, '#ffecb0', 32, 110); }
  }

  function dynastyCourt(c, t, state) {
    sky(c, '#283e4b', '#70564e');
    rect(c, 50, 35, 900, 470, gradient(c, 0, 35, 0, 500, ['#43525a', '#253b42']));
    for (let i = 0; i < 4; i++) {
      const x = 105 + i * 213;
      arch(c, x, 67, 136, 285, '#688e8f', '#d7b97c', 5);
      arch(c, x + 12, 79, 112, 267, gradient(c, 0, 75, 0, 350, ['#b1b8b1', '#659294', '#385b64']));
      line(c, [[x + 68, 78], [x + 68, 346]], '#d1ba88', 5); line(c, [[x + 13, 197], [x + 123, 197]], '#d1ba88', 5);
      poly(c, [[x + 17, 220], [x + 66, 162], [x + 119, 220], [x + 68, 284]], '#7c596b70');
      glow(c, x + 68, 161, 79, '#f7e6bc20');
    }
    for (let i = 0; i < 5; i++) {
      const x = 71 + i * 212; rect(c, x, 57, 21, 359, '#6f7470'); rect(c, x - 9, 57, 39, 13, '#d1bd91', 2); rect(c, x - 11, 403, 43, 19, '#c2ac83', 2);
    }
    poly(c, [[50, 425], [950, 425], [1000, 620], [0, 620]], '#a18d78');
    for (let i = 0; i < 14; i++) line(c, [[90 + i * 64, 425], [-230 + i * 112, 620]], '#594f493e');
    for (let i = 0; i < 6; i++) line(c, [[0, 440 + i * i * 6], [1000, 440 + i * i * 6]], '#ebd3aa40');
    poly(c, [[565, 425], [751, 425], [838, 620], [462, 620]], '#8f3e48');
    line(c, [[577, 429], [477, 620]], '#d7b16a', 3); line(c, [[741, 429], [824, 620]], '#d7b16a', 3);
    rect(c, 529, 402, 280, 28, '#c3a376', 3); rect(c, 548, 383, 239, 20, '#d8b889', 3);
    rect(c, 569, 364, 200, 20, '#e4c69b', 3);
    arch(c, 617, 233, 96, 145, '#b6864b', '#f4d78f', 5); arch(c, 628, 247, 74, 112, '#793d51');
    rect(c, 606, 331, 117, 15, '#d9ae63', 5); line(c, [[614, 345], [611, 383]], '#c89751', 8); line(c, [[714, 345], [718, 383]], '#c89751', 8);
    person(c, 666, 368, 1.73, '#b95659', 0, '#cec2ad'); crown(c, 666, 285, .8);
    poly(c, [[649, 309], [666, 324], [680, 308], [687, 361], [645, 361]], '#d9b570');
    line(c, [[688, 319], [717, 307]], '#d8b879', 5); ellipse(c, 721, 300, 9, 9, '#dcb975');
    for (const x of [80, 884]) {
      rect(c, x, 111, 38, 163, '#a24650'); poly(c, [[x, 270], [x + 19, 297], [x + 38, 270]], '#a24650');
      crown(c, x + 19, 190, .55); line(c, [[x - 5, 111], [x + 43, 111]], '#e5c176', 5);
    }
    for (let i = 0; i < 7; i++) {
      const x = 230 + i * 41, y = 405 + (i % 2) * 40;
      person(c, x, y, 1.12, ['#7c655c', '#527d79', '#b37b57', '#60738a'][i % 4], Math.sin(t + i) * .15);
    }
    person(c, 777, 470, 1.2, '#b88e62', Math.sin(t * .5) * .2); person(c, 845, 466, 1.1, '#4e6c78', 0);
    rect(c, 355, 458, 217, 25, '#805744', 5); poly(c, [[355, 458], [389, 435], [602, 435], [572, 458]], '#bd9873');
    line(c, [[371, 480], [359, 548]], '#5f443b', 10); line(c, [[556, 480], [564, 539]], '#5f443b', 10);
    person(c, 443, 459, 1.1, '#d7a875', 0); rect(c, 476, 435, 65, 24, '#f2dfb5', 2);
    for (let i = 0; i < 4; i++) line(c, [[483, 440 + i * 4], [528 - i * 4, 440 + i * 4]], '#9c7f5d', 1);
    for (const x of [174, 880]) {
      line(c, [[x, 375], [x, 472]], '#c39354', 5); line(c, [[x - 19, 372], [x + 19, 372]], '#d0a561', 4);
      for (let k = -1; k <= 1; k++) { rect(c, x + k * 18 - 3, 350, 6, 22, '#eee0b6'); glow(c, x + k * 18, 346, 32, '#ffc66a38'); ellipse(c, x + k * 18, 344 + Math.sin(t * 4 + k) * 1.3, 3, 7, '#ffdc8d'); }
    }
    // Stage curtains frame a court rather than a dashboard.
    for (const side of [0, 1]) {
      c.save(); if (side) { c.translate(1000, 0); c.scale(-1, 1); }
      poly(c, [[0, 0], [171, 0], [150, 99], [91, 241], [73, 501], [0, 593]], '#813b47');
      for (let i = 0; i < 6; i++) curve(c, [i * 22, 0], [i * 22 + 18, 132], [37 + i * 5 + Math.sin(t * .7) * 2, 287], [20 + i * 8, 529 - i * 12], i % 2 ? '#a85459' : '#602e3f', 8);
      curve(c, [0, 259], [43, 273], [66, 253], [99, 231], '#ddb978', 6); c.restore();
    }
    for (let i = 0; i < Math.min(state.count, 7); i++) { ellipse(c, 262 + i * 36, 78, 11, 13, '#d5b56a'); crown(c, 262 + i * 36, 80, .32); }
    if (state.count && state.elapsed < 2.6) {
      const q = clamp(state.elapsed / 2.6), y = 410 - Math.sin(q * Math.PI) * 104;
      c.save(); c.translate(510, y); c.rotate(Math.sin(q * Math.PI) * -.08);
      rect(c, -47, -39, 94, 66, '#f7e7bd', 4, '#c3a270');
      for (let i = 0; i < 4; i++) line(c, [[-31, -23 + i * 9], [23 - i * 3, -23 + i * 9]], '#b59875', 2);
      ellipse(c, 17, 24, 12, 12, '#ae4853'); poly(c, [[10, 29], [6, 52], [19, 42], [27, 48], [23, 29]], '#b94f55'); c.restore();
      burst(c, state, 510, 340, '#f5d79c', 22, 100);
    }
    motes(c, t, '#efd9ac', 150, 100, 700, 380, 18);
  }

  function shelf(c, x, y, w, rows, seed = 1) {
    rect(c, x - 8, y - 8, w + 16, rows * 53 + 17, '#463b36', 4, '#c19a63');
    rect(c, x, y, w, rows * 53, '#2d3432');
    const palette = ['#b97460', '#557c78', '#d3ad71', '#677d8c', '#976c78', '#b7b18b', '#794d47'];
    for (let row = 0; row < rows; row++) {
      let xx = x + 4, i = 0;
      while (xx < x + w - 10) {
        const n = seed * 100 + row * 32 + i, bw = 6 + hash(n) * 12, bh = 25 + hash(n + 2) * 19;
        const yy = y + row * 53 + 47 - bh;
        rect(c, xx, yy, bw, bh, palette[Math.floor(hash(n + 30) * palette.length)], 1);
        line(c, [[xx + 2, yy + 5], [xx + bw - 2, yy + 5]], '#f5ddb77a');
        line(c, [[xx + 2, yy + bh - 5], [xx + bw - 2, yy + bh - 5]], '#e8c48c80');
        if (bw > 12) line(c, [[xx + bw / 2, yy + 10], [xx + bw / 2, yy + bh - 9]], '#e5c28290', 1.2);
        xx += bw + 2; i++;
      }
      rect(c, x - 4, y + row * 53 + 47, w + 8, 7, '#a47c52', 1); rect(c, x, y + row * 53 + 54, w, 2, '#1a2a2a66');
    }
  }
  function openBook(c, x, y, w, h, tilt = 0, glowColor) {
    c.save(); c.translate(x, y); c.rotate(tilt);
    if (glowColor) glow(c, 0, -h * .3, w * .9, glowColor);
    poly(c, [[-w / 2 - 8, -h + 8], [0, -h + 23], [w / 2 + 8, -h + 8], [w / 2 + 15, 13], [0, 30], [-w / 2 - 15, 13]], '#80594b', '#c3a16a', 2);
    for (let i = 3; i >= 0; i--) {
      const d = i * 2;
      c.beginPath(); c.moveTo(0, 15 + d); c.bezierCurveTo(-w * .15, -2 + d, -w * .36, -5 + d, -w / 2, 4 + d);
      c.lineTo(-w / 2, -h + d); c.bezierCurveTo(-w * .29, -h - 11 + d, -w * .16, -h + 1 + d, 0, -h + 16 + d);
      c.bezierCurveTo(w * .15, -h + 1 + d, w * .3, -h - 10 + d, w / 2, -h + d); c.lineTo(w / 2, 4 + d);
      c.bezierCurveTo(w * .3, -5 + d, w * .15, -1 + d, 0, 15 + d); c.closePath(); c.fillStyle = i ? '#cdbf98' : '#f3e4ba'; c.fill();
      c.strokeStyle = '#ac9c7355'; c.lineWidth = .8; c.stroke();
    }
    curve(c, [0, -h + 17], [-3, -h * .5], [3, -8], [0, 15], '#ab946c', 2);
    for (let i = 0; i < 6; i++) {
      const yy = -h + 25 + i * (h - 35) / 6;
      curve(c, [-w / 2 + 12, yy], [-w * .32, yy - 4], [-w * .2, yy + 1], [-12, yy + 9], '#b6a37d70', 1.2);
      curve(c, [12, yy + 9], [w * .2, yy + 1], [w * .32, yy - 4], [w / 2 - 12, yy], '#b6a37d70', 1.2);
    }
    c.restore();
  }
  function lamp(c, x, y, t) {
    line(c, [[x, 0], [x, y - 10]], '#57544c', 2);
    glow(c, x, y + 18, 92, '#ffd89030');
    poly(c, [[x - 23, y + 12], [x - 10, y - 14], [x + 10, y - 14], [x + 23, y + 12]], '#c49859', '#6f5941');
    ellipse(c, x, y + 12, 23, 5, '#ffe5a3'); ellipse(c, x, y + 17, 4, 5, '#fff2c9');
  }
  function libraryHouse(c, t, state) {
    sky(c, '#263f49', '#867364');
    stars(c, t, '#ded2aa', 36, 310);
    ellipse(c, 861, 91, 27, 27, '#e2dbbc');
    pine(c, 39, 546, 2.25, '#294841'); pine(c, 962, 528, 1.8, '#304a43');
    ellipse(c, 512, 568, 421, 39, '#182c3355');
    poly(c, [[92, 133], [499, 23], [909, 133]], '#523f3e', '#b98b62', 3);
    poly(c, [[112, 136], [499, 41], [886, 136]], '#a67652');
    rect(c, 110, 138, 780, 411, '#bc956e', 4);
    rect(c, 128, 157, 744, 378, '#ddc49a');
    for (let i = 0; i < 8; i++) line(c, [[132, 167 + i * 48], [868, 167 + i * 48]], '#ad886b36');
    line(c, [[499, 43], [499, 132]], '#614b3d', 13); line(c, [[244, 119], [353, 81]], '#614b3d', 13); line(c, [[644, 82], [756, 120]], '#614b3d', 13);
    ellipse(c, 499, 97, 28, 28, '#597f82', 0, '#e7c98e'); line(c, [[471, 97], [527, 97]], '#e7c98e', 3); line(c, [[499, 69], [499, 125]], '#e7c98e', 3);
    // Shelves are actual little book spines, not rectangular menu cards.
    shelf(c, 156, 179, 173, 2, 3); shelf(c, 369, 179, 137, 2, 8); shelf(c, 671, 179, 173, 2, 12);
    shelf(c, 151, 354, 182, 3, 22); shelf(c, 661, 352, 181, 3, 29);
    arch(c, 544, 171, 90, 118, '#587e7b', '#8c6b4c', 6); line(c, [[589, 176], [589, 287]], '#d9bb83', 4); line(c, [[551, 226], [628, 226]], '#d9bb83', 4);
    glow(c, 588, 238, 58, '#91bab433');
    rect(c, 127, 296, 746, 15, '#704e3d'); rect(c, 127, 311, 746, 9, '#c29b68');
    for (let i = 0; i < 28; i++) rect(c, 141 + i * 26, 272, 4, 27, '#7b5a42', 1);
    line(c, [[134, 272], [866, 272]], '#a88052', 6);
    // A diagonal wooden staircase joins the two reading floors.
    poly(c, [[382, 510], [541, 320], [573, 320], [418, 510]], '#8c6247');
    for (let i = 0; i < 11; i++) { const x = 385 + i * 14.7, y = 505 - i * 17.8; line(c, [[x, y], [x + 36, y]], '#dfbf8a', 5); }
    line(c, [[377, 478], [532, 295]], '#6c503e', 5);
    for (let i = 0; i < 9; i++) line(c, [[386 + i * 17, 466 - i * 20], [386 + i * 17, 495 - i * 20]], '#8a6948', 3);
    rect(c, 128, 529, 744, 14, '#775541');
    for (const x of [131, 348, 640, 866]) { rect(c, x, 158, 12, 370, '#8b674b'); rect(c, x - 5, 319, 23, 10, '#c6a574', 1); }
    // Reading desk, soft rug and the librarian's cat.
    ellipse(c, 544, 528, 113, 26, '#9a5960'); ellipse(c, 544, 528, 98, 19, null, 0, '#d2a78a');
    line(c, [[486, 491], [480, 526]], '#694c3d', 8); line(c, [[606, 491], [612, 526]], '#694c3d', 8);
    ellipse(c, 548, 485, 80, 22, '#a67851', 0, '#d6b07a');
    openBook(c, 552, 483, 70, 35, -.05);
    person(c, 604, 491, 1.15, '#557c77', 0, '#a5a193'); person(c, 710, 296, .78, '#a65c57', t * .25);
    line(c, [[278, 498], [310, 360]], '#b88d57', 5); line(c, [[302, 498], [334, 360]], '#b88d57', 5);
    for (let i = 0; i < 6; i++) line(c, [[281 + i * 5, 485 - i * 23], [305 + i * 5, 485 - i * 23]], '#e0ba7e', 3);
    person(c, 305, 424, .83, '#b57655', Math.sin(t) * .15);
    ellipse(c, 222, 525, 22, 10, '#b66e4b'); ellipse(c, 239, 519, 10, 10, '#c58359');
    poly(c, [[232, 512], [231, 504], [238, 510], [246, 504], [247, 514]], '#b66e4b');
    curve(c, [207, 526], [180, 531], [182, 510], [200, 515], '#b66e4b', 6);
    lamp(c, 246, 171, t); lamp(c, 575, 350, t); lamp(c, 748, 171, t);
    for (let i = 0; i < Math.min(state.count, 8); i++) { rect(c, 462 + i * 11, 421 - i % 3 * 4, 9, 28 + i % 3 * 4, ['#648b7d', '#b87961', '#c3a36b'][i % 3], 1); }
    const q = state.elapsed;
    if (state.count && q < 3) {
      const lift = Math.sin(clamp(q / 3) * Math.PI);
      openBook(c, 535, 457 - lift * 125, 120 + lift * 32, 55 + lift * 17, Math.sin(q * 2) * .05, '#f4ce713a');
      burst(c, state, 535, 342, '#ffeba9', 35, 136);
    }
    motes(c, t, '#ffedbb', 155, 160, 685, 360, 35);
    // Amber foreground plants frame the cutaway without covering the architecture.
    tree(c, 88, 584, .95, '#5b7455', t); tree(c, 909, 570, .85, '#71845d', t);
  }

  function libraryObservatory(c, t, state) {
    sky(c, '#142638', '#324659'); stars(c, t, '#dce9d4', 110, 530);
    glow(c, 527, 267, 300, '#688d9b2a'); glow(c, 241, 121, 160, '#576c9e20');
    ellipse(c, 811, 112, 48, 48, '#a4c6ba'); ellipse(c, 827, 99, 43, 43, '#1b3043');
    for (let i = 0; i < 4; i++) {
      c.save(); c.translate(515, 323); c.rotate(-.18 + i * .16); ellipse(c, 0, 0, 231 + i * 48, 84 + i * 12, null, 0, '#a6b6a42c'); c.restore();
    }
    // Broken observatory dome and an engraved brass armillary.
    c.beginPath(); c.arc(512, 473, 347, Math.PI * 1.04, Math.PI * 1.96); c.strokeStyle = '#8d9d9f4c'; c.lineWidth = 12; c.stroke();
    c.beginPath(); c.arc(512, 473, 335, Math.PI * 1.04, Math.PI * 1.96); c.strokeStyle = '#d1c49b78'; c.lineWidth = 1; c.stroke();
    for (let i = 0; i < 15; i++) { const a = Math.PI * 1.06 + i * .195; line(c, [[512 + Math.cos(a) * 334, 473 + Math.sin(a) * 334], [512 + Math.cos(a) * 350, 473 + Math.sin(a) * 350]], '#b8b08c90', 2); }
    ellipse(c, 502, 526, 276, 43, '#10232d'); ellipse(c, 502, 503, 278, 47, '#365056', 0, '#a4a482');
    ellipse(c, 502, 502, 251, 35, null, 0, '#9eae8d88');
    rect(c, 464, 401, 91, 100, '#415c5c'); ellipse(c, 509, 402, 79, 19, '#7a8b72', 0, '#d3c08b');
    poly(c, [[464, 499], [484, 454], [510, 471], [538, 455], [555, 499]], '#607e75');
    const bob = Math.sin(t * .65) * 5;
    openBook(c, 506, 381 + bob, 344, 151, -.03, '#c5e9b729');
    // A tiny landscape rises directly from the open atlas.
    poly(c, [[369, 303 + bob], [409, 241 + bob], [442, 286 + bob], [473, 250 + bob], [506, 306 + bob]], '#6b9584');
    poly(c, [[407, 243 + bob], [423, 266 + bob], [408, 259 + bob], [396, 262 + bob]], '#e0e7bd');
    poly(c, [[506, 309 + bob], [544, 260 + bob], [568, 290 + bob], [601, 235 + bob], [645, 305 + bob]], '#819d8f');
    curve(c, [512, 291 + bob], [555, 304 + bob], [463, 324 + bob], [535, 348 + bob], '#9ddad1', 13);
    for (let i = 0; i < 7; i++) pine(c, 390 + i * 35, 316 + bob + Math.sin(i) * 7, .22 + hash(i) * .13, '#2e6260');
    glow(c, 504, 227, 68, '#d8f1c134'); ellipse(c, 504, 227, 22, 22, '#e0e4ab');
    // Orbiting volumes: covers, paper blocks and gold spines have depth and individual tilt.
    const colors = ['#c7a46c', '#688f93', '#9f7587', '#8fa56f', '#b67966'];
    for (let i = 0; i < 7; i++) {
      const a = i * .9 + t * .035, x = 507 + Math.cos(a) * 332, y = 273 + Math.sin(a) * 128;
      c.save(); c.translate(x, y); c.rotate(Math.sin(a) * .27);
      rect(c, -20, -28, 42, 58, '#1c2d37', 3); rect(c, -18, -31, 39, 54, colors[i % colors.length], 3, '#dfd1a0');
      rect(c, -14, 22, 31, 6, '#cfcda9', 1); line(c, [[-12, -26], [-12, 18]], '#ead5a2', 2);
      ellipse(c, 4, -4, 9, 13, null, 0, '#eddab488'); spark(c, 4, -4, 5, '#eddab4'); c.restore();
    }
    // Brass telescope and a patient astronomer on the balcony.
    line(c, [[278, 467], [250, 533]], '#ac946a', 5); line(c, [[278, 467], [306, 532]], '#ac946a', 5);
    c.save(); c.translate(278, 450); c.rotate(-.45); rect(c, -39, -12, 95, 24, '#b69b69', 5); rect(c, 37, -17, 17, 34, '#d9c088', 3); ellipse(c, 54, 0, 5, 14, '#5c9aa0'); rect(c, -55, -5, 19, 10, '#dfc892', 2); c.restore();
    person(c, 319, 517, 1.13, '#b6a27a', 0); person(c, 713, 511, .95, '#7b9a91', Math.sin(t) * .1);
    for (let i = 0; i < Math.min(state.count, 7); i++) {
      const a = -2.55 + i * .39, x = 515 + Math.cos(a) * 230, y = 277 + Math.sin(a) * 133;
      glow(c, x, y, 23, '#c5e8c22e'); ellipse(c, x, y, 10 + i % 3 * 2, 10 + i % 3 * 2, ['#a4c9a2', '#d7b37d', '#b398b5'][i % 3]);
      ellipse(c, x, y, 21, 6, null, -.3, '#c5ddbd70');
    }
    if (state.count && state.elapsed < 3) {
      const q = clamp(state.elapsed / 3); curve(c, [503, 327], [459, 232 - q * 40], [573, 196 - q * 60], [526, 142 - q * 30], '#bce9ce88', 2.8);
      for (let i = 0; i < 15; i++) { const z = clamp(q + i * .035); const x = 505 + Math.sin(z * 9) * 33; spark(c, x, 329 - z * 191, 2 + i % 3, '#e4ebc1'); }
      burst(c, state, 510, 186, '#bdebd4', 28, 135);
    }
    motes(c, t, '#c0e8df', 182, 250, 620, 283, 30);
  }

  function leaf(c, x, y, w, h, angle, color, vein = '#bad29a66') {
    c.save(); c.translate(x, y); c.rotate(angle);
    c.beginPath(); c.moveTo(0, 0); c.bezierCurveTo(-w, -h * .34, -w * .6, -h * .88, 0, -h); c.bezierCurveTo(w * .65, -h * .76, w * .8, -h * .22, 0, 0); c.closePath(); c.fillStyle = color; c.fill();
    curve(c, [0, 0], [2, -h * .3], [-2, -h * .6], [0, -h * .9], vein, 1.1);
    for (let i = 1; i < 4; i++) line(c, [[0, -h * i / 5], [(i % 2 ? 1 : -1) * w * .45, -h * (i + 1) / 5]], vein, .8);
    c.restore();
  }
  function bloom(c, x, y, s, color, t, kind = 0, open = 1) {
    c.save(); c.translate(x, y); c.scale(s, s);
    const sway = Math.sin(t * .9 + x * .05) * 3;
    curve(c, [0, 0], [-3, -36], [sway + 5, -63], [sway, -92], '#477c50', 3.5);
    leaf(c, -1, -22, 12, 34, -.9, '#5e934e'); leaf(c, 1, -46, 10, 31, .8, '#78a659');
    c.translate(sway, -92); c.scale(Math.max(.08, open), Math.max(.08, open));
    const petals = kind === 1 ? 5 : kind === 2 ? 10 : 8;
    for (let j = 0; j < petals; j++) {
      c.save(); c.rotate(j / petals * TAU);
      if (kind === 1) {
        poly(c, [[0, 5], [-12, -10], [-2, -33], [10, -14]], color, '#ffffff42', .7);
        line(c, [[0, 0], [0, -27]], '#ffffff69', 1);
      } else {
        ellipse(c, 0, -15, kind === 2 ? 5 : 9, kind === 2 ? 19 : 17, color, -.08);
        ellipse(c, -2, -18, 2.5, 10, '#ffffff2c');
      } c.restore();
    }
    ellipse(c, 0, 0, 8, 8, '#c5903e'); ellipse(c, -1, -2, 5, 5, '#f0d273');
    for (let j = 0; j < 9; j++) ellipse(c, Math.cos(j * 2.4) * 5, Math.sin(j * 2.4) * 5, .8, .8, '#8c633b');
    c.restore();
  }
  function butterfly(c, x, y, s, t, color = '#d89d62') {
    c.save(); c.translate(x, y); c.rotate(Math.sin(t) * .16); const flap = .3 + Math.abs(Math.sin(t * 4)) * .7;
    ellipse(c, -7 * flap * s, -3 * s, 9 * flap * s, 10 * s, color, -.5); ellipse(c, 7 * flap * s, -3 * s, 9 * flap * s, 10 * s, color, .5);
    ellipse(c, -5 * flap * s, 6 * s, 6 * flap * s, 6 * s, '#f6d99a', -.3); ellipse(c, 5 * flap * s, 6 * s, 6 * flap * s, 6 * s, '#f6d99a', .3);
    line(c, [[0, -7 * s], [0, 10 * s]], '#514b3b', 2 * s); c.restore();
  }
  function bed(c, x, y, w, d) {
    poly(c, [[x, y], [x + w, y], [x + w + d, y - d * .55], [x + d, y - d * .55]], '#755841');
    poly(c, [[x, y], [x + w, y], [x + w, y + 17], [x, y + 17]], '#b58b60');
    poly(c, [[x + w, y], [x + w + d, y - d * .55], [x + w + d, y + 17 - d * .55], [x + w, y + 17]], '#856347');
    line(c, [[x, y], [x + w, y], [x + w + d, y - d * .55]], '#d8b488', 4);
    for (let i = 0; i < 25; i++) ellipse(c, x + hash(i + x) * w + 12, y - hash(i + y) * d * .4 - 1, 1.5, .7, '#c7a0746a');
  }
  function gardenPlot(c, t, state) {
    sky(c, '#b9d6cc', '#f3dfb4');
    glow(c, 194, 85, 120, '#fff4ce90'); ellipse(c, 194, 85, 39, 39, '#fff2c7');
    cloud(c, 386 + Math.sin(t * .05) * 14, 85, 1.15, '#fff6dc90'); cloud(c, 847, 127, .9, '#fff6dc90');
    poly(c, [[0, 245], [116, 206], [271, 251], [445, 208], [600, 236], [795, 191], [1000, 234], [1000, 620], [0, 620]], '#9fb58b');
    poly(c, [[0, 305], [231, 253], [486, 298], [726, 241], [1000, 291], [1000, 620], [0, 620]], '#88a674');
    // A little gardener's cottage gives the beds a place in the world.
    rect(c, 709, 210, 165, 111, '#ead8ae'); poly(c, [[693, 214], [787, 142], [891, 214]], '#a86558');
    line(c, [[703, 210], [788, 153], [881, 210]], '#d29173', 5);
    rect(c, 825, 141, 20, 49, '#c3ab83'); rect(c, 821, 137, 28, 8, '#e4cdab');
    arch(c, 769, 252, 33, 69, '#547a70'); rect(c, 726, 239, 27, 32, '#789c98', 2, '#b59870'); rect(c, 819, 239, 29, 32, '#789c98', 2, '#b59870');
    line(c, [[739, 240], [739, 271]], '#f1dfb9', 3); line(c, [[726, 255], [753, 255]], '#f1dfb9', 3);
    // Fence, an arbor and layered shrubs.
    for (let i = 0; i < 22; i++) {
      const x = 63 + i * 42, y = 305 + Math.sin(i * .25) * 9;
      poly(c, [[x, y + 29], [x, y - 25], [x + 7, y - 34], [x + 14, y - 25], [x + 14, y + 29]], '#c0b58b', '#a39776');
    }
    line(c, [[55, 306], [958, 306]], '#d0c49a', 5); line(c, [[55, 326], [958, 326]], '#b0a47c', 5);
    for (let i = 0; i < 13; i++) tree(c, 42 + i * 78, 335 + Math.sin(i) * 11, .5 + hash(i) * .32, ['#5f8b5b', '#719962', '#819f64'][i % 3], t);
    tree(c, 106, 332, 2.08, '#527d55', t); tree(c, 935, 342, 1.4, '#789d67', t);
    rect(c, 116, 233, 31, 36, '#bc8657', 3); poly(c, [[111, 234], [132, 215], [153, 234]], '#6d7770'); ellipse(c, 132, 248, 6, 7, '#4b5040'); line(c, [[130, 272], [130, 294]], '#b58b63', 2);
    // A winding pale stone path separates, rather than merely labels, the growing plots.
    curve(c, [776, 332], [714, 388], [535, 423], [508, 620], '#d5c89b', 58);
    for (let i = 0; i < 12; i++) { const q = i / 12, x = 750 - q * 219 + Math.sin(q * 5) * 19, y = 345 + q * 259; ellipse(c, x, y, 15, 8, '#e2d6b16b', -.2); }
    bed(c, 249, 419, 205, 51); bed(c, 600, 441, 196, 61); bed(c, 202, 538, 239, 61);
    for (let i = 0; i < 8; i++) bloom(c, 268 + i * 24, 406 - (i % 2) * 14, .55 + hash(i) * .16, ['#efba83', '#e8c8a5', '#b97689'][i % 3], t, i % 3);
    for (let i = 0; i < 8; i++) bloom(c, 620 + i * 24, 426 - (i % 2) * 13, .64 + hash(i + 11) * .13, ['#938eb1', '#c3abc0', '#d8c6d3'][i % 3], t + 1, 2);
    for (let i = 0; i < 10; i++) { const x = 225 + i * 23; leaf(c, x, 525 - i % 2 * 13, 14, 49 + i % 3 * 9, -.35 + hash(i) * .7, '#528452'); leaf(c, x + 5, 525, 13, 40, .45, '#77a45b'); }
    const colors = ['#e398a8', '#bf9bc9', '#e6c16e', '#92c9b9', '#cc89aa'];
    const n = Math.min(state.count, 6);
    for (let i = 0; i < n; i++) {
      const open = i === n - 1 && state.elapsed < 2.2 ? .15 + ease(state.elapsed / 2.2) * .85 : 1;
      const x = 670 + (i % 3) * 47, y = 525 + Math.floor(i / 3) * 35;
      bloom(c, x, y, .92, colors[i % colors.length], t, 1, open);
    }
    // The rare parent flower already anchors the composition before the first action.
    bloom(c, 603, 529, 1.28, '#e4b26e', t, 1);
    ellipse(c, 129, 488, 90, 42, '#719ca0'); ellipse(c, 129, 488, 81, 34, '#91babb');
    for (let i = 0; i < 7; i++) ellipse(c, 75 + hash(i) * 105, 465 + hash(i + 10) * 43, 13, 5, '#658f65', -.2);
    ellipse(c, 113, 480, 10, 5, '#f1e6c4'); ellipse(c, 121, 473, 5, 6, '#f1e6c4'); poly(c, [[125, 471], [133, 474], [126, 476]], '#c9974e');
    for (let i = 0; i < 7; i++) ellipse(c, 55 + i * 25, 524 + Math.sin(i) * 8, 13, 8, '#afa78b', -.2);
    person(c, 506, 470, 1.18, '#718ea0', Math.sin(t * .5) * .15); ellipse(c, 506, 411, 18, 6, '#d5b276'); ellipse(c, 506, 407, 11, 9, '#d5b276');
    rect(c, 523, 447, 21, 23, '#779492', 5); curve(c, [543, 454], [558, 450], [554, 440], [565, 437], '#789795', 5);
    for (let i = 0; i < 4; i++) butterfly(c, 332 + i * 129 + Math.sin(t * .5 + i) * 20, 275 + i % 2 * 65 + Math.cos(t * .7 + i) * 15, .58, t + i, ['#e4b75f', '#bd9dbb', '#d98d6f'][i % 3]);
    for (let i = 0; i < 9; i++) { const x = 43 + i * 113; bloom(c, x, 623 + i % 2 * 8, .6 + hash(i) * .45, ['#f1cf89', '#cf8c91', '#dfe4c4'][i % 3], t, i % 2); }
    if (state.count && state.elapsed < 2.4) {
      const target = 670 + ((n - 1) % 3) * 47;
      for (let i = 0; i < 22; i++) {
        const q = (state.elapsed * .9 + i * .08) % 1;
        ellipse(c, 563 + q * (target - 563) + Math.sin(i * 4) * 5, 439 + q * q * 75, 1.6, 3, '#d8f1e39c', -.3);
      }
      burst(c, state, target, 430 + Math.floor((n - 1) / 3) * 35, '#f8dfaa', 28, 88);
    }
  }

  function gardenGlass(c, t, state) {
    sky(c, '#173645', '#57736f'); stars(c, t, '#bfd8c8', 60, 290);
    ellipse(c, 817, 96, 34, 34, '#c2d8be'); glow(c, 817, 96, 104, '#b3d1bf25');
    for (let i = 0; i < 9; i++) pine(c, 20 + i * 126, 431 + Math.sin(i) * 28, 1.9 + hash(i) * 1.2, '#244c4f');
    poly(c, [[0, 477], [221, 427], [438, 458], [697, 427], [1000, 461], [1000, 620], [0, 620]], '#9bb0a0');
    ellipse(c, 505, 532, 404, 55, '#183c4040');
    c.beginPath(); c.moveTo(142, 503); c.lineTo(142, 255); c.bezierCurveTo(142, 87, 353, 58, 500, 57); c.bezierCurveTo(647, 58, 858, 87, 858, 255); c.lineTo(858, 503); c.closePath();
    c.fillStyle = gradient(c, 0, 64, 0, 520, ['#70998e80', '#45776f', '#284f48']); c.fill();
    glow(c, 495, 354, 266, '#b9df8735'); glow(c, 689, 346, 177, '#dbbd7133');
    // Dark foliage behind the glazing makes the glowing flowers stand out.
    for (let i = 0; i < 12; i++) {
      const x = 184 + i * 57; leaf(c, x, 469, 36, 163 + hash(i) * 73, -.55 + hash(i + 19), '#2c6553');
      leaf(c, x, 469, 24, 116 + hash(i + 3) * 49, .45, '#4d805b');
    }
    // Stone plinth and a central sunken walkway.
    rect(c, 135, 497, 730, 37, '#607a69', 8); rect(c, 145, 497, 710, 11, '#a5b48b');
    poly(c, [[459, 398], [543, 398], [607, 534], [398, 534]], '#a9a87c');
    for (let i = 0; i < 6; i++) line(c, [[452 - i * 8, 418 + i * 21], [551 + i * 8, 418 + i * 21]], '#6d7d61', 2);
    const pots = [[239, 457, 1], [324, 478, 1.2], [700, 452, 1.1], [789, 484, 1], [624, 488, .8]];
    pots.forEach(([x, y, s], i) => {
      poly(c, [[x - 26 * s, y - 30 * s], [x + 26 * s, y - 30 * s], [x + 19 * s, y], [x - 19 * s, y]], '#99705c');
      ellipse(c, x, y - 30 * s, 27 * s, 7 * s, '#ba9270'); ellipse(c, x, y - 32 * s, 21 * s, 4 * s, '#4f5d46');
      bloom(c, x, y - 31 * s, .95 * s, ['#a6d7ba', '#c5b1d6', '#d9d0a2', '#91c4cc', '#d79cab'][i], t + i, i % 3);
      glow(c, x, y - 124 * s, 54 * s, ['#9fe3bf27', '#c0a2db29', '#e8d19024'][i % 3]);
    });
    // Hanging baskets, curling tendrils and warm suspended lanterns.
    for (const [x, y] of [[303, 183], [697, 181]]) {
      line(c, [[x, 98], [x, y]], '#c1b489', 2); ellipse(c, x, y, 29, 10, '#a38a64');
      for (let i = 0; i < 6; i++) {
        const xx = x - 25 + i * 10;
        curve(c, [xx, y], [xx - 12, y + 35], [xx + 12, y + 48], [xx + Math.sin(t + i) * 3, y + 73 - i % 2 * 22], '#78a56b', 2);
        for (let j = 0; j < 3; j++) leaf(c, xx + (j % 2 ? 4 : -4), y + 18 + j * 16, 5, 13, j % 2 ? .8 : -.8, '#95b87a');
      }
    }
    person(c, 516, 490, 1.04, '#c2ae7a', 0); ellipse(c, 515, 436, 17, 5, '#d8c596');
    // The experiment is a luminous orchid, physically between its parent plants.
    const n = Math.min(state.count, 8), p = state.count && state.elapsed < 2.4 ? .3 + ease(state.elapsed / 2.4) * .7 : 1;
    const orchidColor = ['#b0ded0', '#cab7eb', '#f0d099', '#d8aac9'][state.count % 4];
    rect(c, 450, 414, 92, 13, '#846e53', 4); line(c, [[458, 427], [453, 467]], '#75614c', 6); line(c, [[533, 427], [539, 468]], '#75614c', 6);
    ellipse(c, 495, 410, 23, 8, '#9baf8a'); bloom(c, 495, 407, 1.52, orchidColor, t, 1, p);
    glow(c, 495, 264, 75, '#cbe6c12c');
    for (let i = 0; i < n; i++) { const x = 220 + i * 72; rect(c, x - 8, 490, 16, 13, '#b59069', 3); bloom(c, x, 488, .35, orchidColor, t + i, i % 3); }
    // Glass ribs are drawn over the plants: the room has real depth.
    for (let i = 0; i < 7; i++) {
      const x = 143 + i * 119;
      curve(c, [x, 504], [x, 236], [500 + (x - 500) * .72, 75], [500, 57], '#b1bc9296', 3);
    }
    c.beginPath(); c.moveTo(142, 503); c.lineTo(142, 255); c.bezierCurveTo(142, 87, 353, 58, 500, 57); c.bezierCurveTo(647, 58, 858, 87, 858, 255); c.lineTo(858, 503); c.strokeStyle = '#a8b58a'; c.lineWidth = 8; c.stroke();
    for (const y of [205, 310, 414]) line(c, [[148, y], [853, y]], '#9cac8170', 2);
    arch(c, 438, 325, 124, 207, null, '#b8b78b', 5); line(c, [[500, 337], [500, 531]], '#b8b78b', 3);
    ellipse(c, 490, 459, 3, 4, '#e1dba9');
    for (let i = 0; i < 13; i++) { const x = 180 + hash(i) * 638; const y = 120 + (hash(i + 7) * 310 + t * 3) % 310; line(c, [[x, y], [x - 1, y + 17]], '#d7e8d229', 1); }
    for (const x of [175, 825]) { line(c, [[x, 248], [x, 274]], '#bca97d', 2); rect(c, x - 10, 274, 20, 32, '#bca16d', 3); rect(c, x - 7, 278, 14, 21, '#f5d78b', 2); glow(c, x, 290, 74, '#f8d68a2a'); }
    if (state.count && state.elapsed < 2.6) {
      const q = clamp(state.elapsed / 2.6);
      for (let i = 0; i < 28; i++) {
        const z = clamp(q * 1.35 - i * .018), side = i % 2 ? 1 : -1;
        const x = 495 + side * 176 * (1 - z), y = 348 - Math.sin(z * Math.PI) * 125 - z * 75;
        spark(c, x, y, 2.2, ['#f1dc98', '#c3ecd5'][i % 2]);
      }
      burst(c, state, 495, 265, '#e2f3cb', 26, 105);
    }
    motes(c, t, '#b8e4ae', 180, 240, 630, 270, 28);
    for (let i = 0; i < 14; i++) leaf(c, 8 + i * 78, 636, 24, 74 + hash(i) * 73, -.5 + hash(i + 10), '#345f53');
  }

  function gem(c, x, y, s, color = '#8ab5ac') {
    poly(c, [[x - 8 * s, y], [x - 4 * s, y - 9 * s], [x + 5 * s, y - 9 * s], [x + 10 * s, y], [x, y + 12 * s]], color, '#f2e4c28a', .8);
    poly(c, [[x - 4 * s, y - 9 * s], [x + 2 * s, y], [x, y + 12 * s], [x - 8 * s, y]], '#ffffff35');
    line(c, [[x - 8 * s, y], [x + 10 * s, y]], '#e3e8d9a0', .9);
  }
  function coins(c, x, y, rx, ry, n, seed = 4) {
    ellipse(c, x, y, rx, ry, '#9a773d');
    const list = Array.from({ length: n }, (_, i) => {
      const a = hash(i * 4 + seed) * TAU, r = Math.sqrt(hash(i * 4 + seed + 1));
      return [x + Math.cos(a) * r * rx, y + Math.sin(a) * r * ry, 5 + hash(i + 83) * 5];
    }).sort((a, b) => a[1] - b[1]);
    list.forEach(([px, py, s], i) => {
      ellipse(c, px, py + 3, s, s * .39, '#9e713d');
      ellipse(c, px, py, s, s * .38, ['#d1a451', '#e3bf70', '#edcd88', '#b68b45'][i % 4], .07, '#8d6a3e');
      ellipse(c, px, py, s * .61, s * .23, null, 0, '#f9dfa78a');
    });
  }
  function dragon(c, x, y, s, t, awake, paper = false) {
    c.save(); c.translate(x, y); c.scale(s, s);
    const breath = Math.sin(t * .8) * 1.4;
    if (paper) {
      // Faceted folded-paper silhouette: deliberately different from the rounded cave creature.
      const wing = Math.sin(t * .8) * 3 + (awake ? -9 : 0);
      poly(c, [[-23, -42], [-152, -168 + wing], [-103, -145], [-88, -218 + wing], [-43, -166], [5, -197 + wing], [36, -59]], '#a6545c', '#e4a18a', 1.2);
      poly(c, [[-23, -42], [-88, -218 + wing], [-43, -166], [36, -59]], '#c6736c');
      line(c, [[-88, -218 + wing], [-23, -42], [-152, -168 + wing]], '#f1b89988', 1.3);
      poly(c, [[-108, -13], [-166, -43], [-187, -109], [-167, -81], [-130, -51], [-50, -42]], '#954956');
      poly(c, [[-102, -28], [-51, -75], [43, -67], [91, -38], [60, -2], [-39, 8]], '#c47770', '#773d4d');
      poly(c, [[-51, -75], [-39, 8], [60, -2], [43, -67]], '#de9981');
      poly(c, [[49, -56], [80, -105], [104, -145], [150, -155], [190, -127], [151, -112], [113, -83], [89, -29]], '#bf6a67', '#733f4b');
      poly(c, [[104, -145], [151, -112], [190, -127], [150, -155]], '#e4a58b');
      poly(c, [[124, -145], [125, -178], [145, -151]], '#e9d5a9');
      poly(c, [[-24, -7], [-40, 29], [-5, 29], [12, -4]], '#9e505b');
      poly(c, [[55, -15], [55, 28], [88, 28], [76, -20]], '#b46162');
      ellipse(c, 151, -137, 5, 3, '#f4dfa8'); ellipse(c, 153, -137, 1.5, 3, '#463d45');
      line(c, [[156, -119], [181, -126]], '#673847', 2);
      c.restore(); return;
    }
    const wingRise = Math.sin(t * .55) * 2 + (awake ? -13 : 0);
    c.beginPath(); c.moveTo(-4, -48); c.bezierCurveTo(-59, -117, -109, -125, -142, -185 + wingRise);
    c.lineTo(-91, -158 + wingRise); c.lineTo(-79, -213 + wingRise); c.lineTo(-30, -166 + wingRise); c.lineTo(12, -191 + wingRise);
    c.bezierCurveTo(16, -137, 40, -111, 31, -57); c.closePath(); c.fillStyle = gradient(c, -100, -210, 20, -50, ['#526a71', '#2b535c']); c.fill();
    line(c, [[-79, -213 + wingRise], [-42, -133], [-4, -48]], '#86a397', 3);
    line(c, [[-142, -185 + wingRise], [-42, -133], [12, -191 + wingRise]], '#6c8b84', 2);
    curve(c, [-76, -16], [-182, 22], [-224, -46], [-176, -98], '#315a5c', 30);
    curve(c, [-76, -13], [-177, 25], [-219, -43], [-176, -98], '#629185', 17);
    poly(c, [[-175, -97], [-176, -123], [-194, -105]], '#719b88');
    ellipse(c, -8, -42 + breath, 109, 54, '#508578', -.08, '#2b5557');
    ellipse(c, 17, -22 + breath, 72, 27, '#a3a477', -.08);
    c.save(); c.beginPath(); c.ellipse(-8, -43 + breath, 104, 49, -.08, 0, TAU); c.clip();
    for (let j = 0; j < 4; j++) for (let i = 0; i < 13; i++) {
      const xx = -104 + i * 15 + j % 2 * 7, yy = -77 + j * 16;
      c.beginPath(); c.arc(xx, yy, 6, .2, Math.PI - .2); c.strokeStyle = j < 2 ? '#a0bb8b54' : '#2d665d55'; c.lineWidth = 1.1; c.stroke();
    } c.restore();
    for (let i = 0; i < 7; i++) poly(c, [[-96 + i * 23, -62 - Math.sin(i / 6 * Math.PI) * 32 + breath], [-89 + i * 23, -80 - Math.sin(i / 6 * Math.PI) * 34 + breath], [-78 + i * 23, -66 - Math.sin(i / 6 * Math.PI) * 29 + breath]], '#8da98a');
    // A long arched neck and a large expressive head.
    c.beginPath(); c.moveTo(54, -45); c.bezierCurveTo(70, -73, 67, -108, 110, -135);
    c.bezierCurveTo(130, -151, 156, -148, 176, -132); c.lineTo(203, -117); c.quadraticCurveTo(208, -109, 191, -105);
    c.lineTo(165, -100); c.bezierCurveTo(133, -91, 123, -54, 93, -25); c.closePath(); c.fillStyle = '#56877a'; c.fill(); c.strokeStyle = '#2b5557'; c.lineWidth = 2; c.stroke();
    curve(c, [88, -29], [115, -66], [121, -95], [166, -107], '#abb78c', 12);
    poly(c, [[129, -140], [116, -176], [139, -154]], '#d5c7a0'); poly(c, [[151, -144], [157, -173], [166, -141]], '#e5d6ac');
    ellipse(c, 164, -127, 9, 5, '#d9bd65', -.15); ellipse(c, 168, -127, 2, 4, '#263d3e'); ellipse(c, 170, -129, 1, 1, '#fff1c0');
    ellipse(c, 195, -114, 2, 1.8, '#294b4d'); line(c, [[166, -105], [201, -109]], '#24464c', 2);
    if (awake) { poly(c, [[166, -103], [203, -108], [196, -95], [165, -95]], '#b98b75'); poly(c, [[176, -103], [179, -97], [181, -104]], '#f1dfb4'); }
    // Folded feet and ivory claws rest on the pile.
    ellipse(c, -42, -3, 25, 13, '#3a6a66', -.17); ellipse(c, 62, -7, 29, 13, '#477870', -.18);
    for (let i = 0; i < 3; i++) { poly(c, [[-47 + i * 10, 2], [-42 + i * 10, 13], [-36 + i * 10, 2]], '#dccba3'); poly(c, [[61 + i * 9, -1], [68 + i * 9, 9], [72 + i * 9, -2]], '#dccba3'); }
    c.restore();
  }

  function dragonCave(c, t, state) {
    sky(c, '#142d3b', '#30484b');
    // An opening onto mountains keeps the cave from becoming an empty black rectangle.
    arch(c, 647, 48, 299, 444, gradient(c, 0, 50, 0, 480, ['#788f9b', '#c8b0a2', '#6b8584']));
    ellipse(c, 813, 142, 37, 37, '#e6d3af');
    poly(c, [[657, 390], [717, 267], [771, 337], [850, 236], [941, 370], [941, 498], [657, 498]], '#637e83');
    poly(c, [[657, 427], [755, 351], [806, 396], [908, 310], [943, 402], [943, 505], [657, 505]], '#3a6067');
    poly(c, [[0, 0], [1000, 0], [1000, 104], [942, 87], [930, 211], [907, 86], [876, 59], [701, 62], [644, 129], [613, 319], [572, 168], [471, 91], [347, 130], [167, 84], [0, 180]], '#1e3640');
    poly(c, [[0, 0], [631, 0], [568, 108], [464, 142], [416, 240], [353, 140], [221, 122], [179, 216], [127, 113], [0, 142]], '#2d4449');
    for (let i = 0; i < 10; i++) {
      const x = 14 + i * 61, h = 70 + hash(i) * 119;
      poly(c, [[x, 55], [x + 31, h], [x + 51, 73]], '#263e45', '#52636a50');
    }
    curve(c, [0, 276], [190, 244], [292, 285], [504, 180], '#61746c25', 3);
    curve(c, [0, 340], [160, 297], [319, 341], [530, 224], '#61746c25', 3);
    poly(c, [[0, 482], [190, 437], [345, 465], [556, 420], [725, 451], [1000, 421], [1000, 620], [0, 620]], '#4b574f');
    ellipse(c, 498, 498, 335, 100, '#1f333b5a');
    const extra = Math.min(state.count, 8);
    glow(c, 459, 460, 265, '#c9a15127');
    coins(c, 453, 476, 263, 73 + extra * 2, 165, 8);
    coins(c, 418, 435 - extra * 2, 164 + extra * 4, 48, 115, 31);
    // A chest half open, shields and goblets give the treasure scale.
    rect(c, 140, 446, 85, 52, '#76523b', 4, '#d1ab60'); poly(c, [[134, 448], [134, 421], [211, 410], [226, 440]], '#9a7048', '#d5b268', 3);
    line(c, [[159, 446], [159, 497]], '#d3ac60', 8); line(c, [[205, 445], [205, 496]], '#d3ac60', 8);
    rect(c, 178, 466, 11, 14, '#e1bc71', 2); glow(c, 185, 433, 52, '#f9d58c2e');
    poly(c, [[708, 466], [742, 475], [735, 518], [708, 539], [683, 514], [676, 475]], '#476a6c', '#c7ac70', 4); crown(c, 709, 504, .65);
    line(c, [[762, 510], [778, 415]], '#c6bf9e', 5); line(c, [[756, 455], [788, 461]], '#b7a477', 5);
    const active = state.count > 0 && state.elapsed < 2.2;
    dragon(c, 429, 427 - extra * 1.2, 1.48, t, active);
    // A small traveller makes the dragon feel monumental.
    person(c, 844, 457, .83, '#b78f63', Math.sin(t * .4) * .2);
    line(c, [[856, 432], [863, 393]], '#8e7050', 3); glow(c, 863, 389, 46, '#f5be5a40'); ellipse(c, 863, 387 + Math.sin(t * 4) * 2, 5, 12, '#f1bf70');
    for (let i = 0; i < 9 + extra; i++) gem(c, 269 + hash(i * 3) * 424, 474 + hash(i * 7 + 9) * 78, .75 + hash(i) * .8, ['#8ab9af', '#c68e9a', '#a2a6cb', '#dfc58b'][i % 4]);
    for (let i = 0; i < 13; i++) {
      const x = 253 + hash(i * 7 + 4) * 383, y = 438 + hash(i + 90) * 100;
      c.globalAlpha = .25 + .6 * Math.pow(.5 + .5 * Math.sin(t * 1.1 + i * 2), 4); spark(c, x, y, 2 + i % 3, '#ffedb5');
    } c.globalAlpha = 1;
    if (active) {
      const strength = Math.sin(clamp(state.elapsed / 2.2) * Math.PI);
      c.save(); c.globalAlpha = strength;
      glow(c, 778, 286, 189, '#ffd27b60');
      c.beginPath(); c.moveTo(724, 271); c.bezierCurveTo(763, 221, 802, 216, 824, 159); c.bezierCurveTo(843, 197, 875, 169, 906, 124);
      c.bezierCurveTo(882, 225, 842, 253, 810, 274); c.bezierCurveTo(776, 301, 756, 287, 724, 271); c.closePath(); c.fillStyle = '#d4834c'; c.fill();
      c.beginPath(); c.moveTo(725, 272); c.bezierCurveTo(785, 243, 781, 220, 825, 204); c.bezierCurveTo(811, 244, 803, 256, 778, 273); c.closePath(); c.fillStyle = '#ffe5a0'; c.fill();
      for (let i = 0; i < 31; i++) { const q = (state.elapsed * .7 + i * .063) % 1; spark(c, 732 + q * 182 + Math.sin(i) * 12, 270 - q * 134 + Math.cos(i * 3) * 19, 1.6 + i % 3, '#ffe8a6'); }
      c.restore();
    } else {
      for (let i = 0; i < 3; i++) { const q = (t * .16 + i / 3) % 1; c.globalAlpha = (1 - q) * .19; ellipse(c, 719 + q * 22, 259 - q * 36, 5 + q * 11, 4 + q * 8, '#bdd0c2'); } c.globalAlpha = 1;
    }
    // Foreground stones and crystals finish the frame.
    poly(c, [[0, 551], [77, 503], [112, 543], [181, 558], [217, 620], [0, 620]], '#263f44');
    poly(c, [[1000, 483], [937, 521], [905, 594], [857, 620], [1000, 620]], '#263f44');
    gem(c, 83, 545, 2, '#749f9a'); gem(c, 119, 580, 1.5, '#8daeb0'); gem(c, 937, 553, 2.5, '#709c9d');
    motes(c, t, '#d6bc7b', 140, 334, 658, 209, 22);
  }

  function paperMountain(c, x, y, s, snow = true) {
    poly(c, [[x - 67 * s, y], [x - 14 * s, y - 136 * s], [x + 67 * s, y]], '#a3b9af', '#56757370', 1);
    poly(c, [[x - 14 * s, y - 136 * s], [x + 7 * s, y - 35 * s], [x + 67 * s, y]], '#6f9290');
    if (snow) poly(c, [[x - 14 * s, y - 136 * s], [x + 9 * s, y - 96 * s], [x - 5 * s, y - 104 * s], [x - 13 * s, y - 94 * s], [x - 24 * s, y - 109 * s], [x - 34 * s, y - 87 * s]], '#e8e6ca');
  }
  function dragonBook(c, t, state) {
    sky(c, '#4c3c3f', '#97715b');
    // A storyteller's table, with inlaid wood and physical writing tools.
    rect(c, 0, 95, 1000, 525, gradient(c, 0, 100, 0, 620, ['#8e6a52', '#604a41']));
    for (let i = 0; i < 10; i++) {
      line(c, [[0, 124 + i * 55], [1000, 124 + i * 55]], '#342e2d55', 2);
      for (let j = 0; j < 3; j++) curve(c, [hash(i + j) * 150, 134 + i * 55 + j * 8], [380, 128 + i * 55 + j * 8], [633, 148 + i * 55], [1000, 133 + i * 55 + j * 8], '#c29b6c20', 1);
    }
    poly(c, [[747, 92], [1000, 134], [1000, 620], [790, 620], [826, 370]], '#77444b');
    for (let i = 0; i < 8; i++) curve(c, [810 + i * 23, 105], [810 + i * 20, 291], [864 + i * 12, 421], [813 + i * 23, 620], '#ac6b632b', 4);
    ellipse(c, 502, 525, 337, 58, '#2c333551');
    openBook(c, 497, 497, 636, 230, -.035);
    // Ornamental illuminated margins, drawn as ink on the actual pages.
    for (let i = 0; i < 24; i++) {
      const x = 204 + i * 25;
      spark(c, x, 501 + Math.sin(i / 23 * Math.PI) * 19, 3, i % 2 ? '#ab8751' : '#955b59');
    }
    curve(c, [226, 366], [342, 405], [354, 473], [490, 482], '#75998e', 9);
    curve(c, [226, 366], [342, 405], [354, 473], [490, 482], '#bed2b6', 3);
    // Pop-up terrain: clear paper edges cast shadows onto the printed map.
    poly(c, [[287, 413], [355, 351], [503, 378], [589, 431], [491, 468], [340, 450]], '#798d7954');
    paperMountain(c, 351, 402, 1.05); paperMountain(c, 427, 399, 1.42); paperMountain(c, 508, 414, .87);
    for (let i = 0; i < 10; i++) pine(c, 271 + hash(i) * 277, 426 + hash(i + 19) * 34, .24 + hash(i + 6) * .3, '#4e7c6d');
    // A tiny paper castle and the flight of a much larger paper dragon.
    rect(c, 631, 401, 82, 47, '#d5c6a3', 1); arch(c, 660, 416, 19, 31, '#6c8175');
    for (const x of [628, 704]) { rect(c, x, 371, 20, 76, '#c6b58e'); poly(c, [[x - 6, 372], [x + 10, 344], [x + 26, 372]], '#8b6264'); }
    flag(c, 715, 342, .45, '#bd8e5c', t);
    coins(c, 585, 439, 74 + Math.min(state.count, 8) * 4, 23, 38 + Math.min(state.count, 8) * 5, 44);
    const active = state.count && state.elapsed < 2.8;
    const bob = Math.sin(t * .6) * 3;
    dragon(c, 532, 352 + bob, 1.02, t, active, true);
    // Hinges / paper feet make the illustration feel like a miniature instead of a sticker.
    poly(c, [[495, 379], [508, 408], [518, 379]], '#e9dbb4'); poly(c, [[586, 378], [600, 414], [613, 378]], '#e2d3a7');
    // Quill, inkwell, compass and a wax-seal press.
    rect(c, 121, 377, 51, 42, '#334c50', 8, '#a2a69a'); ellipse(c, 146, 377, 22, 8, '#a9ac96'); ellipse(c, 146, 377, 15, 5, '#20353c');
    curve(c, [145, 381], [146, 295], [189, 215], [208, 177], '#dbd2b0', 3);
    leaf(c, 156, 328, 27, 151, .4, '#d8d8bc', '#8b9c918a');
    for (let i = 0; i < 10; i++) line(c, [[159 + i * 3, 313 - i * 12], [180 + i * 3, 300 - i * 12]], '#aab5a17a', 1);
    ellipse(c, 156, 508, 49, 49, '#b08e54', 0, '#d8bd7e'); ellipse(c, 156, 508, 39, 39, '#d9cfaa', 0, '#76694d');
    poly(c, [[156, 475], [164, 508], [156, 540], [149, 508]], '#426d70'); poly(c, [[156, 475], [156, 508], [149, 508]], '#a65f57');
    for (let i = 0; i < 12; i++) { const a = i / 12 * TAU; line(c, [[156 + Math.cos(a) * 33, 508 + Math.sin(a) * 33], [156 + Math.cos(a) * 37, 508 + Math.sin(a) * 37]], '#837752'); }
    c.save(); c.translate(854, 366); c.rotate(.19); rect(c, -29, -43, 62, 88, '#5b7770', 5, '#d4b57c'); rect(c, -33, -43, 8, 88, '#b89b6a', 2); crown(c, 1, 0, .8); c.restore();
    ellipse(c, 851, 516, 34, 15, '#4b3333'); rect(c, 838, 455, 27, 57, '#ae8a5c', 6); ellipse(c, 851, 452, 22, 15, '#754745');
    for (let i = 0; i < Math.min(state.count, 6); i++) {
      const x = 594 + i * 27, y = 485 + Math.sin(i * .5) * 10;
      ellipse(c, x, y, 10, 9, '#a35557'); crown(c, x, y + 2, .27);
      const xx = 301 + i * 34;
      rect(c, xx, 450 - i % 2 * 11, 12, 21, '#cdbd94'); poly(c, [[xx - 2, 450 - i % 2 * 11], [xx + 6, 437 - i % 2 * 11], [xx + 14, 450 - i % 2 * 11]], '#8e6965');
    }
    if (active) {
      const q = clamp(state.elapsed / 2.8), light = Math.sin(q * Math.PI);
      glow(c, 578, 425, 174, '#efd59444');
      c.save(); c.globalAlpha = light;
      curve(c, [276, 437], [392, 503], [560, 452], [722, 475], '#f3ce7d', 3);
      for (let i = 0; i < 23; i++) { const z = (q + i * .035) % 1; spark(c, 295 + z * 413, 457 + Math.sin(z * 7) * 27 - z * 74, 2 + i % 3, '#ffe8a5'); }
      c.restore(); burst(c, state, 665, 437, '#f5dfac', 25, 90);
    }
    motes(c, t, '#edd4a2', 167, 160, 633, 358, 20);
    // A candle illuminates the miniature from the side, not a generic overlay spotlight.
    ellipse(c, 914, 249, 30, 10, '#ba9865'); rect(c, 906, 173, 16, 77, '#e7d7ab', 3);
    glow(c, 914, 159, 124, '#f3c17928'); ellipse(c, 914 + Math.sin(t * 2) * 1.5, 158, 5, 13, '#ffe2a2');
  }

  window.AssiVisuals.register({
    id: 'dynasty', title: 'Династия', category: 'Миры и истории',
    variants: [
      { name: 'Королевство на ладони', kicker: 'Живая изометрия', description: 'Город растёт на скальном острове: башни, кварталы, жители и мост через реку. Каждое решение меняет само королевство.', features: ['Постройки появляются прямо в городе', 'Жители, облака и знамёна движутся', 'Рост державы виден без таблиц'], action: 'Построить квартал', result: 'На острове появился новый квартал. Дома и жители остаются в королевстве.', accent: '#6bbaa0', background: '#223d3b' },
      { name: 'Театр королевского двора', kicker: 'Персонажи на сцене', description: 'Высокие окна, советники, трон и стол летописца. История династии разворачивается как камерный спектакль.', features: ['Правитель и советники становятся героями', 'Указ раскрывается над столом', 'Новые решения оставляют золотые печати'], action: 'Созвать совет', result: 'Совет принял указ. Летописец добавил новую печать в историю рода.', accent: '#d9b679', background: '#382b3a' }
    ],
    stats: (v, state) => v === 0 ? [['Жителей', String(128 + state.count * 24)], ['Кварталов', String(5 + state.count)], ['Настроение', state.count ? 'Праздничное' : 'Спокойное']] : [['Поколение', 'IV'], ['Указов', String(3 + state.count)], ['Влияние', String(62 + state.count * 7)]],
    draw: (c, t, state, v) => v === 0 ? dynastyRealm(c, t, state) : dynastyCourt(c, t, state)
  });
  window.AssiVisuals.register({
    id: 'library', title: 'Библиотека', category: 'Миры и истории',
    variants: [
      { name: 'Дом тихих открытий', kicker: 'Тёплый разрез', description: 'Библиотека как обжитый дом: полки до потолка, лестницы, читатели и кот. Редкие книги поднимаются в янтарном свете.', features: ['Настоящие этажи и читающие жители', 'Сотни цветных книжных корешков', 'Найденные тома пополняют выставку'], action: 'Открыть редкий том', result: 'Редкий том раскрылся над читальным столом. Коллекция пополнилась.', accent: '#d5b57d', background: '#3e3532' },
      { name: 'Обсерватория ненаписанных миров', kicker: 'Космическое книгохранилище', description: 'Книги обращаются вокруг раскрытого атласа, а из его страниц поднимаются горы, реки и маленькие миры.', features: ['Книги движутся по небесным орбитам', 'Пейзаж вырастает из страниц атласа', 'Каждое открытие зажигает новую планету'], action: 'Открыть новый мир', result: 'Из атласа родился новый мир. Теперь он сияет в книжном созвездии.', accent: '#a8d8c5', background: '#172a39' }
    ],
    stats: (v, state) => v === 0 ? [['Томов', String(1240 + state.count)], ['Читателей', String(18 + state.count * 2)], ['Редких находок', String(7 + state.count)]] : [['Миров', String(7 + state.count)], ['Созвездие', 'Атлас странствий'], ['Открытий', String(state.count)]],
    draw: (c, t, state, v) => v === 0 ? libraryHouse(c, t, state) : libraryObservatory(c, t, state)
  });
  window.AssiVisuals.register({
    id: 'garden', title: 'Сад', category: 'Живые миры',
    variants: [
      { name: 'Сад за старой калиткой', kicker: 'Солнечная садовая диорама', description: 'Дорожки, пруд, домик садовника и грядки с разными растениями. Редкий цветок раскрывается после полива и остаётся в саду.', features: ['Живые формы лепестков и листьев', 'Бабочки, вода и мягкий ветер', 'Новые цветы остаются на участке'], action: 'Вырастить редкий цветок', result: 'Редкий цветок раскрылся. У сада появился новый цвет и форма.', accent: '#afd18c', background: '#354c3e' },
      { name: 'Оранжерея лунных цветов', kicker: 'Ботаническая лаборатория', description: 'За стеклом холодная ночь, внутри — светящиеся орхидеи, вьющиеся растения и стол для новых скрещиваний.', features: ['Светящиеся растения под стеклянным куполом', 'Пыльца соединяет родительские цветы', 'Новые гибриды заселяют горшки'], action: 'Скрестить растения', result: 'Пыльца соединилась. Новый гибрид расцвёл в центре оранжереи.', accent: '#b5d8bd', background: '#203e42' }
    ],
    stats: (v, state) => v === 0 ? [['Цветущих', String(27 + state.count)], ['Редких видов', String(3 + state.count)], ['Условия', 'Мягкое солнце']] : [['Гибридов', String(8 + state.count)], ['Поколение', String(2 + state.count)], ['Микроклимат', 'Лунная ночь']],
    draw: (c, t, state, v) => v === 0 ? gardenPlot(c, t, state) : gardenGlass(c, t, state)
  });
  window.AssiVisuals.register({
    id: 'dragon-hoard', title: 'Сокровищница дракона', category: 'Персонажи и легенды',
    variants: [
      { name: 'Хранитель горной пещеры', kicker: 'Большой герой в живой среде', description: 'Дракон занимает сцену целиком: чешуя, крылья, дыхание и настоящая гора монет. Огонь освещает пещеру, сокровища остаются.', features: ['Крупный дракон с дыханием и пластикой', 'Золото, самоцветы и следы путешественников', 'Пламя освещает скалы и растит сокровищницу'], action: 'Разбудить дракона', result: 'Пещера озарилась пламенем. Дракон добавил добычу к своей горе золота.', accent: '#dcb878', background: '#1b343d' },
      { name: 'Бестиарий оживших легенд', kicker: 'Бумажный мир на столе', description: 'Раскрытая книга превращается в объёмную диораму: сложенный из бумаги дракон, горы, замок и золотые печати хроники.', features: ['Рельефные страницы, бумажные горы и дракон', 'Перо, компас и свеча создают масштаб', 'Новая легенда добавляет печать и башню'], action: 'Добавить легенду', result: 'На странице появилась новая легенда. Замок вырос, хроника получила печать.', accent: '#d8b18a', background: '#4a343a' }
    ],
    stats: (v, state) => v === 0 ? [['Золота', (8400 + state.count * 740).toLocaleString('ru-RU')], ['Самоцветов', String(9 + state.count * 3)], ['Настроение', state.count ? 'Гордое' : 'Дремлет']] : [['Легенд', String(12 + state.count)], ['Башен', String(3 + state.count)], ['Глава', 'Огненный хранитель']],
    draw: (c, t, state, v) => v === 0 ? dragonCave(c, t, state) : dragonBook(c, t, state)
  });
})();

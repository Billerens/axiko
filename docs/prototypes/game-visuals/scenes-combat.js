/* Visual studies only: no game engine, persistence, network or production code. */
(function () {
  'use strict';
  const W = 1000, H = 620, TAU = Math.PI * 2;
  const clamp = (n, a = 0, b = 1) => Math.max(a, Math.min(b, n));
  const rand = n => { const x = Math.sin(n * 127.1 + 31.7) * 43758.5453; return x - Math.floor(x); };
  const elapsed = s => s.elapsed == null ? 999 : Math.max(0, s.elapsed);
  const count = s => Math.max(0, Math.floor(s.count || 0));
  const attack = e => e < 1 ? Math.sin(clamp(e / .8) * Math.PI) : 0;
  function ellipse(c, x, y, rx, ry, color, rotation = 0) {
    c.beginPath(); c.ellipse(x, y, rx, ry, rotation, 0, TAU); c.fillStyle = color; c.fill();
  }
  function poly(c, points, color, line, width = 1) {
    c.beginPath(); points.forEach((p, i) => i ? c.lineTo(p[0], p[1]) : c.moveTo(p[0], p[1]));
    c.closePath(); c.fillStyle = color; c.fill();
    if (line) { c.strokeStyle = line; c.lineWidth = width; c.stroke(); }
  }
  function line(c, points, color, width = 2) {
    c.beginPath(); points.forEach((p, i) => i ? c.lineTo(p[0], p[1]) : c.moveTo(p[0], p[1]));
    c.strokeStyle = color; c.lineWidth = width; c.lineCap = 'round'; c.lineJoin = 'round'; c.stroke();
  }
  function round(c, x, y, w, h, r, color, stroke) {
    c.beginPath(); c.roundRect(x, y, w, h, r); c.fillStyle = color; c.fill();
    if (stroke) { c.strokeStyle = stroke; c.lineWidth = 1.5; c.stroke(); }
  }
  function sky(c, a, b, stop = H) {
    const g = c.createLinearGradient(0, 0, 0, stop); g.addColorStop(0, a); g.addColorStop(1, b);
    c.fillStyle = g; c.fillRect(0, 0, W, H);
  }
  function glow(c, x, y, radius, color) {
    const g = c.createRadialGradient(x, y, 0, x, y, radius);
    g.addColorStop(0, color); g.addColorStop(1, 'rgba(0,0,0,0)');
    c.fillStyle = g; c.fillRect(x - radius, y - radius, radius * 2, radius * 2);
  }
  function shadow(c, x, y, w, h = w * .2) { ellipse(c, x, y, w, h, 'rgba(5,12,15,.3)'); }
  function motes(c, t, n, color, y0 = 0, y1 = H, seed = 1) {
    for (let i = 0; i < n; i++) {
      const x = rand(i + seed) * W + Math.sin(t * .35 + i) * 9;
      const y = y0 + ((rand(i * 3 + seed) * (y1 - y0) - t * (3 + rand(i) * 4)) % (y1 - y0) + y1 - y0) % (y1 - y0);
      c.globalAlpha = .25 + .4 * (.5 + .5 * Math.sin(t + i));
      ellipse(c, x, y, 1 + rand(i + 2) * 2, 1 + rand(i + 2) * 2, color);
    }
    c.globalAlpha = 1;
  }
  function grass(c, x, y, size, color, t = 0) {
    const sway = Math.sin(t * 1.1 + x / 43) * size * .1;
    for (let j = -2; j <= 2; j++) line(c, [[x + j * 2, y], [x + j * size * .13 + sway, y - size * .5], [x + j * size * .3 + sway, y - size * (1 - Math.abs(j) * .12)]], color, 2.2);
  }
  function fern(c, x, y, size, t, color = '#416b4d') {
    c.save(); c.translate(x, y); c.rotate(Math.sin(t * .7 + x) * .025);
    for (let a = -2; a <= 2; a++) {
      c.save(); c.rotate(a * .29); line(c, [[0, 0], [0, -size]], '#42664c', 2);
      for (let j = 1; j <= 5; j++) {
        const yy = -j * size / 6, len = size * (.24 - j * .027);
        ellipse(c, -len * .65, yy + 3, len, size * .045, color, .42);
        ellipse(c, len * .65, yy + 3, len, size * .045, color, -.42);
      }
      c.restore();
    }
    c.restore();
  }
  function tree(c, x, y, scale, t, tone = '#284c40') {
    c.save(); c.translate(x, y); c.scale(scale, scale);
    poly(c, [[-12, 0], [-8, -175], [9, -190], [15, 0]], '#3f4032');
    line(c, [[1, -85], [-38, -146], [-60, -180]], '#3f4032', 10);
    line(c, [[5, -122], [45, -185], [72, -205]], '#3f4032', 8);
    for (let i = 0; i < 11; i++) ellipse(c, (rand(i + x) - .5) * 160 + Math.sin(t * .4 + i) * 2, -180 - rand(i + y) * 90, 38 + rand(i) * 28, 27 + rand(i + 4) * 19, i % 3 ? tone : '#41634b');
    c.restore();
  }
  function pennants(c, x, y, width, t, colors) {
    line(c, [[x, y], [x + width * .5, y + 28], [x + width, y]], '#9b855c', 2);
    for (let i = 0; i < 10; i++) {
      const u = (i + .5) / 10, xx = x + width * u, yy = y + Math.sin(u * Math.PI) * 27;
      poly(c, [[xx - 10, yy], [xx + 10, yy + 1], [xx + Math.sin(t * 2 + i) * 7, yy + 34]], colors[i % colors.length]);
    }
  }
  function impact(c, x, y, e, color, seed = 0) {
    if (e < 0 || e > 1.65) return;
    const k = 1 - e / 1.65;
    glow(c, x, y, 100 * k + 20, 'rgba(255,224,155,' + k * .3 + ')');
    c.globalAlpha = k;
    c.beginPath(); c.ellipse(x, y, 18 + e * 88, 10 + e * 35, -.12, 0, TAU);
    c.strokeStyle = color; c.lineWidth = 3 * k + .5; c.stroke();
    for (let i = 0; i < 17; i++) {
      const a = rand(seed + i) * TAU, speed = 40 + rand(i + 50) * 110;
      const xx = x + Math.cos(a) * speed * e, yy = y + Math.sin(a) * speed * e - 45 * e + 38 * e * e;
      line(c, [[xx, yy], [xx - Math.cos(a) * 8 * k, yy - Math.sin(a) * 8 * k]], color, 2.5);
    }
    c.globalAlpha = 1;
  }
  function beast(c, x, y, scale, t, o = {}) {
    c.save(); c.translate(x, y); c.scale(scale * (o.face || 1), scale);
    const b = Math.sin(t * 2 + (o.seed || 0)) * 1.4, color = o.color || '#82bfa1', dark = o.dark || '#345d54', pale = o.pale || '#d2e2bc';
    shadow(c, 0, 1, 73, 13);
    // A curved tail, four weighted legs and a separate belly keep the animal readable at a glance.
    c.beginPath(); c.moveTo(-42, -49); c.bezierCurveTo(-95, -44, -105, -104, -85, -108); c.bezierCurveTo(-98, -73, -65, -70, -43, -69);
    c.fillStyle = dark; c.fill();
    for (let i = 0; i < 4; i++) {
      const xx = [-41, 27, -26, 42][i], stride = (o.step || 0) * (i % 2 ? 8 : -8);
      poly(c, [[xx - 8, -43], [xx + 8, -42], [xx + 9 + stride, -10], [xx + 5 + stride, 0], [xx - 12 + stride, 0], [xx - 10, -15]], i < 2 ? dark : color);
      round(c, xx - 14 + stride, -7, 23, 9, 4, '#283b3c');
    }
    c.translate(0, b);
    if (o.wings) {
      poly(c, [[-2, -70], [-28, -131], [-76, -159], [-65, -112], [-83, -85], [-35, -92], [-18, -52]], o.wings, dark, 1.6);
      line(c, [[-5, -64], [-30, -123], [-69, -151]], pale, 2);
      line(c, [[-30, -123], [-65, -112]], dark, 1.4);
      line(c, [[-30, -123], [-58, -91]], dark, 1.4);
    }
    ellipse(c, -4, -54, 57, 35, color);
    ellipse(c, 2, -39, 43, 17, pale);
    for (let i = 0; i < 8; i++) ellipse(c, -37 + (i % 4) * 17, -69 + Math.floor(i / 4) * 17, 5, 3.4, dark, i * .4);
    ellipse(c, 43, -84, 27, 28, color, -.18);
    poly(c, [[28, -102], [19, -145], [41, -123], [43, -105]], color, dark);
    poly(c, [[49, -104], [61, -141], [68, -111]], color, dark);
    poly(c, [[27, -116], [25, -136], [35, -124]], '#c98d89');
    if (o.horns) {
      line(c, [[27, -110], [14, -143], [18, -160]], '#c8b98a', 5);
      line(c, [[17, -141], [3, -150]], '#c8b98a', 3.5);
      line(c, [[49, -112], [57, -145], [73, -155]], '#e2d2a8', 5);
      line(c, [[58, -142], [51, -156]], '#e2d2a8', 3.5);
    }
    ellipse(c, 64, -73, 24, 14, pale, -.2);
    ellipse(c, 83, -77, 6, 4.5, '#263b3c');
    const blink = ((t + (o.seed || 0)) % 4.9) < .12;
    if (blink) line(c, [[45, -91], [56, -91]], dark, 2);
    else {
      ellipse(c, 50, -93, 7, 9, '#f2edcf'); ellipse(c, 53, -92, 3.5, 6, '#223a3c'); ellipse(c, 54, -95, 1.5, 2, '#fff8d6');
    }
    line(c, [[65, -65], [75, -66]], dark, 1.6);
    if (o.scar) line(c, [[28, -85], [35, -77], [29, -71]], '#c56b55', 2.6);
    c.restore();
  }
  function hero(c, x, y, scale, t, o = {}) {
    c.save(); c.translate(x, y); c.scale(scale * (o.face || 1), scale);
    const sway = Math.sin(t * 1.8) * 2, cloak = o.cloak || '#426e82';
    shadow(c, 0, 2, 43, 10);
    poly(c, [[-28, -102], [22, -97], [39 + sway, -13], [-42 + sway, -6], [-30, -55]], cloak, '#1d303f', 2);
    poly(c, [[-18, -49], [-2, -48], [-3, -9], [-25, -7]], '#7b929b');
    poly(c, [[6, -48], [22, -47], [25, -7], [6, -8]], '#4a6572');
    round(c, -29, -13, 27, 14, 4, '#1e2d36'); round(c, 4, -12, 29, 13, 4, '#1e2d36');
    poly(c, [[-26, -100], [19, -101], [29, -74], [19, -43], [-24, -43], [-32, -77]], '#bcc4b6', '#263f4b', 2);
    poly(c, [[-20, -95], [-5, -77], [18, -96], [21, -57], [-23, -57]], '#809c9e');
    line(c, [[-5, -90], [-5, -59]], '#d4dfc6', 3);
    round(c, -26, -55, 49, 9, 2, '#574536'); round(c, -7, -57, 13, 12, 2, '#c3a56c');
    ellipse(c, -1, -122, 24, 25, '#9aafb2');
    poly(c, [[-24, -125], [-14, -147], [8, -149], [24, -132], [22, -121]], '#6d8a95', '#263e49', 2);
    poly(c, [[-23, -122], [23, -122], [17, -109], [-16, -109]], '#233a47');
    line(c, [[-13, -117], [0, -117]], '#d9ebdf', 2.4); line(c, [[7, -117], [15, -117]], '#d9ebdf', 2.4);
    poly(c, [[-22, -103], [19, -104], [13, -92], [-25, -94], [-42 - sway, -73], [-43, -96]], o.scarf || '#d59d60');
    if (o.gun) {
      line(c, [[17, -83], [39, -77], [60, -87]], '#bac5b5', 13);
      poly(c, [[32, -96], [80, -92], [79, -82], [48, -82], [42, -69], [32, -71]], '#263e46', '#8ba6a1', 2);
      round(c, 69, -96, 19, 8, 2, '#a5c1b7');
    } else {
      line(c, [[21, -85], [42, -71]], '#bac5b5', 13);
      c.save(); c.translate(42, -71); c.rotate(-.2 - (o.swing || 0) * 1.35);
      poly(c, [[-4, 7], [-6, -66], [0, -89], [7, -66], [5, 7]], '#e4e4cf', '#8ba9af', 1.5);
      line(c, [[0, -72], [0, -2]], '#fff9dd', 2); line(c, [[-15, 5], [15, 5]], '#c7a96f', 5);
      round(c, -4, 8, 8, 18, 2, '#694b3b'); c.restore();
      poly(c, [[-48, -91], [-19, -84], [-22, -52], [-40, -36], [-57, -58]], '#426c78', '#ccb67d', 3);
      line(c, [[-39, -79], [-39, -49]], '#d4d0a3', 3);
    }
    c.restore();
  }
  function guardian(c, x, y, scale, t, scars = 0) {
    c.save(); c.translate(x, y); c.scale(scale, scale);
    const b = Math.sin(t * 1.2) * 1.7;
    shadow(c, 0, 1, 60, 13);
    poly(c, [[-40, -65], [-10, -61], [-14, -8], [-49, 0]], '#393e48', '#19252e', 2);
    poly(c, [[11, -61], [40, -65], [53, 0], [14, -5]], '#53525a', '#19252e', 2);
    c.translate(0, b);
    poly(c, [[-45, -136], [37, -136], [51, -80], [29, -45], [-29, -44], [-54, -83]], '#575b62', '#202e3a', 3);
    poly(c, [[-30, -128], [1, -99], [29, -128], [35, -75], [1, -58], [-38, -76]], '#858684');
    line(c, [[-26, -87], [24, -87]], '#3b444d', 4); line(c, [[-23, -74], [19, -74]], '#3b444d', 4);
    ellipse(c, 0, -153, 28, 32, '#a09b89');
    poly(c, [[-28, -157], [-25, -183], [-7, -176], [8, -176], [29, -182], [28, -155], [16, -130], [-17, -131]], '#4a5360', '#1c2e39', 3);
    poly(c, [[-27, -172], [-50, -198], [-39, -163]], '#b7b39e'); poly(c, [[26, -172], [49, -197], [42, -160]], '#b7b39e');
    line(c, [[-18, -155], [-6, -151]], '#efbd78', 4); line(c, [[7, -151], [19, -155]], '#efbd78', 4);
    poly(c, [[-9, -143], [9, -143], [12, -129], [-11, -129]], '#26323c');
    for (let i = 0; i < Math.min(5, scars); i++) line(c, [[-20 + i * 10, -119], [-13 + i * 8, -103], [-22 + i * 9, -94]], '#d8ac73', 1.5);
    poly(c, [[-45, -131], [-69, -115], [-64, -61], [-46, -58], [-41, -103]], '#747575', '#23333e', 2);
    poly(c, [[40, -130], [67, -112], [70, -63], [51, -55], [40, -101]], '#5e6469', '#23333e', 2);
    line(c, [[67, -57], [72, -177]], '#745d42', 8);
    poly(c, [[67, -179], [110, -177], [126, -145], [103, -121], [74, -135]], '#b1b3a6', '#334552', 3);
    line(c, [[105, -174], [120, -145], [102, -127]], '#ece4c8', 2);
    c.restore();
  }
  function torch(c, x, y, t, size = 1) {
    c.save(); c.translate(x, y); c.scale(size, size);
    glow(c, 0, -20, 95, 'rgba(255,164,68,.18)');
    line(c, [[0, 12], [0, -5]], '#786350', 6);
    poly(c, [[-11, -7], [11, -7], [6, 9], [-6, 9]], '#403d3c', '#a08b64');
    const flicker = Math.sin(t * 7 + x) * 4;
    poly(c, [[-7, -8], [-11, -21], [0, -44 - flicker], [2, -26], [9, -34 + flicker], [10, -13], [4, -6]], '#ed994e');
    poly(c, [[-4, -8], [-5, -21], [2, -31], [5, -14], [1, -7]], '#ffe1a1'); c.restore();
  }
  function arch(c, x, y, w, h, stone, inside) {
    c.save(); c.translate(x, y);
    c.beginPath(); c.moveTo(0, h); c.lineTo(0, w * .48); c.quadraticCurveTo(w * .04, 0, w / 2, -15); c.quadraticCurveTo(w * .96, 0, w, w * .48); c.lineTo(w, h); c.closePath();
    c.fillStyle = inside; c.fill(); c.strokeStyle = stone; c.lineWidth = 17; c.stroke();
    for (let j = 1; j < 5; j++) { line(c, [[-7, h - j * (h - w * .5) / 5], [6, h - j * (h - w * .5) / 5]], '#263b42', 2); line(c, [[w - 6, h - j * (h - w * .5) / 5], [w + 7, h - j * (h - w * .5) / 5]], '#263b42', 2); }
    c.restore();
  }
  function hatchery(c, t, s) {
    const n = count(s), e = elapsed(s);
    sky(c, '#102d34', '#779576'); glow(c, 500, 55, 480, 'rgba(177,215,168,.26)');
    for (let i = 0; i < 7; i++) {
      const x = i * 170 - 10;
      tree(c, x, 390, .9 + rand(i) * .3, t, '#2a5549');
      c.beginPath(); c.moveTo(x, 368); c.quadraticCurveTo(x + 10, 60, 500, -55); c.strokeStyle = '#75958b'; c.lineWidth = 7; c.stroke();
    }
    line(c, [[0, 105], [1000, 105]], 'rgba(172,200,179,.3)', 3);
    line(c, [[0, 205], [1000, 205]], 'rgba(172,200,179,.25)', 3);
    for (let i = 0; i < 30; i++) { const x = rand(i + 800) * W, y = rand(i + 83) * 250; ellipse(c, x, y, 1.8, 4.5, 'rgba(220,238,213,.32)'); }
    poly(c, [[0, 350], [260, 320], [690, 333], [1000, 385], [1000, 620], [0, 620]], '#2b4d42');
    ellipse(c, 510, 470, 405, 127, '#547364'); ellipse(c, 510, 453, 380, 119, '#738a70');
    for (let i = 0; i < 28; i++) {
      const a = i / 28 * TAU, x = 510 + Math.cos(a) * 359, y = 454 + Math.sin(a) * 111;
      ellipse(c, x, y, 18, 9, i % 3 ? '#a5b49a' : '#667e6b', a * .15);
    }
    beast(c, 256, 433, 1.55, t, { color: '#bba5bf', dark: '#675b7d', pale: '#e2d0ca', horns: true, seed: 1 });
    beast(c, 756, 445, 1.7, t, { face: -1, color: '#74b09d', dark: '#386c67', pale: '#d1ddbb', wings: '#6b938e', seed: 3 });
    shadow(c, 510, 505, 85, 21);
    const reveal = n > 0 && e > .45;
    if (reveal) beast(c, 510, 507, 1.03 + Math.min(3, n) * .07, t, { color: ['#9dc9ae', '#b8b2d3', '#cfb580'][n % 3], dark: '#4c7470', pale: '#eef0ce', horns: n % 2 === 0, wings: n % 2 ? '#a9b5d7' : null, seed: 7 });
    else {
      const wobble = e < .5 ? Math.sin(e * 50) * .08 : Math.sin(t * 1.1) * .012;
      ellipse(c, 510, 454, 44, 58, '#d1d6ad', wobble);
      ellipse(c, 495, 434, 13, 18, '#9ea98c', -.25); ellipse(c, 530, 470, 11, 8, '#879f88');
      line(c, [[482, 454], [497, 448], [503, 459], [517, 447], [535, 455]], '#66847b', 2);
    }
    if (n > 0) {
      poly(c, [[451, 510], [461, 488], [472, 503], [483, 492], [490, 515]], '#d5dcb4');
      poly(c, [[548, 517], [554, 496], [566, 508], [573, 489], [584, 514]], '#becaa8');
      impact(c, 512, 458, e, '#d8f0bd', n * 7);
    }
    for (let i = 0; i < 9; i++) { fern(c, i * 129 - 10, 600 + rand(i) * 25, 90 + rand(i + 3) * 50, t, i % 2 ? '#376c4d' : '#254f42'); }
    motes(c, t, 28, '#e6ecc0', 180, 560, 9);
  }
  function tournament(c, t, s) {
    const n = count(s), e = elapsed(s), lunge = attack(e);
    sky(c, '#48546f', '#e7b383', 390); ellipse(c, 754, 132, 63, 63, '#f3c48d');
    for (let i = 0; i < 5; i++) poly(c, [[i * 250 - 80, 307], [i * 250 + 57, 161 + rand(i) * 70], [i * 250 + 240, 307]], i % 2 ? '#566778' : '#677985');
    for (let row = 0; row < 3; row++) {
      round(c, 75, 219 + row * 42, 850, 30, 5, ['#504b4b', '#65534c', '#755e4e'][row]);
      for (let i = 0; i < 27; i++) { const x = 90 + i * 31, y = 224 + row * 42; ellipse(c, x, y - 6, 6, 8, ['#9b8675', '#728e86', '#c6a580'][i % 3]); round(c, x - 7, y, 14, 17, 4, ['#49626b', '#776053', '#66526d'][i % 3]); }
    }
    pennants(c, 52, 132, 893, t, ['#d8946a', '#799e99', '#b49fc3']);
    poly(c, [[0, 354], [1000, 354], [1000, 620], [0, 620]], '#8e775d');
    ellipse(c, 500, 459, 420, 128, '#baa27b'); ellipse(c, 500, 456, 341, 95, '#d3bc91');
    c.beginPath(); c.ellipse(500, 456, 330, 88, 0, 0, TAU); c.strokeStyle = '#f0d9ad'; c.lineWidth = 3; c.stroke();
    for (let i = 0; i < 37; i++) { const x = 120 + rand(i) * 760, y = 361 + rand(i + 50) * 200; line(c, [[x, y], [x + 6, y - 1]], '#ad9673', 1); }
    beast(c, 310 + lunge * 95, 476, 1.85, t, { color: '#74adae', dark: '#386273', pale: '#dae0bd', horns: true, wings: '#678fa5', step: lunge, seed: 5 });
    beast(c, 703 + lunge * 25, 480, 2.0, t, { face: -1, color: '#c49b73', dark: '#7b5e53', pale: '#ead0a5', horns: n % 3 > 0, scar: n > 0, seed: 2 });
    if (e < 1.65) impact(c, 568, 342, Math.max(0, e - .22), '#fff0b3', n * 9);
    for (const x of [76, 924]) { round(c, x - 9, 310, 18, 227, 4, '#624b3a'); ellipse(c, x, 309, 12, 8, '#9f7c50'); }
    line(c, [[76, 500], [500, 556], [924, 500]], '#8a6644', 6);
    for (let i = 0; i < Math.min(n, 7); i++) { const x = 352 + i * 42; poly(c, [[x, 566], [x + 13, 566], [x + 17, 597], [x + 7, 589], [x - 3, 598]], '#6b918d'); ellipse(c, x + 6, 564, 9, 9, '#d9b96c'); }
    grass(c, 36, 615, 55, '#545f43', t); grass(c, 958, 610, 70, '#586849', t);
    motes(c, t, 20, '#f5d6a4', 300, 570, 45);
  }
  function towerCut(c, t, s) {
    const n = count(s), e = elapsed(s), floor = Math.floor(n / 3) % 3, lunge = attack(e);
    sky(c, '#121f35', '#34485a'); ellipse(c, 126, 93, 43, 43, '#c7d6d1'); ellipse(c, 142, 80, 42, 42, '#18283d');
    for (let i = 0; i < 34; i++) ellipse(c, rand(i) * 1000, rand(i + 91) * 360, 1, 1, '#9aafb7');
    for (let i = 0; i < 13; i++) {
      const x = i * 88 - 30, top = 390 - rand(i + 11) * 160;
      poly(c, [[x, 620], [x, top], [x + 28, top - 49], [x + 58, top], [x + 58, 620]], '#1e3043');
      for (let j = 0; j < 3; j++) round(c, x + 23, top + 28 + j * 52, 10, 19, 4, '#526168');
    }
    round(c, 224, 28, 600, 568, 5, '#1b2732');
    for (let f = 0; f < 3; f++) {
      const base = 555 - f * 174, active = f === floor;
      round(c, 250, base - 151, 548, 149, 0, active ? '#3f4646' : '#29333b');
      for (let row = 0; row < 4; row++) for (let j = 0; j < 9; j++) {
        const x = 252 + j * 63 + (row % 2 ? -29 : 0);
        line(c, [[Math.max(250, x), base - 143 + row * 36], [Math.min(798, x + 59), base - 143 + row * 36]], active ? '#58605a' : '#36424a', 1);
      }
      arch(c, 444, base - 129, 97, 127, '#596369', '#1b2b38');
      if (f === 2) { poly(c, [[467, base - 103], [492, base - 121], [517, base - 103], [517, base - 42], [467, base - 42]], '#427080'); line(c, [[492, base - 116], [492, base - 45]], '#adc6b1', 3); }
      if (f === 1) for (let j = 0; j < 7; j++) { round(c, 278 + j * 13, base - 54 - j % 2 * 5, 10, 40, 1, ['#7a7164', '#6d8491', '#947d5b'][j % 3]); }
      if (f === 0) { round(c, 711, base - 33, 55, 30, 4, '#80613f', '#b69a62'); line(c, [[737, base - 32], [737, base - 5]], '#d6b56d', 4); }
      poly(c, [[239, base], [811, base], [824, base + 16], [224, base + 16]], '#74736a');
      line(c, [[240, base + 1], [811, base + 1]], '#a2a28d', 2);
      torch(c, 387, base - 96, t + f, .65); torch(c, 683, base - 96, t + f + 2, .65);
      if (active) {
        glow(c, 525, base - 63, 231, 'rgba(232,181,102,.13)');
        hero(c, 448 + lunge * 48, base - 2, .69, t, { cloak: '#557f8c', swing: lunge });
        guardian(c, 625 + lunge * 12, base - 3, .7, t, n % 3);
        impact(c, 568, base - 90, e - .18, '#f5d296', n);
      } else if (f !== 2) {
        poly(c, [[585, base - 3], [592, base - 70], [624, base - 68], [634, base - 3]], '#26333c'); ellipse(c, 609, base - 83, 17, 20, '#4d5960');
      }
    }
    for (const x of [222, 807]) { round(c, x - 12, 16, 26, 585, 4, '#68716f'); for (let y = 50; y < 600; y += 33) line(c, [[x - 12, y], [x + 14, y]], '#3c4b52', 2); }
    poly(c, [[204, 27], [228, 4], [286, 17], [744, 17], [814, 4], [843, 27]], '#889087');
    for (let i = 0; i < 5; i++) ellipse(c, 180 + i * 185 + Math.sin(t * .1 + i) * 16, 598, 170, 36, 'rgba(135,163,172,.07)');
  }
  function towerTheatre(c, t, s) {
    const n = count(s), e = elapsed(s), lunge = attack(e);
    sky(c, '#171b30', '#4b4744');
    for (let i = 0; i < 5; i++) {
      const x = 42 + i * 225;
      arch(c, x, 44, 136, 414, '#4a5261', '#1a293c');
      if (i > 0 && i < 4) {
        poly(c, [[x + 16, 194], [x + 69, 80], [x + 120, 195], [x + 119, 337], [x + 18, 337]], i % 2 ? '#355c70' : '#6d526d');
        line(c, [[x + 68, 92], [x + 68, 335]], '#a5a18b', 5); line(c, [[x + 21, 223], [x + 116, 223]], '#a5a18b', 5);
        poly(c, [[x + 68, 146], [x + 93, 191], [x + 68, 236], [x + 43, 191]], '#c5ad7b');
      }
    }
    poly(c, [[0, 439], [1000, 439], [1000, 620], [0, 620]], '#4b4b4b');
    for (let i = -4; i < 9; i++) line(c, [[500 + i * 80, 439], [500 + i * 190, 620]], '#74736b', 1.2);
    for (let j = 0; j < 5; j++) line(c, [[0, 450 + j * j * 9], [1000, 450 + j * j * 9]], '#333f46', 2);
    poly(c, [[70, 590], [330, 446], [427, 446], [335, 620]], 'rgba(142,180,189,.1)');
    poly(c, [[650, 620], [592, 444], [686, 444], [977, 612]], 'rgba(231,188,125,.12)');
    torch(c, 95, 330, t, 1.45); torch(c, 903, 330, t + 2, 1.45);
    hero(c, 318 + lunge * 78, 542, 1.8, t, { cloak: '#4d8093', scarf: '#c7a76d', swing: lunge });
    guardian(c, 694 + lunge * 22, 544, 1.8, t, Math.min(n, 5));
    if (e < 1.4) {
      c.globalAlpha = 1 - e / 1.4; c.beginPath(); c.ellipse(529, 365, 99 + e * 28, 55, -.65, -1.1, 1.9); c.strokeStyle = '#e6f6ee'; c.lineWidth = 11 * (1 - e / 1.4); c.stroke(); c.globalAlpha = 1;
      impact(c, 614, 373, e - .18, '#f2d29a', n * 7);
    }
    for (let i = 0; i < Math.min(9, n * 2); i++) poly(c, [[562 + i * 17, 563 + i % 3 * 8], [567 + i * 17, 551 + i % 3 * 8], [576 + i * 17, 565 + i % 3 * 8]], '#9d9985');
    motes(c, t, 31, '#cabd91', 70, 560, 87);
    poly(c, [[0, 0], [55, 0], [42, 465], [13, 620], [0, 620]], '#17212e'); poly(c, [[945, 0], [1000, 0], [1000, 620], [977, 590], [962, 428]], '#17212e');
  }
  const ROUTE = [6, 3, 4, 1, 2, 5, 8, 7, 4, 3];
  function tilePosition(i, v) { return { x: (v ? 285 : 290) + i % 3 * 145 + 66, y: (v ? 112 : 121) + Math.floor(i / 3) * 137 + 80 }; }
  function movingTile(s, v) {
    const n = count(s), now = tilePosition(ROUTE[n % ROUTE.length], v), before = tilePosition(ROUTE[Math.max(0, n - 1) % ROUTE.length], v);
    const k = clamp(elapsed(s) / .55), ease = k * k * (3 - 2 * k);
    return { x: before.x + (now.x - before.x) * ease, y: before.y + (now.y - before.y) * ease, face: now.x < before.x ? -1 : 1 };
  }
  function chest(c, x, y, open, scale = 1) {
    c.save(); c.translate(x, y); c.scale(scale, scale); shadow(c, 0, 1, 30, 7);
    round(c, -27, -31, 54, 32, 5, '#866345', '#cfad6c');
    c.save(); c.translate(-26, -29); c.rotate(open ? -.7 : 0); round(c, 0, -18, 54, 19, 7, '#ad8455', '#dec185'); line(c, [[11, -15], [11, -1]], '#d2b57c', 3); line(c, [[43, -15], [43, -1]], '#d2b57c', 3); c.restore();
    if (open) { glow(c, 0, -29, 60, 'rgba(249,204,100,.4)'); for (let i = 0; i < 7; i++) ellipse(c, -19 + i * 6, -28 - i % 2 * 5, 5, 2.5, '#f2cf78'); }
    else round(c, -5, -28, 10, 15, 2, '#dfc27e'); c.restore();
  }
  function ghost(c, x, y, t, scale = 1) {
    c.save(); c.translate(x, y + Math.sin(t * 2) * 5); c.scale(scale, scale);
    glow(c, 0, -34, 73, 'rgba(92,207,191,.25)');
    c.beginPath(); c.moveTo(-27, 0); c.bezierCurveTo(-17, -18, -24, -67, 0, -73); c.bezierCurveTo(29, -66, 20, -19, 32, 0); c.lineTo(14, -9); c.lineTo(1, 0); c.lineTo(-11, -9); c.closePath(); c.fillStyle = '#72b8ae'; c.fill();
    ellipse(c, 1, -47, 15, 19, '#29495a'); ellipse(c, -5, -49, 3, 5, '#e5f6d8'); ellipse(c, 8, -49, 3, 5, '#e5f6d8');
    line(c, [[-15, -31], [-29, -20]], '#a1d9be', 8); line(c, [[17, -31], [31, -20]], '#a1d9be', 8); c.restore();
  }
  function dungeonTable(c, t, s) {
    const n = count(s), e = elapsed(s);
    sky(c, '#2d2928', '#6f5039');
    for (let i = 0; i < 12; i++) { const x = i * 91; round(c, x, 0, 89, H, 0, i % 2 ? '#624733' : '#70513a'); for (let j = 0; j < 4; j++) line(c, [[x + 15 + j * 17, 0], [x + 8 + j * 17, 620]], 'rgba(31,25,24,.15)', 2); }
    glow(c, 80, 140, 500, 'rgba(230,179,93,.19)');
    c.save(); c.translate(115, 372); c.rotate(-.22); round(c, -92, -121, 188, 249, 8, '#b9a582', '#d5c399');
    line(c, [[-68, -76], [13, -96], [48, -20], [0, 31], [65, 82]], '#81745b', 2);
    for (let i = 0; i < 6; i++) { const x = -58 + rand(i) * 111, y = -83 + i * 30; c.beginPath(); c.arc(x, y, 7, 0, TAU); c.strokeStyle = '#8f7052'; c.lineWidth = 2; c.stroke(); } c.restore();
    round(c, 265, 95, 475, 450, 17, '#272c2b'); round(c, 275, 101, 455, 433, 12, '#574f40', '#b39568');
    for (let i = 0; i < 9; i++) {
      const p = tilePosition(i, 0), visited = ROUTE.slice(0, Math.min(n + 1, ROUTE.length)).includes(i);
      round(c, p.x - 64, p.y - 65, 132, 126, 7, '#403f36');
      round(c, p.x - 64, p.y - 75, 132, 126, 7, visited ? '#959880' : ['#858b7a', '#7b8275', '#91917d'][i % 3], '#b7b59b');
      line(c, [[p.x - 54, p.y - 60], [p.x - 28, p.y - 37], [p.x - 37, p.y - 14]], '#626f63', 1.5);
      line(c, [[p.x + 52, p.y + 37], [p.x + 36, p.y + 21], [p.x + 22, p.y + 29]], '#626f63', 1.5);
      if (visited) { ellipse(c, p.x - 14, p.y + 24, 4, 7, '#c5b487', -.3); ellipse(c, p.x + 2, p.y + 17, 4, 7, '#c5b487', -.3); }
    }
    for (const i of [0, 5]) {
      const p = tilePosition(i, 0); for (let j = 0; j < 3; j++) { round(c, p.x - 48 + j * 32, p.y - 63, 28, 33, 3, '#555f59', '#9a9f8b'); round(c, p.x - 50 + j * 32, p.y - 69, 32, 9, 2, '#a4a58f'); }
    }
    const trap = tilePosition(7, 0); for (let i = 0; i < 5; i++) poly(c, [[trap.x - 31 + i * 15, trap.y + 7], [trap.x - 25 + i * 15, trap.y - 17], [trap.x - 19 + i * 15, trap.y + 7]], '#d6d5bd', '#535f5b');
    const treasure = tilePosition(2, 0); chest(c, treasure.x, treasure.y + 15, n >= 4, .95);
    if (n < 2) { const foe = tilePosition(4, 0); guardian(c, foe.x + 7, foe.y + 22, .48, t, 0); }
    else { const foe = tilePosition(4, 0); ellipse(c, foe.x, foe.y + 17, 19, 10, '#6b6e62'); line(c, [[foe.x - 13, foe.y + 4], [foe.x + 17, foe.y + 24]], '#d4d1b4', 5); }
    const player = movingTile(s, 0); hero(c, player.x, player.y + 25, .57, t, { face: player.face, cloak: '#8d504b', scarf: '#d3b77e', swing: attack(e) * .4 });
    impact(c, player.x, player.y + 10, e, '#f3d39a', n);
    // Physical objects around the board anchor the scale: candle, dice, a discarded blade.
    round(c, 117, 86, 25, 63, 6, '#d1bd8e'); ellipse(c, 130, 87, 13, 5, '#eee0b0'); torch(c, 130, 93, t, .6);
    c.save(); c.translate(829, 432); c.rotate(.25); round(c, -28, -28, 56, 56, 8, '#cbbd97', '#eddfb5'); for (const p of [[-13, -13], [13, -13], [0, 0], [-13, 13], [13, 13]]) ellipse(c, p[0], p[1], 4, 4, '#645744'); c.restore();
    c.save(); c.translate(872, 246); c.rotate(.45); poly(c, [[-9, 74], [-11, -78], [0, -112], [11, -78], [9, 74]], '#b5c0b6', '#667a7a', 2); line(c, [[-28, 72], [28, 72]], '#b78a4d', 8); round(c, -7, 77, 14, 58, 4, '#5d3c30'); c.restore();
  }
  function dungeonLuminous(c, t, s) {
    const n = count(s), e = elapsed(s);
    sky(c, '#091d2b', '#123944');
    glow(c, 500, 320, 360, 'rgba(44,168,155,.15)');
    for (let i = 0; i < 10; i++) { const x = i * 118 - 34; poly(c, [[x, 0], [x + 40, 0], [x + 33, 206 + rand(i) * 82], [x + 9, 273 + rand(i) * 80]], '#142d38'); }
    arch(c, 407, -40, 191, 165, '#3d646c', '#0b202e');
    for (let i = 0; i < 7; i++) line(c, [[435 + i * 22, 10], [435 + i * 22, 116]], '#547c7d', 6);
    for (const x of [216, 784]) { round(c, x - 20, 54, 40, 485, 8, '#254551', '#426775'); round(c, x - 29, 519, 58, 25, 5, '#42636a'); }
    for (let i = 0; i < 9; i++) {
      const p = tilePosition(i, 1), visited = ROUTE.slice(0, Math.min(n + 1, ROUTE.length)).includes(i);
      poly(c, [[p.x - 64, p.y + 47], [p.x + 67, p.y + 47], [p.x + 61, p.y + 64], [p.x - 60, p.y + 65]], '#102b37');
      round(c, p.x - 64, p.y - 74, 132, 123, 5, visited ? '#29555a' : '#213e4b', visited ? '#87c7aa' : '#466e78');
      for (let j = 0; j < 3; j++) line(c, [[p.x - 52, p.y - 59 + j * 35], [p.x + 54, p.y - 59 + j * 35]], '#365461', 1);
      c.beginPath(); c.ellipse(p.x, p.y - 6, 29, 24, 0, 0, TAU); c.strokeStyle = visited ? '#89d6ba' : '#4a6b78'; c.lineWidth = 1.5; c.stroke();
      line(c, [[p.x, p.y - 26], [p.x + 17, p.y - 5], [p.x, p.y + 15], [p.x - 17, p.y - 5], [p.x, p.y - 26]], visited ? '#a1d5b1' : '#4a6b78', 2);
      if (visited) glow(c, p.x, p.y - 3, 61, 'rgba(89,209,172,.11)');
    }
    const treasure = tilePosition(2, 1); chest(c, treasure.x, treasure.y + 16, n >= 4, .9);
    if (n < 2) { const q = tilePosition(4, 1); ghost(c, q.x, q.y + 20, t, .95); }
    else { const q = tilePosition(4, 1); glow(c, q.x, q.y, 49, 'rgba(127,227,190,.23)'); ellipse(c, q.x, q.y, 7, 10, '#a5e6cc', t * .4); }
    const p = movingTile(s, 1); glow(c, p.x, p.y, 75, 'rgba(156,215,200,.2)'); hero(c, p.x, p.y + 24, .57, t, { face: p.face, cloak: '#7caaa1', scarf: '#e9c98a', swing: attack(e) * .35 });
    impact(c, p.x, p.y + 9, e, '#a4e6c7', n + 61);
    for (let i = 0; i < 18; i++) {
      const side = i % 2, x = side ? 808 + rand(i) * 170 : 21 + rand(i) * 171, y = 415 + rand(i + 8) * 200;
      line(c, [[x, y], [x + 2, y - 25 - rand(i) * 35]], '#4c9389', 3);
      ellipse(c, x + 2, y - 32 - rand(i) * 35, 9 + rand(i + 2) * 13, 5, '#89c4ad');
      if (i % 4 === 0) glow(c, x, y - 25, 45, 'rgba(75,205,183,.13)');
    }
    motes(c, t, 40, '#9ed5c2', 170, 620, 140);
  }
  function zombie(c, x, y, scale, t, kind, hit) {
    c.save(); c.translate(x, y); c.scale(scale, scale);
    const sway = Math.sin(t * 4 + kind) * 3;
    shadow(c, 0, 0, 26, 6);
    poly(c, [[-15, -40], [-2, -35], [-8 + sway, -3], [-22 + sway, -3]], '#263e40');
    poly(c, [[4, -36], [16, -40], [23 - sway, -3], [8 - sway, -3]], '#314746');
    poly(c, [[-19, -72], [13, -75], [23, -40], [5, -24], [-23, -40]], hit ? '#e3dcb4' : ['#6c8870', '#847a65', '#6d788c'][kind % 3]);
    ellipse(c, 0, -84, 17, 19, hit ? '#fbebc4' : '#a7af8e');
    poly(c, [[-15, -91], [-20, -109], [-5, -100], [8, -110], [18, -91]], '#4c655a');
    line(c, [[-11, -86], [-4, -84]], '#d9e69a', 3); line(c, [[4, -84], [11, -86]], '#d9e69a', 3);
    poly(c, [[-6, -75], [7, -75], [4, -68], [-4, -69]], '#324347');
    line(c, [[-17, -62], [-32, -46], [-39, -57 + sway]], '#718d74', 9);
    line(c, [[16, -62], [32, -48], [41, -63 - sway]], '#84947a', 9);
    c.restore();
  }
  function autoShots(c, t, targets, color) {
    for (let i = 0; i < 4 && targets.length; i++) {
      const target = targets[(Math.floor(t * 1.8) + i * 3) % targets.length];
      const phase = (t * 2.2 + i * .24) % 1, x = 510 + (target.x - 510) * phase, y = 340 + (target.y - 42 - 340) * phase;
      line(c, [[x, y], [x - (target.x - 510) * .055, y - (target.y - 382) * .055]], color, 2.5);
      ellipse(c, x, y, 2.3, 2.3, '#fff4cf');
    }
  }
  function rainCity(c, t, s) {
    const n = count(s), e = elapsed(s);
    sky(c, '#182e3a', '#263b43');
    poly(c, [[300, 0], [700, 0], [883, 620], [118, 620]], '#34474d');
    for (let i = 0; i < 10; i++) {
      const y = i * 79 - 28;
      poly(c, [[491, y], [509, y], [512, y + 32], [487, y + 32]], '#a1a58a');
      line(c, [[280 - i * 18, y], [234 - i * 18, y + 32]], '#718587', 3);
      line(c, [[723 + i * 18, y], [764 + i * 18, y + 32]], '#718587', 3);
    }
    for (const side of [0, 1]) for (let i = 0; i < 4; i++) {
      const x = side ? 800 + i % 2 * 26 : -20 - i % 2 * 24, y = i * 170 - 45;
      round(c, x, y, 215, 142, 4, side ? '#20333d' : '#263b43');
      poly(c, [[x, y], [x + 31, y - 27], [x + 235, y - 27], [x + 215, y]], '#43535a');
      for (let row = 0; row < 2; row++) for (let j = 0; j < 4; j++) round(c, x + 29 + j * 45, y + 23 + row * 48, 24, 30, 2, (i + j) % 4 ? '#172a35' : '#94a999');
      line(c, [[x + 45, y + 118], [x + 91, y + 100], [x + 120, y + 129]], '#53676b', 2);
    }
    for (let i = 0; i < 13; i++) {
      const x = 233 + rand(i + 44) * 520, y = rand(i + 67) * H;
      ellipse(c, x, y, 21 + rand(i) * 42, 6 + rand(i + 4) * 12, '#496774', -.16);
      line(c, [[x - 18, y + 1], [x + 19, y - 2]], 'rgba(146,182,187,.35)', 1);
    }
    // Wrecked tram and a leaning street light make a specific place, not an abstract arena.
    c.save(); c.translate(255, 449); c.rotate(-.28); shadow(c, 0, 19, 61, 22); round(c, -43, -82, 86, 145, 10, '#647477', '#899996'); round(c, -33, -62, 66, 47, 4, '#253e4a'); round(c, -31, 8, 62, 31, 4, '#3c5159'); line(c, [[-20, -58], [11, -32], [-8, -18]], '#9eaba4', 2); round(c, -30, 48, 12, 8, 2, '#b8a477'); round(c, 18, 48, 12, 8, 2, '#b8a477'); c.restore();
    line(c, [[769, 321], [745, 93], [704, 85]], '#74858a', 7); glow(c, 703, 99, 105, 'rgba(142,201,191,.18)'); round(c, 678, 85, 46, 8, 3, '#bdd7ca');
    const targets = [];
    for (let i = 0; i < 12; i++) {
      if (n > 0 && i < n % 4) continue;
      const a = i / 12 * TAU + .14, radius = 196 + Math.sin(t * .35 + i) * 12;
      const x = 500 + Math.cos(a) * radius * 1.15, y = 380 + Math.sin(a) * radius * .78;
      targets.push({ x, y }); zombie(c, x + (e < 1.4 ? Math.cos(a) * e * 21 : 0), y, .74 + (y / 620) * .18, t + i, i, e > .12 && e < .45);
    }
    hero(c, 502, 388, .65, t, { gun: true, cloak: '#527b85', scarf: '#cda76c' });
    autoShots(c, t, targets, '#a4dcd0');
    if (e < 1.8) {
      c.globalAlpha = (1 - e / 1.8) * .8; c.beginPath(); c.ellipse(500, 364, 35 + e * 170, 22 + e * 110, 0, 0, TAU); c.strokeStyle = '#a5e6d9'; c.lineWidth = 6 * (1 - e / 1.8); c.stroke(); c.globalAlpha = 1;
      impact(c, 500, 350, e, '#c6f1df', n);
    }
    for (let i = 0; i < Math.min(n, 6); i++) { const a = i * 1.9; ellipse(c, 500 + Math.cos(a) * 150, 388 + Math.sin(a) * 100, 20, 10, '#263a41'); }
    c.strokeStyle = 'rgba(173,203,211,.25)'; c.lineWidth = 1;
    for (let i = 0; i < 100; i++) { const x = (rand(i) * 1100 + t * 63) % 1100 - 50, y = (rand(i + 912) * 700 + t * 245) % 700 - 30; c.beginPath(); c.moveTo(x, y); c.lineTo(x - 8, y + 22); c.stroke(); }
    for (let i = 0; i < 8; i++) { const x = 330 + rand(i) * 340, y = 130 + rand(i + 18) * 420, radius = ((t + i * .3) % 2) * 11; c.beginPath(); c.ellipse(x, y, radius, radius * .25, 0, 0, TAU); c.strokeStyle = 'rgba(159,192,198,.23)'; c.stroke(); }
  }
  function drone(c, x, y, t, scale = 1) {
    c.save(); c.translate(x, y); c.scale(scale, scale); shadow(c, 0, 20, 18, 5);
    poly(c, [[-17, -3], [-8, -12], [8, -12], [17, -3], [9, 8], [-9, 8]], '#e0c48b', '#53675e', 2);
    ellipse(c, 0, -2, 6, 6, '#80c9b6'); ellipse(c, 0, -3, 2, 2, '#e8f1ce');
    for (const xx of [-22, 22]) { line(c, [[xx * .45, -4], [xx, -10]], '#54776b', 3); ellipse(c, xx, -13, 15, 3 + Math.sin(t * 35) * .5, 'rgba(204,220,174,.7)'); }
    c.restore();
  }
  function overgrown(c, t, s) {
    const n = count(s), e = elapsed(s);
    sky(c, '#8aa691', '#b9b894');
    for (let i = 0; i < 9; i++) tree(c, i * 132 - 24, 268, .9, t, '#64886b');
    poly(c, [[0, 238], [1000, 238], [1000, 620], [0, 620]], '#778d66');
    for (let i = 0; i < 20; i++) ellipse(c, rand(i) * W, 251 + rand(i + 70) * 400, 45 + rand(i + 2) * 85, 19, i % 3 ? '#7f956d' : '#8c9b72');
    round(c, 152, 185, 697, 53, 3, '#7b7962', '#adab87');
    for (let i = 0; i < 22; i++) round(c, 149 + i * 33, 172, 21, 21, 1, '#99977a');
    arch(c, 431, 152, 139, 89, '#b0aa84', '#46665a');
    for (const x of [156, 829]) { round(c, x - 32, 130, 64, 146, 3, '#8d8c70'); poly(c, [[x - 47, 133], [x, 91], [x + 47, 133]], '#52695c'); round(c, x - 10, 166, 20, 32, 8, '#354c43'); }
    ellipse(c, 504, 408, 304, 147, '#a8a684');
    for (let i = 0; i < 28; i++) {
      const a = i / 28 * TAU, x = 503 + Math.cos(a) * 285, y = 410 + Math.sin(a) * 132;
      round(c, x - 19, y - 8, 36, 18, 4, '#b9b495', '#868f73');
    }
    for (let j = 0; j < 6; j++) line(c, [[292 + j * 68, 302], [265 + j * 79, 512]], '#989f80', 1);
    const targets = [];
    for (let i = 0; i < 9; i++) {
      const a = i / 9 * TAU + .24, x = 500 + Math.cos(a) * (247 + Math.sin(t * .4 + i) * 7), y = 407 + Math.sin(a) * 137;
      targets.push({ x, y }); beast(c, x, y, .47, t + i, { face: x < 500 ? 1 : -1, color: ['#9baa6d', '#b19768', '#7da899'][i % 3], dark: '#536f4e', pale: '#d5d5a3', horns: i % 3 === 0, seed: i });
    }
    hero(c, 503, 405, .68, t, { gun: true, cloak: '#416c61', scarf: '#d99b57' });
    autoShots(c, t, targets, '#f4d78c');
    const drones = Math.min(4, n + 1);
    for (let i = 0; i < drones; i++) {
      const a = t * .6 + i * TAU / drones, x = 504 + Math.cos(a) * 90, y = 319 + Math.sin(a) * 36;
      drone(c, x, y, t, .8);
      if (e < 1.5) { c.globalAlpha = 1 - e / 1.5; line(c, [[x, y], [targets[i * 2 % targets.length].x, targets[i * 2 % targets.length].y - 30]], '#f8e6a1', 2); c.globalAlpha = 1; }
    }
    impact(c, 504, 329, e, '#ffedb6', n + 8);
    for (let i = 0; i < 29; i++) { const x = rand(i + 13) * W, y = 255 + rand(i + 94) * 360; if (x < 231 || x > 800 || y > 548) grass(c, x, y, 25 + rand(i) * 33, '#4f7651', t); }
    tree(c, 25, 646, 1.55, t, '#315c46'); tree(c, 997, 642, 1.42, t, '#376449');
    fern(c, 123, 620, 117, t, '#4a7953'); fern(c, 862, 620, 98, t, '#4d7a53');
    motes(c, t, 23, '#f6e1a0', 235, 600, 212);
    for (let i = 0; i < 5; i++) { const x = (160 + i * 167 + t * 14) % 1000, y = 150 + Math.sin(t * .8 + i) * 30; line(c, [[x - 7, y - Math.sin(t * 6 + i) * 4], [x, y], [x + 7, y - Math.sin(t * 6 + i) * 4]], '#55776c', 1.6); }
  }
  function drawScene(painters) {
    return function (c, t, state, v) {
      c.save(); c.globalAlpha = 1; c.lineCap = 'round'; c.lineJoin = 'round';
      try { painters[v === 1 ? 1 : 0](c, t, state); } finally { c.restore(); }
    };
  }
  const studies = [
    {
      id: 'menagerie', title: 'Зверинец', category: 'Бои и существа',
      variants: [
        { name: 'Стеклянный ковчег', kicker: 'Живой питомник', description: 'Генетика становится видимой: родители окружают гнездо, а новый зверь наследует окрас, крылья и рога. Вольер дышит светом, листвой и маленькими движениями.', features: ['Составные звери вместо карточек', 'Вылупление прямо в окружении', 'Видимые признаки поколения'], action: 'Вывести гибрида', result: 'Скорлупа раскрылась. В новом звере видны унаследованные признаки.', accent: '#a7d4ae', background: '#173b36' },
        { name: 'Кубок заповедника', kicker: 'Рисованный турнир', description: 'Два узнаваемых гибрида встречаются на песчаном ринге. Учебный бой читается по рывку, ответному движению и следам удара, а не только по журналу.', features: ['Контактный бой крупных зверей', 'Трибуны, пыль и флаги', 'Награды остаются на ринге'], action: 'Разыграть раунд', result: 'Гибрид перехватил инициативу. Рывок и попадание показаны в самой сцене.', accent: '#e5b977', background: '#4b4c59' }
      ],
      stats: (v, s) => v ? [['Раунд', String(count(s) + 1)], ['Медали', String(count(s))], ['Арена', 'Закатный круг']] : [['Поколение', String(count(s) + 3)], ['Наследование', count(s) % 2 ? 'Крылья и окрас' : 'Рога и окрас'], ['Биом', 'Тёплый ковчег']],
      draw: drawScene([hatchery, tournament])
    },
    {
      id: 'tower', title: 'Башня', category: 'Бои и существа',
      variants: [
        { name: 'Башня в разрезе', kicker: 'Вертикальное приключение', description: 'Этажи существуют одновременно: оружейная, библиотека и верхний свод. Текущая комната оживает, а пройденный путь остаётся частью видимого здания.', features: ['Разные комнаты в одном разрезе', 'Бой внутри текущего этажа', 'Переход после трёх ходов'], action: 'Сделать ход', result: 'Герой разыграл приём. После третьего хода путь продолжается этажом выше.', accent: '#bfd2bd', background: '#1a2c3d' },
        { name: 'Последний удар', kicker: 'Крупный театральный бой', description: 'Каменный страж занимает почти всю сцену. Свет витражей, тяжёлая поза, клинок и осыпающаяся броня подчёркивают вес каждого пошагового решения.', features: ['Крупные силуэты противников', 'Дуга удара и сколы брони', 'Готический зал с живым светом'], action: 'Ответить на выпад', result: 'Клинок нашёл трещину. На броне стража остался новый след.', accent: '#dac19a', background: '#22283c' }
      ],
      stats: (v, s) => v ? [['Ход', String(count(s) + 1)], ['Страж', 'Каменная корона'], ['Броня', count(s) ? 'С трещинами' : 'Целая']] : [['Этаж', String(Math.floor(count(s) / 3) % 3 + 1)], ['Ход', String(count(s) % 3 + 1) + ' / 3'], ['Путь', 'Вверх по башне']],
      draw: drawScene([towerCut, towerTheatre])
    },
    {
      id: 'one-cell-dungeon', title: 'Подземелье в одну клетку', category: 'Бои и существа',
      variants: [
        { name: 'Стол картографа', kicker: 'Осязаемая тактика 3 × 3', description: 'Девять тяжёлых плиток, миниатюры, ловушки и сундук на деревянном столе. Пространство остаётся строго 3 × 3: один шаг меняет тактическую позицию.', features: ['Девять объёмных каменных плит', 'Фигурки героя и стража', 'Следы маршрута и открытый сундук'], action: 'Шагнуть в соседнюю клетку', result: 'Герой сменил клетку. Маршрут остаётся на плитах, а найденный сундук раскрывается.', accent: '#d7bb83', background: '#5b4536' },
        { name: 'Свет под городом', kicker: 'Светящийся лабиринт 3 × 3', description: 'Та же компактная тактика становится путешествием над подземной бездной. Клетки раскрываются светом, призраки узнаваемы по силуэту, а ход оставляет световой след.', features: ['Чёткое поле 3 × 3 над бездной', 'Свет клеток ведёт по маршруту', 'Призрак, грибы и древние ворота'], action: 'Зажечь следующий шаг', result: 'Руна под ногами ожила. Пройденные клетки удерживают свет маршрута.', accent: '#9adcc1', background: '#102c3a' }
      ],
      stats: (v, s) => { const cell = ROUTE[count(s) % ROUTE.length]; return [['Поле', '3 × 3'], ['Позиция', (Math.floor(cell / 3) + 1) + ' : ' + (cell % 3 + 1)], ['Сундук', count(s) >= 4 ? 'Открыт' : 'Впереди']]; },
      draw: drawScene([dungeonTable, dungeonLuminous])
    },
    {
      id: 'wave-survival', title: 'Последний рубеж', category: 'Бои и существа',
      variants: [
        { name: 'Дождевой квартал', kicker: 'Ночной городской survival', description: 'Автоматическая стрельба происходит в узнаваемом разрушенном городе. Мокрый асфальт, остов трамвая и дождь дают масштаб, а импульс отбрасывает окружающую орду.', features: ['Город вместо пустой арены', 'Постоянная автоматическая стрельба', 'Импульс, дождь и следы боя'], action: 'Выбрать импульс', result: 'Усиление разошлось по улице. Герой продолжает стрелять автоматически.', accent: '#a2ded0', background: '#203641' },
        { name: 'Зелёный форпост', kicker: 'Солнечный survival', description: 'Заросшая крепость, тёплый камень и лесные существа вместо ночного неона. Выбранные дроны остаются рядом с героем и поддерживают автоматический бой.', features: ['Живой дневной форпост', 'Орбитальные боевые дроны', 'Трава, птицы и светлая палитра'], action: 'Добавить боевого дрона', result: 'Дрон занял орбиту. Поддержка продолжает работать без ручной стрельбы.', accent: '#ead18c', background: '#768b67' }
      ],
      stats: (v, s) => v ? [['Волна', String(Math.floor(count(s) / 3) + 1)], ['Дроны', String(Math.min(4, count(s) + 1))], ['Огонь', 'Авто']] : [['Волна', String(Math.floor(count(s) / 3) + 1)], ['Импульс', count(s) ? 'Усилен' : 'Готов к выбору'], ['Огонь', 'Авто']],
      draw: drawScene([rainCity, overgrown])
    }
  ];
  studies.forEach(study => window.AssiVisuals.register(study));
})();


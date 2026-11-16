// The code checks of «Игротека»: node scripts/games/lint-shared.js
//
//  1. node --check of every .js of the hub, the fixture and these scripts; inline <script>s of
//     the hub's pages compiled as classic scripts.
//  2. The shared subset (Core.lint): strict ES5 for the engine's shared files (big, fmt, rng, core —
//     node, the WebView and Rhino) and for every engine-side (Rhino) file; the subset rules for
//     the sims (no class, async/await, f(...args), for (let/const …)).
//  3. The owner's UI rules over the hub's files: no emoji, no shadows, one CSS declaration a line;
//     no network addresses (Core.offlineScan).
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { execFileSync } = require('child_process');
const { HUB, FIXTURE } = require('./harness');
const Core = require(path.join(HUB, 'engine', 'core.js'));

const problems = [];
const rel = (f) => path.relative(path.resolve(HUB, '..', '..', '..', '..', '..', '..', '..'), f).replace(/\\/g, '/');

function walk(dir, out) {
  for (const d of fs.readdirSync(dir, { withFileTypes: true })) {
    const f = path.join(dir, d.name);
    if (d.isDirectory()) walk(f, out);
    else out.push(f);
  }
  return out;
}

const hubFiles = walk(HUB, []);
const fixtureFiles = walk(FIXTURE, []);
const scriptFiles = fs.readdirSync(__dirname).filter((n) => n.endsWith('.js')).map((n) => path.join(__dirname, n));

// 1. Syntax.
let checked = 0;
for (const f of hubFiles.concat(fixtureFiles, scriptFiles).filter((x) => x.endsWith('.js'))) {
  try {
    execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' });
    checked++;
  } catch (error) {
    problems.push(rel(f) + ': node --check: ' + String(error.stderr || error.message).split('\n').slice(0, 5).join(' '));
  }
}
let inline = 0;
for (const f of hubFiles.concat(fixtureFiles).filter((x) => x.endsWith('.html'))) {
  const text = fs.readFileSync(f, 'utf8');
  const re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g;
  let m;
  while ((m = re.exec(text))) {
    try {
      new vm.Script(m[1], { filename: f });
      inline++;
    } catch (error) {
      problems.push(rel(f) + ': встроенный скрипт: ' + error.message);
    }
  }
}

// 2. The shared subset.
const SHARED = ['big.js', 'fmt.js', 'rng.js', 'core.js', 'series.js'].map((n) => path.join(HUB, 'engine', n));
const RHINO = [path.join(HUB, 'main.js'), path.join(HUB, 'stock.js'), path.join(HUB, 'artist.js'), path.join(HUB, '..', '..', 'lib', 'art.js'), path.join(HUB, 'checks.js'), path.join(HUB, 'engine', 'checks-kit.js'),
  path.join(HUB, 'engine', 'template', 'checks.js'), path.join(FIXTURE, 'checks.js')]
  .concat(fs.readdirSync(path.join(HUB, 'links')).filter((n) => n.endsWith('.js')).map((n) => path.join(HUB, 'links', n)));
const SIMS = [path.join(HUB, 'engine', 'template', 'sim.js'), path.join(FIXTURE, 'sim.js')];
function lint(files, strict) {
  for (const f of files) {
    for (const p of Core.lint(fs.readFileSync(f, 'utf8'), strict)) problems.push(rel(f) + ':' + p.line + ': ' + p.rule);
  }
}
lint(SHARED, true);
lint(RHINO, true);
lint(SIMS, false);

// 3. The owner's rules.
const EMOJI = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0F}]/u;
const texts = hubFiles.concat(fixtureFiles).filter((f) => /\.(js|html|css|json|md|svg)$/.test(f));
for (const f of texts) {
  const lines = fs.readFileSync(f, 'utf8').split('\n');
  lines.forEach((line, i) => {
    if (EMOJI.test(line)) problems.push(rel(f) + ':' + (i + 1) + ': эмодзи');
    if (/\b(box-shadow|text-shadow|drop-shadow)\b/.test(line) && !/\bnone\b/.test(line) && /\.(css|html)$/.test(f)) problems.push(rel(f) + ':' + (i + 1) + ': тень');
  });
}
function cssBlocks(f) {
  const text = fs.readFileSync(f, 'utf8');
  if (f.endsWith('.css')) return [text];
  const out = [];
  const re = /<style[^>]*>([\s\S]*?)<\/style>/g;
  let m;
  while ((m = re.exec(text))) out.push(m[1]);
  return out;
}
for (const f of texts.filter((x) => /\.(css|html)$/.test(x))) {
  for (const css of cssBlocks(f)) {
    css.split('\n').forEach((line, i) => {
      const body = line.replace(/\/\*.*?\*\//g, '');
      // Two declarations on one line: "a: b; c: d" or a rule with its body on the same line.
      if (/;\s*[a-z-]+\s*:/.test(body) || /\{\s*[a-z-]+\s*:[^}]*;?\s*\}/.test(body)) problems.push(rel(f) + ': CSS: несколько объявлений в строке: ' + line.trim().slice(0, 80));
    });
  }
}
const hits = Core.offlineScan(texts.filter((f) => /\.(html?|js|css)$/.test(f)).map((f) => ({ path: rel(f), text: fs.readFileSync(f, 'utf8') })));
for (const h of hits) problems.push(h.path + ':' + h.line + ': адрес в сети ' + h.url);

console.log('node --check: ' + checked + ' файлов, встроенных скриптов: ' + inline);
console.log('подмножество: общих ' + SHARED.length + ' (строго), движка ' + RHINO.length + ' (строго), sim ' + SIMS.length);
if (problems.length) {
  console.log('Нарушения (' + problems.length + '):\n  ' + problems.join('\n  '));
  process.exitCode = 1;
} else {
  console.log('lint: нарушений нет');
}

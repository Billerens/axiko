// All the node checks of «Игротека»: node scripts/games/run-all.js
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const files = ['lint-shared.js'].concat(fs.readdirSync(__dirname).filter((n) => n.endsWith('.test.js')).sort());
let failed = 0;
for (const name of files) {
  const r = spawnSync(process.execPath, [path.join(__dirname, name)], { encoding: 'utf8' });
  const out = (r.stdout || '') + (r.stderr || '');
  const text = out.replace(/\s+$/, '').replace(/^\n+/, '');
  process.stdout.write(text ? text + '\n' : name + ': нет вывода\n');
  if (r.status !== 0) failed++;
}
console.log(failed ? '\nНе прошло файлов: ' + failed + ' из ' + files.length : '\nВсё прошло: ' + files.length + ' файлов');
process.exitCode = failed ? 1 : 0;

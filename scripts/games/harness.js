// The node side of the tests of «Игротека»: a tiny test runner and a workspace that runs the
// hub's engine code as the phone does. The engine's modules are Rhino modules — a function of
// (exports, module, require, __filename, __dirname) with the shell's globals around it — so the
// loader here wraps them the same way and gives them job/on/emit/native/spawn/sleep and lib/assi
// over the file system and node:sqlite. No java: the code takes its plain paths.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const FACTORY = path.join(ROOT, 'app', 'src', 'main', 'assets', 'factory');
const HUB = path.join(FACTORY, 'modules', 'games');
const FIXTURE = path.join(__dirname, 'fixture');

// ---- the runner ----------------------------------------------------------------------------------

const tests = [];

function test(name, fn) { tests.push({ name, fn }); }

async function done(title) {
  let passed = 0;
  const failed = [];
  for (const t of tests) {
    try {
      await t.fn();
      passed++;
    } catch (error) {
      failed.push(t.name);
      console.log('  НЕ ПРОШЛО: ' + t.name + '\n    ' + String(error && error.stack ? error.stack : error).split('\n').slice(0, 6).join('\n    '));
    }
  }
  console.log(title + ': прошло ' + passed + ' из ' + tests.length + (failed.length ? ', не прошло ' + failed.length : ''));
  process.exitCode = failed.length ? 1 : 0;
  return { passed, failed };
}

// ---- the shared files as the screen and node see them ---------------------------------------------

function engine(name) { return require(path.join(HUB, 'engine', name)); }

// ---- a workspace ------------------------------------------------------------------------------------

/**
 * A temporary workspace: modules/games of the factory, the fixture as modules/games/fixture
 * (unless fixture: false), lib/base.js. Returns {root, require, files, shell, base(), close()}.
 * shell: {jobs, handlers, emitted, notified, vibrated, spawned, features, natives, ai}.
 */
function workspace(o) {
  o = o || {};
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'games-'));
  fs.cpSync(HUB, path.join(root, 'modules', 'games'), { recursive: true });
  if (o.fixture !== false) fs.cpSync(FIXTURE, path.join(root, 'modules', 'games', 'fixture'), { recursive: true });
  fs.mkdirSync(path.join(root, 'lib'), { recursive: true });
  fs.copyFileSync(path.join(FACTORY, 'lib', 'base.js'), path.join(root, 'lib', 'base.js'));
  fs.copyFileSync(path.join(FACTORY, 'lib', 'check.js'), path.join(root, 'lib', 'check.js'));

  const shell = {
    jobs: {}, handlers: {}, emitted: [], notified: [], vibrated: [], spawned: [], shared: [],
    features: o.features === undefined ? null : o.features,
    natives: {}, ai: null, permission: true, clock: null
  };

  const abs = (p) => path.join(root, String(p));
  const files = {
    path: (p) => abs(p),
    read: (p) => fs.readFileSync(abs(p), 'utf8'),
    write: (p, text) => {
      fs.mkdirSync(path.dirname(abs(p)), { recursive: true });
      fs.writeFileSync(abs(p), String(text));
    },
    exists: (p) => fs.existsSync(abs(p)),
    list: (p) => fs.readdirSync(abs(p), { withFileTypes: true }).map((d) => d.isDirectory() ? d.name + '/' : d.name).sort(),
    remove: (p) => {
      fs.rmSync(abs(p), { recursive: true, force: true });
      return true;
    },
    readJson: (p, fallback) => fs.existsSync(abs(p)) ? JSON.parse(fs.readFileSync(abs(p), 'utf8')) : fallback,
    writeJson: (p, value) => files.write(p, JSON.stringify(value, null, 1))
  };

  const { DatabaseSync } = requireSqlite();
  const databases = {};
  function db(name) {
    if (databases[name]) return databases[name];
    fs.mkdirSync(path.join(root, '.db'), { recursive: true });
    const sql = new DatabaseSync(path.join(root, '.db', name + '.db'));
    const values = (args) => (args || []).map((v) => v === undefined ? null : typeof v === 'boolean' ? (v ? 1 : 0) : v);
    // The phone binds query arguments as strings; so does this.
    const strings = (args) => (args || []).map((v) => v === null || v === undefined ? null : String(v));
    let last = 0;
    const handle = {
      exec: (statement, args) => {
        if (args && args.length) last = Number(sql.prepare(statement).run(...values(args)).lastInsertRowid);
        else sql.exec(statement);
      },
      query: (statement, args) => sql.prepare(statement).all(...strings(args)).map((row) => Object.assign({}, row)),
      lastId: () => last,
      close: () => sql.close()
    };
    databases[name] = handle;
    return handle;
  }

  const assi = {
    files, db,
    notify: (options) => {
      shell.notified.push(options);
      return shell.notified.length;
    },
    vibrate: (x) => { shell.vibrated.push(x); },
    permission: () => shell.permission,
    share: (text, title) => { shell.shared.push({ text, title }); }
  };
  const usage = {
    access: () => !!o.usage,
    daily: (since, until) => (o.usage || []).filter((u) => (!since || u.day >= since) && (!until || u.day < until))
  };
  const ai = {
    ask: (request) => {
      if (!shell.ai) throw new Error('нет модели в тестах');
      return shell.ai(request);
    }
  };
  const builtin = { 'lib/assi': assi, 'lib/usage': usage, 'lib/ai': ai };

  const globals = {
    job: (key, spec, fn) => { shell.jobs[key] = { spec, fn }; },
    unjob: (key) => { delete shell.jobs[key]; },
    on: (name, fn, key) => {
      shell.handlers[name] = shell.handlers[name] || {};
      shell.handlers[name][key || String(Object.keys(shell.handlers[name]).length)] = fn;
    },
    emit: (name, value) => { shell.emitted.push({ name, value }); },
    native: (op, ...args) => {
      if (shell.natives[op]) return shell.natives[op](...args);
      if (op === 'shell.features') {
        if (shell.features === null) throw new Error('Нет такой операции: shell.features');
        return shell.features;
      }
      if (op === 'nav.changed') return true;
      throw new Error('Нет такой операции: ' + op);
    },
    spawn: (fn) => { shell.spawned.push(fn); },
    sleep: () => {},
    ui: (fn) => fn(),
    extend: () => { throw new Error('extend: нет Java'); },
    host: { activity: null, mtime: (file) => fs.existsSync(file) ? fs.statSync(file).mtimeMs : 0 },
    context: undefined
  };

  const cache = {};
  function resolve(base, p) {
    p = String(p);
    let start;
    if (path.isAbsolute(p)) start = p;
    else if (p.startsWith('./') || p.startsWith('../')) start = path.resolve(base, p);
    else start = path.join(root, p);
    for (const suffix of ['', '.js', '.json', '/index.js', '/main.js']) {
      const f = start + suffix;
      if (fs.existsSync(f) && fs.statSync(f).isFile()) return f;
    }
    throw new Error('Модуль не найден: ' + p);
  }
  function load(file) {
    if (cache[file]) return cache[file].exports;
    if (/\.json$/.test(file)) {
      cache[file] = { exports: JSON.parse(fs.readFileSync(file, 'utf8')) };
      return cache[file].exports;
    }
    const module = { exports: {} };
    cache[file] = module;
    const names = Object.keys(globals);
    // eslint-disable-next-line no-new-func
    const fn = new Function('exports', 'module', 'require', '__filename', '__dirname', ...names, fs.readFileSync(file, 'utf8') + '\n//# sourceURL=' + file);
    const dir = path.dirname(file);
    const req = (p) => builtin[p] || load(resolve(dir, p));
    try {
      fn.call(module.exports, module.exports, module, req, file, dir, ...names.map((n) => globals[n]));
    } catch (error) {
      delete cache[file];
      throw error;
    }
    return module.exports;
  }

  return {
    root, shell, files, assi, db,
    require: (p) => builtin[p] || load(resolve(root, p)),
    /** Runs what was spawned (the shell runs it on a thread of its own). */
    flush: () => {
      const list = shell.spawned.splice(0);
      list.forEach((fn) => fn());
      return list.length;
    },
    close: () => {
      Object.keys(databases).forEach((k) => {
        try {
          databases[k].close();
        } catch (ignored) { /* closed */ }
      });
      fs.rmSync(root, { recursive: true, force: true });
    }
  };
}

/**
 * The engine and a game as one workspace sees them (the same Big everywhere):
 * {Core, Big, Rng, Fmt, sim, game, cfg} for modules/games/<id> (the fixture by default).
 */
function gameOf(env, id) {
  id = id || 'fixture';
  const Core = env.require('modules/games/engine/core.js');
  const game = JSON.parse(env.files.read('modules/games/' + id + '/game.json'));
  game.id = id;
  ['currencies', 'prestige', 'achievements', 'stock', 'notify'].forEach((k) => { game[k] = game[k] || []; });
  game.links = game.links || {};
  game.saveVersion = game.saveVersion || 1;
  const own = env.files.exists('modules/games/' + id + '/config.json') ? JSON.parse(env.files.read('modules/games/' + id + '/config.json')) : {};
  return {
    Core, game,
    Big: env.require('modules/games/engine/big.js'),
    Rng: env.require('modules/games/engine/rng.js'),
    Fmt: env.require('modules/games/engine/fmt.js'),
    sim: env.require('modules/games/' + id + '/sim.js'),
    cfg: Core.config(Core.schema(own, game), {}, null)
  };
}

function requireSqlite() {
  // node:sqlite says it is experimental on every start; once is enough.
  const emit = process.emitWarning;
  process.emitWarning = (w, ...rest) => {
    if (String(w && w.message ? w.message : w).indexOf('SQLite') >= 0) return;
    emit.call(process, w, ...rest);
  };
  try {
    return require('node:sqlite');
  } finally {
    process.emitWarning = emit;
  }
}

module.exports = { test, done, engine, workspace, gameOf, ROOT, FACTORY, HUB, FIXTURE };

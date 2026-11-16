// The engine's fixed part: what every script can rely on whatever happens to the workspace.
// Globals: host, context (set by the shell), console, require, ui, spawn, sleep, js, emit, native.

var global = this;
var android = Packages.android;
var androidx = Packages.androidx;
var kotlin = Packages.kotlin;
var okhttp3 = Packages.okhttp3;
var okio = Packages.okio;

/** Is this a Java object (as opposed to a script value)? */
function isJava(value) {
  return value !== null && typeof value === 'object' && value instanceof java.lang.Object;
}

/** A Java collection, map, array or org.json value as a plain script value, deeply. */
function js(value) {
  if (value === null || value === undefined) return value;
  if (!isJava(value)) return value;
  if (value instanceof java.lang.CharSequence) return String(value);
  if (value instanceof java.lang.Number) return Number(value);
  if (value instanceof java.lang.Boolean) return Boolean(value);
  if (value === org.json.JSONObject.NULL) return null;
  var out, it, key;
  if (value instanceof org.json.JSONObject) {
    out = {};
    it = value.keys();
    while (it.hasNext()) { key = String(it.next()); out[key] = js(value.get(key)); }
    return out;
  }
  if (value instanceof org.json.JSONArray) {
    out = [];
    for (var i = 0; i < value.length(); i++) out.push(js(value.get(i)));
    return out;
  }
  if (value instanceof java.util.Map) {
    out = {};
    it = value.entrySet().iterator();
    while (it.hasNext()) { var entry = it.next(); out[String(entry.getKey())] = js(entry.getValue()); }
    return out;
  }
  if (value instanceof java.lang.Iterable) {
    out = [];
    it = value.iterator();
    while (it.hasNext()) out.push(js(it.next()));
    return out;
  }
  if (value.getClass().isArray()) {
    out = [];
    var length = java.lang.reflect.Array.getLength(value);
    for (var j = 0; j < length; j++) out.push(js(java.lang.reflect.Array.get(value, j)));
    return out;
  }
  return value;
}

function __json(value, indent) {
  return JSON.stringify(value, function (key, item) {
    if (isJava(item)) {
      var plain = js(item);
      return isJava(plain) ? String(plain) : plain;
    }
    return item;
  }, indent);
}

/** A value the way a console prints it. */
function __show(value) {
  if (value === undefined) return 'undefined';
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  if (typeof value === 'function') return '[function ' + (value.name || '') + ']';
  if (value instanceof Error) return value.name + ': ' + value.message + (value.stack ? '\n' + value.stack : '');
  if (isJava(value)) {
    var plain = js(value);
    if (isJava(plain)) return String(plain);
    value = plain;
  }
  try {
    var text = __json(value, 1);
    return text === undefined ? String(value) : text;
  } catch (error) {
    return String(value);
  }
}

var console = (function () {
  function line(args) {
    return Array.prototype.map.call(args, __show).join(' ');
  }
  return {
    log: function () { host.log('log', line(arguments)); },
    info: function () { host.log('info', line(arguments)); },
    debug: function () { host.log('debug', line(arguments)); },
    warn: function () { host.log('warn', line(arguments)); },
    error: function () { host.log('error', line(arguments)); }
  };
})();

// ---- modules: require() with reload when the file changes --------------------------------

var __modules = {};

// Screens call the engine from several threads at once. A module is loaded by one of them while
// the others wait: without that, a second caller would get the module half-made — empty.
var __loading = new java.util.concurrent.locks.ReentrantLock();

function __makeRequire(base) {
  return function require(path) {
    var file = String(host.resolveModule(base, path));
    var mtime = host.mtime(file);
    var cached = __modules[file];
    if (cached && cached.ready && cached.mtime === mtime) return cached.module.exports;
    __loading.lock();
    try {
      cached = __modules[file];
      // Ready by now — or being loaded by this very thread: two modules that require each other.
      if (cached && cached.mtime === mtime) return cached.module.exports;
      if (/\.json$/.test(file)) {
        var data = JSON.parse(String(host.readText(file)));
        __modules[file] = { mtime: mtime, ready: true, module: { exports: data } };
        return data;
      }
      var module = { exports: {}, id: file };
      var entry = { mtime: mtime, ready: false, module: module };
      __modules[file] = entry;
      try {
        var dir = String(host.dirname(file));
        var body = host.compileModule(file);
        body.call(module.exports, module.exports, module, __makeRequire(dir), file, dir);
        entry.ready = true;
      } catch (error) {
        delete __modules[file];
        throw error;
      }
      return module.exports;
    } finally {
      __loading.unlock();
    }
  };
}

var require = __makeRequire(String(host.workspace));

// ---- threads -----------------------------------------------------------------------------

/** Runs fn on the main thread and returns its value: views, toasts and dialogs need it. */
function ui(fn) {
  return host.runOnUi(new java.util.concurrent.Callable({ call: function () { return fn(); } }));
}

/** Runs fn on a background script thread without waiting. */
function spawn(fn) {
  host.spawn(new java.lang.Runnable({ run: function () { fn(); } }));
}

function sleep(ms) {
  java.lang.Thread.sleep(ms);
}

/**
 * An object of a Java class with methods overridden by script functions:
 * extend(android.content.BroadcastReceiver, { onReceive: function (ctx, intent) { ... } }).
 * Further arguments go to the class's constructor. Interfaces do not need this.
 */
function extend(type, impl) {
  return host.extend(type, impl, Array.prototype.slice.call(arguments, 2));
}

/** Sends an event to every open page: assi.on(name, data => ...). */
function emit(name, data) {
  host.emit(name, __json(data === undefined ? null : data));
}

// ---- phone events: on('notification', fn), on('screen.app', fn) ------------------------------

var __listeners = {};

/**
 * Calls fn whenever the phone event happens. With a key, a later subscription under the same
 * key replaces this one (so a module that is reloaded does not answer twice).
 * Returns a function that unsubscribes.
 */
function on(name, fn, key) {
  var list = __listeners[name] = __listeners[name] || [];
  if (key !== undefined) {
    for (var i = list.length - 1; i >= 0; i--) if (list[i].key === key) list.splice(i, 1);
  }
  var entry = { fn: fn, key: key };
  list.push(entry);
  host.listening(name);
  return function () {
    var at = list.indexOf(entry);
    if (at >= 0) list.splice(at, 1);
  };
}

/**
 * Stops a subscription: off(name, key) takes away what on(name, fn, key) put — a module
 * listens to something only for a while (Focus watches other apps during a session).
 * A function in place of the key takes away that function.
 */
function off(name, key) {
  var list = __listeners[name] || [];
  for (var i = list.length - 1; i >= 0; i--) {
    if (typeof key === 'function' ? list[i].fn === key : list[i].key === key) list.splice(i, 1);
  }
}

function __fire(name, json) {
  var data = JSON.parse(json);
  (__listeners[name] || []).slice().forEach(function (entry) {
    try { entry.fn(data); } catch (error) { console.error('on ' + name + ': ' + error); }
  });
}

/** A shell operation (agents, models, snapshots, settings, navigation). */
function native(method) {
  var args = Array.prototype.slice.call(arguments, 1);
  var result = host.native(method, JSON.stringify(args));
  return result === null ? undefined : JSON.parse(String(result));
}

// ---- scheduled work --------------------------------------------------------------------------

var __jobs = {};

/**
 * Runs fn on a schedule, whether or not the app is open:
 *   job('mail-digest', { every: 60 }, fn)     every 60 minutes
 *   job('morning', { at: '08:30' }, fn)       daily at that time
 *   job('remind', { once: Date.now() + 6e5 }, fn)   once at that moment, then the job is over
 * Register jobs in lib/boot.js (or in a module's start()), so they are known after a restart.
 */
function job(key, spec, fn) {
  __jobs[key] = fn;
  native('job.set', key, spec);
}

function unjob(key) {
  delete __jobs[key];
  native('job.remove', key);
}

on('job', function (event) {
  var fn = __jobs[event.key];
  // A one-off job is over before its function runs: the function may well set the next one.
  if (event.once) delete __jobs[event.key];
  if (fn) fn();
}, '__jobs');

// ---- entry points the shell calls ----------------------------------------------------------

function __rpc(path, fn, argsJson) {
  var module = require(path);
  var target = module[fn];
  if (typeof target !== 'function') throw new Error('В ' + path + ' нет функции ' + fn);
  var result = target.apply(module, JSON.parse(argsJson));
  return result === undefined ? null : __json(result);
}

function __evalJson(code) {
  var result = (0, eval)(code);
  return result === undefined ? null : __json(result);
}

/** The agents' own tools: every tools/*.js exporting { name, description, parameters, run }. */
function __toolFiles() {
  var found = {};
  var names = host.list('tools');
  for (var i = 0; i < names.length; i++) {
    var name = String(names[i]);
    if (!/\.js$/.test(name)) continue;
    try {
      var tool = require('tools/' + name);
      if (tool && tool.name && typeof tool.run === 'function') found[tool.name] = tool;
    } catch (error) {
      console.error('tools/' + name + ': ' + error);
    }
  }
  return found;
}

function __tools() {
  var found = __toolFiles();
  return JSON.stringify(Object.keys(found).map(function (name) {
    return { name: name, description: found[name].description || '', parameters: found[name].parameters };
  }));
}

function __toolRun(name, argsJson) {
  var tool = __toolFiles()[name];
  if (!tool) throw new Error('Нет инструмента ' + name);
  var result = tool.run(JSON.parse(argsJson));
  return result === undefined ? 'готово' : __show(result);
}

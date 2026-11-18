// The standard library of the engine side: the usual things done once, so modules stay short.
// Usage in a module: var axiko = require('lib/axiko');
// Everything here is ordinary code over Android classes — read it, extend it, replace it.

var TimeUnit = java.util.concurrent.TimeUnit;

// ---- files (paths are workspace-relative; `data/...` is module data, never rolled back) ----

exports.files = {
  path: function (path) { return String(host.path(path)); },
  read: function (path) { return String(host.readText(path)); },
  write: function (path, text) { host.writeText(path, String(text)); },
  exists: function (path) { return host.exists(path); },
  list: function (path) { return js(host.list(path)); },
  remove: function (path) { return host.remove(path); },
  readJson: function (path, fallback) {
    return host.exists(path) ? JSON.parse(String(host.readText(path))) : fallback;
  },
  writeJson: function (path, value) { host.writeText(path, JSON.stringify(value, null, 1)); }
};

/**
 * A small JSON store kept in data/<name>.json:
 * var notes = axiko.store('notes'); notes.get('list', []); notes.set('list', [...]);
 */
exports.store = function (name) {
  var path = 'data/' + name + '.json';
  var load = function () { return exports.files.readJson(path, {}); };
  return {
    get: function (key, fallback) { var all = load(); return key in all ? all[key] : fallback; },
    set: function (key, value) { var all = load(); all[key] = value; exports.files.writeJson(path, all); return value; },
    remove: function (key) { var all = load(); delete all[key]; exports.files.writeJson(path, all); },
    all: load
  };
};

// ---- SQLite: var db = axiko.db('notes'); db.exec('create table ...'); db.query('select ...', [1]) ----

var databases = {};

exports.db = function (name) {
  if (databases[name]) return databases[name];
  var file = new java.io.File(String(host.dataDir), name + '.db');
  file.getParentFile().mkdirs();
  var sql = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file.getPath(), null);
  var strings = function (args) {
    var out = java.lang.reflect.Array.newInstance(java.lang.String, (args || []).length);
    (args || []).forEach(function (value, i) { out[i] = value === null || value === undefined ? null : String(value); });
    return out;
  };
  var objects = function (args) {
    var out = java.lang.reflect.Array.newInstance(java.lang.Object, (args || []).length);
    (args || []).forEach(function (value, i) {
      out[i] = value === undefined ? null : (typeof value === 'boolean' ? (value ? 1 : 0) : value);
    });
    return out;
  };
  var handle = {
    /** Runs a statement that returns nothing; `?` placeholders take values from args. */
    exec: function (statement, args) { sql.execSQL(statement, objects(args)); },
    /** Runs a select; returns an array of row objects. */
    query: function (statement, args) {
      var cursor = sql.rawQuery(statement, strings(args));
      var rows = [];
      try {
        var count = cursor.getColumnCount();
        while (cursor.moveToNext()) {
          var row = {};
          for (var i = 0; i < count; i++) {
            var type = cursor.getType(i);
            row[String(cursor.getColumnName(i))] =
              type === 0 ? null : type === 1 ? Number(cursor.getLong(i)) : type === 2 ? cursor.getDouble(i)
                : type === 3 ? String(cursor.getString(i)) : cursor.getBlob(i);
          }
          rows.push(row);
        }
      } finally {
        cursor.close();
      }
      return rows;
    },
    /** The id of the row the last insert added. */
    lastId: function () { return handle.query('select last_insert_rowid() as id')[0].id; },
    raw: sql
  };
  databases[name] = handle;
  return handle;
};

// ---- HTTP: axiko.http.get(url).json(), axiko.http.request({ url, method, headers, json, body }) ----

var client = null;

function httpClient() {
  if (!client) {
    client = new okhttp3.OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
      .build();
  }
  return client;
}

/** A request that names a secret as {{secret:name}} is sent by the shell, which alone puts the value in. */
function namesSecret(options) {
  try {
    return /\{\{secret:[A-Za-z0-9_.-]+\}\}/.test(JSON.stringify([options.url, options.headers || null,
      options.json === undefined ? null : options.json, options.body === undefined ? null : options.body]));
  } catch (error) { return false; }
}

exports.http = {
  request: function (options) {
    if (typeof options === 'string') options = { url: options };
    if (namesSecret(options)) {
      var sent = { url: options.url };
      ['method', 'headers', 'json', 'body'].forEach(function (name) { if (options[name] !== undefined) sent[name] = options[name]; });
      var got = native('http.secret', sent);
      return { status: got.status, ok: got.ok, headers: got.headers, text: got.text, json: function () { return JSON.parse(got.text); } };
    }
    var headers = options.headers || {};
    var builder = new okhttp3.Request.Builder().url(options.url);
    Object.keys(headers).forEach(function (name) { builder.header(name, String(headers[name])); });
    var body = null;
    if (options.json !== undefined) {
      body = okhttp3.RequestBody.create(okhttp3.MediaType.parse('application/json; charset=utf-8'), JSON.stringify(options.json));
    } else if (options.body !== undefined && options.body !== null) {
      var type = headers['Content-Type'] || headers['content-type'] || 'text/plain; charset=utf-8';
      body = okhttp3.RequestBody.create(okhttp3.MediaType.parse(type), String(options.body));
    }
    var method = String(options.method || (body ? 'POST' : 'GET')).toUpperCase();
    if (!body && method !== 'GET' && method !== 'HEAD') {
      body = okhttp3.RequestBody.create(null, '');
    }
    builder.method(method, body);
    var response = httpClient().newCall(builder.build()).execute();
    try {
      var text = String(response.body().string());
      var responseHeaders = {};
      var names = response.headers().names().iterator();
      while (names.hasNext()) { var name = String(names.next()); responseHeaders[name.toLowerCase()] = String(response.header(name)); }
      return {
        status: response.code(),
        ok: response.isSuccessful(),
        headers: responseHeaders,
        text: text,
        json: function () { return JSON.parse(text); }
      };
    } finally {
      response.close();
    }
  },
  get: function (url, headers) { return exports.http.request({ url: url, headers: headers }); },
  post: function (url, json, headers) { return exports.http.request({ url: url, method: 'POST', json: json, headers: headers }); },
  /** Saves the response body to a file (workspace-relative or data/...); returns the file's path. */
  download: function (url, path, headers) {
    var builder = new okhttp3.Request.Builder().url(url);
    Object.keys(headers || {}).forEach(function (name) { builder.header(name, String(headers[name])); });
    var response = httpClient().newCall(builder.build()).execute();
    try {
      if (!response.isSuccessful()) throw new Error('HTTP ' + response.code() + ' для ' + url);
      var file = new java.io.File(String(host.path(path)));
      file.getParentFile().mkdirs();
      var sink = okio.Okio.buffer(okio.Okio.sink(file));
      sink.writeAll(response.body().source());
      sink.close();
      return String(file.getPath());
    } finally {
      response.close();
    }
  }
};

// ---- the phone ---------------------------------------------------------------------------

exports.toast = function (text) {
  ui(function () { android.widget.Toast.makeText(context, String(text), android.widget.Toast.LENGTH_SHORT).show(); });
};

/**
 * Asks for runtime permissions and waits for the answer; true when all are granted.
 * Names may be short: axiko.permission(['READ_CALENDAR', 'RECORD_AUDIO']).
 */
exports.permission = function (names) {
  var wanted = [].concat(names).map(function (name) {
    return name.indexOf('.') < 0 ? 'android.permission.' + name : name;
  });
  var granted = function (name) { return context.checkSelfPermission(name) === 0; };
  var missing = wanted.filter(function (name) { return !granted(name); });
  if (!missing.length) return true;
  var activity = host.activity;
  if (!activity) return false;
  var list = java.lang.reflect.Array.newInstance(java.lang.String, missing.length);
  missing.forEach(function (name, i) { list[i] = name; });
  ui(function () { activity.requestPermissions(list, 77); });
  // The system dialog takes the window focus; the answer is in once the focus is back.
  for (var i = 0; i < 480; i++) {
    sleep(250);
    if (missing.every(granted)) return true;
    if (i > 6 && activity.hasWindowFocus()) break;
  }
  return missing.every(granted);
};

/**
 * A notification; returns its id (a string). `open` is the screen to show on tap:
 *   axiko.notify({ title, text, open: 'modules/x/ui/index.html' })
 * More: `id` (the same id replaces the notification), `actions: [{ title, call, fn, args, reply }]`
 * (up to three buttons; a press runs require(call)[fn](args, replyText) without opening the app),
 * `ongoing`, `when` (ms) with `chronometer` or `countdown`, `silent`, `progress: { max, value }`,
 * `channel: 'quiet' | 'normal' | 'alarm'` (alarm sounds through "do not disturb" given access).
 */
exports.notify = function (options) {
  if (typeof options === 'string') options = { text: options };
  return native('notify', options);
};
exports.notify.cancel = function (id) { return native('notify.cancel', String(id)); };

exports.clipboard = {
  get: function () {
    return ui(function () {
      var clip = context.getSystemService('clipboard').getPrimaryClip();
      return clip && clip.getItemCount() > 0 ? String(clip.getItemAt(0).coerceToText(context)) : '';
    });
  },
  set: function (text) {
    ui(function () {
      context.getSystemService('clipboard').setPrimaryClip(android.content.ClipData.newPlainText('Axiko', String(text)));
    });
  }
};

/** Opens a link, a file or another app's screen in whatever handles it. */
exports.openUrl = function (url) {
  var intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(String(url)));
  intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
  context.startActivity(intent);
};

/** The system "share" sheet with a text. */
exports.share = function (text, title) {
  var intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
  intent.setType('text/plain');
  intent.putExtra(android.content.Intent.EXTRA_TEXT, String(text));
  var chooser = android.content.Intent.createChooser(intent, title ? String(title) : null);
  chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
  context.startActivity(chooser);
};

/** One buzz of ms (40 by default), or a pattern: axiko.vibrate([pause, buzz, pause, buzz, …]). */
exports.vibrate = function (pattern) {
  var effect;
  if (Array.isArray(pattern)) {
    var timings = java.lang.reflect.Array.newInstance(java.lang.Long.TYPE, pattern.length);
    pattern.forEach(function (ms, i) { timings[i] = Math.max(0, Math.round(ms)); });
    effect = android.os.VibrationEffect.createWaveform(timings, -1);
  } else {
    effect = android.os.VibrationEffect.createOneShot(pattern || 40, -1);
  }
  context.getSystemService('vibrator').vibrate(effect);
};

/**
 * Rows of a content provider (calendar, contacts, SMS…) as plain objects:
 * axiko.rows('content://sms/inbox', ['address', 'body', 'date'], null, null, 'date desc', 20)
 */
exports.rows = function (uri, columns, where, args, order, limit) {
  var projection = null;
  if (columns) {
    projection = java.lang.reflect.Array.newInstance(java.lang.String, columns.length);
    columns.forEach(function (name, i) { projection[i] = name; });
  }
  var selectionArgs = null;
  if (args) {
    selectionArgs = java.lang.reflect.Array.newInstance(java.lang.String, args.length);
    args.forEach(function (value, i) { selectionArgs[i] = String(value); });
  }
  var cursor = context.getContentResolver().query(android.net.Uri.parse(String(uri)), projection, where || null, selectionArgs, order || null);
  var rows = [];
  if (!cursor) return rows;
  try {
    var count = cursor.getColumnCount();
    while (cursor.moveToNext() && (!limit || rows.length < limit)) {
      var row = {};
      for (var i = 0; i < count; i++) {
        var type = cursor.getType(i);
        row[String(cursor.getColumnName(i))] =
          type === 0 ? null : type === 1 ? Number(cursor.getLong(i)) : type === 2 ? cursor.getDouble(i)
            : type === 3 ? String(cursor.getString(i)) : '[blob]';
      }
      rows.push(row);
    }
  } finally {
    cursor.close();
  }
  return rows;
};

/** Settings of the app shared by all screens (strings): axiko.pref('key'), axiko.pref('key', 'value'). */
exports.pref = function (key, value) {
  if (arguments.length < 2) return native('pref.get', key);
  native('pref.set', key, value === null || value === undefined ? null : String(value));
  return value;
};

// ---- voice: recognition on the phone, speech through the model of the "tts" role ------------

exports.speech = {
  /** Listens for one utterance and returns its text ('' when nothing was said). */
  listen: function (maxSeconds) { return native('speech.listen', maxSeconds || 45); },
  /** Says the text aloud after whatever is being said now; returns at once. */
  say: function (text) { native('speech.say', String(text)); },
  stop: function () { native('speech.stop'); },
  /** The text of an audio file (wav, mp3, ogg, m4a...); with { segments: true } — [{ startMs, endMs, text }]. */
  transcribe: function (path, options) { return options ? native('speech.transcribe', path, options) : native('speech.transcribe', path); },
  /** The text spoken into an audio file: mode 'cloud' (the tts role) or 'local'; returns the file's path. */
  render: function (text, path, mode) { return native('speech.render', String(text), path, mode || null); }
};

// ---- other apps: the screen and the notifications (the owner switches these senses on in Settings) ----

exports.screen = {
  /** { app, package, outline } - what is on the screen; outline lines start with element numbers. */
  read: function () { return native('screen.read'); },
  /** The screen as a JPEG in base64. */
  shot: function () { return native('screen.shot'); },
  click: function (index) { return native('screen.click', index); },
  /** Puts text into the field with that number, or into the focused one. */
  type: function (text, index) { return native('screen.type', String(text), index === undefined ? null : index); },
  scroll: function (index, forward) { return native('screen.scroll', index, forward !== false); },
  tap: function (x, y) { return native('screen.tap', x, y); },
  swipe: function (x, y, x2, y2, ms) { return native('screen.swipe', x, y, x2, y2, ms || 300); },
  back: function () { return native('screen.global', 'back'); },
  home: function () { return native('screen.global', 'home'); },
  /** Starts an app by its name or package. */
  openApp: function (name) { return native('screen.openApp', String(name)); },
  /**
   * The number of the first element whose line of the outline has these words — its text, its
   * description or its "#id" — or -1. Reads the screen anew.
   */
  find: function (words) {
    var want = String(words).toLowerCase();
    var lines = String(native('screen.read').outline).split('\n');
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].toLowerCase().indexOf(want) < 0) continue;
      var number = /\[(\d+)\]/.exec(lines[i]);
      if (number) return Number(number[1]);
    }
    return -1;
  },
  /** Waits until the screen shows these words (up to waitMs, 6 s by default); returns the element's number. */
  waitFor: function (words, waitMs) {
    var until = Date.now() + (waitMs || 6000);
    while (true) {
      var index = exports.screen.find(words);
      if (index >= 0) return index;
      if (Date.now() > until) throw new Error('На экране не появилось «' + words + '»');
      sleep(400);
    }
  },
  /** Waits for an element with these words and presses it: the way to replay a shown action. */
  press: function (words, waitMs) { return native('screen.click', exports.screen.waitFor(words, waitMs)); },
  /** Waits for a field with these words (or takes the focused one) and puts the text into it. */
  fill: function (words, text, waitMs) {
    return native('screen.type', String(text), words ? exports.screen.waitFor(words, waitMs) : null);
  }
};

exports.notifications = {
  /** What is in the shade now: [{ time, app, package, title, text, ongoing, key, messages, … }]. */
  active: function () { return native('notices.active'); },
  /** The latest seen since the app started, newest first. */
  recent: function (limit) { return native('notices.recent', limit || 30); },
  dismiss: function (key) { return native('notices.dismiss', key); },
  /** Opens what a tap on it would open (a messenger's chat), also after it is gone; true when it did. */
  open: function (key) { return native('notices.open', key); },
  /** Answers through the notification's own "Reply" action (items with canReply). */
  reply: function (key, text) { return native('notices.reply', key, String(text)); }
};

// ---- the home-screen widget of a module ---------------------------------------------------------
// A module shows itself in a widget by exporting widget() from its main.js:
//   exports.widget = function () { return { title: 'Заметки', text: '3 записи\nКупить молоко', open: 'modules/notes/ui/index.html' }; };

exports.widget = {
  /** Redraws the widgets of that module (call it when the data they show has changed). */
  refresh: function (module) { native('widget.refresh', module || null); }
};

// ---- secrets: passwords and keys, typed by the owner into a system dialog, kept encrypted ----

exports.secret = {
  /** The value, or null when the owner has not given it yet. */
  /** Whether the secret is set. Enough for nearly everything: the value itself is not needed to use it. */
  has: function (name) { return native('secret.has', name) === true; },
  /**
   * The value of a module's own secret. Prefer not to hold it at all: name it in the request as
   * '{{secret:name}}' (address, header or body of axiko.http.request) and the shell puts it in.
   * Secrets of the shell (provider keys, tokens of servers and sign-ins) are not given.
   */
  get: function (name) { var value = native('secret.get', name); return value === undefined ? null : value; },
  /** Asks the owner to type it (never ask for secrets in the chat); true when saved. */
  ask: function (name, title, hint) { return native('secret.ask', name, title || 'Введите значение', hint || ''); },
  set: function (name, value) { native('secret.set', name, String(value)); },
  remove: function (name) { native('secret.remove', name); },
  names: function () { return native('secret.names'); }
};

// ---- the command line -------------------------------------------------------------------------

/**
 * Runs a shell command: axiko.shell('ls -la') → { code, out }.
 * With { privileged: true } it runs with the rights of ADB (needs Shizuku, see Settings).
 */
exports.shell = function (command, options) {
  options = options || {};
  return native('shell.run', String(command), Boolean(options.privileged), options.timeout || 60);
};

// ---- signing in to services (OAuth 2) -----------------------------------------------------------

exports.oauth = {
  /**
   * Opens the browser for the owner to sign in and waits; tokens are kept and renewed by the app.
   *   axiko.oauth.connect('google', { authUrl, tokenUrl, clientId, clientSecret, scope, extra })
   * The redirect address is http://127.0.0.1:<port>/callback — register the client as a
   * "desktop / installed app" at the provider.
   */
  connect: function (name, config) { return native('oauth.connect', name, config); },
  /** A valid access token: use it as "Authorization: Bearer ...". */
  token: function (name) { return native('oauth.token', name); },
  names: function () { return native('oauth.names'); }
};

// ---- MCP servers the owner connected in Settings ----------------------------------------------

exports.mcp = {
  servers: function () { return native('mcp.servers'); },
  tools: function (server) { return native('mcp.tools', server); },
  call: function (server, tool, args) { return native('mcp.call', server, tool, args || {}); }
};

// ---- the Linux environment (Ubuntu under PRoot; the owner installs it in Settings) ------------

/**
 * Runs a bash command there: axiko.linux('python3 -c "print(1)"') → { code, out }.
 * The modules' data folder is /axiko on that side.
 */
exports.linux = function (command, options) {
  return native('linux.run', String(command), (options || {}).timeout || 120);
};
/** Starts a long-running program (a server) under a name; see linux.log for what it prints. */
exports.linux.spawn = function (name, command) { return native('linux.spawn', name, String(command)); };
exports.linux.stop = function (name) { return native('linux.stop', name); };
exports.linux.alive = function (name) { return native('linux.alive', name); };
exports.linux.log = function (name, lines) { return native('linux.log', name, lines || 40); };
exports.linux.status = function () { return native('linux.status'); };

// Long-term memory shared by all agents: var memory = require('lib/memory');
// agent/memory.md holds the few things every conversation needs; everything else goes here
// and is found by search when it matters. Search is by words; when the owner assigns a model
// to the role "embed" in Settings, it is also by meaning.
var assi = require('lib/assi');

var db = assi.db('memory');
db.exec('create virtual table if not exists notes using fts4(text, tags, agent, time, tokenize=unicode61)');
db.exec('create table if not exists vectors (id integer primary key, vector text)');

function embed(texts) {
  try {
    return native('models.embed', texts);
  } catch (error) {
    return null;   // no model for meaning: words alone
  }
}

/** Remembers a fact; tags are free words that help to find it ("здоровье, врач"). Returns its id. */
exports.save = function (text, tags, agent) {
  db.exec('insert into notes (text, tags, agent, time) values (?, ?, ?, ?)', [String(text), tags || '', agent || '', Date.now()]);
  var id = db.lastId();
  var vectors = embed([String(text)]);
  if (vectors && vectors[0]) db.exec('insert into vectors (id, vector) values (?, ?)', [id, JSON.stringify(vectors[0])]);
  return id;
};

function words(query) {
  return String(query).toLowerCase().split(/[^a-zа-яё0-9]+/).filter(function (word) { return word.length > 1; });
}

function byWords(query, limit) {
  // Russian endings vary: match by the beginning of each word.
  var match = words(query).map(function (word) { return (word.length > 5 ? word.slice(0, word.length - 2) : word) + '*'; }).join(' OR ');
  if (!match) return [];
  return db.query('select rowid as id, text, tags, agent, time from notes where notes match ? order by time desc limit ' + Number(limit), [match]);
}

function cosine(a, b) {
  var dot = 0, na = 0, nb = 0;
  for (var i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
  return dot / (Math.sqrt(na) * Math.sqrt(nb) || 1);
}

function byMeaning(query, limit) {
  var stored = db.query('select id, vector from vectors');
  if (!stored.length) return [];
  var vectors = embed([String(query)]);
  if (!vectors || !vectors[0]) return [];
  var scored = stored.map(function (row) { return { id: row.id, score: cosine(vectors[0], JSON.parse(row.vector)) }; })
    .sort(function (a, b) { return b.score - a.score; }).slice(0, limit)
    .filter(function (item) { return item.score > 0.3; });
  if (!scored.length) return [];
  var rows = db.query('select rowid as id, text, tags, agent, time from notes where rowid in (' +
    scored.map(function (item) { return Number(item.id); }).join(',') + ')');
  return scored.map(function (item) { return rows.filter(function (row) { return row.id === item.id; })[0]; })
    .filter(function (row) { return row; });
}

/** What was remembered about this: [{ id, text, tags, agent, time }], the most relevant first. */
exports.search = function (query, limit) {
  limit = limit || 8;
  var found = byMeaning(query, limit);
  var seen = {};
  found.forEach(function (row) { seen[row.id] = true; });
  byWords(query, limit).forEach(function (row) { if (!seen[row.id]) found.push(row); });
  return found.slice(0, limit);
};

exports.recent = function (limit) {
  return db.query('select rowid as id, text, tags, agent, time from notes order by time desc limit ' + Number(limit || 20));
};

exports.remove = function (id) {
  db.exec('delete from notes where rowid = ?', [id]);
  db.exec('delete from vectors where id = ?', [id]);
};

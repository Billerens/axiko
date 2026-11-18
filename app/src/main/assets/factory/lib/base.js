// The owner's personal facts, one store for every module and agent: what was spent, drunk,
// lifted, slept, noted, planned. A module keeps its own working state wherever it likes, but
// what it learns about the owner's life it also records here — then a question can be answered
// across modules ("on which days did I drink less water and sleep worse?").
//
//   var base = require('lib/base');
//   base.add({ kind: 'expense', title: 'Кофе', amount: 250, unit: 'RUB', tags: ['еда'], source: 'money' });
//   base.find({ kind: 'expense', since: Date.now() - 7 * 864e5 });
//   base.sum({ kind: 'water', by: 'day', since: ... });      // [{period: '2026-11-10', count, total}]
//   base.kinds();                                             // what is recorded at all
//
// A record: kind (what it is: expense, water, workout, sleep, mood, note, event… — English,
// singular; look at kinds() before inventing a new one), at (when it happened, ms), title,
// amount and unit (when it is a quantity), tags, data (anything else, an object), source (the
// module that recorded it).
var axiko = require('lib/axiko');
var db = axiko.db('base');

db.exec('create table if not exists records (id integer primary key autoincrement, kind text not null, at integer not null, ' +
  'title text, amount real, unit text, tags text, data text, source text, created integer not null)');
db.exec('create index if not exists records_kind_at on records (kind, at)');

function row(saved) {
  return {
    id: saved.id, kind: saved.kind, at: saved.at, title: saved.title, amount: saved.amount, unit: saved.unit,
    tags: saved.tags ? saved.tags.split(',').filter(function (tag) { return tag; }) : [],
    data: saved.data ? JSON.parse(saved.data) : null, source: saved.source
  };
}

function tags(list) {
  if (!list) return null;
  return ',' + (Array.isArray(list) ? list : String(list).split(',')).map(function (tag) { return String(tag).trim().toLowerCase(); })
    .filter(function (tag) { return tag; }).join(',') + ',';
}

function time(value) {
  if (value === undefined || value === null || value === '') return null;
  if (typeof value === 'number') return value;
  var parsed = Date.parse(value);
  if (isNaN(parsed)) throw new Error('Не понимаю время: ' + value + ' (нужны миллисекунды или дата вида 2026-11-10T08:00)');
  return parsed;
}

/** Records a fact; returns its id. */
exports.add = function (record) {
  if (!record || !record.kind) throw new Error('У записи должен быть kind');
  db.exec('insert into records (kind, at, title, amount, unit, tags, data, source, created) values (?, ?, ?, ?, ?, ?, ?, ?, ?)', [
    String(record.kind).trim().toLowerCase(), time(record.at) || Date.now(), record.title === undefined ? null : record.title,
    record.amount === undefined || record.amount === null ? null : Number(record.amount), record.unit || null, tags(record.tags),
    record.data ? JSON.stringify(record.data) : null, record.source || null, Date.now()
  ]);
  return db.lastId();
};

exports.get = function (id) {
  var found = db.query('select * from records where id = ?', [id]);
  return found.length ? row(found[0]) : null;
};

/** Changes the given fields of a record. */
exports.update = function (id, fields) {
  var was = exports.get(id);
  if (!was) throw new Error('Нет записи ' + id);
  var next = {};
  Object.keys(was).forEach(function (key) { next[key] = fields[key] === undefined ? was[key] : fields[key]; });
  db.exec('update records set kind = ?, at = ?, title = ?, amount = ?, unit = ?, tags = ?, data = ? where id = ?', [
    next.kind, time(next.at), next.title, next.amount === null ? null : Number(next.amount), next.unit, tags(next.tags),
    next.data ? JSON.stringify(next.data) : null, id
  ]);
};

exports.remove = function (id) { db.exec('delete from records where id = ?', [id]); };

function where(query) {
  var parts = [];
  var args = [];
  var kinds = query.kinds || (query.kind ? [query.kind] : null);
  if (kinds && kinds.length) {
    parts.push('kind in (' + kinds.map(function () { return '?'; }).join(', ') + ')');
    kinds.forEach(function (kind) { args.push(String(kind).toLowerCase()); });
  }
  if (query.since !== undefined && query.since !== null && query.since !== '') { parts.push('at >= ?'); args.push(time(query.since)); }
  if (query.until !== undefined && query.until !== null && query.until !== '') { parts.push('at < ?'); args.push(time(query.until)); }
  if (query.tag) { parts.push('tags like ?'); args.push('%,' + String(query.tag).toLowerCase() + ',%'); }
  if (query.source) { parts.push('source = ?'); args.push(query.source); }
  if (query.text) {
    parts.push('(title like ? or data like ? or tags like ?)');
    var like = '%' + query.text + '%';
    args.push(like, like, like);
  }
  return { sql: parts.length ? ' where ' + parts.join(' and ') : '', args: args };
}

/** Records by kind, time, tag, text: {kind | kinds, since, until, tag, text, source, limit, oldestFirst}. */
exports.find = function (query) {
  query = query || {};
  var filter = where(query);
  var limit = Math.min(Number(query.limit) || 100, 1000);
  return db.query('select * from records' + filter.sql + ' order by at ' + (query.oldestFirst ? 'asc' : 'desc') + ' limit ' + limit, filter.args).map(row);
};

/** What is recorded: [{kind, count, first, last, unit}]. */
exports.kinds = function () {
  return db.query('select kind, count(*) as count, min(at) as first, max(at) as last, max(unit) as unit from records group by kind order by count desc');
};

/** Counts and totals by period: {kind, since, until, tag, by: 'day' | 'week' | 'month'} -> [{period, count, total}]. */
exports.sum = function (query) {
  query = query || {};
  var filter = where(query);
  var format = query.by === 'month' ? '%Y-%m' : query.by === 'week' ? '%Y-W%W' : '%Y-%m-%d';
  return db.query("select strftime('" + format + "', at / 1000, 'unixepoch', 'localtime') as period, count(*) as count, " +
    'coalesce(sum(amount), 0) as total from records' + filter.sql + ' group by period order by period', filter.args);
};

/** Any question to the store as a read-only SQL select over the table `records`. */
exports.sql = function (select, args) {
  var text = String(select).trim().replace(/;+\s*$/, '');
  if (!/^(select|with)\b/i.test(text) || /;/.test(text)) throw new Error('Только один запрос select');
  return db.query('select * from (' + text + ') limit 500', args || []);
};

exports.columns = 'records(id, kind, at /* ms */, title, amount, unit, tags /* ,a,b, */, data /* JSON */, source, created)';

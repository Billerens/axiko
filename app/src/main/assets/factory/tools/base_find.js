// Looks into the shared store of the owner's personal facts (lib/base.js).
var base = require('lib/base');

exports.name = 'base_find';
exports.description = 'Посмотреть в общую базу личных данных владельца: траты, вода, тренировки, сон, заметки — всё, что записали модули и вы. ' +
  'Без условий показывает, какие виды записей есть. С by — считает по дням, неделям или месяцам. ' +
  'Для вопросов поперёк видов («в какие дни…») есть sql: один select по таблице ' + base.columns + '.';
exports.parameters = {
  type: 'object',
  properties: {
    kind: { type: 'string', description: 'Вид записей' },
    text: { type: 'string', description: 'Слова, которые должны встречаться' },
    tag: { type: 'string' },
    since: { type: 'string', description: 'С какого времени: 2026-11-01' },
    until: { type: 'string', description: 'До какого времени' },
    by: { type: 'string', enum: ['day', 'week', 'month'], description: 'Не записи, а счёт и сумма по периодам' },
    limit: { type: 'integer', description: 'Сколько записей, по умолчанию 50' },
    sql: { type: 'string', description: 'Свой запрос select вместо остальных условий. Время at — в миллисекундах: date(at / 1000, \'unixepoch\', \'localtime\')' }
  }
};

function day(ms) {
  var d = new Date(ms);
  var two = function (n) { return (n < 10 ? '0' : '') + n; };
  return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate()) + ' ' + two(d.getHours()) + ':' + two(d.getMinutes());
}

exports.run = function (args) {
  if (args.sql) {
    var rows = base.sql(args.sql);
    return rows.length ? JSON.stringify(rows, null, 1) : 'Запрос ничего не вернул.';
  }
  if (!args.kind && !args.text && !args.tag && !args.since && !args.until) {
    var kinds = base.kinds();
    if (!kinds.length) return 'База пока пуста.';
    return 'Виды записей:\n' + kinds.map(function (kind) {
      return kind.kind + ': ' + kind.count + ', с ' + day(kind.first) + ' по ' + day(kind.last) + (kind.unit ? ', единица ' + kind.unit : '');
    }).join('\n');
  }
  if (args.by) {
    var sums = base.sum(args);
    return sums.length ? sums.map(function (item) { return item.period + ': записей ' + item.count + ', сумма ' + item.total; }).join('\n') : 'Записей нет.';
  }
  args.limit = args.limit || 50;
  var found = base.find(args);
  if (!found.length) return 'Записей нет.';
  return found.map(function (item) {
    return '№' + item.id + ' ' + day(item.at) + ' ' + item.kind + ': ' + (item.title || '') +
      (item.amount !== null ? ' ' + item.amount + (item.unit ? ' ' + item.unit : '') : '') +
      (item.tags.length ? ' [' + item.tags.join(', ') + ']' : '') + (item.data ? ' ' + JSON.stringify(item.data) : '');
  }).join('\n');
};

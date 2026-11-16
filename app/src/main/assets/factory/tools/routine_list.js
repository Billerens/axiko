// Lists the routines (lib/routines.js) with what each last came to.
var routines = require('lib/routines');

exports.name = 'routine_list';
exports.description = 'Рутины владельца: что и когда делается само, включено ли, чем закончился последний запуск.';
exports.parameters = { type: 'object', properties: {} };

exports.run = function () {
  var list = routines.list();
  if (!list.length) return 'Рутин пока нет.';
  return list.map(function (item) {
    var last = item.last ? ' Последний раз: ' + new Date(item.last.at).toLocaleString() + (item.last.ok ? '' : ' (ошибка)') + ' — ' + item.last.text.slice(0, 160) : '';
    return item.id + ' · ' + item.title + ' · ' + routines.describe(item.when) + ' · ' + (item.enabled ? 'включена' : 'выключена') +
      '\n  что делать: ' + item.do + (last ? '\n ' + last : '');
  }).join('\n');
};

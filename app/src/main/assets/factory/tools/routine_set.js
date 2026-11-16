// Creates, changes or removes a routine (lib/routines.js): something an agent does by itself.
var routines = require('lib/routines');

exports.name = 'routine_set';
exports.description = 'Завести, изменить или убрать рутину — то, что агент делает сам: по расписанию или когда что-то происходит на телефоне. ' +
  'Владелец говорит словами («каждое утро в 8 — сводка дня», «когда приходит уведомление от банка — запиши трату»), вы переводите это в when и do. ' +
  'В do пишите задание так, как дали бы его агенту в чате, со всем нужным: что сделать, откуда взять, что считать результатом. ' +
  'Без id заводится новая; с id меняется существующая (routine_list покажет их).';
exports.parameters = {
  type: 'object',
  properties: {
    id: { type: 'string', description: 'Какую рутину изменить; не указывайте для новой' },
    title: { type: 'string', description: 'Короткое название' },
    when: {
      type: 'object',
      description: 'Когда: {kind:"daily", at:"08:00", days:[1..7]} — в это время (days: 1 — понедельник, без days — каждый день); ' +
        '{kind:"every", minutes:60}; {kind:"notification", app:"слова из названия приложения", contains:"слова из текста"}; ' +
        '{kind:"event", name:"power|headset|network|screen", state:"on|off|unlocked"}',
      properties: {
        kind: { type: 'string', enum: ['daily', 'every', 'notification', 'event'] },
        at: { type: 'string' }, days: { type: 'array', items: { type: 'integer' } }, minutes: { type: 'integer' },
        app: { type: 'string' }, contains: { type: 'string' }, name: { type: 'string' }, state: { type: 'string' }
      },
      required: ['kind']
    },
    do: { type: 'string', description: 'Что сделать, когда сработает' },
    agent: { type: 'string', description: 'Кто выполняет; по умолчанию основной агент' },
    enabled: { type: 'boolean', description: 'false — выключить, не удаляя' },
    remove: { type: 'boolean', description: 'true — удалить рутину с этим id' }
  }
};

exports.run = function (args) {
  if (args.remove) {
    if (!args.id) throw new Error('Чтобы удалить, нужен id');
    routines.remove(args.id);
    return 'Рутина удалена.';
  }
  if (args.id && !args.title && !args.when && !args.do && args.enabled !== undefined) {
    routines.enable(args.id, args.enabled);
    return args.enabled ? 'Рутина включена.' : 'Рутина выключена.';
  }
  if (args.id) {
    var was = routines.list().filter(function (item) { return item.id === args.id; })[0];
    if (!was) throw new Error('Нет рутины ' + args.id);
    args.title = args.title || was.title;
    args.when = args.when || was.when;
    args.do = args.do || was.do;
  }
  var saved = routines.set(args);
  return 'Рутина «' + saved.title + '» (' + saved.id + '): ' + routines.describe(saved.when) + '. ' + (saved.enabled ? 'Включена.' : 'Выключена.');
};

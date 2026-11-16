// Records a fact about the owner's life in the shared store (lib/base.js).
var base = require('lib/base');

exports.name = 'base_add';
exports.description = 'Записать факт о жизни владельца в общую базу: трата, тренировка, сон, настроение, заметка, событие. ' +
  'Когда он сообщает такое в разговоре («потратил 500 на такси», «лёг в час ночи») — запишите, даже если модуля для этого нет. ' +
  'Перед новым видом записи посмотрите, какие уже есть (base_find без условий показывает виды).';
exports.parameters = {
  type: 'object',
  properties: {
    kind: { type: 'string', description: 'Что это: expense, water, workout, sleep, mood, note, event… По-английски, в единственном числе' },
    title: { type: 'string', description: 'Коротко, что это' },
    amount: { type: 'number', description: 'Количество, если это величина' },
    unit: { type: 'string', description: 'Единица: RUB, ml, min, kg…' },
    at: { type: 'string', description: 'Когда это было: 2026-11-10T08:00; по умолчанию сейчас' },
    tags: { type: 'array', items: { type: 'string' } },
    data: { type: 'object', description: 'Остальное, что стоит сохранить' }
  },
  required: ['kind', 'title']
};

exports.run = function (args) {
  args.source = args.source || 'chat';
  var id = base.add(args);
  return 'Записано, №' + id + ': ' + args.kind + ' — ' + args.title + (args.amount !== undefined ? ' ' + args.amount + (args.unit ? ' ' + args.unit : '') : '');
};

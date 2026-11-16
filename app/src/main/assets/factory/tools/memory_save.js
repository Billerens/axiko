// Lets an agent put a fact into the long-term memory (lib/memory.js).
exports.name = 'memory_save';
exports.description = 'Запомнить надолго факт о владельце, его делах или договорённостях. Пишите полной фразой, понятной без контекста разговора.';
exports.parameters = {
  type: 'object',
  properties: {
    text: { type: 'string', description: 'Что запомнить' },
    tags: { type: 'string', description: 'Слова для поиска через запятую' }
  },
  required: ['text']
};

exports.run = function (args) {
  return 'Запомнено, номер ' + require('lib/memory').save(args.text, args.tags || '');
};

// Lets an agent look into the long-term memory (lib/memory.js).
exports.name = 'memory_search';
exports.description = 'Найти в долгой памяти то, что запоминалось раньше: о владельце, его делах, прошлых договорённостях. Ищите, прежде чем переспрашивать.';
exports.parameters = {
  type: 'object',
  properties: {
    query: { type: 'string', description: 'О чём вспомнить' },
    limit: { type: 'integer', description: 'Сколько записей, по умолчанию 8' }
  },
  required: ['query']
};

exports.run = function (args) {
  var found = require('lib/memory').search(args.query, args.limit || 8);
  if (!found.length) return 'В памяти об этом ничего нет.';
  return found.map(function (row) {
    var day = new Date(row.time).toISOString().slice(0, 10);
    return '[' + row.id + '] ' + day + (row.tags ? ' (' + row.tags + ')' : '') + ': ' + row.text;
  }).join('\n');
};

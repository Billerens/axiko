// Runs a module's own checks (modules/<id>/checks.js; see lib/check.js for how they are written).
var check = require('lib/check');

exports.name = 'module_check';
exports.description = 'Запустить проверки модуля из его checks.js: логику и экраны. Возвращает, что прошло и что нет. ' +
  'Запускайте после каждой правки модуля и не отчитывайтесь владельцу, пока что-то не проходит.';
exports.parameters = {
  type: 'object',
  properties: { module: { type: 'string', description: 'Идентификатор модуля — имя его папки в modules/' } },
  required: ['module']
};

exports.run = function (args) {
  return check.report(check.run(args.module));
};
